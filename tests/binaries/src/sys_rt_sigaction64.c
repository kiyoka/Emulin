/* sys_rt_sigaction64.c — rt_sigaction (syscall #13)
 *
 * 簡単な sigaction 構造体で SIGUSR1 のハンドラ登録を試みる。
 * 戻り値だけ検証 (実際の配信は別テスト)。
 *
 * struct sigaction { sa_handler(8) sa_flags(8) sa_restorer(8) sa_mask(8) }
 */
#include "sys64.h"

#define SIGUSR1 10

static long sa[4];  /* 32 byte sigaction */

void _start(void) {
    sa[0] = 0;  /* sa_handler = SIG_DFL */
    sa[1] = 0;  /* sa_flags */
    sa[2] = 0;  /* sa_restorer */
    sa[3] = 0;  /* sa_mask */
    long r = sys_rt_sigaction(SIGUSR1, sa, 0, 8);
    put("ret=");
    put_dec(r);
    put("\n");
    sys_exit(0);
}
