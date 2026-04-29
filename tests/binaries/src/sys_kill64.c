/* sys_kill64.c — kill (syscall #62)
 *
 * 自分自身に SIGKILL を送る — のは exit してしまうので、
 * ここでは存在しない pid に kill を送って ESRCH (-3) が返ることを確認する。
 * (現状の Emulin は kill が常に 0 を返すスタブだが、テストの形を整えておく)
 */
#include "sys64.h"

void _start(void) {
    /* 存在しない巨大 pid */
    long r = sys_kill(99999, 0);
    put("kill_no_such=");
    put_dec(r);
    put("\n");
    sys_exit(0);
}
