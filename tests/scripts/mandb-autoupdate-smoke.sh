#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/mandb-autoupdate-smoke.sh
#
#  issue #1031: 出荷 rootfs で **man データベースの自動再構築を止めてある**ことの検査。
#
#  ★ 実害 (2026-09-11 実機): `apt install -y xterm x11-apps` が **40 分以上**終わらなかった。
#    x11-apps が man-db を Depends で引き、man-db の postinst が index.db 不在を見て
#    `mandb -cq` (man DB の全再構築) を走らせる。man ページ 3,661 本を、エミュレートされた
#    I/O と Windows のファイルシステム (#495) で舐めるので、実 Linux の数十秒が 40 分超になる。
#    ★ dpkg は設定中の SIGINT を無視するので **Ctrl-C でも止められない**。
#
#  ★ 検査は **dist/mandb-autoupdate-off.sh を必ず通す**。検査側が同じ編集を自前で書くと、
#    設定側が元に戻っても緑のまま通る (#919 で踏んだ「2 系統」の型)。
#
#  guest もネットワークも要らない (ファイル操作だけ)。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
APPLY=$PROJECT/dist/mandb-autoupdate-off.sh

[ -x "$APPLY" ] || { echo "SKIP mandb-autoupdate : dist/mandb-autoupdate-off.sh が無い"; exit 2; }

PASS=0; FAIL=0; FAILED=()
# ★ 出力は **"PASS    " / "FAIL    " (空白 4 個)** で始めること。run-all / run-fast の集計は
#   この形しか数えない。最初 "  ok " で書いたら、**走っているのに PASS 件数が 1 も増えなかった**
#   (登録しても数えられない = #1015 で踏んだ穴と同じ形。件数の増加で気付いた)。
ck() { # ck <rc> <slug> <説明>
  if [ "$1" = 0 ]; then printf 'PASS    mandb-%s (%s)\n' "$2" "$3"; PASS=$((PASS+1));
  else printf 'FAIL    mandb-%s : %s\n' "$2" "$3"; FAIL=$((FAIL+1)); FAILED+=("mandb-$2"); fi; }

WORK=$(mktemp -d -t emulin-mandb.XXXXXX)
trap 'rm -rf "$WORK" 2>/dev/null || true' EXIT

# --- (1) debconf の DB が無い rootfs (man-db 未導入 = 出荷 base の形) ----------
RF1=$WORK/rf1; mkdir -p "$RF1"
"$APPLY" "$RF1" > /dev/null
D1=$RF1/var/cache/debconf/config.dat
grep -qa '^Name: man-db/auto-update$' "$D1" 2>/dev/null; ck $? new-db "config.dat が無くても stanza を作る"
awk 'BEGIN{RS=""} /(^|\n)Name: man-db\/auto-update(\n|$)/ && /(^|\n)Value: false(\n|$)/ {f=1} END{exit !f}' "$D1"
ck $? value-false "man-db/auto-update = false"

# --- (2) 既に stanza があり Value が無い rootfs (実物と同じ形) ----------------
RF2=$WORK/rf2; mkdir -p "$RF2/var/cache/debconf" "$RF2/var/lib/man-db"
cat > "$RF2/var/cache/debconf/config.dat" <<'EOF'
Name: fontconfig/hinting_style
Template: fontconfig/hinting_style
Value: hintslight
Owners: fontconfig-config

Name: man-db/auto-update
Template: man-db/auto-update
Owners: man-db

Name: man-db/install-setuid
Template: man-db/install-setuid
Value: false
Owners: man-db
EOF
touch "$RF2/var/lib/man-db/auto-update"
"$APPLY" "$RF2" > /dev/null
D2=$RF2/var/cache/debconf/config.dat
awk 'BEGIN{RS=""} /(^|\n)Name: man-db\/auto-update(\n|$)/ && /(^|\n)Value: false(\n|$)/ {f=1} END{exit !f}' "$D2"
ck $? existing-stanza "既存 stanza に Value: false を入れる"
[ ! -e "$RF2/var/lib/man-db/auto-update" ]
ck $? flag-removed "フラグファイル /var/lib/man-db/auto-update を消す (トリガ側はこれだけを見る)"
# ★ 他の stanza を壊さないこと。debconf の DB を壊すと影響が広い。
grep -qa 'hintslight' "$D2"; ck $? others-intact "無関係の stanza (fontconfig) が残る"
[ "$(grep -ac '^Name:' "$D2")" = 3 ]; ck $? stanza-count "stanza の数が変わらない (3 件)"
[ "$(awk 'BEGIN{RS="";n=0} /(^|\n)Name: man-db\/auto-update(\n|$)/{n++} END{print n}' "$D2")" = 1 ]
ck $? no-dup "man-db/auto-update の stanza が重複しない"

# --- (3) 冪等性 (base と sandbox の両方から呼ぶので 2 回通る) ------------------
"$APPLY" "$RF2" > /dev/null
[ "$(awk 'BEGIN{RS="";n=0} /(^|\n)Name: man-db\/auto-update(\n|$)/{n++} END{print n}' "$D2")" = 1 ]
ck $? idempotent "2 回適用しても stanza が増えない (冪等)"
[ "$(grep -ac '^Value: false$' "$D2")" = 2 ]
ck $? idempotent-value "2 回適用しても Value 行が増えない (man-db の 2 項目で 2 行)"

echo ""
echo "===== mandb-autoupdate smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then echo "failures: ${FAILED[*]}"; exit 1; fi
exit 0
