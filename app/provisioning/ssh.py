"""Pinned SSH bootstrap. No secrets in commands, output, job views or logs."""

import asyncio
import json
from pathlib import Path

import asyncssh

from app.provisioning import bundles

REMOTE_CODES = {
    "unsupported_os",
    "systemd_required",
    "unsupported_architecture",
    "architecture_mismatch",
    "root_or_passwordless_sudo_required",
    "unsafe_state_path",
    "unmanaged_service",
    "unmanaged_identity",
    "existing_identity_missing",
    "existing_identity_mismatch",
    "management_port_conflict",
    "dependencies_failed",
    "bundle_integrity_failed",
    "invalid_bundle",
    "bundle_too_large",
    "invalid_release",
    "release_integrity_failed",
    "insufficient_disk",
    "install_in_progress",
    "unmanaged_current_path",
    "panel_source_ip_unavailable",
    "too_many_management_peers",
    "command_failed",
    "invalid_job",
    "native_install_failed",
}


class ProvisionError(RuntimeError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


async def bootstrap(payload, credentials, identity, update, expected=None):
    key = asyncssh.import_public_key(payload["server_host_key"])
    private = (
        asyncssh.import_private_key(credentials["private_key"], credentials.get("key_passphrase"))
        if credentials.get("private_key")
        else None
    )
    try:
        async with (
            asyncio.timeout(1800),
            asyncssh.connect(
                payload["host"],
                port=payload["ssh_port"],
                username=payload["username"],
                password=credentials.get("password"),
                client_keys=[private] if private else [],
                agent_path=None,
                known_hosts=([key], [], []),
                config=None,
                connect_timeout=10,
                login_timeout=15,
                keepalive_interval=15,
                keepalive_count_max=3,
            ) as connection,
        ):
            await update("checking_server", 10)
            sudo = "" if payload["username"] == "root" else "sudo -n "
            info = await connection.run("uname -s; uname -m", check=False, timeout=10)
            values = info.stdout.splitlines()
            if len(values) != 2 or values[0] != "Linux":
                raise ProvisionError("unsupported_os")
            architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(values[1])
            if not architecture:
                raise ProvisionError("unsupported_architecture")
            artifact, metadata = await asyncio.to_thread(bundles.select, architecture)
            check = await connection.run(
                sudo
                + "sh -c "
                + "'command -v python3 >/dev/null || (DEBIAN_FRONTEND=noninteractive apt-get -qq update >/dev/null 2>&1 && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y python3 >/dev/null 2>&1)'",
                check=False,
                timeout=600,
            )
            if check.exit_status:
                raise ProvisionError("root_or_python_required")
            python = await connection.run(
                "python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'",
                check=False,
                timeout=10,
            )
            if python.exit_status:
                raise ProvisionError("unsupported_python_version")
            working = "/var/tmp/pg-native-" + identity
            async with connection.start_sftp_client() as sftp:
                try:
                    await sftp.mkdir(working, attrs=asyncssh.SFTPAttrs(permissions=0o700))
                except asyncssh.SFTPFileAlreadyExists:
                    pass
                attributes = await sftp.lstat(working)
                import stat

                if not stat.S_ISDIR(attributes.permissions or 0) or stat.S_IMODE(attributes.permissions or 0) & 0o077:
                    raise ProvisionError("unsafe_remote_staging")
                await update("transferring_bundle", 20)
                try:
                    remote_size = (await sftp.stat(working + "/bundle.tar.gz")).size
                except OSError, asyncssh.Error:
                    remote_size = None
                if remote_size != metadata["bytes"]:
                    await sftp.put(str(artifact), working + "/bundle.tar.gz")
                await sftp.chmod(working + "/bundle.tar.gz", 0o600)
                await sftp.put(str(Path(__file__).with_name("remote.py")), working + "/installer.py")
                request = {
                    **payload,
                    "job_id": identity,
                    "address": expected["address"] if expected else payload["host"],
                    "architecture": architecture,
                    "bundle_sha256": metadata["sha256"],
                }
                request.pop("server_host_key", None)
                if expected:
                    request.update(expected_ca=expected["server_ca"], expected_api_key=expected["api_key"])
                async with sftp.open(working + "/request.json", "w") as target:
                    await target.write(json.dumps(request))
                await sftp.chmod(working + "/request.json", 0o600)
                await update("installing_service", 50)
                result = await connection.run(
                    sudo + "python3 " + working + "/installer.py " + working, check=False, timeout=1500
                )
                try:
                    value = json.loads(result.stdout.strip().splitlines()[-1])
                except ValueError, IndexError:
                    raise ProvisionError("remote_install_failed") from None
                if result.exit_status or value.get("ok") is not True:
                    raise ProvisionError(
                        value.get("code") if value.get("code") in REMOTE_CODES else "remote_install_failed"
                    )
                await update("verifying_node", 75)
                if payload["username"] == "root":
                    async with sftp.open(working + "/registration.json", "r") as source:
                        raw = await source.read(16385)
                else:
                    result = await connection.run(
                        "sudo -n cat " + working + "/registration.json", check=False, timeout=10
                    )
                    if result.exit_status:
                        raise ProvisionError("registration_read_failed")
                    raw = result.stdout
                if len(raw) > 16384:
                    raise ProvisionError("invalid_registration")
                registration = json.loads(raw)
                # The root helper's registration file is 0600. Non-root sudo
                # users retrieve it through a fixed, non-logged sudo read below.
                try:
                    await sftp.rmtree(working)
                except OSError, asyncssh.Error:
                    pass
                return registration, metadata
    except asyncssh.HostKeyNotVerifiable:
        raise ProvisionError("ssh_host_key_changed") from None
    except asyncssh.PermissionDenied:
        raise ProvisionError("ssh_authentication_failed") from None
    except (asyncssh.KeyImportError, ValueError) as error:
        raise ProvisionError(
            str(error)
            if str(error) in ("bundle_architecture_unavailable", "bundle_integrity_failed")
            else "invalid_install_input"
        ) from None
    except asyncssh.Error, OSError, TimeoutError:
        raise ProvisionError("ssh_connection_failed") from None
