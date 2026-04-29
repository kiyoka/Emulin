/* sys_umask64.c — umask (syscall #95)
 *
 * umask は前の値を返す。set→get で連続呼び出しすることで挙動を確認。
 */
#include "sys64.h"

void _start(void) {
    long prev = sys_umask(0022);
    put("first=");
    put_dec(prev);
    put("\n");

    long second = sys_umask(0077);
    put("second=");
    put_dec(second);
    put("\n");
    sys_exit(0);
}
