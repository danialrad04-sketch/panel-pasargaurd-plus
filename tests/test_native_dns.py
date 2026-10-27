import asyncio
import base64
import logging
import threading
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock

import dns.flags
import dns.message
import dns.rcode
import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from sqlalchemy import event, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db.base import Base, get_db
from app.db.models import DNSAccount, DNSIPLease, DNSPolicy, DNSUsagePeriod, User, UserStatus
from app.dns.policy import DNSPolicyInput, DNSRule, parse_query, rule_for
from app.dns.privacy import DNSAccessFilter
from app.dns.service import DNSService
from app.models.admin import AdminDetails, AdminRoleData
from app.models.admin_role import DNSPermissions, RolePermissions
from app.models.dns import DNSAccountCreate, DNSLeaseInput
from app.operation import dns as operation
from app.routers import dns as api
from app.routers.authentication import get_current
from config import dns_settings

OWNER = AdminDetails(username="owner", role=AdminRoleData(is_owner=True))
QUERY = dns.message.make_query("blocked.example", "A").to_wire()


@pytest.fixture
async def native(tmp_path, monkeypatch):
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/native.db", connect_args={"timeout": 10})

    @event.listens_for(engine.sync_engine, "connect")
    def configure(connection, record):
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA journal_mode=WAL")

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    monkeypatch.setattr(dns_settings, "enabled", True)
    monkeypatch.setattr(operation, "get_secret_key", AsyncMock(return_value="test-signing-key"))
    async with sessions() as db:
        user = User(username="native-user")
        policy = DNSPolicy(
            name="native-policy", rules=[{"domain": "blocked.example", "action": "block", "addresses": []}]
        )
        db.add_all([user, policy])
        await db.commit()
        view = await operation.create_account(
            db, DNSAccountCreate(user_id=user.id, policy_id=policy.id, query_limit=3, qps=100), OWNER
        )
        account_id = view["id"]
        token = (await operation.account_credentials(db, account_id, OWNER))["token"]
    service = DNSService(session_factory=sessions)
    yield SimpleNamespace(
        sessions=sessions, service=service, user_id=user.id, policy_id=policy.id, account_id=account_id, token=token
    )
    await engine.dispose()


async def usage(native, period=1):
    async with native.sessions() as db:
        return (
            await db.scalars(
                select(DNSUsagePeriod).where(
                    DNSUsagePeriod.account_id == native.account_id, DNSUsagePeriod.period == period
                )
            )
        ).one()


async def test_native_response_and_ledgers(native):
    result = dns.message.from_wire(await native.service.answer(QUERY, token=native.token))
    assert result.rcode() == dns.rcode.NXDOMAIN
    assert result.id == dns.message.from_wire(QUERY).id
    ledger = await usage(native)
    assert ledger.queries == ledger.blocked == 1
    assert ledger.request_bytes == len(QUERY) and ledger.response_bytes > 0
    async with native.sessions() as db:
        user = await db.get(User, native.user_id)
        assert user.used_traffic == 0
        output = await operation.list_accounts(db, OWNER)
        assert output["items"][0]["username"] == "native-user"
        assert "token_hash" not in str(output) and "credential_seed" not in str(output)


async def test_concurrent_requests_cannot_overspend(native):
    async def query():
        try:
            await native.service.answer(QUERY, token=native.token)
            return 200
        except HTTPException as error:
            return error.status_code

    results = await asyncio.gather(*(query() for _ in range(12)))
    assert results.count(200) == 3
    assert results.count(403) == 9
    assert (await usage(native)).queries == 3


async def test_rate_window_is_persisted_between_service_instances(native, monkeypatch):
    monkeypatch.setattr("app.dns.service.time.time", lambda: 1000)
    async with native.sessions() as db:
        account = await db.get(DNSAccount, native.account_id)
        account.qps = 1
        await db.commit()
    await native.service.answer(QUERY, token=native.token)
    other = DNSService(session_factory=native.sessions)
    with pytest.raises(HTTPException) as caught:
        await other.answer(QUERY, token=native.token)
    assert caught.value.status_code == 429


@pytest.mark.parametrize("change", ["disabled", "expired", "limited", "on_hold", "account_disabled"])
async def test_native_user_eligibility_is_enforced_without_scheduler(native, change):
    async with native.sessions() as db:
        user = await db.get(User, native.user_id)
        if change == "expired":
            user.expire = datetime.now(UTC) - timedelta(seconds=1)
        elif change == "limited":
            user.data_limit = 1
            user.used_traffic = 1
        elif change == "account_disabled":
            (await db.get(DNSAccount, native.account_id)).enabled = False
        else:
            user.status = UserStatus(change)
        await db.commit()
    with pytest.raises(HTTPException) as caught:
        await native.service.answer(QUERY, token=native.token)
    assert caught.value.status_code == 403
    assert (await usage(native)).queries == 0


async def test_reset_preserves_inflight_response_period(native):
    entered, release = threading.Event(), threading.Event()

    def resolve(query, policy):
        entered.set()
        release.wait(5)
        return dns.message.make_response(query), False

    native.service.resolver.resolve = resolve
    task = asyncio.create_task(native.service.answer(QUERY, token=native.token))
    try:
        assert await asyncio.to_thread(entered.wait, 3)
        async with native.sessions() as db:
            await operation.reset_account(db, native.account_id, OWNER)
    finally:
        release.set()
    await task
    assert (await usage(native, 1)).queries == 1
    assert (await usage(native, 1)).response_bytes > 0
    assert (await usage(native, 2)).queries == (await usage(native, 2)).response_bytes == 0


async def test_rotation_revokes_token_and_source_leases(native):
    async with native.sessions() as db:
        await operation.create_lease(db, native.account_id, DNSLeaseInput(ip="192.0.2.10"), OWNER)
    await native.service.answer(QUERY, source_ip="::ffff:192.0.2.10")
    async with native.sessions() as db:
        new_token = (await operation.rotate_account(db, native.account_id, OWNER))["token"]
    for kwargs in [{"token": native.token}, {"source_ip": "192.0.2.10"}]:
        with pytest.raises(HTTPException) as error:
            await native.service.answer(QUERY, **kwargs)
        assert error.value.status_code == 401
    assert await native.service.answer(QUERY, token=new_token)


async def test_user_delete_cascades_service_state(native):
    async with native.sessions() as db:
        user = await db.get(User, native.user_id)
        await db.delete(user)
        await db.commit()
        assert await db.scalar(select(func.count()).select_from(DNSAccount)) == 0
        assert await db.scalar(select(func.count()).select_from(DNSUsagePeriod)) == 0


async def test_dns_scope_does_not_grant_cross_owner_access(native):
    limited = AdminDetails(
        id=42,
        username="delegated",
        role=AdminRoleData(permissions=RolePermissions(dns=DNSPermissions(read={"scope": 1}, update={"scope": 1}))),
    )
    async with native.sessions() as db:
        assert (await operation.list_accounts(db, limited))["total"] == 0
        with pytest.raises(HTTPException) as error:
            await operation.account_by_id(db, native.account_id, limited, "update")
        assert error.value.status_code == 403


async def client_for(native, monkeypatch, admin=OWNER):
    app = FastAPI()
    app.include_router(api.router)

    async def database():
        async with native.sessions() as db:
            yield db

    app.dependency_overrides[get_db] = database
    app.dependency_overrides[get_current] = lambda: admin
    monkeypatch.setattr(api, "service", native.service)
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://native")


async def test_real_doh_get_post_and_input_boundaries(native, monkeypatch):
    async with await client_for(native, monkeypatch) as client:
        encoded = base64.urlsafe_b64encode(QUERY).decode().rstrip("=")
        get = await client.get(f"/dns-query/{native.token}", params={"dns": encoded})
        post = await client.post(
            "/dns-query",
            content=QUERY,
            headers={"Authorization": f"Bearer {native.token}", "Content-Type": "application/dns-message"},
        )
        for response in [get, post]:
            assert response.status_code == 200
            assert response.headers["cache-control"] == "no-store"
            assert dns.message.from_wire(response.content).rcode() == dns.rcode.NXDOMAIN
        assert (await client.post("/dns-query", content=QUERY)).status_code == 401
        assert (await client.post(f"/dns-query/{native.token}", content=QUERY)).status_code == 415
        assert (
            await client.post(
                f"/dns-query/{native.token}", content=b"x" * 4097, headers={"Content-Type": "application/dns-message"}
            )
        ).status_code == 413
        assert (await client.get(f"/dns-query/{native.token}?dns=!!!!")).status_code == 400


async def test_policy_revision_and_delete_assignment(native, monkeypatch):
    async with await client_for(native, monkeypatch) as client:
        path = f"/api/dns/policies/{native.policy_id}"
        body = {"name": "native-policy", "rules": [], "expected_revision": 1}
        assert (await client.put(path, json=body)).status_code == 200
        assert (await client.put(path, json=body)).status_code == 409
        assert (await client.delete(path)).status_code == 409
        response = await client.post(f"/api/dns/accounts/{native.account_id}/credentials")
        assert response.json()["token"] == native.token
        assert response.headers["cache-control"] == "no-store"
        audit = (await client.get("/api/dns/audit")).json()
        assert audit and native.token not in str(audit)


async def test_dns_permission_missing_and_policy_owner_gate(native, monkeypatch):
    admin = AdminDetails(
        username="viewer", role=AdminRoleData(permissions=RolePermissions(dns=DNSPermissions(read=True)))
    )
    async with await client_for(native, monkeypatch, admin) as client:
        assert (await client.get("/api/dns/accounts")).status_code == 200
        assert (await client.post("/api/dns/policies", json={"name": "unauthorized", "rules": []})).status_code == 403
        assert (await client.post(f"/api/dns/accounts/{native.account_id}/reset")).status_code == 403


@pytest.mark.parametrize(
    "wire",
    [
        b"",
        b"x" * 4097,
        dns.message.make_query("example.com", "ANY").to_wire(),
        dns.message.make_response(dns.message.make_query("example.com", "A")).to_wire(),
    ],
)
def test_dns_parser_limits(wire):
    with pytest.raises(ValueError):
        parse_query(wire)


@pytest.mark.parametrize("domain", ["*.example.com", "bad..example.com", "https://example.com", "-bad.example.com"])
def test_invalid_policy_domain(domain):
    with pytest.raises(ValueError):
        DNSRule(domain=domain, action="allow")


def test_policy_boundary_and_duplicate_rules():
    assert rule_for(dns.name.from_text("notexample.com"), [{"domain": "example.com"}]) is None
    assert rule_for(dns.name.from_text("a.example.com"), [{"domain": "example.com"}])
    with pytest.raises(ValueError):
        DNSPolicyInput(name="dup", rules=[DNSRule(domain="example.com", action="block")] * 2)


def test_access_logger_does_not_expose_dns_url_credential():
    record = logging.LogRecord("uvicorn.access", 20, "", 1, "%s %s", ("GET", "/dns-query/pgdns_1_secret?dns=abc"), None)
    DNSAccessFilter().filter(record)
    assert "pgdns_" not in record.getMessage() and "[redacted]" in record.getMessage()


async def test_real_native_udp_tcp_and_dot_transports(native, tmp_path):
    import ipaddress
    import socket
    import ssl

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    from app.dns.worker import TCPServer, UDPServer

    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(lambda: UDPServer(native.service), local_addr=("127.0.0.1", 0))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    address = transport.get_extra_info("sockname")
    try:
        await loop.sock_sendto(sock, QUERY, address)
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(loop.sock_recvfrom(sock, 4096), 0.1)
        async with native.sessions() as db:
            await operation.create_lease(db, native.account_id, DNSLeaseInput(ip="127.0.0.1"), OWNER)
        await loop.sock_sendto(sock, QUERY, address)
        wire, _ = await asyncio.wait_for(loop.sock_recvfrom(sock, 4096), 3)
        assert dns.message.from_wire(wire).rcode() == dns.rcode.NXDOMAIN
    finally:
        transport.close()
        sock.close()
    handler = TCPServer(native.service)
    plain = await asyncio.start_server(handler.handle, "127.0.0.1", 0)
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.now(UTC) - timedelta(minutes=1))
        .not_valid_after(datetime.now(UTC) + timedelta(days=1))
        .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
        .sign(key, hashes.SHA256())
    )
    certfile, keyfile = tmp_path / "cert.pem", tmp_path / "key.pem"
    certfile.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    keyfile.write_bytes(
        key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    )
    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.load_cert_chain(certfile, keyfile)
    secure = await asyncio.start_server(handler.handle, "127.0.0.1", 0, ssl=server_context)
    try:
        for server, context in [(plain, None), (secure, ssl.create_default_context(cafile=str(certfile)))]:
            reader, writer = await asyncio.open_connection("127.0.0.1", server.sockets[0].getsockname()[1], ssl=context)
            writer.write(len(QUERY).to_bytes(2, "big") + QUERY)
            await writer.drain()
            size = int.from_bytes(await asyncio.wait_for(reader.readexactly(2), 3), "big")
            assert dns.message.from_wire(await reader.readexactly(size)).rcode() == dns.rcode.NXDOMAIN
            writer.close()
            await writer.wait_closed()
    finally:
        plain.close()
        secure.close()
        await plain.wait_closed()
        await secure.wait_closed()
    assert (await usage(native)).queries == 3


async def test_expired_ip_lease_and_disabled_service(native, monkeypatch):
    async with native.sessions() as db:
        db.add(
            DNSIPLease(
                ip="192.0.2.5", account_id=native.account_id, expires_at=datetime.now(UTC) - timedelta(seconds=1)
            )
        )
        await db.commit()
    with pytest.raises(HTTPException) as caught:
        await native.service.answer(QUERY, source_ip="192.0.2.5")
    assert caught.value.status_code == 401
    monkeypatch.setattr(dns_settings, "enabled", False)
    with pytest.raises(HTTPException) as caught:
        await native.service.answer(QUERY, token=native.token)
    assert caught.value.status_code == 503


async def test_native_catalog_persists_real_policy(native, monkeypatch):
    async with await client_for(native, monkeypatch) as client:
        catalog = (await client.get("/api/dns/catalog")).json()
        assert catalog["counts"] == {"services": 42, "groups": 48, "domains": 505}
        assert catalog["relay_available"] is False
        result = await client.post(
            "/api/dns/policies/from-catalog",
            json={
                "name": "PlayStation relay",
                "groups": [{"service": "playstation", "group": "online"}],
                "relay_addresses": ["192.0.2.10"],
            },
        )
        assert result.status_code == 201
        assert result.json()["rules"][0]["action"] == "rewrite"
        assert all("192.0.2.10" in rule["addresses"] for rule in result.json()["rules"])


@pytest.mark.parametrize("bulk", [False, True])
async def test_existing_pasarguard_delete_paths_clean_dns_without_sqlite_foreign_keys(native, bulk):
    from sqlalchemy import text

    from app.db.crud.user import remove_user, remove_users

    async with native.sessions() as db:
        await operation.create_lease(db, native.account_id, DNSLeaseInput(ip="192.0.2.8"), OWNER)
        await db.execute(text("PRAGMA foreign_keys=OFF"))
        user = await db.get(User, native.user_id)
        if bulk:
            await remove_users(db, [user])
        else:
            await remove_user(db, user)
        assert await db.scalar(select(func.count()).select_from(DNSAccount)) == 0
        assert await db.scalar(select(func.count()).select_from(DNSUsagePeriod)) == 0
        assert await db.scalar(select(func.count()).select_from(DNSIPLease)) == 0


async def test_native_subscription_dns_metadata_uses_existing_subscription_identity(native, monkeypatch):
    from app.routers.subscription import router, subscription_operator

    app = FastAPI()
    app.include_router(router)

    async def database():
        async with native.sessions() as db:
            yield db

    app.dependency_overrides[get_db] = database
    validate = AsyncMock(return_value=SimpleNamespace(id=native.user_id))
    monkeypatch.setattr(subscription_operator, "get_validated_sub", validate)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        result = await client.get(router.prefix + "/test-subscription/dns")
    assert result.status_code == 200
    assert result.headers["cache-control"] == "no-store"
    assert result.json()["token"] == native.token
    validate.assert_awaited_once()


@pytest.mark.parametrize("path", ["/dns-query?dns=private-wire", "/dns-query/pgdns_1_private?dns=private-wire"])
def test_access_filter_hides_wire_questions_and_credentials(path):
    record = logging.LogRecord("uvicorn.access", 20, "", 1, "%s", (path,), None)
    DNSAccessFilter().filter(record)
    assert "private" not in record.getMessage()


async def test_upstream_failure_consumes_reserved_query_and_records_error(native):
    import dns.exception

    def fail(query, policy):
        raise dns.exception.Timeout()

    native.service.resolver.resolve = fail
    result = dns.message.from_wire(await native.service.answer(QUERY, token=native.token))
    assert result.rcode() == dns.rcode.SERVFAIL
    assert not result.flags & dns.flags.AD
    period = await usage(native)
    assert period.queries == period.errors == 1


async def test_public_doh_endpoint_is_used_in_native_subscription(native, monkeypatch):
    monkeypatch.setattr(dns_settings, "public_url", "https://dns.example.com/private-doh")
    async with native.sessions() as db:
        result = await operation.portal_metadata(db, native.user_id)
    assert result["doh_url"] == "https://dns.example.com/private-doh/" + native.token


@pytest.mark.parametrize(
    "url",
    [
        "http://dns.example.com/dns-query",
        "https://user:password@dns.example.com/dns-query",
        "https://dns.example.com/dns-query?secret=value",
        "https://dns.example.com/dns-query#fragment",
    ],
)
def test_public_doh_endpoint_rejects_insecure_or_ambiguous_urls(url):
    from config import DNSSettings

    with pytest.raises(ValueError):
        DNSSettings(DNS_PUBLIC_URL=url, _env_file=None)
