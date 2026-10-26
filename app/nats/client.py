import asyncio

import nats
from nats.js.client import JetStreamContext
from nats.js.kv import KeyValue

from config import nats_settings

from . import get_nats_config, is_nats_enabled


async def create_nats_client(**callbacks) -> nats.NATS | None:
    """Create a new NATS connection."""
    if not is_nats_enabled():
        return None
    cfg = get_nats_config()
    servers = [value.strip() for value in cfg["url"].split(",") if value.strip()]
    if not servers:
        raise RuntimeError("At least one NATS server URL is required")
    return await nats.connect(
        servers,
        connect_timeout=2,
        reconnect_time_wait=1,
        max_reconnect_attempts=nats_settings.reconnect_attempts,
        **callbacks,
    )


async def get_jetstream_context(nc: nats.NATS) -> JetStreamContext:
    """Get JetStream context from NATS connection. JetStream is always enabled."""
    return nc.jetstream()


async def get_or_create_kv_bucket(js: JetStreamContext, bucket_name: str) -> KeyValue | None:
    """Bounded quorum startup; never downgrade an existing bucket's replicas."""
    from nats.js.errors import APIError, BucketNotFoundError

    async with asyncio.timeout(20):
        while True:
            try:
                try:
                    return await js.create_key_value(bucket=bucket_name, replicas=nats_settings.kv_replicas)
                except APIError:
                    bucket = await js.key_value(bucket=bucket_name)
                    info = await js.stream_info(f"KV_{bucket_name}")
                    if info.config.num_replicas < nats_settings.kv_replicas:
                        info.config.num_replicas = nats_settings.kv_replicas
                        await js.update_stream(config=info.config)
                    return bucket
            except BucketNotFoundError, nats.errors.TimeoutError, nats.errors.NoRespondersError:
                await asyncio.sleep(0.25)
            except APIError as error:
                if error.code != 503 and error.err_code not in (10005, 10059):
                    raise
                await asyncio.sleep(0.25)


async def setup_nats_kv(bucket_name: str) -> tuple[nats.NATS | None, JetStreamContext | None, KeyValue | None]:
    """
    Set up NATS client, JetStream context, and KV bucket in one call.
    Returns (nc, js, kv) tuple. All will be None if NATS is not enabled.
    """
    if not is_nats_enabled():
        return None, None, None

    nc = await create_nats_client()
    if not nc:
        return None, None, None

    js = await get_jetstream_context(nc)
    kv = await get_or_create_kv_bucket(js, bucket_name)

    return nc, js, kv
