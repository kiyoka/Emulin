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
# ★ issue #1018: env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
#   ★ 負のコントロールは `TIMEOUT=1` では**発火しない** (この dist は busybox の小さな
#     rootfs で 1s 以内に終わる)。`TIMEOUT=0.05` のように**小数**を使うこと。
TIMEOUT=${TIMEOUT:-30}

if ! command -v mvn   >/dev/null 2>&1; then echo "SKIP dist-smoke : mvn not found";   exit 2; fi
if ! command -v unzip >/dev/null 2>&1; then echo "SKIP dist-smoke : unzip not found"; exit 2; fi
if ! command -v java  >/dev/null 2>&1; then echo "SKIP dist-smoke : java not found";  exit 2; fi
if [ ! -f /usr/bin/busybox ]; then echo "SKIP dist-smoke : /usr/bin/busybox not found"; exit 2; fi

# 1. zip を作る
( bash "$PROJECT/dist/build-dist.sh" >/dev/null 2>&1 ) || {
    echo "FAIL    dist-build (build-dist.sh failed)"
    exit 1
}

# ★ issue #929: **pom.xml の版と一致する zip だけ**を検査対象にする。
#   旧実装は `ls target/emulin-dist-*.zip | head -1` で、版を上げた直後 (target に旧版の
#   zip が残っている状態) だと **今作った zip ではなく古い zip を検査**していた。
#   build-dist.sh 側を直しただけでは足りず、ここも独立に同じ選び方をしていた
#   (#898/#903/#919/#921 と同じ「N 個のうち 1 個」型)。
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$PROJECT/pom.xml" | head -1)
ZIP=$PROJECT/target/emulin-dist-$VERSION.zip
if [ ! -f "$ZIP" ]; then
    echo "FAIL    dist-build (zip for version $VERSION not produced: $ZIP)"
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
    # ★ issue #1018: **timeout に殺されたことを「出力が違う」に化けさせない**。
    #   実害 (cyg-symlink, 2026-09-08): 途中で殺されると出力が切れ、後続のケースが
    #   すべて値の不一致として報告される。3 回発生して 3 回とも「たまに落ちる」で
    #   片付けかけた。**rc を見て名指しする**こと。
    act=$(timeout $TIMEOUT "$LAUNCHER" ash -c "$cmd" 2>/dev/null)
    local rc=$?
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    dist-%s\n' "$name"
        PASS=$((PASS+1))
    elif [ "$rc" = 124 ]; then
        printf 'FAIL    dist-%s : guest was killed by timeout after %ss\n' "$name" "$TIMEOUT"
        printf '        (負荷で伸びたなら TIMEOUT を上げる。伸びていないなら本体の停止を疑う)\n'
        FAIL=$((FAIL+1)); FAILED+=("$name")
    else
        # ★ issue #1018: 出力が合わない時、guest が異常終了していたなら**それを言う**。
        #   ここで黙ると SIGSEGV/SIGABRT で死んだ (rc=139/134) のも「値が違う」に見える。
        if [ "$rc" != 0 ]; then
            printf 'FAIL    dist-%s (guest rc=%s)\n' "$name" "$rc"
        else
            printf 'FAIL    dist-%s\n' "$name"
        fi
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
