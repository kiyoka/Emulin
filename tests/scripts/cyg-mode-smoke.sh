#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/cyg-mode-smoke.sh
#
#  issue #68 Phase 2: chmod の mode が xattr に永続化され、stat で読み
#  戻せるかの hermetic 動作確認 (EMULIN_FORCE_CYGWIN_SYMLINK=1)。
#
#  検証項目 (全て 1 回の emulin 起動 = CI 並列負荷下の JVM flake 回避):
#    1. chmod 600 → stat %a = 600
#    2. chmod 755 → stat %a = 755
#    3. .ssh fallback: chmod せず置いた鍵は stat で 600 (issue #9 安全網)
#    4. .ssh explicit: chmod 644 した .ssh file は 644 (xattr が偽装に優先)
#
#  (Linux ext4 では実 chmod も効くため xattr 単独の分離検証はできないが、
#   feature が正しく動くことを確認する。xattr 権威性は手動確認済。)
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
    echo "SKIP cyg-mode-smoke : Emulin not built"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP cyg-mode-smoke : host busybox not found at $HOST_BB"
    exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-cygmode.XXXXXX)}
CLEANUP=0
if [ ! -d "$SANDBOX/bin" ]; then CLEANUP=1; fi
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp" "$SANDBOX/root/.ssh"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

SCRIPT='
echo k > /tmp/key; chmod 600 /tmp/key
echo "T1=$(stat -c %a /tmp/key)"
echo s > /tmp/scr; chmod 755 /tmp/scr
echo "T2=$(stat -c %a /tmp/scr)"
echo nochmod-key > /root/.ssh/id_copied
echo "T3=$(stat -c %a /root/.ssh/id_copied)"
echo pub > /root/.ssh/explicit; chmod 644 /root/.ssh/explicit
echo "T4=$(stat -c %a /root/.ssh/explicit)"
'
# ★ issue #1018: **guest が最後まで走ったかを先に見る**。timeout に殺されると
#   出力が途中で切れ、検査は「殺された地点より後ろ全部」を値の不一致として
#   報告してしまう (2026-09-08 の CI で cyg-symlink がそうなり、3 回発生してなお
#   原因が見えなかった)。時間も負荷で伸びるので可変にする。
CYG_TIMEOUT=${CYG_TIMEOUT:-180}
ERRLOG=$SANDBOX/.emulin-stderr
OUT=$( cd "$SANDBOX"; EMULIN_FORCE_CYGWIN_SYMLINK=1 timeout "$CYG_TIMEOUT" \
    java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$SCRIPT" 2>"$ERRLOG" )
GUEST_RC=$?
if [ "$GUEST_RC" != 0 ]; then
    if [ "$GUEST_RC" = 124 ]; then
        echo "FAIL    cyg-mode-guest-finished : guest was killed by timeout after ${CYG_TIMEOUT}s"
        echo "        (負荷で伸びたなら CYG_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    else
        echo "FAIL    cyg-mode-guest-finished : guest exited rc=$GUEST_RC (途中で死んだ)"
    fi
    echo "        --- guest stderr (tail) ---"
    tail -5 "$ERRLOG" 2>/dev/null | sed 's/^/        /'
    echo "===== cyg-mode smoke: PASS=0 FAIL=1 (guest が最後まで走らなかった) ====="
    exit 1
fi

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

check "chmod-600"        "$(get T1)" "600"
check "chmod-755"        "$(get T2)" "755"
check "ssh-fallback-600" "$(get T3)" "600"
check "ssh-explicit-644" "$(get T4)" "644"

echo ""
echo "===== cyg-mode smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
