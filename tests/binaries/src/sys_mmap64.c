/* sys_mmap64.c — mmap (syscall #9) + munmap (#11)
 *
 * 匿名 mmap で 4KB マップ → 書き込み → 読み戻し → munmap。
 */
#include "sys64.h"

#define PROT_READ    0x1
#define PROT_WRITE   0x2
#define MAP_PRIVATE  0x02
#define MAP_ANONYMOUS 0x20

void _start(void) {
    void *p = (void *)sys_mmap(0, 4096, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    put("mapped=");
    put_dec((long)p > 0 ? 1 : 0);
    put("\n");

    char *cp = (char *)p;
    cp[0] = 'A';
    cp[100] = 'B';
    cp[4095] = 'C';
    put("data=");
    sys_write(1, &cp[0], 1);
    sys_write(1, &cp[100], 1);
    sys_write(1, &cp[4095], 1);
    put("\n");

    long u = sys_munmap(p, 4096);
    put("munmap=");
    put_dec(u);
    put("\n");
    sys_exit(0);
}
