"""Opt-in real Xray TCP/UDP acceptance. Never downloads or executes a binary implicitly.

XRAY_TEST_BINARY=/absolute/path/to/xray pytest tests/test_native_tunnel_traffic.py -q
"""

import asyncio
import json
import os
import socket
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from app.operation.tunnel import key
from app.tunnel.compiler import compose

BINARY = os.getenv("XRAY_TEST_BINARY")
pytestmark = pytest.mark.skipif(not BINARY, reason="Set XRAY_TEST_BINARY to a verified Xray binary")
UUID = "64a0f9d1-3ad3-4026-aa43-2ac860915dee"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class EchoDatagrams(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        self.transport.sendto(data, address)


@asynccontextmanager
async def xray(tmp_path, name, config, port):
    config["log"] = {"loglevel": "debug", "error": str(tmp_path / f"{name}.log")}
    path = tmp_path / f"{name}.json"
    path.write_text(json.dumps(config))
    process = await asyncio.create_subprocess_exec(
        BINARY, "run", "-c", str(path), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        for _ in range(150):
            if process.returncode is not None:
                raise AssertionError((await process.stdout.read()).decode())
            try:
                _, writer = await asyncio.open_connection("127.0.0.1", port)
                writer.close()
                await writer.wait_closed()
                break
            except OSError:
                await asyncio.sleep(0.02)
        else:
            raise AssertionError("Xray listener did not start")
        yield process
    finally:
        if process.returncode is None:
            process.terminate()
            await asyncio.wait_for(process.wait(), timeout=5)


async def tcp_query(port, payload):
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    try:
        writer.write(payload)
        await writer.drain()
        return await asyncio.wait_for(reader.readexactly(len(payload)), timeout=3)
    finally:
        writer.close()
        await writer.wait_closed()


async def udp_query(port, payload):
    loop = asyncio.get_running_loop()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setblocking(False)
        await loop.sock_sendto(sock, payload, ("127.0.0.1", port))
        return (await asyncio.wait_for(loop.sock_recvfrom(sock, 65535), timeout=3))[0]


@pytest.mark.parametrize("domain_mode", [False, True])
async def test_real_encrypted_native_tcp_udp_and_fail_closed(tmp_path, domain_mode):
    server_port, source_port, exit_port, client_port = [free_port() for _ in range(4)]

    async def echo(reader, writer):
        try:
            while data := await reader.read(65536):
                writer.write(data)
                await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    echo_server = await asyncio.start_server(echo, "127.0.0.1", server_port)
    udp_echo, _ = await asyncio.get_running_loop().create_datagram_endpoint(
        EchoDatagrams, local_addr=("127.0.0.1", server_port)
    )
    # A deterministic local domain keeps this test independent of Internet DNS.
    destination = "game.example.test" if domain_mode else "127.0.0.1"
    row = SimpleNamespace(
        id=1,
        enabled=True,
        source_node_id=1,
        exit_node_id=2,
        port=exit_port,
        shared_key=key(),
        inbound_tags=["users"],
        domains=["example.test"] if domain_mode else [],
    )
    base = {
        "inbounds": [
            {
                "tag": "users",
                "listen": "127.0.0.1",
                "port": source_port,
                "protocol": "vless",
                "settings": {"decryption": "none", "clients": [{"id": UUID}]},
            }
        ],
        # A direct-route control must also be able to reach the echo destination.
        # This exception exists only in the local fixture, never the compiler.
        "outbounds": [
            {
                "tag": "direct",
                "protocol": "freedom",
                "settings": {
                    "domainStrategy": "ForceIP",
                    "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}],
                },
            }
        ],
        "dns": {"hosts": {"game.example.test": "127.0.0.1"}},
    }
    source, _ = compose(base, 1, [row], {2: SimpleNamespace(address="127.0.0.1")})
    exit_base = {
        "inbounds": [],
        "outbounds": [{"tag": "direct", "protocol": "blackhole"}],
        "dns": {"hosts": {"game.example.test": "127.0.0.1"}},
    }
    exit_config, _ = compose(exit_base, 2, [row], {})
    # Only the isolated echo test permits loopback destinations; production inherits
    # Xray security defaults. ForceIP uses the deterministic test DNS hosts map.
    exit_config["outbounds"][-1]["settings"] = {
        "domainStrategy": "ForceIP",
        "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}],
    }
    # Same compiled production listener, restricted to loopback for this test only.
    exit_config["inbounds"][0]["listen"] = "127.0.0.1"
    client = {
        "inbounds": [
            {
                "tag": "test",
                "listen": "127.0.0.1",
                "port": client_port,
                "protocol": "dokodemo-door",
                "settings": {"address": destination, "port": server_port, "network": "tcp,udp"},
            }
        ],
        "outbounds": [
            {
                "tag": "source",
                "protocol": "vless",
                "settings": {
                    "vnext": [
                        {"address": "127.0.0.1", "port": source_port, "users": [{"id": UUID, "encryption": "none"}]}
                    ]
                },
            }
        ],
    }
    try:
        async with (
            xray(tmp_path, "direct-source-control", base, source_port),
            xray(tmp_path, "direct-client-control", client, client_port),
        ):
            assert await tcp_query(client_port, b"direct-control") == b"direct-control"
            assert await udp_query(client_port, b"direct-control") == b"direct-control"
        async with (
            xray(tmp_path, "exit", exit_config, exit_port) as exit_process,
            xray(tmp_path, "source", source, source_port),
            xray(tmp_path, "client", client, client_port),
        ):
            assert await tcp_query(client_port, b"native-tcp-" * 1000) == b"native-tcp-" * 1000
            for size in (32, 512, 1200):
                payload = os.urandom(size)
                assert await udp_query(client_port, payload) == payload
            # Same destination is reachable directly from source. After exit dies,
            # forwarding must fail, proving no silent freedom fallback occurs.
            exit_process.terminate()
            await exit_process.wait()
            with pytest.raises((TimeoutError, asyncio.IncompleteReadError, ConnectionError)):
                await tcp_query(client_port, b"must-not-leak")
            with pytest.raises(TimeoutError):
                await udp_query(client_port, b"must-not-leak")
    finally:
        echo_server.close()
        await echo_server.wait_closed()
        udp_echo.close()
