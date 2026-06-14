/* sys_sa_restart64.c — SA_RESTART によるシステムコールの自動再開
 *
 * Phase 23 step 2.
 *
 * シナリオ:
 *   1. fork() で子を生成。子は alarm 的な「0.1 秒だけ寝てから exit(0)」
 *      で疑似的に「あとから exit する」を作る。代わりに、ここでは
 *      kill(parent, SIGUSR1) を先に送り、その後 exit(0) する。
 *   2. 親は wait4(-1) で子を待つ。
 *   3. SIGUSR1 が割り込み、ハンドラが走る。
 *   4. SA_RESTART が立っていれば wait4 が自動再開して、最終的に
 *      子の exit_code = 0 を回収できる。立っていなければ EINTR で
 *      抜けてしまい、status は埋まらない。
 *
 * 期待出力:
 *   handler
 *   ret>0=1
 *   wstatus=0
 *   ok
 */
#include "sys64.h"

#define SIGUSR1 10
#define SIGCHLD 17
#define SA_RESTART 0x10000000L

static long sa_int[4];     /* SIGUSR1 ハンドラ (SA_RESTART 付き) */

void handler(long sig) {
    sys_write(1, "handler\n", 8);
}

void _start(void) {
    sa_int[0] = (long)&handler;
    sa_int[1] = SA_RESTART;
    sa_int[2] = 0;
    sa_int[3] = 0;
    sys_rt_sigaction(SIGUSR1, sa_int, 0, 8);

    long pid = sys_fork();
    if (pid == 0) {
        /* 子: 親に SIGUSR1 を送ってから exit */
        long ppid = sys_getppid();
        sys_kill(ppid, SIGUSR1);
        /* 少し時間を置く: 親が wait4 でブロックしている間に SIGUSR1 が
           届くようにする。busy ループで時間稼ぎ。 */
        for (volatile long i = 0; i < 200000L; i++) { }
        sys_exit(0);
    }

    /* 親: wait4(-1) で子を待つ。SA_RESTART のおかげで EINTR にならず
       handler 復帰後に再開し、最終的に子の終了を取得できる。 */
    int status = 0xdeadbeef;
    long ret = sys_wait4(-1, &status, 0, 0);

    put("ret>0=");
    put_dec(ret > 0 ? 1 : 0);
    put("\n");
    put("wstatus=");
    put_dec(status);
    put("\n");
    if (ret > 0 && status == 0) put("ok\n");
    else                        put("ng\n");
    sys_exit(0);
}
