/* sys_readv_64.c — readv(2) を pipe + file で検証 (issue #131 tmux layer 6)
 *
 * tmux client は server からの response 受信に readv を使う。emulin は
 * 旧 ENOSYS スタブだったため "Unsupported amd64 syscall sysno=[19]" warning
 * が出て tmux IPC が崩れていた。
 *
 * 検証パターン:
 *   1. pipe2 で fd 作成 → 一方に write → 他方を readv で 2 iov に分割読み
 *   2. 取得バイト数と分割位置の正しさを確認
 */
#include "sys64.h"

#define O_NONBLOCK 0x800
#define SYS_pipe2  293
#define SYS_readv  19

static long sys_pipe2(int *fds, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"((long)SYS_pipe2), "D"((long)fds), "S"(flags)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_readv(long fd, void *iov, long iovcnt) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"((long)SYS_readv), "D"(fd), "S"(iov), "d"(iovcnt)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    int fds[2] = { 0 };
    long r = sys_pipe2(fds, 0);
    put("pipe2="); put_dec(r); put("\n");

    /* writer end に "HELLOWORLD" (10 byte) を書く */
    const char *msg = "HELLOWORLD";
    long w = sys_write(fds[1], msg, 10);
    put("write="); put_dec(w); put("\n");

    /* readv で 2 iov に分割: 4 byte + 6 byte */
    char buf1[4] = { 0 };
    char buf2[6] = { 0 };
    long iov[4];          /* 2 iovec = 4 longs */
    iov[0] = (long)buf1; iov[1] = 4;
    iov[2] = (long)buf2; iov[3] = 6;

    long rv = sys_readv(fds[0], iov, 2);
    put("readv="); put_dec(rv); put("\n");

    /* buf1, buf2 を確認 */
    put("buf1="); sys_write(1, buf1, 4); put("\n");
    put("buf2="); sys_write(1, buf2, 6); put("\n");

    sys_exit(0);
}
