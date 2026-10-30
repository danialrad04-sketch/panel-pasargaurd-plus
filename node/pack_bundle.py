"""Package only known native binaries/data with an explicit integrity inventory."""

import argparse
import hashlib
import io
import json
import tarfile
from pathlib import Path

NAMES = ("pg-node", "paqet", "backhaul", "gost", "frps", "frpc", "xray")


def package(source, target, architecture):
    source, target = Path(source), Path(target)
    files = {"bin/" + name: source / ("xray/xray" if name == "xray" else name) for name in NAMES}
    files.update({"assets/" + name: source / "assets" / name for name in ("geoip.dat", "geosite.dat")})
    for name, relative in [
        ("node", "LICENSE"),
        ("paqet", "paqet/LICENSE"),
        ("backhaul", "engines/backhaul/LICENSE"),
        ("gost", "engines/gost/LICENSE"),
        ("frp", "engines/frp/LICENSE"),
    ]:
        path = Path(__file__).parent / relative
        if path.is_file():
            files["licenses/" + name + ".txt"] = path
    inventory = {}
    for name, path in files.items():
        if path.is_symlink() or not path.is_file():
            raise ValueError("Missing source-built bundle member: " + name)
        inventory[name] = {"size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    manifest = {
        "format": 1,
        "platform": "linux",
        "architecture": architecture,
        "files": inventory,
        "engines": ["paqet", "backhaul", "gost", "frp"],
    }
    target.mkdir(parents=True, exist_ok=True)
    output = target / f"native-linux-{architecture}.tar.gz"
    with output.open("xb") as raw, tarfile.open(fileobj=raw, mode="w:gz") as archive:
        for name, path in files.items():
            info = archive.gettarinfo(str(path), arcname=name)
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o755 if name.startswith("bin/") else 0o644
            with path.open("rb") as data:
                archive.addfile(info, data)
        content = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        info = tarfile.TarInfo("manifest.json")
        info.size = len(content)
        info.mode = 0o644
        info.mtime = 0
        archive.addfile(info, io.BytesIO(content))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    metadata = {
        "file": output.name,
        "sha256": digest,
        "bytes": output.stat().st_size,
        "architecture": architecture,
        "engines": manifest["engines"],
        "format": 1,
    }
    (target / f"native-linux-{architecture}.json").write_text(json.dumps(metadata, sort_keys=True) + "\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--architecture", choices=("amd64", "arm64"), required=True)
    args = parser.parse_args()
    package(args.source, args.target, args.architecture)
