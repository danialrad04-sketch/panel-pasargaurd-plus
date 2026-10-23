"""Public tunnel contracts contain no node credentials or tunnel keys."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.dns.policy import DNSRule
from app.models.validators import AwareDatetime, OptionalAwareDatetime


class TunnelInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    inbound_tags: list[str] = Field(min_length=1, max_length=128)
    domains: list[str] = Field(default_factory=list, max_length=2000)
    port: int = Field(default=40900, ge=1024, le=65535)
    enabled: bool = False

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


class TunnelView(TunnelInput):
    id: int
    source_node_id: int
    exit_node_id: int
    revision: int
    applied_revision: int
    transport: Literal["shadowsocks-2022"] = "shadowsocks-2022"
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
