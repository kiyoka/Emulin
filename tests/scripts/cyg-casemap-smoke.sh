#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/cyg-casemap-smoke.sh   (issue #349)
#
#  Windows NTFS は既定で case-insensitive のため、Linux package が持つ
#  大小文字違いの同名 file (manpages-dev の regular `_exit.2.gz` と、それを
#  指す symlink `_Exit.2.gz`) が同一 dir に共存できず dpkg unpack が失敗する。
#  emulin の WinCaseMap は衝突した名前だけを可逆 encode (ASCII letter →
#  U+F000+byte の private-use area) して host 上で別実体にし、guest には
#  元名で見せる。regular の content は壊さない。
#
#  本 smoke は CygSymlink モード (EMULIN_FORCE_CYGWIN_SYMLINK=1) で:
#    1. regular file + 大小文字違い symlink ×2 が共存し、readlink / follow-read
#       / getdents が全て正しいこと
#    2. dpkg と同じ「.dpkg-new に content 書込 → symlink → rename」シーケンスで
#       regular の content が保持され、symlink も別実体になること
#  を検証する。Windows では実 NTFS の case 衝突を、Linux では encode/decode/
#  seen-redirect ロジック自体を検証する (case-sensitive fs でも透過に動く)。
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

if [ ! -f "$CLASSDIR/emulin/Emulin.class" ]; then
    echo "SKIP cyg-casemap-smoke : Emulin not built"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP cyg-casemap-smoke : host busybox not found at $HOST_BB"
    exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-cygcm.XXXXXX)}
CLEANUP=0
if [ ! -d "$SANDBOX/bin" ]; then CLEANUP=1; fi
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# 全シナリオを 1 つの sh script にまとめ JVM 起動を 1 回に抑える。
# multi-process pipe (ls|sort|tr) は一部 CI/WSL 環境で fork flaky なので避け、glob で列挙する。
SCRIPT='
mkdir -p /tmp/cs /tmp/cd
echo CASE-CONTENT > /tmp/cs/_exit.2.gz
ln -s _exit.2.gz /tmp/cs/_Exit.2.gz
echo RENAMED-REG > /tmp/cd/_exit.2.gz.dpkg-new
ln -s _exit.2.gz /tmp/cd/_Exit.2.gz.dpkg-new
mv /tmp/cd/_exit.2.gz.dpkg-new /tmp/cd/_exit.2.gz
mv /tmp/cd/_Exit.2.gz.dpkg-new /tmp/cd/_Exit.2.gz
echo "T1=$(cat /tmp/cs/_exit.2.gz)"
echo "T2=$(readlink /tmp/cs/_Exit.2.gz)"
echo "T3=$(cat /tmp/cs/_Exit.2.gz)"
echo "T5=$(cat /tmp/cd/_exit.2.gz)"
echo "T6=$(readlink /tmp/cd/_Exit.2.gz)"
ln -s lwp-request /tmp/h394/HEAD
echo "T7=$(cat /tmp/h394/head)"
echo "T8=$(readlink /tmp/h394/HEAD)"
echo "T9=$(cd /tmp/h394 && echo *)"
cd /tmp/cs
echo "T4=$(echo *)"
'
# issue #394: 起動前 (launcher の Windows tar 相当) に plain head を host へ直書きし、emulin の
#   seen に居ない状態を再現する。後で emulin 内で HEAD symlink を作ると、primeDir が head を
#   case-fold slot 所有者に先取り → HEAD は encode され、coreutils head は上書きされない。
mkdir -p "$SANDBOX/tmp/h394"
printf 'COREUTILS-HEAD\n' > "$SANDBOX/tmp/h394/head"

OUT=$( cd "$SANDBOX"; EMULIN_FORCE_CYGWIN_SYMLINK=1 timeout 90 \
    java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$SCRIPT" 2>/dev/null )

get() { echo "$OUT" | grep -oE "^$1=.*" | head -1 | sed "s/^$1=//"; }

PASS=0
FAIL=0
FAILED=()
check() {
    local label=$1 got=$2 want=$3
    if [ "$got" = "$want" ]; then
        echo "PASS    cyg-$label"; PASS=$((PASS+1))
    else
        echo "FAIL    cyg-$label (got='$got' want='$want')"
        FAIL=$((FAIL+1)); FAILED+=("cyg-$label")
    fi
}

check "casemap-regular-content"  "$(get T1)" "CASE-CONTENT"
check "casemap-readlink"         "$(get T2)" "_exit.2.gz"
check "casemap-read-via-symlink" "$(get T3)" "CASE-CONTENT"
check "casemap-rename-content"   "$(get T5)" "RENAMED-REG"
check "casemap-rename-readlink"  "$(get T6)" "_exit.2.gz"
check "casemap-getdents-both"    "$(get T4)" "_Exit.2.gz _exit.2.gz"

# issue #394: 起動前 plain head + emulin の HEAD symlink で head が壊れず両方共存すること。
#   T7 は Windows(NTFS) での核心 (fix 前は HEAD が head を上書きし lwp-request 内容になる)。
check "casemap-394-head-content"  "$(get T7)" "COREUTILS-HEAD"
check "casemap-394-HEAD-readlink" "$(get T8)" "lwp-request"
check "casemap-394-getdents-both" "$(get T9)" "HEAD head"

# host 側 discriminator (case-sensitive fs のみ): primeDir が効くと HEAD は encode され、host 上に
#   plain "HEAD" は作られない (head の case-fold slot を奪わない)。fix 前は plain HEAD が作られる。
#   case-insensitive host (Windows/Mac) では T7/T9 が核心なのでここは probe で SKIP。
probe="$SANDBOX/tmp/.cs394"; mkdir -p "$probe"; : > "$probe/a"
if [ -e "$probe/A" ]; then
    echo "SKIP    cyg-casemap-394-encoded (host fs case-insensitive)"
elif [ ! -e "$SANDBOX/tmp/h394/HEAD" ] && [ -s "$SANDBOX/tmp/h394/head" ]; then
    echo "PASS    cyg-casemap-394-encoded"; PASS=$((PASS+1))
else
    echo "FAIL    cyg-casemap-394-encoded (plain HEAD not encoded / head clobbered)"
    FAIL=$((FAIL+1)); FAILED+=("cyg-casemap-394-encoded")
fi
rm -rf "$probe"

echo
echo "===== cyg-casemap smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ $FAIL -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
