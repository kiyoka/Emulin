/* sys_nanosleep64.c — nanosleep (syscall #35)
 *
 * 1ms スリープ。エラー無く戻ることだけ確認 (実時間は検証しない)。
 */
#include "sys64.h"

struct timespec { long tv_sec; long tv_nsec; };

void _start(void) {
    struct timespec req = { 0, 1000000 }; /* 1 ms */
    long r = sys_nanosleep(&req, 0);
    put("ret=");
    put_dec(r);
    put("\n");
    sys_exit(0);
}
