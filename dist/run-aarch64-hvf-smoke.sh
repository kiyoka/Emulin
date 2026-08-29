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

JDK_ROOT=$(/usr/libexec/java_home)
SIGNED_RUNTIME="$ROOT/target/aarch64-hvf-java"
rm -rf "$SIGNED_RUNTIME"
mkdir -p "$SIGNED_RUNTIME/bin"
cp "$JDK_ROOT/bin/java" "$SIGNED_RUNTIME/bin/java"
ln -s "$JDK_ROOT/lib" "$SIGNED_RUNTIME/lib"
codesign --force --sign - --entitlements dist/macos-hvf.entitlements \
    "$SIGNED_RUNTIME/bin/java" >/dev/null

exec "$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED -ea \
    -cp "$ROOT/target/classes" emulin.Aarch64HvSmoke
