#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/jline-smoke.sh
#
#  Phase 22 step 3a: JLine 依存導入のスモークテスト。
#
#  emulin.JLineSmoke を起動して JLine の Terminal が作れること、
#  enterRawMode/setAttributes の往復が動くことを確認する。
#  非 tty 環境では type=dumb にフォールバックする。
#
#  classpath は target/cp.txt (mvn dependency:build-classpath 出力) を
#  使い、無ければ生成する。
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
CP_FILE=$PROJECT/target/cp.txt

if [ ! -f "$CLASSES/emulin/JLineSmoke.class" ]; then
    echo "SKIP jline-smoke : JLineSmoke not built ($CLASSES/emulin/JLineSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

if [ ! -f "$CP_FILE" ] || [ "$PROJECT/pom.xml" -nt "$CP_FILE" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "SKIP jline-smoke : mvn not found and $CP_FILE is missing/stale"
        exit 2
    fi
    (cd "$PROJECT" && mvn -q dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" 2>/dev/null) || {
        echo "SKIP jline-smoke : failed to build classpath"
        exit 2
    }
fi

CP="$CLASSES:$(cat "$CP_FILE")"
OUT=$(java -cp "$CP" emulin.JLineSmoke </dev/null 2>/dev/null)
RC=$?

PASS=0
FAIL=0
declare -a FAILED=()

check() {
    local label=$1 pat=$2
    if printf '%s' "$OUT" | grep -E -q -- "$pat"; then
        echo "PASS    jline-$label"
        PASS=$((PASS+1))
    else
        echo "FAIL    jline-$label (missing pattern: $pat)"
        FAIL=$((FAIL+1))
        FAILED+=("$label")
    fi
}

if [ "$RC" -ne 0 ]; then
    echo "FAIL    jline-smoke (exit=$RC)"
    echo "  --- output ---"
    printf '%s\n' "$OUT" | sed 's/^/  | /'
    exit 1
fi

check load   '^jline-version='
check type   '^type='
check size   '^size=[0-9]+x[0-9]+$'
check raw    '^raw-mode-ok$'
check signal '^signal-api-ok$'
check winch  '^winch-api-ok$'
check winsize '^winch-size=[0-9]+x[0-9]+$'

echo
echo "===== JLine smoke: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
