#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-fast.sh
#
#  軽量版回帰: binary tests + ash 系 + jline-smoke のみ。
#    - real-coreutils.sh (~200s wget HTTPS / curl HTTPS / DNS) を skip
#    - real-heavy.sh (~150s Python/OpenSSL 起動) を skip
#    - dist-smoke.sh (~7s mvn rebuild) を skip
#  通常開発時の素早いフィードバック用。30 秒前後で終わる想定。
#
#  終了コード: いずれかが FAIL なら 1、全て PASS/SKIP なら 0
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
SRC_DIR=$ROOT/binaries/src

JOBS=${JOBS:-$( (nproc 2>/dev/null || echo 4) )}

PASS=0
FAIL=0
SKIP=0
FAIL_NAMES=()

NAMES=()
for src in "$SRC_DIR"/*.c "$SRC_DIR"/*.cc; do
    [ -f "$src" ] || continue
    base=$(basename "$src")
    NAMES+=("${base%.*}")
done

RESULTDIR=$(mktemp -d -t emulin-fastrun.XXXXXX)
SBROOT=$(mktemp -d -t emulin-sb.XXXXXX)
EXTDIR=$(mktemp -d -t emulin-extrun.XXXXXX)
trap 'rm -rf "$RESULTDIR" "$SBROOT" "$EXTDIR" 2>/dev/null || true' EXIT

run_one_to_dir() {
    local name=$1 outdir=$2 root=$3 sbroot=$4
    SANDBOX_DIR="$sbroot/$name" \
        "$root/scripts/run-test.sh" "$name" > "$outdir/$name.out" 2>&1
    echo $? > "$outdir/$name.rc"
}
export -f run_one_to_dir

printf '%s\n' "${NAMES[@]}" | xargs -n1 -P "$JOBS" -I{} \
    bash -c 'run_one_to_dir "$@"' _ {} "$RESULTDIR" "$ROOT" "$SBROOT"

for name in "${NAMES[@]}"; do
    [ -f "$RESULTDIR/$name.rc" ] || continue
    cat "$RESULTDIR/$name.out"
    rc=$(cat "$RESULTDIR/$name.rc")
    case $rc in
        0) PASS=$((PASS + 1)) ;;
        1) FAIL=$((FAIL + 1)); FAIL_NAMES+=("$name") ;;
        2) SKIP=$((SKIP + 1)) ;;
    esac
done

# ext scripts (real-* と dist-smoke は除外)
run_ext_one() {
    local label=$1 script=$2 sandbox=$3 outdir=$4
    [ -f "$script" ] || { echo "SKIP_BG"; return 0; }
    SANDBOX_DIR="$sandbox" bash "$script" > "$outdir/$label.out" 2>&1
    echo $? > "$outdir/$label.rc"
}
export -f run_ext_one

declare -A EXT_LABELS=(
    [ash-noni]="$ROOT/scripts/ash-noninteractive.sh|ash non-interactive regression"
    [ash-cook]="$ROOT/scripts/ash-interactive-cooked.sh|ash interactive (cooked) regression"
    [jline-smoke]="$ROOT/scripts/jline-smoke.sh|JLine smoke"
    [ash-jline]="$ROOT/scripts/ash-interactive-jline.sh|ash interactive (-CJ JLine) regression"
    [ash-applet]="$ROOT/scripts/ash-applet-survey.sh|ash applet survey"
    [cyg-symlink]="$ROOT/scripts/cyg-symlink-smoke.sh|Cygwin symlink マジックファイル smoke"
    [cyg-dentry]="$ROOT/scripts/cyg-dentry-smoke.sh|namei dentry cache invalidation smoke (issue #495)"
    [cyg-casemap]="$ROOT/scripts/cyg-casemap-smoke.sh|大小文字衝突 file encode smoke (issue #349)"
    [cyg-caseenc]="$ROOT/scripts/cyg-caseencode-smoke.sh|build時 case pre-encode + read lazy scan smoke (issue #369)"
    [cyg-mode]="$ROOT/scripts/cyg-mode-smoke.sh|Cygwin chmod xattr 永続化 smoke"
    [jit-correct]="$ROOT/scripts/jit-correctness.sh|JIT (EMULIN_USE_JIT=1) correctness smoke"
    [segv-child]="$ROOT/scripts/segv-child-smoke.sh|fork 子 segfault 非致命化 smoke (issue #113)"
    [pool-exhaust]="$ROOT/scripts/pool-exhaust-smoke.sh|fork pool 枯渇 EAGAIN 縮退 smoke (issue #720)"
    [env-inherit]="$ROOT/scripts/env-inherit-smoke.sh|env passthrough (issue #212) smoke"
    [pool-shrink]="$ROOT/scripts/pool-shrink-smoke.sh|fork 子 pool 縮小時の DATA_BASE 継承 smoke (issue #723)"
    [whp-gpabacking]="$ROOT/scripts/whp-gpabacking-smoke.sh|WHP lazy commit chunk ロジック smoke (issue #304)"
)

# smoke は 1 本ごとに -Xmx2g の JVM を起動し、実測 1.5〜2GB 食うものがある。
#   14 本一斉起動だと WSL2 (11GB) 全体が OOM してデスクトップごと巻き添えに
#   なる (2026-07-04 実害: oom-killer が dbus/emacs を道連れ)。同時 EXT_JOBS
#   本に制限する (既定 4 ≈ 8GB 上限)。
EXT_JOBS=${EXT_JOBS:-4}
EXT_PIDS=()
for label in ash-noni ash-cook jline-smoke ash-jline ash-applet cyg-symlink cyg-dentry cyg-casemap cyg-caseenc cyg-mode jit-correct segv-child pool-exhaust pool-shrink env-inherit whp-gpabacking; do
    while [ "$(jobs -rp | wc -l)" -ge "$EXT_JOBS" ]; do wait -n 2>/dev/null || true; done
    spec=${EXT_LABELS[$label]}
    script=${spec%%|*}
    run_ext_one "$label" "$script" "$SBROOT/ext-$label" "$EXTDIR" &
    EXT_PIDS+=("$!")
done
wait "${EXT_PIDS[@]}" 2>/dev/null || true

for label in ash-noni ash-cook jline-smoke ash-jline ash-applet cyg-symlink cyg-dentry cyg-casemap cyg-caseenc cyg-mode jit-correct segv-child pool-exhaust pool-shrink env-inherit whp-gpabacking; do
    spec=${EXT_LABELS[$label]}
    title=${spec##*|}
    [ -f "$EXTDIR/$label.out" ] || continue
    echo
    echo "----- $title -----"
    out=$(cat "$EXTDIR/$label.out")
    echo "$out"
    rc=$(cat "$EXTDIR/$label.rc" 2>/dev/null || echo 0)
    while IFS= read -r line; do
        case "$line" in
            "PASS    "*) PASS=$((PASS + 1)) ;;
            "FAIL    "*)
                n=${line#FAIL    }
                FAIL=$((FAIL + 1))
                FAIL_NAMES+=("$n")
                ;;
        esac
    done <<<"$out"
    if [ "$rc" = 2 ]; then SKIP=$((SKIP + 1)); fi
done

echo
echo "===== fast regression result (skipping real-coreutils / real-heavy / dist-smoke) ====="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
if [ $FAIL -gt 0 ]; then
    echo "  failed: ${FAIL_NAMES[*]}"
    exit 1
fi
exit 0
