#!/usr/bin/env bash
set -Eeuo pipefail
umask 022

exec > >(tee -a /var/log/trinyx-bootstrap.log) 2>&1

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

trap 'rc=$?; echo "TRINYX_HOST_BASE_BOOTSTRAP_FAILED rc=$rc" >&2' ERR

if [ "$(id -u)" -ne 0 ]; then
    fail "host bootstrap must run as root"
fi

echo "=== TRINYX HOST BASE BOOTSTRAP START ==="
date -Is

export DEBIAN_FRONTEND=noninteractive

# -----------------------------------------------------------------------------
# 1. Install only the host prerequisites needed by the runtime/deploy platform.
#    Application source is deliberately NOT cloned on the instance.
# -----------------------------------------------------------------------------

apt-get update
apt-get install -y \
    ca-certificates \
    curl \
    python3 \
    awscli

# -----------------------------------------------------------------------------
# 2. Docker official repository.
# -----------------------------------------------------------------------------

install -m 0755 -d /etc/apt/keyrings

curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
chmod 0644 /etc/apt/keyrings/docker.asc

. /etc/os-release
CODENAME="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
ARCH="$(dpkg --print-architecture)"

printf '%s\n' \
    "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin

# Docker may have auto-started against the root filesystem during package
# installation. Stop it before configuring the persistent Trinyx data disk.
systemctl stop docker.service docker.socket || true

# -----------------------------------------------------------------------------
# 3. Identify the root disk and require exactly one secondary data disk.
#    Never silently choose the first disk if several are attached.
# -----------------------------------------------------------------------------

ROOT_PART="$(findmnt -n -o SOURCE /)"
ROOT_PART="$(readlink -f "$ROOT_PART")"
ROOT_PARENT="$(lsblk -no PKNAME "$ROOT_PART" | head -n1)"
[ -n "$ROOT_PARENT" ] || fail "unable to determine root parent disk"
ROOT_DISK="/dev/${ROOT_PARENT}"

mapfile -t SECONDARY_DISKS < <(
    lsblk -dpno NAME,TYPE \
        | awk '$2 == "disk" {print $1}' \
        | grep -vxF "$ROOT_DISK" || true
)

if [ "${#SECONDARY_DISKS[@]}" -ne 1 ]; then
    fail "expected exactly one secondary data disk, found ${#SECONDARY_DISKS[@]}"
fi

DATA_DISK="${SECONDARY_DISKS[0]}"

echo "Root disk : $ROOT_DISK"
echo "Data disk : $DATA_DISK"

MIN_DATA_DISK_BYTES="${TRINYX_MIN_DATA_DISK_BYTES:-90000000000}"
DATA_SIZE="$(lsblk -dnbo SIZE "$DATA_DISK")"

if [ "$DATA_SIZE" -lt "$MIN_DATA_DISK_BYTES" ]; then
    fail "secondary data disk is smaller than the required minimum"
fi

# This platform layout uses a filesystem directly on the EBS block device.
# A partitioned disk is unexpected and must be reviewed instead of overwritten.
CHILD_COUNT="$(lsblk -nrpo NAME "$DATA_DISK" | tail -n +2 | wc -l)"
if [ "$CHILD_COUNT" -ne 0 ]; then
    fail "secondary data disk has partitions/children; refusing to modify it"
fi

# -----------------------------------------------------------------------------
# 4. Format only a truly blank disk. Existing EBS filesystems are preserved.
# -----------------------------------------------------------------------------

if ! blkid "$DATA_DISK" >/dev/null 2>&1; then
    mkfs.ext4 -F "$DATA_DISK"
fi

DATA_UUID="$(blkid -s UUID -o value "$DATA_DISK")"
[ -n "$DATA_UUID" ] || fail "secondary data disk has no filesystem UUID"

install -d -o root -g root -m 0755 /srv/trinyx

if grep -Eq '^[^#]+[[:space:]]+/srv/trinyx[[:space:]]+' /etc/fstab; then
    grep -Eq "^UUID=${DATA_UUID}[[:space:]]+/srv/trinyx[[:space:]]+" /etc/fstab \
        || fail "/srv/trinyx already has a different fstab mapping"
else
    printf 'UUID=%s /srv/trinyx ext4 defaults 0 2\n' "$DATA_UUID" >> /etc/fstab
fi

if ! mountpoint -q /srv/trinyx; then
    mount /srv/trinyx
fi

MOUNT_SOURCE="$(findmnt -n -o SOURCE /srv/trinyx)"
MOUNT_UUID="$(blkid -s UUID -o value "$MOUNT_SOURCE")"
[ "$MOUNT_UUID" = "$DATA_UUID" ] \
    || fail "/srv/trinyx is not mounted from the expected data disk"

# -----------------------------------------------------------------------------
# 5. Docker persistent storage contract.
# -----------------------------------------------------------------------------

install -d -o root -g root -m 0755 /srv/trinyx/docker
install -d -o root -g root -m 0755 /etc/docker
install -d -o root -g root -m 0755 /etc/systemd/system/docker.service.d

cat > /etc/docker/daemon.json <<'EOF'
{
  "data-root": "/srv/trinyx/docker"
}
EOF

# Base invariant shared by Cloud and Paid. Role-specific bootstrap may add
# stronger dependencies (for example Cloud pre-Docker runtime prerequisites).
cat > /etc/systemd/system/docker.service.d/10-trinyx-storage.conf <<'EOF'
[Unit]
RequiresMountsFor=/srv/trinyx
EOF

systemctl daemon-reload
systemctl enable docker.service
systemctl start docker.service

# -----------------------------------------------------------------------------
# 6. Fail-closed validation and persistent bootstrap marker.
# -----------------------------------------------------------------------------

findmnt /srv/trinyx >/dev/null
[ "$(docker info --format '{{.DockerRootDir}}')" = "/srv/trinyx/docker" ] \
    || fail "Docker is not using /srv/trinyx/docker"

python3 --version
aws --version
docker --version
docker compose version

install -d -o root -g root -m 0755 /var/lib/trinyx-bootstrap
date -Is > /var/lib/trinyx-bootstrap/host-base-complete
chmod 0644 /var/lib/trinyx-bootstrap/host-base-complete

echo "TRINYX_HOST_BASE_BOOTSTRAP_OK"
echo "=== TRINYX HOST BASE BOOTSTRAP COMPLETE ==="

sync
