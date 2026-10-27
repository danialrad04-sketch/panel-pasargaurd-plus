from ipaddress import ip_address
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.dns.policy import DNSRule


class RouteInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    node_id: int = Field(gt=0)
    policy_id: int = Field(gt=0)
    domain: str
    public_address: str
    bind_address: str
    tcp_ports: list[int] = Field(default_factory=lambda: [80, 443], max_length=16)
    udp_ports: list[int] = Field(default_factory=list, max_length=16)
    mode: Literal["sniff", "fixed"] = "sniff"
    enabled: bool = False
    egress_tunnel_id: int | None = Field(default=None, gt=0)
    _domain = field_validator("domain")(DNSRule.normalized_domain.__func__)

    @field_validator("public_address", "bind_address")
    @classmethod
    def address(cls, value):
        ip = ip_address(value)
        if ip.is_multicast or ip.is_link_local:
            raise ValueError("Use a unicast gateway IP")
        if ip.is_loopback:
            raise ValueError("Loopback gateways are not client-reachable")
        return str(ip)

    @field_validator("public_address")
    @classmethod
    def public(cls, value):
        if ip_address(value).is_unspecified:
            raise ValueError("Public address cannot be unspecified")
        return value

    @field_validator("tcp_ports", "udp_ports")
    @classmethod
    def ports(cls, values):
        if any(not 1 <= port <= 65535 for port in values) or len(set(values)) != len(values):
            raise ValueError("Use distinct valid TCP/UDP ports")
        return sorted(values)

    @model_validator(mode="after")
    def listener(self):
        if not self.tcp_ports and not self.udp_ports:
            raise ValueError("At least one listener is required")
        return self


class RouteModify(RouteInput):
    expected_revision: int = Field(ge=1)


class RelayAccountInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = False
    byte_limit: int = Field(default=0, ge=0, le=9_000_000_000_000_000)
    grants_per_second: int = Field(default=200, ge=1, le=2000)


class GrantInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: UUID
    issued_at: int
    route_id: int = Field(gt=0)
    revision: int = Field(ge=1)
    source_ip: str
    host: str
    port: int = Field(ge=1, le=65535)
    network: Literal["tcp", "udp"]
    amount: int = Field(default=262144, ge=1, le=262144)
    _domain = field_validator("host")(DNSRule.normalized_domain.__func__)

    @field_validator("source_ip")
    @classmethod
    def source(cls, value):
        from app.dns.service import source_address

        return source_address(value)


class SettleInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    grant_id: UUID
    used: int = Field(ge=0, le=262144)


class NodeDNSInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected_revision: int = Field(default=0, ge=0)
    enabled: bool = False
    bind_address: str = "0.0.0.0"
    port: int = Field(default=53, ge=1, le=65535)

    @field_validator("bind_address")
    @classmethod
    def address(cls, value):
        address = ip_address(value)
        if address.is_multicast or address.is_link_local:
            raise ValueError("Use a local unicast listener IP")
        return str(address)


class NodeDNSQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    revision: int = Field(ge=1)
    source_ip: str
    udp: bool = True
    wire: str = Field(min_length=16, max_length=8192)

    @field_validator("source_ip")
    @classmethod
    def source(cls, value):
        from app.dns.service import source_address

        return source_address(value)
