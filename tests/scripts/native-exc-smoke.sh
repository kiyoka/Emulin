#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/native-exc-smoke.sh
#
#  issue #1024: native (KVM) backend が **CPU 例外を vector ごとの signal** として
#  guest に配送することの検証。
#
#  ★ なぜ専用の smoke が要るか:
#    #DE の回帰テスト (insn_divovf64 / insn_dedefault64) は run-all のバイナリテストに
#    自動列挙されるが、**それは software backend でしか走らない**。欠陥は native 側
#    だけに在ったので、software で緑でも何も守れない (#1015 の「在るのに走っていない」
#    と同じで、こちらは「走っているが別の backend を見ている」形)。
#
#  ★ 欠陥の中身 (2026-09-09 に native-oracle-full が検出):
#    IDT stub 経路が **vector 13 (#GP) だけ**を guest signal として配送し、残りは
#    一律 SIGSEGV で殺していた。よって
#      - ハンドラ有り: SIGFPE ハンドラが呼ばれず子が SIGSEGV 死  (insn_divovf64 が FAIL)
#      - ハンドラ無し: WTERMSIG が 8 (SIGFPE) ではなく 11 (SIGSEGV) (insn_dedefault64)
#
#  ★ 負のコントロール: NativeCpuBackend.excSignal() の `case 0:` を消す (= 既定の
#    SIGSEGV に落とす) と、この smoke は 2 本とも FAIL する。入れる前に確認すること。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSDIR=$PROJECT/target/classes
CLASSES=$CLASSDIR
ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.6/asm-9.6.jar"
[ -f "$ASM_JAR" ] && CLASSES="$CLASSES:$ASM_JAR"

# ★ issue #1018: 制限時間は env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
#   ★ 1s では発火しない (この guest は 1s 以内に終わる)。NE_TIMEOUT=0.05 のように小数を使う。
NE_TIMEOUT=${NE_TIMEOUT:-60}

if [ ! -f "$CLASSDIR/emulin/Emulin.class" ]; then
    echo "SKIP native-exc : classes not built ($CLASSDIR)"; exit 2
fi
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP native-exc : /dev/kvm not accessible (KVM 無し / kvm group 未加入)"; exit 2
fi

SANDBOX=$(mktemp -d -t emulin-natexc.XXXXXX)
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc"
: > "$SANDBOX/etc/emulin.cnf"

PASS=0; FAIL=0; FAILED=()
ERRLOG=$SANDBOX/.emulin-stderr

run_case() {  # run_case <binary> <説明>
    local name=$1 what=$2
    local bin=$ROOT/binaries/bin/$name
    local exp=$ROOT/expected/$name.stdout
    if [ ! -f "$bin" ] || [ ! -f "$exp" ]; then
        echo "SKIP    native-exc-$name : binary/expected が無い (make -C tests/binaries)"
        return
    fi
    cp "$bin" "$SANDBOX/bin/"
    local act rc
    act=$( cd "$SANDBOX" && EMULIN_BACKEND=native timeout "$NE_TIMEOUT" \
        java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods \
        --enable-native-access=ALL-UNNAMED -cp "$CLASSES" \
        emulin.Emulin "$SANDBOX" "/bin/$name" 2>"$ERRLOG" )
    rc=$?
    # ★ issue #1018: 値を比べる前に、guest が最後まで走ったかを言う。
    if [ "$rc" != 0 ]; then
        if [ "$rc" = 124 ]; then
            echo "FAIL    native-exc-$name : guest was killed by timeout after ${NE_TIMEOUT}s"
            echo "        (負荷で伸びたなら NE_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
        else
            echo "FAIL    native-exc-$name : guest exited rc=$rc (途中で死んだ)"
        fi
        tail -5 "$ERRLOG" 2>/dev/null | sed 's/^/        /'
        FAIL=$((FAIL+1)); FAILED+=("$name"); return
    fi
    if [ "$act" = "$(cat "$exp")" ]; then
        echo "PASS    native-exc-$name ($what)"
        PASS=$((PASS+1))
    else
        echo "FAIL    native-exc-$name ($what)"
        echo "        --- expected ---"; sed 's/^/        /' "$exp"
        echo "        --- native ---";   printf '%s\n' "$act" | sed 's/^/        /'
        # ★ 例外が SIGSEGV に化けていると stderr に [native][EXC] が出る。手掛かりとして出す。
        grep -a '\[native\]\[EXC\]' "$ERRLOG" 2>/dev/null | head -3 | sed 's/^/        /'
        FAIL=$((FAIL+1)); FAILED+=("$name")
    fi
}

# #DE (vector 0) の 2 経路。expected は実 Linux (実CPU) の実行結果と一致している。
run_case insn_divovf64   "ハンドラ有り: #DE が SIGFPE として配送される (#537)"
run_case insn_dedefault64 "ハンドラ無し: #DE の既定動作が SIGFPE 終了 (#1024)"

echo ""
echo "===== native-exc smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
