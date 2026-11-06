"""Verified TLS and the existing native node API key; no independent controller."""

import ssl
from uuid import UUID

import aiohttp
from PasarGuardNodeBridge.proxy import parse_proxy_url


class PaqetNodeError(RuntimeError):
    pass


async def request(node, method, path, body=None, *, namespace="paqet"):
    if namespace not in ("paqet", "health", "smartdns", "backhaul", "gost", "frp", "network"):
        raise ValueError("Unknown native node namespace")
    try:
        ctx = ssl.create_default_context(cadata=node.server_ca)
    except ValueError, ssl.SSLError:
        raise PaqetNodeError("Invalid native node TLS certificate") from None
    proxy = parse_proxy_url(node.proxy_url)
    kwargs = {}
    if proxy:
        kwargs = {
            "connector": proxy.aiohttp_connector_factory(ctx) if proxy.aiohttp_connector_factory else None,
            "proxy": proxy.aiohttp_proxy_url,
            "proxy_auth": proxy.aiohttp_proxy_auth,
        }
    # Proxy settings belong on requests, not ClientSession's constructor.
    connector = kwargs.pop("connector", None)
    host = f"[{node.address}]" if ":" in node.address else node.address
    url = f"https://{host}:{node.tunnel_api_port}/v1/{namespace}/{path}"
    try:
        async with (
            aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=25)) as client,
            client.request(
                method, url, json=body, ssl=ctx, headers={"x-api-key": str(UUID(node.api_key))}, **kwargs
            ) as response,
        ):
            raw = await response.content.read(262145)
            if len(raw) > 262144 or response.status != 200:
                raise PaqetNodeError("Native node extension did not acknowledge the request; inspect node capability/state")
            import json

            return json.loads(raw)
    except (aiohttp.ClientError, TimeoutError, ValueError) as error:
        raise PaqetNodeError(
            "Native node extension API unavailable or TLS validation failed; upgrade/configure the fork node"
        ) from error


async def apply(node, tunnel, nodes):
    from app.paqet.compiler import node_request

    capability = await request(node, "GET", "capabilities")
    if (
        capability.get("contract") != 1
        or capability.get("engine") != "v1.0.0-alpha.21"
        or not capability.get("installed")
    ):
        raise PaqetNodeError("Node lacks the required source-built Paqet contract")
    result = await request(node, "PUT", f"links/{tunnel.engine_id}", node_request(tunnel, node.id, nodes))
    if result.get("revision") != tunnel.revision or bool(result.get("running")) != tunnel.enabled:
        raise PaqetNodeError("Node did not acknowledge the requested Paqet revision")
    return result
