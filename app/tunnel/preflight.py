"""Bounded read-only diagnostics. Node acknowledgements do not prove traffic health."""

import asyncio
import ipaddress

from fastapi import HTTPException

from app.db.models import CoreConfig, NativeTunnel, NodeStatus, TunnelDeployment
from app.models.tunnel import TunnelPreflight, TunnelPreflightCheck
from app.operation import tunnel as operation
from app.paqet.client import request
from app.tunnel.guards import leased

NODE_CHECK_TIMEOUT_SECONDS = 8


async def preflight(db, tunnel_id, expected_revision):
    row = await db.get(NativeTunnel, tunnel_id)
    if not row:
        raise HTTPException(404, "Tunnel not found")
    if row.revision != expected_revision:
        raise HTTPException(409, "Tunnel revision changed; reload before checking readiness")
    checks = []

    def add(code, status, detail, *, apply=False, publish=False):
        checks.append(
            TunnelPreflightCheck(
                code=code,
                status=status,
                detail=detail,
                blocks_apply=apply and status == "fail",
                blocks_publish=publish and status == "fail",
            )
        )

    state = await db.get(TunnelDeployment, 1)
    busy = bool(state and leased(state))
    add(
        "deployment",
        "fail" if busy else "pass",
        "A deployment is in progress" if busy else "No active deployment lease",
        apply=True,
        publish=True,
    )
    try:
        nodes = await operation.validate_topology(db, await operation.rows(db))
    except HTTPException, ValueError, KeyError, TypeError:
        add(
            "topology",
            "fail",
            "Review core types, selected inbounds, topology and port conflicts",
            apply=True,
            publish=True,
        )
        nodes = {}
    else:
        add("topology", "pass", "Stored topology and listener settings are valid")
    applied = row.enabled and row.revision == row.applied_revision
    add(
        "applied_revision",
        "pass" if applied else "fail",
        "Enabled revision was acknowledged" if applied else "Enable and apply this revision before publishing",
        publish=True,
    )

    for role, node_id in (("source", row.source_node_id), ("exit", row.exit_node_id)):
        node = nodes.get(node_id)
        connected = bool(node and node.status == NodeStatus.connected)
        add(
            f"{role}.control",
            "pass" if connected else "fail",
            "Last recorded node control status is connected"
            if connected
            else "Node control is not recorded as connected; check or reconnect it",
            publish=True,
        )
    source = nodes.get(row.source_node_id)
    if source:
        core = await db.get(CoreConfig, source.core_config_id or 1)
        listeners = {item.get("tag"): item for item in (core.config.get("inbounds", []) if core else [])}
        private_listener = False
        for tag in row.inbound_tags:
            listener = listeners.get(tag, {}).get("listen", "0.0.0.0")
            try:
                address = ipaddress.ip_address(listener)
                private_listener |= address.is_loopback or address.is_multicast or address.is_link_local
            except ValueError:
                private_listener = True
        add(
            "ingress_listener",
            "fail" if private_listener else "pass",
            "Source user inbound is bound to loopback or a non-client listener; review its listen address"
            if private_listener
            else "Source user inbounds can accept non-loopback clients",
            publish=True,
        )
    try:
        address = ipaddress.ip_address(source.address)
        usable = not (address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local)
    except ValueError, AttributeError:
        usable = False
    add(
        "ingress_address",
        "pass" if usable else "warning",
        "Numeric source IP can be reviewed in the host publisher"
        if usable
        else "Specify a client-reachable Iran IP when publishing the host",
    )

    async def probe(role, node_id, peer_id):
        node, peer = nodes.get(node_id), nodes.get(peer_id)
        if not node or not peer:
            return
        try:
            async with asyncio.timeout(NODE_CHECK_TIMEOUT_SECONDS):
                capability = await request(node, "GET", "capabilities")
                valid = (
                    isinstance(capability, dict)
                    and capability.get("contract") == 1
                    and capability.get("engine") == "v1.0.0-alpha.21"
                    and capability.get("installed") is True
                )
                add(
                    f"{role}.paqet",
                    "pass" if valid else "fail",
                    "Verified native Paqet capability"
                    if valid
                    else "Node lacks the required source-built Paqet contract",
                    apply=True,
                    publish=True,
                )
                if not valid:
                    return
                try:
                    override = (row.paqet_options or {}).get("server_address" if role == "source" else "client_address")
                    peer_address = ipaddress.ip_address(override or peer.address)
                    network = await request(node, "GET", f"network?peer={peer_address}")
                    if not isinstance(network, dict) or not all(
                        network.get(key) for key in ("interface", "local_ip", "router_mac")
                    ):
                        raise ValueError("Malformed discovery")
                except RuntimeError, ValueError:
                    add(
                        f"{role}.network",
                        "warning",
                        "Discovery unavailable; review manual NIC, local IP and gateway MAC overrides. Apply performs final validation",
                    )
                else:
                    add(f"{role}.network", "pass", "Node discovered an egress interface, local IP and gateway MAC")
                if applied:
                    try:
                        runtime = await request(node, "GET", f"links/{row.engine_id}")
                        running = (
                            isinstance(runtime, dict)
                            and runtime.get("running") is True
                            and runtime.get("revision") == row.revision
                        )
                    except RuntimeError, ValueError:
                        running = False
                    add(
                        f"{role}.runtime",
                        "pass" if running else "fail",
                        "Paqet process runs at this revision"
                        if running
                        else "Paqet runtime is down or stale; reapply to repair",
                        publish=True,
                    )
        except RuntimeError, ValueError, TimeoutError:
            add(
                f"{role}.paqet_access",
                "fail",
                "Node capability check failed or timed out; verify TLS, API key and tunnel API port",
                apply=True,
                publish=True,
            )

    if row.transport == "paqet":
        await asyncio.gather(
            probe("source", row.source_node_id, row.exit_node_id), probe("exit", row.exit_node_id, row.source_node_id)
        )
    checks.sort(key=lambda item: item.code)
    return TunnelPreflight(
        tunnel_id=row.id,
        revision=row.revision,
        can_apply=not any(item.blocks_apply for item in checks),
        can_publish=not any(item.blocks_publish for item in checks),
        checks=checks,
    )
