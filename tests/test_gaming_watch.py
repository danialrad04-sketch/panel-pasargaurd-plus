import pytest
from fastapi import HTTPException
from sqlalchemy import select

from app.db.models import DNSPolicy, SmartDNSRoute
from app.dns import watch
from app.smartdns import gaming
from tests.test_gaming_dns import apply_body, world  # noqa: F401


@pytest.fixture(autouse=True)
def clean():
    watch.clear()
    yield
    watch.clear()


def test_watch_is_bounded_and_merges_repeats():
    for _ in range(3):
        watch.record(1, "a.example.com", "A", "direct")
    watch.record(1, "a.example.com", "AAAA", "via_gateway")
    rows = watch.recent(1)
    assert {(row["name"], row["outcome"]): row["count"] for row in rows} == {
        ("a.example.com", "direct"): 3,
        ("a.example.com", "via_gateway"): 1,
    }
    for index in range(watch.PER_ACCOUNT + 50):
        watch.record(2, f"n{index}.example.com", "A", "direct")
    assert len(watch.recent(2, limit=10_000)) == watch.PER_ACCOUNT
    for account in range(watch.MAX_ACCOUNTS + 20):
        watch.record(1000 + account, "x.example.com", "A", "direct")
    assert watch.recent(1) == []  # evicted, never grows without bound


def test_labels():
    assert watch.label({"action": "rewrite"}) == "via_gateway"
    assert watch.label({"action": "block"}) == "blocked"
    assert watch.label({"action": "unavailable"}) == "pending"
    assert watch.label({"action": "allow"}) == "direct" and watch.label(None) == "direct"


async def test_add_domains_extends_policy_routes_and_deploys(world):  # noqa: F811
    async with world.sessions() as db:
        profile = await gaming.apply(db, apply_body(), "owner")
    async with world.sessions() as db:
        before = len((await db.scalars(select(SmartDNSRoute))).all())
        done = await gaming.add_domains(
            db, profile["policy_id"], ["Match.Example-Game.com", "cdn.example-game.com", "match.example-game.com"], "owner"
        )
    assert done["added"] == ["cdn.example-game.com", "match.example-game.com"]
    assert len(done["route_ids"]) == 2 and done["revision"] > profile["revision"]
    async with world.sessions() as db:
        assert len((await db.scalars(select(SmartDNSRoute))).all()) == before + 2
        policy = await db.get(DNSPolicy, profile["policy_id"])
        rule = next(rule for rule in policy.rules if rule["domain"] == "match.example-game.com")
        assert rule["action"] == "rewrite" and rule["addresses"] == [apply_body().public_address]
    # Repeating is a no-op, a subdomain of an existing route reuses it.
    async with world.sessions() as db:
        again = await gaming.add_domains(db, profile["policy_id"], ["match.example-game.com"], "owner")
    assert again["added"] == [] and again["already"] == ["match.example-game.com"]
    async with world.sessions() as db:
        sub = await gaming.add_domains(db, profile["policy_id"], ["eu.match.example-game.com"], "owner")
    assert sub["added"] == ["eu.match.example-game.com"] and sub["route_ids"] == []


async def test_add_domains_rejects_wildcards_non_gaming_and_blocked(world):  # noqa: F811
    async with world.sessions() as db:
        profile = await gaming.apply(db, apply_body(), "owner")
        plain = DNSPolicy(name="plain", rules=[{"domain": "bad.example.com", "action": "block", "addresses": []}])
        db.add(plain)
        await db.commit()
        plain_id = plain.id
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as wildcard:
            await gaming.add_domains(db, profile["policy_id"], ["*.example.com"], "owner")
    assert wildcard.value.status_code == 422
    async with world.sessions() as db:
        with pytest.raises(HTTPException) as plain_error:
            await gaming.add_domains(db, plain_id, ["a.example.com"], "owner")
    assert plain_error.value.status_code == 409
