# ruff: noqa: F811
"""Opt-in real three-server JetStream, two backend processes and node-role updates."""

import asyncio
import json
import os
import socket
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import nats
import pytest
import pytest_asyncio

from app.db.models import NativeTunnel
from app.models.tunnel import TunnelHostPublish
from app.nats.router import router
from app.tunnel.hosts import publish_host, refresh_tunnel_hosts
from config import nats_settings, runtime_settings
from role import Role
from tests.test_tunnel_subscription_hosts import OWNER, native, publication  # noqa: F401


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest_asyncio.fixture(loop_scope="function")
async def cluster(tmp_path, monkeypatch):
    binary = os.environ.get("NATS_SERVER_BINARY")
    if not binary:
        pytest.skip("Set NATS_SERVER_BINARY for real cluster acceptance")
    ports = [free_port() for _ in range(6)]
    commands = []
    processes = []
    for index in range(3):
        routes = ",".join(f"nats://127.0.0.1:{ports[3 + j]}" for j in range(3) if j != index)
        commands.append(
            [
                binary,
                "-js",
                "-a",
                "127.0.0.1",
                "-p",
                str(ports[index]),
                "-sd",
                str(tmp_path / f"nats-{index}"),
                "--name",
                f"node-{index}",
                "--cluster_name",
                "native-test",
                "--cluster",
                f"nats://127.0.0.1:{ports[3 + index]}",
                "--routes",
                routes,
            ]
        )

    def start(index):
        return subprocess.Popen(commands[index], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    processes[:] = [start(index) for index in range(3)]
    urls = ",".join(f"nats://127.0.0.1:{port}" for port in ports[:3])
    nc = None
    try:
        async with asyncio.timeout(20):
            while True:
                try:
                    nc = await nats.connect(urls.split(","), connect_timeout=0.2, reconnect_time_wait=0.2)
                    await nc.jetstream().account_info()
                    await nc.jetstream().create_key_value(bucket="cluster_readiness", replicas=3)
                    break
                except Exception:
                    if nc:
                        await nc.close()
                    await asyncio.sleep(0.1)
        monkeypatch.setattr(nats_settings, "enabled", True)
        monkeypatch.setattr(nats_settings, "url", urls)
        monkeypatch.setattr(nats_settings, "kv_replicas", 3)
        yield SimpleNamespace(urls=urls, nc=nc, processes=processes, start=start)
    finally:
        if nc:
            await nc.close()
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            await asyncio.to_thread(process.wait, timeout=10)


async def worker(urls, database):
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        str(Path(__file__).parent / "fixtures/native_nats_host_worker.py"),
        env={
            **os.environ,
            "ROLE": "backend",
            "NATS_ENABLED": "1",
            "NATS_URL": urls,
            "NATS_KV_REPLICAS": "3",
            "SQLALCHEMY_DATABASE_URL": database,
        },
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    async with asyncio.timeout(30):
        while True:
            line = await process.stdout.readline()
            if line.strip() == b"READY":
                return process
            if not line:
                raise RuntimeError((await process.stderr.read()).decode())


async def state(process):
    process.stdin.write(b"state\n")
    await process.stdin.drain()
    async with asyncio.timeout(10):
        while True:
            line = await process.stdout.readline()
            if line.startswith(b"STATE "):
                return json.loads(line[6:])
            if not line:
                raise RuntimeError((await process.stderr.read()).decode())


async def wait_state(processes, identity, present):
    async with asyncio.timeout(25):
        while True:
            states = [await state(process) for process in processes]
            if all((identity in value) == present for value in states):
                return states
            await asyncio.sleep(0.1)


async def stop_worker(process):
    if process.returncode is None:
        process.stdin.write(b"stop\n")
        await process.stdin.drain()
        try:
            await asyncio.wait_for(process.wait(), 5)
        except TimeoutError:
            process.kill()
            await process.wait()


async def test_two_backend_caches_survive_cluster_failure_and_stale_kv(publication, cluster, monkeypatch):
    sessions, row, template = publication
    async with sessions() as db:
        host = await publish_host(
            db, row.id, TunnelHostPublish(expected_revision=1, template_host_id=template.id), OWNER
        )
    url = str(sessions.kw["bind"].url)
    workers = []
    try:
        workers = [await worker(cluster.urls, url), await worker(cluster.urls, url)]
        await wait_state(workers, host.id, True)
        js = cluster.nc.jetstream()
        info = await js.stream_info("KV_host_manager_state")
        assert info.config.num_replicas == 3
        kv = await js.key_value("host_manager_state")
        old_snapshot = (await kv.get("state_v5")).value
        monkeypatch.setattr(runtime_settings, "role", Role.NODE)

        async def change(enabled, publish=True):
            async with sessions() as db:
                tunnel = await db.get(NativeTunnel, row.id)
                tunnel.enabled = enabled
                tunnel.revision += 1
                if enabled:
                    tunnel.applied_revision = tunnel.revision
                await db.commit()
                if publish:
                    await refresh_tunnel_hosts(db, [row.id])
                    await router._nc.flush()

        await change(False)
        await wait_state(workers, host.id, False)
        await change(True)
        await wait_state(workers, host.id, True)
        # Stop every broker: guaranteed lost notification, with workers left alive.
        for process in cluster.processes:
            process.terminate()
        for process in cluster.processes:
            await asyncio.to_thread(process.wait, timeout=10)
        await change(False, publish=False)
        await asyncio.sleep(0.5)
        cluster.processes[:] = [cluster.start(index) for index in range(3)]
        await wait_state(workers, host.id, False)
        # Old replicated KV must not resurrect a host after all API workers restart.
        for process in workers:
            await stop_worker(process)
        workers = []
        async with asyncio.timeout(20):
            while True:
                try:
                    await kv.put("state_v5", old_snapshot)
                    break
                except nats.errors.TimeoutError, nats.errors.NoRespondersError, nats.js.errors.NoStreamResponseError:
                    await asyncio.sleep(0.1)
        workers = [await worker(cluster.urls, url), await worker(cluster.urls, url)]
        values = await wait_state(workers, host.id, False)
        assert all(template.id in value for value in values)
        await change(True)
        await wait_state(workers, host.id, True)
        # Losing the stream leader leaves two replicas and both API processes usable.
        leader = (await js.stream_info("KV_host_manager_state")).cluster.leader
        index = int(leader.rsplit("-", 1)[1])
        cluster.processes[index].terminate()
        await asyncio.to_thread(cluster.processes[index].wait, timeout=10)
        await change(False)
        await wait_state(workers, host.id, False)
    finally:
        for process in workers:
            await stop_worker(process)
        await router.stop()
