#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-all.sh
#
#  tests/binaries/src/*.c に対応する全テストを実行する。
#
#  終了コード: いずれかが FAIL なら 1、全て PASS/SKIP なら 0
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC_DIR=$ROOT/binaries/src

PASS=0
FAIL=0
SKIP=0
FAIL_NAMES=()

for src in "$SRC_DIR"/*.c; do
    [ -f "$src" ] || continue
    name=$(basename "$src" .c)
    "$ROOT/scripts/run-test.sh" "$name"
    case $? in
        0) PASS=$((PASS + 1)) ;;
        1) FAIL=$((FAIL + 1)); FAIL_NAMES+=("$name") ;;
        2) SKIP=$((SKIP + 1)) ;;
    esac
done

# Phase 22 (1): busybox ash -c '<script>' の非対話モード回帰
ASH_SCRIPT=$ROOT/scripts/ash-noninteractive.sh
if [ -x "$ASH_SCRIPT" ] || [ -f "$ASH_SCRIPT" ]; then
    echo
    echo "----- ash non-interactive regression -----"
    ASH_OUT=$(bash "$ASH_SCRIPT")
    ASH_RC=$?
    echo "$ASH_OUT"
    while IFS= read -r line; do
        case "$line" in
            "PASS    ash-"*) PASS=$((PASS + 1)) ;;
            "FAIL    ash-"*)
                n=${line#FAIL    }
                FAIL=$((FAIL + 1))
                FAIL_NAMES+=("$n")
                ;;
        esac
    done <<<"$ASH_OUT"
    if [ "$ASH_RC" = 2 ]; then
        SKIP=$((SKIP + 1))
    fi
fi

echo
echo "===== regression result ====="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
if [ $FAIL -gt 0 ]; then
    echo "  failed: ${FAIL_NAMES[*]}"
    exit 1
fi
exit 0
