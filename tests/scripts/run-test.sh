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
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)

BIN=$ROOT/binaries/bin/$NAME
# 並列実行時は呼び出し側が SANDBOX_DIR を指定して衝突を避けられる。
SANDBOX=${SANDBOX_DIR:-$ROOT/sandbox}
EXPECT_OUT=$ROOT/expected/$NAME.stdout
EXPECT_EXIT=$ROOT/expected/$NAME.exit
EXPECT_ARGV=$ROOT/expected/$NAME.argv
EXPECT_STDIN=$ROOT/expected/$NAME.stdin
EXPECT_SKIP=$ROOT/expected/$NAME.skip

if [ -f "$EXPECT_SKIP" ]; then
    REASON=$(head -n1 "$EXPECT_SKIP")
    echo "SKIP $NAME : $REASON"
    exit 2
fi

if [ ! -f "$BIN" ]; then
    echo "SKIP $NAME : binary not built ($BIN)"
    echo "  run 'make -C tests/binaries' first"
    exit 2
fi
CLASSES=$PROJECT/target/classes
# Phase 34-A3: ASM bytecode emitter (emulin.jit が import) を classpath に追加。
# EMULIN_USE_JIT=1 のときに必要 (それ以外では参照されず missing でも OK)。
ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.6/asm-9.6.jar"
if [ -f "$ASM_JAR" ]; then
    CLASSES="$CLASSES:$ASM_JAR"
fi
if [ ! -f "$PROJECT/target/classes/emulin/Emulin.class" ]; then
    echo "SKIP $NAME : Emulin not built ($PROJECT/target/classes/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
if [ ! -f "$EXPECT_OUT" ]; then
    echo "SKIP $NAME : no expected stdout ($EXPECT_OUT)"
    exit 2
fi

# サンドボックスの基本ディレクトリを準備
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
# 依存テスト (例: sys_execve64 が /bin/hello64 を起動する) のため、
# tests/binaries/bin/ 以下の全 ELF を sandbox/bin/ にコピーする。
cp "$ROOT/binaries/bin/"* "$SANDBOX/bin/" 2>/dev/null || true
cp "$BIN" "$SANDBOX/bin/$NAME"

# Phase 24 step 2 関連: 動的リンクテスト (名前に "_dyn" を含む) のときは
# 動的リンカと libc.so.6 をホストから sandbox にコピーしておく。
# (本体 ELF の PT_INTERP が指す /lib64/ld-linux-x86-64.so.2 を
# emulator が直接ホスト FS から読むのではなく sandbox から開けるようにする)
if [[ "$NAME" == *_dyn* ]]; then
    if [ -f /lib64/ld-linux-x86-64.so.2 ] && [ -f /lib/x86_64-linux-gnu/libc.so.6 ]; then
        mkdir -p "$SANDBOX/lib64" "$SANDBOX/lib/x86_64-linux-gnu"
        cp /lib64/ld-linux-x86-64.so.2          "$SANDBOX/lib64/"
        cp /lib/x86_64-linux-gnu/libc.so.6      "$SANDBOX/lib/x86_64-linux-gnu/"
        # Phase 25: C++ / dlopen / wchar 系で必要になる定番ライブラリ。
        # ホストに無い物はスキップ (cp || true)。
        for lib in libm.so.6 libstdc++.so.6 libgcc_s.so.1 libdl.so.2 libz.so.1; do
            [ -f "/lib/x86_64-linux-gnu/$lib" ] && \
                cp "/lib/x86_64-linux-gnu/$lib" "$SANDBOX/lib/x86_64-linux-gnu/"
        done
    fi
fi

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

# SANDBOX 内から起動することで user.dir == root が成立し get_virtual_path が正常動作する。
# -XX:-UsePerfData: /tmp/hsperfdata_* のロック競合警告を抑止 (並列実行時に
# 警告メッセージが他テストの stdout にリークする問題を回避)。
# Phase 27 step 64: -XX:-DontCompileHugeMethods で巨大 method (Cpu64::decode_and_exec
#   が 20K+ bytecode) も JIT C2 コンパイルさせる。git clone HTTPS で 22% 高速化、
#   実機 binary 全般で大きな効果。
JVM_OPTS=( -XX:-UsePerfData -XX:-DontCompileHugeMethods )
if [ -f "$EXPECT_STDIN" ]; then
    (cd "$SANDBOX" && java "${JVM_OPTS[@]}" -cp "$CLASSES" emulin.Emulin "$SANDBOX" "${ARGS[@]}" \
        < "$EXPECT_STDIN" > "$ACT_OUT" 2>/dev/null)
else
    (cd "$SANDBOX" && java "${JVM_OPTS[@]}" -cp "$CLASSES" emulin.Emulin "$SANDBOX" "${ARGS[@]}" \
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
