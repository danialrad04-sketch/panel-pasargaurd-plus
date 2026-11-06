# ruff: noqa: F811
"""Opt-in real SSH/SFTP, native bundle, node TLS, core start and two-node carrier.

The disposable target uses a process supervisor shim; real systemd boot is not
covered here. No user VPS or external SSH destination is contacted.
"""

import asyncio
import json
import os
import subprocess
from copy import deepcopy
from unittest.mock import AsyncMock
from uuid import uuid4

import asyncssh
import pytest

from app.core.manager import core_manager
from app.core.xray import XRayConfig
from app.db.models import CoreConfig, NativeProvisionJob, Node
from app.models.provision import InstallInput, Target
from app.models.tunnel import TunnelCreate
from app.node import node_manager
from app.operation import tunnel as operation
from app.paqet.client import request
from app.provisioning import bundles, secrets, service, worker
from tests.test_native_tunnels import BASE, native  # noqa: F401

IMAGE = os.getenv("NATIVE_SSH_TEST_IMAGE")
pytestmark = pytest.mark.skipif(
    not IMAGE or not os.getenv("NATIVE_SSH_TEST_BUNDLES"), reason="Opt-in disposable SSH acceptance"
)


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.STDOUT).strip()


async def test_real_ssh_installs_all_cores_registers_two_nodes_and_activates_tcp_udp(native, tmp_path, monkeypatch):
    monkeypatch.setattr(bundles.settings, "bundle_directory", os.environ["NATIVE_SSH_TEST_BUNDLES"])
    monkeypatch.setattr(secrets.settings, "key_file", str(tmp_path / "provision.key"))
    fresh = type(node_manager)()
    for field in ("_nodes", "_node_signatures", "_keep_alives", "_user_sync_locks", "_user_sync_tasks", "_lock"):
        monkeypatch.setattr(node_manager, field, getattr(fresh, field))
    base = deepcopy(BASE)
    base["inbounds"][0]["listen"] = "0.0.0.0"
    managed = XRayConfig(base)
    monkeypatch.setattr(core_manager, "_cores", {1: managed})
    monkeypatch.setattr(core_manager, "_inbounds", list(managed.inbounds_by_tag))
    monkeypatch.setattr(core_manager, "_inbounds_by_tag", managed.inbounds_by_tag)
    monkeypatch.setattr("app.tunnel.runtime.GetDB", native)
    monkeypatch.setattr("app.operation.node.notification.connect_node", AsyncMock())
    from app.core.hosts import host_manager

    monkeypatch.setattr(host_manager, "_hosts", {})
    monkeypatch.setattr(host_manager, "_kv", None)
    monkeypatch.setattr(host_manager, "_add_hosts_impl", host_manager._add_hosts_local)
    await host_manager._reset_cache()
    async with native() as db:
        (await db.get(CoreConfig, 1)).config = base
        await db.commit()
    private = asyncssh.generate_private_key("ssh-ed25519")
    authorized = tmp_path / "authorized_keys"
    authorized.write_bytes(private.export_public_key())
    authorized.chmod(0o600)
    containers, identities = [], []
    try:
        for side in ("source", "exit"):
            name = "pg-native-ssh-" + uuid4().hex[:10]
            docker(
                "run",
                "-d",
                "--name",
                name,
                "--cap-add",
                "NET_ADMIN",
                "--cap-add",
                "NET_RAW",
                IMAGE,
            )
            containers.append(name)
            docker("cp", str(authorized), name + ":/root/.ssh/authorized_keys")
            docker("exec", name, "chown", "root:root", "/root/.ssh/authorized_keys")
            address = json.loads(docker("inspect", name))[0]["NetworkSettings"]["IPAddress"]
            for attempt in range(30):
                try:
                    inspected = await service.scan(Target(host=address))
                    break
                except Exception:
                    if attempt == 29:
                        raise
                    await asyncio.sleep(0.2)
            body = InstallInput(
                name="SSH " + side,
                host=address,
                server_host_key=inspected["server_host_key"],
                private_key=private.export_private_key().decode(),
                engine="gost",
            )
            async with native() as db:
                job = await service.enqueue_install(db, body, "owner")
            await worker.drain(sessions=native)
            async with native() as db:
                row = await db.get(NativeProvisionJob, job["id"])
                assert row.state == "ready", (row.result, docker("logs", name))
                assert row.credential_cipher is None and row.result["scope"] == "installed_node_and_core"
                node = await db.get(Node, row.node_id)
                identities.append(node.id)
                for engine in ("paqet", "backhaul", "gost", "frp"):
                    capability = await request(node, "GET", "capabilities", namespace=engine)
                    assert worker.compatible(engine, capability)
            if side == "source":
                previous_ca, previous_key = node.server_ca, node.api_key
                async with native() as db:
                    retry = await service.enqueue_install(db, body.model_copy(update={"node_id": node.id}), "owner")
                await worker.drain(sessions=native)
                async with native() as db:
                    retried = await db.get(NativeProvisionJob, retry["id"])
                    assert retried.state == "ready", retried.result
                    same = await db.get(Node, retried.node_id)
                    assert same.id == node.id and same.server_ca == previous_ca and same.api_key == previous_key
            # Identity files exist only on the target and have private permissions.
            assert docker("exec", name, "stat", "-c", "%a", "/var/lib/pg-node/certs/ssl_key.pem") == "600"
        async with native() as db:
            tunnel = await operation.create(
                db,
                TunnelCreate(
                    name="SSH automatic GOST TLS",
                    source_node_id=identities[0],
                    exit_node_id=identities[1],
                    inbound_tags=["users"],
                    transport="gost",
                    carrier_options={"profile": "tls", "client_port": 12181, "exit_port": 12182},
                ),
                "owner",
            )
            job = await service.enqueue_activation(db, tunnel.id, tunnel.revision, "owner")
        await worker.drain(sessions=native)
        async with native() as db:
            row = await db.get(NativeProvisionJob, job["id"])
            assert row.state == "ready", row.result
            assert row.result["scope"] == "verified_encrypted_carrier"
            source = await db.get(Node, identities[0])
            ingress = await request(source, "GET", "ingress", namespace="network")
            assert ingress["rules"] == 2
            for network in ("tcp", "udp"):
                docker(
                    "exec",
                    containers[0],
                    "iptables",
                    "-C",
                    "PG_PLUS_AUTO",
                    "-s",
                    "0.0.0.0/0",
                    "-p",
                    network,
                    "--dport",
                    "11080",
                    "-m",
                    "comment",
                    "--comment",
                    "pg-plus-native",
                    "-j",
                    "ACCEPT",
                )
    finally:
        for identity in identities:
            try:
                await node_manager.remove_node(identity)
            except Exception:
                pass
        for name in containers:
            docker("rm", "-f", name)
