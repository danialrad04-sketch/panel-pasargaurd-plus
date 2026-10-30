"""Read only installed native build artifacts; no runtime GitHub downloads."""

import hashlib
import json
from pathlib import Path

from config import native_provision_settings as settings


def available():
    result = []
    for architecture in ("amd64", "arm64"):
        path = Path(settings.bundle_directory) / f"native-linux-{architecture}.json"
        if not path.is_file():
            continue
        value = json.loads(path.read_text())
        if value.get("file") != f"native-linux-{architecture}.tar.gz" or value.get("architecture") != architecture:
            continue
        artifact = path.parent / value["file"]
        if artifact.is_file() and not artifact.is_symlink() and artifact.stat().st_size == value.get("bytes"):
            result.append(value)
    return result


def select(architecture):
    value = next((entry for entry in available() if entry["architecture"] == architecture), None)
    if not value:
        raise ValueError("bundle_architecture_unavailable")
    path = Path(settings.bundle_directory) / value["file"]
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != value["sha256"]:
        raise ValueError("bundle_integrity_failed")
    return path, value
