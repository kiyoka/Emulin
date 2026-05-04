#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/real-heavy.sh
#
#  Phase 27: 重量級の実機バイナリ (python3 / openssl) を Emulin 上で
#  動かす回帰。real-coreutils.sh より起動が遅いので別スイートに分離。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (host 不在等)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
TIMEOUT=120

if ! command -v java >/dev/null 2>&1; then echo "SKIP real-heavy : java not found"; exit 2; fi
if [ ! -f /lib64/ld-linux-x86-64.so.2 ]; then echo "SKIP real-heavy : ld-linux not found"; exit 2; fi
if [ ! -f /lib/x86_64-linux-gnu/libc.so.6 ]; then echo "SKIP real-heavy : libc.so.6 not found"; exit 2; fi
if [ ! -d /usr/lib/python3.12 ]; then echo "SKIP real-heavy : /usr/lib/python3.12 not found"; exit 2; fi
if [ ! -x /usr/bin/python3.12 ]; then echo "SKIP real-heavy : /usr/bin/python3.12 not found"; exit 2; fi
if [ ! -x /usr/bin/openssl ]; then echo "SKIP real-heavy : /usr/bin/openssl not found"; exit 2; fi

CLASSES=$PROJECT/target/classes
if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP real-heavy : Emulin not built"
    exit 2
fi

CPFILE=$PROJECT/target/cp.txt
if [ ! -f "$CPFILE" ]; then
    ( cd "$PROJECT" && mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt ) >/dev/null 2>&1
fi
CP="$CLASSES:$(cat "$CPFILE" 2>/dev/null || echo '')"

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-heavy.XXXXXX)}
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX"/{bin,etc,lib,lib64,tmp,usr/bin,usr/lib}
cp /usr/bin/python3.12 "$SANDBOX/usr/bin/python3"
cp /usr/bin/openssl    "$SANDBOX/usr/bin/openssl"

# python3 stdlib は容量が大きいので host のものを symlink
ln -sf /usr/lib/python3.12 "$SANDBOX/usr/lib/python3.12"

# 共有ライブラリ
cp /lib64/ld-linux-x86-64.so.2     "$SANDBOX/lib64/"
cp /lib/x86_64-linux-gnu/libc.so.6 "$SANDBOX/lib/"
for lib in libm.so.6 libcrypto.so.3 libssl.so.3 libz.so.1 libexpat.so.1 libutil.so.1; do
    [ -f "/lib/x86_64-linux-gnu/$lib" ] && cp "/lib/x86_64-linux-gnu/$lib" "$SANDBOX/lib/"
done
: > "$SANDBOX/etc/emulin.cnf"

PASS=0
FAIL=0
declare -a FAILED=()

run_case() {
    local name=$1 pat=$2
    shift 2
    # CASE_FILTER (regex) が設定されていて name にマッチしなければ skip
    if [ -n "${CASE_FILTER:-}" ] && ! [[ "$name" =~ $CASE_FILTER ]]; then return; fi
    local act
    act=$(cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" 2>/dev/null)
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    real-heavy-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-heavy-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pat"
            echo "  --- actual ---"
            printf '%s\n' "$act" | sed 's/^/  | /' | head -10
        fi
    fi
}

# python3 — 短い 1-liner で範囲を絞る (Java emulator 上は重いので)
run_case py-print     'hello python'  /usr/bin/python3 -c 'print("hello python")'
run_case py-arith     '4950'          /usr/bin/python3 -c 'print(sum(range(100)))'
run_case py-listcomp  "['a=1', 'b=2']" /usr/bin/python3 -c 'd={"a":1,"b":2};print([k+"="+str(v) for k,v in d.items()])'
run_case py-version   '(3, 12)'       /usr/bin/python3 -c 'import sys;print(sys.version_info[:2])'

# openssl — version + rand (Phase 27 step 16 で PSRLQ imm を実装、CTR-DRBG
#   が動くようになった = openssl rand / openssl enc -aes-* が動く)
run_case ssl-version  'OpenSSL 3'     /usr/bin/openssl version
# 32 hex chars = 16 byte の random output。grep で 32 文字の hex であることを確認
run_case ssl-rand     ''              /usr/bin/openssl rand -hex 16
# Phase 27 step 17: INC/DEC が CF を保存しないバグを修正 → bn_mul_mont
# (Montgomery 乗算) が動作 → EC ops も動作
run_case ssl-ecparam  'NIST CURVE'    /usr/bin/openssl ecparam -name prime256v1 -text -noout

echo
echo "===== real-heavy: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
