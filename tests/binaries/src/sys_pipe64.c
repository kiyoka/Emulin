/* sys_pipe64.c — pipe (syscall #22)
 *
 * pipe を作成 → 書き込み → 読み出しで往復することを検証。
 * Linux pipe は int[2] を書き込むので C 側も int[2] で受ける。
 */
#include "sys64.h"

void _start(void) {
    int fds[2];
    long r = sys_pipe((long *)fds);
    put("ret=");
    put_dec(r);
    put("\n");

    sys_write(fds[1], "ping", 4);
    char buf[8];
    long n = sys_read(fds[0], buf, 4);
    put("got=");
    sys_write(1, buf, n);
    put("\n");

    sys_close(fds[0]);
    sys_close(fds[1]);
    sys_exit(0);
}
