"""Opt-in, fenced failover: verify the candidate, otherwise restore and suspend.

Only equivalent user routes on the same source may share a policy. No direct
freedom fallback is introduced. Host bindings follow the selected path only after
its revision is acknowledged, and are hidden during transitions.
"""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import select

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import NativeTunnel, ProxyHost, TunnelFailoverPolicy, TunnelHealthState
from app.operation import tunnel as operation
from app.tunnel.guards import leased, lock_state
from app.tunnel.health import probe
from app.utils.helpers import fix_datetime_timezone


def policy_view(row):
    return {
        key: getattr(row, key)
        for key in (
            "source_node_id",
            "candidates",
            "revision",
            "enabled",
            "suspended",
            "failure_threshold",
            "cooldown_seconds",
            "switched_at",
            "last_result",
        )
    }


async def policies(db):
    return [policy_view(row) for row in (await db.scalars(select(TunnelFailoverPolicy))).all()]


async def assert_edit_safe(db, tunnel_id, *, remove=False, disable_health=False, body=None):
    for policy in (await db.scalars(select(TunnelFailoverPolicy))).all():
        if tunnel_id not in policy.candidates:
            continue
        if leased(policy):
            raise HTTPException(409, "Automatic failover is in progress; wait for its result")
        if remove:
            raise HTTPException(409, "Remove the failover policy before deleting its candidate")
        if policy.enabled and body is not None:
            for identity in policy.candidates:
                other = await db.get(NativeTunnel, identity)
                if (
                    identity != tunnel_id
                    and other
                    and (set(body.inbound_tags) != set(other.inbound_tags) or set(body.domains) != set(other.domains))
                ):
                    raise HTTPException(409, "Disable the failover policy before changing its inbound/domain scope")
        if policy.enabled and disable_health:
            raise HTTPException(409, "Disable the failover policy before disabling carrier monitoring")


async def validate(db, source_id, candidates):
    if len(set(candidates)) != len(candidates):
        raise HTTPException(422, "Duplicate failover candidate")
    rows = []
    for identity in candidates:
        row = await db.get(NativeTunnel, identity)
        if not row or row.source_node_id != source_id or not row.health_enabled:
            raise HTTPException(422, "All candidates need the same source and enabled carrier monitoring")
        rows.append(row)
    shape = {(tuple(sorted(row.inbound_tags)), tuple(sorted(row.domains))) for row in rows}
    if len(shape) != 1:
        raise HTTPException(422, "Failover candidates must select identical user inbounds and domain scopes")
    if len({row.exit_node_id for row in rows}) != len(rows):
        raise HTTPException(422, "Use independent exit nodes for failover")
    return rows


async def save(db, source_id, body, actor):
    await lock_state(db)
    policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
    if policy and leased(policy):
        raise HTTPException(409, "Failover transition is in progress")
    if (policy.revision if policy else 0) != body.expected_revision:
        raise HTTPException(409, "Failover policy changed; reload")
    rows = await validate(db, source_id, body.candidates)
    if body.enabled:
        active = [row for row in rows if row.enabled]
        if len(active) != 1 or active[0].revision != active[0].applied_revision:
            raise HTTPException(409, "Apply exactly one active candidate before enabling automatic failover")
        sample = await db.get(TunnelHealthState, active[0].id)
        if (
            not sample
            or sample.revision != active[0].revision
            or not sample.result.get("healthy")
            or not sample.checked_at
            or datetime.now(UTC) - fix_datetime_timezone(sample.checked_at) > timedelta(minutes=5)
        ):
            raise HTTPException(409, "Run a successful recent carrier probe before enabling automatic failover")
    if policy is None:
        policy = TunnelFailoverPolicy(source_node_id=source_id, candidates=body.candidates)
        db.add(policy)
    else:
        policy.revision += 1
    policy.candidates, policy.enabled, policy.suspended = body.candidates, body.enabled, False
    policy.failure_threshold, policy.cooldown_seconds = body.failure_threshold, body.cooldown_seconds
    operation.audit(db, actor, "failover.policy", source_id, revision=policy.revision, enabled=policy.enabled)
    await db.commit()
    return policy_view(policy)


async def remove(db, source_id, revision, actor):
    await lock_state(db)
    policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
    if not policy:
        raise HTTPException(404, "Failover policy not found")
    if leased(policy) or policy.revision != revision:
        raise HTTPException(409, "Failover policy is busy or changed")
    operation.audit(db, actor, "failover.remove", source_id)
    await db.delete(policy)
    await db.commit()


async def run(source_id, *, sessions=GetDB, deploy=operation.deploy, health_probe=probe):
    claim = str(uuid4())
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
        if not policy or not policy.enabled or policy.suspended or leased(policy):
            return
        rows = await validate(db, source_id, policy.candidates)
        active = [row for row in rows if row.enabled]
        if len(active) != 1 or active[0].revision != active[0].applied_revision:
            return
        current = active[0]
        if policy.switched_at and datetime.now(UTC) - fix_datetime_timezone(policy.switched_at) < timedelta(
            seconds=policy.cooldown_seconds
        ):
            return
        policy.lease, policy.lease_until = claim, datetime.now(UTC) + timedelta(seconds=1500)
        identities = list(policy.candidates)
        current_id, current_revision = current.id, current.revision
        threshold = policy.failure_threshold
        await db.commit()
    outcome = {"state": "healthy"}
    try:
        measured = await health_probe(current_id, current_revision, sessions=sessions, minimum_age=45)
        if measured["result"].get("healthy") or measured["result"].get("status") != "measured":
            return
        async with sessions() as db:
            sample = await db.get(TunnelHealthState, current_id)
            if not sample or sample.revision != current_revision or sample.failures < threshold:
                return
            candidates = []
            for identity in identities:
                row = await db.get(NativeTunnel, identity)
                if identity != current_id and row and not row.enabled:
                    candidates.append((row.id, row.revision))
            if not candidates:
                return
            candidate_id, candidate_revision = candidates[0]
        original_hosts = []

        async def switch(db):
            policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
            old = await db.get(NativeTunnel, current_id, with_for_update=True)
            new = await db.get(NativeTunnel, candidate_id, with_for_update=True)
            if (
                not policy
                or policy.lease != claim
                or old.revision != current_revision
                or new.revision != candidate_revision
                or not old.enabled
                or new.enabled
            ):
                raise HTTPException(409, "Failover state changed")
            await validate(db, source_id, identities)
            from app.smartdns.control import rebind_tunnel

            await rebind_tunnel(db, old.id, new.id)
            old.enabled, new.enabled = False, True
            old.revision += 1
            new.revision += 1
            for host in (await db.scalars(select(ProxyHost).where(ProxyHost.native_tunnel_id == old.id))).all():
                original_hosts.append(host.id)
                host.native_tunnel_id = new.id
            operation.audit(db, "system", "failover.start", source_id, previous=current_id, candidate=candidate_id)

        applied = await deploy("system:failover", session_factory=sessions, prepare=switch)
        confirmed = False
        if applied["ok"]:
            after = await health_probe(candidate_id, candidate_revision + 1, sessions=sessions)
            confirmed = after["result"].get("healthy", False)
        if confirmed:
            outcome = {"state": "switched", "previous": current_id, "active": candidate_id}
        else:

            async def rollback(db):
                policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
                old = await db.get(NativeTunnel, current_id, with_for_update=True)
                new = await db.get(NativeTunnel, candidate_id, with_for_update=True)
                if (
                    not policy
                    or policy.lease != claim
                    or old.revision != current_revision + 1
                    or new.revision != candidate_revision + 1
                ):
                    raise HTTPException(409, "Rollback state changed; inspect the deployment")
                from app.smartdns.control import rebind_tunnel

                await rebind_tunnel(db, new.id, old.id)
                old.enabled, new.enabled = True, False
                old.revision += 1
                new.revision += 1
                for identity in original_hosts:
                    host = await db.get(ProxyHost, identity)
                    if host and host.native_tunnel_id == candidate_id:
                        host.native_tunnel_id = current_id
                operation.audit(
                    db, "system", "failover.rollback", source_id, previous=current_id, candidate=candidate_id
                )

            reverted = await deploy("system:failover-rollback", session_factory=sessions, prepare=rollback)
            outcome = {
                "state": "rolled_back" if reverted["ok"] else "manual_repair_required",
                "previous": current_id,
                "candidate": candidate_id,
                "suspend": True,
            }
    except HTTPException, RuntimeError, ValueError, TimeoutError:
        outcome = {"state": "manual_repair_required", "suspend": True}
    finally:
        async with sessions() as db:
            await begin_immediate_if_sqlite(db)
            policy = await db.get(TunnelFailoverPolicy, source_id, with_for_update=True)
            if policy and policy.lease == claim:
                policy.lease = policy.lease_until = None
                policy.last_result = outcome
                if outcome["state"] in ("switched", "rolled_back", "manual_repair_required"):
                    policy.switched_at = datetime.now(UTC)
                if outcome.get("suspend"):
                    policy.suspended = True
                operation.audit(db, "system", "failover.result", source_id, **outcome)
                await db.commit()


async def monitor(*, sessions=GetDB):
    async with sessions() as db:
        identities = list(
            (
                await db.scalars(
                    select(TunnelFailoverPolicy.source_node_id).where(
                        TunnelFailoverPolicy.enabled.is_(True), TunnelFailoverPolicy.suspended.is_(False)
                    )
                )
            ).all()
        )
    # One transition at a time; per-policy and global deployment fences also cover replicas.
    for identity in identities:
        try:
            await run(identity, sessions=sessions)
        except HTTPException, RuntimeError, ValueError:
            continue
