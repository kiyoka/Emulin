/* sys_sigmask64.c — rt_sigprocmask の動作確認 (Phase 27 step 23)
 *
 * SIGUSR1 (10) を block → kill(self, SIGUSR1) → handler 呼ばれない
 * → unblock → 直後に handler が走る (pending signal が unblock 時に配信)
 *
 * 期待出力:
 *   blocked
 *   killed
 *   unblock
 *   handler sig=10
 *   end
 */
#include "sys64.h"

#define SIGUSR1 10
#define SIG_BLOCK   0
#define SIG_UNBLOCK 1
#define SIG_SETMASK 2

static volatile int handler_sig = 0;

static long sys_rt_sigprocmask(long how, const void *set, void *oldset, long sigsetsize) {
    long ret;
    register long r10 __asm__("r10") = sigsetsize;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(14LL), "D"(how), "S"(set), "d"(oldset), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

static void on_usr1(int sig) {
    handler_sig = sig;
}

static long sa[4];

void _start(void) {
    sa[0] = (long)on_usr1;
    sa[1] = 0; sa[2] = 0; sa[3] = 0;
    sys_rt_sigaction(SIGUSR1, sa, 0, 8);

    /* SIGUSR1 を block */
    long mask = 1L << (SIGUSR1 - 1);   /* bit 9 */
    sys_rt_sigprocmask(SIG_BLOCK, &mask, 0, 8);
    put("blocked\n");

    long pid = sys_getpid();
    sys_kill(pid, SIGUSR1);
    put("killed\n");

    /* この時点でも handler は呼ばれていないはず */
    put("unblock\n");
    sys_rt_sigprocmask(SIG_UNBLOCK, &mask, 0, 8);

    /* unblock したので pending signal が配信される */
    put("handler sig=");
    put_dec(handler_sig);
    put("\n");
    put("end\n");
    sys_exit(0);
}
