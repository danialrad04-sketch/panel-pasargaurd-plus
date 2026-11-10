"""Owner-only gaming DNS profile contracts. No node key, tunnel key or user credential is ever a field."""

from ipaddress import ip_address
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.dns.policy import DNSRule
from app.models.dns import DNSLeaseInput


class GamingPlanInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    node_id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=80)
    # Address that clients are sent to; it must be reachable from the player's network.
    public_address: str
    bind_address: str = "0.0.0.0"
    services: list[str] = Field(min_length=1, max_length=64)
    custom_domains: list[str] = Field(default_factory=list, max_length=100)
    egress_tunnel_id: int | None = Field(default=None, gt=0)
    dns_port: int = Field(default=53, ge=1, le=65535)

    @field_validator("name", mode="before")
    @classmethod
    def strip_name(cls, value):
        return value.strip() if isinstance(value, str) else value

    @field_validator("public_address", "bind_address")
    @classmethod
    def address(cls, value):
        ip = ip_address(value)
        if ip.is_multicast or ip.is_link_local or ip.is_loopback:
            raise ValueError("Use a client-reachable unicast IP")
        return str(ip)

    @field_validator("public_address")
    @classmethod
    def public(cls, value):
        if ip_address(value).is_unspecified:
            raise ValueError("Public address cannot be unspecified")
        return value

    @field_validator("services")
    @classmethod
    def distinct(cls, values):
        return sorted(set(values))

    @field_validator("custom_domains")
    @classmethod
    def domains(cls, values):
        return sorted({DNSRule.normalized_domain(value) for value in values})


class GamingApplyInput(GamingPlanInput):
    # Applying replaces the gateway configuration on the node and may open its DNS listener.
    confirm_apply: Literal[True]


class GamingAttachInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: int = Field(gt=0)
    # Optional: the user can register the IP they play from on their own DNS link.
    client_ips: list[str] = Field(default_factory=list, max_length=3)
    ttl: int = Field(default=86400, ge=60, le=86400)
    # 0 means no byte cap for this user; it is chosen explicitly, never implied.
    byte_limit: int = Field(ge=0, le=9_000_000_000_000_000)
    grants_per_second: int = Field(default=200, ge=1, le=2000)
    # Speed cap in bytes/second enforced by the gateway; 0 = unlimited.
    rate_bps: int = Field(default=0, ge=0, le=10_000_000_000)

    @field_validator("client_ips")
    @classmethod
    def ips(cls, values):
        result = sorted({DNSLeaseInput(ip=value).ip for value in values})
        return result


class GamingDomainsInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    # Names found with the watch list; each is validated as an explicit suffix, never a wildcard.
    domains: list[str] = Field(min_length=1, max_length=50)
