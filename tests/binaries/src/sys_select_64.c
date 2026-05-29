/* sys_select_64.c — amd64 select(2) (#23) の 64bit address + pipe readiness 検証
 *
 * issue #113 調査由来の latent バグ回帰固定:
 * 旧実装は amd64 の select(#23) を i386 版 sys_select に dispatch しており、
 * readfds 等のアドレスを (int) で 32bit 切り詰めていた。fd_set は stack 上
 * (高位アドレス 0x7fff...) に置かれるため、切り詰めで全く別アドレスを read/write
 * し、amd64 で select を使うプログラムが壊れていた (PR cd7fa60 / amd64_select)。
 *
 * このテストは pipe にデータを書いてから read 端を select し、
 *   (a) select が 1 (= 1 fd ready) を返す
 *   (b) result fd_set に read 端の bit が残る (write-back 正常)
 * を検証する。stack 上の fd_set を正しく扱えないとこれらは壊れる。
 */
#include "sys64.h"

/* select(2): rdi=nfds, rsi=readfds, rdx=writefds, r10=exceptfds, r8=timeout */
static long sys_select(long nfds, void *r, void *w, void *e, void *tv) {
    long ret;
    register long r10 __asm__("r10") = (long)e;
    register long r8  __asm__("r8")  = (long)tv;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(23LL), "D"(nfds), "S"(r), "d"(w), "r"(r10), "r"(r8)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    int fds[2];   /* pipe(2) は int[2] (計 8 byte) を書く */
    if (sys_pipe((long *)fds) < 0) { put("PIPE-FAIL\n"); sys_exit(1); }
    long rfd = fds[0], wfd = fds[1];

    /* read 端を readable にする */
    sys_write(wfd, "X", 1);

    /* fd_set (1024 bit = 128 byte) を stack 上に置く (高位アドレス) */
    unsigned long rfds[16];
    for (int i = 0; i < 16; i++) rfds[i] = 0;
    rfds[rfd / 64] |= (1UL << (rfd % 64));

    long tv[2] = { 0, 200000 };  /* 0.2s */
    long ret = sys_select(rfd + 1, rfds, 0, 0, tv);

    put("select-ret="); put_dec(ret); put("\n");
    int isset = (int)((rfds[rfd / 64] >> (rfd % 64)) & 1UL);
    put(isset ? "READ-FD-SET\n" : "READ-FD-CLEAR\n");

    /* 追加: 空 readfds + timeout → 0 を返す (ready 無し判定) */
    unsigned long efds[16];
    for (int i = 0; i < 16; i++) efds[i] = 0;
    long tv2[2] = { 0, 50000 };  /* 0.05s */
    long ret2 = sys_select(rfd + 1, efds, 0, 0, tv2);
    put("empty-ret="); put_dec(ret2); put("\n");

    sys_exit(0);
}
