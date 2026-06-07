#!/usr/bin/env bash
# native-oracle.sh — issue #221 step 3d-2 / 3d-2c: KVM native backend の equivalence oracle
#
# -nostdlib 静的 ELF を software backend と native (KVM) backend の両方で実行し、stdout が
# byte 一致することを検証する。native backend は #221 の核心: guest を実 vCPU (ring-3) で
# 走らせ syscall だけ emulin の call_amd64 にトラップする。
#
#   - hello64     : write/exit のみ (3d-2、ring-0→3d-2c-1 で ring-3)
#   - argvdump64  : argc/argv/envp を stack から読んで dump (3d-2c-2 の初期 stack 構築検証)
#   - simd64      : SSE (movdqu/paddd) で CR4.OSFXSR を検証 (3d-2c-3)
#   - hello_static64 : ★初の実 glibc 静的 binary (brk/arch_prctl/mprotect 等、3d-2c-4)
#
# KVM が無い環境 (/dev/kvm 不可、bare-metal でない CI 等) では SKIP。
# 終了コード: 0=PASS, 1=FAIL, 2=SKIP。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
NAME=native-oracle

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP $NAME : Emulin not built"
    exit 2
fi
# KVM 可用性 (read+write)
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP $NAME : /dev/kvm not accessible (KVM 無し / kvm group 未加入)"
    exit 2
fi

# ASM jar (JIT 用、native では不要だが classpath 維持)
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"

SB=$(mktemp -d -t emulin-native-oracle.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin"

JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

# oracle_one <binname> <expect_substr> [guest args...]
#   tests/binaries/bin/<binname> を software と native で実行し byte 一致 + 期待値を検証。
oracle_one() {
    local bin=$1 expect=$2; shift 2
    local src="$ROOT/binaries/bin/$bin"
    if [ ! -f "$src" ]; then
        echo "SKIP $NAME/$bin : not built (run 'make -C tests/binaries')"
        return 2
    fi
    cp "$src" "$SB/bin/$bin"

    local soft soft_rc nat nat_rc
    soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" "$@" 2>/dev/null )
    soft_rc=$?
    nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" "$@" 2>/dev/null )
    nat_rc=$?

    if [ "$soft_rc" != 0 ]; then echo "FAIL $NAME/$bin : software rc=$soft_rc"; return 1; fi
    if [ "$nat_rc"  != 0 ]; then echo "FAIL $NAME/$bin : native rc=$nat_rc";   return 1; fi
    if [ "$soft" != "$nat" ]; then
        echo "FAIL $NAME/$bin : native stdout != software stdout"
        echo "--- software ---"; printf '%s\n' "$soft" | head -12
        echo "--- native ---";   printf '%s\n' "$nat"  | head -12
        return 1
    fi
    if ! printf '%s' "$nat" | grep -qF "$expect"; then
        echo "FAIL $NAME/$bin : output に '$expect' 無し"
        return 1
    fi
    echo "  ok $bin : native(KVM,ring3)==software (byte 一致、'$expect')"
    return 0
}

fail=0 ran=0
# hello64: write/exit
oracle_one hello64 "hello world";          r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# argvdump64: 初期 stack (argc/argv/envp) の検証。引数を渡して argc/argv を確認。
oracle_one argvdump64 "argv[1]=foo" foo bar; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# simd64: SSE (paddd) の検証。CR4.OSFXSR 未設定だと native で #UD→triple fault。
oracle_one simd64 "simd:42,42";            r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# hello_static64: ★初の実 glibc 静的 binary。brk/arch_prctl/mprotect/getrandom 等の
#   glibc 起動 syscall + page_offset コピー修正の総合検証。
oracle_one hello_static64 "hello static";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

if [ "$fail" = 1 ]; then echo "FAIL $NAME"; exit 1; fi
if [ "$ran"  = 0 ]; then echo "SKIP $NAME : 対象 binary 未ビルド"; exit 2; fi
echo "PASS $NAME : hello64 + argvdump64 + simd64 + hello_static64 native(KVM,ring3)==software"
exit 0
