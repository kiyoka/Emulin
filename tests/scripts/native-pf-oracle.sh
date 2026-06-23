#!/usr/bin/env bash
# native-pf-oracle.sh — issue #392 戦略B 4e: anonymous mmap demand paging の equivalence oracle
#
# 戦略B (#392) は native(KVM/WHP) backend の anonymous mmap を reserve-only (PTE not-present) に
# し、guest が触れた時の #PF → faultIn で demand 割当する (EMULIN_NATIVE_PF gate)。本 oracle は
# -nostdlib 静的 ELF を 3 通りで実行し stdout が byte 一致することを検証する:
#
#   1. software                          — 基準 (eager 割当、第一級 canonical oracle)
#   2. native + EMULIN_NO_NATIVE_PF=1     — eager 強制 (旧 default = escape hatch。eager 経路の非回帰確認)
#   3. native (既定 = EMULIN_NATIVE_PF ON) — ★戦略B demand paging (reserve-only + #PF faultIn、2026-06-23 新 default)
#
# 2 と 3 が両方 1 (software) と一致すれば「demand paging は software と等価」かつ「eager escape hatch も
# 不変 (byte-identical)」が同時に言える。テスト binary は確保アドレスに依らない観測 (書いた/読んだ
# byte) だけを出力するので 3 通りで同一になる。
#
# KVM が無い環境 (/dev/kvm 不可、CI / Windows 等) では SKIP。native-oracle.sh と同じ流儀。
# 終了コード: 0=PASS, 1=FAIL, 2=SKIP。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
NAME=native-pf-oracle

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP $NAME : Emulin not built"
    exit 2
fi
# KVM 可用性 (read+write)
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP $NAME : /dev/kvm not accessible (KVM 無し / kvm group 未加入)"
    exit 2
fi

ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"

SB=$(mktemp -d -t emulin-native-pf.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin" "$SB/tmp"

JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

PASS=0 FAIL=0
FAILED=()

# pf_oracle_one <binname> <expect_substr>
#   software / native(eager) / native(NATIVE_PF) の 3 通りで実行し、3 つとも byte 一致 + 期待値を検証。
pf_oracle_one() {
    local bin=$1 expect=$2
    local src="$ROOT/binaries/bin/$bin"
    if [ ! -f "$src" ]; then
        echo "SKIP $NAME/$bin : not built (run 'make -C tests/binaries')"
        return 2
    fi
    cp "$src" "$SB/bin/$bin"

    local soft soft_rc nat nat_rc pf pf_rc
    soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" < /dev/null 2>/dev/null )
    soft_rc=$?
    nat=$(  cd "$SB" && EMULIN_BACKEND=native EMULIN_NO_NATIVE_PF=1 java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" < /dev/null 2>/dev/null )
    nat_rc=$?
    pf=$(   cd "$SB" && EMULIN_BACKEND=native EMULIN_NATIVE_PF=1 java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" < /dev/null 2>/dev/null )
    pf_rc=$?

    if [ "$soft_rc" != 0 ]; then echo "FAIL $NAME/$bin : software rc=$soft_rc"; FAIL=$((FAIL+1)); FAILED+=("$bin:soft-rc"); return 1; fi
    if [ "$nat_rc"  != 0 ]; then echo "FAIL $NAME/$bin : native(eager) rc=$nat_rc"; FAIL=$((FAIL+1)); FAILED+=("$bin:nat-rc"); return 1; fi
    if [ "$pf_rc"   != 0 ]; then echo "FAIL $NAME/$bin : native(NATIVE_PF) rc=$pf_rc"; FAIL=$((FAIL+1)); FAILED+=("$bin:pf-rc"); return 1; fi
    if [ "$soft" != "$nat" ]; then
        echo "FAIL $NAME/$bin : native(eager) stdout != software stdout"
        echo "--- software ---"; printf '%s\n' "$soft" | head -12
        echo "--- native(eager) ---"; printf '%s\n' "$nat" | head -12
        FAIL=$((FAIL+1)); FAILED+=("$bin:nat-diff"); return 1
    fi
    if [ "$soft" != "$pf" ]; then
        echo "FAIL $NAME/$bin : native(NATIVE_PF) stdout != software stdout"
        echo "--- software ---"; printf '%s\n' "$soft" | head -12
        echo "--- native(NATIVE_PF) ---"; printf '%s\n' "$pf" | head -12
        FAIL=$((FAIL+1)); FAILED+=("$bin:pf-diff"); return 1
    fi
    if ! printf '%s' "$pf" | grep -qF "$expect"; then
        echo "FAIL $NAME/$bin : output に '$expect' 無し"
        FAIL=$((FAIL+1)); FAILED+=("$bin:expect"); return 1
    fi
    echo "  ok $bin : software == native(eager) == native(NATIVE_PF) (byte 一致、'$expect')"
    PASS=$((PASS+1))
    return 0
}

# pf_oracle_two <binname> <expect_substr>
#   ≥2GB alloc_huge は native(eager) では ENOMEM なので、software と native(NATIVE_PF) の 2-way で比較する。
pf_oracle_two() {
    local bin=$1 expect=$2
    local src="$ROOT/binaries/bin/$bin"
    if [ ! -f "$src" ]; then
        echo "SKIP $NAME/$bin : not built (run 'make -C tests/binaries')"
        return 2
    fi
    cp "$src" "$SB/bin/$bin"

    local soft soft_rc pf pf_rc
    soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" < /dev/null 2>/dev/null )
    soft_rc=$?
    pf=$(   cd "$SB" && EMULIN_BACKEND=native EMULIN_NATIVE_PF=1 java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" < /dev/null 2>/dev/null )
    pf_rc=$?

    if [ "$soft_rc" != 0 ]; then echo "FAIL $NAME/$bin : software rc=$soft_rc"; FAIL=$((FAIL+1)); FAILED+=("$bin:soft-rc"); return 1; fi
    if [ "$pf_rc"   != 0 ]; then echo "FAIL $NAME/$bin : native(NATIVE_PF) rc=$pf_rc"; FAIL=$((FAIL+1)); FAILED+=("$bin:pf-rc"); return 1; fi
    if [ "$soft" != "$pf" ]; then
        echo "FAIL $NAME/$bin : native(NATIVE_PF) stdout != software stdout"
        echo "--- software ---"; printf '%s\n' "$soft" | head -12
        echo "--- native(NATIVE_PF) ---"; printf '%s\n' "$pf" | head -12
        FAIL=$((FAIL+1)); FAILED+=("$bin:pf-diff"); return 1
    fi
    if ! printf '%s' "$pf" | grep -qF "$expect"; then
        echo "FAIL $NAME/$bin : output に '$expect' 無し"
        FAIL=$((FAIL+1)); FAILED+=("$bin:expect"); return 1
    fi
    echo "  ok $bin : software == native(NATIVE_PF) (byte 一致、'$expect')  [2-way: ≥2GB は eager=ENOMEM]"
    PASS=$((PASS+1))
    return 0
}

echo "===== native-pf-oracle: 戦略B 4e demand paging equivalence (issue #392) ====="
# sys_pf_demand64: reserve-only mmap の demand zero-fill + write/read (A) / head munmap の entry
#   分割保持 (B) / 大領域内 MAP_FIXED guard の entry 非縮小 (C) / cage 内 80 sub-region 越しの gap page を
#   faultIn の maxReserveLen-bounded 下方走査が解決 (D、review #6 の固定 64 cap 回帰を検出)。3-way。
pf_oracle_one sys_pf_demand64 "PF_DEMAND ok"
# sys_pf_huge64: ≥2GB anonymous mmap の alloc_huge reserve-only + demand 割当 (V8 cage 相当)。
#   native(eager) は ENOMEM なので 2-way (software vs NATIVE_PF)。
pf_oracle_two sys_pf_huge64 "PF_HUGE ok"

echo
echo "===== native-pf-oracle result: PASS=$PASS FAIL=$FAIL ====="
if [ $FAIL -gt 0 ]; then
    printf '  failed: %s\n' "${FAILED[@]}"
    exit 1
fi
[ $PASS -eq 0 ] && { echo "(no test ran)"; exit 2; }
exit 0
