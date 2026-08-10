#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/launcher-subcommands.sh
#
#  issue #919: launcher が 2 系統あり、サブコマンドが片方にしか入らない事故を防ぐ。
#
#    dist/launchers/emulin.{bat,sh}      … build-dist.sh が **コピー**する方
#    build-demo-bundle.sh が生成する方   … **配布 zip に入る**方 (release 経路)
#
#  #763 で `setcred` を launchers/ 側にだけ足した結果、**出荷物には setcred が無い**
#  状態で 0.8.0 / 0.8.1 が出てしまった。README/QUICKSTART は `emulin.bat setcred` と
#  案内しているので、ドキュメントどおりの操作が失敗していた。
#
#  ★ dist-smoke.sh は build-dist.sh 経路 (= launchers/ をコピーする方) しか検証して
#    いないため、この穴は構造的に捕まらなかった。「テストしている launcher」と
#    「出荷している launcher」が別物だった。
#
#  ここでは **生成側が launchers/ 側のサブコマンドを必ず包含している**ことだけを
#  検査する。逆向き (生成側にしか無いもの) は許す — demo bundle 固有の機能はあり得るので。
#
#  負のコントロール: BUNDLE_SH=<修正前のファイル> を渡すと FAIL することを確認できる。
#
#  終了コード: 0=PASS / 1=FAIL
# --------------------------------------------------------------------
set -u
HERE=$(cd "$(dirname "$0")" && pwd -P)
ROOT=$(cd "$HERE/../.." && pwd -P)

LAUNCH_BAT=${LAUNCH_BAT:-$ROOT/dist/launchers/emulin.bat}
LAUNCH_SH=${LAUNCH_SH:-$ROOT/dist/launchers/emulin.sh}
BUNDLE_SH=${BUNDLE_SH:-$ROOT/dist/build-demo-bundle.sh}

for f in "$LAUNCH_BAT" "$LAUNCH_SH" "$BUNDLE_SH"; do
    [ -f "$f" ] || { echo "FAIL: not found: $f"; exit 1; }
done

# build-demo-bundle.sh の中の heredoc 本体だけを取り出す
#   (外側スクリプト自身の $1 等を拾わないため。`cat > "$DIST_DIR/<名前>" <<'EOF'` 〜 `EOF`)
extract_heredoc() {   # $1=file $2=生成先ファイル名
    awk -v want="$2" '
        index($0, "$DIST_DIR/" want) && index($0, "<<") { inside=1; next }
        inside && $0 == "EOF" { inside=0 }
        inside { print }
    ' "$1"
}

# .bat のサブコマンド:  if /i "%~1"=="xxx"
subs_bat() { grep -aoE '"%~1"=="[A-Za-z0-9_-]+"' | sed -E 's/.*=="([A-Za-z0-9_-]+)"/\1/' | sort -u; }
# .sh のサブコマンド:   [ "${1:-}" = "xxx" ]
subs_sh()  { grep -aoE '"\$\{1:-\}" = "[A-Za-z0-9_-]+"' | sed -E 's/.*= "([A-Za-z0-9_-]+)"/\1/' | sort -u; }

want_bat=$(subs_bat < "$LAUNCH_BAT")
want_sh=$(subs_sh  < "$LAUNCH_SH")
got_bat=$(extract_heredoc "$BUNDLE_SH" emulin.bat | subs_bat)
got_sh=$(extract_heredoc "$BUNDLE_SH" emulin.sh  | subs_sh)

fail=0
report() {   # $1=種別 $2=期待集合 $3=実際の集合
    local kind=$1 want=$2 got=$3 missing
    missing=$(comm -23 <(echo "$want") <(echo "$got"))
    echo "  $kind: launchers/=[$(echo $want)]  bundle 生成=[$(echo $got)]"
    if [ -n "$missing" ]; then
        echo "  ★ FAIL: 配布 zip の $kind に無いサブコマンド: $(echo $missing)"
        echo "     → build-demo-bundle.sh が生成する launcher にも同じ分岐を足すこと"
        fail=1
    fi
}

echo "===== launcher サブコマンドの一致検査 (issue #919) ====="
report emulin.bat "$want_bat" "$got_bat"
report emulin.sh  "$want_sh"  "$got_sh"

# 「検査が空振りしていない」ことも確かめる (集合が空なら抽出が壊れている)
if [ -z "$want_bat" ] || [ -z "$want_sh" ]; then
    echo "  ★ FAIL: launchers/ 側からサブコマンドを 1 つも抽出できていない (検査自身の不良)"
    fail=1
fi

# run-fast.sh / run-all.sh は "PASS    <名前>" / "FAIL    <名前>" の行を数える
if [ "$fail" = 0 ]; then
    echo "PASS    launcher-subcommands (launchers/ と bundle 生成の一致)"
else
    echo "FAIL    launcher-subcommands (launchers/ と bundle 生成の一致)"
fi
exit $fail
