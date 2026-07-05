/* insn_repscas64.c — issue #519/#525 の string op 一般化の回帰:
 * REPNE SCASB (F2 AE、strchr/memchr イディオム) / REPE SCASB (F3 AE) / REPE CMPSB。
 * 旧実装は REPE SCAS が「常に not found (ZF=0,RCX=0)」の嘘 stub、REPNE 系は F2 prefix を
 * 捨てて 1 回だけ実行していた。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

static unsigned char hay[8]  = { 'a','b','c','X','e','f','g','h' };
static unsigned char s1[6]   = { 1,2,3,4,5,6 };
static unsigned char s2[6]   = { 1,2,9,4,5,6 };   /* [2] で不一致 */

void _start(void) {
    unsigned long rdi, rcx, zf_out;

    /* REPNE SCASB: 'X' を探す → 4 byte 消費 (a,b,c,X)、ZF=1、残 RCX=8-4=4 */
    rdi=(unsigned long)hay; rcx=8;
    __asm__ volatile("cld\n\t movb $'X', %%al\n\t repne scasb\n\t"
                     "pushfq\n\t pop %2"
                     : "+D"(rdi), "+c"(rcx), "=r"(zf_out) : : "rax", "memory", "cc");
    put("repne scasb: off="); put_dec((long)(rdi-(unsigned long)hay));
    put(" rcx="); put_dec((long)rcx);
    put(" zf=");  put_dec((zf_out>>6)&1); put("\n");

    /* REPNE SCASB not-found: 'Z' は無い → 8 byte 全消費、ZF=0、RCX=0 */
    rdi=(unsigned long)hay; rcx=8;
    __asm__ volatile("cld\n\t movb $'Z', %%al\n\t repne scasb\n\t"
                     "pushfq\n\t pop %2"
                     : "+D"(rdi), "+c"(rcx), "=r"(zf_out) : : "rax", "memory", "cc");
    put("repne scasb(nf): off="); put_dec((long)(rdi-(unsigned long)hay));
    put(" rcx="); put_dec((long)rcx);
    put(" zf=");  put_dec((zf_out>>6)&1); put("\n");

    /* REPE SCASB: 先頭から 'a' が続く限り skip → hay は a,b,... なので 2 byte 目 (b) で停止 */
    rdi=(unsigned long)hay; rcx=8;
    __asm__ volatile("cld\n\t movb $'a', %%al\n\t repe scasb\n\t"
                     "pushfq\n\t pop %2"
                     : "+D"(rdi), "+c"(rcx), "=r"(zf_out) : : "rax", "memory", "cc");
    put("repe scasb: off="); put_dec((long)(rdi-(unsigned long)hay));
    put(" rcx="); put_dec((long)rcx);
    put(" zf=");  put_dec((zf_out>>6)&1); put("\n");

    /* REPE CMPSB: s1 vs s2 は [2] で不一致 → 3 byte 消費、ZF=0、CF=0 (1<9 → CF=1) */
    {
        unsigned long rsi=(unsigned long)s1; rdi=(unsigned long)s2; rcx=6;
        __asm__ volatile("cld\n\t repe cmpsb\n\t pushfq\n\t pop %3"
                         : "+S"(rsi), "+D"(rdi), "+c"(rcx), "=r"(zf_out) : : "memory", "cc");
        put("repe cmpsb: si_off="); put_dec((long)(rsi-(unsigned long)s1));
        put(" rcx="); put_dec((long)rcx);
        put(" zf=");  put_dec((zf_out>>6)&1);
        put(" cf=");  put_dec(zf_out&1); put("\n");
    }

    /* REPE CMPSB 全一致: s1 vs s1 → 6 byte 全消費、ZF=1、RCX=0 */
    {
        unsigned long rsi=(unsigned long)s1; rdi=(unsigned long)s1; rcx=6;
        __asm__ volatile("cld\n\t repe cmpsb\n\t pushfq\n\t pop %3"
                         : "+S"(rsi), "+D"(rdi), "+c"(rcx), "=r"(zf_out) : : "memory", "cc");
        put("repe cmpsb(eq): rcx="); put_dec((long)rcx);
        put(" zf=");  put_dec((zf_out>>6)&1); put("\n");
    }

    put("REPSCAS ok\n");
    sys_exit(0);
}
