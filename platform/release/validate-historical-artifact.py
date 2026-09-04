#!/usr/bin/env python3
"""Fail-closed validation of the downloaded historical artifact archive."""

from __future__ import annotations

import argparse
import os
import stat
import zipfile
from pathlib import Path, PurePosixPath
from typing import Iterable

CONTROL_CHARACTER = lambda value: any(ord(char) < 0x20 or ord(char) == 0x7F for char in value)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def normalize_member_name(raw: str) -> tuple[str, bool]:
    if not isinstance(raw, str) or not raw:
        fail("historical artifact contains an empty member name")
    is_directory = raw.endswith("/")
    name = raw[:-1] if is_directory else raw
    if (
        not name
        or name.startswith("/")
        or "\\" in raw
        or CONTROL_CHARACTER(raw)
    ):
        fail(f"unsafe historical artifact member: {raw!r}")
    path = PurePosixPath(name)
    if "." in path.parts or ".." in path.parts or path.as_posix() != name:
        fail(f"non-canonical historical artifact member: {raw!r}")
    return name, is_directory


def validate_zip(path: Path) -> None:
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        fail(f"cannot read historical artifact ZIP: {exc}")
    seen: set[str] = set()
    files: set[str] = set()
    try:
        for info in archive.infolist():
            name, is_directory = normalize_member_name(info.filename)
            if name in seen:
                fail(f"duplicate historical artifact member: {name}")
            seen.add(name)
            if any(parent.as_posix() in files for parent in PurePosixPath(name).parents):
                fail(f"historical artifact file shadows a directory: {name}")
            if info.flag_bits & 0x1:
                fail(f"encrypted historical artifact member: {name}")
            mode = (info.external_attr >> 16) & 0o170000
            if mode:
                expected = stat.S_IFDIR if is_directory else stat.S_IFREG
                if mode != expected:
                    fail(f"historical artifact member is not a regular file/directory: {name}")
            if not is_directory:
                files.add(name)
    finally:
        archive.close()


def validate_extracted_tree(root: Path) -> None:
    if root.is_symlink() or not root.is_dir():
        fail("historical artifact extraction root is missing or unsafe")
    try:
        entries: Iterable[Path] = root.rglob("*")
        for path in entries:
            if path.is_symlink():
                fail(f"historical artifact extraction contains a symlink: {path}")
            mode = stat.S_IFMT(path.stat().st_mode)
            if mode not in {stat.S_IFREG, stat.S_IFDIR}:
                fail(f"historical artifact extraction contains a special file: {path}")
    except OSError as exc:
        fail(f"cannot validate historical artifact extraction: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--zip", type=Path)
    group.add_argument("--extracted", type=Path)
    args = parser.parse_args()
    if args.zip is not None:
        validate_zip(args.zip)
        print("HISTORICAL_ARTIFACT_ZIP_OK")
    else:
        validate_extracted_tree(args.extracted)
        print("HISTORICAL_ARTIFACT_TREE_OK")


if __name__ == "__main__":
    main()
