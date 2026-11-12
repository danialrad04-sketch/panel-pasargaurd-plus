import json

from app.discovery.analyze import analyze, parse_firewall
from tests.test_discovery_facts import (
    FOREIGN_RULES,
    GERMANY_PAQET,
    GERMANY_XRAY,
    IRAN_PAQET,
    IRAN_XRAY,
    collector,
    file_row,
    iran_facts,
)


def test_collector_redacts_secrets_but_keeps_structure():
    text = file_row("/etc/paqet/client.yaml", IRAN_PAQET)["text"]
    assert "very-secret-kcp-key" not in text and "p@ssw0rd" not in text
    assert '"203.0.113.9:9999"' in text
    xray = file_row("/x.json", IRAN_XRAY)["text"]
    assert "b831381d" not in xray
    json.loads(xray)
    assert collector.redact_args(["-L", "socks5://u:pw@:1080", "--key", "abc"]) == [
        "-L",
        "socks5://***@:1080",
        "--key",
        "***",
    ]


def test_iran_client_is_found_with_peer_ports_and_links():
    result = analyze(iran_facts(), address="198.51.100.7")
    paqet = next(t for t in result["tunnels"] if t["kind"] == "paqet")
    assert paqet["role"] == "client" and paqet["peer"] == "203.0.113.9:9999" and paqet["raw_port"] == 9999
    assert paqet["running"] is True and paqet["unit"]["active"] == "active"
    assert {row["port"] for row in paqet["ports"]} == {1080, 5300, 8443}
    # xray's outbound to loopback 1080 is recognised as riding the tunnel
    assert any(link["via"] == "outbound" and link["port"] == 1080 for link in result["links"])
    # the DNS forward (udp -> 53) is reported as a DNS tunnel
    assert any(row["port"] == 5300 for row in result["dns_tunnels"])
    reserved = {(row["port"], row["protocol"]) for row in result["reservations"]}
    assert {(1080, "tcp"), (5300, "udp"), (9999, "tcp"), (443, "tcp"), (53, "udp")} <= reserved
    assert not any(w["code"] in ("paqet_firewall_missing", "not_root") for w in result["warnings"])


def test_germany_server_exit_target_is_linked_to_inbound():
    facts = {
        "privileged": True,
        "processes": [
            {"pid": 1, "name": "paqet", "args": ["run", "-c", "/etc/paqet/server.yaml"], "containerized": False}
        ],
        "listeners": [],
        "files": [file_row("/etc/paqet/server.yaml", GERMANY_PAQET), file_row("/etc/xray/config.json", GERMANY_XRAY)],
        "firewall": {},
    }
    result = analyze(facts, address="203.0.113.9")
    paqet = next(t for t in result["tunnels"] if t["kind"] == "paqet")
    assert paqet["role"] == "server" and paqet["raw_port"] == 9999
    assert any(w["code"] == "firewall_unreadable" for w in result["warnings"])


def test_firewall_rules_are_attributed_to_panel_or_foreign():
    rules = FOREIGN_RULES + FOREIGN_RULES.replace(
        "-j NOTRACK", "-m comment --comment pg-plus-paqet-abc -j NOTRACK"
    ).replace("9999", "11082")
    rows = parse_firewall({"iptables": rules})
    assert rows[9999]["foreign"] == 3 and rows[9999]["panel"] == 0
    assert rows[11082]["panel"] == 2 and rows[11082]["foreign"] == 1


def test_missing_process_and_shared_standard_port_are_flagged():
    facts = iran_facts("")
    facts["processes"] = [p for p in facts["processes"] if p["name"] != "paqet_linux_amd64"]
    facts["files"][0]["text"] = facts["files"][0]["text"].replace("203.0.113.9:9999", "203.0.113.9:443")
    result = analyze(facts, address="198.51.100.7")
    codes = {w["code"] for w in result["warnings"]}
    assert {"config_without_process", "paqet_firewall_missing", "paqet_standard_port"} <= codes


def test_garbage_input_never_raises():
    assert analyze({}, address=None)["tunnels"] == []
    result = analyze(
        {
            "files": [{"path": "/a.yaml", "text": "::: not yaml"}, {"path": "/b.json", "text": "{"}],
            "processes": [{"name": 1}],
        }
    )
    assert result["tunnels"] == []
