"""Native Paqet configuration, private credentials and node contract boundaries."""

from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from app.db.base import get_db
from app.db.models import NativeTunnel
from app.models.tunnel import TunnelCreate, TunnelModify
from app.operation import tunnel as operation
from app.paqet.compiler import node_request, store_options
from app.paqet.models import BLOCKS, PaqetOptions
from app.routers import tunnel as api
from app.routers.authentication import get_current
from app.tunnel.compiler import compose
from tests import test_native_tunnels as tunnel_fixtures
from tests.test_native_tunnels import BASE, OWNER

native = tunnel_fixtures.native


def body(**changes):
    values = {
        "name": "Native Paqet",
        "transport": "paqet",
        "source_node_id": 1,
        "exit_node_id": 2,
        "inbound_tags": ["users"],
        "enabled": True,
    }
    values.update(changes)
    return TunnelCreate(**values)


@pytest.mark.parametrize("mode", ["normal", "fast", "fast2", "fast3", "manual"])
@pytest.mark.parametrize("block", BLOCKS)
def test_every_upstream_mode_and_block_is_accepted(mode, block):
    value = PaqetOptions(kcp={"mode": mode, "block": block}, allow_insecure=block in ("none", "null"))
    assert value.kcp.mode == mode and value.kcp.block == block


@pytest.mark.parametrize(
    "value",
    [
        {"kcp": {"dshard": 10}},
        {"kcp": {"dshard": 128, "pshard": 128}},
        {"kcp": {"smuxkalive": 8, "smuxktimeout": 8}},
        {"kcp": {"block": "none"}},
        {"kcp": {"mtu": 1501}},
        {"kcp": {"tcpbuf": 1024}},
        {"conn": 2, "client_network": {"ipv4": {"addr": "192.0.2.1:9900", "router_mac": "00:11:22:33:44:55"}}},
        {"client_network": {"ipv6": {"addr": "192.0.2.1:0", "router_mac": "00:11:22:33:44:55"}}},
        {"client_network": {"tcp": {"local_flag": ["INVALID"]}}},
        {"forwards": [{"listen": "127.0.0.1:9000", "target": "127.0.0.1:8000", "protocol": "udp"}]},
        {
            "allow_unmetered": True,
            "forwards": [{"listen": "127.0.0.1:11081", "target": "127.0.0.1:8000", "protocol": "tcp"}],
        },
    ],
)
def test_invalid_or_ineffective_settings_are_rejected(value):
    with pytest.raises(ValidationError):
        PaqetOptions(**value)


async def test_full_native_route_uses_loopback_bridges_and_raw_paqet(native):
    async with native() as db:
        row = await operation.create(db, body(), "owner")
    nodes = {1: SimpleNamespace(address="192.0.2.10"), 2: SimpleNamespace(address="198.51.100.20")}
    source, excluded = compose(BASE, 1, [row], nodes)
    exit_cfg, exit_excluded = compose(BASE, 2, [row], nodes)
    assert source["outbounds"][-1]["settings"]["servers"][0]["address"] == "127.0.0.1"
    assert source["outbounds"][-1]["settings"]["servers"][0]["port"] == 11081
    assert exit_cfg["inbounds"][-1]["listen"] == "127.0.0.1" and exit_cfg["inbounds"][-1]["port"] == 11082
    client = node_request(row, 1, nodes)
    server = node_request(row, 2, nodes)
    assert client["config"]["transport"]["kcp"]["key"] == server["config"]["transport"]["kcp"]["key"] == row.paqet_key
    assert client["config"]["forward"] == [
        {"listen": "127.0.0.1:11081", "target": "127.0.0.1:11082", "protocol": p} for p in ("tcp", "udp")
    ]
    assert client["peer"] == "198.51.100.20" and server["peer"] == "192.0.2.10"
    assert not excluded and exit_excluded == {f"pg-tunnel-{row.id}"}


async def test_actual_api_hides_and_preserves_paqet_and_socks_secrets(native):
    options = PaqetOptions(
        allow_unmetered=True,
        socks5=[{"listen": "127.0.0.1:1080", "username": "private-user", "password": "socks-private-secret"}],
    )
    async with native() as db:
        row = await operation.create(db, body(paqet_key="paqet-private-manual-key", paqet_options=options), "owner")
    app = FastAPI()
    app.include_router(api.router)
    app.dependency_overrides[get_current] = lambda: OWNER

    async def database():
        async with native() as db:
            yield db

    app.dependency_overrides[get_db] = database
    async with AsyncClient(transport=ASGITransport(app), base_url="http://native") as client:
        response = await client.get("/api/tunnels")
        assert response.status_code == 200
        assert "paqet-private-manual-key" not in response.text and "socks-private-secret" not in response.text
        public = response.json()["items"][0]
        assert public["paqet_options"]["socks5"][0]["authenticated"] is True and "paqet_key" not in public
        value = {
            k: public[k] for k in ("name", "inbound_tags", "domains", "port", "enabled", "transport", "paqet_options")
        }
        del value["paqet_options"]["socks5"][0]["authenticated"]
        value["expected_revision"] = public["revision"]
        response = await client.put(f"/api/tunnels/{row.id}", json=value)
        assert response.status_code == 200 and "socks-private-secret" not in response.text
    async with native() as db:
        saved = await db.get(NativeTunnel, row.id)
        assert (
            saved.paqet_key == "paqet-private-manual-key"
            and saved.paqet_options["socks5"][0]["password"] == "socks-private-secret"
        )
        await operation.rotate(db, row.id, saved.revision, "owner")
        assert saved.paqet_key != "paqet-private-manual-key"


@pytest.mark.parametrize("port_field,value", [("client_port", 11080), ("exit_port", 11080), ("exit_port", 62052)])
async def test_native_ports_cannot_collide_with_core_or_control_api(native, port_field, value):
    async with native() as db:
        with pytest.raises(HTTPException):
            await operation.create(db, body(paqet_options=PaqetOptions(**{port_field: value})), "owner")


def test_public_socks_requires_complete_authentication():
    with pytest.raises(HTTPException):
        store_options(PaqetOptions(allow_unmetered=True, socks5=[{"listen": "0.0.0.0:1080"}]))
    with pytest.raises(HTTPException):
        store_options(PaqetOptions(allow_unmetered=True, socks5=[{"listen": "127.0.0.1:1080", "username": "user"}]))


async def test_transport_cannot_change_and_leave_an_orphan_engine(native):
    async with native() as db:
        row = await operation.create(db, body(), "owner")
        update = TunnelModify(
            name=row.name, inbound_tags=row.inbound_tags, transport="shadowsocks-2022", expected_revision=row.revision
        )
        with pytest.raises(HTTPException, match="409"):
            await operation.modify(db, row.id, update, "owner")


async def test_real_runtime_hook_requires_paqet_ack_before_core_stop(native, monkeypatch):
    from app.core.xray import XRayConfig
    from app.operation.node import NodeOperation
    from app.tunnel import runtime

    async with native() as db:
        await operation.create(db, body(), "owner")
    monkeypatch.setattr(runtime, "GetDB", native)
    failed = AsyncMock(side_effect=RuntimeError("Paqet unsupported"))
    monkeypatch.setattr("app.paqet.client.apply", failed)
    bridge = SimpleNamespace(start=AsyncMock(), stop=AsyncMock())
    with pytest.raises(RuntimeError, match="unsupported"):
        await NodeOperation._start_or_attach_node(
            bridge,
            SimpleNamespace(id=1, name="source", keep_alive=60),
            XRayConfig(deepcopy(BASE)),
            [],
            object(),
            force_start=True,
        )
    failed.assert_awaited_once()
    bridge.stop.assert_not_awaited()
    bridge.start.assert_not_awaited()


async def test_reused_database_id_does_not_reuse_engine_tombstone(native):
    async with native() as db:
        first = await operation.create(db, body(enabled=False), "owner")
        first_id, engine_id = first.id, first.engine_id
        first.applied_revision = first.revision
        await db.commit()
        await operation.remove(db, first_id, first.revision, "owner")
        second = await operation.create(db, body(enabled=False), "owner")
        assert second.engine_id != engine_id and len(second.engine_id) == 32


async def test_upstream_node_update_cannot_replace_required_paqet_runtime(native):
    from app.tunnel.guards import assert_paqet_node_update_safe

    async with native() as db:
        await assert_paqet_node_update_safe(db, 1)
        await operation.create(db, body(), "owner")
        with pytest.raises(HTTPException, match="409"):
            await assert_paqet_node_update_safe(db, 1)
        with pytest.raises(HTTPException, match="409"):
            await assert_paqet_node_update_safe(db, 2)


@pytest.mark.parametrize(
    "options",
    [
        {"exit_port": 40900},
        {"client_network": {"ipv4": {"addr": "192.0.2.1:62052", "router_mac": "00:11:22:33:44:55"}}},
        {"client_network": {"ipv4": {"addr": "192.0.2.1:11081", "router_mac": "00:11:22:33:44:55"}}},
    ],
)
async def test_raw_port_cannot_collide_with_native_listeners(native, options):
    async with native() as db:
        with pytest.raises(HTTPException):
            await operation.create(db, body(paqet_options=PaqetOptions(**options)), "owner")
