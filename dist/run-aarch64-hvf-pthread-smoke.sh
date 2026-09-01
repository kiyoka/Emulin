#!/usr/bin/env bash
# Run the dynamic glibc pthread/TLS probe with one shared HVF VM and two vCPUs.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${AARCH64_ROOTFS:-$ROOT/target/aarch64-rootfs}
FIXTURE=${AARCH64_HVF_PTHREAD_FIXTURE:-$ROOT/target/aarch64-hvf-pthread-smoke}
SIGNED_RUNTIME=${EMULIN_HVF_JAVA:-$ROOT/target/aarch64-hvf-java}
SIMD_SHIM=${EMULIN_HVF_SHIM:-$ROOT/target/native/libemulin-hvf-simd.dylib}

test -s "$FIXTURE" && test -x "$FIXTURE" \
    || { echo "missing fixture: run dist/build-aarch64-hvf-pthread-smoke.sh" >&2; exit 2; }
test -d "$ROOTFS/root" || { echo "missing AArch64 rootfs: $ROOTFS" >&2; exit 2; }
test -x "$SIGNED_RUNTIME/bin/java" || { echo "missing signed HVF Java runtime" >&2; exit 2; }

install -m 755 "$FIXTURE" "$ROOTFS/usr/bin/aarch64-hvf-pthread-smoke"
OUTPUT=$(mktemp "$ROOT/target/aarch64-hvf-pthread.XXXXXX")
cleanup() { rm -f "$OUTPUT"; }
trap cleanup EXIT

(
    cd "$ROOTFS/root"
    env EMULIN_BACKEND=native LC_ALL=C LANG=C \
        "$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED \
        -Demulin.hvf.simd-shim="$SIMD_SHIM" \
        -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /usr/bin/aarch64-hvf-pthread-smoke
) > "$OUTPUT"

test "$(tail -n 1 "$OUTPUT")" = "aarch64-hvf-pthread-tls-ok"
echo "AArch64 HVF dynamic pthread/TLS smoke: PASS"
