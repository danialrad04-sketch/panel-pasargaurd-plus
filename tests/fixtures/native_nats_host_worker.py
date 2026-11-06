"""Real isolated backend-role host/core cache worker for cluster acceptance."""

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from app.core.hosts import host_manager
from app.core.manager import core_manager
from app.db.base import GetDB, engine
from app.nats.router import router


async def main():
    async with GetDB() as db:
        await core_manager.initialize(db)
        await host_manager.setup(db)
    await router.start()
    async with asyncio.timeout(15):
        while not router._nc or not router._nc.is_connected or not router._nc._subs:
            await asyncio.sleep(0.02)
    await router._nc.flush()
    print("READY", flush=True)
    try:
        while line := await asyncio.to_thread(sys.stdin.readline):
            if line.strip() == "stop":
                break
            if line.strip() == "state":
                hosts = await host_manager.get_hosts()
                print("STATE " + json.dumps(sorted(hosts.keys())), flush=True)
    finally:
        await router.stop()
        for manager in (core_manager, host_manager):
            if manager._nc:
                await manager._nc.close()
        await engine.dispose()


asyncio.run(main())
