/* sys_poll_unix_socket_64.c — poll(2) で AF_UNIX 接続済 socket の POLLIN を検出
 *
 * issue #131 (tmux): tmux client (libevent poll backend、EVENT_NOEPOLL=1) は
 * server から MSG_WRITE_OPEN (12 byte) を受け取って MSG_WRITE_READY を返す。
 * その判定は poll() で server 側 socket fd の POLLIN を見ている。
 *
 * 旧 amd64_poll は AF_UNIX 接続済 socket (`finfo.unixSocket`) の POLLIN 経路を
 * 持っておらず、`finfo.isSOCKET() && finfo.conn == null` (= TCP socket でない)
 * 経路に落ちて何もしなかった。amd64_pselect6 は line 1336 周辺で既に同等の
 * 経路を実装済 (issue #113) だが、amd64_poll は欠落していた。結果 tmux
 * client が server からの MSG_WRITE_OPEN を検出できず、`tmux ls` は host
 * stdout 空のまま 20s timeout で exit していた。
 *
 * 修正: amd64_poll にも pselect と同じく non-blocking 1-byte peek を実装し、
 * Fileinfo.peekBuf に積んで POLLIN を立てる。
 *
 * シナリオ (socketpair は emulin で pipe 実装になり unixSocket を経由しない
 * ため、本テストは AF_UNIX bind/listen/accept で実 unixSocket を作る):
 *   1. AF_UNIX SOCK_STREAM socket を作って /tmp/sys_poll_unix.sock に bind/listen
 *   2. fork: 子は connect して 100ms 後に "hello\n" (6 byte) を write
 *   3. 親は accept、その後 poll(POLLIN, 500ms) で child の write 到着を検出
 *   4. revents が POLLIN (=1) であることを確認
 *   5. read で 6 byte 取得、先頭 'h' (=104) を確認
 */
#include "sys64.h"

struct pollfd_local { int fd; short events; short revents; };
struct ts { long sec; long nsec; };

void _start(void) {
    sys_unlink("/tmp/sys_poll_unix.sock");

    /* server socket */
    long sfd = sys_socket(1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0);
    char sun[112];
    sun[0] = 1; sun[1] = 0;
    const char *path = "/tmp/sys_poll_unix.sock";
    int i = 0;
    for (; path[i]; i++) sun[2 + i] = path[i];
    sun[2 + i] = 0;
    long br = sys_bind(sfd, sun, 2 + i + 1);
    put("bind="); put_dec(br); put("\n");
    long lr = sys_listen(sfd, 1);
    put("listen="); put_dec(lr); put("\n");

    long c = sys_fork();
    if (c == 0) {
        /* child: connect → 100ms 後に write */
        long cfd = sys_socket(1, 1, 0);
        struct ts t0 = { 0, 50L * 1000L * 1000L };
        sys_nanosleep(&t0, 0);  /* parent の accept を待つ */
        sys_connect(cfd, sun, 2 + i + 1);
        struct ts t1 = { 0, 100L * 1000L * 1000L };
        sys_nanosleep(&t1, 0);  /* parent の poll を起動させる */
        sys_write(cfd, "hello\n", 6);
        struct ts t2 = { 0, 200L * 1000L * 1000L };
        sys_nanosleep(&t2, 0);  /* parent が read 終わるまで socket open のまま */
        sys_exit(0);
    }

    /* parent: accept */
    long afd = sys_accept4(sfd, 0, 0, 0);
    put("accept="); put_dec(afd > 0 ? 1 : 0); put("\n");

    /* poll で data 待ち (500ms 以内に child が write する) */
    struct pollfd_local pfd;
    pfd.fd = (int)afd;
    pfd.events = 1;  /* POLLIN */
    pfd.revents = 0;
    long pr = sys_poll(&pfd, 1, 500);
    put("poll="); put_dec(pr); put("\n");
    put("revents=0x"); put_dec(pfd.revents & 0xff); put("\n");

    /* read */
    char buf[16];
    long rr = sys_read(afd, buf, 6);
    put("read="); put_dec(rr); put("\n");
    put("first_byte="); put_dec(buf[0] & 0xff); put("\n");

    /* child reap */
    int status = 0;
    sys_wait4(c, &status, 0, 0);

    sys_close((long)afd);
    sys_close(sfd);
    sys_unlink("/tmp/sys_poll_unix.sock");
    sys_exit(0);
}
