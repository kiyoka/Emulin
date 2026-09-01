#!/usr/bin/env bash
# Build the public dynamic glibc pthread/TLS fixture in the Debian arm64 VM.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-"$ROOT/target/aarch64-hvf-pthread-smoke"}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac

TEMP=$(mktemp "$ROOT/target/.aarch64-hvf-pthread.XXXXXX")
cleanup() { rm -f "$TEMP"; }
trap cleanup EXIT

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    work=$(mktemp -d)
    trap '\''rm -rf "$work"'\'' EXIT
    cat > "$work/pthread-smoke.c"
    cc -O2 -Wall -Wextra -Werror -pthread \
        "$work/pthread-smoke.c" -o "$work/pthread-smoke"
    cat "$work/pthread-smoke"
' < tests/tools/aarch64_hvf_pthread_smoke.c > "$TEMP"
chmod 0755 "$TEMP"
DESCRIPTION=$(file "$TEMP")
case "$DESCRIPTION" in
    *"ELF 64-bit"*"ARM aarch64"*"dynamically linked"*) ;;
    *) echo "not a dynamic AArch64 ELF: $DESCRIPTION" >&2; exit 2 ;;
esac
mv "$TEMP" "$OUT"
trap - EXIT
echo "$(file "$OUT")"
