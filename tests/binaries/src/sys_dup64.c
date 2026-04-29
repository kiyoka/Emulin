/* sys_dup64.c — dup (syscall #32)
 *
 * stdout (fd=1) を dup して新しい fd 経由で書き込む。
 */
#include "sys64.h"

void _start(void) {
    long fd = sys_dup(1);
    put("fd_gt_2=");
    put_dec(fd > 2 ? 1 : 0);
    put("\n");

    sys_write(fd, "via_dup\n", 8);
    sys_close(fd);
    sys_exit(0);
}
