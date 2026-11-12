from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from sqlalchemy import select

from app.db import get_db
from app.db.models import ServiceAudit
from app.discovery import service
from app.discovery.reservations import assert_free, reserved_by_address, reserved_for
from app.models.discovery import ReservationInput
from app.routers import discovery
from app.routers.authentication import require_owner
from app.smartdns import gaming
from app.tunnel.wizard import allocate
from tests.test_discovery_facts import iran_facts
from tests.test_gaming_dns import apply_body, world  # noqa: F401


def reservation(address="127.0.0.1", ports=(9999,), name="paqet-client"):
    return ReservationInput(
        address=address,
        items=[
            {
                "name": name,
                "kind": "paqet",
                "role": "client",
                "peer": "203.0.113.9:9999",
                "ports": [{"port": p} for p in ports],
            }
        ],
    )


async def test_reserved_ports_are_listed_per_address_and_audited(world):  # noqa: F811
    async with world.sessions() as db:
        await service.save_reservations(db, reservation(ports=(9999, 1080)), "owner")
        await service.save_reservations(db, reservation(address="198.51.100.7", ports=(7000,), name="other"), "owner")
    async with world.sessions() as db:
        assert await reserved_for(db, "127.0.0.1") == {9999, 1080}
        assert (await reserved_by_address(db))["198.51.100.7"] == {7000}
        assert (await db.scalars(select(ServiceAudit).where(ServiceAudit.action == "discovery.reserve"))).all()
        # Saving the same name again replaces, never duplicates.
        await service.save_reservations(db, reservation(ports=(9999,)), "owner")
        assert await reserved_for(db, "127.0.0.1") == {9999}
        await service.delete_reservation(db, (await service.list_reservations(db))[0]["id"], "owner")


async def test_cannot_reserve_ports_the_panel_already_uses(world):  # noqa: F811
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await service.save_reservations(db, reservation(ports=(62050,)), "owner")
        assert error.value.status_code == 409 and "62050" in error.value.detail


def test_assert_free_names_the_port():
    assert_free({1, 2}, {3}, "x")
    with pytest.raises(HTTPException) as error:
        assert_free({1, 2}, {2, 3}, "Tunnel 'a'")
    assert error.value.status_code == 422 and "port 2" in error.value.detail


def test_allocator_skips_reserved_ports():
    node = SimpleNamespace(
        address="10.0.0.1", port=62050, api_port=62051, tunnel_api_port=62052, health_echo_port=62053
    )
    free = allocate([], [node], {})
    taken = allocate([], [node], {}, {"10.0.0.1": {free["control"], free["client"]}, "other": {free["exit"]}})
    assert taken["control"] != free["control"] and taken["client"] != free["client"]
    assert taken["exit"] == free["exit"] or taken["exit"] != free["client"]
    assert allocate([], [node], {}, {"elsewhere": {free["control"]}}) == free


async def test_gateway_refuses_a_port_an_existing_service_owns(world):  # noqa: F811
    async with world.sessions() as db:
        await service.save_reservations(db, reservation(ports=(443,), name="external-xray"), "owner")
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as error:
            await gaming.apply(db, apply_body(), "owner")
    blocker = error.value.detail["blockers"][0]
    assert blocker["code"] == "validation_failed" and "port 443" in blocker["detail"]


async def test_api_analyze_reports_conflicts_and_reserves(world):  # noqa: F811
    app = FastAPI()
    app.include_router(discovery.router)

    async def db():
        async with world.sessions() as session:
            yield session

    app.dependency_overrides[get_db] = db
    app.dependency_overrides[require_owner] = lambda: SimpleNamespace(username="owner")
    facts = iran_facts()
    facts["listeners"].append({"proto": "tcp", "bind": "0.0.0.0", "port": 62050, "pid": 9, "name": "other"})
    facts["listeners"].append({"proto": "tcp", "bind": "0.0.0.0", "port": 62052, "pid": 9, "name": "other"})
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
        bad = await client.post(
            "/api/tunnel/discovery/analyze",
            json={"address": "127.0.0.1", "facts": {"listeners": [{"proto": "icmp", "bind": "x", "port": 1}]}},
        )
        assert bad.status_code == 422
        response = await client.post("/api/tunnel/discovery/analyze", json={"address": "127.0.0.1", "facts": facts})
        assert response.status_code == 200
        body = response.json()
        assert body["node_registered"] is True
        assert any(t["kind"] == "paqet" and t["role"] == "client" for t in body["tunnels"])
        tunnel = body["tunnels"][0]
        saved = await client.post(
            "/api/tunnel/discovery/reservations",
            json={
                "address": "127.0.0.1",
                "items": [
                    {
                        "name": "paqet-client",
                        "kind": "paqet",
                        "role": "client",
                        "peer": tunnel["peer"],
                        "ports": [{"port": 9999}, {"port": 1080}],
                    }
                ],
            },
        )
        assert saved.status_code == 200 and saved.json()["items"][0]["ports"][0]["port"] == 9999
        listed = await client.get("/api/tunnel/discovery/reservations")
        identity = listed.json()["items"][0]["id"]
        assert (await client.delete(f"/api/tunnel/discovery/reservations/{identity}")).status_code == 204
        assert (await client.delete(f"/api/tunnel/discovery/reservations/{identity}")).status_code == 404
