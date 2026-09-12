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

# ★ issue #1018: 制限時間を env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
PE_TIMEOUT=${PE_TIMEOUT:-60}
ERRLOG=$SANDBOX/.emulin-stderr

OUT=$( cd "$SANDBOX"; timeout "$PE_TIMEOUT" \
    env EMULIN_BACKEND=native EMULIN_FORCE_POOL_EXHAUST=1 \
    java -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods \
    --enable-native-access=ALL-UNNAMED -cp "$CLASSES" \
    emulin.Emulin "$SANDBOX" /bin/sys_fork_eagain64 2>"$ERRLOG" )
RC=$?

# ★ issue #1018: **出力の中身を比べる前に、guest が最後まで走ったかを言う**。
#   ここで黙ると、途中で殺されただけなのに「fork=EAGAIN が無い」= 機能が壊れた、と
#   読める 2 行が先に出る (実害: cyg-symlink で 3 回とも誤読した #1018)。
if [ "$RC" != 0 ]; then
    if [ "$RC" = 124 ]; then
        echo "FAIL    pool-exhaust : guest was killed by timeout after ${PE_TIMEOUT}s"
        echo "        (負荷で伸びたなら PE_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    else
        echo "FAIL    pool-exhaust : guest exited rc=$RC (JVM/親が死んだ疑い = 途中で死んだ)"
    fi
    echo "        --- guest stderr (tail) ---"
    tail -5 "$ERRLOG" 2>/dev/null | sed 's/^/        /'
    echo "        --- 得られた出力 ---"
    printf '%s\n' "$OUT" | sed 's/^/        /'
    exit 1
fi

FAIL=0
echo "$OUT" | grep -q "^fork=EAGAIN$"    || { echo "FAIL pool-exhaust : fork=EAGAIN が無い (out=$OUT)"; FAIL=1; }
echo "$OUT" | grep -q "^FORK_EAGAIN ok$" || { echo "FAIL pool-exhaust : 親の生存出力が無い";           FAIL=1; }

if [ "$FAIL" = 0 ]; then
    echo "PASS    pool-exhaust-smoke (fork pool 枯渇 -> EAGAIN で親生存、issue #720)"
    exit 0
fi
exit 1
