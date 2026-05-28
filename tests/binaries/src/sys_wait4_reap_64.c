/* sys_wait4_reap_64.c — wait4(specific pid) が zombie を確実に reap する検証
 *
 * tmux server で utempter helper を wait4(pid=6) で reap した後、wait4(-1) が
 * 同じ pid=6 を再度返すバグを修正したことの regression test。
 *
 * シナリオ:
 *   1. 2 つの子プロセスを fork (A, B)
 *   2. 両方とも exit
 *   3. wait4(A_pid) で A を reap
 *   4. wait4(-1) で B を取れる (= 旧実装では A が再返却で fail)
 *   5. wait4(-1) は ECHILD (-10) — 全 child reaped
 */
#include "sys64.h"

void _start(void) {
    long a = sys_fork();
    if (a == 0) { sys_exit(11); }
    long b = sys_fork();
    if (b == 0) { sys_exit(22); }

    /* a, b 両方 exit する時間を作る (sched_yield 相当の syscall 連発) */
    for (int i = 0; i < 10; i++) (void)sys_getpid();

    int status_a = 0;
    long r1 = sys_wait4(a, &status_a, 0, 0);
    put("wait_a="); put_dec(r1); put(" status="); put_dec((status_a >> 8) & 0xff); put("\n");

    int status_b = 0;
    long r2 = sys_wait4(-1, &status_b, 0, 0);
    put("wait_any="); put_dec(r2); put(" status="); put_dec((status_b >> 8) & 0xff); put("\n");

    int status_c = 0;
    long r3 = sys_wait4(-1, &status_c, 0, 0);
    put("wait_again="); put_dec(r3); put("\n");

    sys_exit(0);
}
