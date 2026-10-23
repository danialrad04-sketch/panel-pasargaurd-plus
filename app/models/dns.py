from ipaddress import ip_address

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.dns.policy import DNSPolicyInput, DNSRule
from app.models.validators import AwareDatetime, OptionalAwareDatetime


class DNSPolicyModify(DNSPolicyInput):
    expected_revision: int = Field(ge=1)


class DNSAccountInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    policy_id: int = Field(gt=0)
    enabled: bool = True
    query_limit: int = Field(default=0, ge=0, le=9_000_000_000_000_000)
    qps: int = Field(default=20, ge=1, le=100)


class DNSAccountCreate(DNSAccountInput):
    user_id: int = Field(gt=0)


class DNSAccountView(BaseModel):
    id: int
    user_id: int
    username: str
    policy_id: int
    policy_name: str
    enabled: bool
    eligible: bool
    query_limit: int
    qps: int
    period: int
    queries: int
    request_bytes: int
    response_bytes: int
    errors: int
    blocked: int
    created_at: AwareDatetime


class DNSLeaseInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ip: str
    ttl: int = Field(default=3600, ge=60, le=86400)

    @field_validator("ip")
    @classmethod
    def normalize_ip(cls, value):
        if "%" in value:
            raise ValueError("Scoped addresses cannot identify clients")
        address = ip_address(value)
        address = getattr(address, "ipv4_mapped", None) or address
        if address.is_unspecified or address.is_multicast or address.is_link_local:
            raise ValueError("Unspecified, multicast and link-local clients are invalid")
        return str(address)


class DNSPolicyView(BaseModel):
    id: int
    name: str
    revision: int
    rules: list[DNSRule]


class DNSAccountList(BaseModel):
    items: list[DNSAccountView]
    total: int


class DNSCredential(BaseModel):
    token: str


class DNSLeaseView(BaseModel):
    ip: str
    expires_at: AwareDatetime


class DNSPeriodView(BaseModel):
    period: int
    queries: int
    request_bytes: int
    response_bytes: int
    errors: int
    blocked: int
    created_at: AwareDatetime
    ended_at: OptionalAwareDatetime


class DNSAuditView(BaseModel):
    id: int
    actor: str
    action: str
    target_id: str
    details: dict
    created_at: AwareDatetime


class DNSRuntime(BaseModel):
    native: bool
    enabled: bool
    doh: str
    source_ip_transports: list[str]
    upstream: str
    upstream_port: int
    worker_port: int
    dot_port: int | None
    readiness: str
