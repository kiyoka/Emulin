#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/whp-gpabacking-smoke.sh
#
#  issue #304 (WHP lazy commit): WhpGpaBacking の chunk index 計算
#  (commit+map すべき chunk の算出。WHP API 非依存の純関数) を検証する。
#
#  実際の VirtualAlloc(MEM_COMMIT)/WHvMapGpaRange は Windows + Hyper-V でしか
#  動かないが、chunk 算出ロジック (= lazy commit の正しさの核) は Linux で
#  検証できる。emulin.WhpGpaBackingSmoke (純 Java、KVM/WHP 不要) を起動する。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/WhpGpaBackingSmoke.class" ]; then
    echo "SKIP whp-gpabacking-smoke : not built ($CLASSES/emulin/WhpGpaBackingSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

OUT=$(java -Xmx2g -cp "$CLASSES" emulin.WhpGpaBackingSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'WhpGpaBacking smoke OK'; then
    echo "PASS    whp-gpabacking-smoke (chunk ロジック)"
    exit 0
fi
echo "FAIL    whp-gpabacking-smoke (exit=$RC)"
exit 1
