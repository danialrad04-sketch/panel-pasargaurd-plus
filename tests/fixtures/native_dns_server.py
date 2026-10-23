"""Isolated browser-test server. Never loads or modifies the operator's database."""

import os
from pathlib import Path

path = Path(os.environ["TEST_DNS_DB_PATH"]).resolve()
if path.exists():
    raise SystemExit("Browser fixture database must be a new file")
os.environ["SQLALCHEMY_DATABASE_URL"] = "sqlite+aiosqlite:///" + str(path)
os.environ["DNS_ENABLED"] = "1"

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import asyncio

import uvicorn
from fastapi import FastAPI
from sqlalchemy import select

from app.db.base import Base, SessionLocal, engine
from app.db.models import CoreConfig, DNSPolicy, Node, NodeStatus, TunnelDeployment, User
from app.models.admin import AdminDetails, AdminRoleData
from app.operation import dns as operation
from app.routers.authentication import get_current
from app.routers.dns import router
from app.routers.tunnel import router as tunnel_router


async def signing_key():
    return "local-browser-fixture-key-not-for-deployment"


operation.get_secret_key = signing_key
owner = AdminDetails(username="preview-owner", role=AdminRoleData(is_owner=True))
app = FastAPI()
app.include_router(router)
app.include_router(tunnel_router)
app.dependency_overrides[get_current] = lambda: owner


@app.get("/api/admin")
async def admin():
    return owner


@app.get("/api/users/simple")
async def users():
    async with SessionLocal() as db:
        rows = (await db.scalars(select(User))).all()
    return {"users": [{"id": row.id, "username": row.username} for row in rows], "total": len(rows)}


@app.get("/api/system/resources")
async def resources():
    return {}


async def seed():
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    async with SessionLocal() as db:
        db.add(TunnelDeployment(id=1))
        core = CoreConfig(
            name="Native browser fixture",
            config={
                "inbounds": [
                    {
                        "tag": "users",
                        "listen": "127.0.0.1",
                        "port": 11080,
                        "protocol": "vless",
                        "settings": {"decryption": "none", "clients": []},
                    }
                ],
                "outbounds": [{"tag": "direct", "protocol": "freedom"}],
            },
        )
        db.add(core)
        await db.flush()
        for name in ("Iran", "Germany", "Finland"):
            db.add(
                Node(
                    name=name,
                    address="127.0.0.1",
                    port=62050,
                    api_port=62051,
                    server_ca="intentionally-invalid-browser-fixture",
                    api_key="fixture",
                    core_config_id=core.id,
                    status=NodeStatus.connected,
                )
            )
        db.add(User(username="native-user"))
        db.add(DNSPolicy(name="Privacy", rules=[{"domain": "blocked.example", "action": "block", "addresses": []}]))
        await db.commit()


@app.get("/preview/subscription")
async def subscription_preview():
    from fastapi.responses import HTMLResponse

    from app.templates import render_template

    async with SessionLocal() as db:
        user = await db.get(User, 1)
        metadata = await operation.portal_metadata(db, user.id)
        html = render_template(
            "subscription/index.html",
            {
                "user": user,
                "links": ["vless://example#Germany", "trojan://example#Finland"],
                "dns_info": metadata,
                "apps": [],
                "announce": "",
                "announce_url": None,
            },
        )
    return HTMLResponse(html)


if __name__ == "__main__":
    asyncio.run(seed())
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("TEST_DNS_PORT", "8085")), access_log=False)
