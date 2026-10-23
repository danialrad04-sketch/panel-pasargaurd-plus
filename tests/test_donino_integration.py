"""Contract, privilege and socket checks for the opt-in Donino adapter."""

import os
from unittest.mock import AsyncMock, patch

import pytest
from aiohttp import web
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from app.integrations.donino import (
    MAX_RESPONSE,
    BridgeError,
    PreviewInput,
    StartInput,
    State,
    TunnelClient,
)
from app.models.admin import AdminDetails, AdminRoleData
from app.routers.authentication import get_current
from app.routers.tunnel import router
from config import FeatureSettings, feature_settings

PLAN = "a" * 32
SPEC = {
    "profile_id": PLAN,
    "iran": "iran.example.com",
    "services": [{"id": "vpn", "listen_port": 443, "target_port": 8443}],
    "transports": ["frp-wss"],
}


@pytest.mark.parametrize(
    "changes",
    [
        {"iran": "https://example.com"},
        {"iran": "bad..example"},
        {"iran": "example.com:22"},
        {"transports": ["frp-wss", "frp-wss"]},
        {"transports": ["unknown"]},
        {"transports": ["paqet"]},
        {"base_port": "30000"},
        {"open_ufw": True},
        {"browser_port": 443},
        {"services": [{"id": "vpn", "listen_port": 443, "target_port": 8443}] * 2},
    ],
)
def test_invalid_preview_is_rejected(changes):
    with pytest.raises(ValidationError):
        PreviewInput(**(SPEC | changes))


def test_spec_keeps_loopback_and_firewall_policy():
    value = PreviewInput(**(SPEC | {"transports": ["paqet"], "experimental": True})).worker_spec()
    assert value["services"][0]["target"] == "127.0.0.1:8443"
    assert value["services"][0]["protocol"] == "tcp"
    assert value["open_ufw"] is False
    assert value["experimental"] is True


@pytest.mark.parametrize("confirm", [False, "true", 1, None])
def test_start_needs_literal_confirmation(confirm):
    with pytest.raises(ValidationError):
        StartInput(plan_id=PLAN, confirm_replace=confirm)


def test_response_strips_worker_secrets():
    output = State.model_validate(
        {"available": True, "busy": False, "profiles": [], "jobs": [], "public_key": "hidden", "private_key": "secret"}
    ).model_dump()
    assert "public_key" not in output and "private_key" not in output


async def request_api(owner, method, path, body=None):
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_current] = lambda: AdminDetails(username="owner", role=AdminRoleData(is_owner=owner))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        return await client.request(method, "/api/tunnels" + path, json=body)


@pytest.mark.parametrize(
    "method,path,body",
    [
        ("GET", "/state", None),
        ("POST", "/pair", {}),
        ("POST", "/connection", {}),
        ("POST", "/preview", SPEC),
        ("POST", "/start", {"plan_id": PLAN, "confirm_replace": True}),
    ],
)
async def test_non_owner_cannot_reach_worker(method, path, body):
    with patch.object(TunnelClient, "request", new_callable=AsyncMock) as worker:
        response = await request_api(False, method, path, body)
    assert response.status_code == 403
    worker.assert_not_called()


async def test_disabled_state_does_not_connect(monkeypatch):
    monkeypatch.setattr(feature_settings, "donino_tunnel_bridge", False)
    with patch.object(TunnelClient, "request", new_callable=AsyncMock) as worker:
        response = await request_api(True, "GET", "/state")
    assert response.status_code == 200 and response.json()["available"] is False
    worker.assert_not_called()


async def test_disabled_mutation_and_unauthenticated_request(monkeypatch):
    monkeypatch.setattr(feature_settings, "donino_tunnel_bridge", False)
    response = await request_api(True, "POST", "/preview", SPEC)
    assert response.status_code == 503
    app = FastAPI()
    app.include_router(router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/api/tunnels/state")).status_code == 401


async def test_bad_upstream_schema_is_sanitized(monkeypatch):
    monkeypatch.setattr(feature_settings, "donino_tunnel_bridge", True)
    with patch.object(TunnelClient, "request", new_callable=AsyncMock, return_value={"secret": "do-not-expose"}):
        response = await request_api(True, "GET", "/state")
    assert response.status_code == 502 and "do-not-expose" not in response.text


async def test_preview_and_start_forward_typed_commands(monkeypatch):
    monkeypatch.setattr(feature_settings, "donino_tunnel_bridge", True)
    response_body = {
        "id": PLAN,
        "expires_at": 2000000000,
        "summary": {
            "iran": "iran.example.com",
            "foreign": "exit.example.com",
            "transports": ["frp-wss"],
            "services": [],
            "firewall": {"iran": {"tcp": [443], "udp": []}},
        },
        "secret": "hidden",
    }
    with patch.object(TunnelClient, "request", new_callable=AsyncMock, return_value=response_body) as worker:
        response = await request_api(True, "POST", "/preview", SPEC)
        assert response.status_code == 201 and "secret" not in response.json()
        assert worker.call_args.args[2]["services"][0]["target"] == "127.0.0.1:8443"
    job = {"id": PLAN, "plan_id": PLAN, "status": "running", "stage": "prepare", "created": 1, "updated": 1}
    with patch.object(TunnelClient, "request", new_callable=AsyncMock, return_value=job) as worker:
        response = await request_api(True, "POST", "/start", {"plan_id": PLAN, "confirm_replace": True})
        assert response.status_code == 202
        worker.assert_awaited_once_with("POST", "/start", {"plan_id": PLAN, "confirm_replace": True})


@pytest.mark.parametrize(
    "status,body",
    [(200, b'{"ok":true}'), (400, b"secret failure"), (302, b""), (200, b"[]"), (200, b"x" * (MAX_RESPONSE + 1))],
)
async def test_real_unix_socket_transport(tmp_path, status, body):
    app = web.Application()
    app.router.add_get("/state", lambda request: web.Response(status=status, body=body))
    runner = web.AppRunner(app)
    await runner.setup()
    socket = tmp_path / "controller.sock"
    await web.UnixSite(runner, str(socket)).start()
    os.chmod(socket, 0o660)
    client = TunnelClient(socket, require_root=False)
    try:
        if status == 200 and body.startswith(b"{"):
            assert await client.request("GET", "/state") == {"ok": True}
        else:
            with pytest.raises(BridgeError) as error:
                await client.request("GET", "/state")
            assert error.value.status == (409 if status == 400 else 502)
        os.chmod(socket, 0o666)
        with pytest.raises(BridgeError):
            client.check_socket()
        with pytest.raises(BridgeError):
            await client.request("GET", "/shell")
    finally:
        await runner.cleanup()


def test_regular_file_socket_is_rejected(tmp_path):
    path = tmp_path / "fake.sock"
    path.touch(mode=0o600)
    with pytest.raises(BridgeError):
        TunnelClient(path, require_root=False).check_socket()


def test_bridge_flag_follows_panel_dotenv_configuration(tmp_path, monkeypatch):
    monkeypatch.delenv("DONINO_TUNNEL_BRIDGE", raising=False)
    env = tmp_path / ".env"
    env.write_text("DONINO_TUNNEL_BRIDGE=1\n")
    assert FeatureSettings(_env_file=env).donino_tunnel_bridge is True
    monkeypatch.setenv("DONINO_TUNNEL_BRIDGE", "0")
    assert FeatureSettings(_env_file=env).donino_tunnel_bridge is False
