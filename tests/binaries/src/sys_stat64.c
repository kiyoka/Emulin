/* sys_stat64.c — stat (syscall #4)
 *
 * /etc/emulin.cnf を stat して st_mode フィールドの S_IFREG ビットを確認する。
 */
#include "sys64.h"

#define S_IFMT  0170000
#define S_IFREG 0100000

/* AMD64 struct stat: st_mode は offset 24 (4 bytes) */
static char buf[256];

void _start(void) {
    long r = sys_stat("/etc/emulin.cnf", buf);
    put("ret=");
    put_dec(r);
    put("\n");

    unsigned int mode = *(unsigned int *)(buf + 24);
    put("isreg=");
    put_dec((mode & S_IFMT) == S_IFREG ? 1 : 0);
    put("\n");
    sys_exit(0);
}
