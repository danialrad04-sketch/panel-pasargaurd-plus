#!/usr/bin/env python3
"""Read-only server inspection for tunnel discovery. Standard library only.

Sent to a server over the pinned SSH session (or run by hand: ``sudo python3 collector.py``) and prints ONE JSON
document on stdout. It never writes, restarts, loads or changes anything:

* files are only opened for reading, commands are fixed argument lists without a shell and with timeouts;
* configuration text is redacted here, before it leaves the server (keys, tokens, passwords, UUIDs, URL logins);
* only tunnel, proxy and DNS related processes, units and configuration files are reported.
"""

import glob
import json
import os
import platform
import re
import socket
import struct
import subprocess
import sys
import time

VERSION = 1
MAX_FILES = 40
MAX_FILE_BYTES = 256 * 1024
MAX_TOTAL_BYTES = 1536 * 1024
MAX_LISTENERS = 2000
MAX_FIREWALL_BYTES = 128 * 1024

# Executable base names worth reporting. Matching is on the exact name or a prefix before "-" / "_" / digits.
INTEREST = (
    "paqet|backhaul|gost|frpc|frps|frp|xray|v2ray|sing-box|singbox|hysteria|hysteria2|tuic|trojan|trojan-go|"
    "unbound|dnsmasq|smartdns|coredns|named|stubby|dnscrypt-proxy|stunnel|stunnel4|socat|autossh|udp2raw|"
    "kcptun|client_linux|server_linux|ss-server|ss-local|ssserver|sslocal|shadowsocks|openvpn|cloudflared|"
    "haproxy|nginx|caddy|pasarguard|pasarguard-node|marzban|marzban-node|x-ui|3x-ui|wstunnel|chisel|rathole|"
    "gost-linux|backhaul-linux|paqet-linux"
)
INTEREST_RE = re.compile(rf"^(?:{INTEREST})(?:[-_.0-9a-z]*)?$")
CONFIG_SUFFIXES = (".yaml", ".yml", ".json", ".toml", ".ini", ".conf", ".cfg")
KNOWN_CONFIGS = (
    "/etc/paqet/*",
    "/opt/paqet/*",
    "/root/paqet/*",
    "/usr/local/etc/paqet/*",
    "/etc/xray/*.json",
    "/usr/local/etc/xray/*.json",
    "/usr/local/etc/v2ray/*.json",
    "/etc/v2ray/*.json",
    "/etc/gost/*",
    "/opt/gost/*",
    "/etc/backhaul/*",
    "/opt/backhaul/*",
    "/etc/frp/*",
    "/opt/frp/*",
    "/etc/unbound/unbound.conf",
    "/etc/unbound/unbound.conf.d/*.conf",
    "/etc/dnsmasq.conf",
    "/etc/dnsmasq.d/*.conf",
    "/etc/smartdns/*.conf",
    "/etc/hysteria/*",
    "/etc/sing-box/*.json",
    "/opt/sing-box/*.json",
    "/etc/x-ui/*.json",
    "/usr/local/x-ui/bin/config.json",
)
SKIP_PATH = re.compile(r"(/\.ssh/|/shadow|/gshadow|/sudoers|/etc/ssh/|id_rsa|id_ed25519|\.pem$|\.key$)")
SECRET_KEY = re.compile(
    r"(token|passw|secret|psk|private|passphrase|authkey|auth_key|apikey|api_key|credential|cookie)", re.IGNORECASE
)
SECRET_EXACT = {"key", "id", "uuid", "pass", "auth", "shortid", "shortids", "sid", "seed", "obfs", "salt"}
LINE_RE = re.compile(r"""^(\s*(?:-\s*)?["']?)([A-Za-z0-9_.\-]+)(["']?\s*[:=]\s*)(.*)$""")
JSON_RE = re.compile(
    r'("(?P<k>[A-Za-z0-9_.\-]+)"\s*:\s*)(?P<v>"(?:[^"\\]|\\.)*"|\[\s*(?:"(?:[^"\\]|\\.)*"\s*,?\s*)*\])'
)
URL_LOGIN_RE = re.compile(r"(?P<scheme>[A-Za-z][A-Za-z0-9+.\-]*://)[^/@\s]+@")
SECRET_FLAG = re.compile(
    r"^-{1,2}(k|key|token|password|passwd|pass|secret|psk|auth|passphrase|uuid|id)$", re.IGNORECASE
)
SECRET_FLAG_EQ = re.compile(
    r"^(-{1,2}(?:k|key|token|password|passwd|pass|secret|psk|auth|passphrase|uuid))=", re.IGNORECASE
)


def is_secret_name(name):
    lowered = name.lower()
    return lowered in SECRET_EXACT or bool(SECRET_KEY.search(lowered))


def redact_text(text):
    """Mask secret values in YAML/TOML/INI/JSON/conf text. Structure and ports stay readable."""

    def json_sub(match):
        if not is_secret_name(match.group("k")):
            return match.group(0)
        value = match.group("v")
        return match.group(1) + ('["***"]' if value.startswith("[") else '"***"')

    out = []
    for line in text.splitlines():
        line = URL_LOGIN_RE.sub(lambda m: m.group("scheme") + "***@", line)
        if "{" in line or '"' in line and ":" in line and line.lstrip().startswith(('"', "{")):
            line = JSON_RE.sub(json_sub, line)
        match = LINE_RE.match(line)
        if match and is_secret_name(match.group(2)):
            value = match.group(4).strip()
            if value and not value.startswith(("#", "[", "{", "|", ">")) and value not in ('""', "''"):
                comma = "," if value.rstrip().endswith(",") else ""
                line = match.group(1) + match.group(2) + match.group(3) + '"***"' + comma
        out.append(line)
    return "\n".join(out)


def redact_args(args):
    result, skip = [], False
    for arg in args:
        if skip:
            result.append("***")
            skip = False
            continue
        eq = SECRET_FLAG_EQ.match(arg)
        if eq:
            result.append(eq.group(1) + "=***")
            continue
        if SECRET_FLAG.match(arg):
            result.append(arg)
            skip = True
            continue
        result.append(URL_LOGIN_RE.sub(lambda m: m.group("scheme") + "***@", arg)[:512])
    return result[:64]


def run(command, timeout=6):
    try:
        done = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"},
        )
        return done.returncode, done.stdout, done.stderr
    except (OSError, subprocess.SubprocessError):
        return 127, "", ""


def read_small(path, limit=MAX_FILE_BYTES):
    try:
        with open(path, "rb") as handle:
            raw = handle.read(limit + 1)
    except OSError:
        return None, False
    return raw[:limit].decode("utf-8", "replace"), len(raw) > limit


def interesting(name):
    return bool(name) and bool(INTEREST_RE.match(name.lower()))


def collect_processes():
    rows = []
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        base = "/proc/" + entry
        try:
            with open(base + "/cmdline", "rb") as handle:
                argv = [part.decode("utf-8", "replace") for part in handle.read(16384).split(b"\0") if part]
            if not argv:
                continue
            try:
                exe = os.readlink(base + "/exe")
            except OSError:
                exe = ""
            try:
                cwd = os.readlink(base + "/cwd")
            except OSError:
                cwd = ""
            name = os.path.basename(exe or argv[0]).replace(" (deleted)", "")
            argv0 = os.path.basename(argv[0])
            via_interpreter = False
            if not interesting(name) and not interesting(argv0):
                # "python3 /opt/tool/paqet.py", "sh -c ..." wrappers: look at the script name only.
                script = next((os.path.basename(a) for a in argv[1:3] if not a.startswith("-")), "")
                if not interesting(os.path.splitext(script)[0]):
                    continue
                via_interpreter = True
            uid = None
            try:
                uid = os.stat(base).st_uid
            except OSError:
                pass
            rows.append(
                {
                    "pid": int(entry),
                    "name": name,
                    "exe": exe,
                    "cwd": cwd,
                    "uid": uid,
                    "args": redact_args(argv[1:]),
                    "via_interpreter": via_interpreter,
                    "containerized": _in_container(base),
                }
            )
        except OSError:
            continue
        if len(rows) >= 200:
            break
    return rows


def _in_container(base):
    text, _ = read_small(base + "/cgroup", 4096)
    return bool(text and re.search(r"(docker|containerd|kubepods|libpod|lxc)", text))


def _ipv4(hexvalue):
    return socket.inet_ntoa(struct.pack("<I", int(hexvalue, 16)))


def _ipv6(hexvalue):
    raw = b"".join(struct.pack("<I", int(hexvalue[i : i + 8], 16)) for i in range(0, 32, 8))
    return socket.inet_ntop(socket.AF_INET6, raw)


def socket_owners():
    """Map socket inode -> (pid, name). Needs root to see other users' descriptors."""
    owners = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            names = os.listdir(f"/proc/{entry}/fd")
        except OSError:
            continue
        process = None
        for fd in names:
            try:
                target = os.readlink(f"/proc/{entry}/fd/{fd}")
            except OSError:
                continue
            if target.startswith("socket:["):
                if process is None:
                    text, _ = read_small(f"/proc/{entry}/comm", 64)
                    process = (int(entry), (text or "").strip())
                owners[int(target[8:-1])] = process
    return owners


def collect_listeners():
    owners = socket_owners()
    rows = []
    for proto, family in (("tcp", 4), ("tcp", 6), ("udp", 4), ("udp", 6)):
        path = "/proc/net/{}{}".format(proto, "6" if family == 6 else "")
        text, _ = read_small(path, 2 * 1024 * 1024)
        if not text:
            continue
        for line in text.splitlines()[1:]:
            parts = line.split()
            if len(parts) < 10:
                continue
            if proto == "tcp" and parts[3] != "0A":  # LISTEN only
                continue
            address, _, port = parts[1].rpartition(":")
            try:
                bind = _ipv4(address) if family == 4 else _ipv6(address)
                port = int(port, 16)
            except (ValueError, OSError):
                continue
            if proto == "udp" and port == 0:
                continue
            owner = owners.get(int(parts[9]))
            rows.append(
                {
                    "proto": proto,
                    "bind": bind,
                    "port": port,
                    "pid": owner[0] if owner else None,
                    "name": owner[1] if owner else None,
                }
            )
            if len(rows) >= MAX_LISTENERS:
                return rows
    return rows


def collect_units():
    rows, seen = [], set()
    for directory in ("/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system"):
        for path in sorted(glob.glob(directory + "/*.service"))[:3000]:
            name = os.path.basename(path)
            if name in seen:
                continue
            text, _ = read_small(path, 16384)
            if not text:
                continue
            exec_line = next((l.split("=", 1)[1].strip() for l in text.splitlines() if l.startswith("ExecStart=")), "")
            parts = exec_line.lstrip("-@+!").split()
            binary = os.path.basename(parts[0]) if parts else ""
            script = next((os.path.basename(p) for p in parts[1:3] if not p.startswith("-")), "")
            if not (
                interesting(binary) or interesting(os.path.splitext(script)[0]) or interesting(name[: -len(".service")])
            ):
                continue
            seen.add(name)
            workdir = next(
                (l.split("=", 1)[1].strip() for l in text.splitlines() if l.startswith("WorkingDirectory=")), ""
            )
            rows.append(
                {
                    "name": name,
                    "path": path,
                    "exec_start": " ".join(redact_args(parts)),
                    "working_directory": workdir,
                    "active": None,
                    "enabled": None,
                }
            )
            if len(rows) >= 60:
                break
    for row in rows[:40]:
        _code, out, _ = run(["systemctl", "is-active", row["name"]], 4)
        row["active"] = out.strip() or None
        _code, out, _ = run(["systemctl", "is-enabled", row["name"]], 4)
        row["enabled"] = out.strip() or None
    return rows


def config_candidates(processes, units):
    paths = []

    def add(path, base=""):
        if not path:
            return
        if not os.path.isabs(path):
            path = os.path.join(base or "/", path)
        path = os.path.normpath(path)
        if path.endswith(CONFIG_SUFFIXES) and path not in paths:
            paths.append(path)

    for process in processes:
        if process["containerized"]:
            continue
        for arg in process["args"]:
            add(arg.split("=", 1)[-1], process["cwd"])
    for unit in units:
        for arg in unit["exec_start"].split()[1:]:
            add(arg.split("=", 1)[-1], unit["working_directory"])
    for pattern in KNOWN_CONFIGS:
        for path in sorted(glob.glob(pattern))[:20]:
            add(path)
    return paths


def collect_files(paths):
    rows, total = [], 0
    for path in paths:
        if len(rows) >= MAX_FILES or total >= MAX_TOTAL_BYTES:
            break
        real = os.path.realpath(path)
        if SKIP_PATH.search(path) or SKIP_PATH.search(real) or not os.path.isfile(real):
            continue
        text, truncated = read_small(real)
        if text is None:
            continue
        total += len(text)
        rows.append({"path": path, "bytes": len(text), "truncated": truncated, "text": redact_text(text)})
    return rows


def collect_firewall():
    result, errors = {}, []
    for key, command in (
        ("iptables", ["iptables-save"]),
        ("ip6tables", ["ip6tables-save"]),
        ("nft", ["nft", "list", "ruleset"]),
    ):
        code, out, err = run(command, 8)
        if code == 127:
            continue
        if code:
            errors.append("{}: {}".format(key, (err or "failed").strip()[:200]))
            continue
        result[key] = out[:MAX_FIREWALL_BYTES]
    if errors:
        result["errors"] = errors
    return result


def collect_network():
    interfaces = []
    code, out, _ = run(["ip", "-j", "addr", "show"], 6)
    if code == 0:
        try:
            for item in json.loads(out):
                interfaces.append(
                    {
                        "name": item.get("ifname"),
                        "mac": item.get("address"),
                        "state": item.get("operstate"),
                        "addrs": [
                            "{}/{}".format(a.get("local"), a.get("prefixlen"))
                            for a in item.get("addr_info", [])
                            if a.get("scope") == "global"
                        ],
                    }
                )
        except ValueError:
            pass
    gateway = interface = None
    text, _ = read_small("/proc/net/route", 65536)
    for line in (text or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) > 3 and parts[1] == "00000000" and int(parts[3], 16) & 2:
            interface, gateway = parts[0], _ipv4(parts[2])
            break
    mac = None
    text, _ = read_small("/proc/net/arp", 65536)
    for line in (text or "").splitlines()[1:]:
        parts = line.split()
        if gateway and len(parts) > 3 and parts[0] == gateway and parts[3] != "00:00:00:00:00:00":
            mac = parts[3]
    return {"interfaces": interfaces[:32], "default_interface": interface, "gateway": gateway, "gateway_mac": mac}


def main():
    notes = []
    if hasattr(os, "geteuid") and os.geteuid() != 0:
        notes.append("not_root: firewall rules and other users' sockets may be missing")
    processes = collect_processes()
    units = collect_units()
    document = {
        "version": VERSION,
        "collected_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "privileged": hasattr(os, "geteuid") and os.geteuid() == 0,
        "host": {"hostname": socket.gethostname(), "kernel": platform.release(), "arch": platform.machine()},
        "processes": processes,
        "listeners": collect_listeners(),
        "units": units,
        "files": collect_files(config_candidates(processes, units)),
        "firewall": collect_firewall(),
        "network": collect_network(),
        "notes": notes,
    }
    sys.stdout.write(json.dumps(document, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
