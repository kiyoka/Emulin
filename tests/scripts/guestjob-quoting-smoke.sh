#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/guestjob-quoting-smoke.sh
#
#  issue #948: インストーラが guest へ渡すコマンドが **引用符で壊れない**ことを検査する。
#
#  実害 (2026-08-26): ランチャーの Codex 導入が
#    printf 'sandbox_mode = "danger-full-access"\n' > ~/.codex/config.toml
#  を投げたところ、guest には**二重引用符が消えて**届き、不正な TOML が書かれた。
#  さらに当時の判定は `grep -q danger-full-access` だったので、**壊れたファイルを
#  「導入済み」と判定**し、二度と直らなかった。
#
#  ★ この事故は **Windows (cmd.exe / .bat) でしか起きない**。`set "RUNCMD=%~1"` は
#    外側の引用符しか外せず、中に `"` があると引用が切れる。Linux の bash 経路では
#    再現しないので、**guest を起動するテストでは捕まらない**。
#    だから「command line に何が載るか」という**構造**を検査する。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/GuestJobSmoke.class" ]; then
    echo "SKIP guestjob-quoting-smoke : not built"
    exit 2
fi

# ★ EMULIN_NATIVE_POOL_MB を**わざと設定して**走らせる。
#   「host の env にあっても install job では外れる / sshd では 1024 に上書きされる」
#   ことがこの検査の肝で、未設定のまま走らせると**何も確かめていない**ことになる。
OUT=$(EMULIN_NATIVE_POOL_MB=4096 java -cp "$CLASSES" emulin.GuestJobSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'GuestJob smoke OK'; then
    echo "PASS    guestjob-quoting-smoke (引用符が壊れない転送 #948)"
    exit 0
fi
echo "FAIL    guestjob-quoting-smoke (exit=$RC)"
exit 1
