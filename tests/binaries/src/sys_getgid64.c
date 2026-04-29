/* sys_getgid64.c — getgid (syscall #104) */
#include "sys64.h"

void _start(void) {
    put("gid=");
    put_dec(sys_getgid());
    put("\n");
    sys_exit(0);
}
