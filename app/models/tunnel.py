"""Public tunnel contracts contain no node credentials or tunnel keys."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_serializer, model_validator

from app.dns.policy import DNSRule
from app.models.validators import AwareDatetime, OptionalAwareDatetime
from app.paqet.models import PaqetOptions


class TunnelInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    inbound_tags: list[str] = Field(min_length=1, max_length=128)
    domains: list[str] = Field(default_factory=list, max_length=2000)
    port: int = Field(default=40900, ge=1024, le=65535)
    enabled: bool = False
    transport: Literal["shadowsocks-2022", "paqet"] = "shadowsocks-2022"
    paqet_options: PaqetOptions | None = None
    paqet_key: SecretStr | None = Field(None, json_schema_extra={"writeOnly": True})

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, value):
        if not value.strip():
            raise ValueError("Name must not be blank")
        return value.strip()

    @field_validator("inbound_tags")
    @classmethod
    def valid_tags(cls, values):
        if any(not tag or len(tag) > 128 or tag.startswith("pg-tunnel-") for tag in values):
            raise ValueError("Invalid user inbound tag")
        if len(set(values)) != len(values):
            raise ValueError("Duplicate inbound tags")
        return values

    @field_validator("domains")
    @classmethod
    def valid_domains(cls, values):
        return sorted({DNSRule.normalized_domain(value) for value in values})


class TunnelCreate(TunnelInput):
    source_node_id: int = Field(gt=0)
    exit_node_id: int = Field(gt=0)

    @model_validator(mode="after")
    def different_nodes(self):
        if self.source_node_id == self.exit_node_id:
            raise ValueError("Source and exit must be different nodes")
        return self


class TunnelModify(TunnelInput):
    expected_revision: int = Field(ge=1)


class TunnelRevision(BaseModel):
    expected_revision: int = Field(ge=1)


class TunnelPreflightCheck(BaseModel):
    code: str
    status: Literal["pass", "warning", "fail"]
    detail: str
    blocks_apply: bool = False
    blocks_publish: bool = False


class TunnelPreflight(BaseModel):
    tunnel_id: int
    revision: int
    can_apply: bool
    can_publish: bool
    checks: list[TunnelPreflightCheck]
    traffic_readiness: Literal["not_probed"] = "not_probed"


class TunnelView(TunnelInput):
    engine_id: str
    id: int
    source_node_id: int
    exit_node_id: int
    revision: int
    applied_revision: int
    paqet_options: dict | None = Field(None, repr=False)
    paqet_key: SecretStr | None = Field(None, exclude=True, repr=False)

    @model_serializer(mode="wrap")
    def private_options(self, handler):
        value = handler(self)
        if value.get("paqet_options"):
            import copy

            value["paqet_options"] = copy.deepcopy(value["paqet_options"])
            for row in value["paqet_options"].get("socks5", []):
                row["authenticated"] = bool(row.pop("password", ""))
        return value

    networks: list[str] = ["tcp", "udp"]
    model_config = ConfigDict(from_attributes=True)


class TunnelNodeOption(BaseModel):
    id: int
    name: str
    address: str
    status: str
    core_type: str
    inbound_tags: list[str]


class TunnelNodeResult(BaseModel):
    node_id: int
    ok: bool


class TunnelDeployResult(BaseModel):
    ok: bool
    state: Literal["applying", "applied", "partial_failure", "no_changes"]
    nodes: list[TunnelNodeResult] = Field(default_factory=list)
    traffic_readiness: Literal["not_probed"] = "not_probed"


class TunnelAuditView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: AwareDatetime
    actor: str
    action: str
    target_id: str
    details: dict


class TunnelState(BaseModel):
    items: list[TunnelView]
    nodes: list[TunnelNodeOption]
    applying: bool
    lease_until: OptionalAwareDatetime = None
    last_result: TunnelDeployResult | None
    traffic_readiness: Literal["not_probed"] = "not_probed"


class TunnelHostPublish(TunnelRevision):
    model_config = ConfigDict(extra="forbid")
    template_host_id: int = Field(gt=0)
    address: str | None = Field(default=None, max_length=256)
    port: int | None = Field(default=None, ge=1, le=65535)
    remark: str | None = Field(default=None, max_length=256)

    @field_validator("address")
    @classmethod
    def ingress_address(cls, value):
        if value:
            import ipaddress

            ip = ipaddress.ip_address(value)
            if ip.is_unspecified or ip.is_multicast or ip.is_loopback or ip.is_link_local:
                raise ValueError("Use the client-reachable ingress IP, not a loopback or unspecified address")
            return str(ip)
        return None
