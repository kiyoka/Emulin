/* sys_nonblock_connect_64.c — 非blocking connect → POLLOUT → getsockopt(SO_ERROR)
 *   → accept → 非blocking read のフルパスを自己完結 (loopback) で検証する。
 *
 * issue #113 調査由来: emacs の async url-retrieve (make-network-process :nowait t)
 * が使う経路。非blocking connect が EINPROGRESS を返し、poll(POLLOUT) で接続完了を
 * 検出、getsockopt(SO_ERROR)=0 で確認、その後データを非blocking read する一連の
 * plumbing を hermetic に固定する (外部 server 不要、127.0.0.1 ephemeral port で
 * listen → 同一プロセス内で connect/accept)。
 *
 * 期待出力:
 *   connect=EINPROGRESS
 *   pollout=1
 *   so_error=0
 *   accept-ok
 *   recv=PING
 */
#include "sys64.h"

#define AF_INET        2
#define SOCK_STREAM    1
#define SOCK_NONBLOCK  0x800
#define POLLIN  0x001
#define POLLOUT 0x004

static void mk_sockaddr(unsigned char *sa, int portBE_hi, int portBE_lo) {
    for (int i = 0; i < 16; i++) sa[i] = 0;
    sa[0] = AF_INET; sa[1] = 0;
    sa[2] = (unsigned char)portBE_hi; sa[3] = (unsigned char)portBE_lo;
    sa[4] = 127; sa[5] = 0; sa[6] = 0; sa[7] = 1;   /* 127.0.0.1 */
}

void _start(void) {
    /* 1) listen socket (blocking) を 127.0.0.1:0 (ephemeral) に */
    long L = sys_socket(AF_INET, SOCK_STREAM, 0);
    if (L < 0) { put("LISTEN-SOCK-FAIL\n"); sys_exit(1); }
    unsigned char la[16]; mk_sockaddr(la, 0, 0);   /* port 0 = ephemeral */
    if (sys_bind(L, la, 16) < 0) { put("BIND-FAIL\n"); sys_exit(1); }
    if (sys_listen(L, 4) < 0) { put("LISTEN-FAIL\n"); sys_exit(1); }

    /* bound port を getsockname で取得 */
    unsigned char na[16]; int nalen = 16;
    if (sys_getsockname(L, na, &nalen) < 0) { put("GETSOCKNAME-FAIL\n"); sys_exit(1); }
    int port_hi = na[2], port_lo = na[3];   /* sin_port (BE) */

    /* 2) client socket (NONBLOCK) を connect → EINPROGRESS 期待 */
    long C = sys_socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (C < 0) { put("CLIENT-SOCK-FAIL\n"); sys_exit(1); }
    unsigned char ca[16]; mk_sockaddr(ca, port_hi, port_lo);
    long cr = sys_connect(C, ca, 16);
    put(cr == -115 ? "connect=EINPROGRESS\n" : (cr == 0 ? "connect=OK\n" : "connect=ERR\n"));

    /* 3) poll(POLLOUT) で接続完了待ち */
    unsigned char pfd[8];
    for (int i = 0; i < 8; i++) pfd[i] = 0;
    *(int *)pfd = (int)C; pfd[4] = POLLOUT;
    long pr = sys_poll(pfd, 1, 3000);
    int revents = pfd[6] | (pfd[7] << 8);
    put("pollout="); put_dec((pr == 1 && (revents & POLLOUT)) ? 1 : 0); put("\n");

    /* 4) getsockopt(SO_ERROR) = 0 */
    int soerr = -1, optlen = 4;
    sys_getsockopt(C, 1 /*SOL_SOCKET*/, 4 /*SO_ERROR*/, &soerr, &optlen);
    put("so_error="); put_dec(soerr); put("\n");

    /* 5) accept で server 側 socket を得る (connect は同期完了済なので即返る) */
    unsigned char aa[16]; int aalen = 16;
    long S = sys_accept4(L, aa, &aalen, 0);
    put(S >= 0 ? "accept-ok\n" : "accept-fail\n");
    if (S < 0) sys_exit(1);

    /* 6) server → client へ書き、client (NONBLOCK) で read */
    sys_write(S, "PING", 4);
    pfd[4] = POLLIN; pfd[5] = 0; pfd[6] = 0; pfd[7] = 0;
    *(int *)pfd = (int)C;
    char buf[16];
    long total = 0;
    for (int tries = 0; tries < 30 && total < 4; tries++) {
        long r = sys_read(C, buf + total, 4 - total);
        if (r > 0) total += r;
        else if (r == 0) break;
        else { pfd[4] = POLLIN; pfd[5] = 0; sys_poll(pfd, 1, 300); }
    }
    put("recv=");
    for (long i = 0; i < total; i++) sys_write(1, buf + i, 1);
    put("\n");

    sys_close(C); sys_close(S); sys_close(L);
    sys_exit(0);
}
