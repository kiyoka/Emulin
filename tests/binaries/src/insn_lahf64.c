/* insn_lahf64.c — issue #518: LAHF (9F) / SAHF (9E) の回帰。
 * 旧実装は case 無しで unknown opcode 0x9e/0x9f としてゲストプロセスが死んでいた。
 * LAHF: SF:ZF:0:AF:0:PF:1:CF → AH。SAHF: AH → 各フラグ (pushfq で読み戻して確認)。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

void _start(void) {
    unsigned long ah1, ah2, fl;

    /* CF=1 (stc) → LAHF: AH = ...1.1 (bit1 常時 1, bit0=CF)
     * (%ah は REX 必須命令でエンコード不可なので shr 経由で取り出す) */
    __asm__ volatile(
        "stc\n\t"
        "lahf\n\t"
        "shrl $8, %%eax\n\t"
        "movzbl %%al, %k0\n\t"
        : "=r"(ah1) : : "rax", "cc");
    put("lahf(stc): AH="); put_hex(ah1); put("\n");

    /* ZF=1 PF=1 CF=0 (xor で 0 を作る) → LAHF */
    __asm__ volatile(
        "xorl %%ecx, %%ecx\n\t"     /* ZF=1 PF=1 CF=0 SF=0 AF=0 */
        "lahf\n\t"
        "shrl $8, %%eax\n\t"
        "movzbl %%al, %k0\n\t"
        : "=r"(ah2) : : "rax", "rcx", "cc");
    put("lahf(zero): AH="); put_hex(ah2); put("\n");

    /* SAHF: AH=0xD5 (SF,ZF,AF,PF,CF 全部 1) → pushfq の下位 byte で読み戻し */
    __asm__ volatile(
        "movb $0xd5, %%ah\n\t"
        "sahf\n\t"
        "pushfq\n\t"
        "pop %0\n\t"
        : "=r"(fl) : : "rax", "cc");
    put("sahf(0xd5): flags&0xd5="); put_hex(fl & 0xd5); put("\n");

    /* SAHF: AH=0x02 (全部 0) → 読み戻し */
    __asm__ volatile(
        "movb $0x02, %%ah\n\t"
        "sahf\n\t"
        "pushfq\n\t"
        "pop %0\n\t"
        : "=r"(fl) : : "rax", "cc");
    put("sahf(0x02): flags&0xd5="); put_hex(fl & 0xd5); put("\n");

    put("LAHF_SAHF ok\n");
    sys_exit(0);
}
