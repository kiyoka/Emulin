/* insn_muldiv64.c — issue #523: F7 Grp3 の MUL/IMUL/DIV/IDIV 16bit (66 prefix) 形、
 * issue #524: IDIV r/m32 の被除数 EDX:EAX 構成の回帰。
 * 旧実装は 16bit 形が 32bit として実行され (EAX/EDX 上位まで巻き込み)、IDIV32 は EDX を
 * 無視して EAX の符号拡張のみを被除数にしていた。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

void _start(void) {
    unsigned long eax, edx;

    /* divw: DX:AX = 0x0001_0000 (65536) / CX=3 → AX=21845 DX=1。
     * EAX/EDX/ECX の上位 16bit に故意にゴミを置き、divw が AX/DX/CX しか見ない +
     * 書込が上位 48bit を保存することを同時に検証する。 */
    __asm__ volatile(
        "movl $0xdead0000, %%eax\n\t"    /* AX=0 */
        "movl $0xbeef0001, %%edx\n\t"    /* DX=1 → DX:AX=0x10000 */
        "movl $0xcafe0003, %%ecx\n\t"    /* CX=3 */
        "divw %%cx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("divw: eax="); put_hex(eax & 0xFFFFFFFFUL); put(" edx="); put_hex(edx & 0xFFFFFFFFUL); put("\n");

    /* idivw: DX:AX = -100000 (0xFFFE_7960) / CX=7 → AX=-14285(0xC833) DX=-5(0xFFFB) */
    __asm__ volatile(
        "movl $0x00007960, %%eax\n\t"
        "movl $0x0000fffe, %%edx\n\t"
        "movl $0x00000007, %%ecx\n\t"
        "idivw %%cx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("idivw: ax="); put_hex(eax & 0xFFFF); put(" dx="); put_hex(edx & 0xFFFF); put("\n");

    /* mulw: AX=0x1234 * CX=0x100 → DX:AX = 0x0012:0x3400、上位ゴミ非巻込み */
    __asm__ volatile(
        "movl $0xaaaa1234, %%eax\n\t"
        "movl $0xbbbb0100, %%ecx\n\t"
        "movl $0xcccccccc, %%edx\n\t"
        "mulw %%cx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("mulw: eax="); put_hex(eax & 0xFFFFFFFFUL); put(" edx="); put_hex(edx & 0xFFFFFFFFUL); put("\n");

    /* imulw: AX=-2 * CX=3 → DX:AX = 0xFFFF:0xFFFA (積 -6、16bit に収まる) */
    __asm__ volatile(
        "movl $0x0000fffe, %%eax\n\t"
        "movl $0x00000003, %%ecx\n\t"
        "xorl %%edx, %%edx\n\t"
        "imulw %%cx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("imulw: ax="); put_hex(eax & 0xFFFF); put(" dx="); put_hex(edx & 0xFFFF); put("\n");

    /* idivl (issue #524 の再現値): EDX:EAX = 0x00000000_80000000 (+2^31) / 2 →
     * 商 0x40000000 剰余 0 (旧実装は EAX 符号拡張のみで 0xC0000000 を返していた) */
    __asm__ volatile(
        "movl $0x80000000, %%eax\n\t"
        "movl $0x00000000, %%edx\n\t"
        "movl $2, %%ecx\n\t"
        "idivl %%ecx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("idivl(pos): q="); put_hex(eax & 0xFFFFFFFFUL); put(" r="); put_hex(edx & 0xFFFFFFFFUL); put("\n");

    /* idivl 負数: EDX:EAX = -6 / 4 → q=-1(0xFFFFFFFF) r=-2(0xFFFFFFFE) */
    __asm__ volatile(
        "movl $0xfffffffa, %%eax\n\t"
        "movl $0xffffffff, %%edx\n\t"
        "movl $4, %%ecx\n\t"
        "idivl %%ecx\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rdx, %1\n\t"
        : "=r"(eax), "=r"(edx) : : "rax", "rdx", "rcx", "cc");
    put("idivl(neg): q="); put_hex(eax & 0xFFFFFFFFUL); put(" r="); put_hex(edx & 0xFFFFFFFFUL); put("\n");

    put("MULDIV ok\n");
    sys_exit(0);
}
