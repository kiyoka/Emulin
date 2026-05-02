/* sys_sa_siginfo64.c — SA_SIGINFO で 3 引数ハンドラを呼ぶ
 *
 * Phase 23 step 3.
 *
 * シナリオ:
 *   1. SIGUSR1 のハンドラを SA_SIGINFO 付きで登録
 *   2. kill(self, SIGUSR1)
 *   3. ハンドラは siginfo_t* の si_signo (offset 0) を読み、sig 引数と
 *      一致するか確認する。ucontext_t* も非 NULL であることを確認する。
 *
 * 期待出力:
 *   sig=10
 *   info_signo=10
 *   ucontext_nonnull=1
 *   ok
 */
#include "sys64.h"

#define SIGUSR1 10
#define SA_SIGINFO 0x00000004L

static long sa[4];

static volatile long g_sig          = -1;
static volatile long g_info_signo   = -1;
static volatile long g_ucontext_ptr = 0;

void handler(long sig, void *info, void *uctx) {
    /* siginfo の先頭 4 byte = si_signo を読む */
    int signo;
    __asm__ volatile ("movl (%1), %0" : "=r"(signo) : "r"(info));
    g_sig          = sig;
    g_info_signo   = signo;
    g_ucontext_ptr = (long)uctx;
}

void _start(void) {
    sa[0] = (long)&handler;
    sa[1] = SA_SIGINFO;
    sa[2] = 0;
    sa[3] = 0;
    sys_rt_sigaction(SIGUSR1, sa, 0, 8);

    long pid = sys_getpid();
    sys_kill(pid, SIGUSR1);

    put("sig=");          put_dec(g_sig);          put("\n");
    put("info_signo=");   put_dec(g_info_signo);   put("\n");
    put("ucontext_nonnull=");
    put_dec(g_ucontext_ptr != 0 ? 1 : 0);
    put("\n");

    if (g_sig == SIGUSR1 && g_info_signo == SIGUSR1 && g_ucontext_ptr != 0)
        put("ok\n");
    else
        put("ng\n");
    sys_exit(0);
}
