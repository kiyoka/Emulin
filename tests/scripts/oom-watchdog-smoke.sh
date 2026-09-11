#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/oom-watchdog-smoke.sh
#
#  issue #1026: **内部 OOM のあと guest が前進しなくなったら落とす**見張りの検査。
#
#  ★ 実害: heap を使い切ると busybox の applet 再 exec (`/proc/self/exe`) が
#    OutOfMemoryError → ENOMEM になり、その子が半端に死んだあと
#    **pipe の読み手と wait4 の待ち手が永久に待つ**。検査は 180s の timeout に
#    殺されるだけで、**原因が何も残らなかった** (CI で 3 日に 4 回・手元でも 24 回中 2 回)。
#    ★ 停止は「遅い」より悪い — 遅いなら待てば分かるが、停止は何も残らない。
#
#  ★ 検査するのは **判定** (SyscallAmd64.oomWatchdogVerdict)。System.exit を含む実行側は
#    そのままでは検査できないので、判定を切り出してある (時計を進めたことにして確かめる)。
#  ★ 実物の停止で発火することは別途 実測済み: 2 コアに絞って 6 本並列で cyg-symlink を
#    24 回回すと、従来は 60s の timeout に殺されていたものが rc=125 + 診断で落ちるようになった。
#
#  guest もネットワークも要らない (純 Java)。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

[ -f "$CLASSES/emulin/OomWatchdogSmoke.class" ] || { echo "SKIP oom-watchdog : classes not built"; exit 2; }

# ★ issue #1018: 制限時間は env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
OW_TIMEOUT=${OW_TIMEOUT:-60}

OUT=$( timeout "$OW_TIMEOUT" java -Xmx1g -XX:-UsePerfData -cp "$CLASSES" emulin.OomWatchdogSmoke 2>&1 )
RC=$?
echo "$OUT"

if [ "$RC" = 0 ]; then exit 0; fi
if [ "$RC" = 124 ]; then
    echo "FAIL    oom-watchdog : ${OW_TIMEOUT}s 以内に終わらなかった (timeout で殺された)"
    exit 1
fi
echo "FAIL    oom-watchdog (rc=$RC)"
exit 1
