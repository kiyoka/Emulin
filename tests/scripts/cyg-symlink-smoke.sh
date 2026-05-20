#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/cyg-symlink-smoke.sh
#
#  issue #68: Cygwin 式 symlink マジックファイルの hermetic 動作確認。
#
#  EMULIN_FORCE_CYGWIN_SYMLINK=1 で symlink を「マジック regular file
#  (!<symlink> cookie)」として作成・読出・stat・追従できるかを検証する。
#  (この mode は通常 Windows host で自動 on になるが、Linux でも force
#   flag でテスト可能。)
#
#  検証項目 (全て 1 回の emulin 起動で実行 = 並列 CI 負荷下の JVM 起動
#  flake を避ける):
#    1. ln -s + cat 追従 (target 内容)
#    2. readlink で target
#    3. ls -l で symlink (l...) + -> target
#    4. chained symlink (l2 -> l1 -> real)
#    5. 中間 component が symlink の dir 追従
#  + host 側でマジックファイル cookie を確認 (別途)
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
    echo "SKIP cyg-symlink-smoke : Emulin not built"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP cyg-symlink-smoke : host busybox not found at $HOST_BB"
    exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-cygln.XXXXXX)}
CLEANUP=0
if [ ! -d "$SANDBOX/bin" ]; then CLEANUP=1; fi
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# 全シナリオを 1 つの sh script にまとめ、各結果を "KEY=value" 形式で出力。
# JVM 起動を 1 回に抑えることで CI 並列負荷下の起動 flake を回避する。
SCRIPT='
echo HELLO-SYMLINK > /tmp/real.txt
ln -s /tmp/real.txt /tmp/lk
echo "T1=$(cat /tmp/lk)"
echo "T2=$(readlink /tmp/lk)"
echo "T3=$(ls -l /tmp/lk | grep -oE "^l|-> /tmp/real.txt" | tr "\n" " " | sed "s/ $//")"
echo CHAIN > /tmp/c0
ln -s /tmp/c0 /tmp/c1
ln -s /tmp/c1 /tmp/c2
echo "T4=$(cat /tmp/c2)"
mkdir -p /tmp/rd
echo INDIR > /tmp/rd/f
ln -s /tmp/rd /tmp/dl
echo "T5=$(cat /tmp/dl/f)"
'
OUT=$( cd "$SANDBOX"; EMULIN_FORCE_CYGWIN_SYMLINK=1 timeout 90 \
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
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

check "create-and-follow" "$(get T1)" "HELLO-SYMLINK"
check "readlink"          "$(get T2)" "/tmp/real.txt"
check "ls-l-symlink"      "$(get T3)" "l -> /tmp/real.txt"
check "chained"           "$(get T4)" "CHAIN"
check "intermediate-dir"  "$(get T5)" "INDIR"

# host 側でマジックファイル cookie を確認 (emulin が作った symlink file)
COOKIE=$(head -c 10 "$SANDBOX/tmp/lk" 2>/dev/null)
check "magicfile-cookie"  "$COOKIE" "!<symlink>"

echo ""
echo "===== cyg-symlink smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
