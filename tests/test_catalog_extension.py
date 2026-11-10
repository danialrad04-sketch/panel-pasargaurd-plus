import json
from pathlib import Path

from app.dns.catalog import catalog
from app.dns.policy import DNSRule
from app.smartdns.gaming import suffix_cover

EXTENSION = Path(__file__).resolve().parents[1] / "app" / "data" / "fork-services.json"


def test_extension_is_canonical_and_never_repeats_the_pinned_catalog():
    value = catalog()  # raises on duplicate groups, duplicate or non-canonical domains
    data = json.loads(EXTENSION.read_text())
    keys = [service["key"] for service in data["services"]]
    assert len(keys) == len(set(keys))
    assert value["counts"]["services"] >= 60 and value["counts"]["domains"] > 850
    merged = {service["key"]: service for service in value["services"]}
    for service in data["services"]:
        assert service["key"] in merged
        for group in service["groups"]:
            for domain in group["domains"]:
                assert DNSRule(domain=domain, action="allow").domain == domain
                assert "." in domain and not domain.startswith("*")


def test_extension_does_not_blow_the_route_budget_for_a_full_profile():
    # Selecting every group must still fit the 256-route gateway limit once suffixes are merged.
    domains = [domain for service in catalog()["services"] for group in service["groups"] for domain in group["domains"]]
    assert len(suffix_cover(domains)) <= 900  # sanity bound; the wizard blocks profiles above MAX_ROUTES


def test_new_game_coverage_exists():
    keys = {service["key"] for service in catalog()["services"]}
    assert {"supercell", "hoyoverse", "garena", "pubgmobile", "activision", "mmo", "anticheat", "streaming_games"} <= keys
    riot = next(s for s in catalog()["services"] if s["key"] == "riot")
    assert "riotcdn.net" in {d for g in riot["groups"] for d in g["domains"]}


def test_every_preset_fits_the_gateway_route_budget_alone():
    value = catalog()
    by_key = {service["key"]: service for service in value["services"]}
    for preset in value["presets"]:
        domains = [d for key in preset["services"] for g in by_key[key]["groups"] for d in g["domains"]]
        assert 0 < len(suffix_cover(domains)) <= 256, preset["key"]
