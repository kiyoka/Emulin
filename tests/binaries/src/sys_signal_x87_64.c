/* sys_signal_x87_64.c — signal 配信で x87 FPU 状態が保存復元されるか (issue #119 継続)
 *
 * #119 本体 (commit 156d32d) は XMM0-15 と PF を signal frame に保存復元したが、
 * x87 FPU 状態 (制御ワード fpu_cw / stack top fpu_top / st(0..7)) は「優先度は
 * XMM が高い」として対象外だった。64bit でも long double 演算 (printf %Lf や
 * issue #78 の unordered_map rehash bucket 計算) で x87 は live になりうるため、
 * ハンドラが x87 を使うと被中断側の x87 状態が壊れる。本テストでその回帰を固定する。
 *
 * 手順 (XMM 版と同様に 1 つの asm block で完結させ、間に x87 を触る C を挟まない):
 *   1. fldcw  で非既定の制御ワード (0x0F7F、既定は 0x037F) をセット
 *   2. fildl  で既知の整数 (0x4242) を st(0) に push
 *   3. kill(self, SIGUSR1) で次 step の手前にハンドラを起こす
 *      ハンドラは fninit (cw=0x037F / top=0 にリセット) + fildl で別の garbage を push
 *   4. 復帰後に fnstcw / fistpl で読み戻し、元の値が保たれているか検証
 *
 * 期待出力:
 *   fired=1
 *   cw_preserved=1
 *   st0_preserved=1
 *
 * 旧実装 (x87 未保存) では cw_preserved=0 / st0_preserved=0 になる。
 */
#include "sys64.h"

#define SIGUSR1 10

static volatile int handler_fired = 0;

/* ハンドラ: x87 状態を fninit でリセットし、別の値を stack に push して破壊する。
 * signal 復帰時に被中断側の x87 状態が復元されれば、この破壊は残らない。 */
static void on_sig(int sig) {
    (void)sig;
    static volatile int garbage = 0x7777;
    __asm__ volatile(
        "fninit\n\t"        /* fpu_cw=0x037F, fpu_sw=0, fpu_tag=0xFFFF, fpu_top=0 */
        "fildl %0\n\t"      /* st(0) = garbage (top をさらに動かす) */
        : : "m"(garbage) : "memory");
    handler_fired = 1;
}

/* sigaction struct (Linux x86-64): handler(8) flags(8) restorer(8) mask(8) */
struct kernel_sigaction { long handler; long flags; long restorer; long mask; };

void _start(void) {
    struct kernel_sigaction sa = {0};
    sa.handler = (long)on_sig;
    sa.flags = 0;
    sys_rt_sigaction(SIGUSR1, &sa, 0, 8);

    long pid = sys_getpid();

    unsigned short cw_set = 0x0F7F;   /* 非既定の制御ワード (既定 0x037F と区別) */
    unsigned short cw_got = 0;
    int ival_in  = 0x4242;            /* st(0) に積む既知の整数 */
    int ival_out = 0;

    __asm__ volatile(
        "fldcw %2\n\t"           /* fpu_cw = cw_set */
        "fildl %3\n\t"           /* push ival_in -> st(0) */
        "movq $62, %%rax\n\t"    /* __NR_kill */
        "movq %4, %%rdi\n\t"     /* pid */
        "movq $10, %%rsi\n\t"    /* SIGUSR1 */
        "syscall\n\t"            /* SIGUSR1 pending -> 次 step の手前で配信 */
        "fnstcw %0\n\t"          /* ハンドラ復帰後の fpu_cw を読む */
        "fistpl %1\n\t"          /* ハンドラ復帰後の st(0) を pop */
        : "=m"(cw_got), "=m"(ival_out)
        : "m"(cw_set), "m"(ival_in), "r"(pid)
        : "rax","rdi","rsi","rcx","r11","memory");

    put("fired=");
    put_dec(handler_fired);
    put("\n");
    put("cw_preserved=");
    put_dec(cw_got == cw_set ? 1 : 0);
    put("\n");
    put("st0_preserved=");
    put_dec(ival_out == ival_in ? 1 : 0);
    put("\n");
    sys_exit(0);
}
