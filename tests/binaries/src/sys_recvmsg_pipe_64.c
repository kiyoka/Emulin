/* sys_recvmsg_pipe_64.c — recvmsg(2) on non-socket fd は ENOTSOCK (issue #131)
 *
 * tmux / libevent は signal の self-pipe 等を pipe2 で作って event loop で
 * recvmsg を試行することがある。実 Linux は pipe fd の recvmsg を ENOTSOCK
 * (-88) で返すが、emulin は旧実装で finfo.Read (pipe 非対応) に落とし
 *   ・f == null → -21 (EISDIR)
 *   ・recvmsg ラッパで「r < 0 → -104 ECONNRESET」
 * の経路で -104 を返していた。libevent はこれを「peer reset」と誤判定して
 * server を畳んでいた (PR #142 後の tmux の次レイヤー)。
 *
 * 本テストは pipe2 で作った非 socket fd に recvmsg を発行し、戻り値が
 * -88 (= -ENOTSOCK) であることを検証する。
 */
#include "sys64.h"

#define O_NONBLOCK 0x800

/* AMD64 syscall numbers */
#define SYS_pipe2     293
#define SYS_recvmsg   47

static long sys_pipe2(int *fds, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"((long)SYS_pipe2), "D"((long)fds), "S"(flags)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_recvmsg(long fd, void *msg, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"((long)SYS_recvmsg), "D"(fd), "S"(msg), "d"(flags)
        : "rcx", "r11", "memory");
    return ret;
}

/* AMD64 struct msghdr layout (56 bytes):
 *   off  0: msg_name (8)
 *   off  8: msg_namelen (4) + pad (4)
 *   off 16: msg_iov (8)
 *   off 24: msg_iovlen (8)
 *   off 32: msg_control (8)
 *   off 40: msg_controllen (8)
 *   off 48: msg_flags (4) + pad (4)
 * iovec (16 bytes):
 *   off 0: iov_base (8)
 *   off 8: iov_len (8)
 */

void _start(void) {
    int fds[2] = { 0 };
    long r = sys_pipe2(fds, O_NONBLOCK);
    put("pipe2="); put_dec(r);
    put(" read_fd="); put_dec((long)fds[0]);
    put(" write_fd="); put_dec((long)fds[1]); put("\n");

    /* msghdr + iovec を初期化 (-nostdlib で memset 呼出回避のため手動 zero)。 */
    long msg[7];
    long iov[2];
    char buf[1];
    for (int i = 0; i < 7; i++) msg[i] = 0;
    iov[0] = (long)buf;
    iov[1] = 1;
    msg[2] = (long)iov;        /* msg_iov */
    msg[3] = 1;                /* msg_iovlen */
    buf[0] = 0;

    long rv = sys_recvmsg((long)fds[0], msg, 0);
    put("recvmsg="); put_dec(rv); put("\n");

    sys_exit(0);
}
