# ruff: noqa: F811
# Imported pytest fixtures intentionally share parameter names.
"""Readiness must never leak node secrets or prevent runtime repair."""

import asyncio
from datetime import UTC, datetime, timedelta

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient

from app.db.base import get_db
from app.db.models import NativeTunnel, Node, TunnelDeployment
from app.models.admin import AdminDetails, AdminRoleData
from app.routers.authentication import get_current
from app.routers.tunnel import router
from app.tunnel import preflight as checks
from tests.test_native_tunnels import add, native  # noqa: F401


async def test_readonly_unapplied_link_requires_apply(native):
    row = await add(native, enabled=True)
    async with native() as db:
        result = await checks.preflight(db, row.id, row.revision)
        assert result.can_apply and not result.can_publish
        assert result.traffic_readiness == "not_probed"
        assert row.shared_key not in result.model_dump_json()
        assert (await db.get(NativeTunnel, row.id)).applied_revision == 0


async def test_revision_fence_and_missing(native):
    row = await add(native)
    async with native() as db:
        with pytest.raises(HTTPException) as stale:
            await checks.preflight(db, row.id, 99)
        assert stale.value.status_code == 409
        with pytest.raises(HTTPException) as missing:
            await checks.preflight(db, 999, 1)
        assert missing.value.status_code == 404


async def test_deployment_lease_blocks_changes(native):
    row = await add(native)
    async with native() as db:
        state = await db.get(TunnelDeployment, 1)
        state.lease_until = datetime.now(UTC) + timedelta(minutes=1)
        state.lease = "in-progress"
        await db.commit()
        result = await checks.preflight(db, row.id, 1)
        assert not result.can_apply and not result.can_publish


async def paqet_row(native):
    row = await add(native, enabled=True)
    async with native() as db:
        row = await db.get(NativeTunnel, row.id)
        row.transport = "paqet"
        row.applied_revision = row.revision
        await db.commit()
    return row


async def topology(db, rows):
    return {node_id: await db.get(Node, node_id) for node_id in (1, 2)}


async def test_stale_runtime_allows_repair_but_not_publish(native, monkeypatch):
    row = await paqet_row(native)
    monkeypatch.setattr(checks.operation, "validate_topology", topology)

    async def request(node, method, path):
        if path == "capabilities":
            return {"contract": 1, "engine": "v1.0.0-alpha.21", "installed": True}
        if path.startswith("network"):
            return {"interface": "eth0", "local_ip": "192.0.2.10", "router_mac": "00:11:22:33:44:55"}
        return {"running": False, "revision": 0, "private": "never expose raw state"}

    monkeypatch.setattr(checks, "request", request)
    async with native() as db:
        result = await checks.preflight(db, row.id, 1)
    assert result.can_apply and not result.can_publish
    assert "never expose" not in result.model_dump_json()


async def test_capability_check_is_bounded_and_redacted(native, monkeypatch):
    row = await paqet_row(native)
    monkeypatch.setattr(checks.operation, "validate_topology", topology)
    monkeypatch.setattr(checks, "NODE_CHECK_TIMEOUT_SECONDS", 0.01)

    async def request(*args):
        await asyncio.sleep(10)

    monkeypatch.setattr(checks, "request", request)
    async with native() as db:
        result = await asyncio.wait_for(checks.preflight(db, row.id, 1), 1)
    assert not result.can_apply and not result.can_publish
    assert len([item for item in result.checks if item.code.endswith("paqet_access")]) == 2


async def test_owner_only_readiness_endpoint(native):
    row = await add(native)
    app = FastAPI()
    app.include_router(router)

    async def database():
        async with native() as db:
            yield db

    app.dependency_overrides[get_db] = database
    app.dependency_overrides[get_current] = lambda: AdminDetails(username="limited", role=AdminRoleData(is_owner=False))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(f"/api/tunnels/{row.id}/preflight", json={"expected_revision": 1})
        assert response.status_code == 403
        app.dependency_overrides[get_current] = lambda: AdminDetails(
            username="owner", role=AdminRoleData(is_owner=True)
        )
        response = await client.post(f"/api/tunnels/{row.id}/preflight", json={"expected_revision": 1})
        assert response.status_code == 200
        assert response.json()["traffic_readiness"] == "not_probed"
