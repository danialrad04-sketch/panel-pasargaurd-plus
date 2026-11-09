"""Gaming DNS profile: one policy, sniff gateways and the node DNS listener, planned and applied as one unit.

Success here means configuration was stored and pushed to the node. It never claims that a game works:
games, UDP traffic and real latency stay untested until measured on a live network.
"""

import asyncio

from fastapi import HTTPException
from sqlalchemy import func, select

from app.db.models import DNSAccount, DNSPolicy, NativeTunnel, Node, NodeStatus, SmartDNSNodeState, SmartDNSRoute
from app.dns.catalog import CatalogGroup, CatalogPolicyInput, catalog, compile_policy
from app.models.smartdns import RouteInput
from app.paqet.client import request
from app.smartdns import control
from app.tunnel.guards import lock_state

MAX_ROUTES = 256


def suffix_cover(domains):
    """Smallest set of suffixes that gateway routes need: a subdomain is served by its parent route."""
    result = []
    for domain in sorted(set(domains), key=lambda value: (value.count("."), value)):
        if not any(domain == kept or domain.endswith("." + kept) for kept in result):
            result.append(domain)
    return sorted(result)


async def adapter_ready(node):
    """The node must run the pinned SmartDNS adapter with byte leases; tests replace this probe."""
    try:
        async with asyncio.timeout(8):
            value = await request(node, "GET", "capabilities", namespace="smartdns")
    except RuntimeError, ValueError, TimeoutError:
        return "node_adapter_unavailable"
    if not (isinstance(value, dict) and value.get("contract") == 1 and value.get("byte_leases") is True):
        return "node_adapter_incompatible"
    return None


def blocker(code, detail=None):
    return {"code": code, "detail": detail}


def _catalog_selection(body):
    services = {service["key"]: service for service in catalog()["services"]}
    unknown = [key for key in body.services if key not in services]
    groups = [
        CatalogGroup(service=key, group=group["key"])
        for key in body.services
        if key in services
        for group in services[key]["groups"]
    ]
    return services, unknown, groups


def _egress_blockers(tunnel, node_id):
    if tunnel is None:
        return [blocker("egress_not_found")]
    result = []
    if tunnel.source_node_id != node_id:
        result.append(blocker("egress_wrong_source", "The egress tunnel must start on the gateway node"))
    if not tunnel.enabled:
        result.append(blocker("egress_disabled"))
    elif tunnel.revision != tunnel.applied_revision:
        result.append(blocker("egress_not_applied"))
    return result


async def prepare(db, body):
    """Stage everything in the open transaction. The caller commits (apply) or rolls back (plan)."""
    blockers, warnings = [], []
    node = await db.get(Node, body.node_id)
    if node is None:
        return {"blockers": [blocker("node_not_found")], "warnings": [], "node": None}
    services, unknown, groups = _catalog_selection(body)
    if unknown:
        blockers.append(blocker("unknown_service", ", ".join(unknown)))
    if node.status != NodeStatus.connected:
        blockers.append(blocker("node_not_connected"))
    if await db.scalar(select(DNSPolicy.id).where(DNSPolicy.name == body.name)):
        blockers.append(blocker("policy_name_taken"))
    try:
        control.panel_config()
    except HTTPException as error:
        blockers.append(blocker("panel_url_missing", error.detail))
    tunnel = None
    if body.egress_tunnel_id:
        tunnel = await db.get(NativeTunnel, body.egress_tunnel_id)
        blockers.extend(_egress_blockers(tunnel, node.id))
    else:
        warnings.append("direct_egress")
    warnings.append("udp_not_covered")
    if node.status == NodeStatus.connected:
        problem = await adapter_ready(node)
        if problem:
            blockers.append(blocker(problem))
    summary = {
        "node": {"id": node.id, "name": node.name},
        "egress_tunnel_id": body.egress_tunnel_id,
        "game_acceptance": "untested",
    }
    if unknown:
        return {"blockers": blockers, "warnings": warnings, **summary}
    try:
        policy_input = compile_policy(
            CatalogPolicyInput(
                name=body.name, groups=groups, relay_addresses=[body.public_address], custom_domains=body.custom_domains
            )
        )
    except ValueError as error:
        return {"blockers": [*blockers, blocker("policy_invalid", str(error))], "warnings": warnings, **summary}
    domains = [rule.domain for rule in policy_input.rules]
    cover = suffix_cover(domains)
    count = await db.scalar(select(func.count()).select_from(SmartDNSRoute))
    if count + len(cover) > MAX_ROUTES:
        blockers.append(blocker("route_limit", f"{count}+{len(cover)}>{MAX_ROUTES}"))
    coverage = []
    for key in body.services:
        owned = {domain for group in services[key]["groups"] for domain in group["domains"]}
        coverage.append(
            {
                "service": key,
                "label": services[key]["label"],
                "domains": len(owned),
                "routes": len([domain for domain in cover if domain in owned]),
            }
        )
    summary.update(
        policy={"name": body.name, "domains": len(domains)},
        routes=len(cover),
        coverage=coverage,
        relay_address=body.public_address,
    )
    if blockers:
        return {"blockers": blockers, "warnings": warnings, **summary}

    # Stage the real objects and run the same validation the manual API uses, so plan and apply cannot disagree.
    try:
        state = await control.node_state(db, node.id)
        policy = DNSPolicy(name=body.name, rules=[rule.model_dump() for rule in policy_input.rules])
        db.add(policy)
        await db.flush()
        routes = []
        for domain in cover:
            route = SmartDNSRoute(
                **RouteInput(
                    node_id=node.id,
                    policy_id=policy.id,
                    domain=domain,
                    public_address=body.public_address,
                    bind_address=body.bind_address,
                    tcp_ports=[80, 443],
                    mode="sniff",
                    enabled=True,
                    egress_tunnel_id=body.egress_tunnel_id,
                ).model_dump()
            )
            db.add(route)
            await db.flush()
            await control.validate(db, route)
            routes.append(route)
        dns_config = dict(state.dns_config or {})
        enabling = not dns_config.get("enabled")
        if enabling:
            dns_config = {"enabled": True, "bind_address": body.bind_address, "port": body.dns_port}
        await control.validate_dns_listener(db, node, dns_config)
    except HTTPException as error:
        return {"blockers": [blocker("validation_failed", str(error.detail))], "warnings": warnings, **summary}
    summary["dns"] = {
        "enabled": True,
        "will_enable": enabling,
        **{key: dns_config[key] for key in ("bind_address", "port")},
    }
    return {
        "blockers": [],
        "warnings": warnings,
        "staged": (state, policy, routes, dns_config, enabling),
        **summary,
    }


def public(result):
    return {"ok": not result["blockers"], **{key: value for key, value in result.items() if key != "staged"}}


async def plan(db, body):
    try:
        return public(await prepare(db, body))
    finally:
        await db.rollback()


async def apply(db, body, actor):
    await lock_state(db)
    result = await prepare(db, body)
    if result["blockers"]:
        await db.rollback()
        raise HTTPException(409, {"blockers": result["blockers"]})
    state, policy, routes, dns_config, enabling = result["staged"]
    if enabling:
        state.dns_config = dns_config
    state.revision += 1
    revision = state.revision
    control.audit(db, actor, "gaming", policy.id, node_id=body.node_id, routes=len(routes), revision=revision)
    ids = [route.id for route in routes]
    policy_id, node_id = policy.id, body.node_id
    await db.commit()
    # Stored configuration is fail-closed; a failed push can be retried with /nodes/{id}/apply.
    try:
        pushed = await control.deploy(node_id, revision, actor)
    except HTTPException as error:
        pushed = {"ok": False, "revision": revision, "detail": str(error.detail)}
    return {
        "policy_id": policy_id,
        "node_id": node_id,
        "route_ids": ids,
        "revision": revision,
        "applied": bool(pushed["ok"]),
        "detail": pushed.get("detail"),
        "next": "attach_user",
        "game_acceptance": "untested",
    }


async def attach(db, policy_id, body, admin):
    from app.models.dns import DNSAccountCreate, DNSLeaseInput
    from app.operation import dns as operation

    policy_id = (await operation.policy_by_id(db, policy_id)).id
    if not await db.scalar(select(SmartDNSRoute.id).where(SmartDNSRoute.policy_id == policy_id).limit(1)):
        raise HTTPException(409, "This policy is not a gaming profile")
    account = await db.scalar(select(DNSAccount).where(DNSAccount.user_id == body.user_id))
    if account is not None and account.policy_id != policy_id:
        raise HTTPException(409, "This user already has a DNS account on another policy")
    if account is None:
        view = await operation.create_account(db, DNSAccountCreate(user_id=body.user_id, policy_id=policy_id), admin)
        account_id = view["id"] if isinstance(view, dict) else view.id
    else:
        account_id = account.id
    row, _ = await operation.account_by_id(db, account_id, admin, "update", lock=True)
    row.relay_enabled, row.relay_byte_limit, row.relay_rps = True, body.byte_limit, body.grants_per_second
    control.audit(db, admin.username, "account", account_id, enabled=True, byte_limit=body.byte_limit)
    await db.commit()
    leases = [
        await operation.create_lease(db, account_id, DNSLeaseInput(ip=ip, ttl=body.ttl), admin)
        for ip in body.client_ips
    ]
    # The user's own page: it lists their IPs and lets them register the one they play from.
    credential = (await operation.account_credentials(db, account_id, admin))["token"]
    return {
        "account_id": account_id,
        "policy_id": policy_id,
        "leases": leases,
        "byte_limit": body.byte_limit,
        "portal_path": f"/dns-sub/{credential}",
    }


async def profiles(db):
    rows = (
        await db.execute(
            select(SmartDNSRoute, SmartDNSNodeState).join(
                SmartDNSNodeState, SmartDNSNodeState.node_id == SmartDNSRoute.node_id
            )
        )
    ).all()
    grouped = {}
    for route, state in rows:
        item = grouped.setdefault(
            route.policy_id,
            {
                "policy_id": route.policy_id,
                "node_id": route.node_id,
                "routes": 0,
                "applied": True,
                "address": route.public_address,
            },
        )
        item["routes"] += 1
        item["applied"] = item["applied"] and state.revision == state.applied_revision
    names = {
        policy.id: policy.name
        for policy in (await db.scalars(select(DNSPolicy).where(DNSPolicy.id.in_(grouped)))).all()
    }
    accounts = dict(
        (
            await db.execute(
                select(DNSAccount.policy_id, func.count())
                .where(DNSAccount.policy_id.in_(grouped))
                .group_by(DNSAccount.policy_id)
            )
        ).all()
    )
    return [
        {**item, "name": names.get(key, ""), "accounts": accounts.get(key, 0)} for key, item in sorted(grouped.items())
    ]
