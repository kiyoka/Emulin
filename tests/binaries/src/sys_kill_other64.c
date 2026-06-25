/* sys_kill_other64.c — issue #411: kill(pid, sig) で別プロセスを signal 送出して終了させる
 *   経路の回帰 (プロセス管理 = ps で pid を見つけて kill する実利用の核)。
 *
 *   parent が child を fork → child は pause() で待機 → parent が kill(child, SIGTERM)
 *   → child は default action (terminate) で死ぬ → wait4 が WIFSIGNALED(SIGTERM) を返す。
 *
 * 期待出力:
 *   kill: rc=0 wait=<childpid==parent observed> termsig=15
 *   KILL ok
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid < 0) { put("FORK_FAIL\n"); sys_exit(1); }
    if (pid == 0) {
        /* child: signal が来るまで待機 (SIGTERM default = terminate で死ぬ) */
        for (;;) sys_pause();
        sys_exit(0);
    }
    /* parent: child が pause に入るのを ~150ms 待ってから kill */
    long ts[2]; ts[0] = 0; ts[1] = 150000000L;  /* 0.15s */
    sys_nanosleep(ts, 0);

    long kr = sys_kill(pid, 15);                 /* SIGTERM */

    int status = 0;
    long w = sys_wait4(pid, &status, 0, 0);
    int termsig = status & 0x7f;                 /* WIFSIGNALED: 下位 7bit = 死因 signal */

    /* 出力は決定的に: rc / termsig / 子が wait で回収できたか (pid 値自体は出さない) */
    put("kill: rc=");    put_dec(kr);
    put(" reaped=");     put((w == pid) ? "1" : "0");
    put(" termsig=");    put_dec(termsig);
    put("\n");
    if (kr == 0 && w == pid && termsig == 15) put("KILL ok\n");
    else put("KILL FAIL\n");
    sys_exit(0);
}
