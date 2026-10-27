# ruff: noqa: F811
"""Concurrent strict byte reservations, revocation and independent usage periods."""

import asyncio
import time
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import dns.message
import dns.rcode
import pytest
from fastapi import HTTPException

from app.db.models import DNSAccount, DNSIPLease, Node, SmartDNSGrant, SmartDNSNodeState, SmartDNSRoute
from app.models.smartdns import GrantInput, SettleInput
from app.operation import dns as accounts
from app.smartdns import service
from tests.test_native_dns import OWNER, native, usage  # noqa: F401


@pytest.fixture
async def gateway(native):
    async with native.sessions() as db:
        node = Node(
            core_config_id=None,
            name="Gateway",
            address="192.0.2.1",
            port=62050,
            api_port=62051,
            server_ca="fixture",
            api_key=str(uuid4()),
        )
        db.add(node)
        await db.flush()
        route = SmartDNSRoute(
            node_id=node.id,
            policy_id=native.policy_id,
            domain="game.example",
            public_address="192.0.2.2",
            bind_address="0.0.0.0",
            tcp_ports=[443],
            udp_ports=[3074],
            mode="sniff",
            enabled=True,
        )
        db.add(route)
        db.add(SmartDNSNodeState(node_id=node.id, revision=2, applied_revision=2))
        account = await db.get(DNSAccount, native.account_id)
        account.relay_enabled = True
        account.relay_byte_limit = 1000
        db.add(DNSIPLease(ip="198.51.100.10", account_id=account.id, expires_at=datetime.now(UTC) + timedelta(hours=1)))
        await db.commit()
    return native, node, route


def grant(route, **values):
    return GrantInput(
        request_id=uuid4(),
        issued_at=int(time.time()),
        route_id=route.id,
        revision=route.revision,
        source_ip="198.51.100.10",
        host="game.example",
        port=443,
        network="tcp",
        amount=600,
        **values,
    )


async def test_simultaneous_nodes_or_connections_cannot_overspend(gateway):
    native, node, route = gateway

    async def reserve():
        async with native.sessions() as db:
            try:
                return await service.reserve(db, node.id, node.api_key, grant(route))
            except HTTPException as error:
                return error.status_code

    results = await asyncio.gather(*(reserve() for _ in range(12)))
    accepted = [value for value in results if isinstance(value, dict)]
    assert sum(value["amount"] for value in accepted) == 1000
    assert len(accepted) == 2 and results.count(403) == 10
    assert (await usage(native)).relay_reserved == 1000


async def test_idempotent_settlement_releases_only_unused_bytes(gateway):
    native, node, route = gateway
    body = grant(route)
    async with native.sessions() as db:
        value = await service.reserve(db, node.id, node.api_key, body)
        again = await service.reserve(db, node.id, node.api_key, body)
        assert again == value
    async with native.sessions() as db:
        settle = SettleInput(grant_id=value["grant_id"], used=123)
        assert await service.settle(db, node.id, node.api_key, settle) == {"settled": True}
        assert await service.settle(db, node.id, node.api_key, settle) == {"settled": True}
        with pytest.raises(HTTPException):
            await service.settle(db, node.id, node.api_key, SettleInput(grant_id=value["grant_id"], used=0))
    totals = await usage(native)
    assert totals.relay_bytes == 123 and totals.relay_reserved == 0


async def test_revocation_and_unapplied_revision_fail_closed(gateway):
    native, node, route = gateway
    async with native.sessions() as db:
        with pytest.raises(HTTPException) as invalid:
            await service.reserve(db, node.id, "wrong-node-key", grant(route))
        assert invalid.value.status_code == 401
        account = await db.get(DNSAccount, native.account_id)
        account.relay_enabled = False
        await db.commit()
    async with native.sessions() as db:
        with pytest.raises(HTTPException):
            await service.reserve(db, node.id, node.api_key, grant(route))
        account = await db.get(DNSAccount, native.account_id)
        account.relay_enabled = True
        state = await db.get(SmartDNSNodeState, node.id)
        state.revision += 1
        await db.commit()
    async with native.sessions() as db:
        with pytest.raises(HTTPException):
            await service.reserve(db, node.id, node.api_key, grant(route))
    assert (await usage(native)).relay_reserved == 0


async def test_reset_settles_into_original_period(gateway):
    native, node, route = gateway
    async with native.sessions() as db:
        value = await service.reserve(db, node.id, node.api_key, grant(route))
    async with native.sessions() as db:
        await accounts.reset_account(db, native.account_id, OWNER)
    async with native.sessions() as db:
        await service.settle(db, node.id, node.api_key, SettleInput(grant_id=value["grant_id"], used=99))
    assert (await usage(native, 1)).relay_bytes == 99
    assert (await usage(native, 2)).relay_bytes == 0


async def test_crash_reservation_remains_bounded_and_late_settlement_corrects(gateway):
    native, node, route = gateway
    async with native.sessions() as db:
        value = await service.reserve(db, node.id, node.api_key, grant(route))
    async with native.sessions() as db:
        row = await db.get(SmartDNSGrant, value["grant_id"])
        row.expires_at = datetime.now(UTC) - timedelta(minutes=1)
        await db.commit()
    async with native.sessions() as db:
        await service.reconcile(db, native.account_id)
        await db.commit()
    ledger = await usage(native)
    assert ledger.relay_bytes == 600 and ledger.relay_uncertain_bytes == 600 and ledger.relay_reserved == 0
    async with native.sessions() as db:
        await service.settle(db, node.id, node.api_key, SettleInput(grant_id=value["grant_id"], used=10))
    ledger = await usage(native)
    assert ledger.relay_bytes == 10 and ledger.relay_uncertain_bytes == 0


async def test_native_dns_publishes_only_acknowledged_gateway(gateway):
    native, node, _route = gateway
    wire = dns.message.make_query("game.example", "A").to_wire()
    answer = dns.message.from_wire(await native.service.answer(wire, token=native.token))
    assert answer.answer[0][0].address == "192.0.2.2"
    https = dns.message.make_query("game.example", "HTTPS").to_wire()
    assert not dns.message.from_wire(await native.service.answer(https, token=native.token)).answer
    async with native.sessions() as db:
        state = await db.get(SmartDNSNodeState, node.id)
        state.revision += 1
        await db.commit()
    response = dns.message.from_wire(await native.service.answer(wire, token=native.token))
    assert response.rcode() == dns.rcode.SERVFAIL


async def test_node_dns_authorization_source_ip_budget_and_generation(gateway, monkeypatch):
    import base64

    from app.models.smartdns import NodeDNSQuery
    from app.routers.smartdns import resolve_on_behalf_of_node

    native, node, _ = gateway
    monkeypatch.setattr("app.dns.service.DNSService", lambda: native.service)
    async with native.sessions() as db:
        state = await db.get(SmartDNSNodeState, node.id)
        state.dns_config = {"enabled": True, "bind_address": "0.0.0.0", "port": 5354}
        await db.commit()
    query = dns.message.make_query("blocked.example", "A")
    body = NodeDNSQuery(
        revision=2, source_ip="198.51.100.10", udp=True, wire=base64.b64encode(query.to_wire()).decode()
    )
    async with native.sessions() as db:
        with pytest.raises(HTTPException) as denied:
            await resolve_on_behalf_of_node(node.id, body, db, "wrong-key")
        assert denied.value.status_code == 401
        unknown = body.model_copy(update={"source_ip": "198.51.100.11"})
        with pytest.raises(HTTPException):
            await resolve_on_behalf_of_node(node.id, unknown, db, node.api_key)
    for udp in (True, False, True):
        async with native.sessions() as db:
            answer = await resolve_on_behalf_of_node(node.id, body.model_copy(update={"udp": udp}), db, node.api_key)
            assert dns.message.from_wire(base64.b64decode(answer["wire"])).rcode() == dns.rcode.NXDOMAIN
    assert (await usage(native)).queries == 3
    async with native.sessions() as db:
        with pytest.raises(HTTPException) as exhausted:
            await resolve_on_behalf_of_node(node.id, body, db, node.api_key)
        assert exhausted.value.status_code == 403
        state = await db.get(SmartDNSNodeState, node.id)
        state.revision += 1
        await db.commit()
        with pytest.raises(HTTPException) as pending:
            await resolve_on_behalf_of_node(node.id, body, db, node.api_key)
        assert "pending" in pending.value.detail
