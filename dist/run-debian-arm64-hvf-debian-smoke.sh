#!/usr/bin/env bash
# Run the complete Debian 13 arm64 userland gate on one HVF rootfs.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$(uname -s)" != Darwin ] \
    || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "Debian arm64 complete HVF smoke: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

dist/run-debian-arm64-hvf-bash-smoke.sh "$@"
dist/run-debian-arm64-hvf-network-smoke.sh "$@"
echo "Debian arm64 complete same-rootfs HVF smoke: PASS"
