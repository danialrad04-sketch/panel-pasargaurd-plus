"""Native DNS control plane. Uses PasarGuard users, RBAC and database transactions."""

import base64
import hashlib
import hmac
import secrets
from datetime import UTC, datetime, timedelta

from fastapi import HTTPException
from sqlalchemy import delete, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import selectinload

from app.db.base import begin_immediate_if_sqlite
from app.db.models import Admin, DNSAccount, DNSIPLease, DNSPolicy, DNSUsagePeriod, ServiceAudit, User, UserStatus
from app.operation.permissions import PermissionDenied, enforce_scope, get_scope_admin_id
from app.utils.helpers import fix_datetime_timezone
from app.utils.jwt import get_secret_key

USER_LOAD = selectinload(User.admin).selectinload(Admin.role)


def eligible(user):
    if user.status != UserStatus.active or user.is_expired or user.is_limited:
        return False
    if user.admin is not None:
        if user.admin.role is not None:
            return not user.admin.users_sync_blocked
        return not user.admin.is_disabled
    return True


def credential(account, key):
    """Reconstruct a per-account DNS credential without storing its raw value."""
    payload = f"pasarguard-dns:{account.id}:{account.credential_seed}"
    mac = base64.urlsafe_b64encode(hmac.digest(key.encode(), payload.encode(), "sha256")).decode().rstrip("=")
    return f"pgdns_{account.id}_{mac}"


def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def audit(db, admin, action, target, **details):
    db.add(ServiceAudit(actor=admin.username, action="dns." + action, target_id=str(target), details=details))


def scope(admin, action, user):
    try:
        enforce_scope(admin, "dns", action, user.admin_id)
    except PermissionDenied:
        raise HTTPException(403, "DNS account is outside your scope") from None


async def account_by_id(db, account_id, admin, action, *, lock=False):
    if lock:
        await begin_immediate_if_sqlite(db)
    stmt = select(DNSAccount).where(DNSAccount.id == account_id)
    if lock:
        stmt = stmt.with_for_update()
    account = (await db.execute(stmt)).scalar_one_or_none()
    if account is None:
        raise HTTPException(404, "DNS account not found")
    user = await db.get(User, account.user_id, options=[USER_LOAD])
    if user is None:
        raise HTTPException(404, "User not found")
    scope(admin, action, user)
    return account, user


async def policy_by_id(db, policy_id, *, lock=False):
    stmt = select(DNSPolicy).where(DNSPolicy.id == policy_id)
    if lock:
        stmt = stmt.with_for_update()
    policy = (await db.scalars(stmt)).one_or_none()
    if policy is None:
        raise HTTPException(404, "DNS policy not found")
    return policy


async def account_view(db, account, user):
    policy = await policy_by_id(db, account.policy_id)
    usage = (
        await db.execute(
            select(DNSUsagePeriod).where(
                DNSUsagePeriod.account_id == account.id, DNSUsagePeriod.period == account.period
            )
        )
    ).scalar_one()
    return view_from_rows(account, user, policy, usage)


def view_from_rows(account, user, policy, usage):
    return {
        "id": account.id,
        "user_id": user.id,
        "username": user.username,
        "policy_id": policy.id,
        "policy_name": policy.name,
        "enabled": account.enabled,
        "eligible": eligible(user),
        "query_limit": account.query_limit,
        "qps": account.qps,
        "period": account.period,
        "queries": usage.queries,
        "request_bytes": usage.request_bytes,
        "response_bytes": usage.response_bytes,
        "errors": usage.errors,
        "blocked": usage.blocked,
        "created_at": fix_datetime_timezone(account.created_at),
    }


async def list_accounts(db, admin, offset=0, limit=50, search=""):
    conditions = []
    owner_id = get_scope_admin_id(admin, "dns", "read")
    if owner_id is not None:
        conditions.append(User.admin_id == owner_id)
    if search:
        conditions.append(User.username.contains(search, autoescape=True))
    stmt = (
        select(DNSAccount, User, DNSPolicy, DNSUsagePeriod)
        .join(User, DNSAccount.user_id == User.id)
        .join(DNSPolicy, DNSAccount.policy_id == DNSPolicy.id)
        .join(
            DNSUsagePeriod, (DNSUsagePeriod.account_id == DNSAccount.id) & (DNSUsagePeriod.period == DNSAccount.period)
        )
        .where(*conditions)
        .options(USER_LOAD)
    )
    # Loader paths must start at the selected User entity (not DNSAccount).
    count = (
        await db.execute(
            select(func.count()).select_from(DNSAccount).join(User, DNSAccount.user_id == User.id).where(*conditions)
        )
    ).scalar_one()
    rows = (await db.execute(stmt.order_by(DNSAccount.id.desc()).offset(offset).limit(limit))).all()
    return {
        "items": [view_from_rows(account, user, policy, usage) for account, user, policy, usage in rows],
        "total": count,
    }


async def create_account(db, body, admin):
    key = await get_secret_key()
    await begin_immediate_if_sqlite(db)
    user = await db.get(User, body.user_id, options=[USER_LOAD])
    if user is None:
        raise HTTPException(404, "User not found")
    scope(admin, "create", user)
    await policy_by_id(db, body.policy_id, lock=True)
    account = DNSAccount(**body.model_dump(), credential_seed=secrets.token_hex(32))
    db.add(account)
    try:
        await db.flush()
        account.token_hash = digest(credential(account, key))
        db.add(DNSUsagePeriod(account_id=account.id, period=1))
        audit(db, admin, "create", account.id, user_id=user.id, policy_id=account.policy_id)
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "User already has a DNS account or the selected policy changed") from None
    return await account_view(db, account, user)


async def modify_account(db, account_id, body, admin):
    account, user = await account_by_id(db, account_id, admin, "update", lock=True)
    await policy_by_id(db, body.policy_id, lock=True)
    for name, value in body.model_dump().items():
        setattr(account, name, value)
    audit(
        db,
        admin,
        "update",
        account.id,
        policy_id=account.policy_id,
        enabled=account.enabled,
        query_limit=account.query_limit,
        qps=account.qps,
    )
    await db.commit()
    return await account_view(db, account, user)


async def reset_account(db, account_id, admin):
    account, user = await account_by_id(db, account_id, admin, "reset_usage", lock=True)
    old = (
        await db.execute(
            select(DNSUsagePeriod).where(
                DNSUsagePeriod.account_id == account.id, DNSUsagePeriod.period == account.period
            )
        )
    ).scalar_one()
    old.ended_at = datetime.now(UTC)
    account.period += 1
    db.add(DNSUsagePeriod(account_id=account.id, period=account.period))
    audit(db, admin, "reset", account.id, period=account.period)
    await db.commit()
    return await account_view(db, account, user)


async def rotate_account(db, account_id, admin):
    key = await get_secret_key()
    account, _user = await account_by_id(db, account_id, admin, "rotate_token", lock=True)
    account.credential_seed = secrets.token_hex(32)
    account.token_hash = digest(credential(account, key))
    # Credentials and source IP access are independent; rotating revokes both.
    await db.execute(delete(DNSIPLease).where(DNSIPLease.account_id == account.id))
    audit(db, admin, "rotate", account.id)
    await db.commit()
    return {"token": credential(account, key)}


async def account_credentials(db, account_id, admin):
    account, _ = await account_by_id(db, account_id, admin, "rotate_token")
    value = credential(account, await get_secret_key())
    if not hmac.compare_digest(digest(value), account.token_hash or ""):
        raise HTTPException(409, "Signing key changed; rotate the DNS credential")
    return {"token": value}


async def create_lease(db, account_id, body, admin):
    account, _ = await account_by_id(db, account_id, admin, "update", lock=True)
    now = datetime.now(UTC)
    await db.execute(
        delete(DNSIPLease).where(
            DNSIPLease.expires_at <= now, or_(DNSIPLease.account_id == account.id, DNSIPLease.ip == body.ip)
        )
    )
    old = await db.get(DNSIPLease, body.ip)
    if old and old.account_id != account.id:
        raise HTTPException(409, "This source IP is leased to another account")
    count = (
        await db.execute(select(func.count()).select_from(DNSIPLease).where(DNSIPLease.account_id == account.id))
    ).scalar_one()
    if old is None and count >= 3:
        raise HTTPException(409, "At most three active source IP leases are allowed")
    expires = now + timedelta(seconds=body.ttl)
    if old:
        old.expires_at = expires
    else:
        db.add(DNSIPLease(ip=body.ip, account_id=account.id, expires_at=expires))
    audit(db, admin, "lease", account.id, expires_at=expires.isoformat())
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Source IP was concurrently leased to another account") from None
    return {"ip": body.ip, "expires_at": expires}


async def portal_metadata(db, user_id):
    from config import dns_settings

    account = (await db.scalars(select(DNSAccount).where(DNSAccount.user_id == user_id))).one_or_none()
    if account is None:
        return None
    user = await db.scalar(
        select(User).where(User.id == user_id).options(USER_LOAD).execution_options(populate_existing=True)
    )
    if user is None:
        return None
    result = await account_view(db, account, user)
    result["service_enabled"] = dns_settings.enabled
    result["token"] = None
    result["doh_url"] = None
    if dns_settings.enabled and account.enabled and eligible(user):
        token = credential(account, await get_secret_key())
        if hmac.compare_digest(digest(token), account.token_hash or ""):
            result["token"] = token
            result["doh_url"] = (dns_settings.public_url or "/dns-query") + "/" + token
    return result
