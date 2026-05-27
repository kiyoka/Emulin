/* sys_signal_xmm64.c — signal 配信で XMM が保存復元されるか (issue #119)
 *
 * XMM0-15 を全 1 で破壊するハンドラを kill(self, SIGUSR1) で起こし、復帰後に
 * xmm0 が元の値のままか検証する。旧実装は XMM を signal frame に保存していな
 * かったため、ハンドラの破壊が復帰後も残り被中断側の XMM が壊れていた。
 *
 * set(xmm0) -> kill(self) -> read(xmm0) を 1 つの asm block で行い、間に
 * XMM を触りうる C コードを挟まない。kill で立てた SIGUSR1 は直後の eval step
 * (= 最後の movq) の手前で配信され、ハンドラ復帰後に movq が実行される。
 *
 * 期待出力:
 *   fired=1
 *   xmm0_preserved=1
 */
#include "sys64.h"

#define SIGUSR1 10

static volatile int handler_fired = 0;

/* ハンドラ: xmm0-15 を全 1 (pcmpeqd) で破壊して復帰 */
static void on_sig(int sig) {
    (void)sig;
    __asm__ volatile(
        "pcmpeqd %%xmm0,%%xmm0\n\t"   "pcmpeqd %%xmm1,%%xmm1\n\t"
        "pcmpeqd %%xmm2,%%xmm2\n\t"   "pcmpeqd %%xmm3,%%xmm3\n\t"
        "pcmpeqd %%xmm4,%%xmm4\n\t"   "pcmpeqd %%xmm5,%%xmm5\n\t"
        "pcmpeqd %%xmm6,%%xmm6\n\t"   "pcmpeqd %%xmm7,%%xmm7\n\t"
        "pcmpeqd %%xmm8,%%xmm8\n\t"   "pcmpeqd %%xmm9,%%xmm9\n\t"
        "pcmpeqd %%xmm10,%%xmm10\n\t" "pcmpeqd %%xmm11,%%xmm11\n\t"
        "pcmpeqd %%xmm12,%%xmm12\n\t" "pcmpeqd %%xmm13,%%xmm13\n\t"
        "pcmpeqd %%xmm14,%%xmm14\n\t" "pcmpeqd %%xmm15,%%xmm15\n\t"
        ::: "xmm0","xmm1","xmm2","xmm3","xmm4","xmm5","xmm6","xmm7",
            "xmm8","xmm9","xmm10","xmm11","xmm12","xmm13","xmm14","xmm15");
    handler_fired = 1;
}

/* sigaction struct (Linux x86-64): handler(8) flags(8) restorer(8) mask(8) */
struct kernel_sigaction { long handler; long flags; long restorer; long mask; };

void _start(void) {
    struct kernel_sigaction sa = {0};
    sa.handler = (long)on_sig;
    sa.flags = 0;
    sys_rt_sigaction(SIGUSR1, &sa, 0, 8);

    long pid   = sys_getpid();
    long known = 0x1122334455667788L;
    long got   = 0;

    __asm__ volatile(
        "movq %1, %%xmm0\n\t"    /* xmm0 = known */
        "movq $62, %%rax\n\t"    /* __NR_kill */
        "movq %2, %%rdi\n\t"     /* pid */
        "movq $10, %%rsi\n\t"    /* SIGUSR1 */
        "syscall\n\t"            /* SIGUSR1 pending -> 次の step で配信 */
        "movq %%xmm0, %0\n\t"    /* ハンドラ復帰後の xmm0 (低 64bit) を読む */
        : "=r"(got)
        : "r"(known), "r"(pid)
        : "rax","rdi","rsi","rcx","r11","xmm0","memory");

    put("fired=");
    put_dec(handler_fired);
    put("\n");
    put("xmm0_preserved=");
    put_dec(got == known ? 1 : 0);
    put("\n");
    sys_exit(0);
}
