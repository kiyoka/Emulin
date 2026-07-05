/* insn_repw64.c — issue #525: REP MOVSW/STOSW (66 F3 A5 / 66 F3 AB) が 16bit 幅で
 * 転送される回帰。旧実装は 66 prefix を無視して 32bit 幅 (MOVSD/STOSD) で実行し、
 * 2 倍のメモリを読み書きしていた。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

static unsigned short src16[8] = { 0x1111,0x2222,0x3333,0x4444, 0x5555,0x6666,0x7777,0x8888 };
static unsigned short dst16[8];

void _start(void) {
    /* rep movsw ×4: dst 前半 4 要素のみコピー、後半はガード値 0xCCCC のまま */
    for (int i = 0; i < 8; i++) dst16[i] = 0xCCCC;
    {
        unsigned long rsi = (unsigned long)src16, rdi = (unsigned long)dst16, rcx = 4;
        __asm__ volatile("cld\n\t rep movsw"
                         : "+S"(rsi), "+D"(rdi), "+c"(rcx) : : "memory", "cc");
        put("rep movsw dst=");
        for (int i = 0; i < 8; i++) { put_hex(dst16[i]); put(i < 7 ? "," : ""); }
        put(" si_off="); put_dec((long)(rsi - (unsigned long)src16));
        put(" di_off="); put_dec((long)(rdi - (unsigned long)dst16));
        put(" rcx=");    put_dec((long)rcx); put("\n");
    }

    /* rep stosw ×3: AX=0xBEEF を 3 要素だけ書く (EAX 上位は 0xDEAD でゴミ) */
    for (int i = 0; i < 8; i++) dst16[i] = 0x1111;
    {
        unsigned long rdi = (unsigned long)dst16, rcx = 3, rax = 0xDEADBEEFUL;
        __asm__ volatile("cld\n\t rep stosw"
                         : "+D"(rdi), "+c"(rcx) : "a"(rax) : "memory", "cc");
        put("rep stosw dst=");
        for (int i = 0; i < 8; i++) { put_hex(dst16[i]); put(i < 7 ? "," : ""); }
        put(" di_off="); put_dec((long)(rdi - (unsigned long)dst16)); put("\n");
    }

    put("REPW ok\n");
    sys_exit(0);
}
