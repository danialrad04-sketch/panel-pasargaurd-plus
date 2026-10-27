"""Explicit post-restore reconciliation with private node revision journals."""

import asyncio
import hashlib
import json
import re
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import select

from app.db.base import GetDB
from app.db.models import NativeTunnel, Node, ServiceAudit, SmartDNSNodeState, TunnelDeployment, TunnelFailoverPolicy
from app.paqet.client import request
from app.tunnel.guards import leased, lock_state


async def snapshot(db):
    tunnels = (await db.scalars(select(NativeTunnel).order_by(NativeTunnel.id))).all()
    gateways = (await db.scalars(select(SmartDNSNodeState).order_by(SmartDNSNodeState.node_id))).all()
    nodes = (await db.scalars(select(Node).order_by(Node.id))).all()
    value = {
        "tunnels": [(row.id, row.engine_id, row.revision, row.enabled) for row in tunnels],
        "gateways": [(row.node_id, row.revision) for row in gateways],
        "nodes": [(row.id, row.address, row.port, row.tunnel_api_port) for row in nodes],
    }
    fingerprint = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
    return tunnels, gateways, nodes, fingerprint


async def inventory(*, sessions=GetDB):
    async with sessions() as db:
        tunnels, gateways, nodes, fingerprint = await snapshot(db)
    needed = {row.node_id for row in gateways} | {
        identity
        for row in tunnels
        if row.transport != "shadowsocks-2022" or row.health_enabled
        for identity in (row.source_node_id, row.exit_node_id)
    }
    known = {row.engine_id for row in tunnels}
    semaphore = asyncio.Semaphore(4)

    async def inspect(node):
        result = {
            "node_id": node.id,
            "name": node.name,
            "required": node.id in needed,
            "available": False,
            "paqet_links": [],
            "smartdns_revision": 0,
        }
        async with semaphore:
            try:
                async with asyncio.timeout(8):
                    required_engines = {
                        row.transport
                        for row in tunnels
                        if row.transport in ("backhaul", "gost", "frp")
                        and node.id in (row.source_node_id, row.exit_node_id)
                    }
                    for namespace in ("paqet", "backhaul", "gost", "frp"):
                        try:
                            links = await request(
                                node, "GET", "links", **({"namespace": namespace} if namespace != "paqet" else {})
                            )
                        except RuntimeError:
                            if namespace in required_engines or namespace == "paqet":
                                raise
                            continue
                        if not isinstance(links, list) or len(links) > 256:
                            raise ValueError("Invalid node inventory")
                        for value in links:
                            identity = value.get("id", "")
                            revision = value.get("revision")
                            if (
                                not isinstance(identity, str)
                                or not re.fullmatch(r"[0-9a-f]{32}|[1-9][0-9]{0,15}", identity)
                                or type(revision) is not int
                                or not 0 < revision < 2147483647
                            ):
                                raise ValueError("Invalid engine journal identity")
                            result["paqet_links"].append(
                                {
                                    "id": identity,
                                    "revision": revision,
                                    "enabled": value.get("enabled") is True,
                                    "orphan": identity not in known,
                                    "engine": namespace,
                                }
                            )
                    try:
                        gateway = await request(node, "GET", "status", namespace="smartdns")
                        revision = gateway.get("revision") if isinstance(gateway, dict) else None
                        if type(revision) is not int or not 0 <= revision < 2147483647:
                            raise ValueError("Invalid gateway revision")
                        result["smartdns_revision"] = revision
                    except RuntimeError:
                        if any(row.node_id == node.id for row in gateways):
                            raise
                    result["available"] = True
            except RuntimeError, ValueError, TimeoutError:
                pass
        return result

    return {"fingerprint": fingerprint, "nodes": await asyncio.gather(*(inspect(node) for node in nodes))}


async def reconcile(expected, disable_orphans, actor, *, sessions=GetDB):
    observed = await inventory(sessions=sessions)
    if observed["fingerprint"] != expected:
        raise HTTPException(409, "Panel state changed; inspect a fresh recovery inventory")
    if any(row["required"] and not row["available"] for row in observed["nodes"]):
        raise HTTPException(
            503, "A required node could not be inspected; repair node certificates/API identity or access first"
        )
    orphans = [
        (node["node_id"], link)
        for node in observed["nodes"]
        if node["available"]
        for link in node["paqet_links"]
        if link["orphan"] and link["enabled"]
    ]
    if orphans and not disable_orphans:
        raise HTTPException(409, "Review and explicitly authorize disabling orphan engines before reconciliation")
    claim = str(uuid4())
    async with sessions() as db:
        state = await lock_state(db)
        tunnels, gateways, nodes, fingerprint = await snapshot(db)
        if fingerprint != expected:
            raise HTTPException(409, "Panel state changed; reload recovery inventory")
        state.lease, state.lease_until = claim, datetime.now(UTC) + timedelta(seconds=120)
        if any(leased(row) for row in gateways):
            raise HTTPException(409, "A gateway deployment is still in progress")
        if any(leased(row) for row in (await db.scalars(select(TunnelFailoverPolicy))).all()):
            raise HTTPException(409, "A failover transition is still in progress")
        for row in tunnels:
            row.applied_revision = 0
        for row in gateways:
            row.applied_revision = 0
        for policy in (await db.scalars(select(TunnelFailoverPolicy))).all():
            policy.enabled = False
            policy.suspended = True
        await db.commit()
        from app.tunnel.hosts import refresh_tunnel_hosts

        await refresh_tunnel_hosts(db, [row.id for row in tunnels])
    ok = True
    try:
        by_id = {node.id: node for node in nodes}
        semaphore = asyncio.Semaphore(4)

        async def retire(node_id, link):
            async with semaphore:
                try:
                    answer = await asyncio.wait_for(
                        request(
                            by_id[node_id],
                            "PUT",
                            f"links/{link['id']}",
                            {
                                "revision": link["revision"] + 1,
                                "enabled": False,
                                "config": None,
                                "peer": "",
                                "manage_firewall": False,
                            },
                            **({"namespace": link["engine"]} if link.get("engine", "paqet") != "paqet" else {}),
                        ),
                        timeout=8,
                    )
                    return (
                        isinstance(answer, dict)
                        and not answer.get("running")
                        and answer.get("revision") == link["revision"] + 1
                    )
                except RuntimeError, ValueError, TimeoutError:
                    return False

        try:
            async with asyncio.timeout(90):
                ok = all(await asyncio.gather(*(retire(identity, link) for identity, link in orphans)))
        except TimeoutError:
            ok = False
        async with sessions() as db:
            state = await lock_state(db, allow_applying=True)
            if state.lease != claim:
                raise HTTPException(409, "Recovery lease changed")
            if ok:
                for row in (await db.scalars(select(NativeTunnel))).all():
                    remote = [
                        value["revision"]
                        for node in observed["nodes"]
                        for value in node["paqet_links"]
                        if value["id"] == row.engine_id
                    ]
                    row.revision = max([row.revision, *remote]) + 1
                for row in (await db.scalars(select(SmartDNSNodeState))).all():
                    remote = [node["smartdns_revision"] for node in observed["nodes"] if node["node_id"] == row.node_id]
                    row.revision = max([row.revision, *remote]) + 1
            state.lease = state.lease_until = None
            db.add(
                ServiceAudit(
                    actor=actor,
                    action="operations.runtime_recovery",
                    target_id="all",
                    details={"ok": ok, "orphan_engines": len(orphans)},
                )
            )
            await db.commit()
    except BaseException:
        async with sessions() as db:
            state = await db.get(TunnelDeployment, 1, with_for_update=True)
            if state and state.lease == claim:
                state.lease = state.lease_until = None
                await db.commit()
        raise
    return {
        "ok": ok,
        "state": "reapply_required" if ok else "orphan_cleanup_failed",
        "automation": "disabled",
        "orphan_engines": len(orphans),
    }
