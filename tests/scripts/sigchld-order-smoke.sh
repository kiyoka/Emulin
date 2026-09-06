#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/sigchld-order-smoke.sh
#
#  issue #962: **子の終了が観測できるようになる前に SIGCHLD を積む**ことを検査する。
#
#  実害: 並列実行時に sys_sigchld64 が 8-10% で落ちていた。wait4 は status を
#  正しく返すのに **SIGCHLD ハンドラが 1 度も呼ばれない**。原因は set_exit_flag() の
#  順序で、(1) exit_flag=true (2) 待ち手を起こす (3) 親へ SIGCHLD、の順だったため
#  「子の終了は見えるのに signal はまだ無い」窓があった。実 Linux ではゾンビ化と
#  SIGCHLD の生成は不可分で、この状態は存在しない。
#
#  ★ **8% の間欠 FAIL のままでは回帰を検出できない** (「たまに赤い」で片付く)。
#    EMULIN_FORCE_CHILD_EXIT_WINDOW=1 で窓を人為的に広げ、**順序が戻ったら 100%
#    落ちる**形にする。実測: 旧順序 10/10 失敗 / 修正後 0/10。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
BIN=$ROOT/binaries/bin/sys_sigchld64
RUNS=${SIGCHLD_ORDER_RUNS:-5}

if [ ! -f "$CLASSES/emulin/Emulin.class" ] || [ ! -f "$BIN" ]; then
    echo "SKIP sigchld-order-smoke : not built"
    exit 2
fi

SB=$(mktemp -d -t emulin-sigord.XXXXXX)
trap 'rm -rf "$SB"' EXIT
cp -a "$ROOT/sandbox/." "$SB/" 2>/dev/null
cp "$BIN" "$SB/"

fail=0
for i in $(seq 1 "$RUNS"); do
    OUT=$( cd "$SB" && EMULIN_FORCE_CHILD_EXIT_WINDOW=1 \
           java -Xmx1g -XX:-DontCompileHugeMethods -cp "$CLASSES" \
                emulin.Emulin "$SB" /sys_sigchld64 2>/dev/null )
    if ! printf '%s' "$OUT" | grep -q '^handler'; then
        fail=$(( fail + 1 ))
        [ "$fail" = 1 ] && printf '%s\n' "$OUT" | sed 's/^/    /'
    fi
done

if [ "$fail" = 0 ]; then
    echo "PASS    sigchld-order-smoke (子の終了が見える前に SIGCHLD を積む #962)"
    exit 0
fi
echo "FAIL    sigchld-order-smoke: $fail / $RUNS で SIGCHLD ハンドラが呼ばれなかった (#962)"
exit 1
