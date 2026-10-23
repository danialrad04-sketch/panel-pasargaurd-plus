"""Native DNS data plane with transactionally reserved per-user query budgets."""

import asyncio
import hmac
import ipaddress
import re
import time
from datetime import UTC, datetime

import dns.exception
import dns.flags
import dns.message
import dns.rcode
from fastapi import HTTPException
from sqlalchemy import select, update

from app.db.base import SessionLocal, begin_immediate_if_sqlite
from app.db.models import DNSAccount, DNSIPLease, DNSPolicy, DNSUsagePeriod, User
from app.dns.policy import DNSResolver, parse_query, serialize
from app.operation.dns import USER_LOAD, digest, eligible
from config import dns_settings

TOKEN = re.compile(r"^pgdns_[1-9][0-9]{0,18}_[A-Za-z0-9_-]{43}$")


def source_address(value):
    if "%" in value:
        raise ValueError("Scoped addresses cannot identify clients")
    ip = ipaddress.ip_address(value)
    return str(getattr(ip, "ipv4_mapped", None) or ip)


class DNSService:
    def __init__(self, resolver=None, session_factory=SessionLocal, *, concurrency=32):
        self.resolver = resolver or DNSResolver()
        self.sessions = session_factory
        self.slots = asyncio.Semaphore(concurrency)

    async def reserve(self, size, *, token=None, source_ip=None):
        if token:
            if not TOKEN.fullmatch(token):
                raise HTTPException(401, "Invalid DNS credential")
        elif source_ip:
            try:
                ip = source_address(source_ip)
            except ValueError:
                raise HTTPException(401, "Invalid client address") from None
        else:
            raise HTTPException(401, "DNS credential or active source lease required")

        def authorization():
            if token:
                return DNSAccount.token_hash == digest(token)
            return DNSAccount.id.in_(
                select(DNSIPLease.account_id).where(DNSIPLease.ip == ip, DNSIPLease.expires_at > datetime.now(UTC))
            )

        async with self.sessions() as db:
            # Unknown credentials must not acquire SQLite's global writer slot.
            if await db.scalar(select(DNSAccount.id).where(authorization())) is None:
                raise HTTPException(401, "Invalid DNS credential or source lease")
            await begin_immediate_if_sqlite(db)
            # Recheck authorization after locking: rotation/revocation may have
            # completed between the read-only lookup and this reservation.
            stmt = select(DNSAccount).where(authorization()).with_for_update()
            account = (await db.execute(stmt)).scalar_one_or_none()
            if account is None or (token and not hmac.compare_digest(account.token_hash or "", digest(token))):
                raise HTTPException(401, "Invalid DNS credential or source lease")
            user = await db.get(User, account.user_id, options=[USER_LOAD])
            if user is None or not account.enabled or not eligible(user):
                raise HTTPException(403, "DNS service is disabled or the user is ineligible")
            policy = await db.get(DNSPolicy, account.policy_id)
            if policy is None:
                raise HTTPException(403, "DNS policy unavailable")
            usage = (
                await db.execute(
                    select(DNSUsagePeriod).where(
                        DNSUsagePeriod.account_id == account.id, DNSUsagePeriod.period == account.period
                    )
                )
            ).scalar_one()
            if account.query_limit and usage.queries >= account.query_limit:
                raise HTTPException(403, "DNS query budget exhausted")
            second = int(time.time())
            count = account.rate_count if account.rate_second == second else 0
            if count >= account.qps:
                raise HTTPException(429, "DNS query rate exceeded")
            account.rate_second, account.rate_count = second, count + 1
            usage.queries += 1
            usage.request_bytes += size
            # A timeout still consumed the reserved query. Response bytes stay in
            # the period reserved here, including when reset occurs in flight.
            reservation = (
                account.id,
                account.period,
                {"id": policy.id, "revision": policy.revision, "rules": policy.rules},
            )
            await db.commit()
            return reservation

    async def answer(self, wire, *, token=None, source_ip=None, udp=False):
        if not dns_settings.enabled:
            raise HTTPException(503, "Native DNS service is disabled")
        try:
            query = parse_query(wire)
        except ValueError:
            raise HTTPException(400, "Invalid DNS question") from None
        try:
            await asyncio.wait_for(self.slots.acquire(), timeout=1)
        except TimeoutError:
            raise HTTPException(503, "DNS service is busy") from None
        try:
            account_id, period, policy = await self.reserve(len(wire), token=token, source_ip=source_ip)
            failed, blocked = False, False
            try:
                response, blocked = await asyncio.to_thread(self.resolver.resolve, query, policy)
                failed = response.rcode() == dns.rcode.SERVFAIL
                output = serialize(response, query, udp=udp)
            except dns.exception.DNSException, OSError, ValueError:
                failed = True
                response = dns.message.make_response(query)
                response.flags |= dns.flags.RA
                response.flags &= ~dns.flags.AD
                response.set_rcode(dns.rcode.SERVFAIL)
                output = serialize(response, query, udp=udp)
            async with self.sessions() as db:
                await db.execute(
                    update(DNSUsagePeriod)
                    .where(DNSUsagePeriod.account_id == account_id, DNSUsagePeriod.period == period)
                    .values(
                        response_bytes=DNSUsagePeriod.response_bytes + len(output),
                        errors=DNSUsagePeriod.errors + int(failed),
                        blocked=DNSUsagePeriod.blocked + int(blocked),
                    )
                )
                await db.commit()
            return output
        finally:
            self.slots.release()
