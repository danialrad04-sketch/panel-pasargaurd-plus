"""Actual node identities must fit the native API and survive failed reruns."""

import argparse
import stat

import pytest
from cryptography import x509

from app.models.node import NodeCreate
from node.prepare_node import node_address, prepare


def arguments(tmp_path):
    return argparse.Namespace(
        state_dir=tmp_path / "state",
        env_file=tmp_path / ".env",
        registration_file=tmp_path / "node-registration.json",
        name="Iran",
        address=["192.0.2.10", "2001:db8::10"],
        port=62050,
        api_port=62051,
        tunnel_port=62052,
    )


def test_real_certificate_and_native_registration(tmp_path):
    args = arguments(tmp_path)
    prepare(args)
    registration = NodeCreate.model_validate_json(args.registration_file.read_text())
    assert registration.address == args.address[0]
    assert registration.core_config_id == 1 and registration.keep_alive == 60
    cert_path = args.state_dir / "certs/ssl_cert.pem"
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    assert {str(ip) for ip in sans.get_values_for_type(x509.IPAddress)} == set(args.address)
    for path in (args.env_file, args.registration_file, args.state_dir / "certs/ssl_key.pem"):
        assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert registration.api_key in args.env_file.read_text()
    assert "PRIVATE KEY" not in registration.server_ca


def test_existing_identity_never_overwritten(tmp_path):
    args = arguments(tmp_path)
    prepare(args)
    before = {path: path.read_bytes() for path in tmp_path.rglob("*") if path.is_file()}
    with pytest.raises(FileExistsError):
        prepare(args)
    assert before == {path: path.read_bytes() for path in before}


def test_partial_existing_target_does_not_publish_secrets(tmp_path):
    args = arguments(tmp_path)
    args.registration_file.write_text("preserve")
    with pytest.raises(FileExistsError):
        prepare(args)
    assert not args.env_file.exists() and not args.state_dir.exists()


@pytest.mark.parametrize("address", ["127.0.0.1", "0.0.0.0", "::1", "224.0.0.1", "169.254.1.1"])
def test_reject_unreachable_management_ip(address):
    with pytest.raises(argparse.ArgumentTypeError):
        node_address(address)


def test_ports_must_be_distinct(tmp_path):
    args = arguments(tmp_path)
    args.tunnel_port = args.port
    with pytest.raises(ValueError):
        prepare(args)
    assert not args.env_file.exists()
