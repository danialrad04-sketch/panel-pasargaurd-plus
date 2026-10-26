"""Opt-in real production bundle, migration, identity and persistent-volume checks."""

import asyncio
import os
import re
import socket
import ssl
import subprocess
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from node.prepare_node import prepare
from tests.test_node_bootstrap import arguments

IMAGE = os.getenv("PANEL_DOCKER_IMAGE")
ROOT = Path(__file__).resolve().parents[1]
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set PANEL_DOCKER_IMAGE to a locally built fork image")


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.STDOUT).strip()


def available_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


async def wait_ready(client):
    for _ in range(180):
        try:
            if (await client.get("/health")).is_success:
                return
        except httpx.TransportError:
            pass
        await asyncio.sleep(0.5)
    pytest.fail("Production panel did not become healthy")


async def test_production_install_bundle_and_persistent_database(tmp_path):
    name = "pg-panel-test-" + uuid4().hex[:10]
    state = tmp_path / "state"
    state.mkdir()
    cert, key = tmp_path / "cert.pem", tmp_path / "key.pem"
    await asyncio.to_thread(
        subprocess.run,
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=local production fixture",
            "-addext",
            "subjectAltName=IP:127.0.0.1",
            "-keyout",
            str(key),
            "-out",
            str(cert),
        ],
        check=True,
        capture_output=True,
    )
    port = available_port()
    origin = f"https://127.0.0.1:{port}"
    command = [
        "run",
        "-d",
        "--name",
        name,
        "-p",
        f"127.0.0.1:{port}:8000",
        "-v",
        f"{state}:/var/lib/pasarguard",
        "-v",
        f"{tmp_path}:/test:ro",
        "-e",
        "SQLALCHEMY_DATABASE_URL=sqlite+aiosqlite:////var/lib/pasarguard/db.sqlite3",
        "-e",
        "UVICORN_SSL_CERTFILE=/test/cert.pem",
        "-e",
        "UVICORN_SSL_KEYFILE=/test/key.pem",
        "-e",
        "UVICORN_SSL_CA_TYPE=private",
        "-e",
        "DEBUG=false",
        IMAGE,
    ]
    docker(*command)
    try:
        async with httpx.AsyncClient(
            base_url=origin, verify=ssl.create_default_context(cafile=str(cert)), trust_env=False
        ) as client:
            await wait_ready(client)
            output = docker("exec", name, "python", "-m", "cli.main", "generate-temp-key")
            match = re.search(r"Temp key:\s*([\w-]+)", output)
            assert match, "Temp-key CLI returned no key"
            password = "READY-Lab-42!" + uuid4().hex
            created = await client.post(
                "/api/setup/owner", json={"key": match[1], "username": "ready-owner", "password": password}
            )
            assert created.status_code == 201, created.text

            async def login():
                response = await client.post("/api/admin/token", data={"username": "ready-owner", "password": password})
                assert response.status_code == 200, response.text
                return response.json()["access_token"]

            token = await login()
            for path in ("/api/tunnels", "/api/dns/runtime"):
                response = await client.get(path, headers={"Authorization": f"Bearer {token}"})
                assert response.status_code == 200, (path, response.text)
            html = await client.get("/dashboard/")
            assert html.status_code == 200 and "statics/" in html.text
            assert "no-store" in html.headers["cache-control"]
            docker("exec", name, "/code/healthcheck.sh")
            docker("exec", name, "unbound-checkconf", "/code/deploy/native-dns/unbound.container.conf")
            args = arguments(tmp_path / "identity")
            args.name = "Production test node"
            prepare(args)
            browser = await asyncio.create_subprocess_exec(
                "node",
                str(ROOT / "dashboard/scripts/test-production-panel.mjs"),
                cwd=ROOT,
                env={
                    **os.environ,
                    "PANEL_TEST_URL": origin,
                    "PANEL_TEST_TOKEN": token,
                    "PANEL_TEST_REGISTRATION": str(args.registration_file),
                },
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(browser.communicate(), 90)
            assert browser.returncode == 0, (stdout.decode(), stderr.decode())
            docker("rm", "-f", name)
            docker(*command)
            await wait_ready(client)
            await login()
            assert (state / "db.sqlite3").exists()
    finally:
        docker("rm", "-f", name)


def test_bundled_resolver_udp_and_tcp():
    # localhost is answered internally. This checks daemon startup and wire transports,
    # not public recursion, ISP access or gaming latency.
    script = """
import socket, subprocess, time
import dns.message, dns.query
p = subprocess.Popen(['unbound', '-d', '-c', '/code/deploy/native-dns/unbound.container.conf'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    q = dns.message.make_query('localhost.', 'A')
    for i in range(50):
        try:
            result = dns.query.udp(q, '127.0.0.1', port=5335, timeout=.2)
            break
        except Exception:
            time.sleep(.1)
    else:
        raise RuntimeError('Unbound did not start')
    assert '127.0.0.1' in result.to_text()
    assert '127.0.0.1' in dns.query.tcp(q, '127.0.0.1', port=5335, timeout=2).to_text()
finally:
    p.terminate()
    p.wait(timeout=5)
"""
    docker("run", "--rm", "--entrypoint", "python", IMAGE, "-c", script)
