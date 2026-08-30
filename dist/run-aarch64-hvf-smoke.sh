#!/usr/bin/env bash
# Build an entitled local Java launcher and run the Issue 973 HVF smoke.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)

if [ "$(uname -s)" != Darwin ] || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "aarch64-hvf: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

command -v codesign >/dev/null 2>&1 || {
    echo "ERROR: codesign is required for the Hypervisor.framework entitlement" >&2
    exit 2
}

mvn -q test
bash dist/build-aarch64-hvf-shim.sh >/dev/null
bash dist/build-aarch64-hvf-elf-smoke.sh >/dev/null
bash dist/build-aarch64-hvf-busybox.sh >/dev/null

JDK_ROOT=$(/usr/libexec/java_home)
SIGNED_RUNTIME="$ROOT/target/aarch64-hvf-java"
rm -rf "$SIGNED_RUNTIME"
mkdir -p "$SIGNED_RUNTIME/bin"
cp "$JDK_ROOT/bin/java" "$SIGNED_RUNTIME/bin/java"
ln -s "$JDK_ROOT/lib" "$SIGNED_RUNTIME/lib"
codesign --force --sign - --entitlements dist/macos-hvf.entitlements \
    "$SIGNED_RUNTIME/bin/java" >/dev/null

"$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED -ea \
    -Demulin.hvf.simd-shim="$ROOT/target/native/libemulin-hvf-simd.dylib" \
    -cp "$ROOT/target/classes" emulin.Aarch64HvSmoke
"$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED -ea \
    -Demulin.hvf.simd-shim="$ROOT/target/native/libemulin-hvf-simd.dylib" \
    -cp "$ROOT/target/classes" emulin.Aarch64HvElfSmoke \
    "$ROOT/target/aarch64-hvf-elf-smoke"

# If the Debian rootfs has already been prepared, also exercise the production
# GuestAbi/CpuBackend/Process route. The fixture deliberately exits with 0x51.
ROOTFS=${AARCH64_ROOTFS:-$ROOT/target/aarch64-rootfs}
if [ -d "$ROOTFS/root" ] && [ -d "$ROOTFS/usr/bin" ]; then
    install -m 755 "$ROOT/target/aarch64-hvf-elf-smoke" \
        "$ROOTFS/usr/bin/aarch64-hvf-native-smoke"

    run_backend() {
        local backend=$1
        local status
        set +e
        (
            cd "$ROOTFS/root"
            env EMULIN_BACKEND="$backend" LC_ALL=C LANG=C \
                "$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED \
                -Demulin.hvf.simd-shim="$ROOT/target/native/libemulin-hvf-simd.dylib" \
                -cp "$ROOT/target/classes" emulin.Emulin \
                "$ROOTFS" /usr/bin/aarch64-hvf-native-smoke
        )
        status=$?
        set -e
        if [ "$status" -ne 81 ]; then
            echo "AArch64 HVF $backend backend smoke: FAIL (exit=$status, expected=81)" >&2
            exit 1
        fi
        echo "AArch64 HVF $backend backend smoke: PASS (exit=81)"
    }

    run_backend software
    run_backend native
    run_backend auto
    bash dist/run-aarch64-hvf-busybox-smoke.sh
else
    echo "AArch64 HVF backend integration: SKIP (build Debian arm64 rootfs first)"
fi
