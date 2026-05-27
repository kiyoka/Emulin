/* sys_fchmodat2_64.c — fchmodat2 (syscall #452, Linux 6.6+)
 *
 * glibc 2.39+ は fchmodat を fchmodat2(452) 経由で発行し、kernel が ENOSYS を
 * 返すと旧 fchmodat(268) に fallback する。emulin が 452 を ENOSYS にしていると
 * "Unsupported amd64 syscall sysno=[452]" 警告が出る (ddskk make install の chmod
 * 等で顕在化)。452 は引数が fchmodat(268) と同一なので同じ処理に流す。
 *
 * fchmodat2(AT_FDCWD, path, mode, flags) が flags 有無とも 0 を返すか検証。
 * 期待値は実 Linux と同じ (regular file への chmod は成功 = 0)。
 */
#include "sys64.h"

#define O_WRONLY 1
#define O_CREAT  0100
#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100

static long sys_fchmodat2(long dirfd, const char *path, long mode, long flags) {
    long ret;
    register long r10 __asm__("r10") = flags;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(452LL), "D"(dirfd), "S"(path), "d"(mode), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    const char *p = "/tmp/sys_fchmodat2_452.txt";
    long fd = sys_open(p, O_WRONLY | O_CREAT, 0666);
    sys_write(fd, "x", 1);
    sys_close(fd);

    long r1 = sys_fchmodat2(AT_FDCWD, p, 0644, 0);
    put("fchmodat2=");          put_dec(r1); put("\n");

    long r2 = sys_fchmodat2(AT_FDCWD, p, 0600, AT_SYMLINK_NOFOLLOW);
    put("fchmodat2_nofollow="); put_dec(r2); put("\n");

    sys_unlink(p);
    sys_exit(0);
}
