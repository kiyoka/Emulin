/* sys_unlink_dangle64.c — unlink on a dangling symlink (issue #126)
 *
 * emacs の lock file ".#<name>" -> "user@host.pid" のように target が存在しない
 * symlink (dangling) を unlink/rm で消せること。
 *
 * 旧実装は unlink 前の存在チェックに File.exists() (= symlink を follow) を使い、
 * target 不在で ENOENT を返して unlink を実行しなかった (ls/lstat は NOFOLLOW
 * 対応済なので「見えるのに rm で消せない」非対称が起きていた)。
 * 期待: symlink=0, unlink=0。
 */
#include "sys64.h"

/* symlink(2) = syscall 88。sys64.h には無いのでここで定義。 */
static long sys_symlink(const char *target, const char *linkpath) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(88LL), "D"(target), "S"(linkpath) : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    const char *link = "/tmp/sys_unlink_dangle.lnk";
    sys_unlink(link);  /* 前回の残骸掃除 (結果は無視、出力もしない) */

    long s = sys_symlink("no_such_target_xyz", link);
    put("symlink=");
    put_dec(s);
    put("\n");

    long u = sys_unlink(link);   /* issue #126: dangling でも 0 で消えること */
    put("unlink=");
    put_dec(u);
    put("\n");

    sys_exit(0);
}
