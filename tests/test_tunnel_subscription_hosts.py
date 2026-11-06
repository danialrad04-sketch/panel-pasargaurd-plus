"""Actual host publication API, source-core selection and subscription cache lifecycle."""

from copy import deepcopy
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select

from app.core.hosts import host_manager
from app.core.manager import core_manager
from app.core.xray import XRayConfig
from app.db.base import get_db
from app.db.crud.host import create_host
from app.db.models import CoreConfig, Node, ProxyHost
from app.models.admin import AdminDetails, AdminRoleData
from app.models.host import CreateHost
from app.models.tunnel import TunnelHostPublish
from app.operation import tunnel as operation
from app.routers import tunnel as api
from app.routers.authentication import get_current
from app.subscription.share import process_host
from app.tunnel.hosts import publish_host, validate_host_binding
from tests import test_native_tunnels as fixtures
from tests.test_native_tunnels import OWNER, body, modified

native = fixtures.native


@pytest.fixture
async def publication(native, monkeypatch):
    async with native() as db:
        core = await db.get(CoreConfig, 1)
        config = deepcopy(core.config)
        config["inbounds"][0].update(listen="0.0.0.0", port=2443)
        core.config = config
        node = await db.get(Node, 1)
        node.address = "192.0.2.10"
        await db.commit()
        row = await operation.create(db, body(enabled=True), "owner")
        row.applied_revision = row.revision
        await db.commit()
        template = await create_host(
            db,
            CreateHost(
                remark="Original Germany",
                address={"198.51.100.20"},
                inbound_tag="users",
                port=8443,
                sni={"vpn.example.com"},
                path="/native",
                priority=0,
            ),
        )
    normalized = XRayConfig(config)
    monkeypatch.setattr(core_manager, "_cores", {1: normalized})
    monkeypatch.setattr(core_manager, "_inbounds", ["users"])
    # A different core can reuse a tag. Bound hosts must use source-core defaults.
    other = deepcopy(normalized.inbounds_by_tag["users"])
    other["port"] = 9999
    monkeypatch.setattr(core_manager, "_inbounds_by_tag", {"users": other})
    monkeypatch.setattr(host_manager, "_hosts", {})
    monkeypatch.setattr(host_manager, "_kv", None)
    monkeypatch.setattr(host_manager, "_add_hosts_impl", host_manager._add_hosts_local)
    await host_manager._reset_cache()
    yield native, row, template
    await host_manager._reset_cache()


async def test_real_api_publication_is_idempotent_private_and_owner_only(publication):
    sessions, row, template = publication
    app = FastAPI()
    app.include_router(api.router)
    app.dependency_overrides[get_current] = lambda: OWNER

    async def database():
        async with sessions() as db:
            yield db

    app.dependency_overrides[get_db] = database
    payload = {"expected_revision": row.revision, "template_host_id": template.id}
    async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as client:
        first = await client.post(f"/api/tunnels/{row.id}/hosts", json=payload)
        assert first.status_code == 200, first.text
        host = first.json()
        assert host["address"] == ["192.0.2.10"] and host["port"] == 2443
        assert host["sni"] == ["vpn.example.com"] and host["path"] == "/native"
        assert host["native_tunnel_id"] == row.id and row.shared_key not in first.text
        second = await client.post(f"/api/tunnels/{row.id}/hosts", json=payload | {"remark": "Iran entry"})
        assert second.status_code == 200 and second.json()["id"] == host["id"]
        app.dependency_overrides[get_current] = lambda: AdminDetails(username="operator", role=AdminRoleData())
        denied = await client.post(f"/api/tunnels/{row.id}/hosts", json=payload)
        assert denied.status_code == 403
    prepared = (await host_manager.get_hosts())[host["id"]]
    assert prepared.address == ["192.0.2.10"] and prepared.port == [2443]
    assert await process_host(prepared, {}, ["other-inbound"], {}) is None
    authorized = await process_host(prepared, {}, ["users"], {"vless": {"id": "64a0f9d1-3ad3-4026-aa43-2ac860915dee"}})
    assert authorized is not None
    async with sessions() as db:
        untouched = await db.get(ProxyHost, template.id)
        assert untouched.address == {"198.51.100.20"} and untouched.port == 8443


async def test_host_visibility_tracks_pending_apply_disable_and_rotation(publication):
    sessions, row, template = publication
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
        assert host.id in await host_manager.get_hosts()
        changed = await operation.modify(db, row.id, modified(row, domains=["example.com"]), "owner")
        assert host.id not in await host_manager.get_hosts()
        with pytest.raises(HTTPException, match="409"):
            await publish_host(
                db, row.id, TunnelHostPublish(expected_revision=changed.revision, template_host_id=template.id), OWNER
            )
    failed = await operation.deploy("owner", session_factory=sessions, restart=AsyncMock(return_value=False))
    assert not failed["ok"] and host.id not in await host_manager.get_hosts()
    restart = AsyncMock(return_value=True)
    assert (await operation.deploy("owner", session_factory=sessions, restart=restart))["ok"]
    assert host.id in await host_manager.get_hosts()
    async with sessions() as db:
        current = (await operation.rows(db))[0]
        await operation.rotate(db, row.id, current.revision, "owner")
        assert host.id not in await host_manager.get_hosts()
    await operation.deploy("owner", session_factory=sessions, restart=restart)
    assert host.id in await host_manager.get_hosts()
    async with sessions() as db:
        current = (await operation.rows(db))[0]
        current = await operation.modify(db, row.id, modified(current, enabled=False), "owner")
        assert host.id not in await host_manager.get_hosts()
    await operation.deploy("owner", session_factory=sessions, restart=restart)
    assert host.id not in await host_manager.get_hosts()
    async with sessions() as db:
        current = (await operation.rows(db))[0]
        with pytest.raises(HTTPException, match="409"):
            await operation.remove(db, row.id, current.revision, "owner")


@pytest.mark.parametrize("changes", [{"port": 40900}, {"port": 62052}, {"port": 62050}, {"expected_revision": 99}])
async def test_carrier_control_ports_and_stale_revisions_are_rejected(publication, changes):
    sessions, row, template = publication
    value = {"expected_revision": 1, "template_host_id": template.id} | changes
    async with sessions() as db:
        with pytest.raises(HTTPException):
            await publish_host(db, row.id, TunnelHostPublish(**value), OWNER)
        assert len(list((await db.scalars(select(ProxyHost))).all())) == 1


async def test_loopback_listener_and_sni_address_override_are_rejected(publication):
    sessions, row, template = publication
    async with sessions() as db:
        host = await db.get(ProxyHost, template.id)
        host.use_sni_as_host = True
        await db.commit()
        with pytest.raises(HTTPException, match="422"):
            await publish_host(db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER)
        host = await db.get(ProxyHost, template.id)
        host.use_sni_as_host = False
        core = await db.get(CoreConfig, 1)
        config = deepcopy(core.config)
        config["inbounds"][0]["listen"] = "127.0.0.1"
        core.config = config
        await db.commit()
        with pytest.raises(HTTPException, match="422"):
            await publish_host(db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER)


async def test_older_host_clients_preserve_binding_and_cannot_attach_as_operator(publication):
    sessions, row, template = publication
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
        previous = await db.get(ProxyHost, host.id)
        value = CreateHost.model_validate(host.model_dump(exclude={"native_tunnel_id"}))
        operator = AdminDetails(username="operator", role=AdminRoleData())
        await validate_host_binding(db, value, operator, previous)
        assert value.native_tunnel_id == row.id
        new = CreateHost.model_validate(value.model_dump())
        with pytest.raises(HTTPException, match="403"):
            await validate_host_binding(db, new, operator)


async def test_split_worker_message_rebuilds_bound_hosts_from_database(publication, monkeypatch):
    sessions, row, template = publication
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
        current = await db.get(type(row), row.id)
        current.applied_revision = 0
        await db.commit()
    monkeypatch.setattr("app.core.hosts.GetDB", sessions)
    await host_manager._handle_host_message({"action": "tunnel", "tunnel_ids": [row.id]})
    assert host.id not in await host_manager.get_hosts()


async def test_native_node_worker_publishes_host_refresh_without_overwriting_snapshot(publication, monkeypatch):
    from app.tunnel.hosts import refresh_tunnel_hosts
    from config import runtime_settings
    from role import Role

    sessions, row, template = publication
    async with sessions() as db:
        await publish_host(db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER)
        monkeypatch.setattr(runtime_settings, "role", Role.NODE)
        send = AsyncMock()
        monkeypatch.setattr("app.nats.router.router.publish", send)
        await refresh_tunnel_hosts(db, [row.id])
        assert send.await_args.args[1] == {"action": "tunnel", "tunnel_ids": [row.id]}


async def test_actual_subscription_link_uses_iran_user_port_and_filters_groups(publication):
    from types import SimpleNamespace

    from app.db.models import UserStatus
    from app.models.proxy import ProxyTable
    from app.subscription.links import StandardLinks
    from app.subscription.share import process_inbounds_and_tags

    sessions, row, template = publication
    async with sessions() as db:
        await publish_host(db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER)
    user = SimpleNamespace(
        id=1,
        status=UserStatus.active,
        inbounds=["users"],
        proxy_settings=ProxyTable(vless={"id": "64a0f9d1-3ad3-4026-aa43-2ac860915dee"}),
    )
    links = await process_inbounds_and_tags(user, {}, StandardLinks(), {})
    assert "@192.0.2.10:2443" in links
    assert "40900" not in links and "198.51.100.20" not in links
    user.inbounds = ["other"]
    assert await process_inbounds_and_tags(user, {}, StandardLinks(), {}) == ""


async def test_actual_legacy_host_edit_preserves_native_binding(publication, monkeypatch):
    from app.routers.host import router as host_router

    sessions, row, template = publication
    monkeypatch.setattr("app.operation.host.notification.modify_host", AsyncMock())
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
    app = FastAPI()
    app.include_router(host_router)
    app.dependency_overrides[get_current] = lambda: OWNER

    async def database():
        async with sessions() as db:
            yield db

    app.dependency_overrides[get_db] = database
    value = host.model_dump(mode="json", exclude={"native_tunnel_id"})
    value["remark"] = "Edited by older client"
    async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as client:
        response = await client.put(f"/api/host/{host.id}", json=value)
        assert response.status_code == 200, response.text
        assert response.json()["native_tunnel_id"] == row.id


async def test_api_startup_revalidates_tunnel_hosts_after_offline_worker_changes(publication, monkeypatch):
    sessions, row, template = publication
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
        current = await db.get(type(row), row.id)
        current.enabled = False
        await db.commit()
        # Simulate an old prepared snapshot; no refresh event reached this process.
        assert host.id in await host_manager.get_hosts()
        monkeypatch.setattr(host_manager, "_load_state_from_cache", AsyncMock(return_value=True))
        monkeypatch.setattr(host_manager, "_nats_enabled", False)
        await host_manager.setup(db)
        assert host.id not in await host_manager.get_hosts()
