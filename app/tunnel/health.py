"""Measured TCP/UDP samples over the native encrypted carrier, with DB fencing."""

import asyncio
import secrets
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import select

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import NativeTunnel, Node, TunnelHealthState
from app.paqet.client import request
from app.utils.helpers import fix_datetime_timezone


def view(state):
    return {
        "tunnel_id": state.tunnel_id,
        "revision": state.revision,
        "checked_at": state.checked_at,
        "failures": state.failures,
        "successes": state.successes,
        "result": state.result,
        "scope": "encrypted_carrier",
        "user_inbound_readiness": "not_probed",
    }


async def probe(tunnel_id, expected_revision, *, sessions=GetDB, minimum_age=0):
    claim = str(uuid4())
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        row = await db.get(NativeTunnel, tunnel_id, with_for_update=True)
        if row is None:
            raise HTTPException(404, "Tunnel not found")
        if not row.health_enabled:
            raise HTTPException(409, "Enable carrier monitoring and apply the tunnel revision first")
        if row.revision != expected_revision or row.applied_revision != row.revision or not row.enabled:
            raise HTTPException(409, "Probe requires the enabled, acknowledged revision")
        state = await db.get(TunnelHealthState, row.id, with_for_update=True)
        if state is None:
            state = TunnelHealthState(tunnel_id=row.id)
            db.add(state)
        now = datetime.now(UTC)
        if (
            minimum_age
            and state.revision == row.revision
            and state.checked_at
            and now - fix_datetime_timezone(state.checked_at) < timedelta(seconds=minimum_age)
        ):
            return view(state)
        if state.lease and state.lease_until and fix_datetime_timezone(state.lease_until) > now:
            raise HTTPException(409, "A carrier probe is already running")
        source, exit_node = await db.get(Node, row.source_node_id), await db.get(Node, row.exit_node_id)
        if not source or not exit_node:
            raise HTTPException(409, "Tunnel node unavailable")
        revision = row.revision
        source_id, exit_id = source.id, exit_node.id
        options = row.paqet_options or {}
        body = {
            "address": "127.0.0.1" if row.transport == "paqet" else exit_node.address,
            "port": options.get("client_port", 11081) if row.transport == "paqet" else row.port,
            "password": row.shared_key,
            "token": secrets.token_hex(32),
        }
        state.lease, state.lease_until = claim, now + timedelta(seconds=45)
        await db.commit()
    result = {"healthy": False, "status": "unavailable", "layer": "encrypted_carrier"}
    cancelled = False
    try:
        async with asyncio.timeout(20):
            # Never hold a database transaction or writer lease during network I/O.
            async with sessions() as db:
                source, exit_node = await db.get(Node, source_id), await db.get(Node, exit_id)
            prepared = await request(exit_node, "POST", "prepare", {"token": body["token"]}, namespace="health")
            port = prepared.get("port") if isinstance(prepared, dict) else None
            if type(port) is not int or not 1024 <= port <= 65535:
                raise ValueError("Invalid echo preparation")
            body["target_port"] = port
            measured = await request(source, "POST", "probe", body, namespace="health")
            if not isinstance(measured, dict) or measured.get("layer") != "encrypted_carrier":
                raise ValueError("Unsupported carrier probe contract")
            # Whitelist bounded numeric metrics; no raw node text or configurations.
            result = {
                "healthy": measured.get("tcp") is True and measured.get("udp") is True,
                "status": "measured",
                "layer": "encrypted_carrier",
                "tcp": measured.get("tcp") is True,
                "udp": measured.get("udp") is True,
            }
            for field, maximum in (
                ("tcp_rtt_ms", 20000),
                ("udp_rtt_ms", 20000),
                ("udp_loss_percent", 100),
                ("sample_mbps", 1e9),
                ("udp_sent", 3),
                ("udp_received", 3),
            ):
                value = measured.get(field)
                if type(value) not in (int, float) or not 0 <= value <= maximum:
                    raise ValueError("Invalid carrier measurement")
                result[field] = value
    except TimeoutError, RuntimeError, ValueError, OSError:
        result = {"healthy": False, "status": "unavailable", "layer": "encrypted_carrier"}
    finally:
        # Only the claim owner may publish; edits/rotation invalidate the sample.
        async with sessions() as db:
            await begin_immediate_if_sqlite(db)
            state = await db.get(TunnelHealthState, tunnel_id, with_for_update=True)
            row = await db.get(NativeTunnel, tunnel_id)
            if state and state.lease == claim:
                state.lease = state.lease_until = None
                if (
                    not cancelled
                    and row
                    and row.revision == revision
                    and row.applied_revision == revision
                    and row.enabled
                ):
                    if state.revision != revision:
                        state.failures = state.successes = 0
                    state.revision, state.checked_at, state.result = revision, datetime.now(UTC), result
                    state.failures = (
                        state.failures + 1 if result["status"] == "measured" and not result["healthy"] else 0
                    )
                    state.successes = state.successes + 1 if result["healthy"] else 0
                else:
                    result = {"healthy": False, "status": "stale", "layer": "encrypted_carrier"}
                await db.commit()
    return {
        "tunnel_id": tunnel_id,
        "revision": revision,
        "result": result,
        "scope": "encrypted_carrier",
        "user_inbound_readiness": "not_probed",
    }


async def states(db):
    return [view(state) for state in (await db.scalars(select(TunnelHealthState))).all()]


async def collect(*, sessions=GetDB):
    from app.db.models import TunnelFailoverPolicy

    async with sessions() as db:
        sources = set(
            (
                await db.scalars(
                    select(TunnelFailoverPolicy.source_node_id).where(
                        TunnelFailoverPolicy.enabled.is_(True), TunnelFailoverPolicy.suspended.is_(False)
                    )
                )
            ).all()
        )
        targets = [
            (row.id, row.revision)
            for row in (
                await db.scalars(
                    select(NativeTunnel).where(NativeTunnel.enabled.is_(True), NativeTunnel.health_enabled.is_(True))
                )
            ).all()
            if row.source_node_id not in sources and row.applied_revision == row.revision
        ]
    semaphore = asyncio.Semaphore(4)

    async def sample(identity, revision):
        async with semaphore:
            try:
                await probe(identity, revision, sessions=sessions, minimum_age=45)
            except HTTPException, RuntimeError, ValueError:
                pass

    await asyncio.gather(*(sample(*target) for target in targets))
