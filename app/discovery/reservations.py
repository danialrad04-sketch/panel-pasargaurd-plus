"""Ports of tunnels/proxies/DNS that already exist on a server and must never be touched by this panel."""

from fastapi import HTTPException
from sqlalchemy import select

from app.db.models import NativeExternalTunnel


async def reserved_by_address(db) -> dict[str, set[int]]:
    result: dict[str, set[int]] = {}
    for row in (await db.scalars(select(NativeExternalTunnel))).all():
        result.setdefault(row.address, set()).update(
            int(item["port"]) for item in row.ports if isinstance(item, dict) and isinstance(item.get("port"), int)
        )
    return result


async def reserved_for(db, address) -> set[int]:
    return (await reserved_by_address(db)).get(address, set())


def assert_free(ports, reserved, what, status=422):
    taken = sorted(set(ports) & set(reserved))
    if taken:
        raise HTTPException(
            status,
            f"{what} uses port {', '.join(map(str, taken))}, which belongs to an existing tunnel/listener found by "
            "discovery on this server. Pick another port or remove the reservation after retiring that service.",
        )
