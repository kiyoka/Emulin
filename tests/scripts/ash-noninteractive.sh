#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/ash-noninteractive.sh
#
#  Phase 22 (1): busybox ash -c '<script>' の非対話モード回帰テスト。
#
#  Emulin 経由で busybox ash を起動し、host の busybox ash と
#  出力を比較する。`for / while / case / function / $(...) / $(())
#  / pipeline` といったシェル基本構文を網羅する。
#
#  使い方:
#    bash tests/scripts/ash-noninteractive.sh
#    bash tests/scripts/ash-noninteractive.sh for-seq pipe-chain   # 名前で絞り込み
#    VERBOSE=1 bash tests/scripts/ash-noninteractive.sh
#
#  終了コード: 0=全 PASS / 1=FAIL あり / 2=実行不能 (SKIP)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SANDBOX=$ROOT/sandbox
CLASSES=$PROJECT/target/classes
HOST_BB=/usr/bin/busybox
TIMEOUT=15

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP ash-noninteractive : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP ash-noninteractive : host busybox not found at $HOST_BB"
    exit 2
fi

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# テストケース定義: name@@script
# - script は busybox ash -c に渡す文字列。
# - applet (seq / grep / wc / cat 等) は busybox 経由で PATH 解決する。
CASES=(
    'for-seq@@for i in $(seq 1 5); do echo $i; done'
    'while-counter@@i=0; while [ $i -lt 3 ]; do echo n=$i; i=$((i+1)); done'
    'if-elif@@x=2; if [ $x = 1 ]; then echo one; elif [ $x = 2 ]; then echo two; else echo other; fi'
    'case-stmt@@x=banana; case $x in apple) echo A;; banana) echo B;; *) echo X;; esac'
    'func-def@@greet() { echo hi $1; }; greet world'
    'param-expand@@v=hello; echo ${v}-${v#he}'
    'command-sub@@echo "count=$(seq 1 3 | wc -l)"'
    'arith@@echo $(( (3+4) * 2 ))'
    'pipe-chain@@seq 1 5 | grep -v 3 | wc -l'
    'and-or@@true && echo yes; false || echo no'
    'exit-status@@(exit 7); echo rc=$?'
)

# 引数で名前指定があれば絞り込み
if [ $# -gt 0 ]; then
    SELECT=" $* "
    NEW=()
    for c in "${CASES[@]}"; do
        n=${c%%@@*}
        if [[ "$SELECT" == *" $n "* ]]; then
            NEW+=("$c")
        fi
    done
    CASES=("${NEW[@]}")
fi

PASS=0
FAIL=0
declare -a FAILED=()

for entry in "${CASES[@]}"; do
    name=${entry%%@@*}
    script=${entry#*@@}

    # host 側で expected を生成。busybox applet を見つけられるよう
    # PATH に sandbox/bin を足す。
    EXP=$($HOST_BB ash -c "export PATH=$SANDBOX/bin:\$PATH; $script" </dev/null 2>/dev/null)

    ACT=$(cd "$SANDBOX" && timeout $TIMEOUT \
        java -cp "$CLASSES" emulin.Emulin "$SANDBOX" /bin/busybox ash -c "$script" \
        </dev/null 2>/dev/null)
    rc=$?

    if [ "$rc" = 124 ]; then
        printf 'FAIL    ash-%s (timeout)\n' "$name"
        FAIL=$((FAIL+1))
        FAILED+=("$name(timeout)")
        continue
    fi
    if [ "$EXP" = "$ACT" ]; then
        printf 'PASS    ash-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    ash-%s\n' "$name"
        FAIL=$((FAIL+1))
        FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected (host ash) ---"
            printf '%s\n' "$EXP" | sed 's/^/  | /'
            echo "  --- actual (emulin ash) ---"
            printf '%s\n' "$ACT" | sed 's/^/  | /'
        fi
    fi
done

echo
echo "===== ash non-interactive: PASS=$PASS FAIL=$FAIL (total=${#CASES[@]}) ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
