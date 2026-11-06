# ruff: noqa: F811
"""Native carriers preserve user accounting and share deployment/host fencing."""

from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.db.models import Node
from app.models.carrier import CarrierOptions
from app.models.tunnel import TunnelCreate, TunnelModify, TunnelView
from app.operation import tunnel as operation
from app.tunnel import engines, runtime
from app.tunnel.compiler import compose
from tests.test_native_tunnels import BASE, native  # noqa: F401

CASES = [
    ("backhaul", "tcp"),
    *(("gost", profile) for profile in ("tcp", "ws", "tls", "wss")),
    *(("frp", profile) for profile in ("tcp", "websocket", "kcp", "quic")),
]


def body(engine, profile, **changes):
    return TunnelCreate(
        name=f"native-{engine}-{profile}",
        source_node_id=1,
        exit_node_id=2,
        inbound_tags=["users"],
        enabled=True,
        transport=engine,
        carrier_options={"profile": profile, "client_port": 12181, "exit_port": 12182},
        **changes,
    )


@pytest.mark.parametrize("engine,profile", CASES)
async def test_native_compiler_keys_and_reverse_port_ownership(native, engine, profile):
    async with native() as db:
        row = await operation.create(db, body(engine, profile), "owner")
        nodes = {identity: await db.get(Node, identity) for identity in (1, 2)}
        public = TunnelView.model_validate(row).model_dump(mode="json")
        assert "carrier_key" not in public and row.carrier_key not in str(public)
        assert row.shared_key not in str(public)
        source, _ = compose(BASE, 1, [row], nodes)
        exit, _ = compose(BASE, 2, [row], nodes)
        assert source["outbounds"][-1]["settings"]["servers"][0]["address"] == "127.0.0.1"
        assert source["outbounds"][-1]["settings"]["servers"][0]["port"] == 12181
        assert exit["inbounds"][-1]["listen"] == "127.0.0.1" and exit["inbounds"][-1]["port"] == 12182
        control = 2 if engine == "gost" else 1
        assert engines.control_node(row) == control
        assert row.port in engines.owned_ports(row)[control]
        payload = engines.node_request(row, 1, nodes)
        assert payload["config"]["token"] == row.carrier_key
        assert payload["config"]["role"] == "source"
        assert payload["config"]["peer_ca"] == nodes[control].server_ca
        assert payload["manage_firewall"] is False


@pytest.mark.parametrize("engine,profile", [("backhaul", "tcp"), ("gost", "tls"), ("frp", "quic")])
async def test_engine_start_contract_caps_and_key_preservation(native, monkeypatch, engine, profile):
    async with native() as db:
        row = await operation.create(db, body(engine, profile), "owner")
        previous = row.carrier_key
        edited = TunnelModify(
            name=row.name,
            inbound_tags=row.inbound_tags,
            port=row.port,
            enabled=True,
            transport=engine,
            expected_revision=1,
            carrier_options=row.carrier_options,
        )
        await operation.modify(db, row.id, edited, "owner")
        assert row.carrier_key == previous and row.revision == 2

    async def request(node, method, path, payload=None, **kwargs):
        assert kwargs["namespace"] == engine
        if method == "GET":
            return {
                "contract": 1,
                "engine": engine,
                "installed": True,
                "source_commit": engines.ENGINES[engine]["commit"],
                "version": engines.ENGINES[engine]["version"],
            }
        assert payload["config"]["token"] == previous
        return {"revision": 2, "running": True}

    monkeypatch.setattr("app.paqet.client.request", request)
    monkeypatch.setattr(runtime, "GetDB", native)
    await runtime.apply_node_engines(1)
    await runtime.apply_node_engines(2)
    async with native() as db:
        rotated = await operation.rotate(db, row.id, 2, "owner")
        assert rotated.carrier_key != previous


@pytest.mark.parametrize("engine", ("backhaul", "gost", "frp"))
async def test_control_and_internal_ports_conflict_with_core_or_management(native, engine):
    with pytest.raises(ValidationError):
        body(engine, "tcp", port=12181)
    async with native() as db:
        invalid = body(engine, "tcp", port=62050)
        with pytest.raises(HTTPException) as error:
            await operation.create(db, invalid, "owner")
        assert error.value.status_code == 422


@pytest.mark.parametrize("unsafe", ("https://node.example/path", "0.0.0.0", "::", "224.0.0.1", "node:443", "a\nb"))
def test_carrier_address_cannot_inject_urls_or_config(unsafe):
    with pytest.raises(ValidationError):
        CarrierOptions(control_address=unsafe)


def test_no_false_udp_profiles_or_arbitrary_engine_commands():
    with pytest.raises(ValidationError):
        body("backhaul", "tcpmux")
    with pytest.raises(ValidationError):
        CarrierOptions(command="arbitrary")


async def test_wrong_engine_build_is_rejected_before_apply(native, monkeypatch):
    async with native() as db:
        row = await operation.create(db, body("gost", "tls"), "owner")
        nodes = {identity: await db.get(Node, identity) for identity in (1, 2)}
    network = AsyncMock(
        return_value={"contract": 1, "engine": "gost", "installed": True, "version": "wrong", "source_commit": "wrong"}
    )
    monkeypatch.setattr("app.paqet.client.request", network)
    with pytest.raises(RuntimeError):
        await engines.apply(nodes[1], row, nodes)
    assert network.await_count == 1


async def test_candidate_grace_requires_measured_health_and_is_bounded():
    from app.tunnel.failover import verify_candidate

    pause = AsyncMock()
    probe = AsyncMock(side_effect=[{"result": {"healthy": False}}, {"result": {"healthy": True}}])
    assert await verify_candidate(1, 2, "frp", None, probe, pause=pause)
    assert probe.await_count == 2 and pause.await_count == 1
    probe = AsyncMock(return_value={"result": {"healthy": False}})
    assert not await verify_candidate(1, 2, "backhaul", None, probe, pause=pause)
    assert probe.await_count == 10
    probe.reset_mock()
    assert not await verify_candidate(1, 2, "shadowsocks-2022", None, probe, pause=pause)
    assert probe.await_count == 1


async def test_cancelled_health_probe_preserves_last_valid_measurement(native, monkeypatch):
    import asyncio
    from datetime import UTC, datetime

    from app.db.models import TunnelHealthState
    from app.tunnel.health import probe

    async with native() as db:
        row = await operation.create(
            db,
            TunnelCreate(
                name="cancel-safe",
                source_node_id=1,
                exit_node_id=2,
                inbound_tags=["users"],
                enabled=True,
                health_enabled=True,
            ),
            "owner",
        )
        row.applied_revision = row.revision
        db.add(
            TunnelHealthState(
                tunnel_id=row.id,
                revision=1,
                successes=2,
                checked_at=datetime.now(UTC),
                result={"healthy": True, "status": "measured"},
            )
        )
        await db.commit()
    started = asyncio.Event()

    async def wait_for_node(*args, **kwargs):
        started.set()
        await asyncio.Event().wait()

    monkeypatch.setattr("app.tunnel.health.request", wait_for_node)
    task = asyncio.create_task(probe(row.id, 1, sessions=native))
    await asyncio.wait_for(started.wait(), 2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    async with native() as db:
        state = await db.get(TunnelHealthState, row.id)
        assert state.result["healthy"] is True and state.successes == 2
        assert state.lease is None
