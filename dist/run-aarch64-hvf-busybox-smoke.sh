#!/usr/bin/env bash
# Compare a real static AArch64 BusyBox under software and Apple HVF backends.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${AARCH64_ROOTFS:-$ROOT/target/aarch64-rootfs}
BUSYBOX=${AARCH64_BUSYBOX:-$ROOT/target/aarch64-hvf-busybox}
SIGNED_RUNTIME=${EMULIN_HVF_JAVA:-$ROOT/target/aarch64-hvf-java}
SIMD_SHIM=${EMULIN_HVF_SHIM:-$ROOT/target/native/libemulin-hvf-simd.dylib}

test -d "$ROOTFS/root" || { echo "missing AArch64 rootfs: $ROOTFS" >&2; exit 2; }
test -x "$BUSYBOX" || { echo "missing fixture: run dist/build-aarch64-hvf-busybox.sh" >&2; exit 2; }
test -x "$SIGNED_RUNTIME/bin/java" || { echo "missing signed HVF Java runtime" >&2; exit 2; }
test -f "$SIMD_SHIM" || { echo "missing HVF SIMD shim" >&2; exit 2; }
test -f "$ROOT/target/classes/emulin/Emulin.class" || { echo "missing Emulin classes" >&2; exit 2; }

install -m 755 "$BUSYBOX" "$ROOTFS/usr/bin/busybox-hvf"
printf 'banana\napple\ncherry\n' > "$ROOTFS/tmp/hvf-busybox.txt"
printf 'banana 3\napple 1\ncherry 7\n' > "$ROOTFS/tmp/hvf-busybox-columns.txt"

WORK=$(mktemp -d "$ROOT/target/aarch64-hvf-busybox-smoke.XXXXXX")
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
CASE_NUMBER=0
GUEST_STATUS=0

run_guest() {
    local backend=$1 output=$2 error=$3
    shift 3
    set +e
    (
        cd "$ROOTFS/root"
        env EMULIN_BACKEND="$backend" LC_ALL=C LANG=C \
            "$SIGNED_RUNTIME/bin/java" --enable-native-access=ALL-UNNAMED \
            -Demulin.hvf.simd-shim="$SIMD_SHIM" \
            -cp "$ROOT/target/classes" emulin.Emulin \
            "$ROOTFS" /usr/bin/busybox-hvf "$@"
    ) > "$output" 2> "$error"
    GUEST_STATUS=$?
    set -e
}

compare_applet() {
    local label=$1 expected=$2
    shift 2
    CASE_NUMBER=$((CASE_NUMBER + 1))
    local soft_out="$WORK/$CASE_NUMBER.soft.out"
    local soft_err="$WORK/$CASE_NUMBER.soft.err"
    local native_out="$WORK/$CASE_NUMBER.native.out"
    local native_err="$WORK/$CASE_NUMBER.native.err"
    local soft_status native_status

    run_guest software "$soft_out" "$soft_err" "$@"
    soft_status=$GUEST_STATUS
    run_guest native "$native_out" "$native_err" "$@"
    native_status=$GUEST_STATUS

    if [ "$soft_status" -ne 0 ] || [ "$native_status" -ne 0 ] \
        || ! cmp -s "$soft_out" "$native_out" \
        || ! grep -qF "$expected" "$native_out"; then
        echo "AArch64 HVF BusyBox $label: FAIL (software=$soft_status native=$native_status)" >&2
        echo "--- software stderr ---" >&2
        sed -n '1,120p' "$soft_err" >&2
        echo "--- native stderr ---" >&2
        sed -n '1,120p' "$native_err" >&2
        echo "--- output diff ---" >&2
        diff -u "$soft_out" "$native_out" >&2 || true
        exit 1
    fi
    echo "AArch64 HVF BusyBox $label: PASS"
}

native_applet() {
    local label=$1 expected=$2
    shift 2
    CASE_NUMBER=$((CASE_NUMBER + 1))
    local output="$WORK/$CASE_NUMBER.native.out"
    local error="$WORK/$CASE_NUMBER.native.err"
    run_guest native "$output" "$error" "$@"
    if [ "$GUEST_STATUS" -ne 0 ] || ! grep -qF "$expected" "$output"; then
        echo "AArch64 HVF BusyBox $label: FAIL (native=$GUEST_STATUS)" >&2
        sed -n '1,120p' "$error" >&2
        exit 1
    fi
    echo "AArch64 HVF BusyBox $label: PASS"
}

native_signal_exit() {
    CASE_NUMBER=$((CASE_NUMBER + 1))
    local output="$WORK/$CASE_NUMBER.native.out"
    local error="$WORK/$CASE_NUMBER.native.err"
    run_guest native "$output" "$error" sh -c 'kill -TERM $$; echo must-not-run'
    if [ "$GUEST_STATUS" -ne 143 ] || grep -qF "must-not-run" "$output"; then
        echo "AArch64 HVF BusyBox default-signal-exit: FAIL (native=$GUEST_STATUS)" >&2
        sed -n '1,120p' "$error" >&2
        exit 1
    fi
    echo "AArch64 HVF BusyBox default-signal-exit: PASS"
}

compare_applet echo "hello hvf" echo hello hvf
compare_applet expr "42" expr 6 '*' 7
compare_applet sort "apple" sort /tmp/hvf-busybox.txt
compare_applet grep "banana" grep an /tmp/hvf-busybox.txt
compare_applet sha256sum \
    "64112a2c204881f4aac7da9ffd84a2b0412a193ae9b3773cbab04ff947d2b92c" \
    sha256sum /tmp/hvf-busybox.txt
compare_applet od "62 61 6e" od -An -tx1 /tmp/hvf-busybox.txt
compare_applet awk "sum=11" awk '{s+=$2} END{print "sum="s}' \
    /tmp/hvf-busybox-columns.txt
compare_applet sed "APPLE" sed 's/apple/APPLE/g' /tmp/hvf-busybox.txt
native_applet signal-round-trip "signal-ok" sh -c \
    'trap "echo signal-ok" USR1; kill -USR1 $$'
native_signal_exit

echo "AArch64 HVF static BusyBox smoke: PASS (8 applets native == software, handler/return/default signal native)"
