#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/xdisplay-smoke.sh
#
#  issue #1021: ランチャーの「Open X terminal」の**起動条件**を検査する。
#
#  guest の X アプリを host の X サーバ (VcXsrv 等) に出す経路:
#      guest の X client → TCP 127.0.0.1:(6000+display) → host の X サーバ
#  ssh も VNC も WSL ディストロも要らないので、**zip を展開して叩くだけの Windows
#  利用者に届く**唯一の GUI 経路 (#1011 はディストロ必須で届かず closed になった)。
#
#  ★ 実機でしか見られないもの (窓が本当に出るか) は検査できないが、**起動条件が
#    壊れたこと**は機械で捕まる。特に:
#      - #949 の遮断を開けるのが **X の port だけ** (1 / all にしない)
#      - DISPLAY が **guest の argv に載る** (host env の継承に頼らない)
#      - javaw で起こす (黒い窓を出さない #963/#976)
#
#  guest もネットワークも X サーバも要らない (純 Java)。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/XDisplaySmoke.class" ]; then
    echo "SKIP xdisplay : classes not built ($CLASSES)"; exit 2
fi

# ★ issue #1018: 制限時間は env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
XD_TIMEOUT=${XD_TIMEOUT:-60}

OUT=$( timeout "$XD_TIMEOUT" java -Xmx1g -XX:-UsePerfData -cp "$CLASSES" emulin.XDisplaySmoke 2>&1 )
RC=$?
echo "$OUT"

if [ "$RC" = 0 ]; then
    echo "PASS    xdisplay-smoke (Open X terminal の起動条件、issue #1021)"
    exit 0
fi
if [ "$RC" = 124 ]; then
    echo "FAIL    xdisplay-smoke : ${XD_TIMEOUT}s 以内に終わらなかった (timeout で殺された)"
    exit 1
fi
echo "FAIL    xdisplay-smoke (rc=$RC)"
exit 1
