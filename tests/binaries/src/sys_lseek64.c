/* sys_lseek64.c — lseek (syscall #8)
 *
 * /tmp/lseek.dat に "0123456789" を書き、
 * O_RDONLY で開き直して lseek(SEEK_SET, 5) → 1 バイト read で '5' を確認。
 */
#include "sys64.h"

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT  0100
#define O_TRUNC  01000
#define SEEK_SET 0

void _start(void) {
    long fd = sys_open("/tmp/lseek.dat", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    sys_write(fd, "0123456789", 10);
    sys_close(fd);

    fd = sys_open("/tmp/lseek.dat", O_RDONLY, 0);
    long pos = sys_lseek(fd, 5, SEEK_SET);
    put("pos=");
    put_dec(pos);
    put("\n");

    char c;
    sys_read(fd, &c, 1);
    sys_close(fd);
    put("byte=");
    sys_write(1, &c, 1);
    put("\n");
    sys_exit(0);
}
