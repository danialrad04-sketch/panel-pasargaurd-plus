"""Pinned MIT doctor-dns catalog data; selecting domains does not install a relay."""

import hashlib
import json
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .policy import DNSPolicyInput, DNSRule

ROOT = Path(__file__).resolve().parents[1] / "data"
CATALOG_SHA256 = "7ce0cae493437ec23e0ff9a08f7a92a862c4a8ffffe2a3f8f890293179ca3917"
SOURCE_REVISION = "a2b2ff843cb1fc8f0a5712a88149f5c379198ff1"


@lru_cache(maxsize=1)
def catalog():
    raw = (ROOT / "doctor-dns-services.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != CATALOG_SHA256:
        raise RuntimeError("Gaming catalog differs from its reviewed snapshot")
    value = json.loads(raw)
    provenance = json.loads((ROOT / "doctor-dns-source.json").read_text())
    if provenance["sha256"] != CATALOG_SHA256 or provenance["commit"] != SOURCE_REVISION:
        raise RuntimeError("Gaming catalog provenance mismatch")
    domains = set()
    groups = set()
    for service in value["services"]:
        for group in service["groups"]:
            identity = (service["key"], group["key"])
            if identity in groups:
                raise RuntimeError("Duplicate catalog group")
            groups.add(identity)
            for domain in group["domains"]:
                normalized = DNSRule(domain=domain, action="allow").domain
                if normalized != domain or domain in domains:
                    raise RuntimeError("Duplicate or noncanonical catalog domain")
                domains.add(domain)
    return {
        **value,
        "source": provenance,
        "counts": {
            "services": len(value["services"]),
            "groups": len(groups),
            "domains": len(domains),
        },
        "relay_available": False,
        "game_acceptance": "untested",
    }


class CatalogGroup(BaseModel):
    model_config = ConfigDict(extra="forbid")
    service: str = Field(min_length=1, max_length=80)
    group: str = Field(min_length=1, max_length=80)


class CatalogPolicyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    groups: list[CatalogGroup] = Field(min_length=1, max_length=100)
    relay_addresses: list[str] = Field(default_factory=list, max_length=8)
    domains_off: list[str] = Field(default_factory=list, max_length=1000)
    custom_domains: list[str] = Field(default_factory=list, max_length=500)

    @field_validator("relay_addresses")
    @classmethod
    def addresses(cls, values):
        if not values:
            return []
        return DNSRule(domain="relay.example", action="rewrite", addresses=values).addresses

    @field_validator("domains_off", "custom_domains")
    @classmethod
    def domains(cls, values):
        return sorted({DNSRule(domain=d, action="allow").domain for d in values})


def compile_policy(body):
    if not body.relay_addresses:
        raise ValueError("External relay addresses are required")
    known = {(s["key"], g["key"]): g["domains"] for s in catalog()["services"] for g in s["groups"]}
    selected = {(g.service, g.group) for g in body.groups}
    if not selected <= known.keys():
        raise ValueError("Unknown catalog service/group; no partial policy was created")
    domains = set(body.custom_domains)
    for group in selected:
        domains.update(known[group])
    if not set(body.domains_off) <= domains:
        raise ValueError("An excluded domain must belong to this selected policy")
    rules = [
        {"domain": d, "action": "allow"}
        if d in body.domains_off
        else {"domain": d, "action": "rewrite", "addresses": body.relay_addresses}
        for d in sorted(domains)
    ]
    return DNSPolicyInput(name=body.name, rules=rules)
