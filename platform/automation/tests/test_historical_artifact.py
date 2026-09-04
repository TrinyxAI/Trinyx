from __future__ import annotations

import importlib.util
import os
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


def make_zip(path: Path, entries: list[tuple[str, bytes, int | None]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content, mode in entries:
            info = zipfile.ZipInfo(name)
            if mode is not None:
                info.external_attr = mode << 16
            archive.writestr(info, content)


class HistoricalArtifactValidationTests(unittest.TestCase):
    def test_exact_single_file_archive_extracts_without_unzip(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "cloud.zip"
            make_zip(
                archive,
                [("cloud-image-manifest.json", b"{\"schemaVersion\":1}\n", stat.S_IFREG | 0o644)],
            )
            extracted = root / "extracted"
            validator.extract_exact_zip(archive, extracted, {"cloud-image-manifest.json"})
            self.assertEqual(
                b"{\"schemaVersion\":1}\n",
                (extracted / "cloud-image-manifest.json").read_bytes(),
            )
            validator.validate_extracted_tree(extracted, {"cloud-image-manifest.json"})

    def test_rejects_traversal_absolute_backslash_and_controls(self) -> None:
        validator = load_validator()
        for name in ("../escape", "/absolute", "dir\\escape", "bad\x00name", "./manifest"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(SystemExit, "unsafe|non-canonical"):
                    validator.normalize_member_name(name)

    def test_rejects_duplicate_shadow_and_unexpected_members(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.zip"
            make_zip(duplicate, [("same", b"a", None), ("same", b"b", None)])
            with zipfile.ZipFile(duplicate) as archive:
                with self.assertRaisesRegex(SystemExit, "duplicate"):
                    validator.scan_archive(archive, {"same"})

            shadow = root / "shadow.zip"
            make_zip(shadow, [("file", b"a", None), ("file/child", b"b", None)])
            with zipfile.ZipFile(shadow) as archive:
                with self.assertRaisesRegex(SystemExit, "shadows"):
                    validator.scan_archive(archive, {"file", "file/child"})

            extra = root / "extra.zip"
            make_zip(extra, [("cloud-image-manifest.json", b"{}", None), ("extra", b"x", None)])
            with zipfile.ZipFile(extra) as archive:
                with self.assertRaisesRegex(SystemExit, "file set"):
                    validator.scan_archive(archive, {"cloud-image-manifest.json"})

    def test_rejects_encrypted_symlink_and_special_members(self) -> None:
        validator = load_validator()

        class FakeInfo:
            def __init__(self, name: str, *, flags: int = 0, mode: int = 0):
                self.filename = name
                self.flag_bits = flags
                self.external_attr = mode << 16

        class FakeArchive:
            def __init__(self, infos):
                self._infos = infos
            def infolist(self):
                return self._infos

        with self.assertRaisesRegex(SystemExit, "encrypted"):
            validator.scan_archive(FakeArchive([FakeInfo("secret", flags=0x1)]), {"secret"})
        with self.assertRaisesRegex(SystemExit, "regular"):
            validator.scan_archive(
                FakeArchive([FakeInfo("link", mode=stat.S_IFLNK | 0o777)]),
                {"link"},
            )
        with self.assertRaisesRegex(SystemExit, "regular"):
            validator.scan_archive(
                FakeArchive([FakeInfo("socket", mode=stat.S_IFSOCK | 0o600)]),
                {"socket"},
            )

    @unittest.skipIf(os.name == "nt", "Windows runner lacks unprivileged symlink support; Linux CI executes this")
    def test_extraction_rejects_existing_or_symlinked_destination(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "cloud.zip"
            make_zip(archive, [("cloud-image-manifest.json", b"{}", None)])
            existing = root / "existing"
            existing.mkdir()
            with self.assertRaisesRegex(SystemExit, "destination"):
                validator.extract_exact_zip(archive, existing, {"cloud-image-manifest.json"})
            target = root / "target"
            target.mkdir()
            link = root / "link"
            os.symlink(target, link)
            with self.assertRaisesRegex(SystemExit, "symlink"):
                validator.extract_exact_zip(archive, link / "out", {"cloud-image-manifest.json"})

    @unittest.skipIf(os.name == "nt", "Windows runner lacks unprivileged symlink support; Linux CI executes this")
    def test_extracted_tree_rejects_symlink_and_extra_empty_directory(self) -> None:
        validator = load_validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extracted = root / "extracted"
            extracted.mkdir()
            (extracted / "cloud-image-manifest.json").write_text("{}", encoding="utf-8")
            (extracted / "empty").mkdir()
            with self.assertRaisesRegex(SystemExit, "tree"):
                validator.validate_extracted_tree(extracted, {"cloud-image-manifest.json"})
            (extracted / "empty").rmdir()
            os.symlink(extracted / "cloud-image-manifest.json", extracted / "link")
            with self.assertRaisesRegex(SystemExit, "symlink"):
                validator.validate_extracted_tree(extracted, {"cloud-image-manifest.json"})


if __name__ == "__main__":
    unittest.main()
