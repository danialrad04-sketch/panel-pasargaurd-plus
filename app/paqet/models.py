"""Complete Linux Paqet alpha.21 configuration surface; no ignored fields."""

import ipaddress
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator

VERSION = "v1.0.0-alpha.21"
BLOCKS = (
    "aes",
    "aes-128",
    "aes-128-gcm",
    "aes-192",
    "salsa20",
    "blowfish",
    "twofish",
    "cast5",
    "3des",
    "tea",
    "xtea",
    "xor",
    "sm4",
    "none",
    "null",
)


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class KCP(Strict):
    mode: Literal["normal", "fast", "fast2", "fast3", "manual"] = "normal"
    block: str = "aes-128-gcm"
    mtu: int = Field(1150, ge=50, le=1500)
    rcvwnd: int = Field(512, ge=1, le=32768)
    sndwnd: int = Field(128, ge=1, le=32768)
    nodelay: int = Field(1, ge=0, le=1)
    interval: int = Field(10, ge=10, le=5000)
    resend: int = Field(2, ge=0, le=2)
    nocongestion: int = Field(0, ge=0, le=1)
    wdelay: bool = False
    acknodelay: bool = True
    dshard: int = Field(0, ge=0, le=128)
    pshard: int = Field(0, ge=0, le=128)
    smuxbuf: int = Field(4194304, ge=1024, le=67108864)
    streambuf: int = Field(2097152, ge=1024, le=67108864)
    smuxkalive: int = Field(2, ge=1, le=3600)
    smuxktimeout: int = Field(8, ge=2, le=7200)

    @model_validator(mode="after")
    def paired(self):
        if self.block not in BLOCKS:
            raise ValueError("Unsupported Paqet block cipher")
        if bool(self.dshard) != bool(self.pshard) or self.dshard + self.pshard > 255:
            raise ValueError("FEC requires both shards with total <=255")
        if self.smuxktimeout <= self.smuxkalive:
            raise ValueError("SMUX timeout must exceed keepalive")
        return self


class Address(Strict):
    addr: str
    router_mac: str = Field(pattern=r"^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    @field_validator("addr")
    @classmethod
    def address(cls, value):
        endpoint(value, allow_zero=True)
        return value


class PCAP(Strict):
    sockbuf: int = Field(4194304, ge=1024, le=104857600)


class TCP(Strict):
    local_flag: list[str] = Field(default_factory=lambda: ["PA"], min_length=1, max_length=64)
    remote_flag: list[str] = Field(default_factory=lambda: ["PA"], min_length=1, max_length=64)

    @field_validator("local_flag", "remote_flag")
    @classmethod
    def flags(cls, values):
        if any(not v or len(v) > 9 or set(v) - set("FSRPAUECN") for v in values):
            raise ValueError("Invalid TCP flags")
        return values


class Network(Strict):
    interface: str | None = Field(None, pattern=r"^[A-Za-z0-9_.:-]{1,15}$")
    ipv4: Address | None = None
    ipv6: Address | None = None
    pcap: PCAP = Field(default_factory=PCAP)
    tcp: TCP = Field(default_factory=TCP)

    @field_validator("ipv4", "ipv6", mode="before")
    @classmethod
    def empty_address(cls, value):
        if isinstance(value, dict) and not value.get("addr") and not value.get("router_mac"):
            return None
        return value

    @model_validator(mode="after")
    def families(self):
        for field, version in ((self.ipv4, 4), (self.ipv6, 6)):
            if field and endpoint(field.addr, allow_zero=True)[0].version != version:
                raise ValueError("Address family differs from network field")
        return self


def endpoint(value, *, allow_zero=False):
    if value.startswith("["):
        host, port = value[1:].split("]:")
    else:
        host, port = value.rsplit(":", 1)
    ip = ipaddress.ip_address(host)
    if ip.is_multicast or ip.is_link_local or "%" in host:
        raise ValueError("Use a numeric unscoped unicast address")
    if not port.isdecimal() or not (0 if allow_zero else 1) <= int(port) <= 65535:
        raise ValueError("Invalid port")
    return ip, int(port)


class Forward(Strict):
    listen: str
    target: str
    protocol: Literal["tcp", "udp"]

    @model_validator(mode="after")
    def addresses(self):
        endpoint(self.listen)
        if not endpoint(self.target)[0].is_loopback:
            raise ValueError("Forward targets must be numeric exit-node loopback addresses")
        return self


class Socks(Strict):
    listen: str
    username: str = Field("", max_length=255)
    password: SecretStr | None = Field(None, json_schema_extra={"writeOnly": True})

    @field_validator("listen")
    @classmethod
    def listener(cls, value):
        endpoint(value)
        return value


class PaqetOptions(Strict):
    conn: int = Field(1, ge=1, le=256)
    kcp: KCP = Field(default_factory=KCP)
    log_level: Literal["none", "debug", "info", "warn", "error", "fatal"] = "warn"
    client_network: Network = Field(default_factory=Network)
    server_network: Network = Field(default_factory=Network)
    client_address: str | None = None
    server_address: str | None = None
    client_port: int = Field(11081, ge=1024, le=65535)
    exit_port: int = Field(11082, ge=1024, le=65535)
    manage_firewall: bool = True
    allow_insecure: bool = False
    allow_unmetered: bool = False
    forwards: list[Forward] = Field(default_factory=list, max_length=40)
    socks5: list[Socks] = Field(default_factory=list, max_length=8)

    @field_validator("client_address", "server_address")
    @classmethod
    def peer(cls, value):
        if value:
            ip = ipaddress.ip_address(value)
            if ip.is_unspecified or ip.is_multicast or ip.is_link_local:
                raise ValueError("Use a unicast peer IP")
            return str(ip)
        return value

    @model_validator(mode="after")
    def combinations(self):
        if self.kcp.block in ("none", "null") and not self.allow_insecure:
            raise ValueError("Unencrypted Paqet block requires allow_insecure")
        if (self.forwards or self.socks5) and not self.allow_unmetered:
            raise ValueError("Extra forwards/SOCKS bypass native user accounting; require allow_unmetered")
        ports = {("tcp", self.client_port), ("udp", self.client_port)}
        for row in self.forwards:
            port = (row.protocol, endpoint(row.listen)[1])
            if port in ports:
                raise ValueError("Duplicate or reserved forward listener")
            ports.add(port)
        for row in self.socks5:
            port = ("tcp", endpoint(row.listen)[1])
            if port in ports:
                raise ValueError("Duplicate or reserved SOCKS listener")
            ports.add(port)
            # Missing password on an edit means preserve the existing password;
            # persistence validates credentials after that merge.
        for net in (self.client_network, self.server_network):
            configured = [endpoint(a.addr, allow_zero=True)[1] for a in (net.ipv4, net.ipv6) if a]
            if len(set(configured)) > 1:
                raise ValueError("IPv4/IPv6 local ports must match")
        for a in (self.client_network.ipv4, self.client_network.ipv6):
            if a and endpoint(a.addr, allow_zero=True)[1] and self.conn != 1:
                raise ValueError("Fixed client source port requires conn=1")
        return self
