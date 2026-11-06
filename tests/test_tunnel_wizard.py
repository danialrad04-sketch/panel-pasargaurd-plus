# ruff: noqa: F811
"""Quick-tunnel wizard: scoring, planning blockers, measured selection, cleanup and cancel."""

import json
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from app.db.models import NativeProvisionJob, NativeTunnel, Node, ServiceAudit
from app.models.tunnel_wizard import WizardPlanInput, WizardStartInput
from app.operation import tunnel as operation
from app.provisioning import service, worker
from app.tunnel import wizard
from tests.test_native_tunnels import add, native  # noqa: F401

GOOD = {"tcp": True, "udp": True, "tcp_rtt_ms": 20, "udp_rtt_ms": 22, "udp_loss_percent": 0, "sample_mbps": 40}


def request(**changes):
    return WizardPlanInput(source_node_id=1, exit_node_id=2, inbound_tags=["users"], **changes)


@pytest.fixture(autouse=True)
def nodes_speak(monkeypatch):
    """Every node reports the pinned adapters and a managed firewall unless a test says otherwise."""

    async def capability(node, name):
        return {"id": name, "available": True, "compatible": True, "capability": {}}

    monkeypatch.setattr(wizard, "node_capability", capability)
    monkeypatch.setattr(wizard, "ingress_managed", AsyncMock(return_value=True))


def test_score_orders_by_priority_and_rejects_unhealthy():
    fast = {**GOOD, "tcp_rtt_ms": 5, "udp_rtt_ms": 5, "sample_mbps": 10}
    wide = {**GOOD, "tcp_rtt_ms": 90, "udp_rtt_ms": 90, "sample_mbps": 200}
    assert wizard.score(fast, "latency") > wizard.score(wide, "latency")
    assert wizard.score(wide, "throughput") > wizard.score(fast, "throughput")
    assert wizard.score({**GOOD, "udp": False}) == 0 and wizard.score(None) == 0
    assert wizard.score({**GOOD, "udp_loss_percent": 100}, "throughput") == 0


def test_rank_keeps_priority_order_on_ties_and_ignores_unhealthy():
    steps = [
        {"id": "a", "state": "healthy", "score": 5.0},
        {"id": "b", "state": "unhealthy", "score": 99.0},
        {"id": "c", "state": "healthy", "score": 5.0},
        {"id": "d", "state": "healthy", "score": 9.0},
    ]
    assert [row["id"] for row in wizard.rank(steps)] == ["d", "a", "c"]


def test_clean_whitelists_numbers_and_drops_raw_text():
    result = wizard.clean({**GOOD, "private": "secret", "tcp_rtt_ms": "5"})
    assert "private" not in result and "tcp_rtt_ms" not in result and result["tcp"] is True


def test_request_validation():
    with pytest.raises(ValueError):
        WizardPlanInput(source_node_id=1, exit_node_id=1, inbound_tags=["users"])
    with pytest.raises(ValueError):
        request(candidates=["paqet", "paqet"])
    with pytest.raises(ValueError):
        WizardPlanInput(source_node_id=1, exit_node_id=2, inbound_tags=["pg-tunnel-x"])
    with pytest.raises(ValueError):
        WizardStartInput(source_node_id=1, exit_node_id=2, inbound_tags=["users"], confirm_restart=False)


async def test_plan_is_read_only_and_reports_candidates(native):
    async with native() as db:
        result = await wizard.plan(db, request())
        assert result["ok"] and not result["blockers"]
        assert [item["id"] for item in result["candidates"]] == list(wizard.DEFAULT_ORDER)
        assert result["user_inbound_readiness"] == "not_probed"
        ports = result["ports"]
        assert len({ports["control"], ports["client"], ports["exit"]}) == 3
        assert await operation.rows(db) == []


async def test_plan_blocks_busy_sources_unknown_inbounds_and_offline_nodes(native):
    await add(native, enabled=True)
    async with native() as db:
        assert "source_has_active_tunnel" in (await wizard.plan(db, request()))["blockers"]
        assert (
            "inbound_unavailable"
            in (await wizard.plan(db, WizardPlanInput(source_node_id=3, exit_node_id=2, inbound_tags=["missing"])))[
                "blockers"
            ]
        )
        node = await db.get(Node, 2)
        node.status = "disabled"
        await db.commit()
        assert "exit_not_connected" in (await wizard.plan(db, request()))["blockers"]


async def test_incompatible_adapters_are_skipped_not_fatal(native, monkeypatch):
    async def capability(node, name):
        return {"id": name, "available": True, "compatible": name != "gost", "capability": {}}

    monkeypatch.setattr(wizard, "node_capability", capability)
    async with native() as db:
        result = await wizard.plan(db, request(candidates=["paqet", "gost-tls"]))
        reasons = {item["id"]: item["reason"] for item in result["candidates"]}
        assert reasons["paqet"] is None and reasons["gost-tls"].startswith("node_adapter_incompatible")
        assert result["ok"]


async def test_plan_blocks_when_no_carrier_is_usable(native, monkeypatch):
    async def capability(node, name):
        return {"id": name, "available": False, "capability": None}

    monkeypatch.setattr(wizard, "node_capability", capability)
    async with native() as db:
        result = await wizard.plan(db, request(candidates=["paqet", "backhaul-tcp"]))
        assert "no_compatible_carrier" in result["blockers"]
        with pytest.raises(HTTPException) as error:
            await wizard.enqueue(db, request(candidates=["paqet"]), "owner")
        assert error.value.status_code == 409


async def test_enqueue_is_singleton_and_audited(native):
    async with native() as db:
        job = await wizard.enqueue(db, request(), "owner")
        assert job["state"] == "queued" and "candidates" not in json.dumps(job, default=str)
        with pytest.raises(HTTPException) as error:
            await wizard.enqueue(db, request(), "owner")
        assert error.value.status_code == 409
        assert (await db.get(NativeProvisionJob, job["id"])).active_target == wizard.SINGLETON
        actions = [row.action for row in (await db.scalars(__import__("sqlalchemy").select(ServiceAudit))).all()]
        assert "provision.wizard.queued" in actions


class Bench:
    """Deterministic deploy + probe doubles that actually flip tunnel flags like the real fenced deploy."""

    def __init__(self, native, results, applies=None):
        self.native, self.results, self.applies = native, results, applies or {}
        self.deploys = 0

    async def deploy(self, actor, **kwargs):
        self.deploys += 1
        async with self.native() as db:
            if kwargs.get("prepare"):
                await kwargs["prepare"](db)
                await db.commit()
        async with self.native() as db:
            rows = await operation.rows(db)
            enabled = next((row for row in rows if row.enabled), None)
            if enabled and self.applies.get(enabled.transport) is False:
                return {"ok": False, "state": "partial_failure", "nodes": []}
            for row in rows:
                row.applied_revision = row.revision
            await db.commit()
        return {"ok": True, "state": "applied", "nodes": []}

    async def probe(self, identity, revision, **kwargs):
        async with self.native() as db:
            row = await db.get(NativeTunnel, identity)
            assert row.enabled and row.applied_revision == row.revision == revision
        return {"result": self.results[row.transport]}


async def run_wizard(native, bench, **changes):
    async with native() as db:
        queued = await wizard.enqueue(db, request(**changes), "owner")
        job = await db.get(NativeProvisionJob, queued["id"])
        job.lease = "claim"
        await db.commit()
        db.expunge(job)
    states = []

    async def advance(state, progress):
        states.append((state, progress))

    outcome = await wizard.run(
        job,
        sessions=native,
        advance=advance,
        deploy=bench.deploy,
        health_probe=bench.probe,
        restart=AsyncMock(return_value=True),
    )
    return outcome, states, job


def healthy(**metrics):
    return {"healthy": True, "status": "measured", **GOOD, **metrics}


async def test_fast_mode_keeps_first_healthy_carrier_and_removes_the_rest(native):
    bench = Bench(native, {"paqet": healthy(), "backhaul": healthy(sample_mbps=500)})
    (state, result), states, _ = await run_wizard(native, bench, mode="fast")
    assert state == "ready" and result["winner"]["id"] == "paqet"
    assert [step["id"] for step in result["steps"] if step["state"] == "healthy"] == ["paqet"]
    async with native() as db:
        rows = await operation.rows(db)
        assert [(row.transport, row.enabled, row.health_enabled) for row in rows] == [("paqet", True, True)]
        assert rows[0].applied_revision == rows[0].revision
    assert states and result["user_inbound_readiness"] == "not_probed"
    assert "private" not in json.dumps(result)


async def test_thorough_mode_measures_all_and_reselects_the_best(native):
    bench = Bench(
        native,
        {
            "paqet": healthy(sample_mbps=10),
            "backhaul": healthy(sample_mbps=300),
            "gost": {"healthy": False, "status": "measured", "tcp": True, "udp": False},
            "frp": healthy(sample_mbps=50),
            "shadowsocks-2022": healthy(sample_mbps=5),
        },
    )
    (state, result), _, _ = await run_wizard(native, bench, mode="thorough", priority="throughput")
    assert state == "ready" and result["winner"]["transport"] == "backhaul"
    assert {step["id"]: step["state"] for step in result["steps"]}["gost-tls"] == "unhealthy"
    async with native() as db:
        rows = await operation.rows(db)
        assert [(row.transport, row.enabled) for row in rows] == [("backhaul", True)]
        # Disabled candidates were removed through the normal guarded path.
        assert sorted(result["removed"]) == ["frp-tcp", "gost-tls", "paqet", "ss2022"]


async def test_apply_failure_moves_on_and_never_leaves_a_failed_candidate_enabled(native):
    bench = Bench(native, {"backhaul": healthy()}, applies={"paqet": False})
    (state, result), _, _ = await run_wizard(native, bench, mode="fast")
    steps = {step["id"]: step for step in result["steps"]}
    assert state == "ready" and steps["paqet"]["state"] == "apply_failed" and result["winner"]["id"] == "backhaul-tcp"
    async with native() as db:
        assert [(row.transport, row.enabled) for row in await operation.rows(db)] == [("backhaul", True)]


async def test_no_healthy_carrier_disables_and_removes_everything(native, monkeypatch):
    dead = {"healthy": False, "status": "unavailable"}
    bench = Bench(native, {name: dead for name in ("paqet", "backhaul", "gost", "frp", "shadowsocks-2022")})
    real = wizard.measure

    async def no_wait(*args, **kwargs):
        async def instant(_):
            return None

        return await real(*args[:5], pause=instant)

    monkeypatch.setattr(wizard, "measure", no_wait)
    (state, result), _, _ = await run_wizard(native, bench)
    assert state == "failed" and result["code"] == "no_healthy_carrier" and "winner" not in result
    async with native() as db:
        assert await operation.rows(db) == []
    assert result["cleanup_required"] == []


async def test_cancel_request_stops_before_the_next_candidate(native):
    bench = Bench(native, {"paqet": healthy(), "backhaul": healthy(sample_mbps=500)})
    async with native() as db:
        queued = await wizard.enqueue(db, request(mode="thorough"), "owner")
        job = await db.get(NativeProvisionJob, queued["id"])
        job.lease, job.cancel_requested = "claim", True
        await db.commit()
        db.expunge(job)
    state, result = await wizard.run(
        job, sessions=native, deploy=bench.deploy, health_probe=bench.probe, restart=AsyncMock(return_value=True)
    )
    assert state == "cancelled" and result["code"] == "cancelled" and "winner" not in result
    assert bench.deploys == 0
    async with native() as db:
        assert await operation.rows(db) == []


async def test_service_cancel_flags_running_wizard_but_cancels_queued_one(native):
    async with native() as db:
        queued = await wizard.enqueue(db, request(), "owner")
        row = await db.get(NativeProvisionJob, queued["id"])
        row.lease = "claim"
        await db.commit()
        await service.cancel(db, queued["id"], "owner")
        row = await db.get(NativeProvisionJob, queued["id"], populate_existing=True)
        assert row.cancel_requested and row.state != "cancelled" and row.active_target == wizard.SINGLETON
        row.lease, row.cancel_requested = None, False
        await db.commit()
        done = await service.cancel(db, queued["id"], "owner")
        assert done["state"] == "cancelled"
        assert (await db.get(NativeProvisionJob, queued["id"], populate_existing=True)).active_target is None


async def test_interrupted_wizard_is_never_requeued(native):
    from datetime import UTC, datetime, timedelta

    async with native() as db:
        queued = await wizard.enqueue(db, request(), "owner")
        row = await db.get(NativeProvisionJob, queued["id"])
        row.lease, row.lease_until, row.attempts = "old", datetime.now(UTC) - timedelta(seconds=5), 1
        row.result = {"steps": []}
        await db.commit()
    assert await worker.claim(native) is None
    async with native() as db:
        row = await db.get(NativeProvisionJob, queued["id"])
        assert row.state == "interrupted" and row.active_target is None and row.result["code"] == "worker_interrupted"
