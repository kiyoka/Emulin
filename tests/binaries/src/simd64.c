/* simd64.c — -nostdlib の SSE (SSE2 整数) 命令テスト。
 *
 * native (KVM) backend の CR4.OSFXSR (SSE 有効化) を検証する。OSFXSR 未設定だと
 * guest の SSE 命令 (movdqu/paddd 等) が #UD → IDT 無し → triple fault する。
 * software backend は SSE をエミュレートするので、native==software の oracle で
 * SSE が実 vCPU で正しく動くことを確認できる。
 *
 * 計算: a={40,1,100,1000} + b={2,41,100,1000} = {42,42,200,2000} (paddd)。
 *       先頭 2 要素を出力 → "simd:42,42"。
 */
#include "sys64.h"

void _start(void) {
    /* 16-byte 境界に依存しないよう movdqu (unaligned) を使う */
    int a[4] = { 40, 1, 100, 1000 };
    int b[4] = { 2, 41, 100, 1000 };
    int r[4] = { 0, 0, 0, 0 };
    __asm__ volatile(
        "movdqu (%0), %%xmm0\n"
        "movdqu (%1), %%xmm1\n"
        "paddd  %%xmm1, %%xmm0\n"   /* packed 32-bit add */
        "movdqu %%xmm0, (%2)\n"
        :
        : "r"(a), "r"(b), "r"(r)
        : "xmm0", "xmm1", "memory"
    );
    put("simd:");
    put_dec(r[0]);
    put(",");
    put_dec(r[1]);
    put("\n");
    sys_exit(0);
}
