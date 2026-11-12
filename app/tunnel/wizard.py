"""Quick-tunnel wizard: build candidate carriers, measure them, keep the best one.

Runs as a ``wizard`` job on the durable native job queue (lease, heartbeat, cancel).
Only one carrier is enabled per source node at a time, so candidates are tried
sequentially: each switch is a single fenced ``deploy`` whose ``prepare`` hook disables
the previous candidate and enables the next one. Success always means a *measured*
healthy encrypted carrier (TCP and UDP echo); user inbounds, TLS and games stay
``not_probed`` and are never inferred. No direct-route fallback is ever introduced.
"""

import asyncio
import ipaddress
from dataclasses import dataclass

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.db.base import GetDB
from app.db.models import (
    CoreConfig,
    CoreType,
    NativeProvisionJob,
    NativeTunnel,
    Node,
    NodeStatus,
    ServiceAudit,
    TunnelDeployment,
)
from app.discovery.reservations import reserved_by_address
from app.models.carrier import CarrierOptions
from app.models.tunnel import TunnelCreate
from app.models.tunnel_wizard import WizardPlanInput
from app.operation import tunnel as operation
from app.paqet.client import request
from app.paqet.models import PaqetOptions
from app.provisioning.worker import ClaimLost
from app.tunnel.compiler import USER_PROTOCOLS, occupied_port
from app.tunnel.engines import ENGINES, node_capability, owned_ports
from app.tunnel.guards import leased
from app.tunnel.health import probe

SINGLETON = "tunnel-wizard"
MAX_TUNNELS = 16
METRICS = ("tcp_rtt_ms", "udp_rtt_ms", "udp_loss_percent", "sample_mbps")


@dataclass(frozen=True)
class Spec:
    id: str
    transport: str
    profile: str | None
    label: str


CATALOG = {
    spec.id: spec
    for spec in (
        Spec("paqet", "paqet", None, "Paqet (KCP over raw TCP)"),
        Spec("backhaul-tcp", "backhaul", "tcp", "Backhaul TCP"),
        Spec("gost-tls", "gost", "tls", "GOST TLS"),
        Spec("gost-wss", "gost", "wss", "GOST WSS"),
        Spec("frp-tcp", "frp", "tcp", "FRP TCP"),
        Spec("frp-kcp", "frp", "kcp", "FRP KCP"),
        Spec("frp-quic", "frp", "quic", "FRP QUIC"),
        Spec("ss2022", "shadowsocks-2022", None, "SS2022 direct"),
    )
}
DEFAULT_ORDER = ("paqet", "backhaul-tcp", "gost-tls", "frp-tcp", "ss2022")


def score(metrics, priority="balanced"):
    """Higher is better. Zero means the carrier is not a healthy TCP+UDP path."""
    if not metrics or metrics.get("tcp") is not True or metrics.get("udp") is not True:
        return 0.0
    throughput = max(float(metrics.get("sample_mbps", 0)), 0.1)
    latency = (float(metrics.get("tcp_rtt_ms", 0)) + float(metrics.get("udp_rtt_ms", 0))) / 2
    delivered = 1 - min(max(float(metrics.get("udp_loss_percent", 0)), 0.0), 100.0) / 100
    if priority == "latency":
        value = delivered * 1000 / (1 + latency)
    elif priority == "throughput":
        value = delivered * throughput
    else:
        value = delivered * throughput / (1 + latency / 100)
    return round(value, 4)


def rank(steps):
    """Healthy steps, best first. The sort is stable, so ties keep priority order."""
    return sorted((step for step in steps if step["state"] == "healthy"), key=lambda step: -step["score"])


def clean(measured):
    """Whitelist bounded numeric measurements; never copy raw node text."""
    result = {key: measured[key] for key in ("tcp", "udp") if type(measured.get(key)) is bool}
    for key in METRICS:
        if type(measured.get(key)) in (int, float):
            result[key] = measured[key]
    return result


def free_port(start, blocked):
    port = start
    while blocked(port):
        port += 1
        if port > 65000:
            raise HTTPException(409, "No free carrier port is available")
    return port


def allocate(rows, nodes, cores, reserved=None):
    """One port triple for every candidate: only one candidate is ever enabled."""
    used = set()
    for row in rows:
        for ports in owned_ports(row).values():
            used |= ports
    for node in nodes:
        used |= {value for value in (node.port, node.api_port, node.tunnel_api_port) if value}
        used |= {node.health_echo_port or 62053, 62051}
        used |= (reserved or {}).get(node.address, set())

    def blocked(port):
        return port in used or any(occupied_port(core.config, port) for core in cores.values())

    control = free_port(40900, blocked)
    used.add(control)
    client = free_port(11081, blocked)
    used.add(client)
    return {"control": control, "client": client, "exit": free_port(client + 1, blocked)}


def tunnel_input(spec, *, name, request, ports):
    data = {
        "name": name,
        "source_node_id": request.source_node_id,
        "exit_node_id": request.exit_node_id,
        "inbound_tags": request.inbound_tags,
        "domains": request.domains,
        "port": ports["control"],
        "enabled": False,
        "health_enabled": True,
        "transport": spec.transport,
    }
    if spec.transport == "paqet":
        data["paqet_options"] = PaqetOptions(client_port=ports["client"], exit_port=ports["exit"])
    elif spec.transport in ENGINES:
        data["carrier_options"] = CarrierOptions(
            profile=spec.profile, client_port=ports["client"], exit_port=ports["exit"]
        )
    return TunnelCreate(**data)


def numeric(address):
    try:
        value = ipaddress.ip_address(address)
    except ValueError:
        return False
    # Same rule as app.paqet.compiler.node_request, so the plan never disagrees with apply.
    return not (value.is_unspecified or value.is_multicast or value.is_link_local)


async def ingress_managed(node):
    try:
        async with asyncio.timeout(8):
            value = await request(node, "GET", "ingress", namespace="network")
        return isinstance(value, dict) and type(value.get("revision")) is int
    except RuntimeError, ValueError, TimeoutError:
        return False


async def plan(db, body):
    """Read-only preflight. Nothing is created, applied or restarted."""
    source, exit_node = await db.get(Node, body.source_node_id), await db.get(Node, body.exit_node_id)
    if not source or not exit_node:
        raise HTTPException(404, "Node not found")
    blockers, warnings = [], []
    for role, node in (("source", source), ("exit", exit_node)):
        if node.status != NodeStatus.connected:
            blockers.append(f"{role}_not_connected")
    state = await db.get(TunnelDeployment, 1)
    if state and leased(state):
        blockers.append("deployment_in_progress")
    if await db.scalar(select(NativeProvisionJob.id).where(NativeProvisionJob.active_target == SINGLETON).limit(1)):
        blockers.append("wizard_already_running")
    rows = await operation.rows(db)
    active = [row for row in rows if row.enabled]
    if any(row.source_node_id == source.id for row in active):
        blockers.append("source_has_active_tunnel")
    if any(row.source_node_id == exit_node.id for row in active):
        blockers.append("exit_is_active_source")
    if any(row.exit_node_id == source.id for row in active):
        blockers.append("source_is_active_exit")
    order = list(body.candidates or DEFAULT_ORDER)
    if len(rows) + len(order) > MAX_TUNNELS:
        blockers.append("tunnel_capacity")
    cores = {}
    for role, node in (("source", source), ("exit", exit_node)):
        core = await db.get(CoreConfig, node.core_config_id or 1)
        if not core or core.type not in (None, CoreType.xray):
            blockers.append(f"{role}_core_not_xray")
        else:
            cores[node.id] = core
    core = cores.get(source.id)
    if core:
        available = {
            item["tag"]
            for item in core.config.get("inbounds", [])
            if item.get("protocol") in USER_PROTOCOLS and item.get("tag") not in (core.exclude_inbound_tags or ())
        }
        if not set(body.inbound_tags) <= available:
            blockers.append("inbound_unavailable")
    ports = None
    try:
        ports = allocate(
            rows,
            (source, exit_node),
            {core.id: core for core in cores.values()},
            await reserved_by_address(db),
        )
    except HTTPException:
        blockers.append("no_free_port")
    transports = sorted({CATALOG[item].transport for item in order if CATALOG[item].transport != "shadowsocks-2022"})
    capability = dict(
        zip(
            [(node.id, name) for node in (source, exit_node) for name in transports],
            await asyncio.gather(*(node_capability(node, name) for node in (source, exit_node) for name in transports)),
            strict=True,
        )
    )
    managed = dict(zip(("source", "exit"), await asyncio.gather(ingress_managed(source), ingress_managed(exit_node))))
    for role, value in managed.items():
        if not value:
            warnings.append(f"firewall_not_managed_{role}")
    candidates = []
    for item in order:
        spec = CATALOG[item]
        reason = None
        if spec.transport != "shadowsocks-2022":
            missing = [
                role
                for role, node in (("source", source), ("exit", exit_node))
                if not capability[(node.id, spec.transport)].get("compatible")
            ]
            if missing:
                reason = "node_adapter_incompatible:" + ",".join(missing)
        if not reason and spec.transport == "paqet" and not (numeric(source.address) and numeric(exit_node.address)):
            reason = "numeric_node_ip_required"
        candidates.append(
            {
                "id": spec.id,
                "label": spec.label,
                "transport": spec.transport,
                "profile": spec.profile,
                "compatible": reason is None,
                "reason": reason,
            }
        )
    if not any(item["compatible"] for item in candidates):
        blockers.append("no_compatible_carrier")
    usable = sum(item["compatible"] for item in candidates)
    return {
        "ok": not blockers,
        "blockers": blockers,
        "warnings": warnings,
        "mode": body.mode,
        "priority": body.priority,
        "candidates": candidates,
        "ports": ports,
        "ingress": managed,
        "disruption": "xray_restart_on_both_nodes_per_deployment",
        "max_deployments": usable + (1 if body.mode == "thorough" else 0),
        "scope": "verified_encrypted_carrier",
        "user_inbound_readiness": "not_probed",
    }


async def enqueue(db, body, actor):
    from app.provisioning.service import view

    result = await plan(db, body)
    if result["blockers"]:
        raise HTTPException(409, {"code": "wizard_blocked", "blockers": result["blockers"]})
    payload = {
        "request": body.model_dump(exclude={"confirm_restart"}),
        "candidates": [item["id"] for item in result["candidates"] if item["compatible"]],
        "skipped": [{"id": item["id"], "reason": item["reason"]} for item in result["candidates"] if item["reason"]],
        "ingress": result["ingress"],
    }
    job = NativeProvisionJob(
        actor=actor,
        kind="wizard",
        target=f"wizard:{body.source_node_id}->{body.exit_node_id}",
        active_target=SINGLETON,
        payload=payload,
    )
    db.add(job)
    try:
        await db.flush()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "A quick-tunnel run is already active") from None
    db.add(
        ServiceAudit(
            actor=actor,
            action="provision.wizard.queued",
            target_id=job.id,
            details={"mode": body.mode, "candidates": payload["candidates"]},
        )
    )
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "A quick-tunnel run is already active") from None
    return view(job)


def restarter(managed):
    """Same node restart contract as native activation; firewall rules only where the node manages them."""
    from app.operation import OperatorType
    from app.operation.node import NodeOperation
    from app.provisioning import ingress

    operator = NodeOperation(operator_type=OperatorType.SYSTEM)

    async def restart(db, node_id, **kwargs):
        if node_id in managed:
            await ingress.apply(db, node_id, include_users=False)
        acknowledged = await operator._connect_single_node_local(db, node_id, **kwargs)
        if acknowledged and node_id in managed:
            await ingress.apply(db, node_id, include_users=True)
        return acknowledged

    return restart


async def measure(identity, revision, transport, sessions, health_probe, pause=asyncio.sleep):
    """Reverse and UDP carriers need bounded reconnection time after a fresh apply."""
    last = {"healthy": False, "status": "unavailable"}
    attempts = 10 if transport in ENGINES else 2
    try:
        async with asyncio.timeout(75):
            for attempt in range(attempts):
                try:
                    after = await health_probe(identity, revision, sessions=sessions)
                    last = after["result"]
                except HTTPException:
                    last = {"healthy": False, "status": "probe_unavailable"}
                if last.get("healthy"):
                    return last
                if attempt + 1 < attempts:
                    await pause(3)
    except TimeoutError:
        pass
    return last


class Run:
    def __init__(self, job, *, sessions=GetDB, deploy=operation.deploy, health_probe=probe, pause=asyncio.sleep):
        self.job, self.sessions, self.deploy, self.health_probe, self.pause = job, sessions, deploy, health_probe, pause
        self.request = WizardPlanInput.model_validate(job.payload["request"])
        self.order = list(job.payload["candidates"])
        self.restart = None
        self.advance = None
        self.current = None
        self.created = {}
        self.steps = [
            {
                "id": item,
                "label": CATALOG[item].label,
                "transport": CATALOG[item].transport,
                "profile": CATALOG[item].profile,
                "state": "pending",
                "tunnel_id": None,
                "metrics": None,
                "score": None,
                "reason": None,
            }
            for item in self.order
        ]
        for row in job.payload.get("skipped", []):
            spec = CATALOG[row["id"]]
            self.steps.append(
                {
                    "id": spec.id,
                    "label": spec.label,
                    "transport": spec.transport,
                    "profile": spec.profile,
                    "state": "skipped",
                    "tunnel_id": None,
                    "metrics": None,
                    "score": None,
                    "reason": row["reason"],
                }
            )

    def step(self, identity):
        return next(item for item in self.steps if item["id"] == identity)

    async def publish(self, state=None, progress=None):
        async with self.sessions() as db:
            row = await db.get(NativeProvisionJob, self.job.id, with_for_update=True)
            if not row or row.lease != self.job.lease:
                raise ClaimLost()
            row.result = {
                "mode": self.request.mode,
                "priority": self.request.priority,
                "steps": self.steps,
                "scope": "verified_encrypted_carrier",
            }
            await db.commit()
        if self.advance and (state or progress is not None):
            await self.advance(state or "testing", progress)

    async def cancelled(self):
        async with self.sessions() as db:
            row = await db.get(NativeProvisionJob, self.job.id)
            return bool(row and row.cancel_requested)

    async def revision(self, identity):
        async with self.sessions() as db:
            row = await db.get(NativeTunnel, identity)
            return row.revision if row else None

    async def add_candidate(self, spec):
        async with self.sessions() as db:
            rows = await operation.rows(db)
            nodes = [await db.get(Node, self.request.source_node_id), await db.get(Node, self.request.exit_node_id)]
            cores = {
                core.id: core
                for core in (
                    await db.scalars(
                        select(CoreConfig).where(CoreConfig.id.in_({node.core_config_id or 1 for node in nodes}))
                    )
                ).all()
            }
            body = tunnel_input(
                spec,
                name=f"wizard-{self.job.id[:8]}-{spec.id}",
                request=self.request,
                ports=allocate(rows, nodes, cores, await reserved_by_address(db)),
            )
            row = await operation.create(db, body, "wizard:" + self.job.actor)
            return row.id

    async def switch(self, new_id):
        """Disable the previous candidate and enable the next in one fenced deployment."""
        old_id = self.current
        if old_id == new_id:
            return True, None

        async def prepare(db):
            owner = await db.get(NativeProvisionJob, self.job.id, with_for_update=True)
            if not owner or owner.lease != self.job.lease:
                raise HTTPException(409, "Wizard no longer owns this deployment")
            for identity, enabled in ((old_id, False), (new_id, True)):
                if identity is None:
                    continue
                row = await db.get(NativeTunnel, identity, with_for_update=True, populate_existing=True)
                if row is not None and row.enabled != enabled:
                    row.enabled = enabled
                    row.revision += 1
            operation.audit(db, "system", "wizard.switch", self.job.id, previous=old_id, active=new_id)

        try:
            result = await self.deploy(
                "wizard:" + self.job.actor, session_factory=self.sessions, restart=self.restart, prepare=prepare
            )
        except HTTPException as error:
            return False, error.detail if isinstance(error.detail, str) else "deploy_rejected"
        # The prepared state is committed once the deployment lease is taken.
        self.current = new_id
        return (True, None) if result.get("ok") else (False, "apply_failed")

    async def try_candidate(self, spec_id, spec, index):
        step = self.step(spec_id)
        try:
            identity = await self.add_candidate(spec)
        except HTTPException as error:
            step.update(state="skipped", reason=error.detail if isinstance(error.detail, str) else "rejected")
            return None
        self.created[spec_id] = identity
        step.update(state="deploying", tunnel_id=identity)
        await self.publish("testing", 5 + int(85 * index / max(len(self.order), 1)))
        ok, reason = await self.switch(identity)
        if not ok:
            step.update(state="apply_failed", reason=reason)
            return False
        step["state"] = "measuring"
        await self.publish()
        measured = await measure(
            identity, await self.revision(identity), spec.transport, self.sessions, self.health_probe, self.pause
        )
        metrics = clean(measured)
        if measured.get("healthy"):
            step.update(state="healthy", metrics=metrics, score=score(metrics, self.request.priority), reason=None)
        else:
            step.update(state="unhealthy", metrics=metrics or None, reason=str(measured.get("status", "unhealthy")))
        return bool(measured.get("healthy"))

    async def cleanup(self, keep):
        removed, leftovers = [], []
        for spec_id, identity in self.created.items():
            if identity == keep:
                continue
            try:
                async with self.sessions() as db:
                    row = await db.get(NativeTunnel, identity)
                    if row is not None:
                        await operation.remove(db, identity, row.revision, "wizard:" + self.job.actor)
                removed.append(spec_id)
            except HTTPException:
                leftovers.append(f"wizard-{self.job.id[:8]}-{spec_id}")
        return removed, leftovers

    async def select(self):
        """Keep the best measured carrier that still verifies after being re-enabled."""
        for candidate in rank(self.steps):
            identity = self.created[candidate["id"]]
            if identity != self.current:
                await self.publish("selecting", 92)
                ok, reason = await self.switch(identity)
                if not ok:
                    candidate.update(state="apply_failed", reason=reason)
                    continue
                measured = await measure(
                    identity,
                    await self.revision(identity),
                    candidate["transport"],
                    self.sessions,
                    self.health_probe,
                    self.pause,
                )
                if not measured.get("healthy"):
                    candidate.update(state="unconfirmed", reason="reverify_failed")
                    continue
                candidate["metrics"] = clean(measured)
            return candidate
        return None

    async def execute(self):
        cancelled = False
        failures = 0
        try:
            for index, spec_id in enumerate(self.order):
                if await self.cancelled():
                    cancelled = True
                    break
                outcome = await self.try_candidate(spec_id, CATALOG[spec_id], index)
                failures = failures + 1 if outcome is False and self.step(spec_id)["state"] == "apply_failed" else 0
                await self.publish()
                if outcome and self.request.mode == "fast":
                    break
                if failures >= 2:
                    break
            winner = await self.select()
            if winner is None:
                await self.switch(None)
            keep = self.created.get(winner["id"]) if winner else None
            removed, leftovers = await self.cleanup(keep)
            result = {
                "mode": self.request.mode,
                "priority": self.request.priority,
                "steps": self.steps,
                "removed": removed,
                "cleanup_required": leftovers,
                "scope": "verified_encrypted_carrier",
                "user_inbound_readiness": "not_probed",
            }
            if winner:
                result["winner"] = {
                    key: winner[key] for key in ("id", "label", "transport", "profile", "tunnel_id", "metrics", "score")
                }
                return "ready", result
            result["code"] = "cancelled" if cancelled else "no_healthy_carrier"
            return ("cancelled" if cancelled else "failed"), result
        except asyncio.CancelledError, ClaimLost:
            raise
        except Exception:
            # Best effort only: never leave a half-tested candidate enabled.
            try:
                await self.switch(None)
                _, leftovers = await self.cleanup(None)
            except Exception:
                leftovers = [f"wizard-{self.job.id[:8]}-{spec_id}" for spec_id in self.created]
            return "failed", {"code": "wizard_error", "steps": self.steps, "cleanup_required": leftovers}


async def run(job, *, sessions=GetDB, advance=None, deploy=operation.deploy, health_probe=probe, restart=None):
    """Entry point used by the native job worker. Returns ``(state, result)``."""
    wizard = Run(job, sessions=sessions, deploy=deploy, health_probe=health_probe)
    wizard.advance = advance
    managed = {
        node_id
        for role, node_id in (("source", wizard.request.source_node_id), ("exit", wizard.request.exit_node_id))
        if job.payload.get("ingress", {}).get(role)
    }
    wizard.restart = restart if restart is not None else restarter(managed)
    return await wizard.execute()
