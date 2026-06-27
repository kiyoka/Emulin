/* avx64.c — -nostdlib の AVX (256-bit YMM) 命令テスト。
 *
 * native (KVM/WHP) backend の CR4.OSXSAVE + XCR0 (=0x7、x87|SSE|AVX) を検証する。
 * これらが未設定だと guest の VEX-encoded AVX 命令 (vmovdqu ymm 等) が #UD → IDT
 * 無しなら triple fault する (standalone Bun が user-mode の vmovdqu ymm0 で #UD した
 * のと同じ経路)。software backend は AVX を emulate するので、native==software の
 * oracle で AVX が実 vCPU で正しく動くことを確認できる。
 *
 * ★ AVX1 のみ使用 (vmovdqu / vxorps ymm = AVX2 非依存)。256-bit の上位 lane
 *   (要素 4..7) も計算に絡めることで「YMM 上位 128bit が有効」を確認する。
 *   vmovdqu は unaligned なので stack/rodata alignment に依存しない (vmovaps ymm は
 *   32-byte 境界必須で非整列 stack で #GP するため使わない)。
 *
 * 計算: r[i] = a[i] ^ b[i] (256-bit bitwise XOR、float/int 解釈に依らず正しい)。
 *   a = {0x100,0x200,...,0x800}, b = {0x001,0x002,...,0x008}
 *   → r = {0x101,0x202,...,0x808} = {257,514,771,1028,1285,1542,1799,2056}
 *   出力 r[0],r[4],r[7] = lower lane と upper lane を 1 つずつ → "avx:257,1285,2056"。
 */
#include "sys64.h"

void _start(void) {
    /* 入力は .rodata (static) に置く。vmovdqu は unaligned load なので alignment 不問。 */
    static const int a[8] = { 0x100, 0x200, 0x300, 0x400, 0x500, 0x600, 0x700, 0x800 };
    static const int b[8] = { 0x001, 0x002, 0x003, 0x004, 0x005, 0x006, 0x007, 0x008 };
    int r[8];
    int i;
    for (i = 0; i < 8; i++) r[i] = -1;   /* sentinel: AVX store が全要素を上書きするはず */
    __asm__ volatile(
        "vmovdqu (%0), %%ymm0\n"            /* 256-bit unaligned load (Bun が #UD した命令種) */
        "vmovdqu (%1), %%ymm1\n"
        "vxorps  %%ymm1, %%ymm0, %%ymm2\n"  /* 256-bit bitwise XOR (上位 lane も計算) */
        "vmovdqu %%ymm2, (%2)\n"            /* 256-bit unaligned store */
        "vzeroupper\n"
        :
        : "r"(a), "r"(b), "r"(r)
        : "ymm0", "ymm1", "ymm2", "memory"
    );
    put("avx:");
    put_dec(r[0]); put(",");   /* lower lane (要素 0) */
    put_dec(r[4]); put(",");   /* upper lane (要素 4) */
    put_dec(r[7]);             /* upper lane (要素 7) */
    put("\n");
    sys_exit(0);
}
