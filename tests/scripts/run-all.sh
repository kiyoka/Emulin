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

# 外部スクリプト形式の回帰 (PASS/FAIL/SKIP の行を集計)
run_ext_script() {
    local script=$1 label=$2
    [ -f "$script" ] || return 0
    echo
    echo "----- $label -----"
    local out
    out=$(bash "$script")
    local rc=$?
    echo "$out"
    while IFS= read -r line; do
        case "$line" in
            "PASS    "*) PASS=$((PASS + 1)) ;;
            "FAIL    "*)
                n=${line#FAIL    }
                FAIL=$((FAIL + 1))
                FAIL_NAMES+=("$n")
                ;;
        esac
    done <<<"$out"
    if [ "$rc" = 2 ]; then SKIP=$((SKIP + 1)); fi
}

# Phase 22 (1): busybox ash -c '<script>' の非対話モード回帰
run_ext_script "$ROOT/scripts/ash-noninteractive.sh"     "ash non-interactive regression"
# Phase 22 (2): busybox ash -i (cooked) の対話モード回帰
run_ext_script "$ROOT/scripts/ash-interactive-cooked.sh" "ash interactive (cooked) regression"
# Phase 22 (3a): JLine 依存導入のスモーク
run_ext_script "$ROOT/scripts/jline-smoke.sh"            "JLine smoke"
# Phase 22 (3b): -CJ (JLine 経路) で対話 ash の cooked が動くか
run_ext_script "$ROOT/scripts/ash-interactive-jline.sh"  "ash interactive (-CJ JLine) regression"

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
