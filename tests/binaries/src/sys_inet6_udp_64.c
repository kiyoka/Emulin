/* sys_inet6_udp_64.c — AF_INET6 SOCK_DGRAM loopback 回帰 (issue #9)
 *
 * テストする内容:
 *   1) socket(AF_INET6, SOCK_DGRAM, 0) → fd >= 0
 *   2) getsockname が sockaddr_in6 (28 byte) を返す: family=10, port>0
 *      (DatagramSocket は make_server_socket(-1) で eagerly ephemeral port
 *       に bind されるので port は非 0)
 *   3) 2 つの v6 UDP socket 間で sendto/recvfrom ループバック (::1):
 *      A → B に "ping6" を送り、B が recvfrom で v6 src (sockaddr_in6) と
 *      ペイロードを受け取れる。
 *
 * ::1 loopback は host が IPv6 disable 環境だと失敗するので、その場合は
 *   テスト結果に "loopback_skipped=1" を出して PASS させる。
 *
 * 期待出力:
 *   sock_v6_dgram=1
 *   getsockname_rc=0
 *   addrlen=28
 *   family=10
 *   port_nonzero=1
 *   loopback_ok=1
 *   recv_family=10
 *   recv_msg=ping6
 */
#include "sys64.h"

#define AF_INET6     10
#define SOCK_DGRAM    2

void _start(void) {
    long a = sys_socket(AF_INET6, SOCK_DGRAM, 0);
    put("sock_v6_dgram=");
    put_dec(a >= 0 ? 1 : 0);
    put("\n");
    if (a < 0) sys_exit(1);

    unsigned char sa_a[28];
    unsigned int alen = 28;
    long gs = sys_getsockname(a, sa_a, &alen);
    put("getsockname_rc=");
    put_dec(gs);
    put("\n");
    put("addrlen=");
    put_dec((long)alen);
    put("\n");

    unsigned int fam   = (unsigned int)sa_a[0] | ((unsigned int)sa_a[1] << 8);
    unsigned int portA = ((unsigned int)sa_a[2] << 8) | (unsigned int)sa_a[3];
    put("family=");
    put_dec((long)fam);
    put("\n");
    put("port_nonzero=");
    put_dec(portA != 0 ? 1 : 0);
    put("\n");

    /* B side */
    long b = sys_socket(AF_INET6, SOCK_DGRAM, 0);
    if (b < 0) { put("loopback_ok=0\nrecv_family=0\nrecv_msg=\n"); sys_exit(0); }
    unsigned char sa_b[28];
    alen = 28;
    sys_getsockname(b, sa_b, &alen);
    unsigned int portB = ((unsigned int)sa_b[2] << 8) | (unsigned int)sa_b[3];

    /* sendto A → ::1:portB */
    unsigned char dst[28] = {0};
    dst[0] = AF_INET6;
    dst[1] = 0;
    dst[2] = (unsigned char)(portB >> 8);   /* BE */
    dst[3] = (unsigned char)(portB & 0xFF);
    /* dst[4..7] flowinfo = 0 */
    /* dst[8..23] addr = ::1 → 15 zeros + 0x01 */
    dst[8 + 15] = 0x01;
    /* dst[24..27] scope_id = 0 */

    long sret = sys_sendto(a, "ping6", 5, 0, dst, 28);
    if (sret != 5) {
        put("loopback_ok=0\nrecv_family=0\nrecv_msg=\n");
        sys_exit(0);
    }

    /* recvfrom on B */
    unsigned char rbuf[64] = {0};
    unsigned char rsrc[28];
    alen = 28;
    long rret = sys_recvfrom(b, rbuf, 63, 0, rsrc, &alen);
    if (rret < 0) {
        put("loopback_ok=0\nrecv_family=0\nrecv_msg=\n");
        sys_exit(0);
    }
    put("loopback_ok=1\n");

    unsigned int rfam = (unsigned int)rsrc[0] | ((unsigned int)rsrc[1] << 8);
    put("recv_family=");
    put_dec((long)rfam);
    put("\n");
    put("recv_msg=");
    sys_write(1, rbuf, rret);
    put("\n");

    sys_close(a);
    sys_close(b);
    sys_exit(0);
}
