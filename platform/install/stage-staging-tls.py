#!/usr/bin/env python3
"""Validate and atomically stage approved staging-only TLS material without logging it."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path


class TlsMaterialError(RuntimeError):
    pass


def require(ok: bool, message: str) -> None:
    if not ok:
        raise TlsMaterialError(message)


def rooted(root: Path, absolute: str) -> Path:
    require(absolute.startswith("/") and ".." not in Path(absolute).parts, "unsafe TLS destination")
    return root / absolute.lstrip("/")


def regular_nonempty(path: Path) -> None:
    require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0, "TLS source must be a non-empty regular file")


def run_public(argv: list[str], capture: bool = False) -> str:
    try:
        result = subprocess.run(
            argv, check=True, text=True,
            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=20,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise TlsMaterialError("TLS cryptographic validation failed") from exc
    return result.stdout if capture else ""


def verify_paid(ca: Path, certificate: Path, private_key: Path) -> None:
    run_public(["openssl", "verify", "-CAfile", str(ca), str(certificate)])
    run_public(["openssl", "x509", "-in", str(certificate), "-checkhost", "billing-internal.trinyx.private", "-noout"])
    cert_public = run_public(["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"], capture=True)
    key_public = run_public(["openssl", "pkey", "-in", str(private_key), "-pubout"], capture=True)
    require(
        hashlib.sha256(cert_public.encode()).digest() == hashlib.sha256(key_public.encode()).digest(),
        "certificate/private-key mismatch",
    )


def atomic_install(source: Path, destination: Path, mode: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=str(destination.parent))
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
        directory_fd = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if temporary.exists():
            temporary.unlink()


def stage(
    role: str,
    ca: Path,
    certificate: Path | None,
    private_key: Path | None,
    root: Path,
    verify_crypto: bool = True,
) -> None:
    require(role in {"cloud", "paid"}, "invalid staging role")
    regular_nonempty(ca)
    if role == "cloud":
        require(certificate is None and private_key is None, "Cloud accepts only the public Paid CA")
        items = [(ca, "/etc/trinyx/staging/cloud/config/tls/paid-ca.pem", 0o644)]
    else:
        require(certificate is not None and private_key is not None, "Paid certificate and private key are required")
        regular_nonempty(certificate)
        regular_nonempty(private_key)
        if verify_crypto:
            verify_paid(ca, certificate, private_key)
        items = [
            (ca, "/etc/trinyx/staging/paid/config/tls/staging-ca.pem", 0o644),
            (certificate, "/etc/trinyx/staging/paid/config/tls/paid-server.crt", 0o644),
            (private_key, "/etc/trinyx/staging/paid/config/tls/paid-server.key", 0o600),
        ]
    for source, target, mode in items:
        destination = rooted(root, target)
        if destination.exists():
            require(destination.is_file() and not destination.is_symlink(), "refusing non-regular TLS destination")
        atomic_install(source, destination, mode)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=("cloud", "paid"), required=True)
    parser.add_argument("--ca", type=Path, required=True)
    parser.add_argument("--certificate", type=Path)
    parser.add_argument("--private-key", type=Path)
    parser.add_argument("--root", type=Path, default=Path("/"))
    args = parser.parse_args()
    require(os.geteuid() == 0 or args.root != Path("/"), "live TLS staging requires root")
    stage(args.role, args.ca, args.certificate, args.private_key, args.root)
    print(f"STAGING_TLS_MATERIAL_OK role={args.role}")


if __name__ == "__main__":
    try:
        main()
    except TlsMaterialError as exc:
        print(f"STAGING_TLS_MATERIAL_FAILED={type(exc).__name__}", file=__import__("sys").stderr)
        raise SystemExit(1)
