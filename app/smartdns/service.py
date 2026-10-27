"""Short online byte reservations shared across all gateways and connections.

Unsettled grants remain charged against the cap. A lost worker cannot cause the
same reserved bytes to be allocated twice. Late authenticated settlement corrects
conservative crash accounting in the original period, never the new period.
"""

import hashlib
import hmac
import json
import time
from datetime import UTC, datetime, timedelta

import dns.name
from fastapi import HTTPException
from sqlalchemy import select

from app.db.base import begin_immediate_if_sqlite
from app.db.models import (
    DNSAccount,
    DNSIPLease,
    DNSPolicy,
    DNSUsagePeriod,
    NativeTunnel,
    Node,
    NodeStatus,
    SmartDNSGrant,
    SmartDNSNodeState,
    SmartDNSRoute,
    User,
)
from app.dns.policy import rule_for
from app.operation.dns import USER_LOAD, eligible
from app.utils.helpers import fix_datetime_timezone


def deny(message="SmartDNS access is not authorized", code=403):
    raise HTTPException(code, message)


async def authenticate(db, node_id, secret):
    node = await db.get(Node, node_id, populate_existing=True)
    if not node or not secret or not node.api_key or not hmac.compare_digest(node.api_key, secret):
        deny("Invalid native node authentication", 401)
    return node


async def period(db, account_id, number):
    return await db.scalar(
        select(DNSUsagePeriod)
        .where(DNSUsagePeriod.account_id == account_id, DNSUsagePeriod.period == number)
        .with_for_update()
    )


async def reconcile(db, account_id):
    grants = (
        await db.scalars(
            select(SmartDNSGrant)
            .where(
                SmartDNSGrant.account_id == account_id,
                SmartDNSGrant.closed.is_(False),
                SmartDNSGrant.expires_at < datetime.now(UTC) - timedelta(seconds=30),
            )
            .with_for_update()
        )
    ).all()
    for grant in grants:
        usage = await period(db, grant.account_id, grant.period)
        if usage:
            usage.relay_reserved -= grant.amount
            usage.relay_bytes += grant.amount
            usage.relay_uncertain_bytes += grant.amount
        grant.used, grant.closed, grant.uncertain = grant.amount, True, True


async def reserve(db, node_id, secret, body):
    node = await authenticate(db, node_id, secret)
    if node.status in (NodeStatus.disabled, NodeStatus.limited):
        deny("Gateway node is disabled or limited")
    if abs(time.time() - body.issued_at) > 30:
        deny("Stale grant request", 409)
    # Reject unknown clients without taking SQLite's global writer slot.
    if await db.get(DNSIPLease, body.source_ip) is None:
        deny()
    await begin_immediate_if_sqlite(db)
    await authenticate(db, node_id, secret)
    node = await authenticate(db, node_id, secret)
    if node.status in (NodeStatus.disabled, NodeStatus.limited):
        deny("Gateway node is disabled or limited")
    lease = await db.get(DNSIPLease, body.source_ip, populate_existing=True)
    now = datetime.now(UTC)
    if not lease or fix_datetime_timezone(lease.expires_at) <= now:
        deny()
    account = await db.get(DNSAccount, lease.account_id, with_for_update=True, populate_existing=True)
    user = await db.get(User, account.user_id, options=[USER_LOAD]) if account else None
    route = await db.get(SmartDNSRoute, body.route_id)
    state = await db.get(SmartDNSNodeState, node_id)
    if not account or not account.enabled or not account.relay_enabled or not user or not eligible(user):
        deny()
    if (
        not route
        or route.node_id != node_id
        or not route.enabled
        or route.revision != body.revision
        or route.policy_id != account.policy_id
        or not state
        or state.revision != state.applied_revision
    ):
        deny()
    if route.egress_tunnel_id:
        tunnel = await db.get(NativeTunnel, route.egress_tunnel_id)
        if not tunnel or not tunnel.enabled or tunnel.revision != tunnel.applied_revision:
            deny("Gateway encrypted egress is pending")
    if body.host != route.domain and not body.host.endswith("." + route.domain):
        deny()
    if body.port not in (route.tcp_ports if body.network == "tcp" else route.udp_ports):
        deny()
    if (body.network == "udp" or route.mode == "fixed") and body.host != route.domain:
        deny()
    policy = await db.get(DNSPolicy, account.policy_id)
    matched = rule_for(dns.name.from_text(body.host), policy.rules) if policy else None
    if not policy or (matched and matched["action"] == "block"):
        deny()
    fingerprint = hashlib.sha256(json.dumps(body.model_dump(mode="json"), sort_keys=True).encode()).hexdigest()
    existing = await db.get(SmartDNSGrant, str(body.request_id))
    if existing:
        if (
            existing.node_id != node_id
            or existing.account_id != account.id
            or existing.fingerprint != fingerprint
            or existing.closed
            or fix_datetime_timezone(existing.expires_at) <= now
        ):
            deny("Grant request conflict", 409)
        return {"grant_id": existing.id, "amount": existing.amount, "lease_ms": 2000}
    await reconcile(db, account.id)
    usage = await period(db, account.id, account.period)
    second = int(time.time())
    count = account.relay_rate_count if account.relay_rate_second == second else 0
    if count >= account.relay_rps:
        deny("SmartDNS authorization rate exceeded", 429)
    available = (
        account.relay_byte_limit - usage.relay_bytes - usage.relay_reserved if account.relay_byte_limit else body.amount
    )
    amount = min(body.amount, available)
    if amount <= 0:
        deny("SmartDNS byte budget exhausted")
    account.relay_rate_second, account.relay_rate_count = second, count + 1
    grant = SmartDNSGrant(
        id=str(body.request_id),
        account_id=account.id,
        period=account.period,
        node_id=node_id,
        route_id=route.id,
        fingerprint=fingerprint,
        amount=amount,
        expires_at=now + timedelta(seconds=3),
    )
    db.add(grant)
    usage.relay_reserved += amount
    usage.relay_reservations += 1
    await db.commit()
    return {"grant_id": grant.id, "amount": amount, "lease_ms": 2000}


async def settle(db, node_id, secret, body):
    await authenticate(db, node_id, secret)
    await begin_immediate_if_sqlite(db)
    await authenticate(db, node_id, secret)
    grant = await db.get(SmartDNSGrant, str(body.grant_id))
    if not grant or grant.node_id != node_id:
        deny("Grant not found", 404)
    # Account lock serializes all reservations and settlements across workers.
    await db.get(DNSAccount, grant.account_id, with_for_update=True)
    grant = await db.get(SmartDNSGrant, grant.id, with_for_update=True, populate_existing=True)
    if body.used > grant.amount:
        deny("Settlement exceeds its reservation", 422)
    if grant.closed and not grant.uncertain:
        if grant.used != body.used:
            deny("Settlement already finalized", 409)
        return {"settled": True}
    usage = await period(db, grant.account_id, grant.period)
    if grant.uncertain:
        usage.relay_bytes -= grant.amount - body.used
        usage.relay_uncertain_bytes -= grant.amount
    else:
        usage.relay_reserved -= grant.amount
        usage.relay_bytes += body.used
    grant.used, grant.closed, grant.uncertain = body.used, True, False
    await db.commit()
    return {"settled": True}


async def managed_rules(db, account):
    if not account.relay_enabled:
        return []
    rows = (
        await db.execute(
            select(SmartDNSRoute, SmartDNSNodeState)
            .join(SmartDNSNodeState, SmartDNSNodeState.node_id == SmartDNSRoute.node_id)
            .where(SmartDNSRoute.policy_id == account.policy_id, SmartDNSRoute.enabled.is_(True))
            .order_by(SmartDNSRoute.id)
        )
    ).all()
    result = []
    for route, state in rows:
        ready = state.revision == state.applied_revision
        if route.egress_tunnel_id:
            tunnel = await db.get(NativeTunnel, route.egress_tunnel_id)
            ready = ready and bool(tunnel and tunnel.enabled and tunnel.revision == tunnel.applied_revision)
        result.append(
            {
                "domain": route.domain,
                "action": "rewrite" if ready else "unavailable",
                "addresses": [route.public_address],
                "ttl": 30,
                "exact": route.mode == "fixed" or bool(route.udp_ports),
            }
        )
    return result


async def maintain(*, sessions=None):
    from sqlalchemy import delete

    from app.db.base import GetDB

    sessions = sessions or GetDB
    async with sessions() as db:
        identities = list(
            (
                await db.scalars(
                    select(SmartDNSGrant.account_id)
                    .where(
                        SmartDNSGrant.closed.is_(False),
                        SmartDNSGrant.expires_at < datetime.now(UTC) - timedelta(seconds=30),
                    )
                    .distinct()
                    .limit(200)
                )
            ).all()
        )
    for identity in identities:
        async with sessions() as db:
            await begin_immediate_if_sqlite(db)
            if await db.get(DNSAccount, identity, with_for_update=True):
                await reconcile(db, identity)
            await db.commit()
    async with sessions() as db:
        await db.execute(
            delete(SmartDNSGrant).where(
                SmartDNSGrant.closed.is_(True), SmartDNSGrant.expires_at < datetime.now(UTC) - timedelta(days=7)
            )
        )
        await db.commit()
