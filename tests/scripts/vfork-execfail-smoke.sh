#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/vfork-execfail-smoke.sh
#
#  issue #1028: **vfork の子の execve が「後戻りできない地点」で失敗しても、
#  親が resume すること**の検証。
#
#  ★ 実害 (2026-09-10): guest 内で gcc を動かすと collect2 → ld の posix_spawn で
#    execve が内部エラー (heap 不足 → OutOfMemoryError → ENOMEM) になり、
#    `Process.vfork_signal_parent()` に到達しないまま子が止まった。親は
#    `Kernel.vfork` の CountDownLatch で **永久に park** し、プロセスツリーごと停止。
#    38 分放置しても動かず、jstack を採るまで何も分からなかった。
#    countDown するのは execve 成功 / exit_group / exit の **3 か所だけ**で、
#    それ以外の終わり方が 1 つでもあると固まる ("N 個のうち 1 個" 型)。
#
#  ★ なぜ binary テスト (run-test) だけでは足りないか:
#    通常経路 (exec 成功) は vfork_execfail_dyn64 が見ているが、**失敗経路は
#    自然には起こせない**。EMULIN_FORCE_EXEC_FAIL で決定的に起こして検証する
#    (EMULIN_FORCE_POOL_EXHAUST #720 と同じ流儀)。
#
#  ★ 負のコントロール (入れる前に確認済み):
#    - SyscallAmd64 の catch から vfork_signal_parent() を外し、
#      EMULIN_VFORK_WAIT_SEC=0 (上限なし) にすると **固まる** (25s 打ち切り)
#    - 同じ状態で EMULIN_VFORK_WAIT_SEC=3 にすると安全網が
#      "[vfork] child pid=3 did not execve/_exit within 3s" を出して畳む
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
BIN=$ROOT/binaries/bin/vfork_execfail_dyn64

# ★ issue #1018: 制限時間は env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
VE_TIMEOUT=${VE_TIMEOUT:-60}

[ -f "$CLASSDIR/emulin/Emulin.class" ] || { echo "SKIP vfork-execfail : classes not built"; exit 2; }
[ -f "$BIN" ] || { echo "SKIP vfork-execfail : binary not built (make -C tests/binaries)"; exit 2; }
[ -f /lib64/ld-linux-x86-64.so.2 ] && [ -f /lib/x86_64-linux-gnu/libc.so.6 ] \
  || { echo "SKIP vfork-execfail : host に ld.so / libc.so.6 が無い (動的リンク binary)"; exit 2; }

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-vforkexec.XXXXXX)}
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/lib64" "$SANDBOX/lib/x86_64-linux-gnu"
: > "$SANDBOX/etc/emulin.cnf"
cp "$BIN" "$SANDBOX/bin/"
cp /lib64/ld-linux-x86-64.so.2     "$SANDBOX/lib64/"
cp /lib/x86_64-linux-gnu/libc.so.6 "$SANDBOX/lib/x86_64-linux-gnu/"

PASS=0; FAIL=0; FAILED=()
ERRLOG=$SANDBOX/.emulin-stderr

run() {  # run <label> <期待 stdout の grep パターン> [env...]
    local label=$1 want=$2; shift 2
    local act rc t0 el
    t0=$(date +%s)
    act=$( cd "$SANDBOX" && env "$@" timeout -s QUIT -k 10 "$VE_TIMEOUT" \
        java -Xmx1g -XX:-UsePerfData -cp "$CLASSES" \
        emulin.Emulin "$SANDBOX" /bin/vfork_execfail_dyn64 /bin/vfork_execfail_dyn64 2>"$ERRLOG" )
    rc=$?; el=$(( $(date +%s) - t0 ))
    # ★ issue #1018: 値を比べる前に、guest が最後まで走ったかを言う。ここで黙ると
    #   **停止 (この issue の症状そのもの) が「出力が違う」に化ける**。
    if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then
        echo "FAIL    vfork-$label : guest was killed by timeout after ${VE_TIMEOUT}s (実測 ${el}s)"
        echo "        = 親が resume していない可能性が高い (#1028 の症状そのもの)"
        printf '%s\n' "$act" | sed -n '/^Full thread dump/,$p' \
            | awk '/^"/{hdr=$0; st=""; n=0} /Thread\.State:/{if(st=="")st=$0} /^\tat emulin\./{if(n==0){print hdr; print "   " st} if(n<5){print "   " $0; n++}}' \
            | head -30 | sed 's/^/        /'
        FAIL=$((FAIL+1)); FAILED+=("$label"); return
    fi
    if printf '%s\n' "$act" | grep -qE -- "$want"; then
        echo "PASS    vfork-$label (${el}s)"
        PASS=$((PASS+1))
    else
        echo "FAIL    vfork-$label (rc=$rc、期待 '$want')"
        printf '%s\n' "$act" | tail -5 | sed 's/^/        /'
        tail -3 "$ERRLOG" 2>/dev/null | sed 's/^/        /'
        FAIL=$((FAIL+1)); FAILED+=("$label")
    fi
}

# 1. 通常経路: 子が exec に成功し、親が回収する (実 Linux と同じ出力)
run "exec-ok"   '^spawn rc=0 signaled=0 exit=7$' EMULIN_X=1

# 2. ★ 本題: 「後戻りできない地点」で exec が失敗しても親が resume する。
#    子は畳まれるので signaled=1。**止まらないこと**が要点。
run "exec-fail" '^spawn rc=0 signaled=1 exit=0$' EMULIN_FORCE_EXEC_FAIL=vfork_execfail_dyn64

# 3. 安全網: 親を起こし損ねても上限で畳む (VFORK_WAIT_SEC)。
#    ここでは通常経路なので上限に当たらず、短い上限でも PASS すること
#    (= 安全網が正常系を壊していないこと) を見る。
run "wait-cap-ok" '^spawn rc=0 signaled=0 exit=7$' EMULIN_VFORK_WAIT_SEC=5

echo ""
echo "===== vfork-execfail smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
