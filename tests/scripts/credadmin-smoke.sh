#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/credadmin-smoke.sh
#
#  issue #968: credential の**状況表示**が嘘をつかないことを検査する。
#
#  ★ ここで守りたいのは 4 つ:
#    1. 取り込み元の **期限を見る**。2026-08-25 に、10 日前に期限切れになっていた
#       `.credentials.json` を黙って取り込めてしまい往復した。`expiresAt` は最初から
#       ファイルに書かれていて、ただ誰も見ていなかった。
#    2. **名前ではなく中身で判定する**。`.credentials.json` という名前の別物がある
#       (#964 で `.pub` という名前の秘密鍵に当たったのと同じ形)。
#    3. **共有ログインを見逃さない**。普段使いの `.claude` を取り込むと refresh token の
#       回転でもう片方のセッションが落ちる (#954 / #970)。
#    4. ★ **値を画面に出さない** (#401)。判定の結果に実トークンが混ざらないこと。
#
#  ★ 本物の ~/.emulin/credentials.json は読まない (一時ディレクトリだけで完結する)。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/CredAdminSmoke.class" ]; then
    echo "SKIP credadmin-smoke : not built"
    exit 2
fi

OUT=$(java -cp "$CLASSES" emulin.CredAdminSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" != 0 ] || ! printf '%s' "$OUT" | grep -q 'CredAdmin smoke OK'; then
    echo "FAIL    credadmin-smoke (exit=$RC)"
    exit 1
fi

# ★ 出力そのものに実トークンらしき文字列が出ていないことを、外側からも見る
#   (テスト内の判定を通り抜けて println されるような書き方をしても、ここで止まる)。
if printf '%s' "$OUT" | grep -qE 'sk-ant-(oat|ort)01-SMOKE'; then
    echo "  FAIL テストの出力に実トークン相当の文字列が出ている (#401)"
    echo "FAIL    credadmin-smoke (token leaked to output)"
    exit 1
fi

echo "PASS    credadmin-smoke (credential の状況表示 #968)"
exit 0
