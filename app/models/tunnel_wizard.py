"""Owner-only quick-tunnel wizard contracts. No node credential or tunnel key is ever a field."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.dns.policy import DNSRule

CandidateId = Literal["paqet", "backhaul-tcp", "gost-tls", "gost-wss", "frp-tcp", "frp-kcp", "frp-quic", "ss2022"]


class WizardPlanInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_node_id: int = Field(gt=0)
    exit_node_id: int = Field(gt=0)
    inbound_tags: list[str] = Field(min_length=1, max_length=128)
    domains: list[str] = Field(default_factory=list, max_length=2000)
    # "fast" keeps the first healthy carrier in priority order; "thorough" measures all and keeps the best.
    mode: Literal["fast", "thorough"] = "fast"
    priority: Literal["balanced", "latency", "throughput"] = "balanced"
    candidates: list[CandidateId] | None = Field(default=None, min_length=1, max_length=8)

    @model_validator(mode="after")
    def valid_request(self):
        if self.source_node_id == self.exit_node_id:
            raise ValueError("Source and exit must be different nodes")
        if self.candidates and len(set(self.candidates)) != len(self.candidates):
            raise ValueError("Duplicate candidate")
        return self

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


class WizardStartInput(WizardPlanInput):
    # Every tested carrier restarts the Xray core on both nodes and drops live connections.
    confirm_restart: Literal[True]
