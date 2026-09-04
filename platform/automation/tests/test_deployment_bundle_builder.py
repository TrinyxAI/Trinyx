from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BUILDER_PATH = ROOT / "platform" / "release" / "build-deployment-bundle.py"


def load_builder():
    spec = importlib.util.spec_from_file_location("trinyx_build_deployment_bundle", BUILDER_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DeploymentBundleBuilderTests(unittest.TestCase):
    def test_contract_rejects_noncanonical_paths_and_boolean_schema(self) -> None:
        builder = load_builder()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / "contract.json"
            for value in (".", "./file.txt", "file.txt/", "file//txt", "file\\\\txt"):
                contract.write_text(
                    json.dumps({"schemaVersion": 1, "paths": [value]}),
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(SystemExit, "unsafe|canonical|empty"):
                    builder.load_contract(contract)
            contract.write_text(
                json.dumps({"schemaVersion": True, "paths": ["file.txt"]}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(SystemExit, "invalid deployment bundle file contract"):
                builder.load_contract(contract)
            contract.write_text("[]", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "invalid deployment bundle file contract"):
                builder.load_contract(contract)

    def test_builder_rejects_overlapping_paths_and_special_files(self) -> None:
        builder = load_builder()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dir").mkdir()
            (root / "dir" / "file.txt").write_text("x", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "overlapping"):
                builder.require_no_path_overlap(["dir", "dir/file.txt"])
            if hasattr(os, "mkfifo"):
                fifo = root / "fifo"
                os.mkfifo(fifo)
                with self.assertRaisesRegex(SystemExit, "special"):
                    builder.collect_files(root, ["fifo"])

    def test_builder_rejects_symlinked_repository_root(self) -> None:
        builder = load_builder()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.mkdir()
            link = root / "link"
            os.symlink(target, link)
            with self.assertRaisesRegex(SystemExit, "root may not traverse a symlink"):
                builder.reject_symlinked_root(link)


if __name__ == "__main__":
    unittest.main()
