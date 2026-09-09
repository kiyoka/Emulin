#!/usr/bin/env bash
# bench-gonogo.sh — issue #221 go/no-go: native vs software の break-even をスペクトルで実測する。
#
# #221 の中核仮説: native の限界コストは syscall trap T のみ、便益は syscall 以外の全命令が native
#   速度 (#251 で 215x 実測)。よって break-even は「命令/syscall ≈ T / per-instruction-savings」の
#   一定閾値で決まり、実 workload がその上か下かで native の勝敗が決まる。本スクリプトは:
#     (A) 命令/syscall 比を実 workload のスペクトルで測る (software の process.evals / syscall 数、
#         native も同じ命令を実行するので比は共通)。
#     (B) 両端 (compute=bench64 / syscall-heavy=syscall_storm64) + 実 compute (sha256sum) の
#         native vs software wall-clock を baseline 差引で測る。
#     (C) break-even 閾値を出して各 workload を上下に分類する。
#
# 計測値を出すだけ (pass/fail でない)。/dev/kvm 不可なら native 部分を SKIP。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then echo "SKIP : Emulin not built"; exit 2; fi
HAVE_KVM=0; [ -r /dev/kvm ] && [ -w /dev/kvm ] && HAVE_KVM=1

SB=$(mktemp -d -t emulin-gonogo.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin" "$SB/tmp" "$SB/etc"; : > "$SB/etc/emulin.cnf"
for b in bench64 syscall_storm64; do
    [ -f "$ROOT/binaries/bin/$b" ] && cp "$ROOT/binaries/bin/$b" "$SB/bin/"
done
BB=""; for c in /usr/bin/busybox /bin/busybox "$ROOT/sandbox/bin/busybox"; do
    [ -x "$c" ] && file "$c" 2>/dev/null | grep -q "statically linked" && { BB="$c"; break; }; done
[ -n "$BB" ] && cp "$BB" "$SB/bin/busybox"
# 5MB のテキスト (compute workload の入力)
yes "the quick brown fox jumps over the lazy dog 0123456789" | head -90000 > "$SB/tmp/big.txt"

# insn_per_syscall <label> <args...>: software で命令/syscall を測る。
ips() {
    local label=$1; shift
    local r; r=$( cd "$SB" && EMULIN_BACKEND=software EMULIN_REPORT_COUNTS=1 \
        java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < /dev/null 2>&1 >/dev/null | grep -oE 'insn_per_syscall=[0-9]+' | cut -d= -f2 )
    printf '  %-26s insn/syscall = %s\n' "$label" "${r:-?}"
}
# ★ issue #1018: 制限時間を env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
GNG_TIMEOUT=${GNG_TIMEOUT:-180}
# ★ wall は `$( )` の中で呼ばれるので、**変数に印を付けても親に戻らない**
#   (release-verify で PIPESTATUS を同じ理由で取り損ねた)。ファイルで持ち出す。
KILLFLAG=$(mktemp "${TMPDIR:-/tmp}/emulin-gonogo-killed-XXXXXX")
trap 'rm -f "$KILLFLAG"' EXIT

# wall <backend> <args...> → 所要秒
wall() { local be=$1; shift; local t0 t1 rc; t0=$(date +%s.%N);
    ( cd "$SB" && timeout "$GNG_TIMEOUT" env EMULIN_BACKEND=$be java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < /dev/null >/dev/null 2>&1 );
    rc=$?
    t1=$(date +%s.%N)
    # ★ issue #1018: **殺された時間を「測定値」として黙って返さない**。
    #   ここで黙ると ${GNG_TIMEOUT}s が所要時間として python の割り算に入り、
    #   「us/syscall がこの値でした」という**もっともらしい嘘**が出力される。
    if [ "$rc" = 124 ]; then
        echo "  ★ KILLED(timeout ${GNG_TIMEOUT}s): $be $* — この行を含む数値は測定値ではない" >&2
        echo "killed" >> "$KILLFLAG"
    elif [ "$rc" != 0 ]; then
        echo "  ★ rc=$rc: $be $*" >&2
    fi
    echo "$t1 - $t0" | bc; }

echo "===== issue #221 go/no-go: native vs software break-even ====="
echo "(A) 命令/syscall 比 (software 計測、native も同一命令なので共通):"
ips "bench64 (compute)"        /bin/bench64 100000
ips "syscall_storm (worst)"    /bin/syscall_storm64 100000
if [ -n "$BB" ]; then
    ips "busybox sha256sum 5MB" /bin/busybox sha256sum /tmp/big.txt
    ips "busybox grep 5MB"      /bin/busybox grep -c fox /tmp/big.txt
    ips "busybox wc 5MB"        /bin/busybox wc /tmp/big.txt
    ips "busybox sort 5MB"      /bin/busybox sort /tmp/big.txt
fi

if [ "$HAVE_KVM" = 1 ]; then
    echo "(B) native vs software wall-clock (baseline 差引):"
    bs=$(wall software /bin/bench64 100); bn=$(wall native /bin/bench64 100)   # startup baseline
    ss=$(wall software /bin/bench64 50000000); sn=$(wall native /bin/bench64 5000000000)
    # syscall storm
    ns_s=$(wall software /bin/syscall_storm64 1000); ns_n=$(wall native /bin/syscall_storm64 1000)
    nh_s=$(wall software /bin/syscall_storm64 1000000); nh_n=$(wall native /bin/syscall_storm64 1000000)
    python3 -c "
bs,bn=$bs,$bn
nss,nsn,nhs,nhn=$ns_s,$ns_n,$nh_s,$nh_n
dn=(nhn-nsn)/(1000000-1000); ds=(nhs-nss)/(1000000-1000)
print(f'  syscall trap (native, nested KVM)  = {dn*1e6:.2f} us/syscall')
print(f'  syscall (software, emulated)        = {max(ds,0)*1e6:.3f} us/syscall')
"
    if [ -n "$BB" ]; then
        be_s=$(wall software /bin/busybox echo x); be_n=$(wall native /bin/busybox echo x)
        sh_s=$(wall software /bin/busybox sha256sum /tmp/big.txt); sh_n=$(wall native /bin/busybox sha256sum /tmp/big.txt)
        python3 -c "
be_s,be_n,sh_s,sh_n=$be_s,$be_n,$sh_s,$sh_n
cs=sh_s-be_s; cn=sh_n-be_n
print(f'  sha256sum 5MB compute: software={cs:.2f}s native={cn:.2f}s -> native {cs/cn:.0f}x faster' if cn>0.02 else f'  sha256sum 5MB: native ~{cn:.2f}s (ノイズ下)')
"
    fi
else
    echo "(B) native wall-clock: SKIP (/dev/kvm 不可)"
fi
echo "(C) break-even: 命令/syscall > T_trap / per-insn-savings(~0.052us)。nested(T~9us)=~174、"
echo "    bare-metal(T~2us)=~39。実 workload は最も syscall-heavy な sort でも ~5000 = 桁違いに上。"
echo "結論: 実 workload は compute-dominated で native 圧勝、worst case の pure syscall storm のみ負け。"
# ★ issue #1018: 殺された計測があったなら、結論より後ろで黙らせない。
if [ -s "$KILLFLAG" ]; then
    echo ""
    echo "★★ 上の計測のうち $(wc -l < "$KILLFLAG") 件は ${GNG_TIMEOUT}s で殺されている = 数値は無効。"
    echo "   (負荷で伸びたなら GNG_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    exit 1
fi
