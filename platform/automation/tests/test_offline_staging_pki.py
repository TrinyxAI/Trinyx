from __future__ import annotations

import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
TOOL = ROOT / "platform" / "pki" / "offline_staging_pki.py"


class OfflineStagingPkiTests(unittest.TestCase):
    @unittest.skipIf(os.name == "nt", "OpenSSL integration contract runs on Ubuntu CI")
    def test_offline_hierarchy_leaf_hostname_rotation_and_no_secret_logging(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            workspace = base / "offline"
            paid = base / "paid"
            paid.mkdir()
            passphrase = base / "passphrase"
            secret = "fixture-passphrase-never-log"
            passphrase.write_text(secret + "\n", encoding="utf-8")
            os.chmod(passphrase, 0o600)
            private_key = paid / "billing-internal.key"
            csr = paid / "billing-internal.csr"
            certificate = base / "billing-internal.crt"

            outputs = []
            for argv in (
                ["init", "--workspace", str(workspace), "--passphrase-file", str(passphrase)],
                ["leaf-csr", "--private-key", str(private_key), "--csr", str(csr)],
                ["issue", "--workspace", str(workspace), "--passphrase-file", str(passphrase),
                 "--csr", str(csr), "--certificate", str(certificate)],
                ["verify", "--workspace", str(workspace), "--certificate", str(certificate)],
                ["revoke", "--workspace", str(workspace), "--passphrase-file", str(passphrase),
                 "--certificate", str(certificate)],
            ):
                result = subprocess.run(
                    [sys.executable, str(TOOL), *argv], check=True, text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120,
                )
                outputs.append(result.stdout + result.stderr)

            self.assertTrue(certificate.is_file())
            self.assertEqual(0o600, stat.S_IMODE(private_key.stat().st_mode))
            self.assertIn(
                "BEGIN ENCRYPTED PRIVATE KEY",
                (workspace / "root" / "private" / "root-ca.key.pem").read_text(encoding="utf-8"),
            )
            self.assertIn(
                "BEGIN ENCRYPTED PRIVATE KEY",
                (workspace / "issuer" / "private" / "issuer-ca.key.pem").read_text(encoding="utf-8"),
            )
            self.assertTrue((workspace / "issuer" / "crl" / "issuer-ca.crl.pem").is_file())
            self.assertNotIn(secret, "".join(outputs))


if __name__ == "__main__":
    unittest.main()
