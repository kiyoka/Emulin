#!/usr/bin/env bash
# Build the Apple Silicon-only FFM shim for Hypervisor.framework SIMD values.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
OUT=${EMULIN_HVF_SHIM:-"$ROOT/target/native/libemulin-hvf-simd.dylib"}

if [ "$(uname -s)" != Darwin ] || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "aarch64-hvf-shim: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

mkdir -p "$(dirname "$OUT")"
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=11.0 \
    -Wall -Wextra -Werror -framework Hypervisor \
    -o "$OUT" src/main/native/macos-aarch64/emulin_hvf_simd.c
echo "$OUT"
