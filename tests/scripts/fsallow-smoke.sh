#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/fsallow-smoke.sh
#
#  issue #1046: ランチャーで設定した host パス allowlist (#732) が
#  **guest を起こすすべての経路に渡る**ことを検査する。
#
#  ★ **起動口は 2 系統ある** (#919 / #963 / #985 と同じ形):
#      - GuestLaunch.builder(...)         … X 端末 / sshd / apt 等のジョブ
#      - LauncherApp.terminalBuilder(...) … Open terminal (emulin.bat 経由で
#        GuestLaunch を通らない)
#    負のコントロールで確認済み: GuestLaunch 側を外すと 5 件、terminalBuilder 側を
#    外すと 1 件が赤くなる。**片方だけ直すと、そこだけ無制限の guest が起きる。**
#
#  ★ 実体は FsAllowSmoke (Java)。保存先が user.home 依存なので temp に差し替えて走る。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/FsAllowSmoke.class" ]; then
    echo "SKIP fsallow-smoke : not built"
    exit 2
fi

out=$( java -Xmx512m -cp "$CLASSES" emulin.FsAllowSmoke 2>&1 )
rc=$?
echo "$out"

if [ "$rc" = 0 ]; then
    echo "PASS    fsallow-smoke (ランチャーの host パス allowlist #1046)"
    exit 0
fi
echo "FAIL    fsallow-smoke: rc=$rc"
exit 1
