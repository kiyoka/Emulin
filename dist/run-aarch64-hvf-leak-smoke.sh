#!/usr/bin/env bash
# Repeated fork/exec leak accounting for the process-wide Apple HVF VM.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}
ITERATIONS=${EMULIN_HVF_LEAK_ITERATIONS:-25}

if [ "$(uname -s)" != Darwin ] \
    || { [ "$(uname -m)" != arm64 ] && [ "$(uname -m)" != aarch64 ]; }; then
    echo "AArch64 HVF leak smoke: SKIP (requires Apple Silicon macOS)"
    exit 0
fi

source "$ROOT/dist/aarch64-emulin-runtime.sh"
EMULIN_BACKEND=native configure_aarch64_emulin_runtime "$ROOT"

test -x "$ROOTFS/usr/bin/bash" || {
    echo "missing rootfs; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
case "$ITERATIONS" in
    ''|*[!0-9]*|0) echo "EMULIN_HVF_LEAK_ITERATIONS must be positive" >&2; exit 2 ;;
esac

OUTPUT=$(mktemp "$ROOT/target/aarch64-hvf-leak.out.XXXXXX")
ERROR=$(mktemp "$ROOT/target/aarch64-hvf-leak.err.XXXXXX")
cleanup() { rm -f "$OUTPUT" "$ERROR"; }
trap cleanup EXIT

(
    cd "$ROOTFS/root"
    env EMULIN_BACKEND=native EMULIN_LEAKCHECK=1 LC_ALL=C LANG=C \
        perl -e 'alarm shift; exec @ARGV' 300 \
        "${AARCH64_JAVA_COMMAND[@]}" -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /bin/bash -c '
            iteration=0
            while [ "$iteration" -lt "$1" ]; do
                /usr/bin/true || exit
                iteration=$((iteration + 1))
            done
            printf "hvf-leak-loop-ok\n"
        ' bash "$ITERATIONS"
) > "$OUTPUT" 2> "$ERROR"

grep -q '^hvf-leak-loop-ok$' "$OUTPUT" || {
    cat "$OUTPUT" >&2
    cat "$ERROR" >&2
    exit 1
}
LEAK_LINE=$(grep '^\[leakcheck\]' "$ERROR" | tail -n 1)
test -n "$LEAK_LINE" || {
    cat "$ERROR" >&2
    echo "missing leakcheck result" >&2
    exit 1
}
ALLOCATED=$(printf '%s\n' "$LEAK_LINE" | sed -E 's/.* pool_alloc=([0-9]+).*/\1/')
FREED=$(printf '%s\n' "$LEAK_LINE" | sed -E 's/.* pool_free=([0-9]+).*/\1/')
test "$ALLOCATED" = "$FREED" && test "$ALLOCATED" -gt 1 || {
    echo "$LEAK_LINE" >&2
    exit 1
}
case "$LEAK_LINE" in
    *" pool_live_mb=0 proc_live=0 thread_live=0 pipe_open=0 pty_open=0 guest_fd=0") ;;
    *) echo "$LEAK_LINE" >&2; exit 1 ;;
esac

echo "AArch64 HVF repeated process leak smoke: PASS ($ITERATIONS forks, $ALLOCATED pools released)"
