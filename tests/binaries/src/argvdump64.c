/* argvdump64.c — argc/argv/envp を dump して stack layout を検証
 */
#include "sys64.h"

void _start(void) {
    long argc;
    char **argv;
    __asm__ volatile(
        "movq 8(%%rbp), %0\n"
        "leaq 16(%%rbp), %1\n"
        : "=r"(argc), "=r"(argv)
    );
    put("argc=");
    put_dec(argc);
    put("\n");
    for (long i = 0; i < argc; i++) {
        put("argv[");
        put_dec(i);
        put("]=");
        put(argv[i] ? argv[i] : "(null)");
        put("\n");
    }
    /* argv[argc] should be NULL, then envp follows */
    char **envp = argv + argc + 1;
    for (long i = 0; envp[i] && i < 20; i++) {
        put("env[");
        put_dec(i);
        put("]=");
        put(envp[i]);
        put("\n");
    }
    sys_exit(0);
}
