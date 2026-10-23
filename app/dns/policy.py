"""Adapted from Donino Panel (AGPL-3.0); native PasarGuard DNS policy above a fixed, local validating resolver; no custom recursion."""

import collections
import ipaddress
import threading
import time

import dns.exception
import dns.flags
import dns.message
import dns.name
import dns.opcode
import dns.query
import dns.rcode
import dns.rdataclass
import dns.rdatatype
import dns.rrset
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from config import dns_settings


class DNSRule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    domain: str = Field(min_length=1, max_length=253)
    action: str = Field(pattern=r"^(allow|block|rewrite)$")
    addresses: list[str] = Field(default_factory=list, max_length=8)

    @field_validator("domain")
    @classmethod
    def normalized_domain(cls, value):
        try:
            value = value.rstrip(".").encode("idna").decode("ascii").lower()
            labels = value.split(".")
            if len(labels) < 2 or any(
                not label
                or len(label) > 63
                or label.startswith("-")
                or label.endswith("-")
                or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in label)
                for label in labels
            ):
                raise ValueError("Use an explicit domain suffix without wildcards")
            dns.name.from_text(value)
            return value
        except (UnicodeError, dns.exception.DNSException) as exc:
            raise ValueError("Invalid domain") from exc

    @field_validator("addresses")
    @classmethod
    def valid_addresses(cls, values):
        result = []
        for value in values:
            if "%" in value:
                raise ValueError("Scoped addresses are invalid DNS records")
            ip = ipaddress.ip_address(value)
            if ip.is_unspecified or ip.is_multicast or ip.is_link_local:
                raise ValueError("Unspecified, multicast and link-local rewrites are refused")
            result.append(str(ip))
        return sorted(set(result))

    @model_validator(mode="after")
    def address_action(self):
        if (self.action == "rewrite") != bool(self.addresses):
            raise ValueError("Only rewrite rules require addresses")
        return self


class DNSPolicyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    rules: list[DNSRule] = Field(default_factory=list, max_length=2000)

    @field_validator("name", mode="before")
    @classmethod
    def strip_name(cls, value):
        return value.strip() if isinstance(value, str) else value

    @model_validator(mode="after")
    def unique_rules(self):
        if len({r.domain for r in self.rules}) != len(self.rules):
            raise ValueError("Duplicate domain suffix")
        return self


def parse_query(wire):
    if not 12 <= len(wire) <= 4096:
        raise ValueError("DNS query size must be between 12 and 4096 bytes")
    try:
        query = dns.message.from_wire(wire, keyring=False)
    except dns.exception.DNSException as exc:
        raise ValueError("Malformed DNS message") from exc
    if (
        query.flags & dns.flags.QR
        or query.opcode() != dns.opcode.QUERY
        or len(query.question) != 1
        or query.answer
        or query.authority
        or query.additional
        or query.had_tsig
        or query.edns > 0
    ):
        raise ValueError("Only one ordinary DNS question is accepted")
    question = query.question[0]
    if question.rdclass != dns.rdataclass.IN or question.rdtype in (
        dns.rdatatype.ANY,
        dns.rdatatype.AXFR,
        dns.rdatatype.IXFR,
    ):
        raise ValueError("Unsupported DNS query class or transfer")
    return query


def rule_for(name, rules):
    name = name.to_text().rstrip(".").lower()
    matches = [r for r in rules if name == r["domain"] or name.endswith("." + r["domain"])]
    return max(matches, key=lambda r: len(r["domain"])) if matches else None


def synthetic(query, rule):
    response = dns.message.make_response(query)
    response.flags |= dns.flags.RA
    response.flags &= ~dns.flags.AD
    question = query.question[0]
    if rule["action"] == "unavailable":
        response.set_rcode(dns.rcode.SERVFAIL)
    elif rule["action"] == "block":
        response.set_rcode(dns.rcode.NXDOMAIN)
    else:
        addresses = [
            ip
            for ip in rule["addresses"]
            if (ipaddress.ip_address(ip).version == 4 and question.rdtype == dns.rdatatype.A)
            or (ipaddress.ip_address(ip).version == 6 and question.rdtype == dns.rdatatype.AAAA)
        ]
        if addresses:
            response.answer.append(
                dns.rrset.from_text(question.name, rule.get("ttl", 60), "IN", question.rdtype, *addresses)
            )
    if not response.answer:
        response.authority.append(
            dns.rrset.from_text(
                question.name,
                60,
                "IN",
                "SOA",
                "ns.invalid. hostmaster.invalid. 1 60 60 60 60",
            )
        )
    return response


def serialize(response, query, *, udp=False):
    # Wire round-trip also avoids deepcopy of dnspython's dynamic IntEnums on
    # Python 3.11 (for example an EDNS payload represented as CLASS8192).
    response = dns.message.from_wire(response.to_wire(max_size=65535))
    response.id = query.id
    response.question = list(query.question)
    # Client negotiation is never inherited from another cached request.
    response.flags = (response.flags & ~(dns.flags.RD | dns.flags.CD)) | (query.flags & (dns.flags.RD | dns.flags.CD))
    if query.edns >= 0:
        response.use_edns(
            edns=0,
            ednsflags=query.ednsflags & dns.flags.DO,
            payload=min(1232, max(512, query.payload)),
        )
    else:
        response.use_edns(edns=False)
    maximum = min(1232, max(512, query.payload)) if udp and query.edns >= 0 else 512 if udp else 65535
    return response.to_wire(max_size=maximum, prefer_truncation=True)


class DNSResolver:
    def __init__(self, *, upstream=None, port=None, timeout=2, capacity=1024):
        self.upstream = upstream or dns_settings.upstream
        # A local Unbound instance is the trust boundary for DNSSEC/AD.
        if not ipaddress.ip_address(self.upstream).is_loopback:
            raise ValueError("DNS upstream must be a local validating resolver")
        self.port = int(port or dns_settings.upstream_port)
        if not 1 <= self.port <= 65535:
            raise ValueError("Invalid local resolver port")
        self.timeout, self.capacity = timeout, capacity
        self.cache = collections.OrderedDict()
        self.lock = threading.Lock()

    def resolve(self, query, policy):
        question = query.question[0]
        rule = rule_for(question.name, policy["rules"])
        if rule and rule["action"] != "allow":
            return synthetic(query, rule), rule["action"] == "block"
        key = (
            policy["id"],
            policy["revision"],
            question.name.canonicalize(),
            question.rdtype,
            question.rdclass,
            bool(query.flags & dns.flags.RD),
            bool(query.flags & dns.flags.CD),
            bool(query.ednsflags & dns.flags.DO),
        )
        now = time.monotonic()
        with self.lock:
            cached = self.cache.get(key)
            if cached:
                created, ttl, wire = cached
                if now - created < ttl:
                    self.cache.move_to_end(key)
                    response = dns.message.from_wire(wire)
                    for rrset in [
                        *response.answer,
                        *response.authority,
                        *response.additional,
                    ]:
                        rrset.ttl = max(0, rrset.ttl - int(now - created))
                    return response, False
                del self.cache[key]
        # Strip client-supplied options/cookies. Only DNSSEC/CD affect upstream.
        outgoing = dns.message.make_query(
            question.name,
            question.rdtype,
            use_edns=0,
            want_dnssec=bool(query.ednsflags & dns.flags.DO),
            payload=1232,
        )
        outgoing.flags |= query.flags & dns.flags.CD
        if not query.flags & dns.flags.RD:
            outgoing.flags &= ~dns.flags.RD
        response = dns.query.udp(
            outgoing,
            self.upstream,
            port=self.port,
            timeout=self.timeout,
            raise_on_truncation=False,
        )
        if response.flags & dns.flags.TC:
            response = dns.query.tcp(outgoing, self.upstream, port=self.port, timeout=self.timeout)
        # Policy also applies to aliases returned by the validating resolver.
        for rrset in [*response.answer, *response.authority, *response.additional]:
            names = [rrset.name]
            for record in rrset:
                for attribute in ("target", "exchange"):
                    target = getattr(record, attribute, None)
                    if isinstance(target, dns.name.Name):
                        names.append(target)
            for name in names:
                matched = rule_for(name, policy["rules"])
                if matched and matched["action"] != "allow":
                    return synthetic(query, matched), matched["action"] == "block"
        ttl_values = [rr.ttl for rr in [*response.answer, *response.authority, *response.additional]]
        if not response.answer:
            ttl_values.extend(r.minimum for rr in response.authority if rr.rdtype == dns.rdatatype.SOA for r in rr)
        ttl = min([300, *ttl_values]) if ttl_values else 0
        if ttl > 0 and response.rcode() in (dns.rcode.NOERROR, dns.rcode.NXDOMAIN):
            with self.lock:
                self.cache[key] = (time.monotonic(), ttl, response.to_wire())
                self.cache.move_to_end(key)
                while len(self.cache) > self.capacity:
                    self.cache.popitem(last=False)
        return response, False
