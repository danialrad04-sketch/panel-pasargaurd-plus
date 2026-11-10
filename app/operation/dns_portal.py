"""Self-service DNS link. The per-account DNS credential is the only secret; it never grants panel access.

A user can see their DNS status and register or remove their own source IPs. Nothing else is writable.
"""

import hmac
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import delete, func, select

from app.db.base import begin_immediate_if_sqlite
from app.db.models import DNSAccount, DNSIPLease, DNSPolicy, ServiceAudit, SmartDNSNodeState, SmartDNSRoute, User
from app.dns.catalog import catalog
from app.models.dns import DNSLeaseInput
from app.operation import dns as operation
from config import dns_settings

# A home IP changes rarely; a week keeps the player connected without silently leaving access open for long.
LEASE_TTL = 7 * 86400
MAX_LEASES = 3
# Doctor-DNS style guard: a shared link cannot be used to rotate through addresses.
MAX_REGISTRATIONS_PER_DAY = 6


def normalize_ip(value):
    try:
        return DNSLeaseInput(ip=value).ip
    except ValidationError:
        raise HTTPException(422, "Invalid IP address") from None


async def find(db, token, *, lock=False):
    if not (16 <= len(token) <= 256):
        raise HTTPException(404, "DNS link not found")
    if lock:
        await begin_immediate_if_sqlite(db)
    stmt = select(DNSAccount).where(DNSAccount.token_hash == operation.digest(token))
    if lock:
        stmt = stmt.with_for_update()
    account = (await db.execute(stmt)).scalar_one_or_none()
    # Compare again in constant time and against the reconstructed credential, so a stale hash never authorizes.
    if account is None or not hmac.compare_digest(
        operation.credential(account, await operation.get_secret_key()), token
    ):
        raise HTTPException(404, "DNS link not found")
    user = await db.get(User, account.user_id, options=[operation.USER_LOAD])
    if user is None:
        raise HTTPException(404, "DNS link not found")
    return account, user


async def servers(db, policy_id):
    """Addresses a player types into the device: gateway nodes whose DNS listener is on and that serve this policy."""
    rows = (
        await db.execute(
            select(SmartDNSRoute.node_id, SmartDNSRoute.public_address)
            .where(SmartDNSRoute.policy_id == policy_id, SmartDNSRoute.enabled.is_(True))
            .distinct()
        )
    ).all()
    result = []
    for node_id, address in rows:
        state = await db.get(SmartDNSNodeState, node_id)
        config = (state.dns_config or {}) if state else {}
        if config.get("enabled"):
            result.append(
                {
                    "address": address,
                    "port": config.get("port", 53),
                    "applied": state.revision == state.applied_revision,
                }
            )
    return result


def covered_services(policy):
    domains = {rule.get("domain") for rule in (policy.rules or [])}
    return [
        {"key": service["key"], "label": service["label"]}
        for service in catalog()["services"]
        if domains & {domain for group in service["groups"] for domain in group["domains"]}
    ]


async def info(db, token, client_ip):
    account, user = await find(db, token)
    policy = await db.get(DNSPolicy, account.policy_id)
    view = await operation.account_view(db, account, user)
    now = datetime.now(UTC)
    leases = (await db.scalars(select(DNSIPLease).where(DNSIPLease.account_id == account.id))).all()
    active = [lease for lease in leases if operation.fix_datetime_timezone(lease.expires_at) > now]
    eligible = bool(view["eligible"] and account.enabled and dns_settings.enabled)
    limit = view["relay_byte_limit"]
    return {
        "username": user.username,
        "enabled": account.enabled,
        "service_enabled": dns_settings.enabled,
        "eligible": eligible,
        "client_ip": client_ip,
        "client_registered": any(lease.ip == client_ip for lease in active),
        "max_leases": MAX_LEASES,
        "lease_days": LEASE_TTL // 86400,
        "leases": [
            {
                "ip": lease.ip,
                "expires_at": operation.fix_datetime_timezone(lease.expires_at),
                "current": lease.ip == client_ip,
            }
            for lease in sorted(active, key=lambda item: item.expires_at)
        ],
        "servers": await servers(db, account.policy_id),
        "doh_url": (dns_settings.public_url or "/dns-query") + "/" + token if dns_settings.enabled else None,
        "policy": {"name": policy.name, "domains": len(policy.rules or [])},
        "services": covered_services(policy),
        "relay": {
            "enabled": account.relay_enabled,
            "byte_limit": limit,
            "used": view["relay_bytes"] + view["relay_reserved"],
            "remaining": max(0, limit - view["relay_bytes"] - view["relay_reserved"]) if limit else None,
            "rate_bps": view["relay_rate_bps"],
        },
        "by_service": await operation.service_usage(db, account.id),
        "queries": view["queries"],
        "game_acceptance": "untested",
    }


async def register(db, token, ip):
    ip = normalize_ip(ip)
    account, user = await find(db, token, lock=True)
    if not (account.enabled and operation.eligible(user) and dns_settings.enabled):
        raise HTTPException(403, "This DNS service is not active for your account")
    actor = f"user:{user.id}"
    recent = await db.scalar(
        select(func.count())
        .select_from(ServiceAudit)
        .where(
            ServiceAudit.actor == actor,
            ServiceAudit.action == "dns.lease",
            ServiceAudit.target_id == str(account.id),
            ServiceAudit.created_at > datetime.now(UTC) - timedelta(days=1),
        )
    )
    if recent >= MAX_REGISTRATIONS_PER_DAY:
        raise HTTPException(429, "Too many IP changes today; try again tomorrow or ask your provider")
    await operation.grant_lease(db, account, ip, LEASE_TTL, actor)
    return await info(db, token, ip)


async def remove(db, token, ip, client_ip):
    ip = normalize_ip(ip)
    account, user = await find(db, token, lock=True)
    await db.execute(delete(DNSIPLease).where(DNSIPLease.account_id == account.id, DNSIPLease.ip == ip))
    operation.audit(db, SimpleNamespace(username=f"user:{user.id}"), "lease.revoke", account.id)
    await db.commit()
    return await info(db, token, client_ip)
