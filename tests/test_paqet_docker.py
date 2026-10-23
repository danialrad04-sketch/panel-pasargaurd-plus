"""Opt-in: actual native API -> official fork nodes -> Paqet raw TCP -> Xray.

Build node/Dockerfile.plus first. Tests never pull images or contact a VPS.
PAQET_DOCKER_IMAGE=pasarguard-plus-node:paqet PAQET_GO_BINARY=/path/to/go
XRAY_TEST_BINARY=/path/to/xray pytest tests/test_paqet_docker.py -q
"""

import asyncio
import json
import os
import socket
import subprocess
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.core.manager import core_manager
from app.core.xray import XRayConfig
from app.db.base import Base, get_db
from app.db.models import (
    CoreConfig,
    Group,
    Node,
    NodeConnectionType,
    NodeStatus,
    ProxyInbound,
    TunnelDeployment,
    User,
)
from app.models.admin import AdminDetails, AdminRoleData
from app.node import node_manager
from app.operation import tunnel as operation
from app.routers import tunnel as api
from app.routers.authentication import get_current
from tests.test_native_tunnel_traffic import tcp_query, udp_query, xray

IMAGE = os.getenv("PAQET_DOCKER_IMAGE")
pytestmark = pytest.mark.skipif(
    not IMAGE or not os.getenv("XRAY_TEST_BINARY") or not os.getenv("PAQET_GO_BINARY"),
    reason="Opt-in Docker/Go/Xray integration",
)
ROOT = Path(__file__).resolve().parents[1]
UUID = "64a0f9d1-3ad3-4026-aa43-2ac860915dee"


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.STDOUT).strip()


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


async def test_actual_native_paqet_nodes_tcp_udp_and_lifecycle(tmp_path, monkeypatch):
    name = "pg-paqet-test-" + uuid4().hex[:8]
    names = [name + "-source", name + "-exit"]
    await asyncio.to_thread(
        subprocess.run,
        [
            os.environ["PAQET_GO_BINARY"],
            "build",
            "-o",
            str(tmp_path / "echo"),
            str(ROOT / "node/testdata/paqet-echo/main.go"),
        ],
        check=True,
        capture_output=True,
        env={**os.environ, "CGO_ENABLED": "0"},
    )
    await asyncio.to_thread(
        subprocess.run,
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "ec",
            "-pkeyopt",
            "ec_paramgen_curve:P-256",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=local-native-node",
            "-addext",
            "subjectAltName=IP:127.0.0.1",
            "-keyout",
            str(tmp_path / "key.pem"),
            "-out",
            str(tmp_path / "cert.pem"),
        ],
        check=True,
        capture_output=True,
    )
    # Let Docker select a free subnet rather than reusing a host or VPN range.
    docker("network", "create", name)
    manager_ports = [port(), port()]
    engine_ports = [port(), port()]
    source_port = port()
    client_port = port()
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/native.db")
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    api_key = str(uuid4())
    containers = []
    try:
        for index, container in enumerate(names):
            args = [
                "run",
                "-d",
                "--name",
                container,
                "--network",
                name,
                "--cap-add",
                "NET_ADMIN",
                "--cap-add",
                "NET_RAW",
                "--mount",
                f"type=bind,src={tmp_path},dst=/fixture,readonly",
                "-p",
                f"127.0.0.1:{manager_ports[index]}:62050",
                "-p",
                f"127.0.0.1:{engine_ports[index]}:62052",
                "-e",
                f"API_KEY={api_key}",
                "-e",
                "SSL_CERT_FILE=/fixture/cert.pem",
                "-e",
                "SSL_KEY_FILE=/fixture/key.pem",
            ]
            if index == 0:
                args += ["-p", f"127.0.0.1:{source_port}:11080"]
            args.append(IMAGE)
            docker(*args)
            containers.append(container)
        ips = [
            next(iter(json.loads(docker("inspect", n))[0]["NetworkSettings"]["Networks"].values()))["IPAddress"]
            for n in names
        ]
        docker("exec", "-d", names[1], "/fixture/echo")
        async with engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        base = {
            "log": {"loglevel": "debug", "error": "/var/lib/pg-node/xray-test.log"},
            "inbounds": [
                {
                    "tag": "users",
                    "listen": "0.0.0.0",
                    "port": 11080,
                    "protocol": "vless",
                    "settings": {"decryption": "none", "clients": []},
                }
            ],
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
            "policy": {"levels": {"0": {"statsUserUplink": True, "statsUserDownlink": True}}},
        }
        async with sessions() as db:
            core = CoreConfig(name="Native Paqet fixture", config=base)
            db.add(core)
            await db.flush()
            inbound = ProxyInbound(tag="users")
            group = Group(name="native", inbounds=[inbound])
            user = User(username="native-user", proxy_settings={"vless": {"id": UUID}})
            user.groups = [group]
            db.add_all([inbound, group, user, TunnelDeployment(id=1)])
            for i in range(2):
                db.add(
                    Node(
                        name=names[i],
                        address="127.0.0.1",
                        port=manager_ports[i],
                        api_port=62051,
                        tunnel_api_port=engine_ports[i],
                        server_ca=(tmp_path / "cert.pem").read_text(),
                        api_key=api_key,
                        core_config_id=core.id,
                        status=NodeStatus.connected,
                        connection_type=NodeConnectionType.grpc,
                        default_timeout=30,
                        internal_timeout=30,
                    )
                )
            await db.commit()
        # Xray's production freedom defaults block loopback. Only this isolated
        # fixture may reach its local echo server; leave generated production rules intact.
        from app.tunnel.compiler import compose as native_compose

        def fixture_compose(*args, **kwargs):
            config, excluded = native_compose(*args, **kwargs)
            for outbound in config.get("outbounds", []):
                if outbound["protocol"] == "freedom":
                    outbound.setdefault("settings", {})["finalRules"] = [{"action": "allow", "ip": ["127.0.0.0/8"]}]
            return config, excluded

        monkeypatch.setattr("app.tunnel.runtime.compose", fixture_compose)
        monkeypatch.setattr(core_manager, "_cores", {1: XRayConfig(base)})
        managed = XRayConfig(base)
        monkeypatch.setattr(core_manager, "_inbounds", list(managed.inbounds_by_tag))
        monkeypatch.setattr(core_manager, "_inbounds_by_tag", managed.inbounds_by_tag)
        from app.core.hosts import host_manager

        monkeypatch.setattr(host_manager, "_hosts", {})
        monkeypatch.setattr(host_manager, "_kv", None)
        monkeypatch.setattr(host_manager, "_add_hosts_impl", host_manager._add_hosts_local)
        await host_manager._reset_cache()

        monkeypatch.setattr("app.tunnel.runtime.GetDB", sessions)
        monkeypatch.setattr("app.operation.node.notification.connect_node", AsyncMock())
        monkeypatch.setattr("app.operation.node.notification.error_node", AsyncMock())
        original_deploy = operation.deploy

        async def deploy(actor):
            return await original_deploy(actor, session_factory=sessions)

        monkeypatch.setattr(operation, "deploy", deploy)
        app = FastAPI()
        app.include_router(api.router)

        async def database():
            async with sessions() as db:
                yield db

        app.dependency_overrides[get_db] = database
        app.dependency_overrides[get_current] = lambda: AdminDetails(
            username="owner", role=AdminRoleData(is_owner=True)
        )
        async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as client:
            for i in range(2):
                for attempt in range(100):
                    result = await client.get(f"/api/tunnels/nodes/{i + 1}/paqet")
                    if result.status_code == 200:
                        break
                    await asyncio.sleep(0.1)
                if result.status_code != 200:
                    from app.paqet.client import request as direct_request

                    async with sessions() as db:
                        node = await db.get(Node, i + 1)
                        try:
                            await direct_request(node, "GET", "capabilities")
                        except Exception as error:
                            print("Native Paqet capability cause:", type(error).__name__, repr(error.__cause__))
                assert result.status_code == 200, result.text
                assert result.json()["installed"] and result.json()["contract"] == 1
            create = await client.post(
                "/api/tunnels",
                json={
                    "name": "Native actual Paqet",
                    "transport": "paqet",
                    "source_node_id": 1,
                    "exit_node_id": 2,
                    "inbound_tags": ["users"],
                    "port": 40900,
                    "enabled": True,
                    "paqet_options": {
                        "log_level": "debug",
                        "client_address": ips[0],
                        "server_address": ips[1],
                        "allow_unmetered": True,
                        "socks5": [
                            {"listen": "127.0.0.1:11091", "username": "fixture-user", "password": "fixture-password"}
                        ],
                    },
                },
            )
            assert create.status_code == 201, create.text
            item = create.json()
            link_id = item["id"]
            engine_id = item["engine_id"]
            applied = await client.post("/api/tunnels/apply")
            assert applied.status_code == 200 and applied.json()["ok"], applied.text
            state = await client.get(f"/api/tunnels/{link_id}/paqet/status")
            assert state.json()["source"]["state"]["running"] and state.json()["exit"]["state"]["running"], state.text
            # Publish the actual Iran/source user address through the native host API.
            from app.db.crud.host import create_host
            from app.models.host import CreateHost

            async with sessions() as db:
                template = await create_host(
                    db, CreateHost(remark="Native template", address={ips[1]}, inbound_tag="users", priority=0)
                )
            published = await client.post(
                f"/api/tunnels/{link_id}/hosts",
                json={
                    "expected_revision": item["revision"],
                    "template_host_id": template.id,
                    "address": ips[0],
                },
            )
            assert published.status_code == 200, published.text
            ingress = published.json()
            assert ingress["address"] == [ips[0]] and ingress["port"] == 11080
            from types import SimpleNamespace

            from app.db.models import UserStatus
            from app.models.proxy import ProxyTable
            from app.subscription.links import StandardLinks
            from app.subscription.share import process_inbounds_and_tags

            subscription_user = SimpleNamespace(
                id=1, status=UserStatus.active, inbounds=["users"], proxy_settings=ProxyTable(vless={"id": UUID})
            )
            links = await process_inbounds_and_tags(subscription_user, {}, StandardLinks(), {})
            assert f"@{ips[0]}:11080" in links and ":40900" not in links
            local_client = {
                "inbounds": [
                    {
                        "tag": "test",
                        "listen": "127.0.0.1",
                        "port": client_port,
                        "protocol": "dokodemo-door",
                        "settings": {"address": "127.0.0.1", "port": 11190, "network": "tcp,udp"},
                    }
                ],
                "outbounds": [
                    {
                        "tag": "source",
                        "protocol": "vless",
                        "settings": {
                            "vnext": [
                                {
                                    "address": ingress["address"][0],
                                    "port": ingress["port"],
                                    "users": [{"id": UUID, "encryption": "none"}],
                                }
                            ]
                        },
                    }
                ],
            }
            async with xray(tmp_path, "local-client", local_client, client_port):
                assert await tcp_query(client_port, b"native-paqet-" * 5000) == b"native-paqet-" * 5000
                for size in (32, 512, 1200):
                    payload = os.urandom(size)
                    assert await udp_query(client_port, payload) == payload
                assert "passed" in docker("exec", names[0], "/fixture/echo", "socks")
                from PasarGuardNodeBridge.common import service_pb2 as service

                bridge = await node_manager.get_node(1)
                stats = await bridge.get_stats(service.UserStat, reset=False, name="1")
                assert sum(row.value for row in stats.stats) >= 120000
                # Reconfigure both live engine roles through the real native API.
                edit = {
                    k: item[k]
                    for k in ("name", "inbound_tags", "domains", "port", "enabled", "transport", "paqet_options")
                }
                edit["paqet_options"]["socks5"][0].pop("authenticated")
                edit["paqet_options"]["kcp"].update({"mode": "manual", "dshard": 10, "pshard": 3, "mtu": 1100})
                edit["expected_revision"] = item["revision"]
                changed = await client.put(f"/api/tunnels/{link_id}", json=edit)
                assert changed.status_code == 200, changed.text
                item = changed.json()
                result = await client.post("/api/tunnels/apply")
                assert result.json()["ok"], result.text
                assert await tcp_query(client_port, b"fec-" * 1000) == b"fec-" * 1000
                assert await udp_query(client_port, b"fec-datagram" * 40) == b"fec-datagram" * 40
                assert "passed" in docker("exec", names[0], "/fixture/echo", "socks")
                rotated = await client.post(
                    f"/api/tunnels/{link_id}/rotate", json={"expected_revision": item["revision"]}
                )
                assert rotated.status_code == 200
                item = rotated.json()
                result = await client.post("/api/tunnels/apply")
                assert result.json()["ok"], result.text
                assert await tcp_query(client_port, b"rotated") == b"rotated"
                assert await udp_query(client_port, b"rotated-udp") == b"rotated-udp"
                # Native source user stats remain available through the existing bridge.
                from PasarGuardNodeBridge.common import service_pb2 as service

                bridge = await node_manager.get_node(1)
                stats = await bridge.get_stats(service.UserStat, reset=False, name="1")
                assert sum(row.value for row in stats.stats) > 0
            for container in names:
                rules = docker("exec", container, "iptables-save")
                assert f"pg-plus-paqet-{engine_id}" in rules
            edit = {
                k: item[k] for k in ("name", "inbound_tags", "domains", "port", "enabled", "transport", "paqet_options")
            }
            edit["paqet_options"]["socks5"][0].pop("authenticated")
            edit.update(enabled=False, expected_revision=item["revision"])
            changed = await client.put(f"/api/tunnels/{link_id}", json=edit)
            assert changed.status_code == 200, changed.text
            result = await client.post("/api/tunnels/apply")
            assert result.json()["ok"], result.text
            for container in names:
                assert f"pg-plus-paqet-{engine_id}" not in docker("exec", container, "iptables-save")
            assert await process_inbounds_and_tags(subscription_user, {}, StandardLinks(), {}) == ""
            for node_id in (1, 2):
                assert (await client.get(f"/api/tunnels/nodes/{node_id}/paqet")).status_code == 200
            state = await client.get(f"/api/tunnels/{link_id}/paqet/status")
            assert not state.json()["source"]["state"]["running"] and not state.json()["exit"]["state"]["running"]
    except Exception:
        if "app" in locals() and "link_id" in locals():
            try:
                async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as diagnostic_client:
                    diagnostic = await diagnostic_client.get(f"/api/tunnels/{link_id}/paqet/status")
                    (tmp_path / "paqet-state.json").write_text(diagnostic.text)
            except Exception:
                pass
        for container in containers:
            (tmp_path / (container + ".log")).write_text(docker("logs", container))
            try:
                (tmp_path / (container + "-xray.log")).write_text(
                    docker("exec", container, "cat", "/var/lib/pg-node/xray-test.log")
                )
            except Exception:
                pass

        raise
    finally:
        for i in (1, 2):
            try:
                await node_manager.remove_node(i)
            except Exception:
                pass
        for container in containers:
            docker("rm", "-f", "-v", container)
        docker("network", "rm", name)
        await engine.dispose()
