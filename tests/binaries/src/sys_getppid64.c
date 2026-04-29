/* sys_getppid64.c — getppid (syscall #110) */
#include "sys64.h"

void _start(void) {
    put("ppid=");
    put_dec(sys_getppid());
    put("\n");
    sys_exit(0);
}
