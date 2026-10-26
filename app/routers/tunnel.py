"""Native tunnel infrastructure is owner-only, like DNS infrastructure policies."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import select

from app.db import AsyncSession, get_db
from app.db.models import ServiceAudit
from app.models.admin import AdminDetails
from app.models.host import BaseHost
from app.models.tunnel import (
    TunnelAuditView,
    TunnelCreate,
    TunnelDeployResult,
    TunnelHostPublish,
    TunnelModify,
    TunnelPreflight,
    TunnelRevision,
    TunnelState,
    TunnelView,
)
from app.nats.node_rpc import node_nats_client
from app.operation import tunnel as operation
from app.routers.authentication import require_owner
from config import runtime_settings

router = APIRouter(tags=["Native tunnels"], prefix="/api/tunnels")
DB = Annotated[AsyncSession, Depends(get_db)]
Owner = Annotated[AdminDetails, Depends(require_owner)]


@router.get("", response_model=TunnelState)
async def native_tunnel_state(db: DB, admin: Owner):
    return await operation.state_view(db)


@router.post("", response_model=TunnelView, status_code=201)
async def create_native_tunnel(body: TunnelCreate, db: DB, admin: Owner):
    return await operation.create(db, body, admin.username)


@router.put("/{tunnel_id}", response_model=TunnelView)
async def update_native_tunnel(tunnel_id: int, body: TunnelModify, db: DB, admin: Owner):
    return await operation.modify(db, tunnel_id, body, admin.username)


@router.post("/{tunnel_id}/rotate", response_model=TunnelView)
async def rotate_native_tunnel(tunnel_id: int, body: TunnelRevision, db: DB, admin: Owner):
    return await operation.rotate(db, tunnel_id, body.expected_revision, admin.username)


@router.delete("/{tunnel_id}", status_code=204)
async def delete_native_tunnel(tunnel_id: int, body: TunnelRevision, db: DB, admin: Owner):
    await operation.remove(db, tunnel_id, body.expected_revision, admin.username)
    return Response(status_code=204)


@router.post("/apply", response_model=TunnelDeployResult)
async def apply_native_tunnels(admin: Owner):
    if runtime_settings.role.runs_node:
        return await operation.deploy(admin.username)
    try:
        return await node_nats_client.request("apply_native_tunnels", {"actor": admin.username}, timeout=630)
    except RuntimeError:
        raise HTTPException(
            503, "Node worker did not acknowledge deployment; reload its state before retrying"
        ) from None


@router.get("/audit", response_model=list[TunnelAuditView])
async def native_tunnel_audit(db: DB, admin: Owner, offset: int = Query(0, ge=0), limit: int = Query(30, ge=1, le=100)):
    return (
        await db.scalars(
            select(ServiceAudit)
            .where(ServiceAudit.action.startswith("tunnel."))
            .order_by(ServiceAudit.id.desc())
            .offset(offset)
            .limit(limit)
        )
    ).all()


@router.get("/paqet/schema")
async def native_paqet_schema(admin: Owner):
    from app.paqet.models import BLOCKS, VERSION, PaqetOptions

    return {
        "engine": VERSION,
        "contract": 1,
        "blocks": BLOCKS,
        "defaults": PaqetOptions().model_dump(mode="json"),
        "schema": PaqetOptions.model_json_schema(),
        "source_commit": "4b81ce0ab56b7cc9aec7501c87c4804676cdb1b4",
    }


@router.get("/nodes/{node_id}/paqet")
async def native_node_paqet_capability(node_id: int, db: DB, admin: Owner):
    from app.db.models import Node
    from app.paqet.client import PaqetNodeError, request

    node = await db.get(Node, node_id)
    if not node:
        raise HTTPException(404, "Node not found")
    try:
        return await request(node, "GET", "capabilities")
    except PaqetNodeError, ValueError:
        raise HTTPException(
            503, "Native Paqet capability unavailable; install the fork node and verify TLS/port"
        ) from None


@router.get("/nodes/{node_id}/paqet/network")
async def native_node_paqet_network(node_id: int, peer: str, db: DB, admin: Owner):
    import ipaddress

    from app.db.models import Node
    from app.paqet.client import PaqetNodeError, request

    try:
        peer = str(ipaddress.ip_address(peer))
    except ValueError:
        raise HTTPException(422, "Numeric peer IP required") from None
    node = await db.get(Node, node_id)
    if not node:
        raise HTTPException(404, "Node not found")
    try:
        return await request(node, "GET", f"network?peer={peer}")
    except PaqetNodeError, ValueError:
        raise HTTPException(503, "Node network discovery unavailable") from None


@router.get("/{tunnel_id}/paqet/status")
async def native_paqet_status(tunnel_id: int, db: DB, admin: Owner):
    import asyncio

    from app.db.models import NativeTunnel, Node
    from app.paqet.client import request

    row = await db.get(NativeTunnel, tunnel_id)
    if not row or row.transport != "paqet":
        raise HTTPException(404, "Paqet tunnel not found")

    async def side(node_id):
        node = await db.get(Node, node_id)
        return node

    # Session access is sequential; only independent network calls run together.
    source = await side(row.source_node_id)
    exit_node = await side(row.exit_node_id)

    async def probe(node):
        try:
            return {"available": True, "state": await request(node, "GET", f"links/{row.engine_id}")}
        except RuntimeError, ValueError:
            return {"available": False, "state": None}

    a, b = await asyncio.gather(probe(source), probe(exit_node))
    return {"source": a, "exit": b, "traffic_readiness": "not_probed"}


@router.post("/{tunnel_id}/paqet/action")
async def native_paqet_action(tunnel_id: int, action: str, side: str, body: TunnelRevision, db: DB, admin: Owner):
    from app.db.models import Node
    from app.paqet.client import PaqetNodeError, request

    if action not in ("logs", "version", "ping", "dump", "restart") or side not in ("source", "exit"):
        raise HTTPException(422, "Invalid Paqet action/side")
    row = await operation.get_locked(db, tunnel_id, body.expected_revision)
    if row.transport != "paqet":
        raise HTTPException(404, "Paqet tunnel not found")
    node = await db.get(Node, row.source_node_id if side == "source" else row.exit_node_id)
    path = f"links/{row.engine_id}/{action}?revision={row.revision}"
    await db.commit()
    try:
        result = await request(node, "POST", path)
    except PaqetNodeError, ValueError:
        raise HTTPException(503, "Node did not acknowledge the Paqet action") from None
    operation.audit(db, admin.username, f"paqet.{action}", row.id, side=side, revision=row.revision)
    await db.commit()
    return result


@router.post("/{tunnel_id}/hosts", response_model=BaseHost)
async def publish_native_tunnel_host(tunnel_id: int, body: TunnelHostPublish, db: DB, admin: Owner):
    from app.tunnel.hosts import publish_host

    return await publish_host(db, tunnel_id, body, admin)


@router.post("/{tunnel_id}/preflight", response_model=TunnelPreflight)
async def native_tunnel_preflight(tunnel_id: int, body: TunnelRevision, db: DB, admin: Owner):
    from app.tunnel.preflight import preflight

    return await preflight(db, tunnel_id, body.expected_revision)
