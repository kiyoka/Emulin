/* sys_wait4_64.c — wait4 (syscall #61)
 *
 * fork した子を wait4 で回収し、status と返り値を確認する。
 * 子は終了コード 0 で抜けるので、status は WIFEXITED かつ exit code 0。
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        /* 子: ただ exit する */
        sys_exit(0);
    }
    /* 親: wait4 で回収 */
    int status = -1;
    long r = sys_wait4(-1, &status, 0, 0);
    put("wait_pid_matches=");
    put_dec(r == pid ? 1 : 0);
    put("\n");
    put("status_zero=");
    put_dec(status == 0 ? 1 : 0);
    put("\n");
    sys_exit(0);
}
