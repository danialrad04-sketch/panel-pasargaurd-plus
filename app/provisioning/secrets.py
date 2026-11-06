"""Short-lived job credentials encrypted under a private persistent local key."""

import base64
import json
import os
import stat
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from config import native_provision_settings as settings


def key():
    path = Path(settings.key_file)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not path.exists():
        import tempfile

        fd, temporary = tempfile.mkstemp(prefix=".provision-key-", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as target:
                os.fchmod(target.fileno(), 0o600)
                target.write(os.urandom(32))
                target.flush()
                os.fsync(target.fileno())
            try:
                os.link(temporary, path, follow_symlinks=False)
            except FileExistsError:
                pass
        finally:
            Path(temporary).unlink(missing_ok=True)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077:
            raise ValueError("Provisioning key must be a private regular file")
        value = source.read(33)
    if len(value) != 32:
        raise ValueError("Invalid provisioning key")
    return value


def seal(value, identity):
    nonce = os.urandom(12)
    return base64.b64encode(
        nonce + AESGCM(key()).encrypt(nonce, json.dumps(value).encode(), identity.encode())
    ).decode()


def unseal(value, identity):
    raw = base64.b64decode(value, validate=True)
    return json.loads(AESGCM(key()).decrypt(raw[:12], raw[12:], identity.encode()))
