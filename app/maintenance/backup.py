"""Consistent SQLite snapshots in an authenticated, streaming encrypted archive.

Restores only into a new directory. Never replaces a live database or executes SQL
from an untrusted archive. The encryption key must be stored separately.
"""

import hashlib
import json
import os
import shutil
import sqlite3
import stat
import tarfile
import tempfile
from contextlib import closing
from datetime import UTC, datetime
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b"PGPLUS-BACKUP\x01"
CHUNK = 1024 * 1024
MAX_ARCHIVE = 16 * 1024**3


def keygen(path):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
        output.write(os.urandom(32))
        output.flush()
        os.fsync(output.fileno())


def read_key(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ValueError("Backup key must be a regular private file (chmod 600)")
    value = path.read_bytes()
    if len(value) != 32:
        raise ValueError("Backup key must contain exactly 32 random bytes")
    return value


def file_hash(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for data in iter(lambda: source.read(CHUNK), b""):
            digest.update(data)
    return digest.hexdigest()


def check_database(path):
    with closing(sqlite3.connect(f"file:{Path(path).resolve()}?mode=ro", uri=True)) as db:
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("Snapshot failed SQLite integrity checking")


def create(database, destination, key_file, *, include=()):
    database, destination = Path(database).resolve(), Path(destination).absolute()
    secret = read_key(key_file)
    if destination.exists() or destination.is_symlink():
        raise FileExistsError("Backup destination already exists")
    if not database.is_file() or len(include) > 32:
        raise ValueError("Select an existing SQLite database and at most 32 configuration files")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(prefix=".pg-backup-", dir=destination.parent) as directory:
        temporary = Path(directory)
        snapshot = temporary / "db.sqlite3"
        with (
            closing(sqlite3.connect(f"file:{database}?mode=ro", uri=True)) as source,
            closing(sqlite3.connect(snapshot)) as target,
        ):
            source.backup(target)
        check_database(snapshot)
        files = {"db.sqlite3": snapshot}
        for index, value in enumerate(include):
            value = Path(value)
            if value.resolve() == Path(key_file).resolve():
                raise ValueError("Keep the encryption key outside the backup")
            if not stat.S_ISREG(value.lstat().st_mode) or value.stat().st_size > 64 * CHUNK:
                raise ValueError("Configuration attachments must be regular files of at most 64 MiB")
            # Archive paths are generated, never supplied by the operator or symlink names.
            name = f"config/{index:02d}-{value.name}"
            copied = temporary / f"attachment-{index}"
            shutil.copyfile(value, copied)
            files[name] = copied
        from app.version import __version__

        manifest = {
            "format": 1,
            "created_at": datetime.now(UTC).isoformat(),
            "version": __version__,
            "files": {name: {"size": file.stat().st_size, "sha256": file_hash(file)} for name, file in files.items()},
        }
        if sum(item["size"] for item in manifest["files"].values()) > MAX_ARCHIVE - CHUNK:
            raise ValueError("Backup exceeds the 16 GiB archive limit")
        metadata = temporary / "manifest.json"
        metadata.write_text(json.dumps(manifest))
        archive = temporary / "snapshot.tar"
        with tarfile.open(archive, "w") as output:
            output.add(metadata, arcname="manifest.json", recursive=False)
            for name, file in files.items():
                output.add(file, arcname=name, recursive=False)
        iv = os.urandom(12)
        cipher = Cipher(algorithms.AES(secret), modes.GCM(iv)).encryptor()
        cipher.authenticate_additional_data(MAGIC)
        encrypted = temporary / "encrypted"
        with archive.open("rb") as source, encrypted.open("wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(MAGIC + iv)
            for data in iter(lambda: source.read(CHUNK), b""):
                output.write(cipher.update(data))
            output.write(cipher.finalize())
            output.write(cipher.tag)
            output.flush()
            os.fsync(output.fileno())
        # Atomic publication without overwriting another backup, including a symlink.
        os.link(encrypted, destination)
    return {
        "created_at": manifest["created_at"],
        "version": manifest["version"],
        "bytes": destination.stat().st_size,
        "sha256": file_hash(destination),
    }


def restore(backup, output_dir, key_file):
    backup, output_dir = Path(backup), Path(output_dir).absolute()
    secret = read_key(key_file)
    if output_dir.exists() or output_dir.is_symlink():
        raise FileExistsError("Restore directory must not exist; stop services before manually activating a restore")
    if not backup.is_file() or not len(MAGIC) + 28 < backup.stat().st_size <= MAX_ARCHIVE:
        raise ValueError("Invalid or oversized backup")
    output_dir.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(prefix=".pg-restore-", dir=output_dir.parent) as directory:
        temporary = Path(directory)
        archive = temporary / "verified.tar"
        with backup.open("rb") as source:
            if source.read(len(MAGIC)) != MAGIC:
                raise ValueError("Unsupported backup format")
            iv = source.read(12)
            source.seek(-16, 2)
            tag = source.read(16)
            remaining = backup.stat().st_size - len(MAGIC) - 28
            source.seek(len(MAGIC) + 12)
            cipher = Cipher(algorithms.AES(secret), modes.GCM(iv, tag)).decryptor()
            cipher.authenticate_additional_data(MAGIC)
            with archive.open("wb") as target:
                while remaining:
                    data = source.read(min(CHUNK, remaining))
                    if not data:
                        raise ValueError("Truncated backup")
                    remaining -= len(data)
                    target.write(cipher.update(data))
                target.write(cipher.finalize())  # Authentication completes before archive parsing.
        extracted = temporary / "files"
        extracted.mkdir(mode=0o700)
        with tarfile.open(archive, "r:") as source:
            members = source.getmembers()
            names = [item.name for item in members]
            if len(members) > 34 or len(set(names)) != len(names) or "manifest.json" not in names:
                raise ValueError("Invalid backup inventory")
            for item in members:
                if (
                    not item.isfile()
                    or item.size > MAX_ARCHIVE
                    or (
                        item.name not in ("manifest.json", "db.sqlite3")
                        and not (item.name.startswith("config/") and len(Path(item.name).parts) == 2)
                    )
                ):
                    raise ValueError("Unexpected backup entry")
                if any(part in ("..", ".") for part in Path(item.name).parts) or Path(item.name).is_absolute():
                    raise ValueError("Invalid backup path")
            metadata = source.extractfile("manifest.json")
            if metadata is None or source.getmember("manifest.json").size > CHUNK:
                raise ValueError("Invalid manifest")
            manifest = json.load(metadata)
            if (
                manifest.get("format") != 1
                or set(manifest.get("files", {})) != set(names) - {"manifest.json"}
                or "db.sqlite3" not in manifest["files"]
            ):
                raise ValueError("Manifest does not match archive")
            for item in members:
                if item.name == "manifest.json":
                    continue
                target = extracted / item.name
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with source.extractfile(item) as content, target.open("xb") as output:
                    os.fchmod(output.fileno(), 0o600)
                    shutil.copyfileobj(content, output, CHUNK)
                expected = manifest["files"][item.name]
                if expected["size"] != target.stat().st_size or expected["sha256"] != file_hash(target):
                    raise ValueError("Manifest checksum failed")
        check_database(extracted / "db.sqlite3")
        # Remote runtimes may have advanced since the snapshot. Require a fresh
        # native apply and suspend automation before publishing restored routes.
        with closing(sqlite3.connect(extracted / "db.sqlite3")) as restored:
            tables = {row[0] for row in restored.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table, statement in {
                "native_tunnels": "UPDATE native_tunnels SET applied_revision=0",
                "smartdns_node_state": "UPDATE smartdns_node_state SET applied_revision=0, lease=NULL, lease_until=NULL",
                "tunnel_deployment": "UPDATE tunnel_deployment SET lease=NULL, lease_until=NULL",
                "tunnel_health_state": "DELETE FROM tunnel_health_state",
                "tunnel_failover_policies": "UPDATE tunnel_failover_policies SET suspended=1, lease=NULL, lease_until=NULL",
                "native_maintenance_jobs": "UPDATE native_maintenance_jobs SET lease=NULL, lease_until=NULL",
                "temp_keys": "DELETE FROM temp_keys",
            }.items():
                if table in tables:
                    restored.execute(statement)
            restored.commit()
        # Exclusive directory creation is the final publication fence.
        output_dir.mkdir(mode=0o700)
        try:
            for item in extracted.iterdir():
                shutil.move(str(item), output_dir / item.name)
        except BaseException:
            shutil.rmtree(output_dir)
            raise
    return {"created_at": manifest["created_at"], "version": manifest["version"], "files": len(manifest["files"])}


def verify(archive, key_file):
    archive = Path(archive)
    with tempfile.TemporaryDirectory(prefix=".pg-verify-", dir=archive.parent) as directory:
        result = restore(archive, Path(directory) / "restored", key_file)
    result.update(
        {
            "verified_at": datetime.now(UTC).isoformat(),
            "sha256": file_hash(archive),
            "bytes": archive.stat().st_size,
            "mtime_ns": archive.stat().st_mtime_ns,
        }
    )
    receipt = archive.with_suffix(archive.suffix + ".verified.json")
    with tempfile.NamedTemporaryFile(mode="w", prefix=".receipt-", dir=archive.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            os.fchmod(output.fileno(), 0o600)
            json.dump(result, output)
            output.flush()
            os.fsync(output.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    os.replace(temporary, receipt)
    return result
