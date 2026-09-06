#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/flake-rate.sh — テストの**再現率を測る**
#
#  使い方:
#    tests/scripts/flake-rate.sh <test-name> [RUNS] [PAR]
#      RUNS … 総実行回数 (既定 20)
#      PAR  … 同時に走らせる本数 (既定 1 = 逐次)
#
#  例:
#    flake-rate.sh sys_sigchld64 20 1    # 単独・逐次
#    flake-rate.sh sys_sigchld64 20 4    # 並列 4 本
#
#  ★ なぜ要るか (issue #962 / 非公開 #133):
#    間欠 FAIL を「flake」と呼んで片付けると、**本物のバグを取り逃す**。実際 #817 の
#    clone_cleartid は「flake 扱い」だったが本物だった。判断ではなく **測定** の対象に
#    する。「単独では出ないが並列で 10%」のような形は、数えて初めて言える。
#
#  ★ 各実行は **別々の sandbox** を使う (SANDBOX_DIR)。同じ sandbox を並列で叩くと
#    テスト同士が /tmp や rootfs を取り合い、**測りたい race とは別の理由**で落ちる。
#
#  終了コード: 常に 0 (これは測定であって合否ではない)
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")" && pwd -P)
NAME=${1:?usage: flake-rate.sh <test-name> [RUNS] [PAR]}
RUNS=${2:-20}
PAR=${3:-1}

TMP=$(mktemp -d -t emulin-flake.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

one() {   # one <index>
    local i=$1
    local sb="$TMP/sb$i"
    mkdir -p "$sb"
    cp -a "$ROOT/../sandbox/." "$sb/" 2>/dev/null
    if SANDBOX_DIR="$sb" bash "$ROOT/run-test.sh" "$NAME" > "$TMP/out$i" 2>&1; then
        echo P > "$TMP/r$i"
    else
        echo F > "$TMP/r$i"
    fi
    rm -rf "$sb"
}

echo "=== flake-rate: $NAME  (runs=$RUNS parallel=$PAR) ==="
i=0
while [ "$i" -lt "$RUNS" ]; do
    n=0
    while [ "$n" -lt "$PAR" ] && [ "$i" -lt "$RUNS" ]; do
        one "$i" &
        i=$(( i + 1 )); n=$(( n + 1 ))
    done
    wait
done

fail=0
for f in "$TMP"/r*; do [ "$(cat "$f")" = F ] && fail=$(( fail + 1 )); done
pct=$(( fail * 100 / RUNS ))
echo "  失敗 $fail / $RUNS  ($pct%)"
if [ "$fail" -gt 0 ]; then
    echo "--- 最初の失敗の出力 ---"
    for f in "$TMP"/r*; do
        if [ "$(cat "$f")" = F ]; then sed 's/^/    /' "${f/\/r//out}" | head -20; break; fi
    done
fi
exit 0
