import subprocess
import sys
import textwrap


def _setting(env_value):
    code = textwrap.dedent(
        """
        from config import ServerSettings
        print(ServerSettings().allow_http)
        """
    )
    env = {"PATH": "/usr/bin:/bin"}
    if env_value is not None:
        env["UVICORN_ALLOW_INSECURE_HTTP"] = env_value
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, cwd=".", check=False)
    return out.stdout.strip(), out.stderr[-300:]


def test_http_mode_is_off_by_default_and_opt_in():
    assert _setting(None)[0] == "False"
    assert _setting("true")[0] == "True"
