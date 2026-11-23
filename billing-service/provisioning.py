"""Server-side PasarGuard account creation for paid guest orders."""

import json
import os
import re
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import NAMESPACE_URL, uuid5


class PanelError(Exception):
    def __init__(self, status=None):
        self.status = status
        super().__init__("PasarGuard API request failed")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def credentials():
    key = os.getenv("PANEL_PROVISION_API_KEY", "").strip()
    raw_groups = os.getenv("PANEL_PROVISION_GROUP_IDS", "").strip()
    if (not re.fullmatch(r"[!-~]{10,2048}", key)
            or not re.fullmatch(r"[1-9][0-9]{0,8}(?:\s*,\s*[1-9][0-9]{0,8}){0,31}", raw_groups)):
        return None
    return key, sorted({int(value) for value in raw_groups.split(",")})


def panel_request(path, api_key, *, payload=None, method=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = Request(
        os.environ["PANEL_URL"] + path, data, method=method,
        headers={"X-API-Key": api_key, "Accept": "application/json", "Content-Type": "application/json"},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=8) as response:
            result = json.loads(response.read(262_144))
    except HTTPError as error:
        raise PanelError(error.code) from None
    except (URLError, OSError, ValueError):
        raise PanelError() from None
    if not isinstance(result, dict):
        raise PanelError()
    return result


@lru_cache(maxsize=4)
def available_groups(api_key, _minute):
    result = panel_request("/api/groups/simple", api_key)
    groups = result.get("groups")
    if not isinstance(groups, list):
        raise PanelError()
    return {group["id"]: group["name"] for group in groups if isinstance(group, dict)
            and type(group.get("id")) is int and isinstance(group.get("name"), str)}


def assert_available(config, minute):
    if not config or not set(config[1]).issubset(available_groups(config[0], minute)):
        raise PanelError()


def panel_username(order_id):
    return "pg_" + order_id[:28]


def subscription_url(order_id, account):
    panel = os.environ["PANEL_URL"]
    url = account.get("subscription_url")
    if account.get("username") != panel_username(order_id) or account.get("note") != f"PasarGuard Plus order {order_id}":
        raise PanelError()
    if not isinstance(url, str):
        raise PanelError()
    resolved = urlsplit(urljoin(panel + "/", url))
    allowed = urlsplit(panel)
    if ((resolved.scheme, resolved.netloc) != (allowed.scheme, allowed.netloc)
            or not re.fullmatch(r"/sub/[A-Za-z0-9_~.-]{4,1024}/?", resolved.path)
            or resolved.query or resolved.fragment):
        raise PanelError()
    return resolved.geturl()


def read_subscription(order_id, config):
    if not config:
        raise PanelError()
    account = panel_request("/api/user/by-username/" + panel_username(order_id), config[0])
    return subscription_url(order_id, account)


def provision(order_id, plan, config):
    if not config:
        raise PanelError()
    api_key, group_ids = config
    username = panel_username(order_id)
    try:
        return read_subscription(order_id, config)
    except PanelError as error:
        if error.status != 404:
            raise
    try:
        account = panel_request("/api/user", api_key, payload={
            "username": username,
            "note": f"PasarGuard Plus order {order_id}",
            "status": "active",
            "expire": (datetime.now(UTC) + timedelta(days=plan["duration_days"])).isoformat(),
            "data_limit": (plan["data_limit_gb"] or 0) * 1024**3,
            "data_limit_reset_strategy": "no_reset",
            "group_ids": group_ids,
            "hwid_limit": plan["max_devices"],
            "proxy_settings": {"vless": {"id": str(uuid5(NAMESPACE_URL, "pasarguard:" + order_id))}},
        })
    except PanelError as error:
        if error.status != 409:
            raise
        return read_subscription(order_id, config)
    return subscription_url(order_id, account)


def refund_account(order_id, config):
    if not config:
        raise PanelError()
    account = panel_request("/api/user/by-username/" + panel_username(order_id), config[0])
    if account.get("username") != panel_username(order_id) or account.get("note") != f"PasarGuard Plus order {order_id}":
        raise PanelError()
    return account


def disable_for_refund(order_id, config, account):
    if account.get("status") == "disabled":
        return
    response = panel_request("/api/user/by-username/" + panel_username(order_id), config[0],
                             method="PUT", payload={"status": "disabled"})
    if (response.get("status") != "disabled" or response.get("username") != panel_username(order_id)
            or response.get("note") != f"PasarGuard Plus order {order_id}"):
        raise PanelError()


def renewal_account(username, config):
    if not config or not re.fullmatch(r"[A-Za-z0-9_.@-]{1,128}", username) or username in (".", ".."):
        raise PanelError()
    account = panel_request(f"/api/user/by-username/{username}", config[0])
    if account.get("username") != username:
        raise PanelError()
    return account


def expiration_ms(value):
    try:
        if type(value) is int:
            return value if value >= 1_000_000_000_000 else value * 1000
        if isinstance(value, str):
            stamp = datetime.fromisoformat(value)
            if stamp.tzinfo is not None:
                return int(stamp.timestamp() * 1000)
    except (ValueError, OverflowError):
        pass
    raise PanelError()


def renewal_changes(account, plan, current):
    changes = {}
    previous_expire = account.get("expire")
    start = max(current, expiration_ms(previous_expire)) if previous_expire else current
    if previous_expire or account.get("status") == "expired":
        changes["expire"] = (datetime.fromtimestamp(start / 1000, UTC) + timedelta(days=plan["duration_days"])).isoformat()
    old_limit = account.get("data_limit")
    used = account.get("used_traffic")
    if (old_limit is not None and type(old_limit) is not int) or type(used) is not int:
        raise PanelError()
    if plan["data_limit_gb"] is None:
        changes["data_limit"] = 0
    elif old_limit:
        changes["data_limit"] = max(old_limit, used) + plan["data_limit_gb"] * 1024**3
    if plan["max_devices"] is not None:
        changes["hwid_limit"] = max(account.get("hwid_limit") or 0, plan["max_devices"])
    if account.get("status") in ("expired", "limited"):
        changes["status"] = "active"
    return changes, start


def apply_renewal(username, config, account, changes):
    if not changes:
        return
    remaining = {key: value for key, value in changes.items() if account.get(key) != value}
    if ("expire" in remaining and account.get("expire")
            and expiration_ms(account["expire"]) >= expiration_ms(remaining["expire"])):
        remaining.pop("expire")
    for key in ("data_limit", "hwid_limit"):
        if key in remaining and (account.get(key) or 0) >= remaining[key] > 0:
            remaining.pop(key)
    if remaining:
        result = panel_request(f"/api/user/by-username/{username}", config[0], method="PUT", payload=remaining)
        if result.get("username") != username:
            raise PanelError()
        for key, expected in remaining.items():
            actual = result.get(key)
            if key == "expire":
                if expiration_ms(actual) < expiration_ms(expected):
                    raise PanelError()
            elif actual != expected:
                raise PanelError()
