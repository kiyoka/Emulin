/* insn_loop64.c — issue #520: LOOP/LOOPE/LOOPNE (E0-E2) 実装、issue #521: JECXZ (67 E3) の
 * ECX 判定の回帰。旧実装は E0-E2 が unknown opcode でプロセス死、E3 は 67 prefix を無視して
 * 常に RCX 全 64bit で判定 (JECXZ が JRCXZ 化) していた。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

void _start(void) {
    long counter, rcx_out, branched;

    /* LOOP: RCX=5 → 5 回転して RCX=0 */
    counter = 0;
    __asm__ volatile(
        "movq $5, %%rcx\n\t"
        "1:\n\t"
        "incq %0\n\t"
        "loop 1b\n\t"
        "movq %%rcx, %1\n\t"
        : "+r"(counter), "=r"(rcx_out) : : "rcx", "cc");
    put("loop: n="); put_dec(counter); put(" rcx="); put_dec(rcx_out); put("\n");

    /* LOOPE: ZF=1 を保つ body (test al,al with al=0) → RCX=3 が尽きるまで回る */
    counter = 0;
    __asm__ volatile(
        "movq $3, %%rcx\n\t"
        "xorb %%al, %%al\n\t"
        "1:\n\t"
        "incq %0\n\t"
        "testb %%al, %%al\n\t"     /* ZF=1 */
        "loope 1b\n\t"
        "movq %%rcx, %1\n\t"
        : "+r"(counter), "=r"(rcx_out) : : "rax", "rcx", "cc");
    put("loope(zf1): n="); put_dec(counter); put(" rcx="); put_dec(rcx_out); put("\n");

    /* LOOPE: ZF=0 (al=1) → 1 回で抜ける (RCX は 1 回分だけ減る) */
    counter = 0;
    __asm__ volatile(
        "movq $5, %%rcx\n\t"
        "movb $1, %%al\n\t"
        "1:\n\t"
        "incq %0\n\t"
        "testb %%al, %%al\n\t"     /* ZF=0 */
        "loope 1b\n\t"
        "movq %%rcx, %1\n\t"
        : "+r"(counter), "=r"(rcx_out) : : "rax", "rcx", "cc");
    put("loope(zf0): n="); put_dec(counter); put(" rcx="); put_dec(rcx_out); put("\n");

    /* LOOPNE: ZF=0 を保つ → RCX=4 が尽きるまで回る */
    counter = 0;
    __asm__ volatile(
        "movq $4, %%rcx\n\t"
        "movb $1, %%al\n\t"
        "1:\n\t"
        "incq %0\n\t"
        "testb %%al, %%al\n\t"     /* ZF=0 */
        "loopne 1b\n\t"
        "movq %%rcx, %1\n\t"
        : "+r"(counter), "=r"(rcx_out) : : "rax", "rcx", "cc");
    put("loopne(zf0): n="); put_dec(counter); put(" rcx="); put_dec(rcx_out); put("\n");

    /* JECXZ: RCX=0x100000000 (ECX==0) → 分岐する (issue #521。旧実装は RCX!=0 で分岐せず) */
    __asm__ volatile(
        "movq $0x100000000, %%rcx\n\t"
        "jecxz 1f\n\t"
        "movq $0, %0\n\t"
        "jmp 2f\n\t"
        "1: movq $1, %0\n\t"
        "2:\n\t"
        : "=r"(branched) : : "rcx", "cc");
    put("jecxz(ecx0): branched="); put_dec(branched); put("\n");

    /* JRCXZ: 同じ RCX=0x100000000 → 分岐しない (RCX!=0) */
    __asm__ volatile(
        "movq $0x100000000, %%rcx\n\t"
        "jrcxz 1f\n\t"
        "movq $0, %0\n\t"
        "jmp 2f\n\t"
        "1: movq $1, %0\n\t"
        "2:\n\t"
        : "=r"(branched) : : "rcx", "cc");
    put("jrcxz(rcx!=0): branched="); put_dec(branched); put("\n");

    put("LOOP_JECXZ ok\n");
    sys_exit(0);
}
