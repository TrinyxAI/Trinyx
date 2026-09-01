#!/usr/bin/env python3
"""Offline staging-only PKI: encrypted CA keys, local leaf key, exact hostname, no secret logging."""

from __future__ import annotations

import argparse
import os
import re
import stat
import subprocess
import tempfile
from pathlib import Path


HOSTNAME = "billing-internal.trinyx.private"
ROOT_DAYS = 3650
ISSUER_DAYS = 1825
LEAF_DAYS = 90
CRL_DAYS = 30


class PkiError(RuntimeError):
    pass


def require(ok: bool, message: str) -> None:
    if not ok:
        raise PkiError(message)


def regular(path: Path) -> None:
    require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0, "required PKI file missing/unsafe")


def passphrase(path: Path) -> None:
    regular(path)
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, "passphrase file must not be group/world accessible")


def run(argv: list[str], capture: bool = False) -> str:
    try:
        result = subprocess.run(
            argv, check=True, text=True,
            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=45,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise PkiError("OpenSSL PKI operation failed") from exc
    return result.stdout if capture else ""


def write_private(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            os.chmod(temporary, 0o600)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def ca_config(directory: Path, kind: str) -> str:
    is_root = kind == "root"
    cert = directory / "certs" / ("root-ca.cert.pem" if is_root else "issuer-ca.cert.pem")
    key = directory / "private" / ("root-ca.key.pem" if is_root else "issuer-ca.key.pem")
    policy = "policy_strict" if is_root else "policy_loose"
    return f"""[ ca ]
default_ca = CA_default

[ CA_default ]
dir = {directory}
certs = $dir/certs
crl_dir = $dir/crl
new_certs_dir = $dir/newcerts
database = $dir/index.txt
serial = $dir/serial
crlnumber = $dir/crlnumber
certificate = {cert}
private_key = {key}
default_md = sha384
default_days = {LEAF_DAYS}
default_crl_days = {CRL_DAYS}
policy = {policy}
unique_subject = no
copy_extensions = none

[ policy_strict ]
organizationName = match
organizationalUnitName = optional
commonName = supplied

[ policy_loose ]
organizationName = supplied
organizationalUnitName = optional
commonName = supplied

[ req ]
prompt = no
distinguished_name = dn
string_mask = utf8only

[ dn ]
O = Trinyx
OU = Staging PKI
CN = Trinyx Staging {'Root CA' if is_root else 'Internal TLS Issuer'}

[ v3_root_ca ]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical, CA:true, pathlen:1
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[ v3_intermediate_ca ]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[ server_cert ]
basicConstraints = critical, CA:false
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:{HOSTNAME}
"""


def init_database(directory: Path) -> None:
    for child in ("certs", "crl", "newcerts", "private"):
        (directory / child).mkdir(parents=True, exist_ok=False, mode=0o700)
    write_private(directory / "index.txt", "")
    write_private(directory / "serial", "1000\n")
    write_private(directory / "crlnumber", "1000\n")


def initialize(workspace: Path, passphrase_file: Path) -> None:
    passphrase(passphrase_file)
    require(not workspace.exists() or not any(workspace.iterdir()), "PKI workspace must be empty")
    workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(workspace, 0o700)
    root = workspace / "root"
    issuer = workspace / "issuer"
    init_database(root)
    init_database(issuer)
    root_config = workspace / "root-openssl.cnf"
    issuer_config = workspace / "issuer-openssl.cnf"
    write_private(root_config, ca_config(root, "root"))
    write_private(issuer_config, ca_config(issuer, "issuer"))

    root_key = root / "private" / "root-ca.key.pem"
    root_cert = root / "certs" / "root-ca.cert.pem"
    issuer_key = issuer / "private" / "issuer-ca.key.pem"
    issuer_csr = issuer / "issuer-ca.csr.pem"
    issuer_cert = issuer / "certs" / "issuer-ca.cert.pem"
    pass_arg = f"file:{passphrase_file}"

    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:4096",
         "-aes-256-cbc", "-pass", pass_arg, "-out", str(root_key)])
    os.chmod(root_key, 0o600)
    run(["openssl", "req", "-config", str(root_config), "-key", str(root_key), "-passin", pass_arg,
         "-new", "-x509", "-days", str(ROOT_DAYS), "-sha512", "-extensions", "v3_root_ca",
         "-out", str(root_cert)])
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
         "-aes-256-cbc", "-pass", pass_arg, "-out", str(issuer_key)])
    os.chmod(issuer_key, 0o600)
    run(["openssl", "req", "-config", str(issuer_config), "-key", str(issuer_key), "-passin", pass_arg,
         "-new", "-sha384", "-out", str(issuer_csr)])
    run(["openssl", "ca", "-batch", "-config", str(root_config), "-extensions", "v3_intermediate_ca",
         "-days", str(ISSUER_DAYS), "-md", "sha512", "-passin", pass_arg,
         "-in", str(issuer_csr), "-out", str(issuer_cert)])
    run(["openssl", "verify", "-CAfile", str(root_cert), str(issuer_cert)])
    run(["openssl", "ca", "-config", str(root_config), "-gencrl", "-passin", pass_arg,
         "-out", str(root / "crl" / "root-ca.crl.pem")])
    run(["openssl", "ca", "-config", str(issuer_config), "-gencrl", "-passin", pass_arg,
         "-out", str(issuer / "crl" / "issuer-ca.crl.pem")])


def leaf_csr(private_key: Path, csr: Path) -> None:
    require(not private_key.exists() and not csr.exists(), "leaf key/CSR destination already exists")
    private_key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    csr.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    old_umask = os.umask(0o077)
    try:
        run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
             "-out", str(private_key)])
        os.chmod(private_key, 0o600)
        run(["openssl", "req", "-new", "-sha384", "-key", str(private_key),
             "-subj", f"/O=Trinyx/OU=Staging Runtime/CN={HOSTNAME}",
             "-addext", f"subjectAltName=DNS:{HOSTNAME}", "-out", str(csr)])
        os.chmod(csr, 0o644)
    finally:
        os.umask(old_umask)


def issue(workspace: Path, passphrase_file: Path, csr: Path, certificate: Path) -> None:
    passphrase(passphrase_file)
    regular(csr)
    require(not certificate.exists(), "certificate destination already exists")
    subject = run(["openssl", "req", "-in", str(csr), "-noout", "-subject", "-nameopt", "RFC2253"], capture=True)
    run(["openssl", "req", "-in", str(csr), "-noout", "-verify"])
    require(re.search(rf"(?:^|,)CN={re.escape(HOSTNAME)}(?:,|$)", subject.replace("subject=", "").strip()) is not None,
            "CSR hostname is not the staging billing hostname")
    issuer_config = workspace / "issuer-openssl.cnf"
    issuer_cert = workspace / "issuer" / "certs" / "issuer-ca.cert.pem"
    regular(issuer_config)
    regular(issuer_cert)
    run(["openssl", "ca", "-batch", "-config", str(issuer_config), "-extensions", "server_cert",
         "-days", str(LEAF_DAYS), "-md", "sha384", "-passin", f"file:{passphrase_file}",
         "-in", str(csr), "-out", str(certificate)])
    os.chmod(certificate, 0o644)
    verify(workspace, certificate)


def verify(workspace: Path, certificate: Path) -> None:
    root_cert = workspace / "root" / "certs" / "root-ca.cert.pem"
    issuer_cert = workspace / "issuer" / "certs" / "issuer-ca.cert.pem"
    regular(root_cert)
    regular(issuer_cert)
    regular(certificate)
    run(["openssl", "verify", "-CAfile", str(root_cert), "-untrusted", str(issuer_cert), str(certificate)])
    run(["openssl", "x509", "-in", str(certificate), "-checkhost", HOSTNAME, "-noout"])


def revoke(workspace: Path, passphrase_file: Path, certificate: Path) -> None:
    passphrase(passphrase_file)
    regular(certificate)
    issuer_config = workspace / "issuer-openssl.cnf"
    crl = workspace / "issuer" / "crl" / "issuer-ca.crl.pem"
    run(["openssl", "ca", "-config", str(issuer_config), "-passin", f"file:{passphrase_file}",
         "-revoke", str(certificate), "-crl_reason", "keyCompromise"])
    run(["openssl", "ca", "-config", str(issuer_config), "-passin", f"file:{passphrase_file}",
         "-gencrl", "-out", str(crl)])


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init")
    init.add_argument("--workspace", required=True, type=Path)
    init.add_argument("--passphrase-file", required=True, type=Path)
    csr = sub.add_parser("leaf-csr")
    csr.add_argument("--private-key", required=True, type=Path)
    csr.add_argument("--csr", required=True, type=Path)
    issue_parser = sub.add_parser("issue")
    issue_parser.add_argument("--workspace", required=True, type=Path)
    issue_parser.add_argument("--passphrase-file", required=True, type=Path)
    issue_parser.add_argument("--csr", required=True, type=Path)
    issue_parser.add_argument("--certificate", required=True, type=Path)
    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--workspace", required=True, type=Path)
    verify_parser.add_argument("--certificate", required=True, type=Path)
    revoke_parser = sub.add_parser("revoke")
    revoke_parser.add_argument("--workspace", required=True, type=Path)
    revoke_parser.add_argument("--passphrase-file", required=True, type=Path)
    revoke_parser.add_argument("--certificate", required=True, type=Path)
    args = parser.parse_args()

    if args.command == "init":
        initialize(args.workspace, args.passphrase_file)
    elif args.command == "leaf-csr":
        leaf_csr(args.private_key, args.csr)
    elif args.command == "issue":
        issue(args.workspace, args.passphrase_file, args.csr, args.certificate)
    elif args.command == "verify":
        verify(args.workspace, args.certificate)
    else:
        revoke(args.workspace, args.passphrase_file, args.certificate)
    print(f"OFFLINE_STAGING_PKI_OK command={args.command}")


if __name__ == "__main__":
    try:
        main()
    except PkiError as exc:
        print(f"OFFLINE_STAGING_PKI_FAILED={type(exc).__name__}", file=__import__("sys").stderr)
        raise SystemExit(1)
