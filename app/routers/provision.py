from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select

from app.db import AsyncSession, get_db
from app.db.models import NativeProvisionJob
from app.models.admin import AdminDetails
from app.models.provision import InstallInput, Target
from app.models.tunnel import TunnelRevision
from app.provisioning import bundles, service
from app.routers.authentication import require_owner

router = APIRouter(prefix="/api/provision", tags=["Native automatic installation"])
DB = Annotated[AsyncSession, Depends(get_db)]
Owner = Annotated[AdminDetails, Depends(require_owner)]


@router.get("")
async def native_provision_jobs(db: DB, admin: Owner):
    rows = (await db.scalars(select(NativeProvisionJob).order_by(NativeProvisionJob.created_at.desc()).limit(50))).all()
    return {
        "items": [service.view(row) for row in rows],
        "bundles": bundles.available(),
        "supported_os": ["debian", "ubuntu"],
        "requires": "SSH root or passwordless sudo; review the first server host key",
    }


@router.post("/inspect")
async def native_provision_inspect(body: Target, admin: Owner):
    return await service.scan(body)


@router.post("/install", status_code=202)
async def native_provision_install(body: InstallInput, db: DB, admin: Owner):
    return await service.enqueue_install(db, body, admin.username)


@router.post("/activate/{identity}", status_code=202)
async def native_provision_activate(identity: int, body: TunnelRevision, db: DB, admin: Owner):
    return await service.enqueue_activation(db, identity, body.expected_revision, admin.username)


@router.post("/jobs/{identity}/cancel")
async def native_provision_cancel(identity: str, db: DB, admin: Owner):
    return await service.cancel(db, identity, admin.username)


@router.get("/jobs/{identity}")
async def native_provision_state(identity: str, db: DB, admin: Owner):
    row = await db.get(NativeProvisionJob, identity)
    if not row:
        raise HTTPException(404, "Install job not found")
    return service.view(row)
