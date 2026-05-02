#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/real-ls.sh
#
#  Phase 27: 実機の /bin/ls (動的リンク + libc + libselinux + libpcre2)
#  を Emulin 上で動かす回帰。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (host 不在等)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
TIMEOUT=30

if ! command -v java >/dev/null 2>&1; then echo "SKIP real-ls : java not found"; exit 2; fi
if [ ! -x /bin/ls ]; then echo "SKIP real-ls : /bin/ls not found"; exit 2; fi
if [ ! -f /lib64/ld-linux-x86-64.so.2 ]; then echo "SKIP real-ls : ld-linux not found"; exit 2; fi
if [ ! -f /lib/x86_64-linux-gnu/libc.so.6 ]; then echo "SKIP real-ls : libc.so.6 not found"; exit 2; fi

CLASSES=$PROJECT/target/classes
if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP real-ls : Emulin not built"
    exit 2
fi

CPFILE=$PROJECT/target/cp.txt
if [ ! -f "$CPFILE" ]; then
    ( cd "$PROJECT" && mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt ) >/dev/null 2>&1
fi
CP="$CLASSES:$(cat "$CPFILE" 2>/dev/null || echo '')"

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-realls.XXXXXX)}
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX"/{bin,etc,lib,lib64,tmp}
cp /bin/ls                                "$SANDBOX/bin/"
cp /lib64/ld-linux-x86-64.so.2            "$SANDBOX/lib64/"
cp /lib/x86_64-linux-gnu/libc.so.6        "$SANDBOX/lib/"
[ -f /lib/x86_64-linux-gnu/libselinux.so.1 ] && \
    cp /lib/x86_64-linux-gnu/libselinux.so.1 "$SANDBOX/lib/"
[ -f /lib/x86_64-linux-gnu/libpcre2-8.so.0 ] && \
    cp /lib/x86_64-linux-gnu/libpcre2-8.so.0 "$SANDBOX/lib/"
: > "$SANDBOX/etc/emulin.cnf"

PASS=0
FAIL=0
declare -a FAILED=()

run_case() {
    local name=$1 pat=$2
    shift 2
    local act
    act=$(cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" 2>/dev/null)
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    real-ls-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-ls-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pat"
            echo "  --- actual ---"
            printf '%s\n' "$act" | sed 's/^/  | /' | head -10
        fi
    fi
}

# top-level entries
run_case bare    'lib64'  /bin/ls /
# -la /tmp: total 行が出ること (ls -l フォーマット成立)
run_case la-tmp  'total ' /bin/ls -la /tmp
# -l /lib: libc.so.6 が見えること
run_case l-lib   'libc.so.6' /bin/ls -l /lib

echo
echo "===== real-ls: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
