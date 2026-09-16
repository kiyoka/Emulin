#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/fsallow-smoke.sh
#
#  issue #1046: ランチャーで設定した host パス allowlist (#732) が
#  **guest 側に届き、緩む方向には倒れない**ことを検査する。
#
#  ★ **出どころは `~/.emulin/fs-allow.txt` の 1 本だけ** (env は使わない)。env で渡す形は
#    `Open terminal` が `wt.exe` 経由で別プロセス文脈から起動し直されると落ちて、
#    **そこだけ無制限の guest が起きる**。2026-09-13 に env をやめ、guest 側 (`FsPolicy`)
#    が起動時に自分で読む形にした。したがって「起動口ごとに env を渡せているか」を
#    見る検査はもう無い — **見るのは「別 JVM の FsPolicy が何を読んだか」**。
#
#  ★ **「設定が無い」と「在るのに読めない」を分けているか**も見る (2026-09-16)。
#    読み取り失敗を空リストにすると、設定ファイルが壊れただけで**制限が黙って外れる**。
#
#  ★ 実体は FsAllowSmoke (Java)。保存先が user.home 依存なので temp に差し替えて走る。
#    guest を実際に起こす側の検査は fspolicy-smoke.sh。
#
#  ★ 負のコントロール (実測):
#      - `FsPolicy.ENABLED` から `UNREADABLE` を外す → ここが 4 件 + fspolicy-smoke が 4 件
#      - `FsAllow.save()` の上書き拒否を外す         → ここが 3 件
#      - `describe()` の配線を外す                   → 起動時に policy が表示される検査
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
