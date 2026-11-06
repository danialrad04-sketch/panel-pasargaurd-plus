# ruff: noqa: F811
"""Credential privacy, target claims, remote integrity and safe restore behavior."""

import asyncio
import io
import json
import tarfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import AsyncMock

import asyncssh
import pytest
from cryptography.exceptions import InvalidTag
from fastapi import HTTPException

from app.db.models import NativeProvisionJob
from app.models.provision import InstallInput
from app.provisioning import remote, secrets, service, worker
from tests.test_native_tunnels import native  # noqa: F401


@pytest.fixture
def provisioning(tmp_path, monkeypatch):
    monkeypatch.setattr(secrets.settings, "key_file", str(tmp_path / "private/key"))
    monkeypatch.setattr(service.bundles, "available", lambda: [{"architecture": "amd64"}])
    key = asyncssh.generate_private_key("ssh-ed25519")
    return {
        "host": "192.0.2.10",
        "name": "Installed Iran",
        "server_host_key": key.export_public_key().decode(),
        "password": "private-test-password-not-for-deployment",
    }


async def test_encrypted_job_views_target_claim_and_cancel_purge(native, provisioning):
    async with native() as db:
        value = await service.enqueue_install(db, InstallInput(**provisioning), "owner")
        assert "password" not in json.dumps(value, default=str) and "server_host_key" not in json.dumps(
            value, default=str
        )
    async with native() as db:
        row = await db.get(NativeProvisionJob, value["id"])
        assert provisioning["password"] not in row.credential_cipher
        assert secrets.unseal(row.credential_cipher, row.id)["password"] == provisioning["password"]
        with pytest.raises(HTTPException) as error:
            await service.enqueue_install(db, InstallInput(**provisioning), "owner")
        assert error.value.status_code == 409
    async with native() as db:
        await service.cancel(db, value["id"], "owner")
        row = await db.get(NativeProvisionJob, value["id"])
        assert row.credential_cipher is None and row.active_target is None


async def test_multiple_workers_claim_only_once_and_running_cannot_cancel(native, provisioning):
    async with native() as db:
        job = await service.enqueue_install(db, InstallInput(**provisioning), "owner")
    claimed = await asyncio.gather(worker.claim(native), worker.claim(native), worker.claim(native))
    assert sum(row is not None for row in claimed) == 1
    async with native() as db:
        with pytest.raises(HTTPException) as error:
            await service.cancel(db, job["id"], "owner")
        assert error.value.status_code == 409


async def test_fenced_job_failure_purges_credentials_without_returning_raw_errors(native, provisioning):
    async with native() as db:
        value = await service.enqueue_install(db, InstallInput(**provisioning), "owner")

    async def fail(*args, **kwargs):
        raise RuntimeError(provisioning["password"])

    await worker.drain(sessions=native, bootstrap=fail)
    async with native() as db:
        row = await db.get(NativeProvisionJob, value["id"])
        assert row.state == "failed" and row.result == {"code": "native_job_failed"}
        assert row.credential_cipher is None and provisioning["password"] not in str(service.view(row))
    with pytest.raises(worker.ClaimLost):
        await worker.update(native, value["id"], "wrong", "installing_service", 50)


async def test_expired_claim_resumes_same_job_but_exhausted_claim_never_replays(native, provisioning):
    async with native() as db:
        value = await service.enqueue_install(db, InstallInput(**provisioning), "owner")
    first = await worker.claim(native)
    async with native() as db:
        row = await db.get(NativeProvisionJob, value["id"])
        row.lease_until = datetime.now(UTC) - timedelta(seconds=1)
        await db.commit()
    second = await worker.claim(native)
    assert second.id == first.id and second.lease != first.lease and second.attempts == 2
    async with native() as db:
        row = await db.get(NativeProvisionJob, value["id"])
        row.attempts = 3
        row.lease_until = datetime.now(UTC) - timedelta(seconds=1)
        await db.commit()
    assert await worker.claim(native) is None
    async with native() as db:
        row = await db.get(NativeProvisionJob, value["id"])
        assert row.state == "interrupted" and row.credential_cipher is None


def test_credential_encryption_is_job_bound_private_and_concurrency_safe(provisioning):
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=8) as pool:
        values = list(pool.map(lambda i: secrets.seal({"password": "sensitive"}, str(i)), range(16)))
    for i, value in enumerate(values):
        assert secrets.unseal(value, str(i)) == {"password": "sensitive"}
    with pytest.raises(InvalidTag):
        secrets.unseal(values[0], "other-job")
    assert Path(secrets.settings.key_file).stat().st_mode & 0o777 == 0o600
    Path(secrets.settings.key_file).chmod(0o644)
    with pytest.raises(ValueError):
        secrets.key()


@pytest.mark.parametrize(
    "host", ("127.0.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1", "https://example.com", "server;command")
)
def test_ssh_target_cannot_be_metadata_loopback_or_command(provisioning, host):
    with pytest.raises(ValueError):
        InstallInput(**{**provisioning, "host": host})


def test_native_archive_traversal_is_rejected_before_writing_outside_release(tmp_path, monkeypatch):
    import hashlib

    monkeypatch.setattr(remote, "ROOT", tmp_path / "root")
    bundle = tmp_path / "bad.tar.gz"
    members = {"bin/" + name: b"not-executed" for name in remote.BINARIES}
    members.update({"assets/geoip.dat": b"x", "assets/geosite.dat": b"x", "../outside": b"escape"})
    manifest = {
        "format": 1,
        "architecture": "amd64",
        "files": {
            name: {"size": len(value), "sha256": hashlib.sha256(value).hexdigest()} for name, value in members.items()
        },
    }
    members["manifest.json"] = json.dumps(manifest).encode()
    with tarfile.open(bundle, "w:gz") as target:
        for name, value in members.items():
            record = tarfile.TarInfo(name)
            record.size = len(value)
            target.addfile(record, io.BytesIO(value))
    with pytest.raises(remote.InstallError):
        remote.release(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest(), "amd64", "a" * 32)
    assert not (tmp_path / "outside").exists()


async def test_ssh_host_key_is_pinned_before_any_password_is_sent(provisioning):
    from app.provisioning.ssh import ProvisionError, bootstrap

    calls = []

    class Server(asyncssh.SSHServer):
        def begin_auth(self, username):
            return True

        def password_auth_supported(self):
            return True

        def validate_password(self, username, password):
            calls.append(password)
            return False

    actual = asyncssh.generate_private_key("ssh-ed25519")
    listener = await asyncssh.listen("127.0.0.1", 0, server_factory=Server, server_host_keys=[actual])
    try:
        payload = {**provisioning, "host": "127.0.0.1", "ssh_port": listener.get_port(), "username": "root"}
        with pytest.raises(ProvisionError) as error:
            await bootstrap(payload, {"password": provisioning["password"]}, "a" * 32, AsyncMock())
        assert error.value.code == "ssh_host_key_changed" and calls == []
        payload["server_host_key"] = actual.export_public_key().decode()
        with pytest.raises(ProvisionError) as error:
            await bootstrap(payload, {"password": provisioning["password"]}, "a" * 32, AsyncMock())
        assert error.value.code == "ssh_authentication_failed" and calls == [provisioning["password"]]
    finally:
        listener.close()
        await listener.wait_closed()


async def test_automatic_activation_enables_flags_atomically_and_opens_user_ports_only_after_core_ack(
    native, monkeypatch
):
    from app.db.models import NativeTunnel
    from app.models.tunnel import TunnelCreate
    from app.operation import tunnel as operation
    from app.operation.node import NodeOperation
    from app.tunnel import failover

    calls = []
    async with native() as db:
        row = await operation.create(
            db, TunnelCreate(name="auto", source_node_id=1, exit_node_id=2, inbound_tags=["users"]), "owner"
        )
        result = await service.enqueue_activation(db, row.id, 1, "owner")

    async def ingress(db, identity, *, include_users=True):
        calls.append(("rules", identity, include_users))

    async def start(self, db, identity, **kwargs):
        calls.append(("start", identity))
        return True

    monkeypatch.setattr(worker.ingress, "apply", ingress)
    monkeypatch.setattr(NodeOperation, "_connect_single_node_local", start)
    monkeypatch.setattr(failover, "verify_candidate", AsyncMock(return_value=True))
    await worker.drain(sessions=native)
    async with native() as db:
        job = await db.get(NativeProvisionJob, result["id"])
        row = await db.get(NativeTunnel, row.id)
        assert job.state == "ready" and row.enabled and row.health_enabled
        assert job.payload["revision"] == row.revision == row.applied_revision == 2
    assert calls == [
        ("rules", 2, False),
        ("start", 2),
        ("rules", 2, True),
        ("rules", 1, False),
        ("start", 1),
        ("rules", 1, True),
    ]


async def test_failed_core_ack_does_not_open_public_user_ports(native, monkeypatch):
    from app.models.tunnel import TunnelCreate
    from app.operation import tunnel as operation
    from app.operation.node import NodeOperation

    calls = []
    async with native() as db:
        row = await operation.create(
            db, TunnelCreate(name="failed ack", source_node_id=1, exit_node_id=2, inbound_tags=["users"]), "owner"
        )
        job = await service.enqueue_activation(db, row.id, 1, "owner")

    async def ingress(db, identity, *, include_users=True):
        calls.append(include_users)

    monkeypatch.setattr(worker.ingress, "apply", ingress)
    monkeypatch.setattr(NodeOperation, "_connect_single_node_local", AsyncMock(return_value=False))
    await worker.drain(sessions=native)
    async with native() as db:
        row = await db.get(NativeProvisionJob, job["id"])
        assert row.state == "failed" and row.result["code"] == "tunnel_apply_failed"
    assert calls and not any(calls)


def test_remote_installer_compiles_for_python310():
    import ast

    ast.parse(Path(remote.__file__).read_text(), feature_version=(3, 10))


def test_unmanaged_identity_is_never_replaced(tmp_path, monkeypatch):
    state = tmp_path / "node"
    (state / "certs").mkdir(parents=True)
    cert = state / "certs/ssl_cert.pem"
    cert.write_text("existing private installation")
    monkeypatch.setattr(remote, "STATE", state)
    with pytest.raises(remote.InstallError, match="unmanaged_identity"):
        remote.current_identity({"job_id": "a" * 32}, tmp_path)
    assert cert.read_text() == "existing private installation"


def test_remote_service_failure_restores_prior_release_unit_and_identity(tmp_path, monkeypatch):
    from types import SimpleNamespace
    from unittest.mock import MagicMock

    state, root, working = tmp_path / "state", tmp_path / "releases", tmp_path / "working"
    state.mkdir()
    root.mkdir()
    working.mkdir()
    unit = tmp_path / "native.service"
    unit.write_bytes(b"previous unit")
    (state / "native.env").write_bytes(b"previous private env")
    (state / ".native-owner.json").write_text('{"managed_by":"pasarguard-plus"}')
    previous, selected = root / "previous", root / "next"
    previous.mkdir()
    selected.mkdir()
    (root / "current").symlink_to(previous)
    monkeypatch.setattr(remote, "STATE", state)
    monkeypatch.setattr(remote, "ROOT", root)
    monkeypatch.setattr(remote, "UNIT", unit)
    monkeypatch.setattr(remote.os, "geteuid", lambda: 0)
    monkeypatch.setattr(remote, "environment", lambda: "amd64")
    monkeypatch.setattr(remote, "dependencies", lambda: None)
    monkeypatch.setattr(remote, "current_identity", lambda *args: ("public certificate", "fixture key"))
    monkeypatch.setattr(remote, "old_container", lambda *args: None)
    monkeypatch.setattr(remote, "release", lambda *args: selected)
    monkeypatch.setattr(remote, "firewall", lambda *args: None)
    monkeypatch.setattr(remote.socket, "socket", MagicMock())
    monkeypatch.setattr(remote.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(returncode=0))

    def command(args, **kwargs):
        if args[:2] == ["systemctl", "restart"]:
            raise remote.InstallError("command_failed")
        return ""

    monkeypatch.setattr(remote, "command", command)
    request = {
        "architecture": "amd64",
        "node_port": 62050,
        "tunnel_port": 62052,
        "health_port": 62053,
        "ssh_port": 22,
        "bundle_sha256": "a" * 64,
        "job_id": "b" * 32,
    }
    with pytest.raises(remote.InstallError, match="command_failed"):
        remote.install(request, working)
    assert (root / "current").resolve() == previous
    assert unit.read_bytes() == b"previous unit"
    assert (state / "native.env").read_bytes() == b"previous private env"
    assert not (working / "registration.json").exists()


async def test_provisioning_routes_are_owner_only(native, provisioning):
    from fastapi import FastAPI
    from httpx import ASGITransport, AsyncClient

    from app.db.base import get_db
    from app.models.admin import AdminDetails, AdminRoleData
    from app.routers.authentication import get_current
    from app.routers.provision import router

    api = FastAPI()
    api.include_router(router)

    async def database():
        async with native() as db:
            yield db

    api.dependency_overrides[get_db] = database
    api.dependency_overrides[get_current] = lambda: AdminDetails(username="manager", role=AdminRoleData(is_owner=False))
    async with AsyncClient(transport=ASGITransport(app=api), base_url="http://fixture") as client:
        assert (await client.get("/api/provision")).status_code == 403
        assert (await client.post("/api/provision/install", json=provisioning)).status_code == 403
        api.dependency_overrides[get_current] = lambda: AdminDetails(
            username="owner", role=AdminRoleData(is_owner=True)
        )
        response = await client.post("/api/provision/install", json=provisioning)
        assert response.status_code == 202 and "password" not in response.text
        assert (await client.get("/api/provision")).status_code == 200


def test_panel_openapi_operation_names_remain_unique_for_generated_client():
    from app import create_app

    schema = create_app().openapi()
    names = [
        value["operationId"]
        for item in schema["paths"].values()
        for value in item.values()
        if isinstance(value, dict) and "operationId" in value
    ]
    assert len(names) == len(set(names))


@pytest.mark.parametrize(
    "distribution,version,accepted",
    [("debian", "12", True), ("ubuntu", "22.04", True), ("debian", "11", False), ("ubuntu", "20.04", False)],
)
def test_remote_os_preflight_has_explicit_minimum(distribution, version, accepted, monkeypatch):
    from types import SimpleNamespace

    monkeypatch.setattr(
        remote.Path, "read_text", lambda *args, **kwargs: f'ID={distribution}\nVERSION_ID="{version}"\n'
    )
    monkeypatch.setattr(remote.platform, "system", lambda: "Linux")
    monkeypatch.setattr(remote.platform, "machine", lambda: "x86_64")
    monkeypatch.setattr(remote.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout=b"running"))
    if accepted:
        assert remote.environment() == "amd64"
    else:
        with pytest.raises(remote.InstallError, match="unsupported_os"):
            remote.environment()
