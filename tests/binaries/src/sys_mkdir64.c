/* sys_mkdir64.c — mkdir (syscall #83)
 *
 * /tmp/sys_mkdir_test を作成 → access で確認 → rmdir で片付け。
 * 主目的は mkdir の戻り値検証なので rmdir は最後にだけ呼ぶ。
 */
#include "sys64.h"

#define F_OK 0

void _start(void) {
    /* 前回のゴミがあれば消す (戻り値は無視) */
    sys_rmdir("/tmp/sys_mkdir_test");

    long r = sys_mkdir("/tmp/sys_mkdir_test", 0755);
    put("mkdir=");
    put_dec(r);
    put("\n");

    long a = sys_access("/tmp/sys_mkdir_test", F_OK);
    put("access=");
    put_dec(a);
    put("\n");

    sys_rmdir("/tmp/sys_mkdir_test");
    sys_exit(0);
}
