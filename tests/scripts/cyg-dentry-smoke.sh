#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/cyg-dentry-smoke.sh
#
#  issue #495: namei dentry cache (CygSymlink.readCached) の invalidation 検証。
#
#  cache の危険な staleness は「symlink → 消滅/通常file化」「非symlink →
#  symlink化」「directory rename 配下の phantom 追従」。全ステップを TTL
#  (既定 2000ms) 内で連続実行するため、PASS = invalidate (CygSymlink.write /
#  FileAccess.unlink / FileAccess.rename) が正しく効いている証明になる。
#
#  検証項目 (全て 1 回の emulin 起動で実行):
#    1. symlink → rm → 通常 file 再作成 (stale なら旧 target を返す)
#    2. symlink retarget (rm + ln -s 別 target)
#    3. ln -sf 上書き
#    4. 通常 file → rm → symlink 化 (NOT_LINK sentinel の stale)
#    5. directory rename 後、旧 path に同名の通常 file (phantom 追従検出)
#    6. rename 先の新 path で symlink が生きている
#    7. 中間 component の symlink 差し替え
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
    echo "SKIP cyg-dentry-smoke : Emulin not built"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP cyg-dentry-smoke : host busybox not found at $HOST_BB"
    exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-cygdent.XXXXXX)}
CLEANUP=0
if [ ! -d "$SANDBOX/bin" ]; then CLEANUP=1; fi
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

SCRIPT='
echo AAA > /tmp/a
echo BBB > /tmp/b
ln -s /tmp/a /tmp/s1
cat /tmp/s1 > /dev/null
rm /tmp/s1
echo PLAIN > /tmp/s1
echo "T1=$(cat /tmp/s1)"
ln -s /tmp/a /tmp/s2
cat /tmp/s2 > /dev/null
rm /tmp/s2
ln -s /tmp/b /tmp/s2
echo "T2=$(cat /tmp/s2)"
ln -s /tmp/a /tmp/s3
cat /tmp/s3 > /dev/null
ln -sf /tmp/b /tmp/s3
echo "T3=$(cat /tmp/s3)"
echo PLAIN2 > /tmp/s4
cat /tmp/s4 > /dev/null
rm /tmp/s4
ln -s /tmp/a /tmp/s4
echo "T4=$(cat /tmp/s4)"
mkdir /tmp/d1
ln -s /tmp/a /tmp/d1/lk
cat /tmp/d1/lk > /dev/null
mv /tmp/d1 /tmp/d2
mkdir /tmp/d1
echo FRESH > /tmp/d1/lk
echo "T5=$(cat /tmp/d1/lk)"
echo "T6=$(cat /tmp/d2/lk)"
mkdir /tmp/rd1 /tmp/rd2
echo R1 > /tmp/rd1/f
echo R2 > /tmp/rd2/f
ln -s /tmp/rd1 /tmp/mid
cat /tmp/mid/f > /dev/null
rm /tmp/mid
ln -s /tmp/rd2 /tmp/mid
echo "T7=$(cat /tmp/mid/f)"
'
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
        echo "PASS    dentry-$label"; PASS=$((PASS+1))
    else
        echo "FAIL    dentry-$label (got='$got' want='$want')"
        FAIL=$((FAIL+1)); FAILED+=("dentry-$label")
    fi
}

check "stale-symlink-to-file"  "$(get T1)" "PLAIN"
check "stale-retarget"         "$(get T2)" "BBB"
check "stale-ln-sf"            "$(get T3)" "BBB"
check "stale-file-to-symlink"  "$(get T4)" "AAA"
check "dir-rename-no-phantom"  "$(get T5)" "FRESH"
check "dir-rename-new-path"    "$(get T6)" "AAA"
check "mid-component-swap"     "$(get T7)" "R2"

echo ""
echo "===== cyg-dentry smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
[ $FAIL -gt 0 ] && exit 1
exit 0
