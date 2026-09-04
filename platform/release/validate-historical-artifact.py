#!/usr/bin/env python3
"""Fail-closed, exact extraction of the historical Cloud artifact ZIP."""

from __future__ import annotations

import argparse
import os
import shutil
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
    if not name or name.startswith("/") or "\\" in raw or CONTROL_CHARACTER(raw):
        fail(f"unsafe historical artifact member: {raw!r}")
    path = PurePosixPath(name)
    if "." in path.parts or ".." in path.parts or path.as_posix() != name:
        fail(f"non-canonical historical artifact member: {raw!r}")
    return name, is_directory


def normalize_expected_files(values: Iterable[str]) -> set[str]:
    expected: set[str] = set()
    for value in values:
        name, is_directory = normalize_member_name(value)
        if is_directory or name in expected:
            fail("historical artifact expected-file contract is invalid")
        expected.add(name)
    if not expected:
        fail("historical artifact expected-file contract is empty")
    return expected


def scan_archive(archive: zipfile.ZipFile, expected_files: set[str]) -> list[tuple[zipfile.ZipInfo, str, bool]]:
    seen: set[str] = set()
    files: set[str] = set()
    directories: set[str] = set()
    members: list[tuple[zipfile.ZipInfo, str, bool]] = []

    for info in archive.infolist():
        name, is_directory = normalize_member_name(info.filename)
        if name in seen:
            fail(f"duplicate historical artifact member: {name}")
        seen.add(name)
        if info.flag_bits & 0x1:
            fail(f"encrypted historical artifact member: {name}")

        file_type = (info.external_attr >> 16) & 0o170000
        if file_type:
            expected_type = stat.S_IFDIR if is_directory else stat.S_IFREG
            if file_type != expected_type:
                fail(f"historical artifact member is not a regular file/directory: {name}")

        ancestors = [parent.as_posix() for parent in PurePosixPath(name).parents if parent.as_posix() != "."]
        if any(parent in files for parent in ancestors):
            fail(f"historical artifact file shadows a directory: {name}")

        if is_directory:
            directories.add(name)
        else:
            files.add(name)
        members.append((info, name, is_directory))

    if files != expected_files:
        fail("historical artifact file set does not match the exact expected contract")
    expected_directories = {
        parent.as_posix()
        for name in expected_files
        for parent in PurePosixPath(name).parents
        if parent.as_posix() != "."
    }
    if directories - expected_directories:
        fail("historical artifact contains an unexpected directory")
    return members


def reject_symlinked_ancestors(path: Path) -> None:
    current = Path(os.path.abspath(path))
    while True:
        if current.is_symlink():
            fail("historical artifact extraction path may not traverse a symlink")
        if current.parent == current:
            return
        current = current.parent


def ensure_directory(root: Path, relative_parts: tuple[str, ...]) -> Path:
    current = root
    for part in relative_parts:
        current = current / part
        if current.exists():
            if current.is_symlink() or not current.is_dir():
                fail("historical artifact extraction directory is unsafe")
        else:
            current.mkdir(mode=0o755)
            os.chmod(current, 0o755)
    return current


def validate_extracted_tree(root: Path, expected_files: set[str]) -> None:
    if root.is_symlink() or not root.is_dir():
        fail("historical artifact extraction root is missing or unsafe")
    actual_files: set[str] = set()
    actual_directories: set[str] = set()
    try:
        for path in root.rglob("*"):
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                fail(f"historical artifact extraction contains a symlink: {relative}")
            file_type = stat.S_IFMT(path.stat().st_mode)
            if file_type == stat.S_IFREG:
                actual_files.add(relative)
            elif file_type == stat.S_IFDIR:
                actual_directories.add(relative)
            else:
                fail(f"historical artifact extraction contains a special file: {relative}")
    except OSError as exc:
        fail(f"cannot validate historical artifact extraction: {exc}")

    expected_directories = {
        parent.as_posix()
        for name in expected_files
        for parent in PurePosixPath(name).parents
        if parent.as_posix() != "."
    }
    if actual_files != expected_files or actual_directories != expected_directories:
        fail("historical artifact extracted tree does not match the exact expected contract")


def extract_exact_zip(path: Path, destination: Path, expected_files: set[str]) -> None:
    reject_symlinked_ancestors(destination.parent)
    if destination.exists() or destination.is_symlink():
        fail("historical artifact extraction destination already exists or is unsafe")
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        fail(f"cannot read historical artifact ZIP: {exc}")

    try:
        members = scan_archive(archive, expected_files)
        destination.mkdir(mode=0o755)
        os.chmod(destination, 0o755)
        for info, name, is_directory in members:
            relative = PurePosixPath(name)
            if is_directory:
                ensure_directory(destination, relative.parts)
                continue
            target = ensure_directory(destination, relative.parts[:-1]) / relative.name
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
            try:
                fd = os.open(target, flags, 0o600)
                with archive.open(info, "r") as source, os.fdopen(fd, "wb") as output:
                    shutil.copyfileobj(source, output)
            except (OSError, zipfile.BadZipFile) as exc:
                fail(f"cannot extract historical artifact member {name}: {exc}")
            os.chmod(target, 0o644)
    finally:
        archive.close()

    validate_extracted_tree(destination, expected_files)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--expected-file", action="append", required=True)
    args = parser.parse_args()
    expected_files = normalize_expected_files(args.expected_file)
    extract_exact_zip(args.zip, args.out, expected_files)
    print(f"HISTORICAL_ARTIFACT_EXTRACT_OK files={len(expected_files)}")


if __name__ == "__main__":
    main()
