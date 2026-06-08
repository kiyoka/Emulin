/* sys_execve_self64.c — execve (syscall #59) を fork 無しで検証 (issue #221 native 用)
 *
 * 自プロセスが直接 execve("/bin/hello64") して自身を置き換える。fork を使わないので
 * native backend (fork 未対応) でも execve 単独の動作を検証できる。execve は現プロセスの
 * アドレス空間を新 ELF で置換するので、成功すると hello64 の出力だけが出て本 binary の
 * "exec_failed" は出ない。
 *
 * 期待出力 (hello64 の出力):
 *   hello world
 */
#include "sys64.h"

void _start(void) {
    char *argv[] = { "/bin/hello64", 0 };
    char *envp[] = { 0 };
    sys_execve("/bin/hello64", argv, envp);
    /* execve 成功なら到達しない */
    put("exec_failed\n");
    sys_exit(1);
}
