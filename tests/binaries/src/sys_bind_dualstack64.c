/* sys_bind_dualstack64.c — IPv6 と IPv4 で同じ port を bind できること (issue #1020)
 *
 * ★ 何を守るか: **IPv6 で listen した port が IPv4 の同 port を塞がないこと**。
 *   実 Linux では、IPV6_V6ONLY を立てた IPv6 socket と IPv4 socket は
 *   同じ port を同時に持てる。Emulin では 2 つ目が EADDRINUSE になっていた。
 *
 * ★ 実害 (2026-09-08): OpenSSH の `x11_create_display_inet()` は
 *   `getaddrinfo(NULL, port, AI_PASSIVE)` が返す **IPv6 と IPv4 の両方**に bind する。
 *   2 つ目が失敗するので **`ssh -X` の X11 転送が張れない**。
 *   sshd のログには `listen: Address already in use` と出るが、
 *   ★ **bind は成功していて listen で落ちる**という実 Linux では起きない形だった。
 *
 * ★ 真因: host 側の Java socket に写す際、
 *     - AF_INET  はアドレス引数なしで bind = JVM の **デュアルスタック wildcard**
 *     - AF_INET6 の wildcard も Java ではデュアルスタック
 *   となり、どちらの順序でも互いを塞ぐ。`IPV6_V6ONLY` は設定が成功するのに効かない。
 *
 * ★ port は **getpid() から作る**。テストは並列に走るので固定 port だと
 *   自分同士で衝突して、直っていても落ちる (それでは検査にならない)。
 *
 * ★ 判定は 0/1 に落とす。そうしないと実 Linux をオラクルにできない。
 */
#include "sys64.h"

#define AF_INET   2
#define AF_INET6 10
#define SOCK_STREAM 1
#define SOL_SOCKET  1
#define SO_REUSEADDR 2
#define IPPROTO_IPV6 41
#define IPV6_V6ONLY  26

/* socket / bind / listen / close / getpid は sys64.h にある。setsockopt だけ足す。 */
static long sys_setsockopt(long fd, long lv, long op, const void *v, long l) {
    long r; register long r10 __asm__("r10") = (long)v; register long r8 __asm__("r8") = l;
    __asm__ volatile("syscall" : "=a"(r) : "0"(54LL), "D"(fd), "S"(lv), "d"(op), "r"(r10), "r"(r8)
                     : "rcx","r11","memory");
    return r;
}

static unsigned char sa6[28];
static unsigned char sa4[16];

static void mk6(int port) {
    int i; for (i = 0; i < 28; i++) sa6[i] = 0;
    sa6[0] = AF_INET6 & 0xff; sa6[1] = (AF_INET6 >> 8) & 0xff;
    sa6[2] = (port >> 8) & 0xff; sa6[3] = port & 0xff;      /* big endian */
    /* sin6_addr = :: (全 0) */
}
static void mk4(int port) {
    int i; for (i = 0; i < 16; i++) sa4[i] = 0;
    sa4[0] = AF_INET & 0xff; sa4[1] = (AF_INET >> 8) & 0xff;
    sa4[2] = (port >> 8) & 0xff; sa4[3] = port & 0xff;
    /* sin_addr = 0.0.0.0 */
}

void _start(void) {
    /* 並列実行でも衝突しない port を pid から作る */
    long pid = sys_getpid();
    int port = 21000 + (int)(pid % 3000);
    int one = 1;

    /* --- IPv6 (wildcard, V6ONLY) --- ★ OpenSSH と同じ形 --- */
    long s6 = sys_socket(AF_INET6, SOCK_STREAM, 0);
    put("s6_open=");   put_dec(s6 >= 0 ? 1 : 0); put("\n");
    sys_setsockopt(s6, SOL_SOCKET, SO_REUSEADDR, &one, 4);
    long v6 = sys_setsockopt(s6, IPPROTO_IPV6, IPV6_V6ONLY, &one, 4);
    put("s6_v6only_ok="); put_dec(v6 == 0 ? 1 : 0); put("\n");
    mk6(port);
    put("s6_bind_ok=");   put_dec(sys_bind(s6, sa6, 28) == 0 ? 1 : 0); put("\n");
    put("s6_listen_ok="); put_dec(sys_listen(s6, 5) == 0 ? 1 : 0); put("\n");

    /* --- IPv4 (wildcard) 同じ port --- ここが #1020 で落ちていた --- */
    long s4 = sys_socket(AF_INET, SOCK_STREAM, 0);
    put("s4_open=");   put_dec(s4 >= 0 ? 1 : 0); put("\n");
    sys_setsockopt(s4, SOL_SOCKET, SO_REUSEADDR, &one, 4);
    mk4(port);
    put("s4_bind_ok=");   put_dec(sys_bind(s4, sa4, 16) == 0 ? 1 : 0); put("\n");
    put("s4_listen_ok="); put_dec(sys_listen(s4, 5) == 0 ? 1 : 0); put("\n");

    sys_close(s4); sys_close(s6);

    /* --- 逆順 (IPv4 -> IPv6) も見る。実 Linux はどちらも通る --- */
    int port2 = port + 1;
    long t4 = sys_socket(AF_INET, SOCK_STREAM, 0);
    sys_setsockopt(t4, SOL_SOCKET, SO_REUSEADDR, &one, 4);
    mk4(port2);
    sys_bind(t4, sa4, 16); sys_listen(t4, 5);
    long t6 = sys_socket(AF_INET6, SOCK_STREAM, 0);
    sys_setsockopt(t6, SOL_SOCKET, SO_REUSEADDR, &one, 4);
    sys_setsockopt(t6, IPPROTO_IPV6, IPV6_V6ONLY, &one, 4);
    mk6(port2);
    put("rev_bind_ok=");   put_dec(sys_bind(t6, sa6, 28) == 0 ? 1 : 0); put("\n");
    put("rev_listen_ok="); put_dec(sys_listen(t6, 5) == 0 ? 1 : 0); put("\n");
    sys_close(t6); sys_close(t4);

    sys_exit(0);
}
