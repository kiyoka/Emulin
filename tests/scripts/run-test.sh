#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-test.sh
#
#  単一の回帰テストを実行する。
#
#  使い方:
#    run-test.sh <name>
#
#    <name> は tests/binaries/bin/<name> と
#    tests/expected/<name>.{stdout,exit,argv,stdin} に対応する。
#
#  動作:
#    1. binaries/bin/<name> を tests/sandbox/bin/<name> にコピー
#    2. 任意の expected/<name>.argv から実行引数を読む (省略時は /bin/<name>)
#    3. expected/<name>.stdin があれば stdin から流す
#    4. java emulin.Emulin <sandbox> <args...> を起動
#    5. stdout を expected/<name>.stdout と diff
#    6. exit code を expected/<name>.exit と比較 (省略時は 0)
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (バイナリ未ビルド等)
# --------------------------------------------------------------------
set -u

if [ $# -ne 1 ]; then
    echo "usage: $0 <test-name>" >&2
    exit 2
fi

NAME=$1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROJECT=$(cd "$ROOT/.." && pwd)

BIN=$ROOT/binaries/bin/$NAME
SANDBOX=$ROOT/sandbox
EXPECT_OUT=$ROOT/expected/$NAME.stdout
EXPECT_EXIT=$ROOT/expected/$NAME.exit
EXPECT_ARGV=$ROOT/expected/$NAME.argv
EXPECT_STDIN=$ROOT/expected/$NAME.stdin

if [ ! -f "$BIN" ]; then
    echo "SKIP $NAME : binary not built ($BIN)"
    echo "  run 'make -C tests/binaries' first"
    exit 2
fi
if [ ! -f "$EXPECT_OUT" ]; then
    echo "SKIP $NAME : no expected stdout ($EXPECT_OUT)"
    exit 2
fi

# サンドボックスの bin/ にバイナリを配置
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc"
cp "$BIN" "$SANDBOX/bin/$NAME"

# 引数 (default: /bin/<name> 単独)
if [ -f "$EXPECT_ARGV" ]; then
    # shellcheck disable=SC2207
    ARGS=($(cat "$EXPECT_ARGV"))
else
    ARGS=("/bin/$NAME")
fi

# 期待 exit code
if [ -f "$EXPECT_EXIT" ]; then
    EXP_EXIT=$(cat "$EXPECT_EXIT")
else
    EXP_EXIT=0
fi

# emulin.cnf がなければ最小のものを置く
if [ ! -f "$SANDBOX/etc/emulin.cnf" ]; then
    : > "$SANDBOX/etc/emulin.cnf"
fi

ACT_OUT=$(mktemp)
trap 'rm -f "$ACT_OUT"' EXIT

# クラスパスは emulin/*.class が見える PROJECT 直下
if [ -f "$EXPECT_STDIN" ]; then
    (cd "$PROJECT" && java -cp . emulin.Emulin "$SANDBOX" "${ARGS[@]}" \
        < "$EXPECT_STDIN" > "$ACT_OUT" 2>/dev/null)
else
    (cd "$PROJECT" && java -cp . emulin.Emulin "$SANDBOX" "${ARGS[@]}" \
        < /dev/null > "$ACT_OUT" 2>/dev/null)
fi
ACT_EXIT=$?

DIFF_OUT=$(diff -u "$EXPECT_OUT" "$ACT_OUT" || true)

if [ -n "$DIFF_OUT" ] || [ "$ACT_EXIT" != "$EXP_EXIT" ]; then
    echo "FAIL $NAME"
    if [ -n "$DIFF_OUT" ]; then
        echo "--- stdout diff ---"
        echo "$DIFF_OUT"
    fi
    if [ "$ACT_EXIT" != "$EXP_EXIT" ]; then
        echo "--- exit code: expected=$EXP_EXIT actual=$ACT_EXIT ---"
    fi
    exit 1
fi

echo "PASS $NAME"
exit 0
