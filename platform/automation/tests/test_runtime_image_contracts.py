from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
ASSEMBLER_PATH = ROOT / "platform" / "release" / "assemble-release-images.py"
VALIDATOR_PATH = ROOT / "platform" / "release" / "validate-runtime-images.py"
INVENTORY_PATH = ROOT / "platform" / "release" / "runtime-inventory.json"
THIRD_PARTY_PATH = ROOT / "platform" / "release" / "third-party-images.json"


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def assembled_document(inventory: dict) -> dict:
    images = []
    for index, item in enumerate(inventory["images"]):
        digest = "sha256:" + f"{index + 1:064x}"
        package = f"ghcr.io/trinyxai/fixture-{item['name']}"
        images.append(
            {
                "name": item["name"],
                "role": item["role"],
                "service": item["service"],
                "package": package,
                "environment": item["environment"],
                "digest": digest,
                "immutableRef": package + "@" + digest,
            }
        )
    return {
        "schemaVersion": 1,
        "sourceCommit": "a" * 40,
        "images": images,
    }


class RuntimeImageContractTests(unittest.TestCase):
    def test_current_inventory_and_static_third_party_are_closed_valid_inputs(self) -> None:
        assembler = load_module(ASSEMBLER_PATH, "trinyx_assembler")
        inventory = json.loads(INVENTORY_PATH.read_text())
        self.assertEqual(28, len(assembler.load_inventory(INVENTORY_PATH)))
        images = assembler.load_input_manifest(THIRD_PARTY_PATH, "a" * 40)
        self.assertEqual(12, len(images))

    def test_assembler_rejects_boolean_schema_coercion_and_extra_fields(self) -> None:
        assembler = load_module(ASSEMBLER_PATH, "trinyx_assembler_strict")
        inventory = json.loads(INVENTORY_PATH.read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            invalid_inventory = copy.deepcopy(inventory)
            invalid_inventory["schemaVersion"] = True
            path = root / "inventory.json"
            path.write_text(json.dumps(invalid_inventory), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "invalid runtime inventory"):
                assembler.load_inventory(path)

            invalid_manifest = {
                "schemaVersion": 1,
                "commit": "a" * 40,
                "images": [],
                "unexpected": "x",
            }
            path.write_text(json.dumps(invalid_manifest), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "invalid image manifest schema"):
                assembler.load_input_manifest(path, "a" * 40)

    def test_runtime_validator_rejects_type_coercion_duplicate_keys_and_bad_schema(self) -> None:
        validator = load_module(VALIDATOR_PATH, "trinyx_validator")
        assembler = load_module(ASSEMBLER_PATH, "trinyx_assembler_tagged")
        inventory = json.loads(INVENTORY_PATH.read_text())
        self.assertEqual(28, len(validator.validate_inventory(inventory)))
        document = assembled_document(inventory)
        self.assertEqual(28, len(validator.validate_images(document)))

        # The runtime lock also validates the images embedded in the closed
        # canonical release manifest; accepting a free-form wrapper would
        # weaken this boundary, so only that exact root shape is allowed.
        release_document = {
            "schemaVersion": 1,
            "releaseId": "rel-v1-" + "b" * 32,
            "sourceCommit": document["sourceCommit"],
            "sourceRef": "refs/heads/fixture",
            "platformCommit": "c" * 40,
            "createdAt": "2026-09-01T00:00:00Z",
            "deploymentBundle": {},
            "images": copy.deepcopy(document["images"]),
        }
        self.assertEqual(28, len(validator.validate_images(release_document)))
        malformed_release = copy.deepcopy(release_document)
        malformed_release["unexpected"] = True
        with self.assertRaisesRegex(SystemExit, "schema mismatch"):
            validator.validate_images(malformed_release)

        # The bootstrap fixture shape is an explicit legacy image document,
        # not a generic compatibility escape hatch. Its identity fields stay
        # type-checked and an added root key remains rejected.
        bootstrap_document = {
            "schemaVersion": 1,
            "sourceCommit": document["sourceCommit"],
            "sourceRef": "codex/trinyx-cloud-gateway-v2",
            "legacyReleaseId": "stg-bootstrap-001",
            "images": copy.deepcopy(document["images"]),
        }
        self.assertEqual(28, len(validator.validate_images(bootstrap_document)))
        bad_bootstrap = copy.deepcopy(bootstrap_document)
        bad_bootstrap["legacyReleaseId"] = ""
        with self.assertRaisesRegex(SystemExit, "identity mismatch"):
            validator.validate_images(bad_bootstrap)
        bad_bootstrap = copy.deepcopy(bootstrap_document)
        bad_bootstrap["unexpected"] = True
        with self.assertRaisesRegex(SystemExit, "schema mismatch"):
            validator.validate_images(bad_bootstrap)

        invalid = copy.deepcopy(document)
        invalid["schemaVersion"] = True
        with self.assertRaisesRegex(SystemExit, "schema mismatch"):
            validator.validate_images(invalid)

        invalid = copy.deepcopy(document)
        invalid["images"][0]["package"] = 1
        with self.assertRaisesRegex(SystemExit, "invalid runtime image entry"):
            validator.validate_images(invalid)

        tagged = copy.deepcopy(document)
        tagged["images"][0]["package"] = "ghcr.io/trinyxai/fixture:mutable"
        tagged["images"][0]["immutableRef"] = (
            tagged["images"][0]["package"] + "@" + tagged["images"][0]["digest"]
        )
        with self.assertRaisesRegex(SystemExit, "canonical and tagless"):
            validator.validate_images(tagged)
        with self.assertRaisesRegex(SystemExit, "canonical and tagless"):
            assembler.require_image_identity(tagged["images"][0], "fixture")


if __name__ == "__main__":
    unittest.main()
