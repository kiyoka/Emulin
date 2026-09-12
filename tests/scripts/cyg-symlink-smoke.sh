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
# ★ issue #1018: **guest が最後まで走ったかを先に見る**。
#   実害 (2026-09-08 の CI): timeout に殺されると出力が途中で切れ、
#   検査は「殺された地点より後ろ全部」を `got=''` として報告していた。
#   画面には T3/T4/T5 が「出力が違う」と出るだけで、**本当の原因 (途中で死んだ)
#   がどこにも出ない**ため、3 回発生してなお flake 扱いのままだった。
#   ★ 時間も負荷で伸びるので可変にする (ssh 軸で 2 度踏んだのと同じ形 #1015)。
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
    # ★ 出力の中身を比べる前に言う。ここで黙ると「値が違う」に化ける。
    if [ "$GUEST_RC" = 124 ] || [ "$GUEST_RC" = 137 ]; then
        echo "FAIL    cyg-symlink-guest-finished : guest was killed by timeout after ${CYG_TIMEOUT}s (実測 ${CYG_ELAPSED}s)"
        echo "        (負荷で伸びたなら CYG_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    else
        echo "FAIL    cyg-symlink-guest-finished : guest exited rc=$GUEST_RC (途中で死んだ)"
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
    echo "        --- 得られた出力 (thread dump は除く) ---"
    # ★ thread dump 本体と、その直前に JVM が出す日時行を落として guest の出力だけ見せる。
    printf '%s\n' "$OUT" | sed -E '/^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$/d; /^Full thread dump/,$d' \
        | tail -10 | sed 's/^/        /'
    echo "===== cyg-symlink smoke: PASS=0 FAIL=1 (guest が最後まで走らなかった) ====="
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
