#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/ash-interactive-cooked.sh
#
#  Phase 22 (2): 対話 ash (cooked モード) の回帰テスト。
#
#  busybox ash -i に stdin から行単位でコマンドを流し、プロンプト →
#  行読み → 実行 → 次プロンプト のサイクルが回ることを検証する。
#  raw mode は使わず Std_read (System.in.read) の行バッファのみ。
#
#  検証は「出力にこの文字列が含まれていること」(grep -F) ベース。
#  プロンプト文字列やバナーはバージョン依存なので比較対象から外す。
#
#  使い方:
#    bash tests/scripts/ash-interactive-cooked.sh
#    bash tests/scripts/ash-interactive-cooked.sh echo-line
#    VERBOSE=1 bash tests/scripts/ash-interactive-cooked.sh
#
#  終了コード: 0=全 PASS / 1=FAIL あり / 2=SKIP (Emulin/busybox 未ビルド)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SANDBOX=${SANDBOX_DIR:-$ROOT/sandbox}
CLASSES=$PROJECT/target/classes
HOST_BB=/usr/bin/busybox
TIMEOUT=15

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP ash-interactive-cooked : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP ash-interactive-cooked : host busybox not found at $HOST_BB"
    exit 2
fi

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# テストケース定義: name@@stdin@@expect_pattern
# - stdin は printf %b 形式 (\n などのエスケープが効く)
# - expect_pattern は grep -F で actual stdout から検索する
CASES=(
    'echo-line@@echo hello-cooked\nexit 0\n@@hello-cooked'
    'two-cmds@@echo first-line\necho second-line\nexit 0\n@@second-line'
    'exit-status@@true\necho rc=$?\nexit 0\n@@rc=0'
    'arith-interactive@@echo $((6*7))\nexit 0\n@@42'
    'pipe-interactive@@seq 1 5 | wc -l\nexit 0\n@@5'
)

# 引数で名前指定があれば絞り込み
if [ $# -gt 0 ]; then
    SELECT=" $* "
    NEW=()
    for c in "${CASES[@]}"; do
        n=${c%%@@*}
        if [[ "$SELECT" == *" $n "* ]]; then NEW+=("$c"); fi
    done
    CASES=("${NEW[@]}")
fi

PASS=0
FAIL=0
declare -a FAILED=()

for entry in "${CASES[@]}"; do
    name=${entry%%@@*}
    rest=${entry#*@@}
    stdin_data=${rest%%@@*}
    pattern=${rest#*@@}

    actual=$(printf '%b' "$stdin_data" | (cd "$SANDBOX" && timeout $TIMEOUT \
        java -cp "$CLASSES" emulin.Emulin "$SANDBOX" /bin/busybox ash -i 2>/dev/null))
    rc=$?

    if [ "$rc" = 124 ]; then
        printf 'FAIL    ash-int-%s (timeout)\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name(timeout)")
        continue
    fi

    if printf '%s' "$actual" | grep -F -q -- "$pattern"; then
        printf 'PASS    ash-int-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    ash-int-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pattern"
            echo "  --- actual stdout ---"
            printf '%s\n' "$actual" | sed 's/^/  | /' | head -30
        fi
    fi
done

echo
echo "===== ash interactive (cooked): PASS=$PASS FAIL=$FAIL (total=${#CASES[@]}) ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
