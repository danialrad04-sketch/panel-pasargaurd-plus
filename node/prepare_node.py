#!/usr/bin/env python3
"""Prepare an independent native node identity without replacing existing files."""

import argparse
import ipaddress
import json
import os
import subprocess
import tempfile
import uuid
from pathlib import Path


def node_address(value):
    address = ipaddress.ip_address(value)
    if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local:
        raise argparse.ArgumentTypeError("Use a reachable numeric management IP")
    return str(address)


def port(value):
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("Port must be between 1 and 65535")
    return number


def write_private(path, data, created, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    created.append(path)
    with os.fdopen(fd, "w") as output:
        output.write(data)


def prepare(args):
    state = args.state_dir.absolute()
    if any(character in str(state) for character in ("\n", "\r", '"', "$", "`")):
        raise ValueError("State directory contains unsupported characters")
    cert_dir = state / "certs"
    cert = cert_dir / "ssl_cert.pem"
    key = cert_dir / "ssl_key.pem"
    targets = [args.env_file.absolute(), args.registration_file.absolute(), cert, key]
    if len(set(targets)) != 4 or any(path.exists() or path.is_symlink() for path in targets):
        raise FileExistsError("Refusing to overwrite an existing identity or registration file")
    health_port = getattr(args, "health_port", 62053)
    if not 1024 <= health_port <= 65535:
        raise ValueError("Internal health port must be between 1024 and 65535")
    if len({args.port, args.api_port, args.tunnel_port, health_port}) != 4:
        raise ValueError("Node control, API and tunnel ports must be distinct")
    for path in targets:
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    created = []
    try:
        # OpenSSL writes only into a private temporary directory; publication uses O_EXCL.
        with tempfile.TemporaryDirectory(prefix="prepare-node-") as directory:
            temporary = Path(directory)
            subprocess.run(
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
                    "subjectAltName=" + ",".join(f"IP:{ip}" for ip in args.address),
                    "-keyout",
                    str(temporary / "key.pem"),
                    "-out",
                    str(temporary / "cert.pem"),
                ],
                check=True,
                capture_output=True,
            )
            certificate = (temporary / "cert.pem").read_text()
            write_private(key, (temporary / "key.pem").read_text(), created)
            write_private(cert, certificate, created, mode=0o644)
        api_key = str(uuid.uuid4())
        settings = {
            "name": args.name,
            "address": args.address[0],
            "port": args.port,
            "api_port": args.api_port,
            "tunnel_api_port": args.tunnel_port,
            "health_echo_port": health_port,
            "connection_type": "grpc",
            "server_ca": certificate,
            "api_key": api_key,
            "core_config_id": 1,
            "keep_alive": 60,
        }
        environment = (
            f'PG_NODE_DATA_DIR="{state}"\nSERVICE_PORT={args.port}\nSERVICE_PROTOCOL=grpc\n'
            f"API_KEY={api_key}\nSSL_CERT_FILE=/var/lib/pg-node/certs/ssl_cert.pem\n"
            "SSL_KEY_FILE=/var/lib/pg-node/certs/ssl_key.pem\n"
            f"PG_NODE_PAQET_ENABLED=true\nPG_NODE_PAQET_PORT={args.tunnel_port}\nPG_NODE_HEALTH_PORT={health_port}\n"
        )
        write_private(targets[0], environment, created)
        write_private(targets[1], json.dumps(settings, ensure_ascii=False, indent=2) + "\n", created)
    except BaseException:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise
    return targets[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", type=node_address, action="append", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--state-dir", type=Path, default=Path("/var/lib/pg-node"))
    parser.add_argument("--env-file", type=Path, default=Path(__file__).parent / ".env")
    parser.add_argument("--registration-file", type=Path, default=Path(__file__).parent / "node-registration.json")
    parser.add_argument("--port", type=port, default=62050)
    parser.add_argument("--api-port", type=port, default=62051)
    parser.add_argument("--tunnel-port", type=port, default=62052)
    parser.add_argument("--health-port", type=port, default=62053)
    args = parser.parse_args()
    try:
        registration = prepare(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Node preparation failed: {type(error).__name__}. Existing files were preserved.\n")
    print(f"Registration file created: {registration}. Keep it private; review it in the panel before saving.")


if __name__ == "__main__":
    main()
