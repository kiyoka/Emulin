#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/cyg-caseencode-smoke.sh   (issue #369)
#
#  build 時 pre-encode (dist/cyg-caseencode.sh) + emulin の read 経路 lazy scan の hermetic 検証。
#
#  #369: Windows bundle の launcher は Windows tar で rootfs を NTFS へ展開するため、大小文字違いで同名の
#  file (kernel header xt_CONNMARK.h / xt_connmark.h 等) の片方が上書きで失われる。WinCaseMap は emulin
#  実行時に効くが launcher の tar 展開は emulin 起動前なので効かない。対策: build 時に衝突 leaf を PUA で
#  pre-encode → Windows tar は衝突なく展開 → emulin が read 経路 lazy scan (marker .emulin-casemap で有効化)
#  で encode 名を元名に decode して見せる (lossless)。
#
#  本 smoke は (Linux でも EMULIN_FORCE_CYGWIN_SYMLINK=1 で走る):
#    1. 衝突する 2 file を host に置き dist/cyg-caseencode.sh で pre-encode (1 件 plain / 1 件 PUA + marker)
#    2. emulin で「getdents/create を経ない直接 open」で両 file を元名で cat → 内容が正しいこと
#       (= launcher tar 展開後の pure-read プロセスでの dpkg -L/-V / gcc header open / man 相当)
#    3. getdents (echo *) が両方の元名を見せること
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSDIR=$PROJECT/target/classes
CLASSES=$CLASSDIR
ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.6/asm-9.6.jar"
[ -f "$ASM_JAR" ] && CLASSES="$CLASSES:$ASM_JAR"
HOST_BB=/usr/bin/busybox
ENCODER="$PROJECT/dist/cyg-caseencode.sh"

if [ ! -f "$CLASSDIR/emulin/Emulin.class" ]; then echo "SKIP cyg-caseencode-smoke : Emulin not built"; exit 2; fi
if [ ! -f "$HOST_BB" ]; then echo "SKIP cyg-caseencode-smoke : host busybox not found"; exit 2; fi
if [ ! -f "$ENCODER" ]; then echo "SKIP cyg-caseencode-smoke : $ENCODER not found"; exit 2; fi
command -v python3 >/dev/null 2>&1 || { echo "SKIP cyg-caseencode-smoke : python3 not found"; exit 2; }

SANDBOX=$(mktemp -d -t emulin-caseenc.XXXXXX)
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp/h369"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# 1. 衝突する 2 file を host に直書き (launcher 前の build staging 相当)。Linux は case-sensitive なので両立。
#    sorted 先頭 (xt_CONNMARK.h、'C'<'c') が plain 維持、2 件目 (xt_connmark.h) が PUA encode される。
printf 'UPPER-CONNMARK\n' > "$SANDBOX/tmp/h369/xt_CONNMARK.h"
printf 'lower-connmark\n' > "$SANDBOX/tmp/h369/xt_connmark.h"
# 3-fold 衝突 (FOO.h/Foo.h/foo.h、3 件が同一 case-fold): sorted 先頭 FOO.h が plain、残り 2 件 encode。
#   1 fold-group に 3 件以上あっても全て元名で解決できることを確認する。
printf 'c-FOO\n' > "$SANDBOX/tmp/h369/FOO.h"
printf 'c-Foo\n' > "$SANDBOX/tmp/h369/Foo.h"
printf 'c-foo\n' > "$SANDBOX/tmp/h369/foo.h"
# 非衝突 file: 衝突 file と同居しても encode されず plain のまま読めること (encoder が衝突のみ対象の確認)。
printf 'PLAINTXT\n' > "$SANDBOX/tmp/h369/plain.txt"

# 2. build 時 pre-encode を適用 (= dist/build-demo-bundle.sh が tar 化前に呼ぶのと同じ)。
ENC_OUT=$( bash "$ENCODER" "$SANDBOX" 2>&1 ); ENC_RC=$?
echo "$ENC_OUT"

PASS=0; FAIL=0; FAILED=()
check(){ if [ "$2" = "$3" ]; then echo "PASS    $1"; PASS=$((PASS+1)); else echo "FAIL    $1 : got [$2] want [$3]"; FAIL=$((FAIL+1)); FAILED+=("$1"); fi; }

# 方式C の host 状態: 衝突 leaf は ASCII payload (.emulin-casemap.d/NNNN) へ退避 + manifest (.emulin-casemap)
#   に記録。plain 側は in-place のまま。emulin が初回起動時に payload→PUA 本体を NIO 生成する (bsdtar が PUA
#   名を NTFS に作れない実機問題への対策)。
check "caseenc-encoder-rc"     "$ENC_RC" "0"
[ -f "$SANDBOX/.emulin-casemap" ] && check "caseenc-manifest-created" "yes" "yes" || check "caseenc-manifest-created" "no" "yes"
# plain 側 (xt_CONNMARK.h) は in-place で残る。
[ -f "$SANDBOX/tmp/h369/xt_CONNMARK.h" ] && check "caseenc-plain-kept" "yes" "yes" || check "caseenc-plain-kept" "no" "yes"
# 衝突 3 件 (xt_connmark.h / Foo.h / foo.h) が ASCII payload へ退避されている (bsdtar 展開可な形)。
NPAY=$( ls "$SANDBOX/.emulin-casemap.d/" 2>/dev/null | wc -l | tr -d ' ' )
check "caseenc-payload-staged"  "$NPAY" "3"

# 3. emulin で「直接 open」(事前の getdents/create 無し) で両 file を元名 cat。marker→readScan で lazy scan 解決。
SCRIPT='
echo "T1=$(cat /tmp/h369/xt_connmark.h)"
echo "T2=$(cat /tmp/h369/xt_CONNMARK.h)"
echo "T3=$(cd /tmp/h369 && echo *)"
echo "T4=$(cat /tmp/h369/FOO.h)"
echo "T5=$(cat /tmp/h369/Foo.h)"
echo "T6=$(cat /tmp/h369/foo.h)"
echo "T7=$(cat /tmp/h369/plain.txt)"
'
OUT=$( cd "$SANDBOX"; EMULIN_FORCE_CYGWIN_SYMLINK=1 timeout 90 \
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$SCRIPT" 2>/dev/null )
get(){ printf '%s\n' "$OUT" | sed -n "s/^$1=//p" | head -1; }

# T1 が #369 の核心: emulin が初回起動時に payload→PUA 本体を NIO 生成 (bootstrap) し、その PUA file を
#   元名で直接 open して正しい内容が読めること (bootstrap + lazy scan 解決)。
check "caseenc-369-encoded-read" "$(get T1)" "lower-connmark"
check "caseenc-369-plain-read"   "$(get T2)" "UPPER-CONNMARK"
# getdents は両名が見えること (echo * の glob 順は locale collation 依存なので順序非依存に判定する)。
T3="$(get T3)"
if printf '%s' "$T3" | grep -q "xt_CONNMARK.h" && printf '%s' "$T3" | grep -q "xt_connmark.h"; then
    echo "PASS    caseenc-369-getdents"; PASS=$((PASS+1))
else
    echo "FAIL    caseenc-369-getdents : got [$T3] (両名 xt_CONNMARK.h/xt_connmark.h を期待)"
    FAIL=$((FAIL+1)); FAILED+=("caseenc-369-getdents")
fi
# 3-fold: plain (FOO.h) + encode 2 件 (Foo.h/foo.h) を全て元名で正しく読めること。
check "caseenc-3fold-plain" "$(get T4)" "c-FOO"
check "caseenc-3fold-enc1"  "$(get T5)" "c-Foo"
check "caseenc-3fold-enc2"  "$(get T6)" "c-foo"
# 非衝突 file は encode されず plain のまま読めること。
check "caseenc-noncollide"  "$(get T7)" "PLAINTXT"
# emulin の bootstrap が payload を使い切って片付けたこと (.emulin-casemap.d/ が消えている)。
[ ! -d "$SANDBOX/.emulin-casemap.d" ] && check "caseenc-payload-consumed" "gone" "gone" || check "caseenc-payload-consumed" "left" "gone"

echo
echo "===== cyg-caseencode smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ $FAIL -gt 0 ]; then printf '  failed: %s\n' "${FAILED[@]}"; exit 1; fi
exit 0
