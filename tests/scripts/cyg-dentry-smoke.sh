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
# ★ issue #1018: **guest が最後まで走ったかを先に見る**。timeout に殺されると
#   出力が途中で切れ、検査は「殺された地点より後ろ全部」を値の不一致として
#   報告してしまう (2026-09-08 の CI で cyg-symlink がそうなり、3 回発生してなお
#   原因が見えなかった)。時間も負荷で伸びるので可変にする。
CYG_TIMEOUT=${CYG_TIMEOUT:-180}
ERRLOG=$SANDBOX/.emulin-stderr
# ★ CI で 3 日に 4 回落ちている (2026-09-07/08/09)。#1018 で「timeout に殺された」と
#   名指しできるようにはなったが、**負荷で伸びたのか本体が止まったのかが分からない**。
#   → `-s QUIT` で先に **JVM スレッドダンプ**を取ってから殺す。RUNNABLE で Cpu64/Jit に
#     居れば「遅い」、WaitHub/park で待っていれば「止まっている」と即断できる。
#   ★ QUIT では JVM は死なないので `-k` の SIGKILL が続き、**rc は 124 でなく 137** になる
#     (実測)。137 を timeout 扱いにし忘れると「途中で死んだ」に化ける。
CYG_T0=$(date +%s)
OUT=$( cd "$SANDBOX"; EMULIN_FORCE_CYGWIN_SYMLINK=1 timeout -s QUIT -k 10 "$CYG_TIMEOUT" \
    java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$SCRIPT" 2>"$ERRLOG" )
GUEST_RC=$?
CYG_ELAPSED=$(( $(date +%s) - CYG_T0 ))
if [ "$GUEST_RC" != 0 ]; then
    if [ "$GUEST_RC" = 124 ] || [ "$GUEST_RC" = 137 ]; then
        echo "FAIL    cyg-dentry-guest-finished : guest was killed by timeout after ${CYG_TIMEOUT}s (実測 ${CYG_ELAPSED}s)"
        echo "        (負荷で伸びたなら CYG_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    else
        echo "FAIL    cyg-dentry-guest-finished : guest exited rc=$GUEST_RC (途中で死んだ)"
    fi
    echo "        --- guest stderr (tail) ---"
    tail -5 "$ERRLOG" 2>/dev/null | sed 's/^/        /'
    # ★ 「遅い」と「止まっている」を区別する計器 (#1018 が最後まで分からなかった点)。
    if printf '%s\n' "$OUT" | grep -qa '^Full thread dump'; then
        echo "        --- JVM thread dump (guest スレッドのみ要約) ---"
        # ★ **emulin のフレームを持つスレッドだけ**を、ヘッダ + State + 上位フレームで出す。
        #   全 State 行を grep すると GC/JIT スレッドの State がヘッダ無しで混ざり、読めなくなる。
        printf '%s\n' "$OUT" | sed -n '/^Full thread dump/,$p' \
            | awk '/^"/{hdr=$0; st=""; n=0} /Thread\.State:/{if(st=="")st=$0} /^\tat emulin\./{if(n==0){print hdr; print "   " st} if(n<5){print "   " $0; n++}}' \
            | head -40 | sed 's/^/        /'
    else
        echo "        (JVM thread dump が無い = SIGQUIT が届く前に終わっていた)"
    fi
    echo "===== cyg-dentry smoke: PASS=0 FAIL=1 (guest が最後まで走らなかった) ====="
    exit 1
fi

# ★ 成功時も所要時間を出す。**緑の実行が「普段どれだけかかるか」を記録しない限り、
#   赤が出たときに「負荷で伸びた」かどうかを言えない** (CI では計測が残らなかった)。
echo "        (guest 実行 ${CYG_ELAPSED}s / 制限 ${CYG_TIMEOUT}s)"

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
