"""Database guards shared by native infrastructure CRUD and tunnel deployment.

Kept below the operation layer so core/node database cleanup does not import
control-plane operations or the node runtime.
"""

from datetime import UTC, datetime

from fastapi import HTTPException
from sqlalchemy import or_, select

from app.db.base import begin_immediate_if_sqlite
from app.db.models import NativeTunnel, Node, TunnelDeployment
from app.utils.helpers import fix_datetime_timezone


def leased(state):
    return bool(state.lease and state.lease_until and fix_datetime_timezone(state.lease_until) > datetime.now(UTC))


async def lock_state(db, *, allow_applying=False):
    await begin_immediate_if_sqlite(db)
    state = await db.get(TunnelDeployment, 1, with_for_update=True, populate_existing=True)
    if state is None:
        raise HTTPException(503, "Apply the native tunnel database migration first")
    if leased(state) and not allow_applying:
        raise HTTPException(409, "A tunnel deployment is in progress; retry after it finishes")
    return state


async def assert_core_unreferenced(db, core_ids):
    node_ids = list((await db.scalars(select(Node.id).where(Node.core_config_id.in_(core_ids)))).all())
    if 1 in core_ids:
        node_ids.extend((await db.scalars(select(Node.id).where(Node.core_config_id.is_(None)))).all())
    await assert_node_unreferenced(db, node_ids)


async def assert_node_unreferenced(db, node_ids):
    from app.db.models import SmartDNSRoute, TunnelFailoverPolicy

    if await db.scalar(select(SmartDNSRoute.id).where(SmartDNSRoute.node_id.in_(node_ids)).limit(1)):
        raise HTTPException(409, "Disable/apply/remove native SmartDNS routes before replacing or deleting this node")
    if await db.scalar(
        select(TunnelFailoverPolicy.source_node_id).where(TunnelFailoverPolicy.source_node_id.in_(node_ids)).limit(1)
    ):
        raise HTTPException(409, "Remove the failover policy before deleting this node")

    if await db.scalar(
        select(NativeTunnel.id)
        .where(
            or_(
                NativeTunnel.source_node_id.in_(node_ids),
                NativeTunnel.exit_node_id.in_(node_ids),
            )
        )
        .limit(1)
    ):
        raise HTTPException(409, "Node belongs to a native tunnel; disable, apply and remove that tunnel first")


async def assert_paqet_node_update_safe(db, node_id):
    from app.db.models import SmartDNSNodeState, SmartDNSRoute

    if await db.get(SmartDNSNodeState, node_id):
        raise HTTPException(409, "Native DNS nodes require the source-built fork image")

    if await db.scalar(select(SmartDNSRoute.id).where(SmartDNSRoute.node_id == node_id).limit(1)):
        raise HTTPException(409, "SmartDNS nodes require the source-built fork; update its image")
    if await db.scalar(
        select(NativeTunnel.id)
        .where(
            or_(NativeTunnel.transport != "shadowsocks-2022", NativeTunnel.health_enabled.is_(True)),
            or_(NativeTunnel.source_node_id == node_id, NativeTunnel.exit_node_id == node_id),
        )
        .limit(1)
    ):
        raise HTTPException(409, "Paqet nodes require the source-built fork image; update using node/compose.plus.yml")
