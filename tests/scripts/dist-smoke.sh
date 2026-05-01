#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/dist-smoke.sh
#
#  Phase 22 step 3f: ディストリビューション zip のスモーク。
#
#    1. dist/build-dist.sh で zip を作る
#    2. /tmp の一時ディレクトリに unzip
#    3. emulin.sh ash -c '<command>' で 3 ケース動作確認
#       (展開した zip からの起動が壊れていないかの最低限の検証)
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (mvn / unzip / busybox 不在等)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
TIMEOUT=30

if ! command -v mvn   >/dev/null 2>&1; then echo "SKIP dist-smoke : mvn not found";   exit 2; fi
if ! command -v unzip >/dev/null 2>&1; then echo "SKIP dist-smoke : unzip not found"; exit 2; fi
if ! command -v java  >/dev/null 2>&1; then echo "SKIP dist-smoke : java not found";  exit 2; fi
if [ ! -f /usr/bin/busybox ]; then echo "SKIP dist-smoke : /usr/bin/busybox not found"; exit 2; fi

# 1. zip を作る
( bash "$PROJECT/dist/build-dist.sh" >/dev/null 2>&1 ) || {
    echo "FAIL    dist-build (build-dist.sh failed)"
    exit 1
}

ZIP=$(ls "$PROJECT"/target/emulin-dist-*.zip 2>/dev/null | head -1)
if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
    echo "FAIL    dist-build (zip not produced)"
    exit 1
fi

# 2. 解凍 (mktemp で衝突回避)
EXTRACT=$(mktemp -d /tmp/emulin-dist-test.XXXXXX)
trap 'rm -rf "$EXTRACT"' EXIT
unzip -q "$ZIP" -d "$EXTRACT"
DDIR=$(find "$EXTRACT" -mindepth 1 -maxdepth 1 -type d -name 'emulin-*' | head -1)
LAUNCHER=$DDIR/emulin.sh
if [ ! -x "$LAUNCHER" ]; then
    echo "FAIL    dist-launcher (emulin.sh missing or not executable in $DDIR)"
    exit 1
fi

# 3. ケース実行
PASS=0
FAIL=0
declare -a FAILED=()

run_case() {
    local name=$1 cmd=$2 pat=$3
    local act
    act=$(timeout $TIMEOUT "$LAUNCHER" ash -c "$cmd" 2>/dev/null)
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    dist-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    dist-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pat"
            echo "  --- actual ---"
            printf '%s\n' "$act" | sed 's/^/  | /' | head -10
        fi
    fi
}

run_case echo    'echo dist-zip-extracted-ok'                              'dist-zip-extracted-ok'
run_case for     'for i in $(seq 1 3); do echo n=$i; done'                 'n=3'
run_case pipe    'seq 1 5 | grep -c .'                                     '5'

echo
echo "===== dist smoke: PASS=$PASS FAIL=$FAIL (zip=$(basename "$ZIP")) ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
