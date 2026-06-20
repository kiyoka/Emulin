#!/usr/bin/env bash
# kvm-latency.sh — issue #221 / #304: KVM ring-3 syscall-trap latency 実測ラッパー
#
# KvmSyscallSmoke (ring-3 syscall -> LSTAR HLT -> KVM_RUN round-trip を 200k 回計測し
# min/median/mean/p99/max を出す) を、実行環境を自己文書化しながら走らせる。
#
#   ★ 目的 = bare-metal Linux KVM の trap latency 絶対値の測定 (step 3f)。
#     WSL2 / VM 内 (nested KVM) では VM-exit が L1<->L0 を経由して latency が膨らむ
#     (dev host WSL2 で median ~6.0us)。bare-metal (非 nested) の絶対値は未測で、
#     これが下がるほど break-even (命令/syscall) が下がり native backend が更に有利になる。
#
# 使い方 (bare-metal Linux で):
#   git pull && tests/scripts/kvm-latency.sh
# 出力全体をそのまま貼れば、環境 (bare-metal か nested か) と median が一目で分かる。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

echo "================================================================"
echo " KVM ring-3 syscall-trap latency 計測 (issue #221 step 3f)"
echo "================================================================"

# ---- 1. 実行環境の自己文書化 ----
echo "[env] date     : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "[env] host     : $(hostname 2>/dev/null || echo '?')"
echo "[env] kernel   : $(uname -sr 2>/dev/null || echo '?')"

CPU=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | sed 's/.*: //')
echo "[env] cpu      : ${CPU:-?}"

# WSL 判定 (osrelease に microsoft)
WSL=no
if grep -qiE 'microsoft|wsl' /proc/sys/kernel/osrelease 2>/dev/null; then WSL=yes; fi
echo "[env] WSL      : $WSL"

# hypervisor flag (guest なら立つ = nested KVM)
HV=no
if grep -m1 '^flags' /proc/cpuinfo 2>/dev/null | grep -qw hypervisor; then HV=yes; fi
echo "[env] hypervisor-flag (=VM guest) : $HV"

# systemd-detect-virt があれば併記
if command -v systemd-detect-virt >/dev/null 2>&1; then
  echo "[env] systemd-detect-virt : $(systemd-detect-virt 2>/dev/null || echo none)"
fi

# nested KVM の有効状態 (host が KVM を提供する側のとき意味を持つ)
for p in /sys/module/kvm_intel/parameters/nested /sys/module/kvm_amd/parameters/nested; do
  [ -r "$p" ] && echo "[env] $(basename $(dirname $(dirname "$p")))/nested = $(cat "$p")"
done

# bare-metal 判定
NESTED=no
if [ "$WSL" = yes ] || [ "$HV" = yes ]; then NESTED=yes; fi
if [ "$NESTED" = yes ]; then
  echo "[env] >>> 判定: nested / VM guest = latency は inflated。これは bare-metal 値ではない。<<<"
else
  echo "[env] >>> 判定: bare-metal (非 nested) = ★これが step 3f の最終 trap latency 絶対値。<<<"
fi
echo "----------------------------------------------------------------"

# ---- 2. /dev/kvm 可用性 ----
if [ ! -e /dev/kvm ]; then
  echo "ERROR: /dev/kvm がありません。CPU 仮想化 (VT-x/AMD-V) を BIOS で有効化し、kvm module を load してください。"
  echo "       (Intel: modprobe kvm_intel / AMD: modprobe kvm_amd。Cloud VM なら nested virt を有効化)"
  exit 2
fi
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "ERROR: /dev/kvm に read/write 権限がありません。次のいずれかを実行:"
  echo "       sudo chmod 666 /dev/kvm                 # 一時的"
  echo "       sudo usermod -aG kvm \"\$USER\" && (再ログイン)  # 恒久"
  ls -l /dev/kvm
  exit 2
fi
echo "[kvm] /dev/kvm OK ($(ls -l /dev/kvm | awk '{print $1, $3, $4}'))"

# ---- 3. ビルド確認 ----
if [ ! -f "$CLASSES/emulin/KvmSyscallSmoke.class" ]; then
  echo "[build] target/classes が無いので mvn compile します ..."
  ( cd "$PROJECT" && mvn -q -o compile ) || { echo "ERROR: mvn compile 失敗"; exit 2; }
fi
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"

# ---- 4. 計測本体 (CPU を 1 物理コアに固定して jitter を抑える。taskset があれば使う) ----
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"
PIN=""
if command -v taskset >/dev/null 2>&1; then PIN="taskset -c 1"; echo "[run] taskset -c 1 で CPU 固定 (jitter 抑制)"; fi
echo "----------------------------------------------------------------"
$PIN java $JOPT -cp "$CP" emulin.KvmSyscallSmoke
RC=$?
echo "----------------------------------------------------------------"
echo "[done] rc=$RC  — 上の '(A) ... median=' が bare-metal の trap latency 絶対値。"
echo "       break-even 行 (命令/syscall) も併記。実 workload (sort ~5000 / sha256sum ~3.4M 命令/syscall)"
echo "       が break-even を桁違いに上回れば native 圧勝 = go/no-go の最終確認。"
exit $RC
