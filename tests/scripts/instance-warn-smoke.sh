#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/instance-warn-smoke.sh
#
#  issue #955: 「同じ rootfs を使う別インスタンス」の検出を検証する。
#
#  守る実害 (2026-08-25 に実機で踏んだ): 稼働中の rootfs にもう 1 つ Emulin を
#  起動すると、guest の credential ファイルが別の placeholder で書き直され、
#  **先に動いていた claude が黙って認証切れになる**。原因は画面に何も出ないので、
#  利用者からは「何もしていないのに Login expired」に見える。
#
#  ★ この検査で肝は 2 つ:
#    - **canonical 比較**。実害は symlink / junction 越しに同じ rootfs を掴んだ形
#      だった。生の文字列で比べる実装は「別物」と判断して**検出したい唯一の場面で黙る**
#    - **違う rootfs では警告しない**。これが無いと「常に警告する実装」でも緑になる
#
#  guest もネットワークも要らない (純 Java)。利用者の ~/.emulin/instances は触らない。
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/InstanceWarnSmoke.class" ]; then
    echo "SKIP instance-warn-smoke : not built ($CLASSES/emulin/InstanceWarnSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

OUT=$(java -Xmx1g -cp "$CLASSES" emulin.InstanceWarnSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'InstanceWarn smoke OK'; then
    echo "PASS    instance-warn-smoke (rootfs 共有の検出 #955)"
    exit 0
fi
echo "FAIL    instance-warn-smoke (exit=$RC)"
exit 1
