/* mulmod64.c — IMUL の CF/OF と x87 FPREM/FPREM1 のテスト (-nostdlib)。
 *
 * issue #221 step 3d-2c-34: node/V8 の重い workload で見つかった Cpu64 の 2 バグの回帰固定。
 *
 * (1) IMUL の CF/OF 未設定 (0F AF は of=0;cf=0 ハードコード、6B/69 は未設定、F7 /5 32-bit は
 *     cf=of=0)。V8 は smi 乗算の overflow を `imul r32; jo deopt` で検出するので、OF が
 *     立たないと int32 が silent wrap して `46341*46341` が負値になる (deopt 不発)。
 *     CF/OF = 「結果が dest 幅に収まらないとき 1」(Intel SDM)。
 * (2) x87 FPREM (D9 F8) silent no-op。V8 の Float64Mod は「fprem; fnstsw ax; C2 が立つ間
 *     ループ」で、no-op + C2=0 だと dividend がそのまま剰余として返る
 *     (`90000000000 % 1000003` が dividend を返す)。
 *
 * 期待値は host 実 CPU == software == native の 3 者一致 (native-oracle)。
 */
#include "sys64.h"

void _start(void) {
    /* IMUL r32,r32 (0F AF): 46341*46341 = 2147488281 > INT32_MAX → wrap + OF=1 */
    long v1, o1;
    __asm__ volatile(
        "mov $46341, %%eax\n\t"
        "imul %%eax, %%eax\n\t"
        "mov $0, %1\n\t"
        "seto %b1\n\t"
        "movslq %%eax, %0\n\t"
        : "=r"(v1), "=r"(o1) :: "rax", "cc");
    put("imul32:"); put_dec(v1); put(","); put_dec(o1); put("\n");

    /* 非 overflow: 1000*1000 → OF=0 */
    long v2, o2;
    __asm__ volatile(
        "mov $1000, %%eax\n\t"
        "imul %%eax, %%eax\n\t"
        "mov $0, %1\n\t"
        "seto %b1\n\t"
        "movslq %%eax, %0\n\t"
        : "=r"(v2), "=r"(o2) :: "rax", "cc");
    put("imul32b:"); put_dec(v2); put(","); put_dec(o2); put("\n");

    /* IMUL r64,r64 (REX.W 0F AF): (1<<62)*4 → wrap to 0 + OF=1 */
    long v3, o3;
    __asm__ volatile(
        "movabs $0x4000000000000000, %%rax\n\t"
        "mov $4, %%rcx\n\t"
        "imul %%rcx, %%rax\n\t"
        "mov $0, %1\n\t"
        "seto %b1\n\t"
        "mov %%rax, %0\n\t"
        : "=r"(v3), "=r"(o3) :: "rax", "rcx", "cc");
    put("imul64:"); put_dec(v3); put(","); put_dec(o3); put("\n");

    /* IMUL r32, r/m32, imm8 (6B): 50000000*100 = 5e9 → wrap + OF=1 */
    long v4, o4;
    __asm__ volatile(
        "mov $50000000, %%ecx\n\t"
        "imul $100, %%ecx, %%eax\n\t"
        "mov $0, %1\n\t"
        "seto %b1\n\t"
        "movslq %%eax, %0\n\t"
        : "=r"(v4), "=r"(o4) :: "rax", "rcx", "cc");
    put("imulimm:"); put_dec(v4); put(","); put_dec(o4); put("\n");

    /* F7 /5 one-operand: EDX:EAX = 100000*100000 = 1e10 → EDX=2 ≠ sign-ext → OF=1 */
    long v5, o5;
    __asm__ volatile(
        "mov $100000, %%eax\n\t"
        "mov $100000, %%ecx\n\t"
        "imull %%ecx\n\t"
        "mov $0, %1\n\t"
        "seto %b1\n\t"
        "movslq %%eax, %0\n\t"
        : "=r"(v5), "=r"(o5) :: "rax", "rdx", "rcx", "cc");
    put("imulone:"); put_dec(v5); put(","); put_dec(o5); put("\n");

    /* x87 FPREM ループ (V8 Float64Mod と同じ形): fmod(9e10, 1000003) = 730003 */
    double a = 90000000000.0, b = 1000003.0, r;
    __asm__ volatile(
        "fldl %2\n\t"
        "fldl %1\n\t"
        "1:\n\t"
        "fprem\n\t"
        "fnstsw %%ax\n\t"
        "test $0x4, %%ah\n\t"
        "jnz 1b\n\t"
        "fstpl %0\n\t"
        "fstp %%st(0)\n\t"
        : "=m"(r) : "m"(a), "m"(b) : "rax", "cc", "st", "st(1)");
    put("fprem:"); put_dec((long)r); put("\n");

    /* FPREM1 (IEEE remainder、round-to-nearest): remainder(10,3) = 1 */
    double a2 = 10.0, b2 = 3.0, r2;
    __asm__ volatile(
        "fldl %2\n\t"
        "fldl %1\n\t"
        "2:\n\t"
        "fprem1\n\t"
        "fnstsw %%ax\n\t"
        "test $0x4, %%ah\n\t"
        "jnz 2b\n\t"
        "fstpl %0\n\t"
        "fstp %%st(0)\n\t"
        : "=m"(r2) : "m"(a2), "m"(b2) : "rax", "cc", "st", "st(1)");
    put("fprem1:"); put_dec((long)r2); put("\n");

    sys_exit(0);
}
