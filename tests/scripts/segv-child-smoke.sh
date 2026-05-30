#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/segv-child-smoke.sh
#
#  issue #113: fork した子 process が SIGSEGV (out-of-bounds memory access)
#  しても、JVM 全体を System.exit で落とさず「その子だけを SIGSEGV で終了 +
#  親へ SIGCHLD」する (real Linux 挙動) ことの hermetic 検証。
#
#  sys_segv_child_64 を走らせ、子 segfault 後に親が
#    wait_pid_matches=1 / wifsignaled=1 / wtermsig=11 / parent_alive=1
#  を stdout に出すか (= 親と JVM が生き残ったか) を grep 判定する。
#  子の segfault crash dump が stdout に混ざるため exact-diff ではなく grep。
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

BIN=$ROOT/binaries/bin/sys_segv_child_64
if [ ! -f "$CLASSDIR/emulin/Emulin.class" ]; then
    echo "SKIP segv-child : classes not built ($CLASSDIR)"; exit 2
fi
if [ ! -f "$BIN" ]; then
    echo "SKIP segv-child : binary not built (run 'make -C tests/binaries')"; exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-segvchild.XXXXXX)}
CLEANUP=0
[ ! -d "$SANDBOX/bin" ] && CLEANUP=1
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc"
: > "$SANDBOX/etc/emulin.cnf"
cp "$BIN" "$SANDBOX/bin/sys_segv_child_64"

# 子の segfault dump は stdout に出るので捨てず取り込み、grep で期待行を探す。
RC=0
OUT=$( cd "$SANDBOX"; timeout 60 \
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/sys_segv_child_64 2>/dev/null )
EMU_RC=$?

PASS=0; FAIL=0; FAILED=()
check() {  # $1=label  $2=期待 grep pattern
    if printf '%s\n' "$OUT" | grep -q -- "$2"; then
        echo "PASS    segv-$1"; PASS=$((PASS+1))
    else
        echo "FAIL    segv-$1"; FAIL=$((FAIL+1)); FAILED+=("segv-$1")
    fi
}

check "wait-reaped"   "wait_pid_matches=1"
check "wifsignaled"   "wifsignaled=1"
check "wtermsig-11"   "wtermsig=11"
check "parent-alive"  "parent_alive=1"
# JVM が子 segfault で殺されていない (= emulin が正常終了 rc=0)
if [ "$EMU_RC" = 0 ]; then echo "PASS    segv-jvm-survived"; PASS=$((PASS+1));
else echo "FAIL    segv-jvm-survived (emulin rc=$EMU_RC)"; FAIL=$((FAIL+1)); FAILED+=("segv-jvm-survived"); fi

echo ""
echo "===== segv-child smoke: PASS=$PASS FAIL=$FAIL (total=$((PASS+FAIL))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
