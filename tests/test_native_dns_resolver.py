import asyncio

import dns.flags
import dns.message
import dns.rcode
import dns.rrset
import pytest

from app.dns.policy import DNSResolver, serialize


class LocalAuthority(asyncio.DatagramProtocol):
    def __init__(self, *, truncated=False, alias=False):
        self.truncated, self.alias, self.requests, self.tcp_requests = truncated, alias, 0, 0

    def connection_made(self, transport):
        self.transport = transport

    def response(self, query):
        result = dns.message.make_response(query)
        result.flags |= dns.flags.AD | dns.flags.RA
        if self.alias:
            result.answer.append(dns.rrset.from_text(query.question[0].name, 120, "IN", "CNAME", "blocked.example."))
        else:
            result.answer.append(dns.rrset.from_text(query.question[0].name, 120, "IN", "A", "192.0.2.10"))
        return result

    def datagram_received(self, data, address):
        self.requests += 1
        query = dns.message.from_wire(data)
        if self.truncated:
            response = dns.message.make_response(query)
            response.flags |= dns.flags.TC
        else:
            response = self.response(query)
        self.transport.sendto(response.to_wire(), address)

    async def tcp(self, reader, writer):
        self.tcp_requests += 1
        try:
            length = int.from_bytes(await reader.readexactly(2), "big")
            query = dns.message.from_wire(await reader.readexactly(length))
            response = self.response(query).to_wire()
            writer.write(len(response).to_bytes(2, "big") + response)
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()


@pytest.mark.parametrize("truncated", [False, True])
async def test_real_upstream_forward_tcp_fallback_cache_and_policy_isolation(truncated):
    loop = asyncio.get_running_loop()
    authority = LocalAuthority(truncated=truncated)
    transport, _ = await loop.create_datagram_endpoint(lambda: authority, local_addr=("127.0.0.1", 0))
    port = transport.get_extra_info("sockname")[1]
    server = await asyncio.start_server(authority.tcp, "127.0.0.1", port)
    try:
        resolver = DNSResolver(upstream="127.0.0.1", port=port)
        policy = {"id": 1, "revision": 1, "rules": []}
        query = dns.message.make_query("sample.example", "A", want_dnssec=True)
        result, blocked = await asyncio.to_thread(resolver.resolve, query, policy)
        assert not blocked and result.flags & dns.flags.AD
        assert result.answer[0][0].address == "192.0.2.10"
        query.id = 123
        cached, _ = await asyncio.to_thread(resolver.resolve, query, policy)
        assert dns.message.from_wire(serialize(cached, query)).id == 123
        assert authority.requests == 1 and authority.tcp_requests == int(truncated)
        await asyncio.to_thread(resolver.resolve, query, {"id": 2, "revision": 1, "rules": []})
        await asyncio.to_thread(resolver.resolve, query, {"id": 1, "revision": 2, "rules": []})
        assert authority.requests == 3
        blocked_policy = {
            "id": 1,
            "revision": 3,
            "rules": [{"domain": "sample.example", "action": "block", "addresses": []}],
        }
        response, blocked = await asyncio.to_thread(resolver.resolve, query, blocked_policy)
        assert blocked and response.rcode() == dns.rcode.NXDOMAIN
        assert not response.flags & dns.flags.AD
    finally:
        transport.close()
        server.close()
        await server.wait_closed()


async def test_alias_cannot_bypass_block_policy():
    loop = asyncio.get_running_loop()
    authority = LocalAuthority(alias=True)
    transport, _ = await loop.create_datagram_endpoint(lambda: authority, local_addr=("127.0.0.1", 0))
    try:
        resolver = DNSResolver(upstream="127.0.0.1", port=transport.get_extra_info("sockname")[1])
        query = dns.message.make_query("allowed.example", "A")
        response, blocked = await asyncio.to_thread(
            resolver.resolve,
            query,
            {"id": 1, "revision": 1, "rules": [{"domain": "blocked.example", "action": "block", "addresses": []}]},
        )
        assert blocked and response.rcode() == dns.rcode.NXDOMAIN
        assert response.question == query.question
    finally:
        transport.close()
