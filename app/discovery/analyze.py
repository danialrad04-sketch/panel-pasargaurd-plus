"""Turn collector facts into a tunnel inventory. Pure functions: no database, network or filesystem access.

The result answers four questions for an existing server (typically an Iran node whose Paqet tunnel to a foreign
node already carries V2Ray/Xray traffic and a DNS path):

* which tunnels run here, in which role, towards which peer, on which ports;
* which proxy/DNS configs depend on those tunnels (so nothing is moved or restarted blindly);
* which firewall rules belong to them and which belong to this panel (comment ``pg-plus-paqet-*``);
* which ports must be reserved so the panel never allocates, binds or rewrites them.
"""

import ipaddress
import json
import re
import tomllib
from collections import defaultdict
from urllib.parse import urlsplit

import yaml

PANEL_RULE_TAG = "pg-plus-paqet-"
PANEL_DEFAULT_PORTS = {62050: "node API", 62051: "core API", 62052: "tunnel API", 62053: "health echo"}
DNS_PORTS = {53, 853, 5353, 5335}
PROXY_NAMES = ("xray", "v2ray", "sing-box", "singbox", "x-ui", "3x-ui", "hysteria", "tuic", "trojan")
DNS_NAMES = ("unbound", "dnsmasq", "smartdns", "coredns", "named", "stubby", "dnscrypt-proxy")
LOOPBACK = {"127.0.0.1", "::1", "localhost", "0.0.0.0", "::", ""}


def _port(value):
    try:
        port = int(value)
    except TypeError, ValueError:
        return None
    return port if 0 < port < 65536 else None


def _endpoint(value):
    """'1.2.3.4:5', '[::1]:5', ':5' -> (host, port) or (None, None)."""
    if not isinstance(value, str) or ":" not in value:
        return None, None
    host, _, port = value.strip().rpartition(":")
    return host.strip("[]"), _port(port)


def _name(row):
    value = row.get("name") if isinstance(row, dict) else None
    return value.lower() if isinstance(value, str) else ""


def _kind_of_name(name):
    name = name.lower()
    for kind in ("paqet", "backhaul", "gost", "frp", "kcptun", "udp2raw", "wstunnel", "chisel", "rathole", "socat"):
        if name.startswith(kind):
            return "frp" if kind == "frp" else kind
    if name in ("frpc", "frps"):
        return "frp"
    if name in ("ssh", "autossh"):
        return "ssh"
    for kind in PROXY_NAMES:
        if name.startswith(kind):
            return "proxy"
    for kind in DNS_NAMES:
        if name.startswith(kind):
            return "dns"
    return None


def _load(text, path):
    """Parse a redacted config. Returns (format, data) or (None, None)."""
    if path.endswith(".json"):
        try:
            return "json", json.loads(text)
        except ValueError:
            return None, None
    if path.endswith((".yaml", ".yml")):
        try:
            data = yaml.safe_load(text)
        except yaml.YAMLError:
            return None, None
        return ("yaml", data) if isinstance(data, dict) else (None, None)
    if path.endswith(".toml"):
        try:
            return "toml", tomllib.loads(text)
        except tomllib.TOMLDecodeError:
            return None, None
    return None, None


def _obj(value):
    return value if isinstance(value, dict) else {}


def _listeners_for(listeners, pid=None, ports=()):
    wanted = set(ports)
    return [row for row in listeners if (pid is not None and row.get("pid") == pid) or row.get("port") in wanted]


def _paqet(path, data, process, listeners):
    role = data.get("role")
    network = _obj(data.get("network"))
    if role not in ("client", "server") or not (network or data.get("transport")):
        return None
    ipv4 = _obj(network.get("ipv4"))
    ipv6 = _obj(network.get("ipv6"))
    local = ipv4.get("addr") or ipv6.get("addr")
    peer = _obj(data.get("server")).get("addr") if role == "client" else None
    listen = _obj(data.get("listen")).get("addr") if role == "server" else None
    ports = []
    if role == "server":
        _, port = _endpoint(listen) if listen else (None, None)
        if port:
            ports.append({"port": port, "protocol": "tcp", "bind": "raw", "purpose": "paqet server (raw packets)"})
    else:
        _, port = _endpoint(peer or "")
        for row in data.get("socks5") or []:
            host, port = _endpoint(_obj(row).get("listen", ""))
            if port:
                ports.append({"port": port, "protocol": "tcp", "bind": host or "127.0.0.1", "purpose": "paqet SOCKS5"})
        for row in data.get("forward") or []:
            row = _obj(row)
            host, port = _endpoint(row.get("listen", ""))
            if port:
                ports.append(
                    {
                        "port": port,
                        "protocol": row.get("protocol") or "tcp",
                        "bind": host or "127.0.0.1",
                        "purpose": "paqet forward to " + str(row.get("target", "?")),
                        "target": row.get("target"),
                    }
                )
    return {
        "kind": "paqet",
        "role": role,
        "config_path": path,
        "peer": peer,
        "listen": listen,
        "ports": ports,
        "interface": network.get("interface"),
        "local_address": local,
        "router_mac": ipv4.get("router_mac") or ipv6.get("router_mac"),
        "settings": {
            "conn": _obj(data.get("transport")).get("conn"),
            "mode": _obj(_obj(data.get("transport")).get("kcp")).get("mode"),
            "block": _obj(_obj(data.get("transport")).get("kcp")).get("block"),
        },
    }


def _backhaul(path, data, process, listeners):
    server, client = _obj(data.get("server")), _obj(data.get("client"))
    role = "server" if server else "client" if client else None
    if not role:
        return None
    section = server or client
    ports = []
    _, bind = _endpoint(section.get("bind_addr", ""))
    if bind:
        ports.append({"port": bind, "protocol": "tcp", "bind": "0.0.0.0", "purpose": "backhaul control"})
    for entry in section.get("ports") or []:
        match = re.match(r"^(?:[\d.:\[\]a-fA-F]+:)?(\d+)(?:-\d+)?\s*=", str(entry))
        if match and _port(match.group(1)):
            ports.append(
                {"port": int(match.group(1)), "protocol": "tcp", "bind": "0.0.0.0", "purpose": "backhaul forward"}
            )
    return {
        "kind": "backhaul",
        "role": role,
        "config_path": path,
        "peer": section.get("remote_addr"),
        "listen": section.get("bind_addr"),
        "ports": ports,
        "settings": {"transport": section.get("transport")},
    }


def _frp(path, data, process, listeners):
    server_addr = data.get("serverAddr") or data.get("server_addr")
    bind = data.get("bindPort") or data.get("bind_port")
    if not (server_addr or bind):
        return None
    ports = []
    if bind and _port(bind):
        ports.append({"port": int(bind), "protocol": "tcp", "bind": "0.0.0.0", "purpose": "frp server"})
    for row in data.get("proxies") or []:
        remote = _port(_obj(row).get("remotePort") or _obj(row).get("remote_port"))
        if remote:
            ports.append(
                {"port": remote, "protocol": _obj(row).get("type") or "tcp", "bind": "0.0.0.0", "purpose": "frp proxy"}
            )
    return {
        "kind": "frp",
        "role": "client" if server_addr else "server",
        "config_path": path,
        "peer": "{}:{}".format(server_addr, data.get("serverPort") or data.get("server_port")) if server_addr else None,
        "ports": ports,
        "settings": {},
    }


def _gost_args(process):
    args, ports, peers = process.get("args", []), [], []
    for index, arg in enumerate(args):
        value = args[index + 1] if index + 1 < len(args) else ""
        if arg in ("-L", "-F") or arg.startswith(("-L=", "-F=")):
            url = arg.split("=", 1)[1] if "=" in arg else value
            parts = urlsplit(url if "://" in url else "tcp://" + url)
            host, port = parts.hostname or "", parts.port
            if arg.startswith("-L") and port:
                ports.append(
                    {
                        "port": port,
                        "protocol": "udp" if parts.scheme.startswith("udp") else "tcp",
                        "bind": host or "0.0.0.0",
                        "purpose": "gost listener (" + parts.scheme + ")",
                    }
                )
            elif arg.startswith("-F") and port:
                peers.append(f"{host}:{port}")
    if not (ports or peers):
        return None
    return {
        "kind": "gost",
        "role": "client" if peers else "server",
        "config_path": None,
        "peer": peers[0] if peers else None,
        "ports": ports,
        "settings": {},
    }


def _proxy(path, data, process, listeners):
    inbounds, outbounds = data.get("inbounds"), data.get("outbounds")
    if not isinstance(inbounds, list) and not isinstance(outbounds, list):
        return None
    ins, outs = [], []
    for row in inbounds or []:
        row = _obj(row)
        port = _port(row.get("port") or row.get("listen_port"))
        if port:
            ins.append(
                {
                    "tag": row.get("tag"),
                    "protocol": row.get("protocol") or row.get("type"),
                    "listen": row.get("listen") or "0.0.0.0",
                    "port": port,
                    "target": _obj(row.get("settings")).get("address") or row.get("override_address"),
                }
            )
    for row in outbounds or []:
        row = _obj(row)
        settings = _obj(row.get("settings"))
        nodes = settings.get("vnext") or settings.get("servers") or [{}]
        first = _obj(nodes[0] if isinstance(nodes, list) and nodes else {})
        host = first.get("address") or settings.get("address") or row.get("server")
        port = _port(first.get("port") or settings.get("port") or row.get("server_port"))
        if host and port:
            outs.append(
                {
                    "tag": row.get("tag"),
                    "protocol": row.get("protocol") or row.get("type"),
                    "address": str(host),
                    "port": port,
                }
            )
    return {"kind": "proxy", "config_path": path, "inbounds": ins, "outbounds": outs}


def parse_firewall(firewall):
    """Per-port Paqet signature rules, tagged by owner (this panel or someone else)."""
    result = defaultdict(lambda: {"panel": 0, "foreign": 0, "chains": set()})
    for key in ("iptables", "ip6tables"):
        table = None
        for line in (firewall.get(key) or "").splitlines():
            if line.startswith("*"):
                table = line[1:].strip()
                continue
            if not line.startswith("-A "):
                continue
            notrack = table == "raw" and "-j NOTRACK" in line
            rst = table == "mangle" and "--tcp-flags RST RST" in line and "-j DROP" in line
            if not (notrack or rst):
                continue
            match = re.search(r"--(?:dport|sport) (\d+)", line)
            if not match:
                continue
            port = int(match.group(1))
            row = result[port]
            row["panel" if PANEL_RULE_TAG in line else "foreign"] += 1
            row["chains"].add(f"{table}/{line.split()[1]}")
    for line in (firewall.get("nft") or "").splitlines():
        if "notrack" in line or ("rst" in line and "drop" in line):
            for port in re.findall(r"(?:dport|sport) (\d+)", line):
                row = result[int(port)]
                row["panel" if PANEL_RULE_TAG in line else "foreign"] += 1
                row["chains"].add("nft")
    return {port: {**row, "chains": sorted(row["chains"])} for port, row in result.items()}


def _is_loopback(host):
    if host in LOOPBACK:
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def analyze(facts, *, address=None):
    """Return the inventory. ``address`` is the server address used for reservations."""
    processes = facts.get("processes") or []
    listeners = facts.get("listeners") or []
    units = {u.get("name"): u for u in facts.get("units") or []}
    files = {f.get("path"): f for f in facts.get("files") or []}
    firewall = parse_firewall(facts.get("firewall") or {})
    tunnels, proxies, notes = [], [], list(facts.get("notes") or [])

    by_config = {}
    for process in processes:
        for arg in process.get("args", []):
            value = arg.split("=", 1)[-1]
            if value in files:
                by_config.setdefault(value, process)
    for unit in units.values():
        for arg in (unit.get("exec_start") or "").split()[1:]:
            value = arg.split("=", 1)[-1]
            if value in files:
                by_config.setdefault(value, {"name": unit["name"], "pid": None, "args": []})

    seen = set()
    for path, file in files.items():
        fmt, data = _load(file.get("text", ""), path)
        if not data:
            continue
        process = by_config.get(path, {})
        found = None
        if fmt in ("yaml",):
            found = _paqet(path, data, process, listeners) or _frp(path, data, process, listeners)
        if found is None and fmt == "toml":
            found = _backhaul(path, data, process, listeners) or _frp(path, data, process, listeners)
        if found is None and fmt == "json":
            found = _proxy(path, data, process, listeners)
        if found is None:
            continue
        found["process"] = {"pid": process.get("pid"), "name": process.get("name")} if process else None
        found["running"] = bool(process.get("pid")) if process else None
        (proxies if found["kind"] == "proxy" else tunnels).append(found)
        seen.add(path)

    for process in processes:
        kind = _kind_of_name(_name(process))
        if kind == "gost":
            found = _gost_args(process)
            if found:
                found.update(process={"pid": process.get("pid"), "name": process.get("name")}, running=True)
                tunnels.append(found)
        elif kind in ("kcptun", "udp2raw", "wstunnel", "chisel", "rathole", "socat", "ssh"):
            tunnels.append(
                {
                    "kind": kind,
                    "role": "unknown",
                    "config_path": None,
                    "peer": None,
                    "ports": [],
                    "settings": {"args": process.get("args", [])[:12]},
                    "process": {"pid": process.get("pid"), "name": process.get("name")},
                    "running": True,
                }
            )

    for unit in units.values():
        for tunnel in tunnels:
            if tunnel.get("config_path") and tunnel["config_path"] in (unit.get("exec_start") or ""):
                tunnel["unit"] = {"name": unit["name"], "active": unit.get("active"), "enabled": unit.get("enabled")}

    # Evidence from live sockets and firewall for each tunnel.
    for tunnel in tunnels:
        pid = (tunnel.get("process") or {}).get("pid")
        ports = {row["port"] for row in tunnel["ports"]}
        if tunnel["kind"] == "paqet":
            _, peer_port = _endpoint(tunnel.get("peer") or "")
            _, listen_port = _endpoint(tunnel.get("listen") or "")
            raw_port = peer_port if tunnel["role"] == "client" else listen_port
            tunnel["raw_port"] = raw_port
            tunnel["firewall"] = firewall.get(raw_port) if raw_port else None
            ports.discard(raw_port)
        tunnel["sockets"] = [
            {"proto": row["proto"], "bind": row["bind"], "port": row["port"]}
            for row in _listeners_for(listeners, pid, ports)
        ][:20]

    # Links: which proxy/DNS configs talk to a tunnel's local port (loopback) or are its exit target.
    local_ports = {}
    for index, tunnel in enumerate(tunnels):
        for row in tunnel["ports"]:
            local_ports[(row["port"])] = (index, row)
    links = []
    for proxy in proxies:
        for out in proxy["outbounds"]:
            if _is_loopback(out["address"]) and out["port"] in local_ports:
                index, row = local_ports[out["port"]]
                links.append(
                    {
                        "from": proxy["config_path"],
                        "via": "outbound",
                        "tag": out.get("tag"),
                        "tunnel": index,
                        "port": out["port"],
                        "purpose": row["purpose"],
                    }
                )
        for inbound in proxy["inbounds"]:
            for index, tunnel in enumerate(tunnels):
                for row in tunnel["ports"]:
                    _, target_port = _endpoint(row.get("target") or "")
                    if target_port and target_port == inbound["port"]:
                        links.append(
                            {
                                "from": proxy["config_path"],
                                "via": "inbound",
                                "tag": inbound.get("tag"),
                                "tunnel": index,
                                "port": inbound["port"],
                                "purpose": "tunnel exit target",
                            }
                        )
    dns_rows = [
        row
        for row in listeners
        if row.get("port") in DNS_PORTS or any((row.get("name") or "").lower().startswith(n) for n in DNS_NAMES)
    ]
    dns_tunnels = []
    for index, tunnel in enumerate(tunnels):
        for row in tunnel["ports"]:
            _, target_port = _endpoint(row.get("target") or "")
            if target_port in DNS_PORTS or row["port"] in DNS_PORTS:
                dns_tunnels.append(
                    {"tunnel": index, "port": row["port"], "target": row.get("target"), "protocol": row["protocol"]}
                )

    reservations = _reservations(tunnels, proxies, dns_rows, listeners, firewall, address)
    warnings = _warnings(tunnels, proxies, firewall, facts, links)
    return {
        "address": address,
        "privileged": bool(facts.get("privileged")),
        "host": facts.get("host") or {},
        "network": facts.get("network") or {},
        "tunnels": tunnels,
        "proxies": proxies,
        "listening": [
            {"port": row.get("port"), "proto": row.get("proto"), "bind": row.get("bind"), "name": row.get("name")}
            for row in listeners[:400]
        ],
        "dns_listeners": dns_rows[:40],
        "dns_tunnels": dns_tunnels,
        "links": links,
        "firewall_ports": {str(port): row for port, row in firewall.items()},
        "panel_node_present": any(row.get("port") in PANEL_DEFAULT_PORTS for row in listeners),
        "reservations": reservations,
        "warnings": warnings,
        "notes": notes,
    }


def _reservations(tunnels, proxies, dns_rows, listeners, firewall, address):
    out, seen = [], set()

    def add(port, protocol, bind, purpose, owner):
        key = (port, protocol)
        if port and key not in seen:
            seen.add(key)
            out.append({"port": port, "protocol": protocol, "bind": bind, "purpose": purpose, "owner": owner})

    for tunnel in tunnels:
        for row in tunnel["ports"]:
            add(
                row["port"],
                row["protocol"] if row["protocol"] in ("tcp", "udp") else "tcp",
                row["bind"],
                row["purpose"],
                tunnel["kind"],
            )
        if tunnel.get("raw_port"):
            add(tunnel["raw_port"], "tcp", "raw", "paqet raw port (firewall rules attached)", "paqet")
    for proxy in proxies:
        for row in proxy["inbounds"]:
            add(
                row["port"],
                "tcp",
                row["listen"],
                "proxy inbound " + str(row.get("tag") or row.get("protocol") or ""),
                "proxy",
            )
    for row in dns_rows:
        add(row["port"], row["proto"], row["bind"], "DNS listener (%s)" % (row.get("name") or "unknown"), "dns")
    for port, row in firewall.items():
        if row["foreign"]:
            add(port, "tcp", "raw", "port with Paqet-style NOTRACK/RST rules not created by this panel", "firewall")
    return sorted(out, key=lambda item: (item["port"], item["protocol"]))


def _warnings(tunnels, proxies, firewall, facts, links):
    warnings = []
    if not facts.get("privileged"):
        warnings.append(
            {"code": "not_root", "detail": "Run as root so firewall rules and every socket owner are visible."}
        )
    if not (facts.get("firewall") or {}).get("iptables") and not (facts.get("firewall") or {}).get("nft"):
        warnings.append(
            {"code": "firewall_unreadable", "detail": "Firewall rules could not be read; Paqet rules were not checked."}
        )
    for tunnel in tunnels:
        label = "{} {}".format(tunnel["kind"], tunnel.get("role") or "")
        if tunnel.get("running") is False:
            warnings.append(
                {"code": "config_without_process", "detail": label + ": configuration found but no running process."}
            )
        if tunnel["kind"] == "paqet" and tunnel.get("raw_port"):
            row = tunnel.get("firewall")
            if not row or not row["foreign"] and not row["panel"]:
                warnings.append(
                    {
                        "code": "paqet_firewall_missing",
                        "detail": "Paqet on port {} has no NOTRACK/RST-drop rules; it may be failing or managed elsewhere.".format(
                            tunnel["raw_port"]
                        ),
                    }
                )
            if row and row["panel"] and row["foreign"]:
                warnings.append(
                    {
                        "code": "paqet_firewall_shared",
                        "detail": "Port {} has rules from both this panel and another source.".format(
                            tunnel["raw_port"]
                        ),
                    }
                )
            if tunnel["raw_port"] in (80, 443, 53):
                warnings.append(
                    {
                        "code": "paqet_standard_port",
                        "detail": "Paqet raw port {} is a standard service port; its firewall rules can break normal traffic.".format(
                            tunnel["raw_port"]
                        ),
                    }
                )
        if tunnel.get("config_path") and tunnel.get("unit") is None and tunnel.get("running"):
            warnings.append(
                {
                    "code": "no_service_unit",
                    "detail": label + " runs without a systemd unit; it will not survive a reboot.",
                }
            )
    paqet = [t for t in tunnels if t["kind"] == "paqet"]
    ports = [t.get("raw_port") for t in paqet if t.get("raw_port")]
    if len(ports) != len(set(ports)):
        warnings.append({"code": "paqet_duplicate_port", "detail": "Two Paqet instances share the same raw port."})
    return warnings
