"""Owner-only provisioning inputs. SSH secrets are never response fields."""

from ipaddress import ip_address
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator


class Target(BaseModel):
    model_config = ConfigDict(extra="forbid")
    host: str
    ssh_port: int = Field(default=22, ge=1, le=65535)

    @field_validator("host")
    @classmethod
    def unicast(cls, value):
        address = ip_address(value)
        if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local:
            raise ValueError("Use the server management IP")
        return str(address)


class InstallInput(Target):
    name: str = Field(min_length=1, max_length=80)
    node_id: int | None = Field(default=None, gt=0)
    core_config_id: int = Field(default=1, gt=0)
    engine: Literal["paqet", "backhaul", "gost", "frp"] = "paqet"
    username: str = Field(default="root", pattern=r"^[a-z_][a-z0-9_-]{0,31}$")
    server_host_key: str = Field(min_length=20, max_length=16384)
    password: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})
    private_key: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})
    key_passphrase: SecretStr | None = Field(default=None, json_schema_extra={"writeOnly": True})
    node_port: int = Field(default=62050, ge=1024, le=65535)
    tunnel_port: int = Field(default=62052, ge=1024, le=65535)
    health_port: int = Field(default=62053, ge=1024, le=65535)

    @field_validator("name")
    @classmethod
    def nonempty_name(cls, value):
        if not value.strip():
            raise ValueError("Node name is required")
        return value.strip()

    @model_validator(mode="after")
    def credentials_and_ports(self):
        if bool(self.password) == bool(self.private_key):
            raise ValueError("Provide exactly one SSH password or private key")
        for value, limit in ((self.password, 4096), (self.private_key, 65536), (self.key_passphrase, 4096)):
            if value and len(value.get_secret_value().encode()) > limit:
                raise ValueError("SSH credential is too long")
        if len({self.node_port, self.tunnel_port, self.health_port, 62051, self.ssh_port}) != 5:
            raise ValueError("SSH, node, tunnel, health and core API ports must differ")
        return self
