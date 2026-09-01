#!/usr/bin/env bash
# Run the Debian 13 arm64 bash/coreutils/dpkg gate on Apple Hypervisor.framework.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$(uname -s)" != Darwin ] \
    || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "Debian arm64 HVF bash smoke: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

env EMULIN_BACKEND=native dist/run-debian-arm64-bash-smoke.sh "$@"
