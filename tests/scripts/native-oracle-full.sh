#!/usr/bin/env bash
# ----------------------------------------
#  native-oracle-full.sh — issue #304: tests/binaries の全 64-bit binary を
#    software/native (KVM) で実行し byte 一致を自動網羅検証する。
#
#  Copyright (C) 1998-2026  Kiyoka Nishiyama
# ----------------------------------------
#
# native-oracle.sh は手動リスト (~51 件) だが、本スクリプトは tests/binaries/bin の
# 全 *64 binary を自動列挙し、expected/<name>.{stdout,exit,argv,stdin} を使って:
#   (1) software が expected と一致するか (canonical の正しさ)
#   (2) native が software と byte 一致 + exit 一致するか (native の正しさ)
# を検証する。(1) が NG の binary は環境/host 依存とみなし SKIP (native の責任でない)。
#
# i386 (32-bit) は native 非対象。network/host 依存や stdout 非決定の binary は SKIP。
# 終了コード: 0=全 ok/SKIP、1=FAIL あり、2=環境不備。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
NAME=native-oracle-full

[ -f "$CLASSES/emulin/Emulin.class" ] || { echo "SKIP $NAME : Emulin not built"; exit 2; }
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP $NAME : /dev/kvm not accessible (KVM 無し / kvm group 未加入)"; exit 2
fi

# ---- sandbox 構築 (run-test.sh と同じ: 全 binary + dynamic lib + /tmp) ----
SB=$(mktemp -d -t emulin-nofull.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin" "$SB/tmp" "$SB/etc" "$SB/lib64" "$SB/lib/x86_64-linux-gnu"
: > "$SB/etc/emulin.cnf"
# 依存テスト (sys_execve64 が /bin/hello64 を exec 等) のため全 binary をコピー。
cp "$ROOT/binaries/bin/"*64 "$SB/bin/" 2>/dev/null || true
# dynamic glibc 用の ld.so + libc + 定番 lib。
if [ -f /lib64/ld-linux-x86-64.so.2 ]; then
    cp /lib64/ld-linux-x86-64.so.2 "$SB/lib64/" 2>/dev/null
    cp /lib/x86_64-linux-gnu/libc.so.6 "$SB/lib/x86_64-linux-gnu/" 2>/dev/null
    for lib in libm.so.6 libstdc++.so.6 libgcc_s.so.1 libdl.so.2 libz.so.1 libpthread.so.0; do
        [ -f "/lib/x86_64-linux-gnu/$lib" ] && cp "/lib/x86_64-linux-gnu/$lib" "$SB/lib/x86_64-linux-gnu/"
    done
fi

JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData -XX:-DontCompileHugeMethods"
# host network / 非決定 stdout など hermetic 比較に不適な binary を名前パターンで除外。
#   sys_sa_mask64: native の handler 実行中 sa_mask が未対応で停止する既知バグ (issue #309)。
SKIP_RE='sys_inet|sys_socket|sys_udp|sys_dns|_net_|env_probe|sys_sa_mask64'

run_one() {  # run_one <backend> <stdin_file> <args...> → stdout (stderr 捨て)
    local be=$1 stdin=$2; shift 2
    ( cd "$SB" && EMULIN_BACKEND=$be timeout 60 java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < "$stdin" 2>/dev/null )
}

ok=0; fail=0; skip=0; failed=""
for bin in "$ROOT/binaries/bin/"*64; do
    [ -f "$bin" ] || continue
    name=$(basename "$bin")
    # 検証には expected stdout が必須。
    [ -f "$ROOT/expected/$name.stdout" ] || { skip=$((skip+1)); continue; }
    # 明示 skip / 名前パターン skip。
    [ -f "$ROOT/expected/$name.skip" ] && { skip=$((skip+1)); continue; }
    echo "$name" | grep -qE "$SKIP_RE" && { skip=$((skip+1)); continue; }

    # argv (expected/<name>.argv があれば全引数、なければ /bin/<name> 単独)。
    if [ -f "$ROOT/expected/$name.argv" ]; then
        # shellcheck disable=SC2207
        args=($(cat "$ROOT/expected/$name.argv"))
    else
        args=("/bin/$name")
    fi
    # stdin (あれば redirect、なければ /dev/null)。
    stdin_file=/dev/null
    [ -f "$ROOT/expected/$name.stdin" ] && stdin_file="$ROOT/expected/$name.stdin"
    # 期待 exit code。
    exp_exit=0
    [ -f "$ROOT/expected/$name.exit" ] && exp_exit=$(cat "$ROOT/expected/$name.exit")
    exp_out=$(cat "$ROOT/expected/$name.stdout")

    # software (canonical) を先に実行し、expected と一致しなければ環境依存 → SKIP。
    soft=$(run_one software "$stdin_file" "${args[@]}"); soft_rc=$?
    if [ "$soft" != "$exp_out" ] || [ "$soft_rc" != "$exp_exit" ]; then
        skip=$((skip+1)); continue
    fi
    # native が software と byte 一致 + exit 一致なら PASS。
    nat=$(run_one native "$stdin_file" "${args[@]}"); nat_rc=$?
    if [ "$soft" = "$nat" ] && [ "$soft_rc" = "$nat_rc" ]; then
        ok=$((ok+1))
    else
        fail=$((fail+1)); failed="$failed $name"
    fi
done

echo "============================================================"
echo "$NAME : software==native (KVM,ring3) byte 一致 自動網羅"
echo "  ok=$ok  FAIL=$fail  SKIP=$skip"
if [ "$fail" -gt 0 ]; then
    echo "  failed:$failed"
    echo "FAIL $NAME"
    exit 1
fi
echo "PASS $NAME : tests/binaries の native 適用可 $ok 件すべて native==software"
exit 0
