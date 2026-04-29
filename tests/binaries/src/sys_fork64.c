/* sys_fork64.c — fork (syscall #57)
 *
 * fork() で子プロセスを生成し、親は wait4 で子の終了を待ってから書き出す。
 * 並行実行で stdout が混ざらないよう、wait4 で同期する。
 * fork は親に子の pid、子に 0 を返す。
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        put("child\n");
        sys_exit(0);
    } else if (pid > 0) {
        int status = 0;
        sys_wait4(-1, &status, 0, 0);
        put("parent_saw_child=");
        put_dec(pid > 0 ? 1 : 0);
        put("\n");
        sys_exit(0);
    } else {
        put("fork_failed=");
        put_dec(pid);
        put("\n");
        sys_exit(1);
    }
}
