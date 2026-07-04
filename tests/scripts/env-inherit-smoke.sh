#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/env-inherit-smoke.sh
#
#  issue #212: EMULIN_INHERIT_ENV による host OS env の guest 継承を検証する。
#
#    Case A (EMULIN_INHERIT_ENV=1):
#      - host の任意変数 (FOO_ISSUE212) が guest に届く
#      - emulin 必須変数 (HOME/PATH) は emulin の値で上書きされ、host の
#        値 (HOME=/HOST/BOGUS_HOME) が guest を壊さない
#    Case B (flag 無し = 従来動作):
#      - host 変数は guest に漏れない (whitelist のみ通す default off)
#
#  guest 側 env の観測には env_probe64 (-nostdlib、初期 stack から envp を
#  dump) を使う。Emulin / env_probe64 が未ビルドなら SKIP。
#
#  終了コード: 0=全 PASS / 1=いずれか FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
BIN=$ROOT/binaries/bin/env_probe64

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP env-inherit : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
if [ ! -f "$BIN" ]; then
    echo "SKIP env-inherit : env_probe64 not built ($BIN)"
    echo "  run 'make -C tests/binaries' first"
    exit 2
fi

# 並列実行時は呼び出し側 (run-fast/run-all) が SANDBOX_DIR を渡す。その場合は
# 掃除しない (呼び出し側が管理)。未指定なら自分で mktemp して後始末する。
if [ -n "${SANDBOX_DIR:-}" ]; then
    SANDBOX=$SANDBOX_DIR; OWN_SANDBOX=0
else
    SANDBOX=$(mktemp -d -t emulin-envtest.XXXXXX); OWN_SANDBOX=1
fi
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc"
: > "$SANDBOX/etc/emulin.cnf"
cp "$BIN" "$SANDBOX/bin/env_probe64"

JVM_OPTS=( -Xmx2g -XX:-UsePerfData -XX:-DontCompileHugeMethods )

# run_probe <extra env / env flags...> : 追加 env で guest env を dump する。
run_probe() {
    ( cd "$SANDBOX" && env "$@" java "${JVM_OPTS[@]}" -cp "$CLASSES" \
        emulin.Emulin "$SANDBOX" /bin/env_probe64 </dev/null 2>/dev/null )
}

PASS=0
FAIL=0
declare -a FAILED=()
ok()  { echo "PASS    env-$1"; PASS=$((PASS+1)); }
ng()  { echo "FAIL    env-$1"; FAIL=$((FAIL+1)); FAILED+=("$1"); }

# emulin の default PATH (Kernel.boot の必須セット)。host が PATH を渡しても
# これで上書きされる。
EMU_PATH_VALUE='PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/sbin:/usr/bin:/bin'

# ---- Case A: EMULIN_INHERIT_ENV=1 -------------------------------------------
OUT_ON=$(run_probe EMULIN_INHERIT_ENV=1 FOO_ISSUE212=bar123 HOME=/HOST/BOGUS_HOME)

# A-1: host の任意変数が guest に届く
if grep -qxF 'FOO_ISSUE212=bar123' <<<"$OUT_ON"; then ok inherit-on; else
    ng inherit-on; echo "  (FOO_ISSUE212=bar123 が guest env に無い)"; fi

# A-2: 必須 HOME は emulin 値 (/root)、host の BOGUS は漏れない
if grep -qxF 'HOME=/root' <<<"$OUT_ON" && ! grep -q 'BOGUS_HOME' <<<"$OUT_ON"; then
    ok essential-override; else
    ng essential-override; echo "  (HOME=/root でない or host HOME が漏れた)"; fi

# A-3: 必須 PATH も emulin 値のまま
if grep -qxF "$EMU_PATH_VALUE" <<<"$OUT_ON"; then ok path-intact; else
    ng path-intact; echo "  (PATH が emulin 既定値でない)"; fi

# ---- Case B: flag 無し (従来動作) -------------------------------------------
# 呼び出し元の環境に EMULIN_INHERIT_ENV が残っていても確実に off にする。
OUT_OFF=$(run_probe -u EMULIN_INHERIT_ENV FOO_ISSUE212=bar123)

# B-1: default off では host 変数は guest に漏れない (後方互換)
if grep -q 'FOO_ISSUE212' <<<"$OUT_OFF"; then
    ng inherit-off; echo "  (flag 無しなのに FOO_ISSUE212 が漏れた)"; else
    ok inherit-off; fi

[ "$OWN_SANDBOX" = 1 ] && rm -rf "$SANDBOX" 2>/dev/null || true

echo
echo "===== env-inherit smoke: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
