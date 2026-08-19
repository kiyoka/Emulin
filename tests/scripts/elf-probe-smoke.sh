#!/usr/bin/env bash
# issue #951 Phase 0/1: architecture selection and minimal guest execution.
set -u

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
bash "$ROOT/tests/scripts/check-build-fresh.sh" "tests/scripts/elf-probe-smoke.sh" || exit 2

TMP=$(mktemp -d -t emulin-elf-probe.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

java -cp "$ROOT/target/classes" emulin.ElfProbeSmoke "$TMP/fixtures"

for name in hello-i386 hello-x86_64 hello-aarch64; do
    sandbox="$TMP/sandbox-$name"
    mkdir -p "$sandbox/bin" "$sandbox/etc" "$sandbox/tmp"
    cp "$TMP/fixtures/$name" "$sandbox/bin/$name"
    : > "$sandbox/etc/emulin.cnf"
    actual=$(cd "$sandbox" && java -cp "$ROOT/target/classes" \
        emulin.Emulin "$sandbox" "/bin/$name" </dev/null 2>/dev/null)
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "FAIL $name: unexpected exit status: $status" >&2
        exit 1
    fi
    case "$name:$actual" in
        hello-i386:i386|hello-x86_64:x86_64|hello-aarch64:aarch64) ;;
        *) echo "FAIL $name: unexpected stdout: $actual" >&2; exit 1 ;;
    esac
done

echo "Guest architecture execution smoke OK"
