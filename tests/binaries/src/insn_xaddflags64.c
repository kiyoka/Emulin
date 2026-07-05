/* insn_xaddflags64.c — issue #526: CMPXCHG r/m8 (0F B0) と XADD の 8/16bit 形の
 * フラグ計算の回帰。旧実装は CMPXCHG8 が ZF/SF のみ (CF/OF/AF/PF 未計算)、
 * XADD8/XADD16 が AF/PF 未計算だった。算術フラグ (OF|SF|ZF|AF|PF|CF = 0x8D5) を
 * pushfq で読み出して比較する。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

#define FMASK 0x8D5UL   /* OF(0x800) SF(0x80) ZF(0x40) AF(0x10) PF(0x4) CF(0x1) */

void _start(void) {
    unsigned long fl, val;

    /* XADD8: 0x0f + 0x01 → 0x10 (下位 nibble キャリー = AF=1) */
    __asm__ volatile(
        "movb $0x0f, %%al\n\t"
        "movb $0x01, %%bl\n\t"
        "xaddb %%bl, %%al\n\t"
        "pushfq\n\t pop %0\n\t"
        "movzbq %%al, %1\n\t"
        : "=r"(fl), "=r"(val) : : "rax", "rbx", "cc");
    put("xadd8(0f+01): al="); put_hex(val); put(" fl="); put_hex(fl & FMASK); put("\n");

    /* XADD8: 0xff + 0x01 → 0x00 (CF=1 ZF=1 AF=1 PF=1) */
    __asm__ volatile(
        "movb $0xff, %%al\n\t"
        "movb $0x01, %%bl\n\t"
        "xaddb %%bl, %%al\n\t"
        "pushfq\n\t pop %0\n\t"
        : "=r"(fl) : : "rax", "rbx", "cc");
    put("xadd8(ff+01): fl="); put_hex(fl & FMASK); put("\n");

    /* XADD16: 0x000f + 0x0001 → AF=1 */
    __asm__ volatile(
        "movw $0x000f, %%ax\n\t"
        "movw $0x0001, %%bx\n\t"
        "xaddw %%bx, %%ax\n\t"
        "pushfq\n\t pop %0\n\t"
        : "=r"(fl) : : "rax", "rbx", "cc");
    put("xadd16(0f+01): fl="); put_hex(fl & FMASK); put("\n");

    /* XADD16: 0xffff + 0x0001 → 0 (CF=1 ZF=1 AF=1 PF=1) */
    __asm__ volatile(
        "movw $0xffff, %%ax\n\t"
        "movw $0x0001, %%bx\n\t"
        "xaddw %%bx, %%ax\n\t"
        "pushfq\n\t pop %0\n\t"
        : "=r"(fl) : : "rax", "rbx", "cc");
    put("xadd16(ffff+01): fl="); put_hex(fl & FMASK); put("\n");

    /* CMPXCHG8 不一致: AL=0x01 vs BL=0x0f → CMP 0x01,0x0f (borrow: CF=1 AF=1) */
    __asm__ volatile(
        "movb $0x01, %%al\n\t"
        "movb $0x0f, %%bl\n\t"
        "movb $0x77, %%cl\n\t"
        "cmpxchgb %%cl, %%bl\n\t"
        "pushfq\n\t pop %0\n\t"
        "movzbq %%al, %1\n\t"
        : "=r"(fl), "=r"(val) : : "rax", "rbx", "rcx", "cc");
    put("cmpxchg8(ne): al="); put_hex(val); put(" fl="); put_hex(fl & FMASK); put("\n");

    /* CMPXCHG8 一致: AL=0x42 vs BL=0x42 → ZF=1、BL←CL */
    __asm__ volatile(
        "movb $0x42, %%al\n\t"
        "movb $0x42, %%bl\n\t"
        "movb $0x99, %%cl\n\t"
        "cmpxchgb %%cl, %%bl\n\t"
        "pushfq\n\t pop %0\n\t"
        "movzbq %%bl, %1\n\t"
        : "=r"(fl), "=r"(val) : : "rax", "rbx", "rcx", "cc");
    put("cmpxchg8(eq): bl="); put_hex(val); put(" fl="); put_hex(fl & FMASK); put("\n");

    put("XADD_CMPXCHG_FLAGS ok\n");
    sys_exit(0);
}
