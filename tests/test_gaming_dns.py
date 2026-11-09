from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import event, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db.base import Base
from app.db.models import (
    CoreConfig,
    DNSAccount,
    DNSIPLease,
    DNSPolicy,
    Node,
    NodeStatus,
    SmartDNSNodeState,
    SmartDNSRoute,
    TunnelDeployment,
    User,
)
from app.models.admin import AdminDetails, AdminRoleData
from app.models.dns import DNSAccountCreate
from app.models.smartdns_gaming import GamingApplyInput, GamingAttachInput, GamingPlanInput
from app.operation import dns as dns_operation
from app.smartdns import control, gaming
from config import smartdns_settings

OWNER = AdminDetails(username="owner", role=AdminRoleData(is_owner=True))
CORE = {"inbounds": [{"tag": "users", "port": 8443, "protocol": "vless", "settings": {}}], "outbounds": []}


@pytest.fixture
async def world(tmp_path, monkeypatch):
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/gaming.db", connect_args={"timeout": 10})

    @event.listens_for(engine.sync_engine, "connect")
    def configure(connection, _):
        connection.execute("PRAGMA journal_mode=WAL")

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    async with sessions() as db:
        core = CoreConfig(name="core", config=deepcopy(CORE))
        db.add(core)
        await db.flush()
        for name in ("Edge", "Other"):
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
        db.add_all([TunnelDeployment(id=1), User(username="player")])
        await db.commit()
    monkeypatch.setattr(smartdns_settings, "panel_url", "https://panel.example")
    monkeypatch.setattr(smartdns_settings, "panel_ca_file", "")
    monkeypatch.setattr(gaming, "adapter_ready", AsyncMock(return_value=None))
    deploy = AsyncMock(return_value={"ok": True, "revision": 0})
    monkeypatch.setattr(control, "deploy", deploy)
    monkeypatch.setattr(dns_operation, "get_secret_key", AsyncMock(return_value="test-signing-key"))
    yield SimpleNamespace(sessions=sessions, deploy=deploy)
    await engine.dispose()


def plan_body(**changes):
    values = {
        "node_id": 1,
        "name": "Gaming",
        "public_address": "203.0.113.10",
        "services": ["playstation", "xbox"],
        **changes,
    }
    return GamingPlanInput(**values)


def apply_body(**changes):
    return GamingApplyInput(**plan_body(**changes).model_dump(), confirm_apply=True)


async def counts(world):
    async with world.sessions() as db:
        return [
            await db.scalar(select(func.count()).select_from(model))
            for model in (DNSPolicy, SmartDNSRoute, SmartDNSNodeState)
        ]


def test_suffix_cover_keeps_only_parents():
    assert gaming.suffix_cover(["a.example.com", "example.com", "b.test", "xexample.com"]) == [
        "b.test",
        "example.com",
        "xexample.com",
    ]


def test_inputs_are_strict():
    with pytest.raises(ValidationError):
        plan_body(public_address="127.0.0.1")
    with pytest.raises(ValidationError):
        plan_body(custom_domains=["*.bad.example"])
    with pytest.raises(ValidationError):
        GamingApplyInput(**plan_body().model_dump())
    with pytest.raises(ValidationError):
        GamingAttachInput(user_id=1, client_ips=["1.1.1.1"])
    assert GamingAttachInput(user_id=1, client_ips=["2.2.2.2", "2.2.2.2"], byte_limit=0).client_ips == ["2.2.2.2"]


async def test_plan_is_read_only_and_reports_coverage(world):
    async with world.sessions() as db:
        result = await gaming.plan(db, plan_body())
    assert result["ok"] is True and result["blockers"] == []
    assert result["game_acceptance"] == "untested"
    assert "direct_egress" in result["warnings"] and "udp_not_covered" in result["warnings"]
    assert result["routes"] < result["policy"]["domains"] or result["routes"] == result["policy"]["domains"]
    assert {item["service"] for item in result["coverage"]} == {"playstation", "xbox"}
    assert result["dns"]["will_enable"] is True and result["dns"]["port"] == 53
    assert await counts(world) == [0, 0, 0]
    world.deploy.assert_not_awaited()


async def test_plan_blockers(world, monkeypatch):
    async def codes(**changes):
        async with world.sessions() as db:
            return {item["code"] for item in (await gaming.plan(db, plan_body(**changes)))["blockers"]}

    assert await codes(node_id=99) == {"node_not_found"}
    assert "unknown_service" in await codes(services=["nope"])
    assert "egress_not_found" in await codes(egress_tunnel_id=5)
    monkeypatch.setattr(smartdns_settings, "panel_url", "")
    assert "panel_url_missing" in await codes()
    monkeypatch.setattr(smartdns_settings, "panel_url", "https://panel.example")
    monkeypatch.setattr(gaming, "adapter_ready", AsyncMock(return_value="node_adapter_unavailable"))
    assert "node_adapter_unavailable" in await codes()
    monkeypatch.setattr(gaming, "adapter_ready", AsyncMock(return_value=None))
    async with world.sessions() as db:
        (await db.get(Node, 1)).status = NodeStatus.error
        await db.commit()
    assert "node_not_connected" in await codes()
    assert await counts(world) == [0, 0, 0]


async def test_plan_blocks_port_conflict_with_user_inbound(world):
    async with world.sessions() as db:
        core = await db.get(CoreConfig, 1)
        core.config = {**core.config, "inbounds": [{"tag": "users", "port": 443, "protocol": "vless", "settings": {}}]}
        await db.commit()
    async with world.sessions() as db:
        result = await gaming.plan(db, plan_body())
    assert [item["code"] for item in result["blockers"]] == ["validation_failed"]
    assert "user core inbound" in result["blockers"][0]["detail"]
    assert await counts(world) == [0, 0, 0]


async def test_apply_creates_everything_once_and_deploys(world):
    async with world.sessions() as db:
        result = await gaming.apply(db, apply_body(), "owner")
    assert result["applied"] is True and result["game_acceptance"] == "untested"
    world.deploy.assert_awaited_once_with(1, result["revision"], "owner")
    async with world.sessions() as db:
        routes = (await db.scalars(select(SmartDNSRoute))).all()
        state = await db.get(SmartDNSNodeState, 1)
        policy = await db.get(DNSPolicy, result["policy_id"])
        assert len(routes) == len(result["route_ids"]) > 0
        assert all(r.enabled and r.mode == "sniff" and r.tcp_ports == [80, 443] and r.udp_ports == [] for r in routes)
        assert {r.public_address for r in routes} == {"203.0.113.10"}
        assert state.revision == result["revision"] and state.dns_config["enabled"] is True
        assert all(rule["addresses"] == ["203.0.113.10"] for rule in policy.rules)
        assert {r.domain for r in routes} <= {rule["domain"] for rule in policy.rules}
    async with world.sessions() as db:
        listed = await gaming.profiles(db)
    assert listed[0]["policy_id"] == result["policy_id"] and listed[0]["accounts"] == 0
    assert listed[0]["applied"] is False  # the mocked deploy did not record an applied revision


async def test_failed_push_keeps_configuration_fail_closed(world):
    world.deploy.side_effect = HTTPException(409, "node unreachable")
    async with world.sessions() as db:
        result = await gaming.apply(db, apply_body(), "owner")
    assert result["applied"] is False and "unreachable" in result["detail"]
    assert (await counts(world))[1] > 0


async def test_apply_is_all_or_nothing_when_a_route_is_invalid(world, monkeypatch):
    original = control.validate
    calls = {"n": 0}

    async def flaky(db, route, **kw):
        calls["n"] += 1
        if calls["n"] == 3:
            raise HTTPException(422, "boom")
        return await original(db, route, **kw)

    monkeypatch.setattr(control, "validate", flaky)
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await gaming.apply(db, apply_body(), "owner")
    assert error.value.status_code == 409 and error.value.detail["blockers"][0]["code"] == "validation_failed"
    assert await counts(world) == [0, 0, 0]
    world.deploy.assert_not_awaited()


async def test_second_profile_with_same_name_is_blocked(world):
    async with world.sessions() as db:
        await gaming.apply(db, apply_body(), "owner")
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await gaming.apply(db, apply_body(), "owner")
    assert error.value.detail["blockers"][0]["code"] == "policy_name_taken"
    assert (await counts(world))[0] == 1


async def test_second_profile_with_other_services_coexists(world):
    async with world.sessions() as db:
        first = await gaming.apply(db, apply_body(), "owner")
    async with world.sessions() as db:
        second = await gaming.apply(db, apply_body(name="Steam", services=["steam"]), "owner")
    assert second["revision"] == first["revision"] + 1
    assert (await counts(world))[0] == 2


async def test_egress_must_be_applied_on_the_gateway_node(world):
    from app.db.models import NativeTunnel

    async with world.sessions() as db:
        db.add(
            NativeTunnel(
                name="t",
                source_node_id=2,
                exit_node_id=1,
                inbound_tags=["users"],
                enabled=True,
                port=40000,
                shared_key="k" * 32,
                revision=2,
                applied_revision=1,
            )
        )
        await db.commit()
    async with world.sessions() as db:
        result = await gaming.plan(db, plan_body(egress_tunnel_id=1))
    assert {item["code"] for item in result["blockers"]} >= {"egress_wrong_source", "egress_not_applied"}
    assert "direct_egress" not in result["warnings"]


async def test_attach_user_enables_relay_with_explicit_limit_and_leases(world):
    async with world.sessions() as db:
        result = await gaming.apply(db, apply_body(), "owner")
    body = GamingAttachInput(user_id=1, client_ips=["198.51.100.7", "198.51.100.8"], byte_limit=5_000_000)
    async with world.sessions() as db:
        done = await gaming.attach(db, result["policy_id"], body, OWNER)
    async with world.sessions() as db:
        assert done["portal_path"].startswith("/dns-sub/") and len(done["portal_path"]) > 40
        account = await db.get(DNSAccount, done["account_id"])
        assert account.policy_id == result["policy_id"]
        assert account.relay_enabled and account.relay_byte_limit == 5_000_000
        assert {row.ip for row in (await db.scalars(select(DNSIPLease))).all()} == {"198.51.100.7", "198.51.100.8"}
    # Attaching again reuses the account and keeps at most three leases.
    async with world.sessions() as db:
        again = await gaming.attach(db, result["policy_id"], body, OWNER)
    assert again["account_id"] == done["account_id"]


async def test_attach_rejects_other_policy_and_non_gaming_policy(world):
    async with world.sessions() as db:
        gaming_profile = await gaming.apply(db, apply_body(), "owner")
        plain = DNSPolicy(name="plain", rules=[])
        db.add(plain)
        await db.commit()
        plain_id = plain.id
    body = GamingAttachInput(user_id=1, client_ips=["198.51.100.7"], byte_limit=1)
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await gaming.attach(db, plain_id, body, OWNER)
    assert error.value.status_code == 409
    async with world.sessions() as db:
        await dns_operation.create_account(db, DNSAccountCreate(user_id=1, policy_id=plain_id), OWNER)
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await gaming.attach(db, gaming_profile["policy_id"], body, OWNER)
    assert "another policy" in error.value.detail
