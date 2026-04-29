/* sys_access64.c — access (syscall #21)
 *
 * 既存ファイル (/etc/emulin.cnf) に対する F_OK と
 * 存在しないファイルに対する F_OK の戻り値を検証する。
 */
#include "sys64.h"

#define F_OK 0

void _start(void) {
    long r1 = sys_access("/etc/emulin.cnf", F_OK);
    put("exists=");
    put_dec(r1);
    put("\n");

    long r2 = sys_access("/no/such/file", F_OK);
    put("missing=");
    put_dec(r2);
    put("\n");
    sys_exit(0);
}
