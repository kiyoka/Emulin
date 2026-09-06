/* sys_statx_procfd64.c — statx (#332) / readlink (#89) on /proc/self/fd/N  (issue #984)
 *
 * ★ 何を守るか: **名前を持たない fd** (eventfd / epoll) の /proc/self/fd/N を statx すると
 *   Emulin 内で NullPointerException が起きていた。Fileinfo.get_name() が null を返し、
 *   それが Mount.get_virtual_path(null) に渡って `_native_path.charAt(0)` で落ちる。
 *   NPE は EFAULT に変換されるので guest からは **statx が理由なく EFAULT で失敗**する。
 *
 * ★ 同じ穴は readlink(/proc/self/fd/N) の経路では既に塞がれていた
 *   (SyscallAmd64 の `nm != null` guard)。**2 箇所のうち 1 箇所だけ直っていた**形なので、
 *   両方の経路を検査する。
 *
 * ★ **errno そのものは固定しない。** 実 Linux はこれら 4 ケースすべてで成功する (statx=0)
 *   が、Emulin は現状 ENOENT を返す。これは #1003 として分離した別の欠陥で、ここで
 *   `-2` を期待値に書くと **その食い違いを「正しい」と記録してしまう**。
 *   このテストが守るのは「**EFAULT (= NPE 経路) にならないこと**」だけ。
 *   #1003 が直ったら、実 Linux と同じ「成功」を期待するよう更新すること。
 *
 * ★ この形なら **実 Linux で走らせても同じ期待値になる** (どちらも EFAULT ではない)。
 */
#include "sys64.h"

#define S_IFMT   0170000
#define S_IFIFO  0010000
#define S_IFREG  0100000

#define AT_FDCWD (-100)

static long sys_statx(long dirfd, const char *path, long flags, long mask, void *buf) {
    long ret;
    /* ★ 引数の順序を間違えると **実 Linux でも EINVAL** になる (最初それで踏んだ)。
     *   statx(dirfd, path, flags, mask, buf) = rdi rsi rdx r10 r8 */
    register long r10 __asm__("r10") = mask;
    register long r8  __asm__("r8")  = (long)buf;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(332LL), "D"(dirfd), "S"(path), "d"(flags), "r"(r10), "r"(r8)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_readlink(const char *path, char *buf, long sz) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(89LL), "D"(path), "S"(buf), "d"(sz) : "rcx", "r11", "memory");
    return ret;
}

static long sys_eventfd2(long initval, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(290LL), "D"(initval), "S"(flags) : "rcx", "r11");
    return ret;
}

static long sys_epoll_create1(long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(291LL), "D"(flags) : "rcx", "r11");
    return ret;
}

static char stxbuf[256];
static char linkbuf[256];
static char path[64];

/* "/proc/self/fd/<n>" を組み立てる */
static void mkpath(long n) {
    const char *p = "/proc/self/fd/";
    int i = 0;
    while (p[i]) { path[i] = p[i]; i++; }
    if (n >= 10) { path[i++] = (char)('0' + n / 10); }
    path[i++] = (char)('0' + n % 10);
    path[i] = 0;
}

void _start(void) {
    /* ★ pipe(2) は **int を 2 つ**書く。long[2] で受けると 2 つが 1 語に詰まって
     *   fd 番号が壊れる (最初それで踏んだ)。 */
    int fds[2];
    long r = sys_pipe((long *)fds);
    put("pipe=");   put_dec(r);      put("\n");

    /* --- pipe: 実 Linux は statx 成功 + S_IFIFO、readlink 成功 --- */
    mkpath(fds[0]);
    r = sys_statx(AT_FDCWD, path, 0, 0x7ffL, stxbuf);
    put("statx_pipe=");       put_dec(r); put("\n");
    put("statx_pipe_isfifo=");
    put_dec(((*(unsigned short *)(stxbuf + 28)) & S_IFMT) == S_IFIFO ? 1 : 0); put("\n");

    r = sys_readlink(path, linkbuf, sizeof(linkbuf));
    put("readlink_pipe_ok="); put_dec(r > 0 ? 1 : 0); put("\n");

    /* --- eventfd / epoll: 名前を持たない fd。#984 の NPE はここで出た。
     *     実 Linux は statx 成功、mode は **type bit 無し** (0600)。 --- */
    long efd = sys_eventfd2(0, 0);
    mkpath(efd);
    r = sys_statx(AT_FDCWD, path, 0, 0x7ffL, stxbuf);
    put("statx_eventfd=");        put_dec(r); put("\n");
    put("statx_eventfd_notype=");
    put_dec(((*(unsigned short *)(stxbuf + 28)) & S_IFMT) == 0 ? 1 : 0); put("\n");

    long pfd = sys_epoll_create1(0);
    mkpath(pfd);
    r = sys_statx(AT_FDCWD, path, 0, 0x7ffL, stxbuf);
    put("statx_epoll=");          put_dec(r); put("\n");

    /* --- fstat 相当 (AT_EMPTY_PATH) も同じ答えになること --- */
    r = sys_statx(fds[0], "", 0x1000, 0x7ffL, stxbuf);
    put("fstat_pipe=");           put_dec(r); put("\n");
    put("fstat_pipe_isfifo=");
    put_dec(((*(unsigned short *)(stxbuf + 28)) & S_IFMT) == S_IFIFO ? 1 : 0); put("\n");

    r = sys_statx(efd, "", 0x1000, 0x7ffL, stxbuf);
    put("fstat_eventfd=");        put_dec(r); put("\n");

    sys_exit(0);
}
