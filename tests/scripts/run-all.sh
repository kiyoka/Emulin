#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-all.sh
#
#  tests/binaries/src/*.c に対応する全テストを実行する。
#
#  終了コード: いずれかが FAIL なら 1、全て PASS/SKIP なら 0
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
SRC_DIR=$ROOT/binaries/src

# issue #924: ソースより古い target/classes でテストを走らせない。
#   古いまま走らせると (a) 直前の編集を含まない別物を測る (b) 下の dist-smoke の
#   mvn package が実ビルドになり、同時に走る他のスイートを巻き添えにする。
bash "$ROOT/scripts/check-build-fresh.sh" "tests/scripts/run-all.sh" || exit 2

# 並列度: 環境変数 JOBS 優先、未指定なら nproc。JOBS=1 で従来の逐次実行に戻る。
JOBS=${JOBS:-$( (nproc 2>/dev/null || echo 4) )}

PASS=0
FAIL=0
SKIP=0
FAIL_NAMES=()

# テスト名一覧を先に作る (sys_*64.c だけでなく *.c 全部 = 旧仕様と同じ)
NAMES=()
for src in "$SRC_DIR"/*.c "$SRC_DIR"/*.cc; do
    [ -f "$src" ] || continue
    base=$(basename "$src")
    NAMES+=("${base%.*}")
done

# 一時的な結果格納ディレクトリと per-test sandbox のベース。
# WSL DrvFs (/mnt/c/...) は I/O が遅く chmod も効かないため、
# Linux 側の /tmp (tmpfs / ext4) に sandbox を置く。これで
# sys_chmod64 が PASS し、全体のテストも数秒短縮される。
RESULTDIR=$(mktemp -d -t emulin-regrun.XXXXXX)
SBROOT=$(mktemp -d -t emulin-sb.XXXXXX)
trap 'rm -rf "$RESULTDIR" "$SBROOT" 2>/dev/null || true' EXIT

# 1 件分のラッパ: 専用の sandbox/$name で run-test.sh を呼び stdout / exit code
# を $outdir に保存する。xargs から bash -c で起動する。
run_one_to_dir() {
    local name=$1 outdir=$2 root=$3 sbroot=$4
    SANDBOX_DIR="$sbroot/$name" \
        "$root/scripts/run-test.sh" "$name" > "$outdir/$name.out" 2>&1
    echo $? > "$outdir/$name.rc"
}
export -f run_one_to_dir

# xargs -P で並列実行
printf '%s\n' "${NAMES[@]}" | xargs -n1 -P "$JOBS" -I{} \
    bash -c 'run_one_to_dir "$@"' _ {} "$RESULTDIR" "$ROOT" "$SBROOT"

# 結果集計 (元のソース順序で出す)
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

# 外部スクリプト形式の回帰 (PASS/FAIL/SKIP の行を集計)
#
# 各 ext script を並列で走らせるが、SANDBOX を共有すると衝突するので
# それぞれ別の SANDBOX_DIR を渡す。
# ★ issue #924: dist-smoke は内部で mvn package (= target/classes と jar を書き換える)
#   を呼ぶので **並列群に入れてはいけない**。旧実装は「target は既にビルド済み想定
#   (no-op に近い) のため衝突は無視」としていたが、その前提が崩れると (ソースを触った
#   直後など) 実ビルドが走り、同時に走る 8 本が書き換え中の target/classes を読んで
#   大量の偽 FAIL になる (実際に 26 件出た)。並列に入る前に**単独で**走らせる。
EXTDIR=$(mktemp -d -t emulin-extrun.XXXXXX)
trap 'rm -rf "$RESULTDIR" "$SBROOT" "$EXTDIR" 2>/dev/null || true' EXIT

run_ext_one() {
    local label=$1 script=$2 sandbox=$3 outdir=$4
    [ -f "$script" ] || { echo "SKIP_BG"; return 0; }
    SANDBOX_DIR="$sandbox" bash "$script" > "$outdir/$label.out" 2>&1
    echo $? > "$outdir/$label.rc"
}
export -f run_ext_one

# 5 本同時に投げる。各々独立した sandbox.<label>/ を使う。
declare -A EXT_LABELS=(
    [ash-noni]="$ROOT/scripts/ash-noninteractive.sh|ash non-interactive regression"
    [ash-cook]="$ROOT/scripts/ash-interactive-cooked.sh|ash interactive (cooked) regression"
    [jline-smoke]="$ROOT/scripts/jline-smoke.sh|JLine smoke"
    [ash-jline]="$ROOT/scripts/ash-interactive-jline.sh|ash interactive (-CJ JLine) regression"
    [ash-applet]="$ROOT/scripts/ash-applet-survey.sh|ash applet survey"
    [dist-smoke]="$ROOT/scripts/dist-smoke.sh|dist zip smoke"
    [real-coreutils]="$ROOT/scripts/real-coreutils.sh|real GNU coreutils smoke"
    [real-heavy]="$ROOT/scripts/real-heavy.sh|real heavy binaries smoke (python3, openssl)"
    [env-inherit]="$ROOT/scripts/env-inherit-smoke.sh|env passthrough (issue #212) smoke"
    [token-rotate]="$ROOT/scripts/token-rotate-smoke.sh|OAuth refresh の in-flight 直列化 (issue #954)"
    [credadmin]="$ROOT/scripts/credadmin-smoke.sh|credential の状況表示 (issue #968)"
    [instance-warn]="$ROOT/scripts/instance-warn-smoke.sh|rootfs 共有の検出 (issue #955)"
    [jlink-modules]="$ROOT/scripts/jlink-modules-match.sh|jlink module set の一致 (issue #959)"
    [guestjob-quote]="$ROOT/scripts/guestjob-quoting-smoke.sh|guest へ渡すコマンドの引用 (issue #948)"
    [guest-launch]="$ROOT/scripts/guest-launch-match.sh|guest 起動条件の一致 (issue #963)"
    [sshkeys]="$ROOT/scripts/sshkeys-smoke.sh|公開鍵の登録 / 秘密鍵の拒否 (issue #964)"
)

# issue #924: dist-smoke だけ先に単独で走らせる (mvn package を並列群と重ねない)。
{
    spec=${EXT_LABELS[dist-smoke]}
    run_ext_one "dist-smoke" "${spec%%|*}" "$SBROOT/ext-dist-smoke" "$EXTDIR"
}

EXT_PIDS=()
for label in ash-noni ash-cook jline-smoke ash-jline ash-applet real-coreutils real-heavy env-inherit token-rotate credadmin instance-warn jlink-modules guestjob-quote guest-launch sshkeys; do
    spec=${EXT_LABELS[$label]}
    script=${spec%%|*}
    run_ext_one "$label" "$script" "$SBROOT/ext-$label" "$EXTDIR" &
    EXT_PIDS+=("$!")
done
wait "${EXT_PIDS[@]}" 2>/dev/null || true

# 結果を元の順序で表示・集計
for label in ash-noni ash-cook jline-smoke ash-jline ash-applet dist-smoke real-coreutils real-heavy env-inherit token-rotate credadmin instance-warn jlink-modules guestjob-quote guest-launch sshkeys; do
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
echo "===== regression result ====="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
if [ $FAIL -gt 0 ]; then
    echo "  failed: ${FAIL_NAMES[*]}"
    exit 1
fi
exit 0
