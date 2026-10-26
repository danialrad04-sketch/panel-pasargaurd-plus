"""Single-claim scheduled backup with verification before retention."""

import asyncio
import json
import shutil
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from sqlalchemy.engine import make_url

from app.db.base import GetDB, begin_immediate_if_sqlite
from app.db.models import NativeMaintenanceJob
from app.maintenance import backup
from app.utils.helpers import fix_datetime_timezone
from config import database_settings, native_maintenance_settings as settings, server_settings


def snapshot():
    url = make_url(database_settings.url)
    if url.get_backend_name() != "sqlite" or not url.database or url.database == ":memory:":
        raise ValueError("Use a database-native external backup adapter for this backend")
    if not settings.backup_key_file:
        raise ValueError("Configure a separate private backup key")
    database = Path(url.database).resolve()
    directory = Path(settings.backup_directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if shutil.disk_usage(directory).free < database.stat().st_size * 3 + 128 * 1024**2:
        raise ValueError("Insufficient temporary space for verified backup")
    attachments = []
    for value in (settings.backup_config_file, server_settings.ssl_certfile, server_settings.ssl_keyfile):
        if value and Path(value).is_file() and Path(value) not in attachments:
            attachments.append(Path(value))
    archive = directory / ("pgplus-" + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8] + ".pgb")
    backup.create(database, archive, settings.backup_key_file, include=attachments)
    verified = backup.verify(archive, settings.backup_key_file)
    # Retention only touches verified, generated archives in this dedicated directory.
    safe = []
    for path in directory.glob("pgplus-*.pgb"):
        receipt = path.with_suffix(path.suffix + ".verified.json")
        try:
            data = json.loads(receipt.read_text())
            if data["bytes"] == path.stat().st_size and data["mtime_ns"] == path.stat().st_mtime_ns:
                safe.append(path)
        except OSError, ValueError, KeyError:
            continue
    for path in sorted(safe, key=lambda value: value.stat().st_mtime, reverse=True)[settings.backup_retain :]:
        path.unlink()
        path.with_suffix(path.suffix + ".verified.json").unlink(missing_ok=True)
    return {
        "ok": True,
        "verified_at": verified["verified_at"],
        "bytes": verified["bytes"],
        "sha256": verified["sha256"],
    }


async def scheduled_backup(*, sessions=GetDB, run_snapshot=snapshot):
    if not settings.backup_enabled:
        return
    token = str(uuid4())
    now = datetime.now(UTC)
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        state = await db.get(NativeMaintenanceJob, "backup", with_for_update=True)
        if state is None:
            state = NativeMaintenanceJob(name="backup")
            db.add(state)
        if state.lease and state.lease_until and fix_datetime_timezone(state.lease_until) > now:
            return
        if state.last_run and now - fix_datetime_timezone(state.last_run) < timedelta(
            hours=settings.backup_interval_hours
        ):
            return
        state.lease, state.lease_until = token, now + timedelta(hours=1)
        await db.commit()
    result = {"ok": False, "status": "backup_failed"}
    cancelled = False
    try:
        result = await asyncio.to_thread(run_snapshot)
    except asyncio.CancelledError:
        # Cancelling to_thread does not stop the snapshot thread. Keep its lease
        # until expiry so another worker cannot start an overlapping snapshot.
        cancelled = True
        raise
    except OSError, ValueError, RuntimeError:
        pass
    finally:
        if not cancelled:
            await finish_backup_claim(sessions, token, result)


async def finish_backup_claim(sessions, token, result):
    async with sessions() as db:
        await begin_immediate_if_sqlite(db)
        state = await db.get(NativeMaintenanceJob, "backup", with_for_update=True)
        if state and state.lease == token:
            state.lease = state.lease_until = None
            state.last_run = datetime.now(UTC)
            if not result.get("ok"):
                state.last_run -= timedelta(hours=settings.backup_interval_hours) - timedelta(minutes=15)
            state.last_result = result
            await db.commit()
