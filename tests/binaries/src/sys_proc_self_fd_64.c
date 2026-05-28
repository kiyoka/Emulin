/* sys_proc_self_fd_64.c — /proc/self/fd 合成 directory が動くか検証 (issue #131)
 *
 * tmux / openssh / glibc の closefrom 互換コードは /proc/self/fd を opendir して
 * 開いている fd を列挙する。emulin はこれを合成 directory として実装する必要が
 * あり、本 test は以下を確認する:
 *
 *   1. openat("/proc/self/fd", O_DIRECTORY|O_RDONLY) が >=0 を返す
 *   2. fstat した結果 S_IFDIR
 *   3. getdents64 が 1 byte 以上 (entries) を返す
 *   4. close できる
 */
#include "sys64.h"

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define O_DIRECTORY 0x10000
#define O_CLOEXEC 0x80000

#define S_IFMT  0170000
#define S_IFDIR 0040000

static long sys_openat(long dirfd, const char *path, long flags, long mode) {
    long ret;
    register long r10 __asm__("r10") = mode;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(257LL), "D"(dirfd), "S"(path), "d"(flags), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_getdents64(long fd, void *dirp, long count) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(217LL), "D"(fd), "S"(dirp), "d"(count)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    long fd = sys_openat(AT_FDCWD, "/proc/self/fd",
                         O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    put("openat="); put_dec(fd >= 0 ? 1 : 0); put("\n");
    if (fd < 0) { sys_exit(1); }

    /* fstat → S_IFDIR チェック */
    long stbuf[18];   /* 144 byte struct stat 相当 */
    (void)sys_fstat(fd, stbuf);
    unsigned int mode = (unsigned int)( stbuf[3] & 0xFFFFFFFFL );  /* offset 24 = stbuf[3] 低位 32bit */
    int is_dir = ((mode & S_IFMT) == S_IFDIR);
    put("isdir="); put_dec(is_dir); put("\n");

    /* getdents64 → 1 byte 以上を期待 */
    char dirbuf[4096];
    long bytes = sys_getdents64(fd, dirbuf, sizeof(dirbuf));
    put("getdents>0="); put_dec(bytes > 0 ? 1 : 0); put("\n");

    sys_close(fd);
    sys_exit(0);
}
