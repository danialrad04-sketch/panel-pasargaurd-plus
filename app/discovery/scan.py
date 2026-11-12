"""Run the read-only collector over a pinned SSH session. Nothing is written or installed on the server:
the script travels on stdin of ``python3 -I -`` and no secret is stored by the panel."""

import asyncio
import json
from pathlib import Path

import asyncssh

COLLECTOR = Path(__file__).with_name("collector.py")
MAX_OUTPUT = 6 * 1024 * 1024


class ScanError(RuntimeError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


async def collect(body):
    key = asyncssh.import_public_key(body.server_host_key)
    private = (
        asyncssh.import_private_key(
            body.private_key.get_secret_value(), body.key_passphrase.get_secret_value() if body.key_passphrase else None
        )
        if body.private_key
        else None
    )
    script = COLLECTOR.read_text()
    try:
        async with (
            asyncio.timeout(90),
            asyncssh.connect(
                body.host,
                port=body.ssh_port,
                username=body.username,
                password=body.password.get_secret_value() if body.password else None,
                client_keys=[private] if private else [],
                agent_path=None,
                known_hosts=([key], [], []),
                config=None,
                connect_timeout=10,
                login_timeout=15,
            ) as connection,
        ):
            sudo = "" if body.username == "root" else "sudo -n "
            result = await connection.run(sudo + "python3 -I -", input=script, check=False, timeout=70)
    except asyncssh.HostKeyNotVerifiable:
        raise ScanError("ssh_host_key_changed") from None
    except asyncssh.PermissionDenied:
        raise ScanError("ssh_authentication_failed") from None
    except asyncssh.KeyImportError, ValueError:
        raise ScanError("invalid_ssh_input") from None
    except asyncssh.Error, OSError, TimeoutError:
        raise ScanError("ssh_connection_failed") from None
    if result.exit_status == 127 or "python3: command not found" in (result.stderr or ""):
        raise ScanError("python3_required")
    if result.exit_status:
        raise ScanError("root_or_passwordless_sudo_required" if body.username != "root" else "collector_failed")
    out = result.stdout or ""
    if len(out) > MAX_OUTPUT:
        raise ScanError("collector_output_too_large")
    try:
        return json.loads(out.strip().splitlines()[-1])
    except ValueError, IndexError:
        raise ScanError("collector_output_invalid") from None
