#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/run-network.sh
#
#  ネットワーク関連テストだけを実行する subset。
#    - binary tests: sockpair*  (socketpair AF_UNIX)
#    - real-coreutils: wget-* / curl-http / curl-ver / curl-https
#    - real-heavy: ssl-* (OpenSSL)
#  ネットワーク機能の作業中の素早い検証用。
#
#  HTTPS が遅い (~50s) ので除外したいときは CASE_FILTER 直接指定:
#    CASE_FILTER='wget-http$|curl-http|^ssl-' bash tests/scripts/real-coreutils.sh
#  などで個別ケースだけ走らせる方が速い。
#
#  終了コード: いずれかが FAIL なら 1、全て PASS/SKIP なら 0
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)

PASS=0
FAIL=0
SKIP=0
FAIL_NAMES=()

RESULTDIR=$(mktemp -d -t emulin-netrun.XXXXXX)
SBROOT=$(mktemp -d -t emulin-sb.XXXXXX)
EXTDIR=$(mktemp -d -t emulin-extrun.XXXXXX)
trap 'rm -rf "$RESULTDIR" "$SBROOT" "$EXTDIR" 2>/dev/null || true' EXIT

# --- 1) binary tests: sockpair* のみ ---
NAMES=(sockpair_dyn64 sockpair_bidir_dyn64)
run_one_to_dir() {
    local name=$1 outdir=$2 root=$3 sbroot=$4
    SANDBOX_DIR="$sbroot/$name" \
        "$root/scripts/run-test.sh" "$name" > "$outdir/$name.out" 2>&1
    echo $? > "$outdir/$name.rc"
}
export -f run_one_to_dir

printf '%s\n' "${NAMES[@]}" | xargs -P 4 -I{} \
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

# --- 2) real-coreutils: network 関連だけ filter ---
# 対象 case: curl-ver, curl-https (version文字列), wget-http, curl-http,
#   wget-dns, wget-https
echo
echo "----- real-coreutils (network only) -----"
SANDBOX_DIR="$SBROOT/ext-real-coreutils" \
    CASE_FILTER='^(curl-ver|curl-https|wget-http|curl-http|wget-dns|wget-https)$' \
    bash "$ROOT/scripts/real-coreutils.sh" > "$EXTDIR/real-coreutils.out" 2>&1
rc1=$?
cat "$EXTDIR/real-coreutils.out"
while IFS= read -r line; do
    case "$line" in
        "PASS    "*) PASS=$((PASS + 1)) ;;
        "FAIL    "*) n=${line#FAIL    }; FAIL=$((FAIL + 1)); FAIL_NAMES+=("$n") ;;
    esac
done < "$EXTDIR/real-coreutils.out"
if [ "$rc1" = 2 ]; then SKIP=$((SKIP + 1)); fi

# --- 3) real-heavy: ssl-* だけ filter (Python は除く) ---
echo
echo "----- real-heavy (ssl only) -----"
SANDBOX_DIR="$SBROOT/ext-real-heavy" \
    CASE_FILTER='^ssl-' \
    bash "$ROOT/scripts/real-heavy.sh" > "$EXTDIR/real-heavy.out" 2>&1
rc2=$?
cat "$EXTDIR/real-heavy.out"
while IFS= read -r line; do
    case "$line" in
        "PASS    "*) PASS=$((PASS + 1)) ;;
        "FAIL    "*) n=${line#FAIL    }; FAIL=$((FAIL + 1)); FAIL_NAMES+=("$n") ;;
    esac
done < "$EXTDIR/real-heavy.out"
if [ "$rc2" = 2 ]; then SKIP=$((SKIP + 1)); fi

echo
echo "----- sshd Phase 1 MVP smoke -----"
bash "$ROOT/scripts/sshd-smoke.sh" > "$EXTDIR/sshd-smoke.out" 2>&1
rc3=$?
cat "$EXTDIR/sshd-smoke.out"
case $rc3 in
    0) PASS=$((PASS + 1)) ;;
    1) FAIL=$((FAIL + 1)); FAIL_NAMES+=("sshd-smoke") ;;
    2) SKIP=$((SKIP + 1)) ;;
esac

echo
echo "===== network regression result ====="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
if [ $FAIL -gt 0 ]; then
    echo "  failed: ${FAIL_NAMES[*]}"
    exit 1
fi
exit 0
