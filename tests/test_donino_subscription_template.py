from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.db.models import DataLimitResetStrategy, UserStatus
from app.templates import render_template


def context(status="active", limit=1000, used=250):
    return {
        "user": SimpleNamespace(
            username="sample <script>alert(1)</script>",
            status=UserStatus(status),
            data_limit=limit,
            used_traffic=used,
            data_limit_reset_strategy=DataLimitResetStrategy.no_reset,
            expire=datetime.now(UTC) + timedelta(days=3),
            on_hold_expire_duration=86400,
            on_hold_timeout=None,
        ),
        "links": ["vless://example#%D8%A7%D8%AA%D8%B5%D8%A7%D9%84"],
        "apps": [],
        "announce": "",
        "announce_url": None,
    }


@pytest.mark.parametrize("status", ["active", "on_hold", "expired", "limited", "disabled"])
def test_subscription_status_links_and_escaping(status):
    html = render_template("subscription/index.html", context(status))
    assert "<script>alert(1)</script>" not in html
    assert "&lt;script&gt;" in html
    assert ('class="link-input"' in html) == (status in ("active", "on_hold"))
    assert 'lang="fa" dir="rtl"' in html


@pytest.mark.parametrize("limit,used,percentage", [(None, 250, 0), (0, 50, 0), (100, 1000, 100), (100, 0, 0)])
def test_usage_handles_unlimited_and_over_limit(limit, used, percentage):
    html = render_template("subscription/index.html", context(limit=limit, used=used))
    assert f"--usage:{float(percentage)}" in html or f"--usage:{percentage}" in html
    assert "nan" not in html.lower()


def test_empty_links_nullable_reset_and_unsafe_urls():
    values = context()
    values["user"].data_limit_reset_strategy = None
    values.update(links=[], announce="<img src=x onerror=alert(1)>", announce_url="javascript:alert(1)")
    html = render_template("subscription/index.html", values)
    assert "هنوز اتصالی" in html
    assert 'href="javascript:' not in html
    assert "<img src=x" not in html
