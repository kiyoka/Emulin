/* sys_inet6_server_64.c — AF_INET6 TCP server (bind/listen/accept4) 回帰 (issue #9)
 *
 * テストする内容:
 *   1) socket(AF_INET6, SOCK_STREAM | SOCK_NONBLOCK, 0) → fd >= 0
 *   2) bind(::1, port=0) → 0   (loopback / ephemeral)
 *   3) listen(fd, 1) → 0
 *   4) getsockname → family=10, port>0, addrlen=28
 *   5) accept4(fd, ..., SOCK_NONBLOCK) → -EAGAIN (-11)
 *      (接続要求は来ていないので即 EAGAIN)
 *
 * これで bind/listen/accept4 の amd64 plumbing と AF_INET6 sockaddr_in6 の
 *   parse が hermetic に検証できる。実 client 接続テストは fork/pthread を
 *   要するため、本テストは plumbing 確認に絞る。
 *
 * 期待出力:
 *   sock_v6=1
 *   bind_rc=0
 *   listen_rc=0
 *   getsockname_rc=0
 *   addrlen=28
 *   family=10
 *   port_nonzero=1
 *   accept4_rc=-11
 */
#include "sys64.h"

#define AF_INET6      10
#define SOCK_STREAM    1
#define SOCK_NONBLOCK  0x800

void _start(void) {
    long s = sys_socket(AF_INET6, SOCK_STREAM | SOCK_NONBLOCK, 0);
    put("sock_v6=");
    put_dec(s >= 0 ? 1 : 0);
    put("\n");
    if (s < 0) sys_exit(1);

    /* sockaddr_in6: family(2) + port(2 BE) + flow(4) + addr(16) + scope(4) */
    unsigned char sa[28] = {0};
    sa[0] = AF_INET6;
    sa[1] = 0;
    sa[2] = 0; sa[3] = 0;            /* port=0 ephemeral */
    /* flow = 0 */
    /* addr = ::1 → 15 zeros + 0x01 */
    sa[8 + 15] = 0x01;
    /* scope = 0 */

    long br = sys_bind(s, sa, 28);
    put("bind_rc=");
    put_dec(br);
    put("\n");
    if (br != 0) sys_exit(2);

    long lr = sys_listen(s, 1);
    put("listen_rc=");
    put_dec(lr);
    put("\n");
    if (lr != 0) sys_exit(3);

    unsigned char gsa[28];
    unsigned int alen = 28;
    long gs = sys_getsockname(s, gsa, &alen);
    put("getsockname_rc=");
    put_dec(gs);
    put("\n");
    put("addrlen=");
    put_dec((long)alen);
    put("\n");
    unsigned int fam = (unsigned int)gsa[0] | ((unsigned int)gsa[1] << 8);
    unsigned int port = ((unsigned int)gsa[2] << 8) | (unsigned int)gsa[3];
    put("family=");
    put_dec((long)fam);
    put("\n");
    put("port_nonzero=");
    put_dec(port != 0 ? 1 : 0);
    put("\n");

    /* 接続要求は無い + listener が SOCK_NONBLOCK なので即 EAGAIN */
    unsigned char psa[28];
    alen = 28;
    long ar = sys_accept4(s, psa, &alen, 0);
    put("accept4_rc=");
    put_dec(ar);
    put("\n");

    sys_close(s);
    sys_exit(0);
}
