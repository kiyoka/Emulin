#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/message-lang-check.sh
#
#  issue #969: **利用者に出すメッセージに日本語を混ぜない**ことを検査する。
#
#  実害: ランチャーのログ欄に日本語と英語が混在していた。エミュレータ本体の
#  診断は英語で書かれているのに、後から足した [egress] / [mitm] 等が日本語だったため。
#
#  ★ 検査するのは **メッセージだけ**。コード内のコメントと assert の不変条件は
#    開発者向けなので日本語のままでよい (このリポジトリはコメントが日本語)。
#    ここで全部を弾くと、コメントを書くたびに赤くなって**検査ごと外される**。
#
#  ★ 対象は「1 文の中に日本語が含まれる println 系の呼び出し」。**継続行も見る**
#    (`println( "a"` + 改行 + `+ "日本語" )` の形で実際に書かれている)。
#
#  終了コード: 0=PASS / 1=FAIL
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SRC=$PROJECT/src/main/java/emulin

# println 系の呼び出しを **文単位** (`);` まで) に連結してから日本語を探す。
BAD=$(awk '
  FILENAME ~ /Smoke[0-9]*\.java$/ { next }
  {
    line = $0
    sub(/\/\/.*$/, "", line)                 # 行末コメントは対象外
  }
  buf != "" { buf = buf line; if (line ~ /\);[ \t]*$/) { print FILENAME ":" start ":" buf; buf = "" } next }
  line ~ /(TRACE_OUT\.println|TRACE_OUT_println|System\.err\.println|System\.out\.println)[ \t]*\(/ {
    start = FNR
    if (line ~ /\);[ \t]*$/) { print FILENAME ":" FNR ":" line } else { buf = line }
  }
' "$SRC"/*.java | grep -P '[\x{3040}-\x{30ff}\x{4e00}-\x{9faf}]' || true)

if [ -z "$BAD" ]; then
    echo "PASS    message-lang-check (利用者向けメッセージに日本語が無い #969)"
    exit 0
fi
echo "$BAD" | sed 's/^/  /' | cut -c1-140
echo "FAIL    message-lang-check: 上のメッセージに日本語が混ざっている (#969)"
exit 1
