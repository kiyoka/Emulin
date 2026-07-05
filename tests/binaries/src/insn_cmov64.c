/* insn_cmov64.c — issue #522: CMOVcc r32 形が条件不成立 (not-taken) でも dest の
 * 上位 32bit をゼロ化する回帰 (SDM 3.4.1.1: 32bit 書込は常に zero-extend)。
 * 旧実装は not-taken で何もせず、上位 32bit が残っていた。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

void _start(void) {
    unsigned long out;

    /* r32 not-taken: OF=0 で cmovo → dest 上位 32bit ゼロ化のみ */
    __asm__ volatile(
        "movq $0xAAAAAAAA55555555, %%rax\n\t"
        "movq $0x1111111122222222, %%rbx\n\t"
        "xorl %%ecx, %%ecx\n\t"          /* OF=0 */
        "cmovo %%ebx, %%eax\n\t"         /* not taken */
        "movq %%rax, %0\n\t"
        : "=r"(out) : : "rax", "rbx", "rcx", "cc");
    put("cmovo/r32/not-taken: "); put_hex(out); put("\n");

    /* r32 taken: ZF=1 で cmovz → src 下位 32bit + zero-extend */
    __asm__ volatile(
        "movq $0xAAAAAAAA55555555, %%rax\n\t"
        "movq $0x1111111122222222, %%rbx\n\t"
        "xorl %%ecx, %%ecx\n\t"          /* ZF=1 */
        "cmovz %%ebx, %%eax\n\t"         /* taken */
        "movq %%rax, %0\n\t"
        : "=r"(out) : : "rax", "rbx", "rcx", "cc");
    put("cmovz/r32/taken: "); put_hex(out); put("\n");

    /* r64 not-taken: 上位も保存 (対照実験) */
    __asm__ volatile(
        "movq $0xAAAAAAAA55555555, %%rax\n\t"
        "movq $0x1111111122222222, %%rbx\n\t"
        "xorl %%ecx, %%ecx\n\t"          /* OF=0 */
        "cmovo %%rbx, %%rax\n\t"         /* not taken (REX.W) */
        "movq %%rax, %0\n\t"
        : "=r"(out) : : "rax", "rbx", "rcx", "cc");
    put("cmovo/r64/not-taken: "); put_hex(out); put("\n");

    /* r16 taken: 16bit 幅で書き、上位 48bit は保存 (旧実装は r32 として実行していた) */
    __asm__ volatile(
        "movq $0xAAAAAAAA55555555, %%rax\n\t"
        "movq $0x1111111122222222, %%rbx\n\t"
        "xorl %%ecx, %%ecx\n\t"          /* ZF=1 */
        "cmovzw %%bx, %%ax\n\t"          /* taken (66 prefix) */
        "movq %%rax, %0\n\t"
        : "=r"(out) : : "rax", "rbx", "rcx", "cc");
    put("cmovz/r16/taken: "); put_hex(out); put("\n");

    /* r16 not-taken: 全 bit 保存 */
    __asm__ volatile(
        "movq $0xAAAAAAAA55555555, %%rax\n\t"
        "movq $0x1111111122222222, %%rbx\n\t"
        "xorl %%ecx, %%ecx\n\t"          /* OF=0 */
        "cmovow %%bx, %%ax\n\t"          /* not taken (66 prefix) */
        "movq %%rax, %0\n\t"
        : "=r"(out) : : "rax", "rbx", "rcx", "cc");
    put("cmovo/r16/not-taken: "); put_hex(out); put("\n");

    put("CMOV_ZEXT ok\n");
    sys_exit(0);
}
