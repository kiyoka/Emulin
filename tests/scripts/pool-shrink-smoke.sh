#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/pool-shrink-smoke.sh
#
#  issue #723: 32GB 窓ひっ迫 (issue #379) で fork 子の guest RAM pool が親より
#  小さく確保されたとき、NativeMemoryBackend の DATA_BASE (page table 領域と
#  data 領域の境界、pool サイズから算出) が親と食い違う問題の回帰。
#
#  子は親の物理レイアウト (page table / data ページの物理 offset) を verbatim copy
#  するので DATA_BASE は「その pool のレイアウト」の一部であり、必ず親から継承する
#  必要がある。食い違うと、子が孫を fork する際に duplicate() が「自分の (小さい)
#  DATA_BASE」から data を copy し、WHP lazy commit (#304) の未 commit ギャップ
#  [ptNext, 親 DATA_BASE) を読んで Windows で EXCEPTION_ACCESS_VIOLATION になる。
#
#  emulin.PoolShrinkSmoke (純 Java、KVM/WHP 不要) を起動する。
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/PoolShrinkSmoke.class" ]; then
    echo "SKIP pool-shrink : classes not built ($CLASSES)"
    exit 2
fi

OUT=$( timeout 60 java -Xmx2g -XX:-UsePerfData --enable-native-access=ALL-UNNAMED \
         -cp "$CLASSES" emulin.PoolShrinkSmoke 2>&1 )
RC=$?
echo "$OUT"

if [ "$RC" = 0 ]; then
    echo "PASS    pool-shrink-smoke (fork 子 pool 縮小時の DATA_BASE 継承、issue #723)"
    exit 0
fi
echo "FAIL    pool-shrink-smoke (rc=$RC)"
exit 1
