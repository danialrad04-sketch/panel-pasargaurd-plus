from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Response
from sqlalchemy import select

from app.db import AsyncSession, get_db
from app.db.base import begin_immediate_if_sqlite
from app.db.models import DNSAccount, SmartDNSNodeState, SmartDNSRoute
from app.models.admin import AdminDetails
from app.models.smartdns import GrantInput, RelayAccountInput, RouteInput, RouteModify, SettleInput
from app.models.tunnel import TunnelRevision
from app.routers.authentication import require_owner
from app.smartdns import control, service

router = APIRouter(prefix="/api/smartdns", tags=["Native SmartDNS"])
DB = Annotated[AsyncSession, Depends(get_db)]
Owner = Annotated[AdminDetails, Depends(require_owner)]


@router.get("")
async def state(db: DB, admin: Owner):
    routes = (await db.scalars(select(SmartDNSRoute).order_by(SmartDNSRoute.id))).all()
    nodes = (await db.scalars(select(SmartDNSNodeState))).all()
    return {
        "routes": [control.view(route) for route in routes],
        "nodes": [
            {
                "node_id": node.node_id,
                "revision": node.revision,
                "applied_revision": node.applied_revision,
                "result": node.result,
            }
            for node in nodes
        ],
        "revocation_bound_ms": 2000,
    }


@router.post("/routes", status_code=201)
async def create(body: RouteInput, db: DB, admin: Owner):
    return await control.save(db, body, admin.username)


@router.put("/routes/{route_id}")
async def modify(route_id: int, body: RouteModify, db: DB, admin: Owner):
    return await control.save(db, body, admin.username, route_id)


@router.delete("/routes/{route_id}", status_code=204)
async def remove(route_id: int, body: TunnelRevision, db: DB, admin: Owner):
    await control.remove(db, route_id, body.expected_revision, admin.username)
    return Response(status_code=204)


@router.post("/nodes/{node_id}/apply")
async def deploy(node_id: int, body: TunnelRevision, admin: Owner):
    return await control.deploy(node_id, body.expected_revision, admin.username)


@router.put("/accounts/{account_id}")
async def account(account_id: int, body: RelayAccountInput, db: DB, admin: Owner):
    await begin_immediate_if_sqlite(db)
    row = await db.get(DNSAccount, account_id, with_for_update=True)
    if not row:
        raise HTTPException(404, "DNS account not found")
    row.relay_enabled, row.relay_byte_limit, row.relay_rps = body.enabled, body.byte_limit, body.grants_per_second
    control.audit(db, admin.username, "account", account_id, enabled=body.enabled, byte_limit=body.byte_limit)
    await db.commit()
    return {"enabled": row.relay_enabled, "byte_limit": row.relay_byte_limit, "grants_per_second": row.relay_rps}


@router.post("/nodes/{node_id}/reserve")
async def reserve(
    node_id: int, body: GrantInput, db: DB, node_key: Annotated[str | None, Header(alias="x-pg-node-key")] = None
):
    return await service.reserve(db, node_id, node_key, body)


@router.post("/nodes/{node_id}/settle")
async def settle(
    node_id: int, body: SettleInput, db: DB, node_key: Annotated[str | None, Header(alias="x-pg-node-key")] = None
):
    return await service.settle(db, node_id, node_key, body)
