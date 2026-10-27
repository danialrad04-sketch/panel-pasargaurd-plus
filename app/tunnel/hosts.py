"""Native subscription ingress publication. No carrier ports or exit IPs in user links."""

import ipaddress

from fastapi import HTTPException
from sqlalchemy import select

from app.core.manager import core_manager
from app.db.models import CoreConfig, NativeTunnel, Node, ProxyHost
from app.models.host import BaseHost, CreateHost


async def source_inbound(db, tunnel, tag):
    node = await db.get(Node, tunnel.source_node_id)
    if node is None:
        return None
    core = await db.get(CoreConfig, node.core_config_id or 1)
    if core is None:
        return None
    managed = core_manager.validate_core(
        core.config, set(core.exclude_inbound_tags or ()), set(core.fallbacks_inbound_tags or ()), core.type
    )
    return managed.inbounds_by_tag.get(tag)


async def resolve_bound_inbound(db, host):
    tunnel = await db.get(NativeTunnel, host.native_tunnel_id)
    if (
        not tunnel
        or not tunnel.enabled
        or tunnel.applied_revision != tunnel.revision
        or host.inbound_tag not in tunnel.inbound_tags
    ):
        return None
    return await source_inbound(db, tunnel, host.inbound_tag)


async def validate_host_binding(db, host, admin, previous=None):
    old = previous.native_tunnel_id if previous else None
    # Older clients must not silently detach a managed host on an unrelated edit.
    if previous and "native_tunnel_id" not in host.model_fields_set:
        host.native_tunnel_id = old
    if host.native_tunnel_id != old and not admin.role.is_owner:
        raise HTTPException(403, "Only the owner may attach or detach tunnel subscription hosts")
    if host.native_tunnel_id:
        row = await db.get(NativeTunnel, host.native_tunnel_id)
        if not row or host.inbound_tag not in row.inbound_tags:
            raise HTTPException(422, "Host inbound must belong to the selected native tunnel")


async def refresh_tunnel_hosts(db, tunnel_ids, *, local=False):
    from app.core.hosts import host_manager
    from app.db.crud.host import get_hosts

    bound = list((await db.scalars(select(ProxyHost).where(ProxyHost.native_tunnel_id.in_(tunnel_ids)))).all())
    if not bound:
        return
    from config import runtime_settings
    from role import Role

    if not local and runtime_settings.role == Role.NODE:
        from app.nats.message import MessageTopic
        from app.nats.router import router

        await router.publish(MessageTopic.HOST, {"action": "tunnel", "tunnel_ids": list(tunnel_ids)})
        return
    ids = {h.id for h in bound}
    dependents = [
        h
        for h in await get_hosts(db)
        if ((h.transport_settings or {}).get("xhttp_settings") or {}).get("download_settings") in ids
    ]
    if local:
        await host_manager._add_hosts_local(db, [*bound, *dependents])
    else:
        await host_manager.add_hosts(db, [*bound, *dependents])


async def publish_host(db, tunnel_id, body, admin):
    from sqlalchemy import func

    from app.core.hosts import host_manager
    from app.db.crud.host import get_or_create_inbound
    from app.operation.tunnel import audit, get_locked

    tunnel = await get_locked(db, tunnel_id, body.expected_revision)
    if not tunnel.enabled or tunnel.revision != tunnel.applied_revision:
        raise HTTPException(409, "Enable and successfully apply this tunnel before publishing its ingress")
    template = await db.get(ProxyHost, body.template_host_id)
    if not template or template.inbound_tag not in tunnel.inbound_tags:
        raise HTTPException(422, "Select a template host for one of this tunnel's user inbounds")
    if template.native_tunnel_id:
        raise HTTPException(422, "Choose an ordinary host as the template, not another tunnel host")
    inbound = await source_inbound(db, tunnel, template.inbound_tag)
    if not inbound:
        raise HTTPException(422, "The source user inbound is unavailable")
    node = await db.get(Node, tunnel.source_node_id)
    raw = next(
        (
            i
            for i in (await db.get(CoreConfig, node.core_config_id or 1)).config.get("inbounds", [])
            if i.get("tag") == template.inbound_tag
        ),
        {},
    )
    try:
        listen = ipaddress.ip_address(raw.get("listen") or "0.0.0.0")
    except ValueError:
        raise HTTPException(422, "Source inbound must listen on a client-reachable IP") from None
    if listen.is_loopback:
        raise HTTPException(422, "Source user inbound is loopback-only; configure its public listener first")
    address = body.address or node.address
    try:
        ip = ipaddress.ip_address(address)
        if ip.is_unspecified or ip.is_loopback or ip.is_link_local or ip.is_multicast:
            raise ValueError()
    except ValueError:
        raise HTTPException(422, "Set the client-reachable ingress IP; the management address cannot be used") from None
    port = body.port or inbound.get("port")
    if not str(port).isdigit() or not 1 <= int(port) <= 65535:
        raise HTTPException(422, "Select one public user port for this inbound")
    if int(port) in (tunnel.port, node.port, node.api_port, node.tunnel_api_port):
        raise HTTPException(422, "Subscription port cannot be a carrier or management API port")
    if template.use_sni_as_host:
        raise HTTPException(422, "Disable use_sni_as_host on the template so the ingress IP is used")
    if template.transport_settings and (
        (template.transport_settings.get("xhttp_settings") or {}).get("download_settings")
    ):
        raise HTTPException(422, "Use a template without a separate xHTTP download host")
    value = BaseHost.model_validate(template).model_dump(exclude={"id"})
    value.update(
        native_tunnel_id=tunnel.id,
        address={str(ip)},
        port=int(port),
        is_disabled=False,
        remark=body.remark or f"{node.name} → {tunnel.name}",
    )
    from pydantic import ValidationError

    try:
        validated = CreateHost.model_validate(value)
    except ValidationError as error:
        raise HTTPException(422, [{"loc": e["loc"], "msg": e["msg"]} for e in error.errors()]) from None
    existing = await db.scalar(
        select(ProxyHost)
        .where(ProxyHost.native_tunnel_id == tunnel.id, ProxyHost.inbound_tag == template.inbound_tag)
        .order_by(ProxyHost.id)
        .limit(1)
    )
    data = validated.model_dump(exclude={"inbound_tag", "id"})
    if existing is None:
        data["priority"] = (await db.scalar(select(func.max(ProxyHost.priority))) or 0) + 1
        existing = ProxyHost(**data)
        existing.inbound = await get_or_create_inbound(db, template.inbound_tag)
        db.add(existing)
    else:
        for key, value in data.items():
            if key != "priority":
                setattr(existing, key, value)
    await db.flush()
    audit(db, admin.username, "host.publish", tunnel.id, host_id=existing.id, inbound_tag=template.inbound_tag)
    await db.commit()
    await host_manager.add_host(db, existing)
    return BaseHost.model_validate(existing)
