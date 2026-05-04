/* sys_alarm_deliver64.c — alarm(1) → pause() → SIGALRM ハンドラ実行 (Phase 27 step 23)
 *
 * alarm(1) で 1 秒後の SIGALRM を arm し、pause() で待機。
 * background timer thread が SIGALRM を配信 → ハンドラが globalフラグ立てて
 * 復帰 → pause() が EINTR で抜ける → exit。
 *
 * 期待出力:
 *   before
 *   handler sig=14
 *   after
 */
#include "sys64.h"

#define SIGALRM 14

static volatile int handler_fired = 0;
static volatile int handler_sig = -1;

static void on_alarm(int sig) {
    handler_sig = sig;
    handler_fired = 1;
    /* return; pause() will then return -EINTR */
}

/* sigaction struct (Linux x86-64): handler(8) flags(8) restorer(8) mask(8 = sigsetsize=8) */
struct kernel_sigaction {
    long handler;
    long flags;
    long restorer;
    long mask;
};

#define SA_RESTORER 0x04000000

void _start(void) {
    struct kernel_sigaction sa = {0};
    sa.handler = (long)on_alarm;
    sa.flags = 0;  /* 自然な block + return then EINTR (no SA_RESTART) */
    /* glibc 経由でないので restorer は不要だが flags に SA_RESTORER は立てない */
    sys_rt_sigaction(SIGALRM, &sa, 0, 8);

    put("before\n");
    sys_alarm(1);
    sys_pause();  /* SIGALRM が来るまで block。EINTR で帰る */

    put("handler sig=");
    put_dec(handler_sig);
    put("\n");
    put("after\n");
    sys_exit(0);
}
