/* sys_wait4_wuntraced_64.c — waitpid(-1, &status, WNOHANG|WUNTRACED) の非ブロック動作
 *
 * issue #131 (tmux): tmux server の SIGCHLD handler は
 *   `while ((pid = waitpid(WAIT_ANY, &status, WNOHANG|WUNTRACED)) > 0) ...`
 * で reap ループを回す (server.c L467)。emulin の wait4 は旧実装で
 * `options == WNOHANG` の完全一致比較を使っていたため、options=0x3
 * (WNOHANG|WUNTRACED) を blocking 経路と誤分類し、SIGCHLD handler 内で
 * 5ms sleep を繰り返した。結果 tmux server が動かなくなり
 * `tmux new-session -d` 後 session が即破綻して見えていた。本テストは
 * bitwise AND 修正の regression 固定。
 *
 * シナリオ:
 *   1. fork して child は 300ms nanosleep してから exit(7)
 *   2. parent は 50ms sleep して child を sleep 状態に入れた後、
 *      WNOHANG|WUNTRACED で wait4 → 即 0 を期待
 *      (旧実装は child の exit を待ってしまい 0 ではなく child pid が返る)
 *   3. 改めて blocking wait4 で child を reap
 *   4. WNOHANG 再呼び出しで ECHILD を確認
 */
#include "sys64.h"

struct ts { long sec; long nsec; };

void _start(void) {
    long c = sys_fork();
    if (c == 0) {
        struct ts t = { 0, 300L * 1000L * 1000L };  /* 300ms */
        sys_nanosleep(&t, 0);
        sys_exit(7);
    }

    /* parent: child が nanosleep に入る時間を確保 */
    struct ts t0 = { 0, 50L * 1000L * 1000L };  /* 50ms */
    sys_nanosleep(&t0, 0);

    /* WNOHANG | WUNTRACED で wait → 子はまだ sleep 中、即 0 が期待値 */
    int status1 = 0;
    long r1 = sys_wait4(-1, &status1, 3 /* WNOHANG|WUNTRACED */, 0);
    put("nohang_wuntraced="); put_dec(r1); put("\n");

    /* child の sleep 完了を待って blocking で reap */
    int status2 = 0;
    long r2 = sys_wait4(-1, &status2, 0, 0);
    put("reaped_pid="); put_dec(r2);
    put(" status="); put_dec((status2 >> 8) & 0xFF); put("\n");

    /* 残子なし */
    long r3 = sys_wait4(-1, 0, 1 /* WNOHANG */, 0);
    put("no_more="); put_dec(r3); put("\n");

    sys_exit(0);
}
