/* sys_riprel_imm64.c — RIP-relative + immediate な ALU memory 命令の正しさ検証
 *
 * issue #138: x86-64 JIT (EMULIN_USE_JIT=1) が `addq $imm, global(%rip)` のような
 * 「RIP-relative メモリ operand + immediate」命令の実効アドレスを imm 分手前に
 * 誤計算し、隣接メモリを破壊するバグがあった。PIE binary の global 変数更新
 * (例: ld.so の counter 加算) で多発する。
 *
 * このテストは明示的 inline asm で 0x83 /n imm8 の RIP-relative form を hot loop
 * (>16 回 = JIT compile 閾値超え) で実行し、global の最終値を検証する。
 * interpreter / JIT どちらでも同じ結果になること:
 *   c_add = 0    + 1*100      = 100
 *   c_sub = 1000 - 3*100      = 700
 *   c_and = 0xFF & 0x7f       = 127   (idempotent、AND imm8 経路の翻訳を exercise)
 *   c_or  = 0    | 2          = 2     (idempotent、OR  imm8 経路の翻訳を exercise)
 *
 * -fno-pie -static でも `sym(%rip)` を明示すれば assembler が RIP-relative
 * (R_X86_64_PC32) で encode するため、バグ命令そのものを再現できる。
 */
#include "sys64.h"

static long c_add = 0;
static long c_sub = 1000;
static long c_and = 0xFF;
static long c_or  = 0;

void _start(void) {
    for (int i = 0; i < 100; i++)
        __asm__ volatile("addq $1, c_add(%%rip)"   ::: "memory", "cc");
    for (int i = 0; i < 100; i++)
        __asm__ volatile("subq $3, c_sub(%%rip)"   ::: "memory", "cc");
    for (int i = 0; i < 100; i++)
        __asm__ volatile("andq $0x7f, c_and(%%rip)" ::: "memory", "cc");
    for (int i = 0; i < 100; i++)
        __asm__ volatile("orq  $2, c_or(%%rip)"    ::: "memory", "cc");

    put("c_add="); put_dec(c_add); put("\n");
    put("c_sub="); put_dec(c_sub); put("\n");
    put("c_and="); put_dec(c_and); put("\n");
    put("c_or=");  put_dec(c_or);  put("\n");
    sys_exit(0);
}
