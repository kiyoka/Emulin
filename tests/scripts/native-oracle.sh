#!/usr/bin/env bash
# native-oracle.sh — issue #221 step 3d-2: KVM native backend の equivalence oracle
#
# hello64 (-nostdlib 静的 ELF) を software backend と native (KVM) backend の両方で
# 実行し、stdout が byte 一致することを検証する。native backend は #221 の核心:
# guest を実 vCPU で走らせ syscall だけ emulin の call_amd64 にトラップする。
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
HELLO=$ROOT/binaries/bin/hello64
if [ ! -f "$HELLO" ]; then
    echo "SKIP $NAME : hello64 not built (run 'make -C tests/binaries')"
    exit 2
fi

# ASM jar (JIT 用、native では不要だが classpath 維持)
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"

SB=$(mktemp -d -t emulin-native-oracle.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin"
cp "$HELLO" "$SB/bin/hello64"

JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/hello64 2>/dev/null )
soft_rc=$?
nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/hello64 2>/dev/null )
nat_rc=$?

if [ "$soft_rc" != 0 ]; then
    echo "FAIL $NAME : software backend rc=$soft_rc"
    exit 1
fi
if [ "$nat_rc" != 0 ]; then
    echo "FAIL $NAME : native backend rc=$nat_rc"
    exit 1
fi
if [ "$soft" != "$nat" ]; then
    echo "FAIL $NAME : native stdout != software stdout"
    echo "--- software ---"; printf '%s\n' "$soft" | head -10
    echo "--- native ---";   printf '%s\n' "$nat"  | head -10
    exit 1
fi
# 期待値も確認 (hello world を含む)
if ! printf '%s' "$nat" | grep -q "hello world"; then
    echo "FAIL $NAME : output に 'hello world' 無し"
    exit 1
fi

echo "PASS $NAME : hello64 native(KVM)==software (byte 一致、'hello world')"
exit 0
