/* pushf64.c — PUSHFQ (0x9C) / POPFQ (0x9D) のテスト (-nostdlib)。
 *
 * issue #221 step 3d-2c-32: node/V8 が PUSHFQ/POPFQ を使い、software backend は
 * unknown opcode 0x9d で落ちていた。native backend は実 CPU で実行するので、
 * software の RFLAGS 構成が architectural layout と一致することを native==software
 * oracle で検証する。
 *
 * 整数 ALU 由来の比較は CF(0x1)|ZF(0x40)|SF(0x80)|OF(0x800) = mask 0x8C1 に限る。
 * software backend は整数 ALU で PF を追跡しない (pf field は ucomisd/ucomiss 等
 * FP 比較のみが更新、glibc の NaN 判定用)。AF/DF も追跡が部分的なので外す。
 * PF は ucomisd(NaN) = software が追跡する経路で別途検証する (mask 0x8C5)。
 *
 * 1) PUSHFQ: cmp/add/ucomisd で既知のフラグ状態を作って pushfq し、masked 値を出力。
 *    - 1-1=0          : ZF=1            → 0x40  (mask 0x8C1)
 *    - 0-1=-1         : CF=1 SF=1       → 0x81  (mask 0x8C1)
 *    - 0x7fffffff+1   : OF=1 SF=1       → 0x880 (mask 0x8C1)
 *    - ucomisd(NaN)   : ZF=1 PF=1 CF=1  → 0x45  (mask 0x8C5、PF 検証)
 * 2) POPFQ: 構成した RFLAGS 値を popfq で読み込み、直後の setcc で各フラグを
 *    読み戻して出力 (c,z,s,o,p の順)。
 *    - CF|ZF (0x43)   → 11000
 *    - SF|OF (0x882)  → 00110
 */
#include "sys64.h"

void _start(void) {
    unsigned long f1, f2, f3;
    /* pushfq: 1 つの asm block 内でフラグ生成 → pushfq → pop (途中でコンパイラが
     * flags を壊す命令を挟まないように)。 */
    __asm__ volatile(
        "mov $1, %%rax\n\t"
        "cmp $1, %%rax\n\t"
        "pushfq\n\t"
        "pop %0\n\t"
        "xor %%rax, %%rax\n\t"
        "cmp $1, %%rax\n\t"
        "pushfq\n\t"
        "pop %1\n\t"
        "mov $0x7fffffff, %%eax\n\t"
        "add $1, %%eax\n\t"
        "pushfq\n\t"
        "pop %2\n\t"
        : "=r"(f1), "=r"(f2), "=r"(f3)
        :
        : "rax", "cc");
    /* ucomisd(NaN,NaN) → unordered: ZF=PF=CF=1。PF は software が追跡する FP 比較
     * 経路で検証する (整数 ALU の PF は software 未追跡なので上の 3 つは 0x8C1)。 */
    unsigned long f4;
    unsigned long nanbits = 0x7ff8000000000000UL;   /* quiet NaN */
    __asm__ volatile(
        "movq %1, %%xmm0\n\t"
        "ucomisd %%xmm0, %%xmm0\n\t"
        "pushfq\n\t"
        "pop %0\n\t"
        : "=r"(f4)
        : "r"(nanbits)
        : "xmm0", "cc");
    put("pushf:");
    put_hex(f1 & 0x8C1);
    put(",");
    put_hex(f2 & 0x8C1);
    put(",");
    put_hex(f3 & 0x8C1);
    put(",");
    put_hex(f4 & 0x8C5);
    put("\n");

    /* popfq: 値を flags へロード → setcc で読み戻す。mov はフラグを変えないので
     * popfq と setcc の間に挟んでよい。 */
    unsigned long c, z, s, o, p;
    unsigned long m1 = 0x2UL | 0x1 | 0x40;   /* reserved | CF | ZF */
    __asm__ volatile(
        "push %5\n\t"
        "popfq\n\t"
        "mov $0, %0\n\t" "mov $0, %1\n\t" "mov $0, %2\n\t" "mov $0, %3\n\t" "mov $0, %4\n\t"
        "setc %b0\n\t" "setz %b1\n\t" "sets %b2\n\t" "seto %b3\n\t" "setp %b4\n\t"
        : "=&r"(c), "=&r"(z), "=&r"(s), "=&r"(o), "=&r"(p)
        : "r"(m1)
        : "cc");
    put("popf:");
    put_dec((long)c); put_dec((long)z); put_dec((long)s); put_dec((long)o); put_dec((long)p);

    unsigned long m2 = 0x2UL | 0x80 | 0x800; /* reserved | SF | OF */
    __asm__ volatile(
        "push %5\n\t"
        "popfq\n\t"
        "mov $0, %0\n\t" "mov $0, %1\n\t" "mov $0, %2\n\t" "mov $0, %3\n\t" "mov $0, %4\n\t"
        "setc %b0\n\t" "setz %b1\n\t" "sets %b2\n\t" "seto %b3\n\t" "setp %b4\n\t"
        : "=&r"(c), "=&r"(z), "=&r"(s), "=&r"(o), "=&r"(p)
        : "r"(m2)
        : "cc");
    put(",");
    put_dec((long)c); put_dec((long)z); put_dec((long)s); put_dec((long)o); put_dec((long)p);
    put("\n");

    sys_exit(0);
}
