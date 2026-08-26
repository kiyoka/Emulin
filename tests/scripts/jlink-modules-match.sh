#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/jlink-modules-match.sh
#
#  issue #959: **jlink は 2 箇所にある**。
#    - dist/build-demo-bundle.sh  … 出荷 zip が使う方
#    - dist/build-jre-bundle.sh   … JRE 単体 bundle
#
#  片方だけ直すと「変更したのに出荷物が変わらない」になる。実際に踏んだ:
#  #959 で build-jre-bundle.sh にだけ java.desktop を足して zip をビルドしたところ、
#  zip は **+40KB しか増えず** (java.desktop なら +13MB)、ランチャー画面は開けないままだった。
#
#  ★ #919 (launcher が 2 系統あり出荷側を検証していなかった) と同じ形。
#    「N 個あるのに 1 個しか直していない」を検査で塞ぐ。
#
#  終了コード: 0=PASS / 1=FAIL
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)

modules_of() {
    # `--add-modules a,b,c` の値だけを取り出し、順序に依存しないよう並べ替える
    grep -ah -- '--add-modules' "$1" \
      | sed 's/.*--add-modules[ =]*//' | awk '{print $1}' \
      | tr ',' '\n' | grep -a . | sort -u | tr '\n' ',' | sed 's/,$//'
}

A=$(modules_of "$PROJECT/dist/build-demo-bundle.sh")
B=$(modules_of "$PROJECT/dist/build-jre-bundle.sh")

echo "===== jlink module set の一致検査 (issue #959) ====="
echo "  build-demo-bundle.sh (出荷 zip): $A"
echo "  build-jre-bundle.sh  (JRE 単体): $B"

if [ -z "$A" ] || [ -z "$B" ]; then
    echo "FAIL    jlink-modules-match (--add-modules を取り出せない: A='$A' B='$B')"
    exit 1
fi
if [ "$A" != "$B" ]; then
    echo "FAIL    jlink-modules-match (2 系統の module set が食い違っている)"
    echo "        出荷 zip が使うのは build-demo-bundle.sh の方。両方を揃えること。"
    exit 1
fi
# ランチャー画面 (#948) が開ける構成か
case ",$A," in
    *,java.desktop,*) ;;
    *) echo "FAIL    jlink-modules-match (java.desktop が無い: ランチャー画面 #948 が開けない)"
       exit 1 ;;
esac
echo "PASS    jlink-modules-match (2 系統一致 / java.desktop あり)"
exit 0
