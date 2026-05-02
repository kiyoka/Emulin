#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/real-coreutils.sh
#
#  Phase 27: 実機の GNU coreutils (動的リンク + libc + ...) を Emulin 上で
#  動かす回帰。ls / cat / wc / echo / true / false / dirname / basename /
#  uname の 9 種類を host から sandbox にコピーして起動する。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (host 不在等)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
TIMEOUT=30

if ! command -v java >/dev/null 2>&1; then echo "SKIP real-coreutils : java not found"; exit 2; fi
if [ ! -x /bin/ls ]; then echo "SKIP real-coreutils : /bin/ls not found"; exit 2; fi
if [ ! -f /lib64/ld-linux-x86-64.so.2 ]; then echo "SKIP real-coreutils : ld-linux not found"; exit 2; fi
if [ ! -f /lib/x86_64-linux-gnu/libc.so.6 ]; then echo "SKIP real-coreutils : libc.so.6 not found"; exit 2; fi

CLASSES=$PROJECT/target/classes
if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP real-coreutils : Emulin not built"
    exit 2
fi

CPFILE=$PROJECT/target/cp.txt
if [ ! -f "$CPFILE" ]; then
    ( cd "$PROJECT" && mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt ) >/dev/null 2>&1
fi
CP="$CLASSES:$(cat "$CPFILE" 2>/dev/null || echo '')"

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-coreutils.XXXXXX)}
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX"/{bin,etc,lib,lib64,tmp,usr/bin}
# 必須バイナリ
for b in /bin/ls /bin/cat /bin/echo /bin/true /bin/false /bin/dirname /bin/basename /bin/uname; do
    [ -x "$b" ] && cp "$b" "$SANDBOX/bin/"
done
# wc は Debian 系では /usr/bin
[ -x /usr/bin/wc ] && cp /usr/bin/wc "$SANDBOX/usr/bin/"

# 共有ライブラリ
cp /lib64/ld-linux-x86-64.so.2            "$SANDBOX/lib64/"
cp /lib/x86_64-linux-gnu/libc.so.6        "$SANDBOX/lib/"
for lib in libselinux.so.1 libpcre2-8.so.0 libacl.so.1; do
    [ -f "/lib/x86_64-linux-gnu/$lib" ] && cp "/lib/x86_64-linux-gnu/$lib" "$SANDBOX/lib/"
done
: > "$SANDBOX/etc/emulin.cnf"

# サンプル入力ファイル (4 行)
printf 'hello world from cat\none\ntwo\nthree\n' > "$SANDBOX/tmp/sample.txt"

PASS=0
FAIL=0
declare -a FAILED=()

run_case() {
    local name=$1 pat=$2
    shift 2
    local act
    act=$(cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" 2>/dev/null)
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    real-coreutils-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-coreutils-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pat"
            echo "  --- actual ---"
            printf '%s\n' "$act" | sed 's/^/  | /' | head -10
        fi
    fi
}

run_case_exit() {
    local name=$1 expected_exit=$2
    shift 2
    ( cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" >/dev/null 2>&1 )
    local rc=$?
    if [ "$rc" = "$expected_exit" ]; then
        printf 'PASS    real-coreutils-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-coreutils-%s (exit: expected=%s actual=%s)\n' "$name" "$expected_exit" "$rc"
        FAIL=$((FAIL+1)); FAILED+=("$name")
    fi
}

# ls
run_case ls-bare    'lib64'        /bin/ls /
run_case ls-la-tmp  'total '       /bin/ls -la /tmp
run_case ls-l-lib   'libc.so.6'    /bin/ls -l /lib
# cat / wc — sample.txt の内容で確認
run_case cat        'hello world'  /bin/cat /tmp/sample.txt
run_case wc         '4'            /usr/bin/wc -l /tmp/sample.txt
# echo はそのまま echo back
run_case echo       'hello-echo'   /bin/echo hello-echo
# true/false の exit code
run_case_exit true   0  /bin/true
run_case_exit false  1  /bin/false
# dirname / basename
run_case dirname    '/a/b'         /bin/dirname /a/b/c
run_case basename   'c'            /bin/basename /a/b/c
# uname (Emulin の identifier が出るのを確認)
run_case uname      'Emulin'       /bin/uname -a

echo
echo "===== real-coreutils: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
