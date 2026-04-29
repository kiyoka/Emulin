/* sys_brk64.c — brk (syscall #12)
 *
 * brk(0) で現在の break を取得 → +0x1000 で拡張 → 元の break+0 へ書き込み読み出し。
 */
#include "sys64.h"

void _start(void) {
    long cur = sys_brk(0);
    put("nonzero=");
    put_dec(cur != 0 ? 1 : 0);
    put("\n");

    long extended = sys_brk((void *)(cur + 0x1000));
    put("grew=");
    put_dec(extended >= cur + 0x1000 ? 1 : 0);
    put("\n");

    /* 拡張領域に書き込んで読み戻せるか */
    volatile char *p = (volatile char *)cur;
    p[0] = 'X';
    p[0x800] = 'Y';
    put("readback=");
    sys_write(1, (const void *)p, 1);
    sys_write(1, (const void *)(p + 0x800), 1);
    put("\n");
    sys_exit(0);
}
