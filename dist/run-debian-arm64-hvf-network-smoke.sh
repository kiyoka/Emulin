#!/usr/bin/env bash
# Run Debian DNS, TLS, and apt gates on Apple Hypervisor.framework.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$(uname -s)" != Darwin ] \
    || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "Debian arm64 HVF network smoke: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

export EMULIN_BACKEND=native
dist/run-debian-arm64-dns-smoke.sh "$@"
dist/run-debian-arm64-tls-smoke.sh "$@"
dist/run-debian-arm64-apt-smoke.sh "$@"
echo "Debian arm64 HVF DNS/TLS/apt smoke: PASS"
