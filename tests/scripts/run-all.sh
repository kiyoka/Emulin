#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-all.sh
#
#  tests/binaries/src/*.c に対応する全テストを実行する。
#
#  終了コード: いずれかが FAIL なら 1、全て PASS/SKIP なら 0
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
SRC_DIR=$ROOT/binaries/src

# 並列度: 環境変数 JOBS 優先、未指定なら nproc。JOBS=1 で従来の逐次実行に戻る。
JOBS=${JOBS:-$( (nproc 2>/dev/null || echo 4) )}

PASS=0
FAIL=0
SKIP=0
FAIL_NAMES=()

# テスト名一覧を先に作る (sys_*64.c だけでなく *.c 全部 = 旧仕様と同じ)
NAMES=()
for src in "$SRC_DIR"/*.c; do
    [ -f "$src" ] || continue
    NAMES+=("$(basename "$src" .c)")
done

# 一時的な結果格納ディレクトリ
RESULTDIR=$(mktemp -d -t emulin-regrun.XXXXXX)
trap 'rm -rf "$RESULTDIR" "$ROOT"/sandbox.* 2>/dev/null || true' EXIT

# 1 件分のラッパ: 専用の sandbox.$name で run-test.sh を呼び stdout / exit code
# を $outdir に保存する。xargs から bash -c で起動する。
run_one_to_dir() {
    local name=$1 outdir=$2 root=$3
    SANDBOX_DIR="$root/sandbox.$name" \
        "$root/scripts/run-test.sh" "$name" > "$outdir/$name.out" 2>&1
    echo $? > "$outdir/$name.rc"
}
export -f run_one_to_dir

# xargs -P で並列実行
printf '%s\n' "${NAMES[@]}" | xargs -n1 -P "$JOBS" -I{} \
    bash -c 'run_one_to_dir "$@"' _ {} "$RESULTDIR" "$ROOT"

# 結果集計 (元のソース順序で出す)
for name in "${NAMES[@]}"; do
    [ -f "$RESULTDIR/$name.rc" ] || continue
    cat "$RESULTDIR/$name.out"
    rc=$(cat "$RESULTDIR/$name.rc")
    case $rc in
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
# Phase 22 (3f): ディストリビューション zip の build + 解凍 + 起動スモーク
run_ext_script "$ROOT/scripts/dist-smoke.sh"             "dist zip smoke"

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
