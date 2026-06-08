/* mmap64.c — -nostdlib anonymous mmap テスト (issue #221 native backend の mmap 検証)。
 *
 * mmap(NULL, 8192, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) で 2 ページ確保し、
 * 先頭/2 ページ目/末尾に書いて読み戻し → "mmap: MAPZ" を出力。munmap で解放。
 * native では guest RAM 上端から bump-allocate した領域 (identity-map 済) に read/write する。
 * 出力は書いた byte だけに依存するので確保アドレスに関係なく native==software になる。
 */
#include "sys64.h"

void _start(void) {
    long addr = sys_mmap(0, 8192, 0x3 /*PROT_RW*/, 0x22 /*MAP_PRIVATE|MAP_ANONYMOUS*/, -1, 0);
    if (addr < 0) { put("mmap failed\n"); sys_exit(1); }

    volatile char *p = (volatile char *)addr;
    p[0]    = 'M';
    p[1]    = 'A';
    p[4095] = 'P';   /* 1 ページ目末尾 */
    p[8191] = 'Z';   /* 2 ページ目末尾 */

    char b[5];
    b[0] = p[0]; b[1] = p[1]; b[2] = p[4095]; b[3] = p[8191]; b[4] = 0;
    put("mmap: ");
    put(b);
    put("\n");

    sys_munmap((void *)addr, 8192);
    sys_exit(0);
}
