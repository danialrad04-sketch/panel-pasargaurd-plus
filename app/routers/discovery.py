from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from app.db import AsyncSession, get_db
from app.discovery import scan, service
from app.models.admin import AdminDetails
from app.models.discovery import AnalyzeInput, ReservationInput, ScanInput
from app.routers.authentication import require_owner

router = APIRouter(prefix="/api/tunnel/discovery", tags=["Tunnel discovery"])
DB = Annotated[AsyncSession, Depends(get_db)]
Owner = Annotated[AdminDetails, Depends(require_owner)]

SCAN_ERRORS = {
    "ssh_host_key_changed": 409,
    "ssh_authentication_failed": 401,
    "python3_required": 422,
    "root_or_passwordless_sudo_required": 422,
    "invalid_ssh_input": 422,
}


@router.post("/scan")
async def discovery_scan(body: ScanInput, db: DB, admin: Owner):
    """Read-only inspection over SSH. The collector script goes in on stdin; nothing is written, installed or restarted."""
    try:
        facts = await scan.collect(body)
    except scan.ScanError as error:
        raise HTTPException(SCAN_ERRORS.get(error.code, 502), error.code) from None
    from app.models.discovery import Facts

    return await service.inspect(db, body.host, Facts.model_validate(facts).model_dump())


@router.post("/analyze")
async def discovery_analyze(body: AnalyzeInput, db: DB, admin: Owner):
    """Analyse output of `python3 collector.py` that the owner ran on a server by hand."""
    return await service.inspect(db, body.address, body.facts.model_dump())


@router.get("/reservations")
async def discovery_reservations(db: DB, admin: Owner):
    return {"items": await service.list_reservations(db)}


@router.post("/reservations")
async def discovery_reserve(body: ReservationInput, db: DB, admin: Owner):
    return {"items": await service.save_reservations(db, body, admin.username)}


@router.delete("/reservations/{identity}", status_code=204)
async def discovery_release(identity: int, db: DB, admin: Owner):
    await service.delete_reservation(db, identity, admin.username)
