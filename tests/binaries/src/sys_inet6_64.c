/* sys_inet6_64.c — AF_INET6 socket + getsockname/getpeername 回帰 (issue #9)
 *
 * AF_INET6 サポートの最小ライン:
 *   1) socket(AF_INET6, SOCK_STREAM, 0)  → fd >= 0
 *   2) getsockname() で sockaddr_in6 (28 byte) が返る、
 *      family=10 (AF_INET6), port=0, addr=:: (16 zeros)
 *   3) getpeername() は未接続なので -ENOTCONN (-107)
 *   4) socket(99, ...)  → -EAFNOSUPPORT (-97)  ※未対応ドメイン
 *
 * 実際の v6 接続テストは外部ネットワーク必須なので別レイヤ
 *   (real-coreutils.sh の wget/curl) で扱う。本テストは hermetic。
 *
 * 期待出力:
 *   sock_v6_nonneg=1
 *   getsockname_rc=0
 *   addrlen=28
 *   family=10
 *   port=0
 *   addr_zero=1
 *   getpeername_rc=-107
 *   sock_bad_rc=-97
 */
#include "sys64.h"

#define AF_INET6   10
#define SOCK_STREAM 1

void _start(void) {
    long sfd = sys_socket(AF_INET6, SOCK_STREAM, 0);
    put("sock_v6_nonneg=");
    put_dec(sfd >= 0 ? 1 : 0);
    put("\n");
    if (sfd < 0) sys_exit(1);

    /* sockaddr_in6 layout (28 byte):
     *   u16 sin6_family + u16 sin6_port(BE) + u32 flowinfo
     *   + u8[16] addr + u32 scope_id                       */
    unsigned char buf[64];
    int i;
    for (i = 0; i < 64; i++) buf[i] = 0xCC;  /* sentinel */
    unsigned int alen = 64;

    long gs = sys_getsockname(sfd, buf, &alen);
    put("getsockname_rc=");
    put_dec(gs);
    put("\n");

    put("addrlen=");
    put_dec((long)alen);
    put("\n");

    unsigned int fam  = (unsigned int)buf[0] | ((unsigned int)buf[1] << 8);
    unsigned int portB = ((unsigned int)buf[2] << 8) | (unsigned int)buf[3];
    put("family=");
    put_dec((long)fam);
    put("\n");
    put("port=");
    put_dec((long)portB);
    put("\n");

    int all_zero = 1;
    for (i = 0; i < 16; i++) if (buf[8 + i] != 0) { all_zero = 0; break; }
    put("addr_zero=");
    put_dec((long)all_zero);
    put("\n");

    long gp = sys_getpeername(sfd, buf, &alen);
    put("getpeername_rc=");
    put_dec(gp);
    put("\n");

    sys_close(sfd);

    /* 未対応ドメインは EAFNOSUPPORT で蹴られる */
    long bad = sys_socket(99, SOCK_STREAM, 0);
    put("sock_bad_rc=");
    put_dec(bad);
    put("\n");

    sys_exit(0);
}
