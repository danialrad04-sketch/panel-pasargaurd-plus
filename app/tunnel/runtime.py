"""Load native desired state on every node start, including repair/restart/NATS roles."""

import json

from sqlalchemy import or_, select

from app.db import GetDB
from app.db.models import CoreType, NativeTunnel, Node
from app.tunnel.compiler import compose


async def node_payload(node_id, core):
    async with GetDB() as db:
        tunnels = list(
            (
                await db.scalars(
                    select(NativeTunnel).where(
                        NativeTunnel.enabled.is_(True),
                        or_(NativeTunnel.source_node_id == node_id, NativeTunnel.exit_node_id == node_id),
                    )
                )
            ).all()
        )
        if not tunnels:
            return core.to_str(), set(getattr(core, "exclude_inbound_tags", ()))
        if core.type != CoreType.xray:
            raise ValueError("Native tunnels require an Xray core on both nodes")
        ids = {row.exit_node_id for row in tunnels}
        nodes = {row.id: row for row in (await db.scalars(select(Node).where(Node.id.in_(ids)))).all()}
        payload, excluded = compose(dict(core), node_id, tunnels, nodes, core.exclude_inbound_tags)
        return json.dumps(payload), excluded


async def apply_node_engines(node_id):
    from app.paqet.client import apply

    async with GetDB() as db:
        tunnels = list(
            (
                await db.scalars(
                    select(NativeTunnel)
                    .where(
                        NativeTunnel.transport == "paqet",
                        or_(NativeTunnel.source_node_id == node_id, NativeTunnel.exit_node_id == node_id),
                    )
                    .order_by(NativeTunnel.enabled, NativeTunnel.id)
                )
            ).all()
        )
        if not tunnels:
            return
        ids = {i for t in tunnels for i in (t.source_node_id, t.exit_node_id)}
        nodes = {n.id: n for n in (await db.scalars(select(Node).where(Node.id.in_(ids)))).all()}
        for tunnel in tunnels:
            await apply(nodes[node_id], tunnel, nodes)
