from __future__ import annotations

import importlib.util
import io
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
VALIDATOR = ROOT / "platform" / "release" / "validate-historical-artifact.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("trinyx_historical_artifact_validator", VALIDATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_zip(path: Path, entries: list[tuple[str, bytes, int | None]]):
    with zipfile.ZipFile(path, "w") as archive:
        for name, content, mode in entries:
            info = zipfile.ZipInfo(name)
            if mode is not None:
                info.external_attr = mode << 16
            archive.writestr(info, content)


class HistoricalArtifactValidationTests(unittest.TestCase):
    def test_valid_archive_and_extracted_tree(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "cloud.zip"
            make_zip(
                archive,
                [
                    ("root/", b"", stat.S_IFDIR | 0o755),
                    ("root/cloud-image-manifest.json", b"{}", stat.S_IFREG | 0o644),
                ],
            )
            validator.validate_zip(archive)
            extracted = root / "extracted"
            extracted.mkdir()
            (extracted / "cloud-image-manifest.json").write_text("{}", encoding="utf-8")
            validator.validate_extracted_tree(extracted)

    def test_rejects_traversal_absolute_backslash_and_controls(self) -> None:
        validator = load_validator()
        for name in ("../escape", "/absolute", "dir\\escape", "bad\x00name"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(SystemExit, "unsafe|non-canonical"):
                    validator.normalize_member_name(name)

    def test_rejects_duplicate_and_file_directory_shadowing(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.zip"
            make_zip(duplicate, [("same", b"a", None), ("same", b"b", None)])
            with self.assertRaisesRegex(SystemExit, "duplicate"):
                validator.validate_zip(duplicate)
            shadow = root / "shadow.zip"
            make_zip(shadow, [("file", b"a", None), ("file/child", b"b", None)])
            with self.assertRaisesRegex(SystemExit, "shadows"):
                validator.validate_zip(shadow)

    def test_rejects_encrypted_symlink_and_special_members(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            encrypted = root / "encrypted.zip"
            make_zip(encrypted, [("secret", b"x", None)])
            with zipfile.ZipFile(encrypted, "a") as archive:
                info = archive.getinfo("secret")
                info.flag_bits |= 0x1
            # zipfile rewrites flags on close for ordinary entries; exercise the
            # validator's explicit flag branch with a synthetic object instead.
            class EncryptedArchive:
                def infolist(self):
                    info = zipfile.ZipInfo("secret")
                    info.flag_bits = 0x1
                    return [info]
                def close(self):
                    return None
            original = validator.zipfile.ZipFile
            validator.zipfile.ZipFile = lambda _: EncryptedArchive()
            try:
                with self.assertRaisesRegex(SystemExit, "encrypted"):
                    validator.validate_zip(encrypted)
            finally:
                validator.zipfile.ZipFile = original

            symlink = root / "symlink.zip"
            make_zip(symlink, [("link", b"target", stat.S_IFLNK | 0o777)])
            with self.assertRaisesRegex(SystemExit, "regular"):
                validator.validate_zip(symlink)

            special = root / "special.zip"
            make_zip(special, [("socket", b"", stat.S_IFSOCK | 0o600)])
            with self.assertRaisesRegex(SystemExit, "regular"):
                validator.validate_zip(special)

    def test_extracted_tree_rejects_symlink_and_special(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extracted = root / "extracted"
            extracted.mkdir()
            (extracted / "file").write_text("ok", encoding="utf-8")
            if hasattr(__import__("os"), "symlink"):
                import os
                os.symlink(extracted / "file", extracted / "link")
                with self.assertRaisesRegex(SystemExit, "symlink"):
                    validator.validate_extracted_tree(extracted)


if __name__ == "__main__":
    unittest.main()
