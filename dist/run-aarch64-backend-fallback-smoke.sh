#!/usr/bin/env bash
# Deterministic AArch64 backend selection on hosts where Apple HVF is unsupported.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)

test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

java -ea -cp "$ROOT/target/classes" emulin.Aarch64HvAvailabilitySmoke
