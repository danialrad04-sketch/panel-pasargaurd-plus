"""Shared carrier identity and port ownership; no user or subscription duplicates."""

ENGINES = {
    "backhaul": {"version": "v0.7.2", "commit": "df7966f8f725837a680ea7b90bd37ea52666c277", "reverse": True},
    "gost": {"version": "v3.3.1", "commit": "84dd70f9f65e6cb318268204887b2b8e78b93937", "reverse": False},
    "frp": {"version": "v0.71.1", "commit": "d8cf1800dc1abd7fda1828cab105d7a6b5a50eb1", "reverse": True},
}
MANAGED = ("paqet", *ENGINES)


PAQET_ENGINE = "v1.0.0-alpha.21"
PAQET_COMMIT = "4b81ce0ab56b7cc9aec7501c87c4804676cdb1b4"


def compatible_capability(name, value):
    """True only for the pinned native adapter contract; shared by the router and wizard."""
    if not (isinstance(value, dict) and value.get("contract") == 1 and value.get("installed") is True):
        return False
    definition = ENGINES.get(name)
    if definition:
        return (
            value.get("engine") == name
            and value.get("version") == definition["version"]
            and value.get("source_commit") == definition["commit"]
        )
    return value.get("engine") == PAQET_ENGINE and value.get("source_commit") == PAQET_COMMIT


async def node_capability(node, name):
    import asyncio

    from app.paqet.client import request

    try:
        async with asyncio.timeout(8):
            value = await request(node, "GET", "capabilities", namespace=name)
        return {"id": name, "available": True, "compatible": compatible_capability(name, value), "capability": value}
    except RuntimeError, ValueError, TimeoutError:
        return {"id": name, "available": False, "capability": None}


def forwarded(tunnel):
    return getattr(tunnel, "transport", "shadowsocks-2022") in MANAGED


def options(tunnel):
    return (
        getattr(tunnel, "paqet_options", {})
        if getattr(tunnel, "transport", "shadowsocks-2022") == "paqet"
        else getattr(tunnel, "carrier_options", {})
    ) or {}


def control_node(tunnel):
    return (
        tunnel.source_node_id
        if ENGINES.get(getattr(tunnel, "transport", "shadowsocks-2022"), {}).get("reverse")
        else tunnel.exit_node_id
    )


def owned_ports(tunnel):
    result = {tunnel.source_node_id: set(), tunnel.exit_node_id: set()}
    result[control_node(tunnel)].add(tunnel.port)
    if forwarded(tunnel):
        value = options(tunnel)
        result[tunnel.source_node_id].add(value.get("client_port", 11081))
        result[tunnel.exit_node_id].add(value.get("exit_port", 11082))
        if tunnel.transport == "paqet":
            result[tunnel.source_node_id].update(
                int(row["listen"].rsplit(":", 1)[1]) for row in value.get("forwards", []) + value.get("socks5", [])
            )
    return result


def node_request(tunnel, node_id, nodes):
    from app.models.carrier import stored_options

    value = stored_options(tunnel.transport, tunnel.carrier_options)
    control = nodes[control_node(tunnel)]
    payload = {key: item for key, item in value.items() if key != "control_address"}
    payload.update(
        role="source" if node_id == tunnel.source_node_id else "exit",
        peer=value.get("control_address") or control.address,
        peer_ca=control.server_ca,
        peer_name=control.address,
        token=tunnel.carrier_key,
        control_port=tunnel.port,
    )
    return {"revision": tunnel.revision, "enabled": tunnel.enabled, "config": payload, "manage_firewall": False}


async def apply(node, tunnel, nodes):
    from app.paqet.client import PaqetNodeError, request

    definition = ENGINES[tunnel.transport]
    capability = await request(node, "GET", "capabilities", namespace=tunnel.transport)
    if (
        capability.get("contract") != 1
        or capability.get("engine") != tunnel.transport
        or capability.get("version") != definition["version"]
        or capability.get("source_commit") != definition["commit"]
        or not capability.get("installed")
    ):
        raise PaqetNodeError("Node lacks the pinned native carrier adapter")
    result = await request(
        node, "PUT", f"links/{tunnel.engine_id}", node_request(tunnel, node.id, nodes), namespace=tunnel.transport
    )
    if result.get("revision") != tunnel.revision or bool(result.get("running")) != tunnel.enabled:
        raise PaqetNodeError("Node did not acknowledge the native carrier revision")
    return result
