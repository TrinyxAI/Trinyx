from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
INSTALLER = ROOT / "platform" / "install" / "install-release.py"


def load_installer():
    spec = importlib.util.spec_from_file_location("trinyx_install_release_modes", INSTALLER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def tar_bytes(entries: list[tuple[str, bytes, int]]) -> bytes:
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.GNU_FORMAT) as archive:
        for name, content, mode in entries:
            member = tarfile.TarInfo(name)
            member.size = len(content)
            member.mode = mode
            member.mtime = 0
            member.uid = 0
            member.gid = 0
            archive.addfile(member, io.BytesIO(content))
    return output.getvalue()


def write_case(
    root: Path,
    *,
    name: str,
    release_id: str,
    entries: list[tuple[str, bytes, int]],
    include_mode: bool,
) -> tuple[Path, Path, dict]:
    payload = tar_bytes(entries)
    bundle_path = root / f"{name}.tar"
    bundle_path.write_bytes(payload)
    files = []
    for path, content, mode in entries:
        item = {"path": path, "digest": sha256(content), "sizeBytes": len(content)}
        if include_mode:
            item["mode"] = mode
        files.append(item)
    bundle = {
        "schemaVersion": 1,
        "format": "tar",
        "digest": sha256(payload),
        "sizeBytes": len(payload),
        "files": files,
    }
    bundle_path_json = root / f"{name}.json"
    bundle_path_json.write_text(json.dumps(bundle), encoding="utf-8")
    release = {
        "releaseId": release_id,
        "deploymentBundle": {
            "format": "tar",
            "digest": bundle["digest"],
            "sizeBytes": bundle["sizeBytes"],
            "fileCount": len(files),
        },
    }
    return bundle_path_json, bundle_path, release


class InstallReleaseModeTests(unittest.TestCase):
    def test_frozen_mode_less_schema_is_exactly_bounded(self) -> None:
        installer = load_installer()
        self.assertEqual(
            "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4",
            installer.FROZEN_CANDIDATE_RELEASE_ID,
        )
        self.assertEqual(
            "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
            installer.FROZEN_CANDIDATE_BUNDLE_DIGEST,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path, tar_path, release = write_case(
                root,
                name="frozen-ok",
                release_id="rel-test-frozen",
                entries=[("x.txt", b"frozen\n", 0o644)],
                include_mode=False,
            )
            installer.FROZEN_CANDIDATE_RELEASE_ID = release["releaseId"]
            installer.FROZEN_CANDIDATE_BUNDLE_DIGEST = release["deploymentBundle"]["digest"]
            bundle, _ = installer.validate_bundle(release, manifest_path, tar_path)
            self.assertEqual({"path", "digest", "sizeBytes"}, set(bundle["files"][0]))

            bad_manifest, bad_tar, bad_release = write_case(
                root,
                name="frozen-executable",
                release_id=release["releaseId"],
                entries=[("x.txt", b"frozen\n", 0o755)],
                include_mode=False,
            )
            installer.FROZEN_CANDIDATE_BUNDLE_DIGEST = bad_release["deploymentBundle"]["digest"]
            with self.assertRaisesRegex(SystemExit, "tar mode mismatch"):
                installer.validate_bundle(bad_release, bad_manifest, bad_tar)

            mode_manifest, mode_tar, mode_release = write_case(
                root,
                name="frozen-with-mode",
                release_id=release["releaseId"],
                entries=[("x.txt", b"frozen\n", 0o644)],
                include_mode=True,
            )
            installer.FROZEN_CANDIDATE_BUNDLE_DIGEST = mode_release["deploymentBundle"]["digest"]
            with self.assertRaisesRegex(SystemExit, "invalid deployment bundle file entry"):
                installer.validate_bundle(mode_release, mode_manifest, mode_tar)

            non_frozen_manifest, non_frozen_tar, non_frozen_release = write_case(
                root,
                name="non-frozen-mode-less",
                release_id="rel-test-modern",
                entries=[("x.txt", b"frozen\n", 0o644)],
                include_mode=False,
            )
            with self.assertRaisesRegex(SystemExit, "invalid deployment bundle file entry"):
                installer.validate_bundle(
                    non_frozen_release, non_frozen_manifest, non_frozen_tar
                )

    def test_modern_modes_are_authenticated_and_extract_read_only(self) -> None:
        installer = load_installer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path, tar_path, release = write_case(
                root,
                name="modern",
                release_id="rel-test-modern",
                entries=[
                    ("plain.txt", b"plain\n", 0o644),
                    ("bin/run.sh", b"#!/bin/sh\n", 0o755),
                ],
                include_mode=True,
            )
            bundle, _ = installer.validate_bundle(release, manifest_path, tar_path)
            installer.install_bundle_tree(root / "extract", tar_path, bundle)
            bundle_dir = root / "extract" / "bundle"
            self.assertEqual(0o444, (bundle_dir / "plain.txt").stat().st_mode & 0o777)
            self.assertEqual(0o555, (bundle_dir / "bin" / "run.sh").stat().st_mode & 0o777)
            self.assertTrue(installer.verify_bundle_tree(bundle_dir, bundle))
            (bundle_dir / "plain.txt").chmod(0o555)
            self.assertFalse(installer.verify_bundle_tree(bundle_dir, bundle))


if __name__ == "__main__":
    unittest.main()
