/* sys_fstat64.c — fstat (syscall #5)
 *
 * stdout (fd=1) を fstat し、st_mode の S_IFCHR ビットを検証する。
 * ターミナル相当として返ることを期待。
 */
#include "sys64.h"

#define S_IFMT  0170000
#define S_IFCHR 0020000

static char buf[256];

void _start(void) {
    long r = sys_fstat(1, buf);
    put("ret=");
    put_dec(r);
    put("\n");

    unsigned int mode = *(unsigned int *)(buf + 24);
    put("ischr=");
    put_dec((mode & S_IFMT) == S_IFCHR ? 1 : 0);
    put("\n");
    sys_exit(0);
}
