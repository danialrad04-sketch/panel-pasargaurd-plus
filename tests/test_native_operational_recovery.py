# ruff: noqa: F811
import asyncio
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.db.models import NativeMaintenanceJob, NativeTunnel, SmartDNSNodeState, TunnelFailoverPolicy, TunnelHealthState
from app.maintenance import backup, jobs, recovery
from tests.test_native_tunnels import add, native  # noqa: F401


async def test_restore_requires_fresh_runtime_apply_and_suspends_automation(native, tmp_path):
    row = await add(native, enabled=True, health_enabled=True)
    async with native() as db:
        (await db.get(NativeTunnel, row.id)).applied_revision = 1
        db.add(TunnelHealthState(tunnel_id=row.id, revision=1, result={"healthy": True}))
        db.add(TunnelFailoverPolicy(source_node_id=1, candidates=[row.id], enabled=True))
        db.add(SmartDNSNodeState(node_id=1, revision=5, applied_revision=5))
        await db.commit()
    source = Path(native.kw["bind"].url.database)
    key = tmp_path / "key"
    archive = tmp_path / "snapshot.pgb"
    backup.keygen(key)
    backup.create(source, archive, key)
    verified = backup.verify(archive, key)
    assert verified["sha256"] == backup.file_hash(archive)
    backup.restore(archive, tmp_path / "restored", key)
    with sqlite3.connect(tmp_path / "restored/db.sqlite3") as restored:
        assert restored.execute("SELECT applied_revision FROM native_tunnels").fetchone()[0] == 0
        assert restored.execute("SELECT applied_revision FROM smartdns_node_state").fetchone()[0] == 0
        assert restored.execute("SELECT suspended FROM tunnel_failover_policies").fetchone()[0] == 1
        assert restored.execute("SELECT COUNT(*) FROM tunnel_health_state").fetchone()[0] == 0


async def test_recovery_rebases_remote_revisions_and_requires_orphan_authorization(native, monkeypatch):
    row = await add(native, enabled=True)
    async with native() as db:
        db.add(SmartDNSNodeState(node_id=1, revision=2, applied_revision=2))
        await db.commit()

    async def request(node, method, path, body=None, **kwargs):
        if kwargs.get("namespace") == "smartdns":
            return {"revision": 7}
        if method == "PUT":
            return {"revision": body["revision"], "running": False}
        return [{"id": row.engine_id, "revision": 9, "enabled": True}, {"id": "f" * 32, "revision": 4, "enabled": True}]

    monkeypatch.setattr(recovery, "request", request)
    inventory = await recovery.inventory(sessions=native)
    with pytest.raises(HTTPException) as denied:
        await recovery.reconcile(inventory["fingerprint"], False, "owner", sessions=native)
    assert denied.value.status_code == 409
    result = await recovery.reconcile(inventory["fingerprint"], True, "owner", sessions=native)
    assert result["ok"] and result["state"] == "reapply_required"
    async with native() as db:
        tunnel = await db.get(NativeTunnel, row.id)
        assert tunnel.revision == 10 and tunnel.applied_revision == 0
        state = await db.get(SmartDNSNodeState, 1)
        assert state.revision == 8 and state.applied_revision == 0


async def test_scheduled_backup_claim_prevents_duplicate_worker_execution(native, monkeypatch):
    monkeypatch.setattr(jobs.settings, "backup_enabled", True)
    started, release = threading.Event(), threading.Event()
    calls = []

    def snapshot():
        calls.append(1)
        started.set()
        release.wait(3)
        return {"ok": True, "verified_at": datetime.now(UTC).isoformat()}

    task = asyncio.create_task(jobs.scheduled_backup(sessions=native, run_snapshot=snapshot))
    assert await asyncio.to_thread(started.wait, 2)
    try:
        await jobs.scheduled_backup(sessions=native, run_snapshot=snapshot)
    finally:
        release.set()
    await task
    assert len(calls) == 1
    async with native() as db:
        state = await db.get(NativeMaintenanceJob, "backup")
        assert state.last_result["ok"] and state.lease is None


async def test_cancelled_backup_keeps_claim_while_snapshot_thread_finishes(native, monkeypatch):
    monkeypatch.setattr(jobs.settings, "backup_enabled", True)
    started, release, finished = threading.Event(), threading.Event(), threading.Event()

    def snapshot():
        started.set()
        release.wait(5)
        finished.set()
        return {"ok": True}

    task = asyncio.create_task(jobs.scheduled_backup(sessions=native, run_snapshot=snapshot))
    assert await asyncio.to_thread(started.wait, 2)
    try:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with native() as db:
            state = await db.get(NativeMaintenanceJob, "backup")
            assert state.lease is not None and state.lease_until is not None
        await jobs.scheduled_backup(sessions=native, run_snapshot=lambda: pytest.fail("overlapping backup"))
    finally:
        release.set()
        assert await asyncio.to_thread(finished.wait, 2)


@pytest.mark.parametrize(
    "role_name,expected",
    [
        ("BACKEND", set()),
        ("SCHEDULER", {"native-encrypted-backup", "native-smartdns-grants"}),
        ("NODE", {"native-tunnel-failover", "native-carrier-health", "native-gateway-health"}),
        (
            "ALL_IN_ONE",
            {
                "native-encrypted-backup",
                "native-smartdns-grants",
                "native-tunnel-failover",
                "native-carrier-health",
                "native-gateway-health",
            },
        ),
    ],
)
def test_maintenance_jobs_registered_on_actual_runtime_roles(role_name, expected, monkeypatch):
    import runpy

    from app.scheduler import scheduler
    from config import runtime_settings
    from role import Role

    registered = set()
    monkeypatch.setattr(runtime_settings, "role", Role[role_name])
    monkeypatch.setattr(scheduler, "add_job", lambda *args, **kwargs: registered.add(kwargs["id"]))
    runpy.run_path("app/jobs/native_failover.py")
    assert registered == expected
