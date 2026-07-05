/* insn_dirflag64.c — issue #519: CLD/STD が DF (RFLAGS bit10) を実際に更新し、
 * string ops (単発 + REP) が DF を見て backward (−) に進む回帰。
 * 旧実装は CLD/STD が完全 NOP で、STD 後も pushfq の bit10 が 0 のまま +
 * string ops は常に forward だった。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

static unsigned char src[8] = { 'A','B','C','D','E','F','G','H' };
static unsigned char dst[8];

void _start(void) {
    unsigned long f_std, f_cld;

    /* STD → DF=1、CLD → DF=0 (pushfq bit10) */
    __asm__ volatile("std\n\t pushfq\n\t pop %0\n\t cld\n\t pushfq\n\t pop %1"
                     : "=r"(f_std), "=r"(f_cld) : : "cc");
    put("DF after std="); put_dec((f_std >> 10) & 1);
    put(" after cld=");   put_dec((f_cld >> 10) & 1); put("\n");

    /* backward REP MOVSB: RSI/RDI を末尾に置き 4 byte を backward copy →
     * dst[4..7]="EFGH" (順序保存)、RSI/RDI は先頭-1 相当まで下がる */
    {
        unsigned long rsi = (unsigned long)&src[7], rdi = (unsigned long)&dst[7], rcx = 4;
        __asm__ volatile("std\n\t rep movsb\n\t cld"
                         : "+S"(rsi), "+D"(rdi), "+c"(rcx) : : "memory", "cc");
        put("bwd movsb dst=");
        for (int i = 0; i < 8; i++) { char c = dst[i] ? (char)dst[i] : '.'; sys_write(1, &c, 1); }
        put(" si_back="); put_dec((long)((unsigned long)&src[7] - rsi));
        put(" di_back="); put_dec((long)((unsigned long)&dst[7] - rdi));
        put(" rcx=");     put_dec((long)rcx); put("\n");
    }

    /* backward REP STOSB: dst[0..3] を 'x' で backward fill */
    {
        unsigned long rdi = (unsigned long)&dst[3], rcx = 4, rax = 'x';
        __asm__ volatile("std\n\t rep stosb\n\t cld"
                         : "+D"(rdi), "+c"(rcx) : "a"(rax) : "memory", "cc");
        put("bwd stosb dst=");
        for (int i = 0; i < 8; i++) { char c = dst[i] ? (char)dst[i] : '.'; sys_write(1, &c, 1); }
        put("\n");
    }

    /* 単発 MOVSB の backward: RSI/RDI が −1 されること */
    {
        unsigned long rsi = (unsigned long)&src[2], rdi = (unsigned long)&dst[2];
        __asm__ volatile("std\n\t movsb\n\t cld"
                         : "+S"(rsi), "+D"(rdi) : : "memory", "cc");
        put("bwd movsb1 si_delta="); put_dec((long)(rsi - (unsigned long)&src[2]));
        put(" di_delta=");           put_dec((long)(rdi - (unsigned long)&dst[2])); put("\n");
    }

    put("DIRFLAG ok\n");
    sys_exit(0);
}
