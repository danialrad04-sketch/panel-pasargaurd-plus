"""Read-only operational alerts; never returns private paths, keys or config."""

import asyncio
import shutil
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Annotated

from cryptography import x509
from fastapi import APIRouter, Depends
from sqlalchemy import select

from app.db import AsyncSession, get_db
from app.db.models import (
    NativeMaintenanceJob,
    NativeTunnel,
    Node,
    SmartDNSNodeState,
    TunnelFailoverPolicy,
    TunnelHealthState,
)
from app.models.admin import AdminDetails
from app.routers.authentication import require_owner
from app.utils.helpers import fix_datetime_timezone
from config import native_maintenance_settings, server_settings

router = APIRouter(prefix="/api/operations", tags=["Native operations"])


@router.get("/readiness")
async def readiness(
    db: Annotated[AsyncSession, Depends(get_db)], admin: Annotated[AdminDetails, Depends(require_owner)]
):
    checks = []
    now = datetime.now(UTC)

    def add(code, status, detail, **context):
        checks.append({"code": code, "status": status, "detail": detail, **context})

    def disk():
        location = Path(native_maintenance_settings.backup_directory)
        while not location.exists() and location != location.parent:
            location = location.parent
        return shutil.disk_usage(location)

    usage = await asyncio.to_thread(disk)
    free = round(100 * usage.free / usage.total, 1)
    add(
        "disk",
        "critical" if free < 5 else "warning" if free < 15 else "ok",
        "Free storage for runtime and consistent backup",
        free_percent=free,
        free_bytes=usage.free,
    )

    def certificate(pem, code, **context):
        try:
            cert = x509.load_pem_x509_certificate(pem.encode())
            remaining = (cert.not_valid_after_utc - now).total_seconds() / 86400
            add(
                code,
                "critical" if remaining < 0 else "warning" if remaining < 14 else "ok",
                "Management certificate expiry",
                days_remaining=round(remaining, 1),
                expires_at=cert.not_valid_after_utc.isoformat(),
                **context,
            )
        except ValueError, TypeError:
            add(code, "warning", "Management certificate could not be parsed", **context)

    for node in (await db.scalars(select(Node))).all():
        certificate(node.server_ca, "node_certificate", node_id=node.id, name=node.name)
    if server_settings.ssl_certfile:
        try:
            certificate(Path(server_settings.ssl_certfile).read_text(), "panel_certificate")
        except OSError:
            add("panel_certificate", "warning", "Panel certificate file unavailable")
    else:
        add("panel_certificate", "info", "TLS may terminate at the reverse proxy; check its certificate separately")
    backup = await db.get(NativeMaintenanceJob, "backup")
    if native_maintenance_settings.backup_enabled:
        stale = (
            not backup
            or not backup.last_run
            or now - fix_datetime_timezone(backup.last_run)
            > timedelta(hours=native_maintenance_settings.backup_interval_hours * 2)
        )
        good = bool(backup and backup.last_result.get("ok"))
        add(
            "backup",
            "warning" if stale or not good else "ok",
            "Scheduled encrypted backup and recovery verification",
            last_run=backup.last_run if backup else None,
            result=backup.last_result if backup else {},
        )
    else:
        add(
            "backup",
            "info",
            "Scheduled native backup is disabled; configure its separate private key or use your external backup system",
        )
    for row in (
        await db.scalars(
            select(NativeTunnel).where(NativeTunnel.enabled.is_(True), NativeTunnel.health_enabled.is_(True))
        )
    ).all():
        sample = await db.get(TunnelHealthState, row.id)
        stale = (
            not sample
            or sample.revision != row.revision
            or not sample.checked_at
            or now - fix_datetime_timezone(sample.checked_at) > timedelta(minutes=3)
        )
        add(
            "carrier",
            "warning" if stale else "ok" if sample.result.get("healthy") else "critical",
            "Encrypted carrier traffic measurement",
            tunnel_id=row.id,
            name=row.name,
            result=sample.result if sample and not stale else {"status": "stale_or_missing"},
        )
    for policy in (
        await db.scalars(select(TunnelFailoverPolicy).where(TunnelFailoverPolicy.suspended.is_(True)))
    ).all():
        add(
            "failover",
            "critical",
            "Automatic failover is suspended; inspect rollback and reapply before resuming",
            node_id=policy.source_node_id,
            result=policy.last_result,
        )
    for state in (await db.scalars(select(SmartDNSNodeState))).all():
        if state.revision != state.applied_revision:
            add(
                "smartdns",
                "warning",
                "Gateway configuration is pending acknowledgement; affected SmartDNS responses fail closed",
                node_id=state.node_id,
            )
    return {
        "checked_at": now,
        "checks": checks,
        "critical": sum(check["status"] == "critical" for check in checks),
        "warnings": sum(check["status"] == "warning" for check in checks),
    }


from pydantic import BaseModel, Field


class RuntimeRecoveryRequest(BaseModel):
    fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    disable_orphans: bool = False


@router.get("/runtime-recovery")
async def recovery_inventory(admin: Annotated[AdminDetails, Depends(require_owner)]):
    from app.maintenance.recovery import inventory

    return await inventory()


@router.post("/runtime-recovery")
async def runtime_recovery(body: RuntimeRecoveryRequest, admin: Annotated[AdminDetails, Depends(require_owner)]):
    from app.maintenance.recovery import reconcile

    return await reconcile(body.fingerprint, body.disable_orphans, admin.username)
