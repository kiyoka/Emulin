/* sys_readlink_pty64.c — readlink(/proc/self/fd/N) が pty で device 名を返すこと (issue #1013)
 *
 * ★ 何を守るか: **pty の fd で `readlink(/proc/self/fd/N)` が `/dev/ptmx` /
 *   `/dev/pts/N` を返すこと**。ここが別の文字列になると glibc の `ttyname(3)` が
 *   失敗し、**openpty を使う実装 (sshd / script / tmux) が pty を開けなくなる**。
 *
 * ★ 実害 (0.9.1 の実機確認で発覚): ssh でログインすると sshd が
 *     `openpty returns device for which ttyname fails.`
 *   を出してセッションが張れなかった。#1003 で `/proc/self/fd/N` に
 *   「名前を持たない fd は種別 (`pipe:[...]` / `anon_inode:[...]`) を返す」処理を
 *   足したとき、**名前を見るより先に種別を見ていた**のが原因。
 *   Emulin の pty は **pipe で裏打ちされている** (`set_pipe_pair` が
 *   `pipe_in_flag/pipe_out_flag` を立てる) ので、pty が `isPIPE()` に該当して
 *   `pipe:[...]` を返していた。
 *
 * ★ **1 つの変更で守る対象が 2 つある**形だった: 「名前を持たない fd」と
 *   「名前を持つが pipe で裏打ちされた fd」。後者を数え忘れると、
 *   前者のテスト (sys_statx_procfd64) は緑のまま通る。
 *
 * ★ 判定は **0/1 に落とす**。pts の番号は実行ごとに変わるので、そのまま出すと
 *   実 Linux をオラクルにできない。`/dev/ptmx` も distro によっては
 *   `/dev/pts/ptmx` に解決されるため、**部分一致**で見る。
 *   失敗したときは値そのものを見たいので、`_raw` 行に**種別だけ**を出す
 *   (pipe: で始まるか / anon_inode: で始まるか)。これが 1 なら今回の回帰。
 */
#include "sys64.h"

#define O_RDWR    2
#define O_NOCTTY  0400

#define TIOCGPTN    0x80045430L
#define TIOCSPTLCK  0x40045431L

static long sys_readlink(const char *path, char *buf, long sz) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(89LL), "D"(path), "S"(buf), "d"(sz) : "rcx", "r11", "memory");
    return ret;
}

#define S_IFMT   0170000
#define S_IFCHR  0020000

static char stbuf_m[160];
static char stbuf_s[160];
static char stbuf_p[160];

/* x86-64 struct stat: st_dev=0, st_ino=8, st_mode=24 (4 byte) */
static int ischr(const char *st) {
    return ((*(unsigned int *)(st + 24)) & S_IFMT) == S_IFCHR ? 1 : 0;
}
static int same_ino(const char *a, const char *b) {
    return (*(unsigned long *)(a + 0) == *(unsigned long *)(b + 0)
         && *(unsigned long *)(a + 8) == *(unsigned long *)(b + 8)) ? 1 : 0;
}

static char stxbuf[256];

static long sys_statx(long dirfd, const char *path, long flags, long mask, void *buf) {
    long ret;
    register long r10 __asm__("r10") = mask;
    register long r8  __asm__("r8")  = (long)buf;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(332LL), "D"(dirfd), "S"(path), "d"(flags), "r"(r10), "r"(r8)
        : "rcx", "r11", "memory");
    return ret;
}
/* statx: stx_mode は offset 0x1C の 2 byte */
static int stx_ischr(const char *b) {
    return ((*(unsigned short *)(b + 0x1C)) & S_IFMT) == S_IFCHR ? 1 : 0;
}

static char linkbuf[256];
static char path[64];
static char pts[64];

static void mkprocfd(long n) {
    const char *p = "/proc/self/fd/";
    int i = 0;
    while (p[i]) { path[i] = p[i]; i++; }
    if (n >= 10) path[i++] = (char)('0' + n / 10);
    path[i++] = (char)('0' + n % 10);
    path[i] = 0;
}

static void mkpts(unsigned int n) {
    const char *p = "/dev/pts/";
    int i = 0;
    while (p[i]) { pts[i] = p[i]; i++; }
    if (n >= 100) pts[i++] = (char)('0' + (n / 100) % 10);
    if (n >= 10)  pts[i++] = (char)('0' + (n / 10) % 10);
    pts[i++] = (char)('0' + n % 10);
    pts[i] = 0;
}

/* buf に needle が含まれるか */
static int contains(const char *buf, long len, const char *needle) {
    long i, j;
    for (i = 0; i < len; i++) {
        for (j = 0; needle[j]; j++) {
            if (i + j >= len || buf[i + j] != needle[j]) break;
        }
        if (!needle[j]) return 1;
    }
    return 0;
}

static int starts(const char *buf, long len, const char *pfx) {
    long j;
    for (j = 0; pfx[j]; j++) { if (j >= len || buf[j] != pfx[j]) return 0; }
    return 1;
}

void _start(void) {
    long m = sys_open("/dev/ptmx", O_RDWR | O_NOCTTY, 0);
    put("ptmx_open_ok=");  put_dec(m >= 0 ? 1 : 0); put("\n");

    mkprocfd(m);
    long r = sys_readlink(path, linkbuf, sizeof(linkbuf));
    put("ptmx_readlink_ok=");    put_dec(r > 0 ? 1 : 0); put("\n");
    put("ptmx_is_ptmx=");        put_dec(r > 0 && contains(linkbuf, r, "ptmx") ? 1 : 0); put("\n");
    /* ★ 回帰したときにだけ 1 になる行。原因を一目で言えるようにする。 */
    put("ptmx_looks_like_pipe=");
    put_dec(r > 0 && starts(linkbuf, r, "pipe:") ? 1 : 0); put("\n");

    int zero = 0;
    sys_ioctl(m, TIOCSPTLCK, &zero);
    unsigned int n = 0;
    long g = sys_ioctl(m, TIOCGPTN, &n);
    put("ptn_ok=");        put_dec(g == 0 ? 1 : 0); put("\n");

    mkpts(n);
    long s = sys_open(pts, O_RDWR | O_NOCTTY, 0);
    put("pts_open_ok=");   put_dec(s >= 0 ? 1 : 0); put("\n");

    mkprocfd(s);
    r = sys_readlink(path, linkbuf, sizeof(linkbuf));
    put("pts_readlink_ok=");     put_dec(r > 0 ? 1 : 0); put("\n");
    put("pts_is_devpts=");       put_dec(r > 0 && starts(linkbuf, r, "/dev/pts/") ? 1 : 0); put("\n");
    put("pts_looks_like_pipe=");
    put_dec(r > 0 && starts(linkbuf, r, "pipe:") ? 1 : 0); put("\n");

    /* ★ ここからが ttyname(3) が実際に見るところ。readlink だけ合っていても通らない。
     *   glibc の ttyname は
     *     1. fstat(fd) が **character device** であること
     *     2. readlink(/proc/self/fd/N) の結果を stat して、st_dev/st_ino が
     *        fstat(fd) と **一致**すること
     *   を要求する。Emulin の pty は pipe で裏打ちされているので、pipe を
     *   S_IFIFO として返すと **pty まで FIFO になり** ここで落ちる。 */
    r = sys_fstat(m, stbuf_m);
    put("fstat_master_ok=");  put_dec(r == 0 ? 1 : 0); put("\n");
    put("master_ischr=");     put_dec(ischr(stbuf_m)); put("\n");

    r = sys_fstat(s, stbuf_s);
    put("fstat_slave_ok=");   put_dec(r == 0 ? 1 : 0); put("\n");
    put("slave_ischr=");      put_dec(ischr(stbuf_s)); put("\n");

    r = sys_stat(pts, stbuf_p);
    put("stat_ptspath_ok=");  put_dec(r == 0 ? 1 : 0); put("\n");
    put("ptspath_ischr=");    put_dec(ischr(stbuf_p)); put("\n");

    /* ttyname が最後に行う突合。ここが 0 だと openpty が使えない。 */
    put("slave_matches_ptspath=");
    put_dec(same_ino(stbuf_s, stbuf_p)); put("\n");

    /* ★ statx 経路も踏む。fstat とは **別の実装**なので、片方だけ直しても
     *   新しい glibc (statx を使う) では同じ症状が残る。 */
    r = sys_statx(m, "", 0x1000 /*AT_EMPTY_PATH*/, 0x7ffL, stxbuf);
    put("statx_master_ok=");     put_dec(r == 0 ? 1 : 0); put("\n");
    put("statx_master_ischr=");  put_dec(stx_ischr(stxbuf)); put("\n");
    r = sys_statx(s, "", 0x1000, 0x7ffL, stxbuf);
    put("statx_slave_ok=");      put_dec(r == 0 ? 1 : 0); put("\n");
    put("statx_slave_ischr=");   put_dec(stx_ischr(stxbuf)); put("\n");

    sys_exit(0);
}
