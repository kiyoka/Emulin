/* sys_dup_fork_eof_64.c — pipe を dup してから fork し、全 writer fd を閉じたら
 *   read 端が必ず EOF になることを検証する (refcount leak の回帰検出)。
 *
 * issue #349 (pipe race) の regression guard。pipe write 端を dup2 で複製してから
 *   fork すると、子は dup された 2 つの fd slot を継承する。全ての writer fd
 *   (親側 2 つ + 子側 2 つ) を閉じれば o_connected は 0 になり、read 端は EOF
 *   (read==0) を返さなければならない。refcount が残留すると read が永久 block
 *   する (= apt の status pipe hang と同型) ため、read 端を非ブロッキングにして
 *   EOF / STUCK を確定的に出力する。
 */
#include "sys64.h"

#define F_GETFL    3
#define F_SETFL    4
#define O_NONBLOCK 0x800

void _start(void) {
    int fds[2];
    sys_pipe((long *)fds);
    int rd = fds[0];
    int wr = fds[1];

    /* 親で write 端を高 fd に複製 (wr と 21 が同一 Fileinfo を共有) */
    sys_dup2(wr, 21);

    long pid = sys_fork();
    if (pid == 0) {
        /* 子: 継承した write 端を 2 つとも閉じて終了 (書き込みはしない) */
        sys_close(wr);
        sys_close(21);
        sys_close(rd);
        sys_exit(0);
    }

    /* 親: 自分の write 端を 2 つとも閉じる */
    sys_close(wr);
    sys_close(21);

    int status;
    sys_wait4(pid, &status, 0, 0);   /* 子の終了で子側の writer も全て解放 */

    /* これで writer は 1 つも残っていないので read 端は EOF になるはず。
       refcount leak だと block するので非ブロッキングで EOF/STUCK を判定。 */
    long fl = sys_fcntl(rd, F_GETFL, 0);
    sys_fcntl(rd, F_SETFL, fl | O_NONBLOCK);

    char buf[8];
    int tries = 0;
    for (;;) {
        long n = sys_read(rd, buf, 4);
        if (n == 0) { put("eof=1\n"); break; }          /* 正常: EOF */
        if (n > 0)  { continue; }                        /* 念のため余剰 data を捨てる */
        if (++tries > 2000) { put("eof=0 STUCK\n"); break; } /* refcount leak */
    }

    sys_close(rd);
    sys_exit(0);
}
