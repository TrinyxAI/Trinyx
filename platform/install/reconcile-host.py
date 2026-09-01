#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RENDERER = REPO_ROOT / "platform/render/render-environment.py"


@dataclass(frozen=True)
class DesiredFile:
    source: Path
    target: str
    mode: int


@dataclass(frozen=True)
class DesiredDir:
    target: str
    mode: int


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        if "=" not in raw:
            fail(f"invalid rendered metadata line in {path}")
        key, value = raw.split("=", 1)
        if key in values:
            fail(f"duplicate rendered metadata key: {key}")
        values[key] = value
    return values


def rooted(root: Path, target: str) -> Path:
    if not target.startswith("/") or ".." in Path(target).parts:
        fail(f"unsafe target path: {target}")
    return root / target.lstrip("/")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def same_content(source: Path, target: Path) -> bool:
    if not target.exists() or not target.is_file() or target.is_symlink():
        return False
    if source.stat().st_size != target.stat().st_size:
        return False
    return sha256(source) == sha256(target)


def expected_owner(root: Path) -> tuple[int, int]:
    if root == Path("/"):
        return (0, 0)
    return (os.getuid(), os.getgid())


def file_actions(root: Path, item: DesiredFile) -> list[str]:
    path = rooted(root, item.target)
    actions: list[str] = []
    if not path.exists() and not path.is_symlink():
        actions.append("CREATE")
        return actions
    if path.is_dir():
        fail(f"managed file target is a directory: {item.target}")
    if path.is_symlink() or not path.is_file():
        actions.append("REPLACE_NONREGULAR")
        return actions
    if not same_content(item.source, path):
        actions.append("CONTENT")
    actual_mode = stat.S_IMODE(path.stat().st_mode)
    if actual_mode != item.mode:
        actions.append(f"MODE_{actual_mode:04o}_TO_{item.mode:04o}")
    uid, gid = expected_owner(root)
    st = path.stat()
    if st.st_uid != uid or st.st_gid != gid:
        actions.append("OWNER")
    return actions


def dir_actions(root: Path, item: DesiredDir) -> list[str]:
    path = rooted(root, item.target)
    if not path.exists():
        return ["CREATE_DIR"]
    if not path.is_dir() or path.is_symlink():
        fail(f"managed directory target is not a directory: {item.target}")
    actions: list[str] = []
    actual_mode = stat.S_IMODE(path.stat().st_mode)
    if actual_mode != item.mode:
        actions.append(f"MODE_{actual_mode:04o}_TO_{item.mode:04o}")
    uid, gid = expected_owner(root)
    st = path.stat()
    if st.st_uid != uid or st.st_gid != gid:
        actions.append("OWNER")
    return actions


def apply_dir(root: Path, item: DesiredDir) -> None:
    path = rooted(root, item.target)
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, item.mode)
    uid, gid = expected_owner(root)
    os.chown(path, uid, gid)


def atomic_install(root: Path, item: DesiredFile) -> None:
    target = rooted(root, item.target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.is_dir() and not target.is_symlink():
        fail(f"refusing to replace directory: {item.target}")

    fd, temp_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=str(target.parent))
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as out, item.source.open("rb") as src:
            shutil.copyfileobj(src, out)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(temp, item.mode)
        uid, gid = expected_owner(root)
        os.chown(temp, uid, gid)
        os.replace(temp, target)
        dir_fd = os.open(target.parent, os.O_DIRECTORY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    finally:
        if temp.exists():
            temp.unlink()


def validate_live_host(role: str, metadata: dict[str, str]) -> None:
    if os.geteuid() != 0:
        fail("live-root apply/plan must run as root")
    if subprocess.run(["mountpoint", "-q", "/srv/trinyx"], check=False).returncode != 0:
        fail("/srv/trinyx is not a mountpoint")
    docker_root = subprocess.check_output(
        ["docker", "info", "--format", "{{.DockerRootDir}}"], text=True
    ).strip()
    if docker_root != "/srv/trinyx/docker":
        fail("Docker data-root is not /srv/trinyx/docker")
    if role == "cloud":
        for key in (
            "PAID_MONOLITH_TRUSTSTORE_SOURCE_PATH",
            "PAID_MONOLITH_TRUSTSTORE_PASSWORD_SOURCE_PATH",
        ):
            source = Path(metadata[key])
            if not source.is_file() or source.stat().st_size == 0:
                fail(f"required Cloud trust material source is missing: {key}")


def build_desired(role: str, rendered: Path, metadata: dict[str, str]) -> tuple[list[DesiredDir], list[DesiredFile]]:
    environment = metadata.get("TRINYX_ENVIRONMENT", "")
    if environment not in {"staging", "production"}:
        fail("renderer produced invalid environment")

    common = [
        DesiredFile(
            REPO_ROOT / "platform/host/common/runtime-env.sh",
            "/usr/local/lib/trinyx/runtime-env.sh",
            0o750,
        ),
        DesiredFile(
            REPO_ROOT / "platform/host/common/staging-deploy.sh",
            "/usr/local/lib/trinyx/staging-deploy",
            0o750,
        ),
        DesiredFile(rendered / "metadata.env", "/etc/trinyx/platform/environment.env", 0o600),
        DesiredFile(
            REPO_ROOT / "platform/bootstrap/cloud/staging/rootfs/etc/docker/daemon.json",
            "/etc/docker/daemon.json",
            0o644,
        ),
    ]

    if environment == "staging":
        common.extend([
            DesiredFile(REPO_ROOT / "platform/automation/invariants.py", "/usr/local/lib/trinyx/invariants.py", 0o750),
            DesiredFile(REPO_ROOT / "platform/automation/deploy_engine.py", "/usr/local/lib/trinyx/deploy_engine.py", 0o750),
            DesiredFile(REPO_ROOT / "platform/automation/release_registry.py", "/usr/local/lib/trinyx/release_registry.py", 0o750),
            DesiredFile(REPO_ROOT / "platform/automation/health_probe.py", "/usr/local/lib/trinyx/health-probe", 0o750),
            DesiredFile(REPO_ROOT / "platform/install/stage-staging-tls.py", "/usr/local/lib/trinyx/stage-staging-tls", 0o750),
            DesiredFile(REPO_ROOT / "platform/install/install-release.py", "/usr/local/lib/trinyx/install-release.py", 0o750),
            DesiredFile(REPO_ROOT / "platform/release/release.py", "/usr/local/lib/trinyx/release.py", 0o750),
            DesiredFile(REPO_ROOT / "platform/release/runtime-inventory.json", "/usr/local/share/trinyx/runtime-inventory.json", 0o644),
        ])

    dirs = [DesiredDir("/etc/trinyx/platform", 0o700)]

    if role == "cloud":
        if not metadata.get("CLOUD_PRIVATE_IP") or not metadata.get("PAID_PRIVATE_IP"):
            fail("Cloud reconcile requires CLOUD_PRIVATE_IP and PAID_PRIVATE_IP")
        base = f"/etc/trinyx/{environment}/cloud/config"
        dirs.append(DesiredDir(base, 0o700))
        if environment == "staging":
            dirs.append(DesiredDir(f"{base}/tls", 0o700))
        files = common + [
            DesiredFile(REPO_ROOT / "platform/host/cloud/pre-docker-cloud.sh", "/usr/local/lib/trinyx/pre-docker-cloud.sh", 0o750),
            DesiredFile(REPO_ROOT / "platform/host/cloud/runtime-materialize-cloud.sh", "/usr/local/lib/trinyx/runtime-materialize-cloud.sh", 0o750),
            DesiredFile(REPO_ROOT / "platform/host/cloud/systemd/trinyx-pre-docker.service", "/etc/systemd/system/trinyx-pre-docker.service", 0o644),
            DesiredFile(REPO_ROOT / "platform/host/cloud/systemd/trinyx-cloud-runtime-materialize.service", "/etc/systemd/system/trinyx-cloud-runtime-materialize.service", 0o644),
            DesiredFile(REPO_ROOT / "platform/host/cloud/systemd/docker.service.d/10-trinyx-runtime.conf", "/etc/systemd/system/docker.service.d/10-trinyx-runtime.conf", 0o644),
            DesiredFile(REPO_ROOT / "platform/contracts/ssm/cloud-required.txt", f"{base}/ssm-required.txt", 0o600),
            DesiredFile(REPO_ROOT / "platform/host/cloud/cloud-auth-files.sh", f"{base}/cloud-auth-files.sh", 0o600),
            DesiredFile(rendered / "cloud/runtime-static.env", f"{base}/runtime-static.env", 0o600),
            DesiredFile(rendered / "cloud/cloud-paid.override.yml", f"{base}/cloud-paid.override.yml", 0o600),
            *([DesiredFile(REPO_ROOT / "platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/deployment-plan.json", f"{base}/deployment-plan.json", 0o600),
               DesiredFile(REPO_ROOT / "platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/cloud-health-endpoints.json", f"{base}/cloud-health-endpoints.json", 0o644)] if environment == "staging" else []),
        ]
        return dirs, files

    if not metadata.get("PAID_PRIVATE_IP"):
        fail("Paid reconcile requires PAID_PRIVATE_IP")
    base = f"/etc/trinyx/{environment}/paid/config"
    dirs.append(DesiredDir(base, 0o700))
    if environment == "staging":
        dirs.append(DesiredDir(f"{base}/tls", 0o700))
    files = common + [
        DesiredFile(REPO_ROOT / "platform/host/paid/runtime-materialize-paid.sh", "/usr/local/lib/trinyx/runtime-materialize-paid.sh", 0o750),
        DesiredFile(REPO_ROOT / "platform/host/paid/systemd/trinyx-paid-runtime-materialize.service", "/etc/systemd/system/trinyx-paid-runtime-materialize.service", 0o644),
        DesiredFile(REPO_ROOT / "platform/host/paid/systemd/docker.service.d/10-trinyx-storage.conf", "/etc/systemd/system/docker.service.d/10-trinyx-storage.conf", 0o644),
        DesiredFile(REPO_ROOT / "platform/contracts/ssm/paid-required.txt", f"{base}/ssm-required.txt", 0o600),
        DesiredFile(rendered / "paid/paid.override.yml", f"{base}/paid.override.yml", 0o600),
        DesiredFile(rendered / "paid/paid-bind.override.yml", f"{base}/paid-bind.override.yml", 0o600),
        DesiredFile(rendered / "paid/paid-runtime.override.yml", f"{base}/paid-runtime.override.yml", 0o600),
        *([DesiredFile(REPO_ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/deployment-plan.json", f"{base}/deployment-plan.json", 0o600),
           DesiredFile(REPO_ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-health-endpoints.json", f"{base}/paid-health-endpoints.json", 0o644)] if environment == "staging" else []),
    ]
    return dirs, files


def service_for(role: str) -> str:
    return "trinyx-cloud-runtime-materialize.service" if role == "cloud" else "trinyx-paid-runtime-materialize.service"


def main() -> None:
    parser = argparse.ArgumentParser(description="Plan or apply Trinyx host platform state")
    parser.add_argument("--role", required=True, choices=("cloud", "paid"))
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--root", default=Path("/"), type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    root = args.root
    if not root.is_absolute():
        fail("--root must be absolute")
    if not root.exists() or not root.is_dir():
        fail("--root must exist and be a directory")
    if not args.inventory.is_file():
        fail("inventory file does not exist")

    with tempfile.TemporaryDirectory(prefix="trinyx-render-") as temp_dir:
        rendered = Path(temp_dir) / "rendered"
        subprocess.run(
            [sys.executable, str(RENDERER), "--inventory", str(args.inventory), "--out", str(rendered)],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        metadata = parse_env(rendered / "metadata.env")

        if root == Path("/"):
            validate_live_host(args.role, metadata)

        dirs, files = build_desired(args.role, rendered, metadata)

        changes: list[tuple[str, str]] = []
        for item in dirs:
            actions = dir_actions(root, item)
            if actions:
                changes.append((item.target, "+".join(actions)))
        for item in files:
            actions = file_actions(root, item)
            if actions:
                changes.append((item.target, "+".join(actions)))

        systemd_enable_needed = False
        if root == Path("/"):
            service = service_for(args.role)
            enabled = subprocess.run(
                ["systemctl", "is-enabled", "--quiet", service], check=False
            ).returncode == 0
            if not enabled:
                systemd_enable_needed = True
                changes.append((f"systemd:{service}", "ENABLE"))

        for target, action in changes:
            print(f"PLAN action={action} target={target}")

        print(
            f"HOST_RECONCILE_PLAN_OK role={args.role} "
            f"environment={metadata['TRINYX_ENVIRONMENT']} changes={len(changes)}"
        )

        if not args.apply:
            return

        if root == Path("/") and os.geteuid() != 0:
            fail("--apply against live root requires root")

        for item in dirs:
            if dir_actions(root, item):
                apply_dir(root, item)
        for item in files:
            if file_actions(root, item):
                atomic_install(root, item)

        if root == Path("/"):
            subprocess.run(["systemctl", "daemon-reload"], check=True)
            if systemd_enable_needed:
                subprocess.run(["systemctl", "enable", service_for(args.role)], check=True)

        residual = 0
        for item in dirs:
            residual += bool(dir_actions(root, item))
        for item in files:
            residual += bool(file_actions(root, item))
        if residual:
            fail(f"reconcile left {residual} residual filesystem changes")

        print(
            f"HOST_RECONCILE_APPLY_OK role={args.role} "
            f"environment={metadata['TRINYX_ENVIRONMENT']} changes={len(changes)} "
            f"runtime_refresh_required={'yes' if changes else 'no'}"
        )


if __name__ == "__main__":
    main()
