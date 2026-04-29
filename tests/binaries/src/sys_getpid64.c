/* sys_getpid64.c — getpid (syscall #39) */
#include "sys64.h"

void _start(void) {
    long pid = sys_getpid();
    put("pid=");
    put_dec(pid);
    put("\n");
    sys_exit(0);
}
