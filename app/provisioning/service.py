"""Durable provisioning requests and sanitized owner views."""

import asyncio
from datetime import UTC, datetime

import asyncssh
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.db.base import begin_immediate_if_sqlite
from app.db.models import CoreConfig, CoreType, NativeProvisionJob, NativeTunnel, Node, ServiceAudit
from app.provisioning import bundles, secrets

TERMINAL = {"ready", "failed", "cancelled", "interrupted"}


def view(row):
    return {
        "id": row.id,
        "kind": row.kind,
        "target": row.target,
        "node_id": row.node_id,
        "state": row.state,
        "progress": row.progress,
        "result": row.result,
        "created_at": row.created_at,
        "updated_at": row.updated_at,
        "attempts": row.attempts,
    }


async def scan(body):
    try:
        async with asyncio.timeout(10):
            key = await asyncssh.get_server_host_key(body.host, body.ssh_port, config=None)
        if key is None:
            raise ValueError()
        return {
            "host": body.host,
            "ssh_port": body.ssh_port,
            "server_host_key": key.export_public_key().decode().strip(),
            "fingerprint": key.get_fingerprint("sha256"),
            "trust": "first_connection_review",
        }
    except OSError, asyncssh.Error, TimeoutError, ValueError:
        raise HTTPException(503, "SSH host key could not be inspected") from None


async def enqueue_install(db, body, actor):
    if not bundles.available():
        raise HTTPException(503, "Native installation bundle is not available in this panel build")
    try:
        key = asyncssh.import_public_key(body.server_host_key)
        host_key = key.export_public_key().decode().strip()
    except ValueError, asyncssh.Error:
        raise HTTPException(422, "Invalid SSH server host key") from None
    core = await db.get(CoreConfig, body.core_config_id)
    if not core or core.type not in (None, CoreType.xray):
        raise HTTPException(422, "Select an Xray core for the tunnel node")
    if body.node_id:
        node = await db.get(Node, body.node_id)
        if not node:
            raise HTTPException(404, "Node not found")
        if (node.port, node.tunnel_api_port, node.health_echo_port) != (
            body.node_port,
            body.tunnel_port,
            body.health_port,
        ):
            raise HTTPException(409, "Preserve existing node ports while installing its managed runtime")
        if (node.core_config_id or 1) != body.core_config_id:
            raise HTTPException(409, "Keep the existing node core while upgrading")
    else:
        if await db.scalar(select(Node.id).where(Node.address == body.host, Node.port == body.node_port).limit(1)):
            raise HTTPException(409, "This server is already registered; select its existing node")
        if await db.scalar(select(Node.id).where(Node.name == body.name).limit(1)):
            raise HTTPException(409, "Node name already exists")
    payload = body.model_dump(exclude={"password", "private_key", "key_passphrase"})
    payload["server_host_key"] = host_key
    row = NativeProvisionJob(
        actor=actor,
        kind="install",
        target=f"{body.host}:{body.ssh_port}",
        active_target=f"ssh:{body.host}:{body.ssh_port}",
        payload=payload,
        node_id=body.node_id,
    )
    db.add(row)
    try:
        await db.flush()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "An installation is already queued for this target") from None
    credentials = {
        name: getattr(body, name).get_secret_value() if getattr(body, name) else None
        for name in ("password", "private_key", "key_passphrase")
    }
    try:
        row.credential_cipher = secrets.seal(credentials, row.id)
    except OSError, ValueError:
        raise HTTPException(503, "Private provisioning key could not be initialized") from None
    db.add(
        ServiceAudit(
            actor=actor,
            action="provision.install.queued",
            target_id=row.id,
            details={"node_id": body.node_id, "engine": body.engine, "host_fingerprint": key.get_fingerprint("sha256")},
        )
    )
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "An installation is already queued for this target") from None
    return view(row)


async def enqueue_activation(db, identity, revision, actor):
    row = await db.get(NativeTunnel, identity)
    if not row:
        raise HTTPException(404, "Tunnel not found")
    if row.revision != revision:
        raise HTTPException(409, "Tunnel changed; reload before starting")
    job = NativeProvisionJob(
        actor=actor,
        kind="activate",
        target=f"tunnel:{identity}",
        active_target=f"tunnel:{identity}",
        payload={"tunnel_id": identity, "revision": revision},
    )
    db.add(job)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Tunnel activation is already queued") from None
    return view(job)


async def cancel(db, identity, actor):
    await begin_immediate_if_sqlite(db)
    row = await db.get(NativeProvisionJob, identity, with_for_update=True)
    if not row:
        raise HTTPException(404, "Install job not found")
    if row.state != "queued" or row.lease:
        raise HTTPException(409, "A running installation must reach a safe outcome first")
    row.state = "cancelled"
    row.active_target = None
    row.credential_cipher = None
    row.updated_at = datetime.now(UTC)
    db.add(ServiceAudit(actor=actor, action="provision.cancel", target_id=row.id, details={}))
    await db.commit()
    return view(row)
