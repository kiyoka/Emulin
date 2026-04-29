/* sys_chdir64.c — chdir (syscall #80)
 *
 * /tmp に chdir する。戻り値だけ検証する (getcwd は別 syscall)。
 */
#include "sys64.h"

void _start(void) {
    long r = sys_chdir("/tmp");
    put("chdir=");
    put_dec(r);
    put("\n");

    long r2 = sys_chdir("/no/such/dir");
    put("missing=");
    put_dec(r2);
    put("\n");
    sys_exit(0);
}
