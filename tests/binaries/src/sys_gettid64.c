/* sys_gettid64.c — gettid (syscall #186) */
#include "sys64.h"

void _start(void) {
    put("tid=");
    put_dec(sys_gettid());
    put("\n");
    sys_exit(0);
}
