#!/usr/bin/env bash
# Build the public AArch64 HVF ELF fixture in the configured Debian arm64 VM.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-"$ROOT/target/aarch64-hvf-elf-smoke"}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    work=$(mktemp -d)
    trap '\''rm -rf "$work"'\'' EXIT
    cat > "$work/smoke.S"
    cc -nostdlib -static -Wl,--build-id=none -Wl,-e,_start \
        "$work/smoke.S" -o "$work/smoke"
    cat "$work/smoke"
' < tests/tools/aarch64_hvf_elf_smoke.S > "$OUT"
chmod 0755 "$OUT"
file "$OUT"
