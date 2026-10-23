"""Owner-only access to the opt-in local Donino controller."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import ValidationError

from app.integrations.donino import (
    BridgeError,
    Connection,
    ConnectionInput,
    Job,
    PairInput,
    Preview,
    PreviewInput,
    Profile,
    StartInput,
    State,
    TunnelClient,
    enabled,
)

from .authentication import require_owner

router = APIRouter(prefix="/api/tunnels", tags=["Donino Tunnel"], dependencies=[Depends(require_owner)])


async def call(model, method, route, body=None):
    if not enabled():
        raise HTTPException(503, "Donino integration is disabled; configure DONINO_TUNNEL_BRIDGE=1")
    try:
        payload = await TunnelClient().request(method, route, body)
        return model.model_validate(payload)
    except BridgeError as error:
        raise HTTPException(
            error.status, "Donino controller unavailable or request rejected. Refresh state before retrying a mutation."
        ) from None
    except ValidationError:
        raise HTTPException(502, "Unsupported Donino controller response") from None


@router.get("/state", response_model=State)
async def state():
    if not enabled():
        return State(available=False, busy=False, profiles=[], jobs=[])
    return await call(State, "GET", "/state")


@router.post("/pair", response_model=Profile, status_code=201)
async def pair(body: PairInput):
    return await call(Profile, "POST", "/pair", body.model_dump())


@router.post("/connection", response_model=Connection)
async def connection(body: ConnectionInput):
    return await call(Connection, "POST", "/connection", body.model_dump())


@router.post("/preview", response_model=Preview, status_code=201)
async def preview(body: PreviewInput):
    return await call(Preview, "POST", "/preview", body.worker_spec())


@router.post("/start", response_model=Job, status_code=202)
async def start(body: StartInput):
    return await call(Job, "POST", "/start", body.model_dump())
