#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/pool-exhaust-smoke.sh
#
#  issue #720: native backend で fork 子の guest RAM pool が確保できない
#  (32GB 窓枯渇、issue #379) とき、JVM 全体を System.exit で落とさず
#  「その fork だけ -EAGAIN」にして親を生かす (real Linux 挙動) ことの検証。
#
#  KVM では pool 確保 (mmap MAP_ANON) が自然には失敗しないため、診断スイッチ
#  EMULIN_FORCE_POOL_EXHAUST=1 で fork 経路の枯渇を決定的に再現する。
#  sys_fork_eagain64 が fork=-11 (EAGAIN) を観測し、親が生きて
#    fork=EAGAIN / FORK_EAGAIN ok
#  を出力して exit 0 する (= JVM も親も生存) ことを検証する。
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

BIN=$ROOT/binaries/bin/sys_fork_eagain64
if [ ! -f "$CLASSDIR/emulin/Emulin.class" ]; then
    echo "SKIP pool-exhaust : classes not built ($CLASSDIR)"; exit 2
fi
if [ ! -f "$BIN" ]; then
    echo "SKIP pool-exhaust : binary not built (run 'make -C tests/binaries')"; exit 2
fi
# 診断スイッチは native (KVM) の fork 経路のみに効く
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP pool-exhaust : /dev/kvm not accessible (KVM 無し)"; exit 2
fi

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-poolexh.XXXXXX)}
CLEANUP=0
[ ! -d "$SANDBOX/bin" ] && CLEANUP=1
trap '[ "$CLEANUP" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true' EXIT
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc"
: > "$SANDBOX/etc/emulin.cnf"
cp "$BIN" "$SANDBOX/bin/sys_fork_eagain64"

OUT=$( cd "$SANDBOX"; timeout 60 \
    env EMULIN_BACKEND=native EMULIN_FORCE_POOL_EXHAUST=1 \
    java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods \
    --enable-native-access=ALL-UNNAMED -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/sys_fork_eagain64 2>/dev/null )
RC=$?

FAIL=0
echo "$OUT" | grep -q "^fork=EAGAIN$"    || { echo "FAIL pool-exhaust : fork=EAGAIN が無い (out=$OUT)"; FAIL=1; }
echo "$OUT" | grep -q "^FORK_EAGAIN ok$" || { echo "FAIL pool-exhaust : 親の生存出力が無い";           FAIL=1; }
[ "$RC" = 0 ]                            || { echo "FAIL pool-exhaust : exit=$RC (JVM/親が死んだ疑い)"; FAIL=1; }

if [ "$FAIL" = 0 ]; then
    echo "PASS    pool-exhaust-smoke (fork pool 枯渇 -> EAGAIN で親生存、issue #720)"
    exit 0
fi
exit 1
