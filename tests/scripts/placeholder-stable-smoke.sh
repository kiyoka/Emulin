#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/placeholder-stable-smoke.sh
#
#  issue #955: placeholder が **rootfs ごとに固定**されることを検査する。
#
#  実害: placeholder を起動ごとに作り直していたため、同じ rootfs で 2 つ目の
#  Emulin が起動すると guest の credential ファイルが新しい値で上書きされ、
#  **先に動いていたセッション**の MITM が自分の知らない値を受け取って素通し ->
#  401 -> claude が "Login expired" になった。★ **壊れるのは操作した側ではなく
#  動いていた側**で、警告も出ないため利用者は原因に辿り着けない。実運用で繰り返し踏んだ。
#
#  ★ 「固定されているか」だけでなく **「rootfs が違えば違う値か」** も見る。
#    全部同じにしてしまうと、前者の検査は通るのに分離が壊れる。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/PlaceholderStableSmoke.class" ]; then
    echo "SKIP placeholder-stable-smoke : not built"
    exit 2
fi

OUT=$(java -cp "$CLASSES" emulin.PlaceholderStableSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'Placeholder stable smoke OK'; then
    echo "PASS    placeholder-stable-smoke (placeholder が rootfs ごとに固定される #955)"
    exit 0
fi
echo "FAIL    placeholder-stable-smoke (exit=$RC)"
exit 1
