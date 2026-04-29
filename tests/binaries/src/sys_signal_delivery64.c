/* sys_signal_delivery64.c — シグナル配信の往復テスト
 *
 * 1. SIGUSR1 のハンドラを rt_sigaction で登録
 * 2. getpid() で自 pid を取得
 * 3. kill(self, SIGUSR1) を発行
 * 4. ハンドラ内で "handler\n" を表示し flag を立てる
 * 5. main に戻ったら "after\n" を表示し、flag が立っているか確認
 *
 * 期待出力:
 *   handler
 *   after
 *   flag=1
 */
#include "sys64.h"

#define SIGUSR1 10

static volatile long handler_called = 0;

/* sa_handler / sa_flags / sa_restorer / sa_mask */
static long sa[4];

void handler(long sig) {
    sys_write(1, "handler\n", 8);
    handler_called = 1;
}

void _start(void) {
    sa[0] = (long)&handler;
    sa[1] = 0;
    sa[2] = 0;
    sa[3] = 0;
    sys_rt_sigaction(SIGUSR1, sa, 0, 8);

    long pid = sys_getpid();
    sys_kill(pid, SIGUSR1);

    sys_write(1, "after\n", 6);
    put("flag=");
    put_dec(handler_called);
    put("\n");
    sys_exit(0);
}
