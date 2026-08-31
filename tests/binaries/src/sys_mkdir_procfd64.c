/* sys_mkdir_procfd64.c — mkdir("/proc/self/fd/N/suffix", ...) (issue #982)
 *
 * Bun (claude CLI のランタイム) は openat の代わりに、dirfd を
 * `/proc/self/fd/<dirfd>/<相対パス>` という Linux の定石で文字列に埋め込んで
 * レガシー mkdir(2) を呼ぶ。resolve_proc_self_fd() は元々 `/proc/self/fd/N`
 * の完全一致しか扱えず、N の後ろに `/` 以降が続くとただの (存在しない)
 * パス要素として扱われて ENOENT になっていた (実機の claude 2.1.251 で再現)。
 *
 * fd を既知の番号 (90) へ dup2 してから、その fd 経由で mkdir する。
 */
#include "sys64.h"

#define O_RDONLY 0
#define O_DIRECTORY 0200000
#define F_OK 0

void _start(void) {
    sys_rmdir("/proc/self/fd/90/sys982_test");  /* 前回のゴミ (戻り値は無視) */

    long fd = sys_open("/tmp", O_RDONLY | O_DIRECTORY, 0);
    put("open=");
    put_dec(fd);
    put("\n");

    long d = sys_dup2(fd, 90);
    put("dup2=");
    put_dec(d);
    put("\n");

    long r = sys_mkdir("/proc/self/fd/90/sys982_test", 0755);
    put("mkdir=");
    put_dec(r);
    put("\n");

    long a = sys_access("/tmp/sys982_test", F_OK);
    put("access=");
    put_dec(a);
    put("\n");

    sys_rmdir("/tmp/sys982_test");
    sys_close(90);
    sys_exit(0);
}
