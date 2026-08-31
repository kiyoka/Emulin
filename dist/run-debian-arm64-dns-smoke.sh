#!/usr/bin/env bash
# Resolve a public Debian host through the guest libc and Emulin socket ABI.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}
TIMEOUT_SECONDS=${EMULIN_DNS_TIMEOUT_SECONDS:-180}
source "$ROOT/dist/aarch64-emulin-runtime.sh"
configure_aarch64_emulin_runtime "$ROOT"

test -x "$ROOTFS/usr/bin/emulin-aarch64-phase6-probe" || {
    echo "missing Phase 6 probe; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

OUTPUT=$(
    cd "$ROOTFS/root"
    env EMULIN_BACKEND="$AARCH64_TEST_BACKEND" LC_ALL=C LANG=C \
        perl -e 'alarm shift; exec @ARGV' "$TIMEOUT_SECONDS" \
        "${AARCH64_JAVA_COMMAND[@]}" -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /usr/bin/emulin-aarch64-phase6-probe dns
)
case "$OUTPUT" in
    *dns-getaddrinfo-ok) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac
if [ "$AARCH64_TEST_BACKEND" = software ]; then
    echo "Debian arm64 DNS smoke: PASS"
else
    echo "Debian arm64 DNS smoke ($AARCH64_TEST_BACKEND): PASS"
fi
