"""Discovery results checked against what this panel manages, and durable port reservations."""

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.db.models import NativeExternalTunnel, NativeTunnel, Node, ServiceAudit, SmartDNSRoute
from app.discovery.analyze import PANEL_DEFAULT_PORTS, analyze
from app.tunnel.engines import owned_ports


async def managed_ports(db, address):
    """Ports this panel already uses on the node(s) at ``address``: port -> description."""
    nodes = list((await db.scalars(select(Node).where(Node.address == address))).all())
    managed = {}
    for node in nodes:
        for port in (node.port, node.api_port, node.tunnel_api_port, node.health_echo_port):
            if port:
                managed.setdefault(port, f"panel node '{node.name}' management or health port")
        for tunnel in (
            await db.scalars(
                select(NativeTunnel).where(
                    (NativeTunnel.source_node_id == node.id) | (NativeTunnel.exit_node_id == node.id)
                )
            )
        ).all():
            for port in owned_ports(tunnel).get(node.id, set()):
                managed.setdefault(port, f"panel tunnel '{tunnel.name}' ({tunnel.transport})")
        for route in (await db.scalars(select(SmartDNSRoute).where(SmartDNSRoute.node_id == node.id))).all():
            for port in set(route.tcp_ports + route.udp_ports):
                managed.setdefault(port, f"gaming DNS gateway route '{route.domain}'")
    return nodes, managed


async def conflicts(db, address, analysis):
    """What would break if this panel configured something on the server as it stands."""
    nodes, managed = await managed_ports(db, address)
    result = []
    reserved = {item["port"]: item for item in analysis["reservations"]}
    for port, owner in managed.items():
        if port in reserved:
            result.append(
                {
                    "code": "port_in_use_by_existing_service",
                    "port": port,
                    "panel": owner,
                    "existing": reserved[port]["purpose"],
                    "action": "Change the panel-side port; the existing service is left untouched.",
                }
            )
    listening = {(row["port"]): row for row in analysis.get("listening", [])}
    if not nodes:
        for port, label in PANEL_DEFAULT_PORTS.items():
            row = listening.get(port)
            if row and not analysis["panel_node_present"]:
                result.append(
                    {
                        "code": "node_install_port_in_use",
                        "port": port,
                        "panel": f"node installer default ({label})",
                        "existing": row.get("name") or "unknown process",
                        "action": "Choose another port in the node install form.",
                    }
                )
    return {"conflicts": result, "node_registered": bool(nodes), "node_ids": [node.id for node in nodes]}


async def inspect(db, address, facts):
    analysis = analyze(facts, address=address)
    analysis.update(await conflicts(db, address, analysis))
    analysis["safe_to_proceed"] = not analysis["conflicts"]
    return analysis


async def list_reservations(db):
    rows = (
        await db.scalars(select(NativeExternalTunnel).order_by(NativeExternalTunnel.address, NativeExternalTunnel.name))
    ).all()
    return [
        {
            "id": row.id,
            "address": row.address,
            "name": row.name,
            "kind": row.kind,
            "role": row.role,
            "peer": row.peer,
            "ports": row.ports,
            "created_by": row.created_by,
            "created_at": row.created_at,
        }
        for row in rows
    ]


async def save_reservations(db, body, actor):
    _, managed = await managed_ports(db, body.address)
    clash = sorted({p.port for item in body.items for p in item.ports} & managed.keys())
    if clash:
        raise HTTPException(
            409,
            "These ports are already used by this panel on that server ("
            + ", ".join(f"{port}: {managed[port]}" for port in clash)
            + "); change the panel side first, then reserve.",
        )
    saved = []
    for item in body.items:
        row = await db.scalar(
            select(NativeExternalTunnel).where(
                NativeExternalTunnel.address == body.address, NativeExternalTunnel.name == item.name
            )
        )
        ports = [port.model_dump() for port in item.ports]
        if row:
            row.kind, row.role, row.peer, row.ports = item.kind, item.role, item.peer, ports
        else:
            row = NativeExternalTunnel(
                address=body.address,
                name=item.name,
                kind=item.kind,
                ports=ports,
                role=item.role,
                peer=item.peer,
                created_by=actor,
            )
            db.add(row)
        saved.append(item.name)
    db.add(
        ServiceAudit(
            actor=actor,
            action="discovery.reserve",
            target_id=body.address[:64],
            details={"address": body.address, "items": saved},
        )
    )
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Reservation changed concurrently; retry") from None
    return await list_reservations(db)


async def delete_reservation(db, identity, actor):
    row = await db.get(NativeExternalTunnel, identity)
    if not row:
        raise HTTPException(404, "Reservation not found")
    db.add(
        ServiceAudit(
            actor=actor,
            action="discovery.release",
            target_id=str(row.id),
            details={"address": row.address, "name": row.name},
        )
    )
    await db.delete(row)
    await db.commit()
