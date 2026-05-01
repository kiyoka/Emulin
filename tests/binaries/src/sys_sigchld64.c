/* sys_sigchld64.c — 子プロセス終了時の SIGCHLD 自動配信テスト
 *
 * Phase 23 (signal 拡張): 子プロセスが exit したら親に SIGCHLD が
 * 自動配信されることを検証する。
 *
 * シナリオ:
 *   1. SIGCHLD ハンドラを登録
 *   2. fork() → 子は即 exit(7)
 *   3. 親は wait4(-1) でブロック
 *   4. SIGCHLD が来てハンドラが走る (handler= が出る)
 *   5. wait4 は EINTR で抜けるか、子の pid を返すか、いずれにせよ
 *      最終的に exit_code=7 を取得できる
 *
 * 期待出力:
 *   handler
 *   wstatus=0x700
 *   ok
 */
#include "sys64.h"

#define SIGCHLD 17

static volatile long handler_called = 0;
static long sa[4];

void handler(long sig) {
    sys_write(1, "handler\n", 8);
    handler_called = 1;
}

void _start(void) {
    sa[0] = (long)&handler;
    sa[1] = 0;
    sa[2] = 0;
    sa[3] = 0;
    sys_rt_sigaction(SIGCHLD, sa, 0, 8);

    long pid = sys_fork();
    if (pid == 0) {
        /* 子: そのまま exit */
        sys_exit(7);
    }
    /* 親: wait4(-1) で子の終了を待つ */
    long status = 0;
    long ret;
    do {
        ret = sys_wait4(-1, &status, 0, 0);
        /* EINTR (-4) なら再試行 */
    } while (ret == -4);

    put("wstatus=");
    put_hex(status);
    put("\n");
    /* status = (exit_code & 0xFF) << 8 = 7 << 8 = 0x700 */
    if (status == 0x700 && handler_called) put("ok\n");
    else                                   put("ng\n");
    sys_exit(0);
}
