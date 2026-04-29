/* sys_execve64.c — execve (syscall #59)
 *
 * fork してから子プロセスで execve を呼び /bin/hello64 を起動する。
 * 親は wait4 で子の終了を待ってから自分の出力を行う。
 * 期待出力 (hello64 の "hello world" + 親の "parent_done"):
 *   hello world
 *   parent_done
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        /* 子: hello64 を exec */
        char *argv[] = { "/bin/hello64", 0 };
        char *envp[] = { 0 };
        sys_execve("/bin/hello64", argv, envp);
        /* exec 失敗時のみここに来る */
        put("exec_failed\n");
        sys_exit(1);
    }
    /* 親: 子を待つ */
    int status = 0;
    sys_wait4(-1, &status, 0, 0);
    put("parent_done\n");
    sys_exit(0);
}
