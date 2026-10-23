"""Pure, deterministic overlays for the official node's existing Xray Start contract.

User inbounds and accounting remain owned by PasarGuard. An excluded, authenticated
SS2022 inbound is only used for the encrypted inter-node hop (native TCP and UDP).
"""

from copy import deepcopy

PREFIX = "pg-tunnel-"
METHOD = "2022-blake3-aes-128-gcm"
USER_PROTOCOLS = {"vmess", "vless", "trojan", "shadowsocks", "hysteria", "masque", "wireguard"}


def occupied_port(config, port):
    for inbound in config.get("inbounds", []):
        value = str(inbound.get("port", ""))
        for part in value.split(","):
            bounds = part.strip().split("-")
            if len(bounds) in (1, 2) and all(v.isdigit() for v in bounds) and int(bounds[0]) <= port <= int(bounds[-1]):
                return True
    return False


def compose(config: dict, node_id: int, tunnels, nodes: dict, excluded=()):
    result = deepcopy(config)
    exclude = set(excluded)
    rules = []
    for kind in ("inbounds", "outbounds"):
        if any(str(row.get("tag", "")).startswith(PREFIX) for row in result.get(kind, [])):
            raise ValueError("Core uses the reserved pg-tunnel- namespace")
    for tunnel in sorted(tunnels, key=lambda item: item.id):
        if not tunnel.enabled:
            continue
        tag = f"{PREFIX}{tunnel.id}"
        if node_id == tunnel.source_node_id:
            available = {
                row["tag"]
                for row in result.get("inbounds", [])
                if row.get("protocol") in USER_PROTOCOLS and row.get("tag") not in exclude
            }
            if not set(tunnel.inbound_tags).issubset(available):
                raise ValueError(f"Tunnel {tunnel.id}: selected user inbound no longer exists")
            exit_node = nodes.get(tunnel.exit_node_id)
            if exit_node is None:
                raise ValueError(f"Tunnel {tunnel.id}: exit node no longer exists")
            result.setdefault("outbounds", []).append(
                {
                    "tag": tag,
                    "protocol": "shadowsocks",
                    "settings": {
                        "servers": [
                            {
                                "address": exit_node.address,
                                "port": tunnel.port,
                                "method": METHOD,
                                "password": tunnel.shared_key,
                            }
                        ]
                    },
                }
            )
            rule = {"type": "field", "inboundTag": list(tunnel.inbound_tags), "outboundTag": tag}
            if tunnel.domains:
                rule["domain"] = [f"domain:{domain}" for domain in tunnel.domains]
            rules.append(rule)
        elif node_id == tunnel.exit_node_id:
            if occupied_port(result, tunnel.port):
                raise ValueError(f"Tunnel {tunnel.id}: exit port is already used")
            result.setdefault("inbounds", []).append(
                {
                    "tag": tag,
                    "listen": "0.0.0.0",
                    "port": tunnel.port,
                    "protocol": "shadowsocks",
                    "settings": {"method": METHOD, "password": tunnel.shared_key, "network": "tcp,udp"},
                }
            )
            result.setdefault("outbounds", []).append({"tag": tag, "protocol": "freedom", "settings": {}})
            rules.append({"type": "field", "inboundTag": [tag], "outboundTag": tag})
            exclude.add(tag)
    if rules:
        routing = result.setdefault("routing", {})
        routing["rules"] = rules + routing.get("rules", [])
    return result, exclude
