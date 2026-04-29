/* sys_rename64.c — rename (syscall #82)
 *
 * /tmp/rename_a.dat → /tmp/rename_b.dat にリネーム。
 */
#include "sys64.h"

#define F_OK 0
#define O_WRONLY 1
#define O_CREAT  0100
#define O_TRUNC  01000

void _start(void) {
    sys_unlink("/tmp/rename_a.dat");
    sys_unlink("/tmp/rename_b.dat");

    long fd = sys_open("/tmp/rename_a.dat", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    sys_close(fd);

    long r = sys_rename("/tmp/rename_a.dat", "/tmp/rename_b.dat");
    put("rename=");
    put_dec(r);
    put("\n");

    long a = sys_access("/tmp/rename_a.dat", F_OK);
    long b = sys_access("/tmp/rename_b.dat", F_OK);
    put("a_gone=");
    put_dec(a);
    put("\n");
    put("b_here=");
    put_dec(b);
    put("\n");

    sys_unlink("/tmp/rename_b.dat");
    sys_exit(0);
}
