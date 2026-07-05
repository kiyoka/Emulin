/* insn_xchg_cx16_64.c — issue #536: XCHG AX,r16 (アキュムレータ形 66 91-97) の 16bit 幅 +
 * 上位保存の回帰、issue #535: CPUID.01H:ECX.CX16(bit13) の申告 + CMPXCHG16B 動作の回帰。
 * 旧実装: XCHG アキュムレータ形は op66 を見ず 32bit swap + zero-extend。CX16 は
 * CMPXCHG16B 実装済みなのに未申告だった。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

void _start(void) {
    unsigned long rax_out, rbx_out, ecx_out, zf_out;

    /* XCHG AX,BX (66 93 アキュムレータ形): 下位16bitのみ交換、両者の上位48bit保存 */
    __asm__ volatile(
        "movq $0x1111111111110001, %%rax\n\t"
        "movq $0x2222222222220002, %%rbx\n\t"
        "xchgw %%bx, %%ax\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rbx, %1\n\t"
        : "=r"(rax_out), "=r"(rbx_out) : : "rax", "rbx");
    put("xchg ax,bx: rax="); put_hex(rax_out); put(" rbx="); put_hex(rbx_out); put("\n");

    /* XCHG EAX,BX... 32bit 形の対照 (91-97 の従来経路が壊れていないこと): 交換 + zero-extend */
    __asm__ volatile(
        "movq $0x1111111111110001, %%rax\n\t"
        "movq $0x2222222222220002, %%rbx\n\t"
        "xchgl %%ebx, %%eax\n\t"
        "movq %%rax, %0\n\t"
        "movq %%rbx, %1\n\t"
        : "=r"(rax_out), "=r"(rbx_out) : : "rax", "rbx");
    put("xchg eax,ebx: rax="); put_hex(rax_out); put(" rbx="); put_hex(rbx_out); put("\n");

    /* CPUID.01H:ECX の CX16 (bit13) */
    __asm__ volatile("movl $1, %%eax\n\t xorl %%ecx, %%ecx\n\t cpuid\n\t movl %%ecx, %k0"
                     : "=r"(ecx_out) : : "rax", "rbx", "rcx", "rdx");
    put("cpuid cx16="); put_dec((ecx_out >> 13) & 1); put("\n");

    /* CMPXCHG16B: 一致ケース (RDX:RAX == [mem]) → ZF=1、[mem] ← RCX:RBX */
    {
        static __attribute__((aligned(16))) volatile unsigned long m[2] = { 0x1111, 0x2222 };
        unsigned long rax = 0x1111, rdx = 0x2222, rbx = 0xAAAA, rcx = 0xBBBB;
        unsigned char zf;
        __asm__ volatile("lock cmpxchg16b %0\n\t setz %1"
                         : "+m"(*(volatile __int128 *)m), "=q"(zf), "+a"(rax), "+d"(rdx)
                         : "b"(rbx), "c"(rcx) : "cc");
        put("cmpxchg16b eq: zf="); put_dec(zf);
        put(" m0="); put_hex(m[0]); put(" m1="); put_hex(m[1]); put("\n");
        /* 不一致ケース → ZF=0、RDX:RAX ← [mem] */
        zf_out = 0;
        rax = 0x9999; rdx = 0x8888;
        __asm__ volatile("lock cmpxchg16b %0\n\t setz %1"
                         : "+m"(*(volatile __int128 *)m), "=q"(zf), "+a"(rax), "+d"(rdx)
                         : "b"(rbx), "c"(rcx) : "cc");
        (void)zf_out;
        put("cmpxchg16b ne: zf="); put_dec(zf);
        put(" rax="); put_hex(rax); put(" rdx="); put_hex(rdx); put("\n");
    }

    put("XCHG_CX16 ok\n");
    sys_exit(0);
}
