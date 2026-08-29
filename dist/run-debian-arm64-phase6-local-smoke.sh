#!/usr/bin/env bash
# Exercise Phase 6 local networking, time, pipe, socketpair, and POSIX pty APIs.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}

test -x "$ROOTFS/usr/bin/emulin-aarch64-phase6-probe" || {
    echo "missing Phase 6 probe; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

OUTPUT=$(mktemp "$ROOT/target/aarch64-phase6-local.XXXXXX")
cleanup() { rm -f "$OUTPUT"; }
trap cleanup EXIT

(
    cd "$ROOTFS/root"
    env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' 180 \
        java -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /usr/bin/emulin-aarch64-phase6-probe
) > "$OUTPUT"

EXPECTED=$(printf '%s\n' \
    clock-pipe-ppoll-ok \
    socketpair-sendmsg-recvmsg-ok \
    udp-sendto-recvfrom-ok \
    tcp-connect-accept-ok \
    posix-pty-ok \
    aarch64-phase6-local-ok)
test "$(tail -n 6 "$OUTPUT")" = "$EXPECTED"
echo "Debian arm64 Phase 6 local smoke: PASS"
