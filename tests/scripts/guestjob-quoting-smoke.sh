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

# ★ native pool の検査は **env の 2 状態の両方**で走らせないと意味がない。
#   - 設定あり: 「host に値があっても install job では外れる / sshd では 1024 になる」
#   - 未設定  : 「Open terminal は 1024 を入れる」(issue #985)
#   片方しか走らせないと、もう片方の分岐は一度も実行されないまま緑になる。
run_case() {   # $1=見出し  $2... = env 指定つきの java 起動
    local label=$1; shift
    echo "--- $label ---"
    OUT=$("$@" </dev/null 2>&1); RC=$?
    printf '%s\n' "$OUT" | sed 's/^/  /'
    if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'GuestJob smoke OK'; then return 0; fi
    echo "FAIL    guestjob-quoting-smoke [$label] (exit=$RC)"
    return 1
}

RC_ALL=0
run_case "EMULIN_NATIVE_POOL_MB=4096 (host が明示)" \
    env EMULIN_NATIVE_POOL_MB=4096 java -cp "$CLASSES" emulin.GuestJobSmoke || RC_ALL=1
run_case "EMULIN_NATIVE_POOL_MB 未設定" \
    env -u EMULIN_NATIVE_POOL_MB java -cp "$CLASSES" emulin.GuestJobSmoke || RC_ALL=1

if [ "$RC_ALL" = 0 ]; then
    echo "PASS    guestjob-quoting-smoke (引用符が壊れない転送 #948 / pool の扱い #985)"
    exit 0
fi
exit 1
