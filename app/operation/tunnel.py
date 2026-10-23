"""Owner-only native infrastructure changes with a database-wide deployment fence."""

import asyncio
import base64
import secrets
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import func, select

from app.core.manager import core_manager
from app.db import GetDB
from app.db.models import CoreConfig, CoreType, NativeTunnel, Node, NodeStatus, ServiceAudit, TunnelDeployment
from app.models.tunnel import TunnelView
from app.tunnel.compiler import USER_PROTOCOLS, compose
from app.tunnel.guards import leased, lock_state


def key():
    return base64.b64encode(secrets.token_bytes(16)).decode()


def audit(db, actor, action, target, **details):
    db.add(ServiceAudit(actor=actor, action=f"tunnel.{action}", target_id=str(target), details=details))


async def rows(db):
    return list((await db.scalars(select(NativeTunnel).order_by(NativeTunnel.id))).all())


async def validate_topology(db, tunnels, *, core_overrides=None):
    active = [row for row in tunnels if row.enabled]
    sources = [row.source_node_id for row in active]
    exits = {row.exit_node_id for row in active}
    if len(sources) != len(set(sources)):
        raise HTTPException(409, "Only one enabled exit per source node is supported")
    if set(sources) & exits:
        raise HTTPException(409, "Tunnel chains and loops are not supported; source and exit roles must be disjoint")
    ids = {node_id for row in tunnels for node_id in (row.source_node_id, row.exit_node_id)}
    nodes = {row.id: row for row in (await db.scalars(select(Node).where(Node.id.in_(ids)))).all()}
    if ids - nodes.keys():
        raise HTTPException(409, "A tunnel node no longer exists")
    cores = {
        row.id: row
        for row in (
            await db.scalars(
                select(CoreConfig).where(CoreConfig.id.in_({row.core_config_id or 1 for row in nodes.values()}))
            )
        ).all()
    }
    cores.update(core_overrides or {})
    for node_id in ids:
        node = nodes[node_id]
        core = cores.get(node.core_config_id or 1)
        if not core or core.type not in (None, CoreType.xray):
            raise HTTPException(422, "Native SS2022 tunnels require Xray on both nodes")
        if any(row.exit_node_id == node_id and row.port in (node.port, node.api_port) for row in tunnels):
            raise HTTPException(422, "Tunnel port conflicts with the node management API")
        # Validate disabled drafts too, before they can be enabled later.
        relevant = [row for row in tunnels if node_id in (row.source_node_id, row.exit_node_id)]
        try:
            compose(core.config, node_id, relevant, nodes, core.exclude_inbound_tags or ())
            for row in relevant:
                if node_id == row.source_node_id:
                    managed = core_manager.validate_core(
                        core.config,
                        set(core.exclude_inbound_tags or ()),
                        set(core.fallbacks_inbound_tags or ()),
                        core.type,
                    )
                    if not set(row.inbound_tags).issubset(set(managed.inbounds)):
                        raise ValueError("Selected user inbound is absent or excluded")
        except (ValueError, KeyError, TypeError) as exc:
            raise HTTPException(422, str(exc)) from None
    return nodes


async def validate_core_change(db, core_id, candidate):
    await lock_state(db)
    tunnels = await rows(db)
    if tunnels:
        await validate_topology(db, tunnels, core_overrides={core_id: candidate})


async def state_view(db):
    tunnels = await rows(db)
    state = await db.get(TunnelDeployment, 1)
    options = []
    cores = {row.id: row for row in (await db.scalars(select(CoreConfig))).all()}
    for node in (await db.scalars(select(Node).order_by(Node.id))).all():
        core = cores.get(node.core_config_id or 1)
        options.append(
            {
                "id": node.id,
                "name": node.name,
                "address": node.address,
                "status": node.status,
                "core_type": (core.type.value if core.type else "xray") if core else "missing",
                "inbound_tags": [
                    item["tag"]
                    for item in (core.config.get("inbounds", []) if core else [])
                    if item.get("protocol") in USER_PROTOCOLS
                    and item.get("tag") not in (core.exclude_inbound_tags or ())
                ],
            }
        )
    return {
        "items": [TunnelView.model_validate(row) for row in tunnels],
        "nodes": options,
        "applying": leased(state) if state else False,
        "lease_until": state.lease_until if state else None,
        "last_result": state.last_result or None if state else None,
        "traffic_readiness": "not_probed",
    }


async def create(db, body, actor):
    await lock_state(db)
    if await db.scalar(select(func.count()).select_from(NativeTunnel)) >= 16:
        raise HTTPException(409, "This release supports at most 16 native tunnel links")
    if await db.scalar(select(NativeTunnel.id).where(NativeTunnel.name == body.name)):
        raise HTTPException(409, "Tunnel name already exists")
    row = NativeTunnel(**body.model_dump(), shared_key=key())
    # A temporary id is unnecessary for topology; flushing does not deploy anything.
    db.add(row)
    await db.flush()
    await validate_topology(db, await rows(db))
    audit(db, actor, "create", row.id, revision=row.revision)
    await db.commit()
    return row


async def get_locked(db, tunnel_id, revision):
    await lock_state(db)
    row = await db.get(NativeTunnel, tunnel_id, with_for_update=True, populate_existing=True)
    if row is None:
        raise HTTPException(404, "Tunnel not found")
    if row.revision != revision:
        raise HTTPException(409, "Tunnel changed; reload before editing")
    return row


async def modify(db, tunnel_id, body, actor):
    row = await get_locked(db, tunnel_id, body.expected_revision)
    if await db.scalar(select(NativeTunnel.id).where(NativeTunnel.name == body.name, NativeTunnel.id != row.id)):
        raise HTTPException(409, "Tunnel name already exists")
    for field, value in body.model_dump(exclude={"expected_revision"}).items():
        setattr(row, field, value)
    row.revision += 1
    await db.flush()
    await validate_topology(db, await rows(db))
    audit(db, actor, "update", row.id, revision=row.revision)
    await db.commit()
    return row


async def rotate(db, tunnel_id, revision, actor):
    row = await get_locked(db, tunnel_id, revision)
    row.shared_key = key()
    row.revision += 1
    audit(db, actor, "rotate", row.id, revision=row.revision)
    await db.commit()
    return row


async def remove(db, tunnel_id, revision, actor):
    row = await get_locked(db, tunnel_id, revision)
    if row.enabled or row.applied_revision != row.revision:
        raise HTTPException(409, "Disable and successfully apply the tunnel before deleting it")
    audit(db, actor, "delete", row.id)
    await db.delete(row)
    await db.commit()


async def deploy(actor, *, session_factory=GetDB, restart=None):
    """Runs on the native node worker. RPC completion acknowledges Start, never game health.

    Partial failures stay pending. No automatic direct-route fallback; failed encrypted
    exits therefore cannot silently send selected traffic through the source server.
    """
    if restart is None:
        from app.operation import OperatorType
        from app.operation.node import NodeOperation

        operator = NodeOperation(operator_type=OperatorType.SYSTEM)
        restart = operator._connect_single_node_local
    token = str(uuid4())
    async with session_factory() as db:
        state = await lock_state(db)
        tunnels = await rows(db)
        nodes = await validate_topology(db, tunnels)
        if not tunnels:
            return {"ok": True, "state": "no_changes", "nodes": [], "traffic_readiness": "not_probed"}
        disabled_nodes = [
            node.id for node in nodes.values() if node.status in (NodeStatus.disabled, NodeStatus.limited)
        ]
        if disabled_nodes:
            raise HTTPException(409, "Enable tunnel nodes and resolve node quotas before applying")
        revisions = {row.id: row.revision for row in tunnels}
        state.lease, state.lease_until = token, datetime.now(UTC) + timedelta(seconds=900)
        state.last_result = {"ok": False, "state": "applying", "nodes": []}
        audit(db, actor, "apply.start", "all", revisions=revisions)
        # Do not hold a database write transaction during network operations.
        await db.commit()
    active = [row for row in tunnels if row.enabled]
    active_sources = {row.source_node_id for row in active}
    active_exits = {row.exit_node_id for row in active}
    all_sources = {row.source_node_id for row in tunnels}
    all_exits = {row.exit_node_id for row in tunnels}
    phases = [
        all_sources - active_sources - active_exits,
        active_exits,
        active_sources,
        all_exits - active_exits - active_sources - all_sources,
    ]
    results = []
    ok = True
    cancelled = False
    try:
        async with asyncio.timeout(600):
            semaphore = asyncio.Semaphore(8)

            async def apply_node(node_id):
                async with semaphore:
                    try:
                        async with session_factory() as db:
                            success = await asyncio.wait_for(
                                restart(db, node_id, force_start=True, require_start_ack=True), timeout=90
                            )
                        return {"node_id": node_id, "ok": bool(success)}
                    except Exception:
                        # Core failures may contain full configurations. Never echo them.
                        return {"node_id": node_id, "ok": False}

            for phase in phases:
                phase_results = await asyncio.gather(*(apply_node(node_id) for node_id in sorted(phase)))
                results.extend(phase_results)
                if not all(item["ok"] for item in phase_results):
                    ok = False
                    break
    except TimeoutError:
        ok = False
    except asyncio.CancelledError:
        ok, cancelled = False, True
    result = {
        "ok": ok,
        "state": "applied" if ok else "partial_failure",
        "nodes": results,
        "traffic_readiness": "not_probed",
    }
    async with session_factory() as db:
        state = await lock_state(db, allow_applying=True)
        if state.lease == token:
            if ok:
                for row in await rows(db):
                    if revisions.get(row.id) == row.revision:
                        row.applied_revision = row.revision
            state.last_result = result
            state.lease, state.lease_until = None, None
            audit(db, actor, "apply.finish", "all", **result)
            await db.commit()
    if cancelled:
        raise asyncio.CancelledError
    return result
