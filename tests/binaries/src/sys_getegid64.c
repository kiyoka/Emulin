/* sys_getegid64.c — getegid (syscall #108) */
#include "sys64.h"

void _start(void) {
    put("egid=");
    put_dec(sys_getegid());
    put("\n");
    sys_exit(0);
}
