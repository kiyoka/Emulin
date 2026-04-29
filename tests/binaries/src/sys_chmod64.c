/* sys_chmod64.c — chmod (syscall #90)
 *
 * /tmp/sys_chmod.dat を作成 → chmod 0600 → stat で mode を確認。
 */
#include "sys64.h"

#define O_WRONLY 1
#define O_CREAT  0100
#define O_TRUNC  01000

static char buf[256];

void _start(void) {
    long fd = sys_open("/tmp/sys_chmod.dat", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    sys_close(fd);

    long r = sys_chmod("/tmp/sys_chmod.dat", 0600);
    put("chmod=");
    put_dec(r);
    put("\n");

    sys_stat("/tmp/sys_chmod.dat", buf);
    unsigned int mode = *(unsigned int *)(buf + 24) & 0777;
    put("mode=0");
    put_dec(mode);
    put("\n");

    sys_unlink("/tmp/sys_chmod.dat");
    sys_exit(0);
}
