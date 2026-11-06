"""Claimed, recoverable native jobs. Network writes never hold a DB writer lock."""

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from sqlalchemy import func, select

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import NativeProvisionJob, NativeTunnel, Node, ServiceAudit, TunnelDeployment
from app.models.node import NodeCreate
from app.provisioning import ingress, secrets, ssh
from app.tunnel.engines import ENGINES
from app.utils.helpers import fix_datetime_timezone


class ClaimLost(RuntimeError):
    pass


async def claim(sessions):
    now = datetime.now(UTC)
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        # One short shared row lock serializes capacity checks on PostgreSQL/
        # MySQL too. No remote operation holds this lock.
        if not await db.get(TunnelDeployment, 1, with_for_update=True):
            await db.commit()
            return None
        expired = (
            await db.scalars(
                select(NativeProvisionJob)
                .where(NativeProvisionJob.lease_until < now, NativeProvisionJob.active_target.is_not(None))
                .with_for_update()
            )
        ).all()
        for job in expired:
            job.lease = job.lease_until = None
            if job.attempts >= 3 or now - fix_datetime_timezone(job.created_at) > timedelta(minutes=45):
                job.state = "interrupted"
                job.credential_cipher = None
                job.active_target = None
                job.result = {"code": "worker_interrupted"}
            else:
                job.state = "queued"
        active = await db.scalar(
            select(func.count()).select_from(NativeProvisionJob).where(NativeProvisionJob.lease_until > now)
        )
        if active >= 2:
            await db.commit()
            return None
        row = await db.scalar(
            select(NativeProvisionJob)
            .where(NativeProvisionJob.state == "queued", NativeProvisionJob.lease.is_(None))
            .order_by(NativeProvisionJob.created_at)
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        if not row:
            await db.commit()
            return None
        row.lease = str(uuid4())
        row.lease_until = now + timedelta(seconds=90)
        row.attempts += 1
        row.state = "connecting" if row.kind == "install" else "activating"
        row.updated_at = now
        await db.commit()
        return row


async def update(sessions, identity, token, state=None, progress=None):
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        row = await db.get(NativeProvisionJob, identity, with_for_update=True)
        if not row or row.lease != token:
            raise ClaimLost()
        row.lease_until = datetime.now(UTC) + timedelta(seconds=90)
        row.updated_at = datetime.now(UTC)
        if state:
            row.state = state
        if progress is not None:
            row.progress = progress
        await db.commit()


async def finish(sessions, job, state, result):
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        row = await db.get(NativeProvisionJob, job.id, with_for_update=True)
        if not row or row.lease != job.lease:
            return
        row.state = state
        row.result = result
        row.updated_at = datetime.now(UTC)
        row.lease = row.lease_until = None
        if state != "queued":
            row.active_target = None
            row.credential_cipher = None
        if state == "ready":
            row.progress = 100
        db.add(ServiceAudit(actor=job.actor, action="provision." + state, target_id=job.id, details=result))
        await db.commit()


def compatible(engine, value):
    if value.get("contract") != 1 or value.get("installed") is not True:
        return False
    if engine == "paqet":
        return (
            value.get("engine") == "v1.0.0-alpha.21"
            and value.get("source_commit") == "4b81ce0ab56b7cc9aec7501c87c4804676cdb1b4"
        )
    definition = ENGINES[engine]
    return (
        value.get("engine") == engine
        and value.get("version") == definition["version"]
        and value.get("source_commit") == definition["commit"]
    )


async def register(sessions, job, registration):
    payload = job.payload
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        current = await db.get(NativeProvisionJob, job.id, with_for_update=True)
        if not current or current.lease != job.lease:
            raise ClaimLost()
        if current.node_id:
            row = await db.get(Node, current.node_id)
            if not row:
                raise ssh.ProvisionError("existing_node_removed")
        else:
            if registration.get("address") != payload["host"]:
                raise ssh.ProvisionError("registration_address_mismatch")
            for key, expected in (
                ("port", payload["node_port"]),
                ("tunnel_api_port", payload["tunnel_port"]),
                ("health_echo_port", payload["health_port"]),
            ):
                if registration.get(key) != expected:
                    raise ssh.ProvisionError("registration_port_mismatch")
            value = NodeCreate(
                name=payload["name"],
                connection_type="grpc",
                keep_alive=120,
                core_config_id=payload["core_config_id"],
                **registration,
            )
            existing = await db.scalar(select(Node).where(Node.address == value.address, Node.port == value.port))
            if existing:
                if existing.api_key != value.api_key or existing.server_ca != value.server_ca:
                    raise ssh.ProvisionError("node_registration_conflict")
                row = existing
            else:
                row = Node(**value.model_dump())
                db.add(row)
                await db.flush()
            current.node_id = row.id
        current.state = "starting_node"
        current.progress = 85
        await db.commit()
        return row.id


async def installed(job, sessions, advance, bootstrap):
    from app.operation import OperatorType
    from app.operation.node import NodeOperation
    from app.paqet.client import request

    expected = None
    if job.node_id:
        async with sessions() as db:
            node = await db.get(Node, job.node_id)
            if not node:
                raise ssh.ProvisionError("existing_node_removed")
            expected = {"address": node.address, "server_ca": node.server_ca, "api_key": node.api_key}
    credentials = secrets.unseal(job.credential_cipher, job.id)
    registration, metadata = await bootstrap(job.payload, credentials, job.id, advance, expected)
    identity = await register(sessions, job, registration)
    await advance("starting_node", 85)
    operator = NodeOperation(operator_type=OperatorType.SYSTEM)
    async with sessions() as db:
        node = await db.get(Node, identity)
        async with asyncio.timeout(30):
            for attempt in range(15):
                try:
                    capability = await request(node, "GET", "capabilities", namespace=job.payload["engine"])
                    if compatible(job.payload["engine"], capability):
                        break
                except RuntimeError:
                    pass
                await asyncio.sleep(1)
            else:
                raise ssh.ProvisionError("installed_node_not_verified")
        if not await operator._connect_single_node_local(db, identity, force_start=True, require_start_ack=True):
            raise ssh.ProvisionError("node_core_start_failed")
    return {
        "node_id": identity,
        "engine": job.payload["engine"],
        "bundle": metadata["sha256"],
        "scope": "installed_node_and_core",
    }


async def activated(job, sessions, advance):
    from app.models.tunnel import TunnelModify, TunnelView
    from app.operation import OperatorType, tunnel as operation
    from app.operation.node import NodeOperation
    from app.tunnel.failover import verify_candidate
    from app.tunnel.health import probe

    identity = job.payload["tunnel_id"]
    revision = job.payload["revision"]
    await advance("checking_configuration", 10)
    async with sessions() as db:
        row = await db.get(NativeTunnel, identity)
        if not row or row.revision != revision:
            raise ssh.ProvisionError("tunnel_revision_changed")
        if not row.enabled or not row.health_enabled:
            values = TunnelView.model_validate(row).model_dump(
                include={"name", "inbound_tags", "domains", "port", "transport", "paqet_options", "carrier_options"}
            )
            if row.transport not in ENGINES:
                values["carrier_options"] = None
            if row.transport != "paqet":
                values["paqet_options"] = None
            if row.transport == "paqet":
                for item in (values.get("paqet_options") or {}).get("socks5", []):
                    item.pop("authenticated", None)
            values.update(expected_revision=revision, enabled=True, health_enabled=True)

            async def remember(session, changed):
                current = await session.get(NativeProvisionJob, job.id, with_for_update=True)
                if not current or current.lease != job.lease:
                    raise ClaimLost()
                current.payload = {**current.payload, "revision": changed.revision}

            row = await operation.modify(db, identity, TunnelModify(**values), job.actor, before_commit=remember)
            revision = row.revision
        transport = row.transport
    await advance("configuring_ingress", 30)
    operator = NodeOperation(operator_type=OperatorType.SYSTEM)

    async def restart(db, node_id, **kwargs):
        await ingress.apply(db, node_id, include_users=False)
        acknowledged = await operator._connect_single_node_local(db, node_id, **kwargs)
        if acknowledged:
            await ingress.apply(db, node_id, include_users=True)
        return acknowledged

    result = await operation.deploy("provision:" + job.actor, session_factory=sessions, restart=restart)
    if not result["ok"]:
        raise ssh.ProvisionError("tunnel_apply_failed")
    await advance("probing_tcp_udp", 80)
    if not await verify_candidate(identity, revision, transport, sessions, probe):
        raise ssh.ProvisionError("carrier_health_failed")
    return {
        "tunnel_id": identity,
        "revision": revision,
        "scope": "verified_encrypted_carrier",
        "user_inbound_readiness": "not_probed",
    }


async def drain(*, sessions=GetDB, bootstrap=ssh.bootstrap):
    job = await claim(sessions)
    if not job:
        return

    async def advance(state, progress):
        await update(sessions, job.id, job.lease, state, progress)

    async def heartbeat():
        while True:
            await asyncio.sleep(15)
            await update(sessions, job.id, job.lease)

    pulse = asyncio.create_task(heartbeat())
    try:
        result = await (
            installed(job, sessions, advance, bootstrap) if job.kind == "install" else activated(job, sessions, advance)
        )
        await finish(sessions, job, "ready", result)
    except asyncio.CancelledError:
        # SSH may have completed a remote write. Preserve the durable claim until
        # expiry; the next owner resumes the same idempotent job, never a new one.
        raise
    except ClaimLost:
        pass
    except ssh.ProvisionError as error:
        if error.code == "install_in_progress" and job.attempts < 20:
            await finish(sessions, job, "queued", {"code": "waiting_for_remote_install"})
        else:
            await finish(sessions, job, "failed", {"code": error.code})
    except Exception:
        await finish(sessions, job, "failed", {"code": "native_job_failed"})
    finally:
        pulse.cancel()
        try:
            await pulse
        except asyncio.CancelledError, ClaimLost:
            pass
