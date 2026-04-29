/* sys_ftruncate64.c — ftruncate (syscall #77)
 *
 * /tmp/ft.dat に "0123456789" を書き、ftruncate(fd, 4) で 4 バイトに縮める。
 * 再 open して全部読み、サイズを確認する。
 */
#include "sys64.h"

#define O_WRONLY 1
#define O_RDONLY 0
#define O_CREAT  0100
#define O_TRUNC  01000

static char rbuf[64];

void _start(void) {
    long fd = sys_open("/tmp/ft.dat", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    sys_write(fd, "0123456789", 10);
    long r = sys_ftruncate(fd, 4);
    sys_close(fd);
    put("ftruncate=");
    put_dec(r);
    put("\n");

    fd = sys_open("/tmp/ft.dat", O_RDONLY, 0);
    long n = sys_read(fd, rbuf, sizeof(rbuf));
    sys_close(fd);
    put("size=");
    put_dec(n);
    put("\n");
    put("data=");
    sys_write(1, rbuf, n);
    put("\n");

    sys_unlink("/tmp/ft.dat");
    sys_exit(0);
}
