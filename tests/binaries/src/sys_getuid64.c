/* sys_getuid64.c — getuid (syscall #102) */
#include "sys64.h"

void _start(void) {
    put("uid=");
    put_dec(sys_getuid());
    put("\n");
    sys_exit(0);
}
