/* sys_unlink64.c — unlink (syscall #87)
 *
 * /tmp/sys_unlink.dat を作成 → unlink → access で消えていることを確認。
 */
#include "sys64.h"

#define F_OK 0
#define O_WRONLY 1
#define O_CREAT  0100
#define O_TRUNC  01000

void _start(void) {
    long fd = sys_open("/tmp/sys_unlink.dat", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    sys_write(fd, "x", 1);
    sys_close(fd);

    long r = sys_unlink("/tmp/sys_unlink.dat");
    put("unlink=");
    put_dec(r);
    put("\n");

    long a = sys_access("/tmp/sys_unlink.dat", F_OK);
    put("access=");
    put_dec(a);
    put("\n");
    sys_exit(0);
}
