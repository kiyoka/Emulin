#!/usr/bin/env bash
# Build the minimal Debian 13 arm64 rootfs used by the issue #951 bash smoke.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-$ROOT/target/aarch64-rootfs}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac

mkdir -p "$ROOT/target"
STAGE=$(mktemp -d "$ROOT/target/.aarch64-rootfs.XXXXXX")
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    test "$(uname -m)" = aarch64
    test "$(dpkg --print-architecture)" = arm64
    test "$(. /etc/os-release; echo "$VERSION_ID")" = 13
    tinfo=$(readlink -f /usr/lib/aarch64-linux-gnu/libtinfo.so.6)
    tinfo=${tinfo#/}
    tar -C / -cf - \
        usr/bin/bash \
        usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \
        usr/lib/aarch64-linux-gnu/libc.so.6 \
        usr/lib/aarch64-linux-gnu/libtinfo.so.6 \
        "$tinfo"
' | tar -C "$STAGE" -xf -

mkdir -p "$STAGE/etc" "$STAGE/root" "$STAGE/tmp"
ln -s usr/bin "$STAGE/bin"
ln -s usr/lib "$STAGE/lib"
ln -s aarch64-linux-gnu/ld-linux-aarch64.so.1 \
    "$STAGE/usr/lib/ld-linux-aarch64.so.1"
: > "$STAGE/etc/emulin.cnf"

rm -rf "$OUT"
mv "$STAGE" "$OUT"
trap - EXIT
echo "Debian arm64 bash rootfs: $OUT"
