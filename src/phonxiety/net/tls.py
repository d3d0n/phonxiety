"""Self-signed TLS identity of the daemon's phone listener.

The phone does not validate a certificate chain: pairing gives it the SHA-256
of this certificate and it accepts exactly that peer.
"""

from __future__ import annotations

import datetime
import fcntl
import hashlib
import os
import ssl
import tempfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CERT_NAME = "device-cert.pem"
KEY_NAME = "device-key.pem"


def _write_private(path: Path, data: bytes) -> None:
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def _generate(cert_path: Path, key_path: Path) -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "phonxiety daemon")])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .sign(key, hashes.SHA256())
    )
    _write_private(
        key_path,
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    _write_private(cert_path, certificate.public_bytes(serialization.Encoding.PEM))


def device_identity(state_dir: Path) -> tuple[Path, Path, str]:
    """Return certificate path, key path and certificate SHA-256, creating the identity once."""
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    cert_path, key_path = state_dir / CERT_NAME, state_dir / KEY_NAME
    # The daemon and `phonxiety pair` may both start on a fresh state directory.
    with open(state_dir / "identity.lock", "a+") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        if not cert_path.is_file() or not key_path.is_file():
            _generate(cert_path, key_path)
    certificate = x509.load_pem_x509_certificate(cert_path.read_bytes())
    fingerprint = hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest()
    return cert_path, key_path, fingerprint


def server_context(cert_path: Path, key_path: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_path, key_path)
    return context
