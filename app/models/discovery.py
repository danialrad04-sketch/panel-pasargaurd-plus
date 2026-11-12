"""Inputs for read-only tunnel discovery. Bounded: pasted facts are untrusted."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator

from app.models.provision import Target


class Loose(BaseModel):
    model_config = ConfigDict(extra="ignore")


class FactProcess(Loose):
    pid: int | None = None
    name: str = Field("", max_length=256)
    exe: str = Field("", max_length=1024)
    cwd: str = Field("", max_length=1024)
    uid: int | None = None
    args: list[str] = Field(default_factory=list, max_length=64)
    via_interpreter: bool = False
    containerized: bool = False


class FactListener(Loose):
    proto: Literal["tcp", "udp"]
    bind: str = Field(max_length=64)
    port: int = Field(ge=1, le=65535)
    pid: int | None = None
    name: str | None = Field(None, max_length=64)


class FactUnit(Loose):
    name: str = Field(max_length=256)
    path: str = Field("", max_length=1024)
    exec_start: str = Field("", max_length=4096)
    working_directory: str = Field("", max_length=1024)
    active: str | None = Field(None, max_length=32)
    enabled: str | None = Field(None, max_length=32)


class FactFile(Loose):
    path: str = Field(max_length=1024)
    bytes: int = 0
    truncated: bool = False
    text: str = Field(max_length=300_000)


class Facts(Loose):
    version: int = 1
    privileged: bool = False
    host: dict[str, str] = Field(default_factory=dict, max_length=8)
    processes: list[FactProcess] = Field(default_factory=list, max_length=200)
    listeners: list[FactListener] = Field(default_factory=list, max_length=2000)
    units: list[FactUnit] = Field(default_factory=list, max_length=60)
    files: list[FactFile] = Field(default_factory=list, max_length=40)
    firewall: dict[str, str | list[str]] = Field(default_factory=dict, max_length=8)
    network: dict = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list, max_length=20)


class AnalyzeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    address: str = Field(min_length=1, max_length=256)
    facts: Facts


class ScanInput(Target):
    username: str = Field(default="root", pattern=r"^[a-z_][a-z0-9_-]{0,31}$")
    server_host_key: str = Field(min_length=20, max_length=16384)
    password: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})
    private_key: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})
    key_passphrase: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})

    @model_validator(mode="after")
    def credentials(self):
        if bool(self.password) == bool(self.private_key):
            raise ValueError("Provide exactly one SSH password or private key")
        for value, limit in ((self.password, 4096), (self.private_key, 65536), (self.key_passphrase, 4096)):
            if value and len(value.get_secret_value().encode()) > limit:
                raise ValueError("SSH credential is too long")
        return self


class ReservedPort(BaseModel):
    model_config = ConfigDict(extra="forbid")
    port: int = Field(ge=1, le=65535)
    protocol: Literal["tcp", "udp"] = "tcp"
    bind: str = Field("", max_length=64)
    purpose: str = Field("", max_length=200)


class ReservationItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=120)
    kind: str = Field(min_length=1, max_length=32, pattern=r"^[a-z0-9_-]+$")
    role: str | None = Field(None, max_length=16)
    peer: str | None = Field(None, max_length=300)
    ports: list[ReservedPort] = Field(min_length=1, max_length=64)


class ReservationInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    address: str = Field(min_length=1, max_length=256)
    items: list[ReservationItem] = Field(min_length=1, max_length=50)
