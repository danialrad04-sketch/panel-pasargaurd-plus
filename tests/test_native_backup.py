import sqlite3
import stat

import pytest
from cryptography.exceptions import InvalidTag

from app.maintenance import backup


def database(tmp_path):
    path = tmp_path / "live.db"
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("CREATE TABLE users (name TEXT, secret TEXT)")
        db.execute("INSERT INTO users VALUES ('existing-user', 'private-secret')")
        db.commit()
    key = tmp_path / "key"
    backup.keygen(key)
    return path, key


def test_encrypted_snapshot_restores_data_config_and_permissions(tmp_path):
    source, key = database(tmp_path)
    config = tmp_path / ".env"
    config.write_text("PRIVATE_SETTING=must-not-be-plaintext")
    archive = tmp_path / "backup.pgb"
    metadata = backup.create(source, archive, key, include=[config])
    wire = archive.read_bytes()
    assert b"private-secret" not in wire and b"must-not-be-plaintext" not in wire
    assert metadata["sha256"] == backup.file_hash(archive)
    output = tmp_path / "restored"
    backup.restore(archive, output, key)
    with sqlite3.connect(output / "db.sqlite3") as db:
        assert db.execute("SELECT * FROM users").fetchall() == [("existing-user", "private-secret")]
    assert (output / "config/00-.env").read_text() == config.read_text()
    assert stat.S_IMODE(archive.stat().st_mode) == 0o600
    assert stat.S_IMODE((output / "db.sqlite3").stat().st_mode) == 0o600


def test_tampering_wrong_key_and_existing_restore_are_refused(tmp_path):
    source, key = database(tmp_path)
    archive = tmp_path / "backup.pgb"
    backup.create(source, archive, key)
    with pytest.raises(FileExistsError):
        backup.create(source, archive, key)
    other_key = tmp_path / "other-key"
    backup.keygen(other_key)
    with pytest.raises(InvalidTag):
        backup.restore(archive, tmp_path / "wrong-key", other_key)
    assert not (tmp_path / "wrong-key").exists()
    wire = bytearray(archive.read_bytes())
    wire[len(wire) // 2] ^= 1
    archive.write_bytes(wire)
    with pytest.raises(InvalidTag):
        backup.restore(archive, tmp_path / "tampered", key)
    assert not (tmp_path / "tampered").exists()
    with pytest.raises(FileExistsError):
        backup.restore(archive, tmp_path, key)
    assert source.exists()


def test_wal_snapshot_excludes_uncommitted_changes(tmp_path):
    source, key = database(tmp_path)
    with sqlite3.connect(source) as live:
        live.execute("INSERT INTO users VALUES ('not-committed', 'no')")
        archive = tmp_path / "snapshot.pgb"
        backup.create(source, archive, key)
        live.rollback()
    backup.restore(archive, tmp_path / "restore", key)
    with sqlite3.connect(tmp_path / "restore/db.sqlite3") as db:
        assert db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 1


def test_key_must_be_private_and_cannot_be_overwritten(tmp_path):
    key = tmp_path / "key"
    backup.keygen(key)
    with pytest.raises(FileExistsError):
        backup.keygen(key)
    key.chmod(0o644)
    with pytest.raises(ValueError):
        backup.read_key(key)


def test_restore_never_replays_ssh_installation_or_keeps_queued_credentials(tmp_path):
    source, key = database(tmp_path)
    with sqlite3.connect(source) as db:
        db.execute(
            "CREATE TABLE native_provision_jobs (state TEXT, credential_cipher TEXT, active_target TEXT, lease TEXT, lease_until TEXT)"
        )
        db.execute(
            "INSERT INTO native_provision_jobs VALUES ('installing_service', 'encrypted-secret', 'ssh:192.0.2.10:22', 'old-worker', '2099-01-01')"
        )
        db.commit()
    archive = tmp_path / "snapshot.pgb"
    backup.create(source, archive, key)
    backup.restore(archive, tmp_path / "restored", key)
    with sqlite3.connect(tmp_path / "restored/db.sqlite3") as db:
        assert db.execute("SELECT * FROM native_provision_jobs").fetchone() == ("interrupted", None, None, None, None)
    # A restore modifies only the new offline copy.
    with sqlite3.connect(source) as db:
        assert db.execute("SELECT state FROM native_provision_jobs").fetchone() == ("installing_service",)
