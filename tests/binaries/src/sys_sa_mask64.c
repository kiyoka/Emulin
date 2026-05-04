/* sys_sa_mask64.c — sa_mask の動作確認 (Phase 27 step 27)
 *
 * SIGUSR1 の handler で sa_mask に SIGUSR2 を含めて install。
 * handler 実行中に SIGUSR2 を kill self → handler 終了まで待たされる
 * (SIGUSR2 handler が呼ばれない)。handler 終了後に SIGUSR2 が配信される。
 *
 * 期待出力:
 *   start
 *   in_usr1
 *   killed_usr2
 *   in_usr1_after_kill
 *   leaving_usr1
 *   in_usr2
 *   end
 */
#include "sys64.h"

#define SIGUSR1 10
#define SIGUSR2 12

static volatile int order = 0;

static void on_usr1(int sig) {
    sys_write(1, "in_usr1\n", 8);
    /* handler 中に SIGUSR2 を自分に送る → sa_mask で block されているはず */
    long pid = sys_getpid();
    sys_kill(pid, SIGUSR2);
    sys_write(1, "killed_usr2\n", 12);
    sys_write(1, "in_usr1_after_kill\n", 19);
    sys_write(1, "leaving_usr1\n", 13);
}

static void on_usr2(int sig) {
    sys_write(1, "in_usr2\n", 8);
}

/* sa_handler / sa_flags / sa_restorer / sa_mask  各 8 byte = 32 byte */
static long sa_usr1[4];
static long sa_usr2[4];

void _start(void) {
    /* SIGUSR2 handler は普通に install */
    sa_usr2[0] = (long)on_usr2;
    sa_usr2[1] = 0; sa_usr2[2] = 0; sa_usr2[3] = 0;
    sys_rt_sigaction(SIGUSR2, sa_usr2, 0, 8);

    /* SIGUSR1 handler は sa_mask に SIGUSR2 を入れて install */
    sa_usr1[0] = (long)on_usr1;
    sa_usr1[1] = 0; sa_usr1[2] = 0;
    sa_usr1[3] = 1L << (SIGUSR2 - 1);  /* sa_mask: SIGUSR2 を block */
    sys_rt_sigaction(SIGUSR1, sa_usr1, 0, 8);

    sys_write(1, "start\n", 6);
    long pid = sys_getpid();
    sys_kill(pid, SIGUSR1);
    sys_write(1, "end\n", 4);
    sys_exit(0);
}
