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
    /* argv[argc] should be NULL, then envp follows.
     * 先頭 5 個の絶対不変 env だけ出す。それ以降は host passthrough
     * (LESSCHARSET, TERM, LANG 等) で host 環境依存のため check しない。
     * stack layout (argc/argv/envp/auxv の順番) の検証が目的。 */
    char **envp = argv + argc + 1;
    for (long i = 0; envp[i] && i < 5; i++) {
        put("env[");
        put_dec(i);
        put("]=");
        put(envp[i]);
        put("\n");
    }
    sys_exit(0);
}
