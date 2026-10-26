"""Native configuration, ownership, deployment fencing and partial-failure behavior."""

import asyncio
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from sqlalchemy import event, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db.base import Base, get_db
from app.db.models import CoreConfig, NativeTunnel, Node, NodeStatus, ServiceAudit, TunnelDeployment
from app.models.admin import AdminDetails, AdminRoleData
from app.models.tunnel import TunnelCreate, TunnelModify, TunnelView
from app.operation import tunnel as operation
from app.routers import tunnel as api
from app.routers.authentication import get_current
from app.tunnel.compiler import compose

BASE = {
    "inbounds": [
        {
            "tag": "users",
            "listen": "127.0.0.1",
            "port": 11080,
            "protocol": "vless",
            "settings": {"decryption": "none", "clients": []},
        }
    ],
    "outbounds": [{"tag": "direct", "protocol": "freedom"}],
    "routing": {"rules": [{"type": "field", "ip": ["127.0.0.0/8"], "outboundTag": "direct"}]},
}
OWNER = AdminDetails(username="owner", role=AdminRoleData(is_owner=True))


@pytest.fixture
async def native(tmp_path):
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/network.db")

    @event.listens_for(engine.sync_engine, "connect")
    def configure(connection, _):
        connection.execute("PRAGMA journal_mode=WAL")
        # Intentionally leave FK enforcement off to exercise explicit native cleanup guards.
        connection.execute("PRAGMA busy_timeout=5000")

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    async with sessions() as db:
        core = CoreConfig(name="shared core", config=deepcopy(BASE))
        db.add(core)
        await db.flush()
        for name in ("Iran", "Germany", "Finland"):
            db.add(
                Node(
                    name=name,
                    address="127.0.0.1",
                    port=62050,
                    api_port=62051,
                    server_ca="fixture",
                    api_key="fixture",
                    core_config_id=core.id,
                    status=NodeStatus.connected,
                )
            )
        db.add(TunnelDeployment(id=1))
        await db.commit()
    yield sessions
    await engine.dispose()


def body(**changes):
    return TunnelCreate(name="Iran to Germany", source_node_id=1, exit_node_id=2, inbound_tags=["users"], **changes)


async def add(native, **changes):
    async with native() as db:
        return await operation.create(db, body(**changes), "owner")


def modified(row, **changes):
    values = TunnelView.model_validate(row).model_dump(include={"name", "port", "enabled", "domains", "inbound_tags"})
    values.update(changes)
    return TunnelModify(**values, expected_revision=row.revision)


async def test_native_overlay_isolated_and_credentials_private(native):
    row = await add(native, enabled=True)
    base = deepcopy(BASE)
    exit_node = SimpleNamespace(address="127.0.0.1")
    source, excluded = compose(base, 1, [row], {2: exit_node})
    destination, destination_excluded = compose(base, 2, [row], {2: exit_node})
    unrelated, _ = compose(base, 3, [row], {2: exit_node})
    assert base == BASE == unrelated
    assert source["routing"]["rules"][0]["inboundTag"] == ["users"]
    assert source["outbounds"][-1]["settings"]["servers"][0]["password"] == row.shared_key
    assert destination["inbounds"][-1]["settings"]["password"] == row.shared_key
    assert destination_excluded == {f"pg-tunnel-{row.id}"} and not excluded
    assert source["inbounds"] == BASE["inbounds"]
    async with native() as db:
        public = str(await operation.state_view(db))
        assert row.shared_key not in public
        assert "api_key" not in public and "server_ca" not in public
        assert "shared_key" not in repr(row)


async def test_domain_suffix_routes_and_normalization(native):
    row = await add(native, enabled=True, domains=["Example.COM.", "example.com"])
    config, _ = compose(BASE, 1, [row], {2: SimpleNamespace(address="127.0.0.1")})
    assert config["routing"]["rules"][0]["domain"] == ["domain:example.com"]


async def test_concurrent_deploy_fences_edits_and_other_workers(native):
    row = await add(native, enabled=True)
    entered, release = asyncio.Event(), asyncio.Event()
    order = []

    async def restart(db, node_id, **kwargs):
        order.append(node_id)
        if len(order) == 1:
            entered.set()
            await release.wait()
        return True

    task = asyncio.create_task(operation.deploy("owner", session_factory=native, restart=restart))
    await asyncio.wait_for(entered.wait(), timeout=5)
    with pytest.raises(HTTPException, match="409"):
        await operation.deploy("other worker", session_factory=native, restart=restart)
    async with native() as db:
        with pytest.raises(HTTPException, match="409"):
            await operation.modify(db, row.id, modified(row, enabled=False), "owner")
    release.set()
    assert (await task)["ok"]
    assert order == [2, 1]
    async with native() as db:
        assert (await db.get(NativeTunnel, row.id)).applied_revision == 1
        assert not operation.leased(await db.get(TunnelDeployment, 1))
        audits = (await db.scalars(select(ServiceAudit))).all()
        assert len(audits) == 3
        assert row.shared_key not in str([item.details for item in audits])


async def test_partial_failure_stays_pending_without_source_fallback(native):
    row = await add(native, enabled=True)
    calls = []

    async def fail_exit(db, node_id, **kwargs):
        calls.append(node_id)
        raise RuntimeError("private config must not appear in results")

    result = await operation.deploy("owner", session_factory=native, restart=fail_exit)
    assert not result["ok"] and calls == [2]
    assert "private config" not in str(result)
    async with native() as db:
        assert (await db.get(NativeTunnel, row.id)).applied_revision == 0
    result = await operation.deploy("owner", session_factory=native, restart=AsyncMock(return_value=True))
    assert result["ok"]


async def test_disable_apply_then_delete_and_native_node_cleanup_guard(native):
    from app.db.crud.node import remove_node, remove_nodes

    row = await add(native, enabled=True)
    async with native() as db:
        with pytest.raises(HTTPException, match="409"):
            await remove_node(db, await db.get(Node, 1))
        await db.rollback()
        with pytest.raises(HTTPException, match="409"):
            await remove_nodes(db, [2])
        await db.rollback()
        with pytest.raises(HTTPException, match="409"):
            await operation.remove(db, row.id, 1, "owner")
        await db.rollback()
        row = await operation.modify(db, row.id, modified(row, enabled=False), "owner")
    order = []

    async def restart(db, node_id, **kwargs):
        order.append(node_id)
        return True

    assert (await operation.deploy("owner", session_factory=native, restart=restart))["ok"]
    assert order == [1, 2]
    async with native() as db:
        await operation.remove(db, row.id, row.revision, "owner")
        await remove_nodes(db, [2])
        assert await db.get(Node, 2) is None


async def test_optimistic_revision_and_rotation(native):
    row = await add(native)
    old_key = row.shared_key
    async with native() as db:
        rotated = await operation.rotate(db, row.id, row.revision, "owner")
        assert rotated.shared_key != old_key and rotated.revision == 2
        with pytest.raises(HTTPException, match="409"):
            await operation.rotate(db, row.id, 1, "owner")


@pytest.mark.parametrize("change", ["same_source", "loop", "port", "missing_inbound", "management_port"])
async def test_invalid_topologies_are_rejected(native, change):
    row = await add(native, enabled=True)
    async with native() as db:
        if change == "same_source":
            payload = body(enabled=True).model_copy(update={"name": "another", "exit_node_id": 3})
            action = operation.create(db, payload, "owner")
        elif change == "loop":
            payload = body(enabled=True).model_copy(update={"name": "loop", "source_node_id": 2, "exit_node_id": 1})
            action = operation.create(db, payload, "owner")
        elif change == "port":
            action = operation.modify(db, row.id, modified(row, port=11080), "owner")
        elif change == "management_port":
            action = operation.modify(db, row.id, modified(row, port=62051), "owner")
        else:
            action = operation.modify(db, row.id, modified(row, inbound_tags=["absent"]), "owner")
        with pytest.raises(HTTPException):
            await action


async def test_disabled_nodes_cannot_be_applied(native):
    await add(native, enabled=True)
    async with native() as db:
        node = await db.get(Node, 2)
        node.status = NodeStatus.limited
        await db.commit()
    with pytest.raises(HTTPException, match="409"):
        await operation.deploy("owner", session_factory=native)


async def test_actual_api_owner_gate_and_no_secrets(native):
    row = await add(native, enabled=True)
    app = FastAPI()
    app.include_router(api.router)

    async def db_override():
        async with native() as db:
            yield db

    app.dependency_overrides[get_db] = db_override
    app.dependency_overrides[get_current] = lambda: OWNER
    async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as client:
        response = await client.get("/api/tunnels")
        assert response.status_code == 200
        assert response.json()["nodes"][0]["core_type"] == "xray"
        assert row.shared_key not in response.text
        events = await client.get("/api/tunnels/audit")
        assert events.status_code == 200 and events.json()[0]["action"] == "tunnel.create"
        assert row.shared_key not in events.text
        app.dependency_overrides[get_current] = lambda: AdminDetails(username="reseller")
        for method, path in [("GET", "/api/tunnels"), ("POST", "/api/tunnels/apply")]:
            assert (await client.request(method, path)).status_code == 403


async def test_restart_hook_reads_per_node_overlay(native, monkeypatch):
    from app.core.xray import XRayConfig
    from app.tunnel import runtime

    await add(native, enabled=True)
    monkeypatch.setattr(runtime, "GetDB", native)
    config = XRayConfig(BASE)
    raw, excluded = await runtime.node_payload(2, config)
    assert '"protocol": "shadowsocks"' in raw
    assert excluded == {"pg-tunnel-1"}
    unchanged, excluded = await runtime.node_payload(3, config)
    assert unchanged == config.to_str() and not excluded


async def test_core_changes_cannot_remove_managed_route_or_reassign_backend(native):
    from app.models.core import CoreCreate
    from app.operation.tunnel import validate_core_change
    from app.tunnel.guards import assert_core_unreferenced

    await add(native, enabled=True)
    async with native() as db:
        with pytest.raises(HTTPException, match="409"):
            await assert_core_unreferenced(db, [1])
        await db.rollback()
        bad = deepcopy(BASE)
        bad["inbounds"][0]["tag"] = "renamed"
        with pytest.raises(HTTPException, match="422"):
            await validate_core_change(db, 1, CoreCreate(config=bad))
        await db.rollback()
        await validate_core_change(db, 1, CoreCreate(config=deepcopy(BASE)))


@pytest.mark.parametrize("code", [409, -1])
async def test_busy_or_ambiguous_start_cannot_acknowledge_tunnel_revision(monkeypatch, code):
    from PasarGuardNodeBridge import NodeAPIError

    from app.models.core import CoreType
    from app.operation.node import NodeOperation

    bridge = object()
    monkeypatch.setattr("app.operation.node.node_manager.get_node", AsyncMock(return_value=bridge))
    monkeypatch.setattr(NodeOperation, "_start_or_attach_node", AsyncMock(side_effect=NodeAPIError(code, "busy")))
    attach = AsyncMock(return_value=SimpleNamespace(node_version="v1.0.0", core_version="26.10.21"))
    monkeypatch.setattr(NodeOperation, "_attach_if_running", attach)
    result = await NodeOperation.connect_node(
        SimpleNamespace(id=1, name="source", status=NodeStatus.connected),
        SimpleNamespace(type=CoreType.xray),
        [],
        force_start=True,
        require_start_ack=True,
    )
    assert result is None
    attach.assert_not_awaited()


async def test_health_exception_scoped_to_carrier_and_explicit_internal_port(native):
    row = await add(native, enabled=True, health_enabled=True)
    node = SimpleNamespace(address="192.0.2.1", tunnel_api_port=44000, health_echo_port=62555)
    result, _ = compose(BASE, 2, [row], {2: node})
    rule = result["routing"]["rules"][0]
    assert rule == {
        "type": "field",
        "inboundTag": [f"pg-tunnel-{row.id}"],
        "ip": ["127.0.0.1/32"],
        "port": "62555",
        "outboundTag": f"pg-tunnel-{row.id}-health",
    }
    health_outbound = next(item for item in result["outbounds"] if item["tag"] == rule["outboundTag"])
    assert health_outbound["settings"] == {"finalRules": [{"action": "allow", "ip": ["127.0.0.1/32"]}]}
    normal = next(item for item in result["outbounds"] if item["tag"] == f"pg-tunnel-{row.id}")
    assert normal["settings"] == {}
    row.health_enabled = False
    disabled, _ = compose(BASE, 2, [row], {2: node})
    assert not any(item["tag"].endswith("-health") for item in disabled["outbounds"])
