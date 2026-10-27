"""Bounded operator settings for built-in native engine adapters."""

import re
from ipaddress import ip_address
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

PROFILES = {
    "backhaul": ("tcp",),
    "gost": ("tcp", "ws", "tls", "wss"),
    "frp": ("tcp", "websocket", "kcp", "quic"),
}
DEFAULT_PROFILE = {"backhaul": "tcp", "gost": "tls", "frp": "tcp"}


class CarrierOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    profile: Literal["tcp", "ws", "tls", "wss", "websocket", "kcp", "quic"] | None = None
    client_port: int = Field(default=11081, ge=1024, le=65535)
    exit_port: int = Field(default=11082, ge=1024, le=65535)
    control_address: str | None = None
    pool: int = Field(default=8, ge=1, le=64)
    keepalive: int = Field(default=30, ge=5, le=300)
    retry: int = Field(default=3, ge=1, le=60)
    dial_timeout: int = Field(default=10, ge=1, le=60)
    no_delay: bool = True
    mux: bool = True
    mux_concurrency: int = Field(default=8, ge=1, le=64)
    mux_version: int = Field(default=1, ge=1, le=2)
    frame_size: int = Field(default=32768, ge=1024, le=65535)
    receive_buffer: int = Field(default=4194304, ge=65536, le=16777216)
    stream_buffer: int = Field(default=65536, ge=4096, le=4194304)
    channel_size: int = Field(default=2048, ge=16, le=8192)
    heartbeat: int = Field(default=30, ge=5, le=300)
    udp_buffer: int = Field(default=4096, ge=1500, le=65535)

    @field_validator("control_address")
    @classmethod
    def address(cls, value):
        if value is None:
            return None
        try:
            address = ip_address(value)
        except ValueError:
            if len(value) <= 253 and re.fullmatch(r"(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", value):
                return value.lower()
            raise ValueError("Use a numeric IP or hostname, without URL or port") from None
        if address.is_unspecified or address.is_multicast or address.is_link_local:
            raise ValueError("Use a reachable unicast control address")
        return str(address)

    @model_validator(mode="after")
    def distinct_ports(self):
        if self.client_port == self.exit_port:
            raise ValueError("Source and exit internal ports must differ")
        return self


def stored_options(engine, value):
    options = value if isinstance(value, CarrierOptions) else CarrierOptions.model_validate(value or {})
    data = options.model_dump()
    data["profile"] = data["profile"] or DEFAULT_PROFILE[engine]
    if data["profile"] not in PROFILES[engine]:
        raise ValueError("This engine profile does not support the native TCP/UDP contract")
    return data
