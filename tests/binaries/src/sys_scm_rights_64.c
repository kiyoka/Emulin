/* sys_scm_rights_64.c — sendmsg/recvmsg の SCM_RIGHTS (fd passing)
 *
 * issue #131 (tmux layer 14): 対話 tmux (`tmux new-session` 前景) は
 * server_start() が socketpair(AF_UNIX, SOCK_STREAM) を作って fork し、
 * client が自分の stdin/stdout (tty) fd を MSG_IDENTIFY_STDIN/STDOUT で
 * SCM_RIGHTS 越しに server へ渡す。server はその fd で isatty() を通して
 * CLIENT_TERMINAL を立てる。旧 amd64_sendmsg/recvmsg は msg_control (cmsg)
 * を完全に無視しており fd が渡らず "open terminal failed: not a terminal"
 * で対話 attach できなかった。
 *
 * emulin は AF_UNIX socketpair を pipe pair で実装する (unixSocket=null、
 * pipe_no / pipe_write_no を持つ)。本テストはその socketpair 越しに
 * console fd (fd 1 = stdout、std_flag) を渡し、受信側で
 *   - recvmsg がデータを返す
 *   - msg_controllen が CMSG_LEN(sizeof(int))=20 になる
 *   - cmsg が SOL_SOCKET(1) / SCM_RIGHTS(1)
 *   - 受信した fd が console へ書ける (std_flag が引き継がれている)
 * ことを確認する。emulin の fd passing は console/tty fd に限定している
 * (受信側に install するのは std_flag/stderr_flag だけ持つ新規 Fileinfo)。
 *
 * x86-64 cmsg layout: cmsg_len(8 @0) cmsg_level(4 @8) cmsg_type(4 @12) fd(4 @16)
 * msghdr layout (56B): name(8) namelen(4+4) iov(8) iovlen(8) control(8) controllen(8) flags(4+4)
 */
#include "sys64.h"

/* sendmsg(46) / recvmsg(47) は sys64.h に無い (sys_recvmsg_pipe_64.c が独自
 * 定義しているため共通ヘッダには置けない)。本テスト内に local 定義する。 */
static long sys_sendmsg(long fd, const void *msg, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(46LL), "D"(fd), "S"(msg), "d"(flags) : "rcx", "r11", "memory");
    return ret;
}
static long sys_recvmsg(long fd, void *msg, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(47LL), "D"(fd), "S"(msg), "d"(flags) : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    int sv[2];
    long sp = sys_socketpair(1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0, sv);
    put("socketpair="); put_dec(sp); put("\n");

    long c = sys_fork();
    if (c == 0) {
        /* child: fd 1 (stdout, std_flag) を SCM_RIGHTS で送る */
        char xbuf[1]; xbuf[0] = 'X';
        long iov[2]; iov[0] = (long)xbuf; iov[1] = 1;
        long cbuf[3];                              /* 24B 8-aligned control buf */
        cbuf[0] = 20;                              /* cmsg_len = CMSG_LEN(4) */
        *((int *)((char *)cbuf + 8))  = 1;         /* cmsg_level = SOL_SOCKET */
        *((int *)((char *)cbuf + 12)) = 1;         /* cmsg_type  = SCM_RIGHTS */
        *((int *)((char *)cbuf + 16)) = 1;         /* fd to pass = stdout */
        long msg[7];
        for (int i = 0; i < 7; i++) msg[i] = 0;
        msg[2] = (long)iov; msg[3] = 1;            /* msg_iov / msg_iovlen */
        msg[4] = (long)cbuf; msg[5] = 20;          /* msg_control / msg_controllen */
        sys_sendmsg((long)sv[1], msg, 0);
        sys_exit(0);
    }

    /* parent: SCM_RIGHTS 付き message を受ける */
    char rbuf[8];
    long iov[2]; iov[0] = (long)rbuf; iov[1] = 8;
    long rcbuf[3];                                 /* 24B control capacity */
    for (int i = 0; i < 3; i++) rcbuf[i] = 0;
    long msg[7];
    for (int i = 0; i < 7; i++) msg[i] = 0;
    msg[2] = (long)iov; msg[3] = 1;
    msg[4] = (long)rcbuf; msg[5] = 24;             /* controllen = capacity */

    long rv = sys_recvmsg((long)sv[0], msg, 0);
    put("recvmsg="); put_dec(rv); put("\n");
    put("controllen="); put_dec(msg[5]); put("\n");
    int clevel = *((int *)((char *)rcbuf + 8));
    int ctype  = *((int *)((char *)rcbuf + 12));
    int newfd  = *((int *)((char *)rcbuf + 16));
    put("cmsg_level="); put_dec(clevel); put("\n");
    put("cmsg_type="); put_dec(ctype); put("\n");
    put("newfd_ge3="); put_dec(newfd >= 3 ? 1 : 0); put("\n");

    /* 受信した fd は console (stdout) に繋がっているはず → 書けることを確認 */
    long w = sys_write((long)newfd, "FROM_PASSED_FD\n", 15);
    put("passed_write="); put_dec(w); put("\n");

    int status = 0;
    sys_wait4(c, &status, 0, 0);
    sys_close((long)sv[0]);
    sys_close((long)sv[1]);
    sys_exit(0);
}
