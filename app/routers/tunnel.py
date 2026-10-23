"""Native tunnel infrastructure is owner-only, like DNS infrastructure policies."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import select

from app.db import AsyncSession, get_db
from app.db.models import ServiceAudit
from app.models.admin import AdminDetails
from app.models.tunnel import (
    TunnelAuditView,
    TunnelCreate,
    TunnelDeployResult,
    TunnelModify,
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
