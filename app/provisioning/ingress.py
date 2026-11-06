"""Compile only selected native data/control ingress; preserve host firewall policy."""

import asyncio
import ipaddress

from sqlalchemy import select

from app.db.models import CoreConfig, NativeTunnel, Node
from app.paqet.client import request
from app.provisioning.ssh import ProvisionError
from app.tunnel.engines import control_node


def ports(value):
    result = set()
    for section in str(value).split(","):
        parts = section.strip().split("-")
        if len(parts) not in (1, 2) or not all(part.isdigit() for part in parts):
            raise ProvisionError("unsupported_inbound_port")
        start, end = int(parts[0]), int(parts[-1])
        if start < 1 or start == 22 or end > 65535 or not 0 <= end - start <= 32:
            raise ProvisionError("unsupported_inbound_port")
        result.update(range(start, end + 1))
    return result


async def apply(db, node_id, *, include_users=True):
    rows = (await db.scalars(select(NativeTunnel).where(NativeTunnel.enabled.is_(True)))).all()
    nodes = {node.id: node for node in (await db.scalars(select(Node))).all()}
    node = nodes[node_id]
    rules = set()
    for row in rows:
        if include_users and row.source_node_id == node_id:
            core = await db.get(CoreConfig, node.core_config_id or 1)
            for inbound in core.config.get("inbounds", []) if core else []:
                if inbound.get("tag") in row.inbound_tags:
                    for port in ports(inbound.get("port")):
                        for source in ("::/0",) if ":" in node.address else ("0.0.0.0/0",):
                            for network in ("tcp", "udp"):
                                rules.add((port, network, source))
        if control_node(row) == node_id:
            peer_id = row.exit_node_id if node_id == row.source_node_id else row.source_node_id
            peer = nodes[peer_id].address
            if row.transport == "paqet":
                peer = (row.paqet_options or {}).get("client_address") or peer
            try:
                addresses = [str(ipaddress.ip_address(peer))]
            except ValueError:
                resolved = await asyncio.get_running_loop().getaddrinfo(peer, None)
                addresses = sorted({value[4][0] for value in resolved})
            networks = (
                ("tcp", "udp")
                if row.transport == "shadowsocks-2022"
                or (row.transport == "frp" and row.carrier_options.get("profile") in ("kcp", "quic"))
                else ("tcp",)
            )
            for address in addresses:
                ip = ipaddress.ip_address(address)
                source = str(ip) + ("/32" if ip.version == 4 else "/128")
                for network in networks:
                    rules.add((row.port, network, source))
    if len(rules) > 256:
        raise ProvisionError("ingress_capacity_exceeded")
    current = await request(node, "GET", "ingress", namespace="network")
    revision = current.get("revision")
    if type(revision) is not int or not 0 <= revision < 2147483646:
        raise ProvisionError("invalid_ingress_revision")
    value = await request(
        node,
        "PUT",
        "ingress",
        {
            "revision": revision + 1,
            "rules": [{"port": port, "network": network, "source": source} for port, network, source in sorted(rules)],
        },
        namespace="network",
    )
    if value.get("revision") != revision + 1:
        raise ProvisionError("ingress_not_acknowledged")
