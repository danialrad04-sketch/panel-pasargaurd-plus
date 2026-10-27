"""Owner-only route configuration; applied generations fence DNS and relay access."""

import asyncio
import ipaddress
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import func, select

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import CoreConfig, DNSPolicy, Node, ServiceAudit, SmartDNSNodeState, SmartDNSRoute
from app.paqet.client import request
from app.tunnel.compiler import occupied_port
from app.tunnel.engines import forwarded, options as engine_options, owned_ports
from app.tunnel.guards import lock_state
from app.utils.helpers import fix_datetime_timezone
from config import smartdns_settings


def view(route):
    return {
        name: getattr(route, name)
        for name in (
            "id",
            "node_id",
            "policy_id",
            "domain",
            "public_address",
            "bind_address",
            "tcp_ports",
            "udp_ports",
            "mode",
            "egress_tunnel_id",
            "enabled",
            "revision",
        )
    }


def audit(db, actor, action, target, **details):
    db.add(ServiceAudit(actor=actor, action="smartdns." + action, target_id=str(target), details=details))


async def node_state(db, node_id):
    state = await db.get(SmartDNSNodeState, node_id, with_for_update=True)
    if state is None:
        state = SmartDNSNodeState(node_id=node_id)
        db.add(state)
        await db.flush()
    if state.lease and state.lease_until and fix_datetime_timezone(state.lease_until) > datetime.now(UTC):
        raise HTTPException(409, "SmartDNS node deployment is in progress")
    return state


async def validate(db, route, *, allow_failover=False):
    node = await db.get(Node, route.node_id)
    if not node or not await db.get(DNSPolicy, route.policy_id):
        raise HTTPException(404, "Node or DNS policy not found")
    others = (await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.id != route.id))).all()
    for other in others:
        if other.policy_id == route.policy_id and other.domain == route.domain:
            raise HTTPException(409, "This policy already has a gateway for the domain")
        if other.node_id != route.node_id or not other.enabled or not route.enabled:
            continue
        overlap = (
            other.bind_address == route.bind_address
            or ipaddress.ip_address(other.bind_address).is_unspecified
            or ipaddress.ip_address(route.bind_address).is_unspecified
        )
        if not overlap:
            continue
        if set(other.udp_ports) & set(route.udp_ports) and other.domain != route.domain:
            raise HTTPException(409, "A UDP listener needs one explicit destination domain; use another IP or port")
        if (
            set(other.tcp_ports) & set(route.tcp_ports)
            and (other.mode == "fixed" or route.mode == "fixed")
            and other.domain != route.domain
        ):
            raise HTTPException(409, "Fixed TCP listeners cannot share a port across different destination domains")
    state = await db.get(SmartDNSNodeState, node.id)
    dns_config = state.dns_config if state else {}
    if route.enabled and dns_config.get("enabled") and dns_config.get("port") in route.tcp_ports + route.udp_ports:
        address = dns_config.get("bind_address", "0.0.0.0")
        if (
            address == route.bind_address
            or ipaddress.ip_address(address).is_unspecified
            or ipaddress.ip_address(route.bind_address).is_unspecified
        ):
            raise HTTPException(422, "Gateway port conflicts with the node DNS listener")
    core = await db.get(CoreConfig, node.core_config_id or 1)
    if core and core.config.get("listen_port") in route.udp_ports:
        raise HTTPException(422, "Gateway UDP listener conflicts with WireGuard")
    for port in set(route.tcp_ports + route.udp_ports):
        if port in (node.port, node.api_port, node.tunnel_api_port, node.health_echo_port):
            raise HTTPException(422, "Gateway port conflicts with node management or health")
        if core and occupied_port(core.config, port):
            for inbound in core.config.get("inbounds", []):
                if occupied_port({"inbounds": [inbound]}, port) and (
                    inbound.get("listen", "0.0.0.0") in ("0.0.0.0", "::", route.bind_address)
                    or ipaddress.ip_address(route.bind_address).is_unspecified
                ):
                    raise HTTPException(
                        422, "Gateway listener conflicts with a user core inbound; use a distinct address or port"
                    )
    from app.db.models import NativeTunnel

    if route.egress_tunnel_id:
        from app.tunnel.failover import assert_edit_safe

        if not allow_failover:
            await assert_edit_safe(db, route.egress_tunnel_id)
        tunnel = await db.get(NativeTunnel, route.egress_tunnel_id)
        if not tunnel or tunnel.source_node_id != route.node_id:
            raise HTTPException(422, "Gateway tunnel must start on this gateway node")

    for tunnel in (await db.scalars(select(NativeTunnel).where(NativeTunnel.enabled.is_(True)))).all():
        if owned_ports(tunnel).get(node.id, set()) & set(route.tcp_ports + route.udp_ports):
            raise HTTPException(422, "Gateway listener conflicts with a native carrier")


async def save(db, body, actor, route_id=None):
    await lock_state(db)
    if route_id:
        route = await db.get(SmartDNSRoute, route_id, with_for_update=True)
        if not route:
            raise HTTPException(404, "SmartDNS route not found")
        if route.revision != body.expected_revision:
            raise HTTPException(409, "Route changed; reload")
        if body.node_id != route.node_id:
            raise HTTPException(409, "Gateway node is immutable; disable/apply/remove before replacement")
        state = await node_state(db, route.node_id)
        for name, value in body.model_dump(exclude={"expected_revision"}).items():
            setattr(route, name, value)
        route.revision += 1
    else:
        if await db.scalar(select(func.count()).select_from(SmartDNSRoute)) >= 256:
            raise HTTPException(409, "This release supports at most 256 SmartDNS routes")
        if not await db.get(Node, body.node_id):
            raise HTTPException(404, "Node not found")
        state = await node_state(db, body.node_id)
        route = SmartDNSRoute(**body.model_dump())
        db.add(route)
        await db.flush()
    await validate(db, route)
    state.revision += 1
    audit(db, actor, "save", route.id, revision=route.revision)
    await db.commit()
    return view(route)


async def remove(db, route_id, revision, actor):
    await lock_state(db)
    route = await db.get(SmartDNSRoute, route_id, with_for_update=True)
    if not route:
        raise HTTPException(404, "SmartDNS route not found")
    state = await node_state(db, route.node_id)
    if route.revision != revision:
        raise HTTPException(409, "Route changed; reload")
    if route.enabled or state.applied_revision != state.revision:
        raise HTTPException(409, "Disable and apply this route before removing it")
    await db.delete(route)
    state.revision += 1
    audit(db, actor, "delete", route_id)
    await db.commit()


def panel_config():
    value = urlsplit(smartdns_settings.panel_url)
    if (
        value.scheme != "https"
        or not value.hostname
        or value.username
        or value.query
        or value.fragment
        or value.path not in ("", "/")
    ):
        raise HTTPException(409, "Set SMARTDNS_PANEL_URL to the panel HTTPS origin before deploying a gateway")
    ca = Path(smartdns_settings.panel_ca_file).read_text() if smartdns_settings.panel_ca_file else ""
    if "PRIVATE KEY" in ca:
        raise HTTPException(409, "SmartDNS panel CA file must contain only public certificates")
    return smartdns_settings.panel_url.rstrip("/"), ca


async def deploy(node_id, revision, actor, *, sessions=GetDB, allow_failover=False):
    origin, ca = panel_config()
    claim = str(uuid4())
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        node = await db.get(Node, node_id)
        if not node:
            raise HTTPException(404, "Node not found")
        state = await node_state(db, node_id)
        if state.revision != revision:
            raise HTTPException(409, "Gateway generation changed; reload")
        routes = (await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.node_id == node_id))).all()
        for route in routes:
            await validate(db, route, allow_failover=allow_failover)
        await validate_dns_listener(db, node, state.dns_config)
        compiled = []
        from app.db.models import NativeTunnel

        for route in routes:
            if not route.enabled:
                continue
            item = view(route)
            if route.egress_tunnel_id:
                tunnel = await db.get(NativeTunnel, route.egress_tunnel_id)
                if tunnel and not tunnel.enabled:
                    # Remove its listener/proxy while keeping dependent DNS fail-closed.
                    continue
                if not tunnel or tunnel.revision != tunnel.applied_revision:
                    raise HTTPException(409, "Apply the gateway's active native tunnel before deploying SmartDNS")
                exit_node = await db.get(Node, tunnel.exit_node_id)
                item["egress"] = {
                    "address": "127.0.0.1" if forwarded(tunnel) else exit_node.address,
                    "port": engine_options(tunnel).get("client_port", 11081) if forwarded(tunnel) else tunnel.port,
                    "password": tunnel.shared_key,
                }
            compiled.append(item)
        body = {
            "revision": revision,
            "node_id": node_id,
            "panel_url": origin,
            "panel_ca": ca,
            "routes": compiled,
            "dns": state.dns_config or {"enabled": False, "bind_address": "0.0.0.0", "port": 53},
        }
        state.lease, state.lease_until = claim, datetime.now(UTC) + timedelta(seconds=45)
        audit(db, actor, "apply.start", node_id, revision=revision)
        await db.commit()
    ok = False
    try:
        async with asyncio.timeout(25):
            result = await request(node, "PUT", "config", body, namespace="smartdns")
            ok = isinstance(result, dict) and result.get("revision") == revision and result.get("applied") is True
    except RuntimeError, ValueError, TimeoutError:
        pass
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        state = await db.get(SmartDNSNodeState, node_id, with_for_update=True)
        if state and state.lease == claim:
            if ok and state.revision == revision:
                state.applied_revision = revision
            state.lease = state.lease_until = None
            state.result = {"ok": ok, "revision": revision}
            audit(db, actor, "apply.finish", node_id, ok=ok, revision=revision)
            await db.commit()
    return {"ok": ok, "revision": revision}


async def touch_tunnel(db, tunnel_id):
    """A key/route revision invalidates every compiled gateway that uses it."""
    nodes = set(
        (await db.scalars(select(SmartDNSRoute.node_id).where(SmartDNSRoute.egress_tunnel_id == tunnel_id))).all()
    )
    for identity in nodes:
        state = await node_state(db, identity)
        state.revision += 1


async def rebind_tunnel(db, previous, new):
    routes = (await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.egress_tunnel_id == previous))).all()
    for route in routes:
        route.egress_tunnel_id = new
        route.revision += 1
    for identity in {route.node_id for route in routes}:
        state = await node_state(db, identity)
        state.revision += 1


async def apply_bound_gateways(tunnel_ids, actor, *, sessions=GetDB, allow_failover=False):
    async with sessions() as db:
        identities = set(
            (
                await db.scalars(
                    select(SmartDNSRoute.node_id).where(
                        SmartDNSRoute.enabled.is_(True), SmartDNSRoute.egress_tunnel_id.in_(tunnel_ids)
                    )
                )
            ).all()
        )
        targets = []
        for identity in identities:
            state = await db.get(SmartDNSNodeState, identity)
            if state and state.revision != state.applied_revision:
                targets.append((identity, state.revision))
    results = []
    for identity, revision in targets:
        try:
            result = await deploy(identity, revision, actor, sessions=sessions, allow_failover=allow_failover)
            results.append({"node_id": identity, "ok": result["ok"]})
        except HTTPException, RuntimeError, ValueError:
            results.append({"node_id": identity, "ok": False})
    return results


async def validate_core(db, core_id, candidate):
    from sqlalchemy import or_

    ids = set(
        (
            await db.scalars(
                select(Node.id).where(
                    or_(Node.core_config_id == core_id, Node.core_config_id.is_(None) if core_id == 1 else False)
                )
            )
        ).all()
    )
    for route in (
        await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.node_id.in_(ids), SmartDNSRoute.enabled.is_(True)))
    ).all():
        if candidate.config.get("listen_port") in route.udp_ports:
            raise HTTPException(409, "Core WireGuard listener conflicts with a SmartDNS UDP gateway")
        for port in route.tcp_ports + route.udp_ports:
            for inbound in candidate.config.get("inbounds", []):
                if occupied_port({"inbounds": [inbound]}, port) and (
                    inbound.get("listen", "0.0.0.0") in ("0.0.0.0", "::", route.bind_address)
                    or ipaddress.ip_address(route.bind_address).is_unspecified
                ):
                    raise HTTPException(409, "Core listener conflicts with an enabled SmartDNS gateway")
    for state in (await db.scalars(select(SmartDNSNodeState).where(SmartDNSNodeState.node_id.in_(ids)))).all():
        dns_config = state.dns_config or {}
        if dns_config.get("enabled"):
            address, port = dns_config["bind_address"], dns_config["port"]
            if candidate.config.get("listen_port") == port:
                raise HTTPException(409, "Core WireGuard listener conflicts with native node DNS")
            for inbound in candidate.config.get("inbounds", []):
                if occupied_port({"inbounds": [inbound]}, port) and (
                    inbound.get("listen", "0.0.0.0") in ("0.0.0.0", "::", address)
                    or ipaddress.ip_address(address).is_unspecified
                ):
                    raise HTTPException(409, "Core listener conflicts with native node DNS")


async def validate_dns_listener(db, node, config):
    if not config or not config.get("enabled"):
        return
    port = config["port"]
    address = config["bind_address"]
    if port in (node.port, node.api_port, node.tunnel_api_port, node.health_echo_port):
        raise HTTPException(422, "DNS listener conflicts with management or carrier health")
    core = await db.get(CoreConfig, node.core_config_id or 1)
    if core:
        if core.config.get("listen_port") == port:
            raise HTTPException(422, "DNS listener conflicts with WireGuard")
        for inbound in core.config.get("inbounds", []):
            if occupied_port({"inbounds": [inbound]}, port) and (
                inbound.get("listen", "0.0.0.0") in ("0.0.0.0", "::", address)
                or ipaddress.ip_address(address).is_unspecified
            ):
                raise HTTPException(422, "DNS listener conflicts with a core inbound")
    for route in (
        await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.node_id == node.id, SmartDNSRoute.enabled.is_(True)))
    ).all():
        if port in route.tcp_ports + route.udp_ports and (
            route.bind_address == address
            or ipaddress.ip_address(address).is_unspecified
            or ipaddress.ip_address(route.bind_address).is_unspecified
        ):
            raise HTTPException(422, "DNS listener conflicts with a gateway listener")
    from app.db.models import NativeTunnel

    for tunnel in (await db.scalars(select(NativeTunnel).where(NativeTunnel.enabled.is_(True)))).all():
        if port in owned_ports(tunnel).get(node.id, set()):
            raise HTTPException(422, "DNS listener conflicts with a native tunnel")


async def save_dns(db, node_id, body, actor):
    await lock_state(db)
    node = await db.get(Node, node_id)
    if not node:
        raise HTTPException(404, "Node not found")
    current = await db.get(SmartDNSNodeState, node_id)
    if (current.revision if current else 0) != body.expected_revision:
        raise HTTPException(409, "Node gateway/DNS generation changed; reload")
    state = await node_state(db, node_id)
    value = body.model_dump(exclude={"expected_revision"})
    await validate_dns_listener(db, node, value)
    state.dns_config = value
    state.revision += 1
    audit(db, actor, "node_dns", node_id, revision=state.revision, enabled=body.enabled)
    await db.commit()
    return {"node_id": node_id, "revision": state.revision, "dns": state.dns_config}
