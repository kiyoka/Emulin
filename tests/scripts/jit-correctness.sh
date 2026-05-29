#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/jit-correctness.sh
#
#  x86-64 JIT (EMULIN_USE_JIT=1) の正しさ smoke。
#  curated な 64-bit テスト群を JIT 有効で再実行し、interpreter と同じ
#  expected stdout になるか検証する (run-test.sh を EMULIN_USE_JIT=1 で呼ぶ)。
#
#  特に sys_riprel_imm64 は issue #138 の回帰固定:
#  「RIP-relative メモリ operand + immediate」(addq $imm, global(%rip)) の
#  実効アドレス誤計算 (imm 分手前を破壊) を検出する。
#
#  PASS/FAIL 行を出力 (run-fast.sh が集計)。いずれか FAIL で exit 1。
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)

# JIT で再検証する 64-bit テスト群 (syscall / glibc / SIMD/AES / PIE / pthread を網羅)
TESTS=(
    sys_riprel_imm64          # issue #138 回帰固定 (RIP-rel + imm)
    hello64 echo_stdin64 fileio64 argvdump64 myls64
    sys_getdents64 sys_fork64 sys_execve64 sys_mmap64 sys_fstat64 sys_lseek64
    hello_static64 ctype_static64 bb_decode_static64 aesni_static64 sse_audit64
    hello_dyn64 printf_dyn64 regex_dyn64 pie_dyn64 pthread_basic_dyn64
)

PASS=0
FAIL=0
SKIP=0
FAILED=()

for t in "${TESTS[@]}"; do
    if [ ! -f "$ROOT/binaries/bin/$t" ]; then
        echo "SKIP    jit-$t (binary not built)"
        SKIP=$((SKIP+1)); continue
    fi
    sb=$(mktemp -d -t emulin-jit-sb.XXXXXX)
    if SANDBOX_DIR="$sb" EMULIN_USE_JIT=1 bash "$ROOT/scripts/run-test.sh" "$t" >/dev/null 2>&1; then
        echo "PASS    jit-$t"; PASS=$((PASS+1))
    else
        echo "FAIL    jit-$t"; FAIL=$((FAIL+1)); FAILED+=("jit-$t")
    fi
    rm -rf "$sb" 2>/dev/null || true
done

echo ""
echo "===== JIT correctness smoke: PASS=$PASS FAIL=$FAIL SKIP=$SKIP (total=$((PASS+FAIL+SKIP))) ====="
if [ "$FAIL" -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
    exit 1
fi
exit 0
