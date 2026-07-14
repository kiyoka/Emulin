/* sys_fork_eagain64.c — issue #720: fork の pool 枯渇 EAGAIN 縮退の回帰テスト。
 *
 * native backend で 32GB 窓枯渇 (issue #379) により fork 子の guest RAM pool が確保
 * できない場合、旧実装は System.exit で emulator (JVM) ごと落ちていた。修正後は Linux の
 * リソース逼迫時と同じく fork(2) が -EAGAIN を返し、親プロセスは継続できる。
 *
 * 本テストは適応型:
 *   - 通常実行 (software / native とも): fork 成功 → 子を reap → "fork=ok"
 *   - EMULIN_FORCE_POOL_EXHAUST=1 + native: fork が -EAGAIN → "fork=EAGAIN"
 * どちらでも最後に "FORK_EAGAIN ok" を出力して親が生存継続できることを証明する。
 * 通常実行の期待値は expected/sys_fork_eagain64.stdout (fork=ok 側)。EAGAIN 側は
 * tests/scripts/pool-exhaust-smoke.sh が KVM + 診断スイッチで検証する。
 *
 * 期待出力 (通常):
 *   fork=ok
 *   FORK_EAGAIN ok
 */
#include "sys64.h"

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        sys_exit(0);                       /* 子: 即終了 (親が reap) */
    } else if (pid > 0) {
        int st = 0;
        if (sys_wait4(pid, &st, 0, 0) != pid) { put("wait4 failed\n"); sys_exit(1); }
        put("fork=ok\n");
    } else if (pid == -11) {               /* -EAGAIN: pool 枯渇の縮退 (issue #720) */
        put("fork=EAGAIN\n");
    } else {
        put("fork=err "); put_dec(pid); put("\n");
        sys_exit(1);
    }
    put("FORK_EAGAIN ok\n");               /* 親プロセスが生きて続行できる証明 */
    sys_exit(0);
}
