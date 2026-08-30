#!/usr/bin/env bash
# Fetch the public static AArch64 BusyBox fixture from the configured Debian VM.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-"$ROOT/target/aarch64-hvf-busybox"}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    test -x /bin/busybox
    file /bin/busybox >&2
    cat /bin/busybox
' > "$OUT"
chmod 0755 "$OUT"

DESCRIPTION=$(file "$OUT")
case "$DESCRIPTION" in
    *"ELF 64-bit"*"ARM aarch64"*"statically linked"*) ;;
    *) echo "not a static AArch64 BusyBox: $DESCRIPTION" >&2; exit 2 ;;
esac
echo "$DESCRIPTION"
