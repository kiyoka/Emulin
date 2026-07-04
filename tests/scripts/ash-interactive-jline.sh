#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/ash-interactive-jline.sh
#
#  Phase 22 step 3b: JLine バックエンド (-CJ) で対話 ash が動くか検証する。
#
#  ash-interactive-cooked.sh と同じケースを emulin -CJ で流して、出力に
#  期待文字列が含まれるか比較する。実 tty では JLine 経由で raw / cooked
#  を扱うが、CI のパイプ入力では JLine が dumb terminal にフォールバック
#  し、cooked 行バッファ動作になる。
#
#  classpath: target/cp.txt (mvn dependency:build-classpath 出力) を使う。
#
#  使い方:
#    bash tests/scripts/ash-interactive-jline.sh
#    bash tests/scripts/ash-interactive-jline.sh echo-line
#    VERBOSE=1 bash tests/scripts/ash-interactive-jline.sh
#
#  終了コード: 0=全 PASS / 1=FAIL あり / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SANDBOX=${SANDBOX_DIR:-$ROOT/sandbox}
CLASSES=$PROJECT/target/classes
CP_FILE=$PROJECT/target/cp.txt
HOST_BB=/usr/bin/busybox
TIMEOUT=15

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP ash-interactive-jline : Emulin not built"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP ash-interactive-jline : host busybox not found"
    exit 2
fi
if [ ! -f "$CP_FILE" ] || [ "$PROJECT/pom.xml" -nt "$CP_FILE" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "SKIP ash-interactive-jline : mvn not found and $CP_FILE missing"
        exit 2
    fi
    (cd "$PROJECT" && mvn -q dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" 2>/dev/null) || {
        echo "SKIP ash-interactive-jline : failed to build classpath"
        exit 2
    }
fi

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# tar tf 用のフィクスチャ (wait4 ECHILD 修正の回帰固定 / commit d8fb4d3)
TAR_FIX=$SANDBOX/tmp/regfix.tar
( cd "$SANDBOX/tmp" && rm -f rf-a.txt rf-b.txt "$TAR_FIX" \
    && echo a > rf-a.txt && echo b > rf-b.txt \
    && tar cf "$TAR_FIX" rf-a.txt rf-b.txt 2>/dev/null )

CP="$CLASSES:$(cat "$CP_FILE")"

# tar-tf-prompt-returns:
#   busybox tar は内部で wait4(-1) を呼び ECHILD を期待する。我々が 0 を
#   返すと tar が無限ループに陥り、親 ash の wait4(tar_pid) も帰らず
#   プロンプトが返らないバグ (commit d8fb4d3 で修正)。 tar の出力後に
#   echo AFTER が見えれば、ash がプロンプト復帰している証拠。
CASES=(
    'echo-line@@echo hello-jline\nexit 0\n@@hello-jline'
    'two-cmds@@echo first-line\necho second-line\nexit 0\n@@second-line'
    'exit-status@@true\necho rc=$?\nexit 0\n@@rc=0'
    'arith-interactive@@echo $((6*7))\nexit 0\n@@42'
    'pipe-interactive@@seq 1 5 | wc -l\nexit 0\n@@5'
    'tar-tf-prompt-returns@@tar tf /tmp/regfix.tar\necho AFTER-TAR\nexit 0\n@@AFTER-TAR'
)

if [ $# -gt 0 ]; then
    SELECT=" $* "
    NEW=()
    for c in "${CASES[@]}"; do
        n=${c%%@@*}
        if [[ "$SELECT" == *" $n "* ]]; then NEW+=("$c"); fi
    done
    CASES=("${NEW[@]}")
fi

PASS=0
FAIL=0
declare -a FAILED=()

for entry in "${CASES[@]}"; do
    name=${entry%%@@*}
    rest=${entry#*@@}
    stdin_data=${rest%%@@*}
    pattern=${rest#*@@}

    actual=$(printf '%b' "$stdin_data" | (cd "$SANDBOX" && timeout $TIMEOUT \
        java -Xmx2g -cp "$CP" emulin.Emulin "$SANDBOX" -CJ /bin/busybox ash -i 2>/dev/null))
    rc=$?

    if [ "$rc" = 124 ]; then
        printf 'FAIL    ash-jline-%s (timeout)\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name(timeout)")
        continue
    fi

    if printf '%s' "$actual" | grep -F -q -- "$pattern"; then
        printf 'PASS    ash-jline-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    ash-jline-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pattern"
            echo "  --- actual stdout ---"
            printf '%s\n' "$actual" | sed 's/^/  | /' | head -30
        fi
    fi
done

echo
echo "===== ash interactive (-CJ JLine): PASS=$PASS FAIL=$FAIL (total=${#CASES[@]}) ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
