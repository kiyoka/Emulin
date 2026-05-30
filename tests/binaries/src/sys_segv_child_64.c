/* sys_segv_child_64.c — fork した子が SIGSEGV しても親 (と JVM) が生き残ることを検証
 *
 * issue #113: 旧実装は子 process の segfault (Memory の out-of-bounds) で
 * System.exit(1) を呼び JVM 全体が落ちていた。これを「その process だけを
 * SIGSEGV (signal 11) で終了 + 親へ SIGCHLD」(real Linux 挙動) に変えた回帰固定。
 *
 * 子: unmapped アドレスへ store → SIGSEGV。
 * 親: wait4(-1) で子を reap し、WIFSIGNALED かつ WTERMSIG=11 を受け取り、
 *     自身は生き残って (parent_alive=1 を出力して) 正常 exit する。
 *
 * 注意: 子の segfault crash dump は process.println 経由で stdout に出るため、
 *   本テストは run-test.sh の exact-diff ではなく tests/scripts/segv-child-smoke.sh
 *   の grep 判定で検証する (expected/.skip で normal loop からは除外)。
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        /* 子: 確実に unmapped な低位アドレスへ store8 → store8_slow → raiseSegv */
        volatile char *bad = (volatile char *)0x12345;
        *bad = 1;
        sys_exit(0);   /* 到達しない */
    }
    int status = -1;
    long r = sys_wait4(-1, &status, 0, 0);
    put("wait_pid_matches="); put_dec(r == pid ? 1 : 0); put("\n");
    int term = status & 0x7f;                 /* WTERMSIG */
    put("wifsignaled="); put_dec((term != 0 && term != 0x7f) ? 1 : 0); put("\n");
    put("wtermsig=");    put_dec(term); put("\n");
    /* ここに到達した時点で「子 segfault が親/JVM を巻き込まなかった」証拠 */
    put("parent_alive=1\n");
    sys_exit(0);
}
