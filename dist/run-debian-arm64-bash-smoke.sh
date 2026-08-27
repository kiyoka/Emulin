#!/usr/bin/env bash
# Run Debian arm64 dynamic bash under Emulin and require a clean exit.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}

test -x "$ROOTFS/usr/bin/bash" || {
    echo "missing rootfs; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

OUTPUT=$(mktemp "$ROOT/target/aarch64-bash-smoke.XXXXXX")
cleanup() { rm -f "$OUTPUT"; }
trap cleanup EXIT

(
    cd "$ROOTFS/root"
    env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' 30 \
        java -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /bin/bash -c 'printf "bash-rootfs-ok\n"'
) > "$OUTPUT"

grep -qx 'bash-rootfs-ok' "$OUTPUT"
echo "Debian arm64 bash smoke: PASS"
