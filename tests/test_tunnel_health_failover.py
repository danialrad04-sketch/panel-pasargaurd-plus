# ruff: noqa: F811
from datetime import UTC, datetime
from unittest.mock import AsyncMock

from app.db.models import NativeTunnel, TunnelFailoverPolicy, TunnelHealthState
from app.models.tunnel import FailoverPolicyInput, TunnelCreate
from app.operation import tunnel as operation
from app.tunnel import failover, health
from tests.test_native_tunnels import add, body, native  # noqa: F401


async def acknowledged(native, **values):
    row = await add(native, enabled=True, health_enabled=True, **values)
    async with native() as db:
        (await db.get(NativeTunnel, row.id)).applied_revision = 1
        await db.commit()
    return row


async def test_measured_carrier_probe_is_redacted_and_revision_fenced(native, monkeypatch):
    row = await acknowledged(native)

    async def request(node, method, path, body, **kwargs):
        if path == "prepare":
            return {"port": 62053}
        return {
            "layer": "encrypted_carrier",
            "tcp": True,
            "udp": True,
            "tcp_rtt_ms": 4,
            "udp_rtt_ms": 5,
            "udp_loss_percent": 0,
            "sample_mbps": 7,
            "udp_sent": 3,
            "udp_received": 3,
            "private": body["password"],
        }

    monkeypatch.setattr(health, "request", request)
    result = await health.probe(row.id, 1, sessions=native)
    assert result["result"]["healthy"] and row.shared_key not in str(result)
    async with native() as db:
        state = await db.get(TunnelHealthState, row.id)
        assert state.successes == 1 and state.failures == 0


async def setup_policy(native):
    row = await acknowledged(native)
    async with native() as db:
        standby = await operation.create(
            db,
            TunnelCreate(
                name="standby",
                source_node_id=1,
                exit_node_id=3,
                inbound_tags=["users"],
                enabled=False,
                health_enabled=True,
            ),
            "owner",
        )
        db.add(
            TunnelHealthState(
                tunnel_id=row.id, revision=1, checked_at=datetime.now(UTC), successes=1, result={"healthy": True}
            )
        )
        await db.commit()
        await failover.save(
            db, 1, FailoverPolicyInput(candidates=[row.id, standby.id], enabled=True, failure_threshold=2), "owner"
        )
        sample = await db.get(TunnelHealthState, row.id)
        sample.failures = 2
        sample.result = {"healthy": False, "status": "measured"}
        await db.commit()
    return row, standby


async def test_verified_failover_switches_equivalent_path(native):
    row, standby = await setup_policy(native)

    async def probe(identity, revision, **kwargs):
        return {"result": {"healthy": identity == standby.id, "status": "measured"}}

    async def deploy(actor, **kwargs):
        return await operation.deploy(
            actor, session_factory=native, restart=AsyncMock(return_value=True), prepare=kwargs["prepare"]
        )

    await failover.run(1, sessions=native, deploy=deploy, health_probe=probe)
    async with native() as db:
        assert not (await db.get(NativeTunnel, row.id)).enabled
        active = await db.get(NativeTunnel, standby.id)
        assert active.enabled and active.revision == active.applied_revision
        policy = await db.get(TunnelFailoverPolicy, 1)
        assert policy.last_result["state"] == "switched" and not policy.suspended


async def test_failed_candidate_rolls_back_and_suspends_automation(native):
    row, standby = await setup_policy(native)

    async def probe(*args, **kwargs):
        return {"result": {"healthy": False, "status": "measured"}}

    async def deploy(actor, **kwargs):
        return await operation.deploy(
            actor, session_factory=native, restart=AsyncMock(return_value=True), prepare=kwargs["prepare"]
        )

    await failover.run(1, sessions=native, deploy=deploy, health_probe=probe)
    async with native() as db:
        old = await db.get(NativeTunnel, row.id)
        assert old.enabled and old.revision == old.applied_revision == 3
        assert not (await db.get(NativeTunnel, standby.id)).enabled
        policy = await db.get(TunnelFailoverPolicy, 1)
        assert policy.suspended and policy.last_result["state"] == "rolled_back"
