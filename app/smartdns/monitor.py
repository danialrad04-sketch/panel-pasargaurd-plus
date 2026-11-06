"""Observe actual node gateway/DNS runtime, without certifying client game paths."""

import asyncio
from datetime import UTC, datetime

from sqlalchemy import select

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import Node, NodeStatus, SmartDNSNodeState, SmartDNSRoute
from app.paqet.client import request


async def collect(*, sessions=GetDB):
    async with sessions() as db:
        enabled = set((await db.scalars(select(SmartDNSRoute.node_id).where(SmartDNSRoute.enabled.is_(True)))).all())
        targets = []
        for state in (await db.scalars(select(SmartDNSNodeState))).all():
            if state.node_id not in enabled and not state.dns_config.get("enabled"):
                continue
            node = await db.get(Node, state.node_id)
            if node:
                targets.append((node, state.revision, state.applied_revision, bool(state.dns_config.get("enabled"))))
    slots = asyncio.Semaphore(4)

    async def sample(node, revision, applied, dns_enabled):
        async with slots:
            result = {"healthy": False, "status": "unavailable_or_stale"}
            if node.status in (NodeStatus.disabled, NodeStatus.limited):
                result["status"] = "node_disabled"
            else:
                try:
                    async with asyncio.timeout(8):
                        state = await request(node, "GET", "status", namespace="smartdns")
                        if (
                            isinstance(state, dict)
                            and state.get("revision") == revision == applied
                            and state.get("dns_enabled", False) == dns_enabled
                        ):
                            result = {"healthy": True, "status": "running"}
                            for name, maximum in (("active_tcp", 256), ("active_udp", 2048)):
                                value = state.get(name)
                                if type(value) is int and 0 <= value <= maximum:
                                    result[name] = value
                except RuntimeError, ValueError, TimeoutError:
                    pass
            async with sessions() as db:
                await begin_immediate_if_sqlite(db)
                current = await db.get(SmartDNSNodeState, node.id, with_for_update=True)
                if current and current.revision == revision:
                    current.checked_at = datetime.now(UTC)
                    current.runtime_result = result
                    await db.commit()

    await asyncio.gather(*(sample(*target) for target in targets))
