"""Compile a native tunnel into the source-built node's Paqet contract."""

import copy
import ipaddress
import secrets

from fastapi import HTTPException

from app.paqet.models import PaqetOptions, endpoint


def store_options(options, previous=None):
    options = options or PaqetOptions()
    value = options.model_dump(mode="python")
    old = {row["listen"]: row for row in (previous or {}).get("socks5", [])}
    for row, source in zip(value["socks5"], options.socks5, strict=True):
        secret = (
            source.password.get_secret_value() if source.password else old.get(row["listen"], {}).get("password", "")
        )
        if len(secret.encode()) > 255 or len(row["username"].encode()) > 255 or bool(row["username"]) != bool(secret):
            raise HTTPException(422, "SOCKS username/password must be a complete pair, at most 255 bytes")
        if not endpoint(row["listen"])[0].is_loopback and not secret:
            raise HTTPException(422, "Public SOCKS listeners require authentication")
        row["password"] = secret
    return value


def key(value=None):
    secret = value.get_secret_value() if value else secrets.token_hex(32)
    if not 16 <= len(secret.encode()) <= 256:
        raise HTTPException(422, "Paqet key must be 16–256 bytes")
    return secret


def hostport(host, port):
    return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"


def node_request(tunnel, node_id, nodes):
    options = copy.deepcopy(tunnel.paqet_options)
    is_client = node_id == tunnel.source_node_id
    peer_id = tunnel.exit_node_id if is_client else tunnel.source_node_id
    peer = options.get("server_address" if is_client else "client_address") or nodes[peer_id].address
    try:
        ip = ipaddress.ip_address(peer)
        if ip.is_unspecified or ip.is_multicast or ip.is_link_local:
            raise ValueError()
    except ValueError:
        raise ValueError("Paqet needs numeric peer addresses; set client/server address overrides") from None
    network = copy.deepcopy(options.get("client_network" if is_client else "server_network", {}))
    network = {k: v for k, v in network.items() if v is not None}
    # Generated proxy ports are loopback-only. Paqet adds the raw carrier, while
    # authentication and per-user counters remain in the native Xray inbounds.
    cfg = {
        "role": "client" if is_client else "server",
        "log": {"level": options.get("log_level", "warn")},
        "network": network,
        "transport": {
            "protocol": "kcp",
            "conn": options.get("conn", 1),
            "kcp": {**options.get("kcp", {}), "key": tunnel.paqet_key},
        },
    }
    if is_client:
        cfg["server"] = {"addr": hostport(str(ip), tunnel.port)}
        cfg["forward"] = [
            {
                "listen": f"127.0.0.1:{options['client_port']}",
                "target": f"127.0.0.1:{options['exit_port']}",
                "protocol": protocol,
            }
            for protocol in ("tcp", "udp")
        ]
        cfg["forward"] += options.get("forwards", [])
        cfg["socks5"] = options.get("socks5", [])
    else:
        cfg["listen"] = {"addr": f":{tunnel.port}"}
    return {
        "revision": tunnel.revision,
        "enabled": tunnel.enabled,
        "config": cfg,
        "peer": str(ip),
        "manage_firewall": options.get("manage_firewall", True),
    }
