"""Public self-service DNS link. Authorized only by the account's own DNS credential in the URL."""

from fastapi import APIRouter, Depends, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import HTMLResponse, JSONResponse

from app.db import AsyncSession, get_db
from app.operation import dns_portal as portal
from app.templates import render_template
from app.utils.request import get_client_ip
from config import dashboard_settings

router = APIRouter(prefix="/dns-sub", tags=["DNS Portal"], include_in_schema=False)
HEADERS = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Robots-Tag": "noindex"}


def reply(value, status=200):
    return JSONResponse(jsonable_encoder(value), status_code=status, headers=HEADERS)


@router.get("/{token}", response_class=HTMLResponse)
async def page(token: str, request: Request, db: AsyncSession = Depends(get_db)):
    data = await portal.info(db, token, get_client_ip(request))
    html = render_template(
        "dns-portal/index.html", {"data": jsonable_encoder(data), "dashboard_path": dashboard_settings.path}
    )
    return HTMLResponse(html, headers=HEADERS)


@router.get("/{token}/info")
async def info(token: str, request: Request, db: AsyncSession = Depends(get_db)):
    return reply(await portal.info(db, token, get_client_ip(request)))


@router.post("/{token}/ip")
async def register(token: str, request: Request, db: AsyncSession = Depends(get_db)):
    """Registers the caller's own address; an address cannot be supplied by the caller."""
    return reply(await portal.register(db, token, get_client_ip(request)))


@router.delete("/{token}/ip/{ip}")
async def remove(token: str, ip: str, request: Request, db: AsyncSession = Depends(get_db)):
    return reply(await portal.remove(db, token, ip, get_client_ip(request)))
