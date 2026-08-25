#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/token-rotate-smoke.sh
#
#  issue #954: OAuth refresh の **in-flight 直列化** を検証する。
#
#  守る実害: guest 内で複数の client (ssh 2 本の claude / Remote Control の
#  bridge と worker) が**同時に** refresh を投げると、両方が同じ refresh token を
#  上流へ提示し、後着が invalid_grant で弾かれる。弾かれた client は
#  credential を捨てるので、Emulin を再起動するまで Login expired のままになる
#  (#944 の実害。2026-08-25 に実機で踏んだ)。
#
#  ★ このテストは**負のコントロールを内蔵**している: 直列化を切った状態
#    (= #943 までの実装) で「上流へ 2 本以上行く」ことを先に測ってから、
#    直列化ありで 1 本になることを確認する。壊れた実装を通さない。
#
#  ネットワークも guest も要らない (純 Java)。
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/TokenRotateSmoke.class" ]; then
    echo "SKIP token-rotate-smoke : not built ($CLASSES/emulin/TokenRotateSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

OUT=$(java -Xmx1g -cp "$CLASSES" emulin.TokenRotateSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'TokenRotate smoke OK'; then
    echo "PASS    token-rotate-smoke (refresh の直列化 #954)"
    exit 0
fi
echo "FAIL    token-rotate-smoke (exit=$RC)"
exit 1
