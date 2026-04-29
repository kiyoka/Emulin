/* sys_gettimeofday64.c — gettimeofday (syscall #96)
 *
 * 現在時刻を取得し、tv_sec が 0 でないことを検証する。
 */
#include "sys64.h"

struct timeval { long tv_sec; long tv_usec; };

void _start(void) {
    struct timeval tv;
    long r = sys_gettimeofday(&tv, 0);
    put("ret=");
    put_dec(r);
    put("\n");
    put("nonzero_sec=");
    put_dec(tv.tv_sec != 0 ? 1 : 0);
    put("\n");
    sys_exit(0);
}
