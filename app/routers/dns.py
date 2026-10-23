"""Native DNS management and authenticated RFC 8484 endpoints."""

import base64
import binascii
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError

from app.db import AsyncSession, get_db
from app.db.base import begin_immediate_if_sqlite
from app.db.models import DNSAccount, DNSIPLease, DNSPolicy, DNSUsagePeriod, ServiceAudit
from app.dns.catalog import CatalogPolicyInput, catalog, compile_policy
from app.dns.policy import DNSPolicyInput
from app.dns.privacy import install_access_filter
from app.dns.service import DNSService
from app.models.admin import AdminDetails
from app.models.dns import (
    DNSAccountCreate,
    DNSAccountInput,
    DNSAccountList,
    DNSAccountView,
    DNSAuditView,
    DNSCredential,
    DNSLeaseInput,
    DNSLeaseView,
    DNSPeriodView,
    DNSPolicyModify,
    DNSPolicyView,
    DNSRuntime,
)
from app.operation import dns as operation
from config import dns_settings

from .authentication import require_owner, require_permission

router = APIRouter(tags=["DNS"])
service = DNSService()
install_access_filter()
DB = Annotated[AsyncSession, Depends(get_db)]
Read = Annotated[AdminDetails, Depends(require_permission("dns", "read"))]
Owner = Annotated[AdminDetails, Depends(require_owner)]


def policy_view(value):
    return {"id": value.id, "name": value.name, "rules": value.rules, "revision": value.revision}


@router.get("/api/dns/runtime", response_model=DNSRuntime)
async def runtime(admin: Read):
    return {
        "native": True,
        "enabled": dns_settings.enabled,
        "doh": dns_settings.public_url or "/dns-query",
        "source_ip_transports": ["udp", "tcp", "dot"],
        "upstream": dns_settings.upstream,
        "upstream_port": dns_settings.upstream_port,
        "worker_port": dns_settings.port,
        "dot_port": dns_settings.dot_port,
        "readiness": "not_probed",
    }


@router.get("/api/dns/catalog")
async def gaming_catalog(admin: Read):
    return catalog()


@router.get("/api/dns/policies", response_model=list[DNSPolicyView])
async def policies(db: DB, admin: Read):
    return [policy_view(row) for row in (await db.scalars(select(DNSPolicy).order_by(DNSPolicy.id))).all()]


async def add_policy(db, body, admin):
    policy = DNSPolicy(name=body.name, rules=[row.model_dump() for row in body.rules])
    db.add(policy)
    try:
        await db.flush()
        operation.audit(db, admin, "policy.create", policy.id, revision=1)
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "DNS policy name already exists") from None
    return policy_view(policy)


@router.post("/api/dns/policies", status_code=201, response_model=DNSPolicyView)
async def create_policy(body: DNSPolicyInput, db: DB, admin: Owner):
    return await add_policy(db, body, admin)


@router.post("/api/dns/policies/from-catalog", status_code=201, response_model=DNSPolicyView)
async def catalog_policy(body: CatalogPolicyInput, db: DB, admin: Owner):
    try:
        policy = compile_policy(body)
    except ValueError as error:
        raise HTTPException(422, str(error)) from None
    return await add_policy(db, policy, admin)


@router.put("/api/dns/policies/{policy_id}", response_model=DNSPolicyView)
async def modify_policy(policy_id: int, body: DNSPolicyModify, db: DB, admin: Owner):
    await begin_immediate_if_sqlite(db)
    policy = (await db.scalars(select(DNSPolicy).where(DNSPolicy.id == policy_id).with_for_update())).one_or_none()
    if policy is None:
        raise HTTPException(404, "DNS policy not found")
    if policy.revision != body.expected_revision:
        raise HTTPException(409, "Policy changed; reload before editing")
    policy.name, policy.rules = body.name, [row.model_dump() for row in body.rules]
    policy.revision += 1
    operation.audit(db, admin, "policy.update", policy.id, revision=policy.revision)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "DNS policy name already exists") from None
    return policy_view(policy)


@router.delete("/api/dns/policies/{policy_id}", status_code=204)
async def remove_policy(policy_id: int, db: DB, admin: Owner):
    await begin_immediate_if_sqlite(db)
    policy = (await db.scalars(select(DNSPolicy).where(DNSPolicy.id == policy_id).with_for_update())).one_or_none()
    if policy is None:
        raise HTTPException(404, "DNS policy not found")
    if await db.scalar(select(func.count()).select_from(DNSAccount).where(DNSAccount.policy_id == policy_id)):
        raise HTTPException(409, "Policy is assigned to DNS accounts")
    await db.delete(policy)
    operation.audit(db, admin, "policy.delete", policy_id)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Policy is assigned to DNS accounts") from None
    return Response(status_code=204)


@router.get("/api/dns/accounts", response_model=DNSAccountList)
async def accounts(
    db: DB,
    admin: Read,
    offset: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    search: str = Query("", max_length=64),
):
    return await operation.list_accounts(db, admin, offset, limit, search)


@router.post("/api/dns/accounts", response_model=DNSAccountView, status_code=201)
async def create_account(
    body: DNSAccountCreate, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "create"))]
):
    return await operation.create_account(db, body, admin)


@router.put("/api/dns/accounts/{account_id}", response_model=DNSAccountView)
async def modify_account(
    account_id: int,
    body: DNSAccountInput,
    db: DB,
    admin: Annotated[AdminDetails, Depends(require_permission("dns", "update"))],
):
    return await operation.modify_account(db, account_id, body, admin)


@router.delete("/api/dns/accounts/{account_id}", status_code=204)
async def remove_account(
    account_id: int, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "delete"))]
):
    account, _ = await operation.account_by_id(db, account_id, admin, "delete", lock=True)
    await db.execute(delete(DNSIPLease).where(DNSIPLease.account_id == account_id))
    await db.execute(delete(DNSUsagePeriod).where(DNSUsagePeriod.account_id == account_id))
    await db.delete(account)
    operation.audit(db, admin, "delete", account_id)
    await db.commit()
    return Response(status_code=204)


@router.post("/api/dns/accounts/{account_id}/reset", response_model=DNSAccountView)
async def reset(
    account_id: int, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "reset_usage"))]
):
    return await operation.reset_account(db, account_id, admin)


@router.post("/api/dns/accounts/{account_id}/rotate", response_model=DNSCredential)
async def rotate(
    account_id: int, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "rotate_token"))]
):
    result = await operation.rotate_account(db, account_id, admin)
    return Response(
        content=__import__("json").dumps(result), media_type="application/json", headers={"Cache-Control": "no-store"}
    )


@router.post("/api/dns/accounts/{account_id}/credentials", response_model=DNSCredential)
async def credentials(
    account_id: int, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "rotate_token"))]
):
    result = await operation.account_credentials(db, account_id, admin)
    return Response(
        content=__import__("json").dumps(result), media_type="application/json", headers={"Cache-Control": "no-store"}
    )


@router.get("/api/dns/accounts/{account_id}/periods", response_model=list[DNSPeriodView])
async def periods(
    account_id: int, db: DB, admin: Read, offset: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)
):
    await operation.account_by_id(db, account_id, admin, "read")
    rows = (
        await db.scalars(
            select(DNSUsagePeriod)
            .where(DNSUsagePeriod.account_id == account_id)
            .order_by(DNSUsagePeriod.period.desc())
            .offset(offset)
            .limit(limit)
        )
    ).all()
    return [
        {
            "period": r.period,
            "queries": r.queries,
            "request_bytes": r.request_bytes,
            "response_bytes": r.response_bytes,
            "errors": r.errors,
            "blocked": r.blocked,
            "created_at": r.created_at,
            "ended_at": r.ended_at,
        }
        for r in rows
    ]


@router.get("/api/dns/accounts/{account_id}/leases", response_model=list[DNSLeaseView])
async def leases(account_id: int, db: DB, admin: Read):
    await operation.account_by_id(db, account_id, admin, "read")
    rows = (
        await db.scalars(
            select(DNSIPLease).where(DNSIPLease.account_id == account_id, DNSIPLease.expires_at > datetime.now(UTC))
        )
    ).all()
    return [{"ip": r.ip, "expires_at": r.expires_at} for r in rows]


@router.post("/api/dns/accounts/{account_id}/leases", status_code=201, response_model=DNSLeaseView)
async def lease(
    account_id: int,
    body: DNSLeaseInput,
    db: DB,
    admin: Annotated[AdminDetails, Depends(require_permission("dns", "update"))],
):
    return await operation.create_lease(db, account_id, body, admin)


@router.delete("/api/dns/accounts/{account_id}/leases/{ip}", status_code=204)
async def revoke_lease(
    account_id: int, ip: str, db: DB, admin: Annotated[AdminDetails, Depends(require_permission("dns", "update"))]
):
    account, _ = await operation.account_by_id(db, account_id, admin, "update", lock=True)
    try:
        normalized = DNSLeaseInput(ip=ip).ip
    except ValidationError:
        raise HTTPException(422, "Invalid client IP") from None
    await db.execute(delete(DNSIPLease).where(DNSIPLease.account_id == account.id, DNSIPLease.ip == normalized))
    operation.audit(db, admin, "lease.revoke", account.id)
    await db.commit()
    return Response(status_code=204)


@router.get("/api/dns/audit", response_model=list[DNSAuditView])
async def audit(db: DB, admin: Owner, offset: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=100)):
    rows = (
        await db.scalars(
            select(ServiceAudit)
            .where(ServiceAudit.action.startswith("dns."))
            .order_by(ServiceAudit.id.desc())
            .offset(offset)
            .limit(limit)
        )
    ).all()
    return [
        {
            "id": r.id,
            "actor": r.actor,
            "action": r.action,
            "target_id": r.target_id,
            "details": r.details,
            "created_at": r.created_at,
        }
        for r in rows
    ]


async def doh_answer(request, token=None):
    if token is None:
        scheme, _, token = request.headers.get("authorization", "").partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise HTTPException(401, "DNS bearer credential required")
    if request.method == "GET":
        encoded = request.query_params.get("dns", "")
        if not encoded or len(encoded) > 5500:
            raise HTTPException(400, "Invalid DNS message")
        try:
            wire = base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True)
        except binascii.Error, ValueError:
            raise HTTPException(400, "Invalid DNS message") from None
    else:
        if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/dns-message":
            raise HTTPException(415, "Use application/dns-message")
        buffer = bytearray()
        async for part in request.stream():
            if len(buffer) + len(part) > 4096:
                raise HTTPException(413, "DNS message too large")
            buffer.extend(part)
        wire = bytes(buffer)
    result = await service.answer(wire, token=token)
    return Response(
        result,
        media_type="application/dns-message",
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
    )


@router.get("/dns-query", response_class=Response)
async def dns_query_get(request: Request):
    return await doh_answer(request)


@router.post("/dns-query", response_class=Response)
async def dns_query_post(request: Request):
    return await doh_answer(request)


@router.api_route("/dns-query/{token}", methods=["GET", "POST"], include_in_schema=False)
async def credential_doh(token: str, request: Request):
    return await doh_answer(request, token)
