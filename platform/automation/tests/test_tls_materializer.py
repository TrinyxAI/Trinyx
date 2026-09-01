from __future__ import annotations

import importlib.util
import stat
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[2] / "install" / "stage-staging-tls.py"
SPEC = importlib.util.spec_from_file_location("stage_staging_tls", MODULE_PATH)
assert SPEC and SPEC.loader
tls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tls)


class TlsMaterializerTests(unittest.TestCase):
    def test_cloud_stages_only_public_ca(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source-ca.pem"
            source.write_text("PUBLIC CA FIXTURE\n", encoding="utf-8")
            tls.stage("cloud", source, None, None, root, verify_crypto=False)
            target = root / "etc/trinyx/staging/cloud/config/tls/paid-ca.pem"
            self.assertEqual(source.read_bytes(), target.read_bytes())
            self.assertEqual(0o644, stat.S_IMODE(target.stat().st_mode))

    def test_paid_private_key_is_mode_0600_and_atomic(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            ca, certificate, key = (root / name for name in ("ca.pem", "server.crt", "server.key"))
            ca.write_text("CA\n", encoding="utf-8")
            certificate.write_text("CERT\n", encoding="utf-8")
            key.write_text("PRIVATE\n", encoding="utf-8")
            tls.stage("paid", ca, certificate, key, root, verify_crypto=False)
            target = root / "etc/trinyx/staging/paid/config/tls/paid-server.key"
            self.assertEqual(b"PRIVATE\n", target.read_bytes())
            self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
            self.assertFalse(any(target.parent.glob(".paid-server.key.*")))

    def test_symlink_source_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            real = root / "real.pem"
            link = root / "link.pem"
            real.write_text("CA\n", encoding="utf-8")
            try:
                link.symlink_to(real)
            except OSError:
                self.skipTest("symlinks unavailable")
            with self.assertRaises(tls.TlsMaterialError):
                tls.stage("cloud", link, None, None, root, verify_crypto=False)


if __name__ == "__main__":
    unittest.main()
