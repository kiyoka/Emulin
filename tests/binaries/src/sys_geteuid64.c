/* sys_geteuid64.c — geteuid (syscall #107) */
#include "sys64.h"

void _start(void) {
    put("euid=");
    put_dec(sys_geteuid());
    put("\n");
    sys_exit(0);
}
