#!/usr/bin/env bash
# bench-native.sh — issue #221: native (KVM) vs software backend の compute 性能比較。
#
# bench64 (compute-heavy ループ、syscall 極小) を両 backend で実行し、各 backend の
#   iter/sec = N / (total(N) - total(baseline)) を求めて比較する。compute 時間から JVM 起動 +
#   KVM setup を baseline 差分で除去するので「非 syscall 命令を実 CPU で走らせる native」対
#   「per-instruction emulation の software」の純粋な実行速度になる = #221 核心仮説の実測。
#
#   native は software の数百倍速いため、両者に同一 N を使うと native の compute が計測ノイズ
#   以下になる。よって backend 別に N を変える: software は moderate (既定 5e7、~26s)、native は
#   huge (既定 1e10、~24s)。正しさは software の N で両者の出力を diff。
#
# 環境変数: BENCH_N_SOFT (既定 50000000) / BENCH_N_NAT (既定 10000000000) / BENCH_BASE_N (既定 1000)。
# /dev/kvm 不可なら SKIP。go/no-go 報告用なので pass/fail でなく数値を出す。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
N_SOFT=${BENCH_N_SOFT:-50000000}
N_NAT=${BENCH_N_NAT:-10000000000}
BASE_N=${BENCH_BASE_N:-1000}

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then echo "SKIP : Emulin not built"; exit 2; fi
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then echo "SKIP : /dev/kvm not accessible"; exit 2; fi
BENCH=$ROOT/binaries/bin/bench64
if [ ! -f "$BENCH" ]; then echo "SKIP : bench64 not built (make -C tests/binaries)"; exit 2; fi

ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
SB=$(mktemp -d -t emulin-bench.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin"
cp "$BENCH" "$SB/bin/bench64"
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

# run <backend> <n> <outfile> → 出力を outfile に、所要秒を echo。
run() {
    local backend=$1 n=$2 outfile=$3 t0 t1
    t0=$(date +%s.%N)
    ( cd "$SB" && EMULIN_BACKEND=$backend java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/bench64 "$n" < /dev/null 2>/dev/null ) > "$outfile"
    t1=$(date +%s.%N)
    echo "$(echo "$t1 - $t0" | bc)"
}

echo "===== issue #221 native vs software compute benchmark ====="
echo "  bench64 = FNV ハッシュ N 反復 (syscall: argv 読み + write×数 + exit のみ = trap 極小)"
echo "  software N=$N_SOFT / native N=$N_NAT / baseline N=$BASE_N"
echo

soft_base=$(run software "$BASE_N" "$SB/o.sb")
soft_full=$(run software "$N_SOFT" "$SB/o.sf")
nat_base=$( run native   "$BASE_N" "$SB/o.nb")
nat_full=$( run native   "$N_NAT"  "$SB/o.nf")
nat_soft=$( run native   "$N_SOFT" "$SB/o.ns")   # 正しさ照合用 (software と同一 N)

# 正しさ: 同一 N (=N_SOFT) で native==software か
if ! diff -q "$SB/o.sf" "$SB/o.ns" >/dev/null; then
    echo "!! CORRECTNESS MISMATCH (N=$N_SOFT)"
    echo "  software: $(cat "$SB/o.sf")"; echo "  native  : $(cat "$SB/o.ns")"
    exit 1
fi
echo "correctness: native==software  ($(tail -1 "$SB/o.sf"))"
echo

soft_compute=$(echo "$soft_full - $soft_base" | bc)
nat_compute=$( echo "$nat_full - $nat_base"   | bc)
soft_ips=$(echo "scale=0; $N_SOFT / $soft_compute" | bc)
nat_ips=$( echo "scale=0; $N_NAT  / $nat_compute"  | bc)
speedup=$( echo "scale=1; $nat_ips / $soft_ips"    | bc)

printf "  software : N=%-12s compute=%7.2fs  => %12s iter/s\n" "$N_SOFT" "$soft_compute" "$soft_ips"
printf "  native   : N=%-12s compute=%7.2fs  => %12s iter/s\n" "$N_NAT"  "$nat_compute"  "$nat_ips"
echo
echo "  ===> native compute は software の ${speedup}x 速い (純粋な命令実行速度)"
echo
echo "  ※ break-even: native の唯一の追加コストは syscall trap。compute が ~${speedup}x 速い"
echo "     ので、syscall 間の命令数が trap コスト/命令節約 を上回る workload で native が勝つ。"
echo "  ※ WSL2 nested 計測。bare-metal/WHP では trap が更に小さく break-even が下がる見込み。"
exit 0
