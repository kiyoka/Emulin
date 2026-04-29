/* sys_uname64.c — uname (syscall #63)
 *
 * struct utsname (Linux): 5 つの 65 文字フィールド (sysname/nodename/release/version/machine)。
 * sysname を出力する。
 */
#include "sys64.h"

static char buf[6 * 65];

void _start(void) {
    long r = sys_uname(buf);
    put("ret=");
    put_dec(r);
    put("\n");
    put("sysname=");
    put(buf);
    put("\n");
    sys_exit(0);
}
