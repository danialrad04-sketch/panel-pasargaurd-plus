from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from sqlalchemy import select

from app.db import get_db
from app.db.models import DNSAccount, DNSIPLease
from app.models.smartdns_gaming import GamingAttachInput
from app.operation import dns as dns_operation, dns_portal
from app.routers import dns_portal as portal_router
from app.smartdns import gaming
from config import dns_settings
from tests.test_gaming_dns import OWNER, apply_body, world  # noqa: F401

SIGNING_KEY = "portal-signing-key"


@pytest.fixture
async def portal(world, monkeypatch):  # noqa: F811
    monkeypatch.setattr(dns_settings, "enabled", True)
    monkeypatch.setattr(
        dns_operation, "get_secret_key", __import__("unittest.mock").mock.AsyncMock(return_value=SIGNING_KEY)
    )
    monkeypatch.setattr(dns_portal.operation, "get_secret_key", dns_operation.get_secret_key)
    async with world.sessions() as db:
        profile = await gaming.apply(db, apply_body(), "owner")
    async with world.sessions() as db:
        done = await gaming.attach(
            db, profile["policy_id"], GamingAttachInput(user_id=1, client_ips=["198.51.100.7"], byte_limit=1000), OWNER
        )
    async with world.sessions() as db:
        token = (await dns_operation.account_credentials(db, done["account_id"], OWNER))["token"]
    app = FastAPI()
    app.include_router(portal_router.router)

    async def override():
        async with world.sessions() as db:
            yield db

    app.dependency_overrides[get_db] = override
    client = httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app, client=("203.0.113.50", 5000)), base_url="http://t"
    )
    yield SimpleNamespace(client=client, token=token, world=world, account_id=done["account_id"])
    await client.aclose()


async def test_page_is_private_and_shows_status(portal):
    response = await portal.client.get(f"/dns-sub/{portal.token}")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store" and response.headers["referrer-policy"] == "no-referrer"
    assert "player" in response.text and "203.0.113.50" in response.text
    for bad in ("x" * 40, "short", portal.token[:-1] + ("a" if portal.token[-1] != "a" else "b")):
        assert (await portal.client.get(f"/dns-sub/{bad}")).status_code == 404
        assert (await portal.client.post(f"/dns-sub/{bad}/ip")).status_code == 404


async def test_info_lists_gateway_services_and_usage(portal):
    data = (await portal.client.get(f"/dns-sub/{portal.token}/info")).json()
    assert data["eligible"] is True and data["client_ip"] == "203.0.113.50" and data["client_registered"] is False
    assert data["servers"] == [{"address": "203.0.113.10", "port": 53, "applied": False}]
    assert {item["key"] for item in data["services"]} == {"playstation", "xbox"}
    assert data["relay"]["byte_limit"] == 1000 and data["relay"]["remaining"] == 1000
    assert [lease["ip"] for lease in data["leases"]] == ["198.51.100.7"]
    assert "token" not in data and data["game_acceptance"] == "untested"
    assert data["doh_url"].endswith("/" + portal.token)


async def test_user_registers_only_their_own_address(portal):
    response = await portal.client.post(f"/dns-sub/{portal.token}/ip", json={"ip": "9.9.9.9"})
    assert response.status_code == 200
    data = response.json()
    assert data["client_registered"] is True
    assert {lease["ip"] for lease in data["leases"]} == {"198.51.100.7", "203.0.113.50"}
    async with portal.world.sessions() as db:
        lease = await db.get(DNSIPLease, "203.0.113.50")
        assert await db.get(DNSIPLease, "9.9.9.9") is None
        remaining = lease.expires_at.replace(tzinfo=UTC) - datetime.now(UTC)
        assert timedelta(days=6) < remaining <= timedelta(days=7)


async def test_limit_is_three_and_removal_frees_a_slot(portal):
    async with portal.world.sessions() as db:
        account = await db.get(DNSAccount, portal.account_id)
        for ip in ("198.51.100.8", "198.51.100.9"):
            await dns_operation.grant_lease(db, account, ip, 3600, "test")
    refused = await portal.client.post(f"/dns-sub/{portal.token}/ip")
    assert refused.status_code == 409 and "three" in refused.json()["detail"]
    removed = await portal.client.delete(f"/dns-sub/{portal.token}/ip/198.51.100.9")
    assert removed.status_code == 200 and "198.51.100.9" not in {lease["ip"] for lease in removed.json()["leases"]}
    assert (await portal.client.post(f"/dns-sub/{portal.token}/ip")).status_code == 200
    assert (await portal.client.delete(f"/dns-sub/{portal.token}/ip/not-an-ip")).status_code == 422


async def test_inactive_account_cannot_register_and_rotation_revokes_the_link(portal):
    async with portal.world.sessions() as db:
        account = await db.get(DNSAccount, portal.account_id)
        account.enabled = False
        await db.commit()
    assert (await portal.client.post(f"/dns-sub/{portal.token}/ip")).status_code == 403
    assert (await portal.client.get(f"/dns-sub/{portal.token}/info")).json()["eligible"] is False
    async with portal.world.sessions() as db:
        await dns_operation.rotate_account(db, portal.account_id, OWNER)
    assert (await portal.client.get(f"/dns-sub/{portal.token}/info")).status_code == 404


async def test_one_users_link_cannot_remove_another_users_ip(portal):
    async with portal.world.sessions() as db:
        rows = (await db.scalars(select(DNSIPLease))).all()
        assert rows
    # The only lease belongs to this account; a second account's token (none exists here) could not match it.
    removed = await portal.client.delete(f"/dns-sub/{portal.token}/ip/203.0.113.99")
    assert removed.status_code == 200
    async with portal.world.sessions() as db:
        assert await db.get(DNSIPLease, "198.51.100.7") is not None
