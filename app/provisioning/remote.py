"""Fixed, root-only native installer uploaded over pinned SSH. Python 3.10+."""

import ctypes.util
import fcntl
import hashlib
import ipaddress
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import uuid
from pathlib import Path

STATE = Path("/var/lib/pg-node")
ROOT = Path("/opt/pasarguard-native")
UNIT = Path("/etc/systemd/system/pasarguard-native-node.service")
SERVICE = "pasarguard-native-node.service"
BINARIES = {"pg-node", "paqet", "backhaul", "gost", "frps", "frpc", "xray"}


class InstallError(Exception):
    pass


def fail(code):
    raise InstallError(code)


def command(args, timeout=120):
    result = subprocess.run(
        args, check=False, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout
    )
    if result.returncode:
        fail("command_failed")
    return result.stdout.decode(errors="replace")


def write(path, value, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix=".new-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            os.fchmod(output.fileno(), mode)
            output.write(value if isinstance(value, bytes) else value.encode())
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def digest(path):
    with Path(path).open("rb") as source:
        value = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
        return value.hexdigest()


def environment():
    values = {}
    for line in Path("/etc/os-release").read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip('"')
    if values.get("ID") not in ("debian", "ubuntu") or platform.system() != "Linux":
        fail("unsupported_os")
    try:
        version = tuple(int(part) for part in values.get("VERSION_ID", "").split("."))
    except ValueError:
        fail("unsupported_os")
    minimum = (12,) if values["ID"] == "debian" else (22, 4)
    if version < minimum:
        fail("unsupported_os")
    result = subprocess.run(
        ["systemctl", "is-system-running"], check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    if result.stdout.strip() not in (b"running", b"degraded", b"starting"):
        fail("systemd_required")
    architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(platform.machine())
    if not architecture:
        fail("unsupported_architecture")
    return architecture


def dependencies():
    tools = ("openssl", "ip", "iptables", "ping", "wg", "nft")
    if all(shutil.which(tool) for tool in tools) and ctypes.util.find_library("pcap"):
        return
    try:
        env = {**os.environ, "DEBIAN_FRONTEND": "noninteractive"}
        for args in (["apt-get", "-qq", "update"],):
            subprocess.run(args, env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
        probe = subprocess.run(
            ["apt-cache", "show", "libpcap0.8t64"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        lib = "libpcap0.8t64" if probe.returncode == 0 else "libpcap0.8"
        subprocess.run(
            [
                "apt-get",
                "-qq",
                "install",
                "-y",
                "--no-install-recommends",
                lib,
                "ca-certificates",
                "openssl",
                "iproute2",
                "iptables",
                "iputils-ping",
                "wireguard-tools",
                "nftables",
            ],
            env=env,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=600,
        )
    except (OSError, subprocess.SubprocessError):
        fail("dependencies_failed")


def release(archive, expected, architecture, job):
    if digest(archive) != expected:
        fail("bundle_integrity_failed")
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o755)
    releases = ROOT / "releases"
    releases.mkdir(exist_ok=True)
    target = releases / expected
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 32 or len({m.name for m in members}) != len(members):
            fail("invalid_bundle")
        metadata = source.getmember("manifest.json")
        if metadata.size > 65536:
            fail("invalid_bundle")
        manifest = json.load(source.extractfile(metadata))
        required = {"bin/" + name for name in BINARIES} | {"assets/geoip.dat", "assets/geosite.dat"}
        if (
            manifest.get("format") != 1
            or manifest.get("architecture") != architecture
            or not required <= set(manifest.get("files", {}))
        ):
            fail("invalid_bundle")
        if {m.name for m in members} != {"manifest.json", *manifest["files"]}:
            fail("invalid_bundle")
        if sum(m.size for m in members) > 2 * 1024**3:
            fail("bundle_too_large")
        for member in members:
            if not member.isfile() or member.name.startswith("/") or ".." in Path(member.name).parts:
                fail("invalid_bundle")
            if member.name not in {"manifest.json", *required} and not (
                member.name.startswith("licenses/") and len(Path(member.name).parts) == 2
            ):
                fail("invalid_bundle")
        if target.exists():
            if target.is_symlink() or not target.is_dir():
                fail("invalid_release")
            for name, value in manifest["files"].items():
                if digest(target / name) != value["sha256"]:
                    fail("release_integrity_failed")
            return target
        if shutil.disk_usage(ROOT).free < sum(m.size for m in members) * 2 + 128 * 1024**2:
            fail("insufficient_disk")
        with tempfile.TemporaryDirectory(prefix=".release-", dir=releases) as temporary:
            directory = Path(temporary) / "new"
            directory.mkdir(mode=0o755)
            for member in members:
                path = directory / member.name
                path.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as data, path.open("xb") as output:
                    shutil.copyfileobj(data, output, 1024**2)
                if member.name != "manifest.json":
                    record = manifest["files"][member.name]
                    if member.size != record["size"] or digest(path) != record["sha256"]:
                        fail("bundle_integrity_failed")
                path.chmod(0o755 if member.name.startswith("bin/") else 0o644)
            os.replace(directory, target)
    return target


def current_identity(request, working):
    cert = STATE / "certs/ssl_cert.pem"
    key = STATE / "certs/ssl_key.pem"
    marker = STATE / ".native-owner.json"
    if any(p.is_symlink() for p in (STATE, STATE / "certs", cert, key, marker)):
        fail("unsafe_state_path")
    if request.get("expected_ca"):
        if not cert.is_file() or not key.is_file():
            fail("existing_identity_missing")
        ca = working / "expected-ca.pem"
        write(ca, request["expected_ca"])
        args = ["openssl", "verify", "-CAfile", str(ca)]
        try:
            ipaddress.ip_address(request["address"])
            args += ["-verify_ip", request["address"]]
        except ValueError:
            args += ["-verify_hostname", request["address"]]
        args.append(str(cert))
        try:
            command(args)
        except InstallError:
            fail("existing_identity_mismatch")
    elif cert.exists() or key.exists():
        if not marker.is_file() or not cert.is_file() or not key.is_file():
            fail("unmanaged_identity")
    else:
        if marker.exists():
            fail("existing_identity_missing")
        cert.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with tempfile.TemporaryDirectory(dir=cert.parent) as temporary:
            directory = Path(temporary)
            addresses = {request["address"], request["host"]}
            san = ",".join("IP:" + str(ipaddress.ip_address(value)) for value in sorted(addresses))
            command(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:3072",
                    "-sha256",
                    "-nodes",
                    "-days",
                    "365",
                    "-subj",
                    "/CN=PasarGuard native node",
                    "-addext",
                    "subjectAltName=" + san,
                    "-keyout",
                    str(directory / "key"),
                    "-out",
                    str(directory / "cert"),
                ]
            )
            write(key, (directory / "key").read_bytes())
            write(cert, (directory / "cert").read_bytes(), 0o644)
            write(
                marker,
                json.dumps({"managed_by": "pasarguard-plus", "state": "initialized", "job_id": request["job_id"]}),
            )
    api_key = request.get("expected_api_key")
    envfile = STATE / "native.env"
    if not api_key and marker.is_file() and envfile.is_file():
        api_key = next(
            (line.split("=", 1)[1] for line in envfile.read_text().splitlines() if line.startswith("API_KEY=")), None
        )
    api_key = str(uuid.UUID(api_key)) if api_key else str(uuid.uuid4())
    return cert.read_text(), api_key


def old_container(request):
    if not request.get("expected_ca") or not shutil.which("docker"):
        return None
    try:
        ids = command(["docker", "ps", "-q"]).split()
    except InstallError:
        return None
    for identity in ids:
        value = json.loads(command(["docker", "inspect", identity]))[0]
        config = value.get("Config", {})
        if config.get("Entrypoint") != ["/usr/local/bin/pg-node"]:
            continue
        mounts = value.get("Mounts", [])
        if not any(
            m.get("Destination") == "/var/lib/pg-node" and Path(m.get("Source", "")).resolve() == STATE.resolve()
            for m in mounts
        ):
            continue
        environment = config.get("Env", [])
        if "API_KEY=" + request.get("expected_api_key", "") not in environment:
            continue
        return identity
    return None


def firewall(request):
    # Restrict management to the actual SSH bootstrap peer when UFW is active.
    # Never flush or replace existing chains, default policy, or SSH rules.
    value = os.environ.get("SSH_CONNECTION", "").split()
    if not value:
        fail("panel_source_ip_unavailable")
    peer = ipaddress.ip_address(value[0])
    binary = "iptables" if peer.version == 4 else "ip6tables"
    chain = "PG_PLUS_BOOTSTRAP"
    subprocess.run([binary, "-w", "5", "-N", chain], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for port in (request["node_port"], request["tunnel_port"]):
        args = [
            "-s",
            str(peer),
            "-p",
            "tcp",
            "--dport",
            str(port),
            "-m",
            "comment",
            "--comment",
            "pg-native-management",
            "-j",
            "ACCEPT",
        ]
        probe = subprocess.run(
            [binary, "-w", "5", "-C", chain, *args], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        if probe.returncode:
            command([binary, "-w", "5", "-A", chain, *args])
    probe = subprocess.run(
        [binary, "-w", "5", "-C", "INPUT", "-j", chain],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if probe.returncode:
        command([binary, "-w", "5", "-I", "INPUT", "1", "-j", chain])
    record = STATE / "bootstrap/ingress.json"
    previous = json.loads(record.read_text()) if record.is_file() else {"revision": 0, "rules": []}
    sources = {r["source"] for r in previous["rules"]}
    sources.add(str(peer) + "/" + ("32" if peer.version == 4 else "128"))
    if len(sources) > 16:
        fail("too_many_management_peers")
    write(
        record,
        json.dumps(
            {
                "revision": previous["revision"] + 1,
                "rules": [
                    {"port": p, "network": "tcp", "source": source}
                    for source in sorted(sources)
                    for p in (request["node_port"], request["tunnel_port"])
                ],
            }
        ),
    )


def install(request, working):
    if os.geteuid() != 0:
        fail("root_or_passwordless_sudo_required")
    architecture = environment()
    if architecture != request["architecture"]:
        fail("architecture_mismatch")
    if STATE.is_symlink() or ROOT.is_symlink():
        fail("unsafe_state_path")
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (STATE / ".native-install.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail("install_in_progress")
        marker = STATE / ".native-owner.json"
        if UNIT.exists() and not marker.is_file():
            fail("unmanaged_service")
        dependencies()
        cert, key = current_identity(request, working)
        old = old_container(request)
        owned = marker.is_file() and UNIT.is_file()
        for port in (request["node_port"], request["tunnel_port"]):
            try:
                with socket.socket() as listener:
                    listener.bind(("0.0.0.0", port))
            except OSError:
                if not owned and not old:
                    fail("management_port_conflict")
        selected = release(working / "bundle.tar.gz", request["bundle_sha256"], architecture, request["job_id"])
        previous = os.readlink(ROOT / "current") if (ROOT / "current").is_symlink() else None
        if (ROOT / "current").exists() and previous is None:
            fail("unmanaged_current_path")
        prior_unit = UNIT.read_bytes() if UNIT.exists() else None
        envfile = STATE / "native.env"
        prior_env = envfile.read_bytes() if envfile.exists() else None
        environment_text = (
            f"SERVICE_PORT={request['node_port']}\nSERVICE_PROTOCOL=grpc\nAPI_KEY={key}\n"
            f"SSL_CERT_FILE={STATE}/certs/ssl_cert.pem\nSSL_KEY_FILE={STATE}/certs/ssl_key.pem\n"
            f"PG_NODE_SSH_PORT={request['ssh_port']}\nPG_NODE_PAQET_ENABLED=true\nPG_NODE_PAQET_PORT={request['tunnel_port']}\nPG_NODE_HEALTH_PORT={request['health_port']}\n"
            f"PG_NODE_PAQET_BINARY={ROOT}/current/bin/paqet\nPG_NODE_ENGINE_BIN_DIR={ROOT}/current/bin\n"
            f"XRAY_EXECUTABLE_PATH={ROOT}/current/bin/xray\nXRAY_ASSETS_PATH={ROOT}/current/assets\n"
        )
        unit = (
            f"[Unit]\nDescription=PasarGuard native managed node\nAfter=network-online.target\nWants=network-online.target\n"
            f"[Service]\nType=simple\nWorkingDirectory={STATE}\nEnvironmentFile={envfile}\nExecStart={ROOT}/current/bin/pg-node\n"
            "Restart=always\nRestartSec=3\nTimeoutStopSec=30\nKillMode=control-group\nUMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\n"
            "[Install]\nWantedBy=multi-user.target\n"
        )
        try:
            firewall(request)
            if old:
                command(["docker", "stop", "--time", "30", old], timeout=45)
            if owned:
                command(["systemctl", "stop", SERVICE], timeout=45)
            write(envfile, environment_text)
            link = ROOT / (".current-" + request["job_id"])
            link.unlink(missing_ok=True)
            link.symlink_to(selected)
            os.replace(link, ROOT / "current")
            write(UNIT, unit, 0o644)
            command(["systemctl", "daemon-reload"])
            command(["systemctl", "enable", SERVICE])
            command(["systemctl", "restart", SERVICE], timeout=45)
            command(["systemctl", "is-active", "--quiet", SERVICE])
            write(
                marker,
                json.dumps(
                    {"managed_by": "pasarguard-plus", "bundle": request["bundle_sha256"], "job_id": request["job_id"]}
                ),
            )
        except BaseException:
            subprocess.run(
                ["systemctl", "stop", SERVICE],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=45,
            )
            if previous:
                link = ROOT / (".rollback-" + request["job_id"])
                link.unlink(missing_ok=True)
                link.symlink_to(previous)
                os.replace(link, ROOT / "current")
            else:
                (ROOT / "current").unlink(missing_ok=True)
            if prior_unit is not None:
                write(UNIT, prior_unit, 0o644)
            else:
                UNIT.unlink(missing_ok=True)
            if prior_env is not None:
                write(envfile, prior_env)
            subprocess.run(
                ["systemctl", "daemon-reload"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
            if previous and prior_unit is not None:
                subprocess.run(
                    ["systemctl", "start", SERVICE],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=45,
                )
            if old:
                subprocess.run(
                    ["docker", "start", old],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=45,
                )
            raise
        write(
            working / "registration.json",
            json.dumps(
                {
                    "server_ca": cert,
                    "api_key": key,
                    "address": request["address"],
                    "port": request["node_port"],
                    "api_port": 62051,
                    "tunnel_api_port": request["tunnel_port"],
                    "health_echo_port": request["health_port"],
                }
            ),
        )
        return {"ok": True, "bundle": request["bundle_sha256"], "architecture": architecture}


if __name__ == "__main__":
    try:
        working = Path(sys.argv[1]).resolve()
        request = json.loads((working / "request.json").read_text())
        if len(request["job_id"]) != 32 or any(c not in "0123456789abcdef" for c in request["job_id"]):
            fail("invalid_job")
        result = install(request, working)
    except InstallError as error:
        result = {"ok": False, "code": str(error)}
    except BaseException:
        result = {"ok": False, "code": "native_install_failed"}
    print(json.dumps(result))
    sys.exit(0 if result["ok"] else 1)
