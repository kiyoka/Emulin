/* sys_fchmodat64.c — fchmodat (syscall #268) — issue #80
 *
 * Emacs の対話 save (set-file-modes ... 'nofollow) が fchmodat を発行する。
 * 未実装だと「Doing chmod: Operation not supported」で保存失敗 (古い版は落ちる)。
 * fchmodat(AT_FDCWD, path, mode, flags) が flags 有無とも 0 を返すか検証。
 * 期待値は実 Linux と同じ (regular file への chmod は成功 = 0)。
 */
#include "sys64.h"

#define O_WRONLY 1
#define O_CREAT  0100
#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_EMPTY_PATH       0x1000

static long sys_fchmodat(long dirfd, const char *path, long mode, long flags) {
    long ret;
    register long r10 __asm__("r10") = flags;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(268LL), "D"(dirfd), "S"(path), "d"(mode), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    const char *p = "/tmp/sys_fchmodat268.txt";
    long fd = sys_open(p, O_WRONLY | O_CREAT, 0666);
    sys_write(fd, "x", 1);
    sys_close(fd);

    long r1 = sys_fchmodat(AT_FDCWD, p, 0644, 0);
    put("fchmodat=");          put_dec(r1); put("\n");

    long r2 = sys_fchmodat(AT_FDCWD, p, 0600, AT_SYMLINK_NOFOLLOW);
    put("fchmodat_nofollow="); put_dec(r2); put("\n");

    /* issue #370: raw fchmodat(268) は 3 引数 syscall で flags(r10) を持たない。
       glibc は chmod(path,mode) を fchmodat(AT_FDCWD,path,mode) として発行し、
       r10 には呼び出し前の未初期化ゴミ値が残る。そのゴミに AT_EMPTY_PATH(0x1000)
       bit がたまたま立っていても、実 Linux の raw fchmodat は r10 を無視するので
       絶対パスへの chmod は成功する (= 0)。emulin が r10 を flags として解釈し
       AT_EMPTY_PATH 分岐で fchmod(AT_FDCWD) → EBADF を返すと、emacs の package
       install が "Doing chmod: Bad file descriptor" で非決定的に失敗する。
       r10 にゴミ (AT_EMPTY_PATH 含む) を入れても 0 が返ることを検証。 */
    long r3 = sys_fchmodat(AT_FDCWD, p, 0644, AT_EMPTY_PATH);
    put("fchmodat_garbage_r10="); put_dec(r3); put("\n");

    /* pointer 風のゴミ (0x1000 bit を含む) でも同様に成功する */
    long r4 = sys_fchmodat(AT_FDCWD, p, 0644, 0x55555685c6b0L | AT_EMPTY_PATH);
    put("fchmodat_ptr_r10=");     put_dec(r4); put("\n");

    sys_unlink(p);
    sys_exit(0);
}
