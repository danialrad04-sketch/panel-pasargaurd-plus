import importlib.util
import json
from pathlib import Path

_spec = importlib.util.spec_from_file_location("collector", Path("app/discovery/collector.py"))
collector = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collector)

IRAN_PAQET = """role: "client"
log:
  level: "warn"
socks5:
  - listen: "127.0.0.1:1080"
    username: "bob"
    password: "p@ssw0rd"
forward:
  - listen: "127.0.0.1:5300"
    target: "127.0.0.1:53"
    protocol: "udp"
  - listen: "127.0.0.1:8443"
    target: "127.0.0.1:2443"
    protocol: "tcp"
network:
  interface: "eth0"
  ipv4:
    addr: "198.51.100.7:0"
    router_mac: "aa:bb:cc:dd:ee:01"
server:
  addr: "203.0.113.9:9999"
transport:
  protocol: "kcp"
  conn: 1
  kcp:
    mode: "fast"
    block: "aes-128-gcm"
    key: "very-secret-kcp-key"
"""
IRAN_XRAY = json.dumps(
    {
        "inbounds": [
            {
                "tag": "users",
                "protocol": "vless",
                "port": 443,
                "settings": {"clients": [{"id": "b831381d-6324-4d53-ad4f-8cda48b30811"}]},
            }
        ],
        "outbounds": [
            {
                "tag": "to-germany",
                "protocol": "socks",
                "settings": {"servers": [{"address": "127.0.0.1", "port": 1080}]},
            },
            {"tag": "direct", "protocol": "freedom"},
        ],
    },
    indent=2,
)
GERMANY_PAQET = """role: "server"
listen:
  addr: ":9999"
network:
  interface: "eth0"
  ipv4:
    addr: "203.0.113.9:9999"
    router_mac: "aa:bb:cc:dd:ee:02"
transport:
  protocol: "kcp"
  kcp:
    key: "very-secret-kcp-key"
"""
GERMANY_XRAY = json.dumps(
    {
        "inbounds": [{"tag": "from-iran", "protocol": "vless", "port": 2443, "listen": "127.0.0.1"}],
        "outbounds": [{"protocol": "freedom"}],
    }
)
FOREIGN_RULES = """*raw
-A PREROUTING -s 203.0.113.9/32 -d 198.51.100.7/32 -p tcp -m tcp --sport 9999 -j NOTRACK
-A OUTPUT -s 198.51.100.7/32 -d 203.0.113.9/32 -p tcp -m tcp --dport 9999 -j NOTRACK
COMMIT
*mangle
-A OUTPUT -s 198.51.100.7/32 -d 203.0.113.9/32 -p tcp -m tcp --dport 9999 --tcp-flags RST RST -j DROP
COMMIT
"""


def file_row(path, text):
    return {"path": path, "bytes": len(text), "truncated": False, "text": collector.redact_text(text)}


def iran_facts(rules=FOREIGN_RULES):
    return {
        "privileged": True,
        "processes": [
            {
                "pid": 410,
                "name": "paqet_linux_amd64",
                "args": ["run", "-c", "/etc/paqet/client.yaml"],
                "containerized": False,
            },
            {
                "pid": 520,
                "name": "xray",
                "args": ["run", "-c", "/usr/local/etc/xray/config.json"],
                "containerized": False,
            },
        ],
        "listeners": [
            {"proto": "tcp", "bind": "127.0.0.1", "port": 1080, "pid": 410, "name": "paqet"},
            {"proto": "udp", "bind": "127.0.0.1", "port": 5300, "pid": 410, "name": "paqet"},
            {"proto": "tcp", "bind": "0.0.0.0", "port": 443, "pid": 520, "name": "xray"},
            {"proto": "udp", "bind": "127.0.0.1", "port": 53, "pid": 700, "name": "unbound"},
        ],
        "units": [
            {
                "name": "paqet.service",
                "exec_start": "/usr/local/bin/paqet run -c /etc/paqet/client.yaml",
                "active": "active",
                "enabled": "enabled",
            }
        ],
        "files": [
            file_row("/etc/paqet/client.yaml", IRAN_PAQET),
            file_row("/usr/local/etc/xray/config.json", IRAN_XRAY),
        ],
        "firewall": {"iptables": rules},
    }
