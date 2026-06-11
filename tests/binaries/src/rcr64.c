/* rcr64.c — RCL (Grp2 /2) / RCR (Grp2 /3) = rotate-through-carry のテスト (-nostdlib)。
 *
 * issue #221 step 3d-2c-37: Go runtime の除算-by-定数 magic 等が `rcr $1,%rdx` を使うが、
 * Cpu64 interpreter は Grp2 /2 (RCL) / /3 (RCR) 未実装で "unsupported Grp2 /3" で停止していた。
 * RCR/RCL は (size+1)-bit の [CF:operand] を回転する。期待値は host 実 CPU == software == native
 * の 3 者一致 (native-oracle)。
 *
 * 各ケースで CF を stc/clc で既知化し、rotate 後の値 + CF (adc で吸い出す) を出力する。
 */
#include "sys64.h"

/* rotate 結果と CF を "val,cf" で出力 */
static void emit(const char *tag, unsigned long val, unsigned long cf) {
    put(tag);
    put_hex(val);
    put(",");
    put_dec((long)cf);
    put("\n");
}

void _start(void) {
    unsigned long r, cf;

    /* RCR 64-bit by 1 (D1 /3): CF=0, val=0x3 → 0x1, CF=1 (bit0 が CF へ) */
    __asm__ volatile(
        "clc\n\t"
        "movq $0x3, %0\n\t"
        "rcrq $1, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r), "=&r"(cf) :: "cc");
    emit("rcr1:", r, cf);

    /* RCR 64-bit by 1: CF=1, val=0x0 → 0x8000000000000000 (CF が MSB へ), CF=0 */
    __asm__ volatile(
        "stc\n\t"
        "movq $0x0, %0\n\t"
        "rcrq $1, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r), "=&r"(cf) :: "cc");
    emit("rcr2:", r, cf);

    /* RCL 64-bit by 1 (D1 /2): CF=1, val=0x1 → 0x3 (CF が bit0、元 bit0 が bit1), CF=0 */
    __asm__ volatile(
        "stc\n\t"
        "movq $0x1, %0\n\t"
        "rclq $1, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r), "=&r"(cf) :: "cc");
    emit("rcl1:", r, cf);

    /* RCL 64-bit by 1: CF=0, val=0x8000000000000000 → 0x0 (MSB が CF へ), CF=1 */
    __asm__ volatile(
        "clc\n\t"
        "movabsq $0x8000000000000000, %0\n\t"
        "rclq $1, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r), "=&r"(cf) :: "cc");
    emit("rcl2:", r, cf);

    /* RCR by CL (D3 /3): val=0xFF, CF=0, count=4 → [CF:0xFF] を 4 回右回転 (65-bit) */
    __asm__ volatile(
        "clc\n\t"
        "movq $0xFF, %0\n\t"
        "movq $4, %%rcx\n\t"
        "rcrq %%cl, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r), "=&r"(cf) :: "rcx", "cc");
    emit("rcrcl:", r, cf);

    /* RCR 32-bit by 1 (D1 /3, no REX.W): CF=1, val=0x2 → 0x80000001, CF=0 */
    unsigned int r32;
    __asm__ volatile(
        "stc\n\t"
        "movl $0x2, %0\n\t"
        "rcrl $1, %0\n\t"
        "movq $0, %1\n\t"
        "adcq $0, %1\n\t"
        : "=&r"(r32), "=&r"(cf) :: "cc");
    emit("rcr32:", (unsigned long)r32, cf);

    sys_exit(0);
}
