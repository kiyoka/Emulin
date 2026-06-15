// sys_mmap_large64.c — file-backed mmap を 2GB 超 offset で検証 (issue #336)
//   mmap の off_t が int に切り詰められていると offset 0x100000000 (4GiB) が 0 に化け、
//   file[0..] (別マーカー 'x') を map してしまう。sparse file で検証する。
#include "sys64.h"

static long sys_pwrite(long fd, const void *buf, long count, long off) {
    long ret; register long r10 __asm__("r10") = off;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(18LL), "D"(fd), "S"((long)buf), "d"(count), "r"(r10) : "rcx","r11","memory");
    return ret;
}

#define O_RDWR      2
#define O_CREAT     0x40
#define O_TRUNC     0x200
#define PROT_READ   1
#define MAP_PRIVATE 2

void _start(void) {
    sys_unlink("/tmp/mmap_large64.dat");
    long fd = sys_open("/tmp/mmap_large64.dat", O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (fd < 0) { put("OPEN_FAIL\n"); sys_exit(1); }

    long off = 0x100000000L;            // 4GiB, page-aligned
    sys_pwrite(fd, "M", 1, off);        // 4GiB に 'M' (sparse)
    sys_pwrite(fd, "x", 1, 0);          // offset 0 に 'x' (切り詰め alias 検出用)

    // file[4GiB .. 4GiB+4096) を map → byte0 = 'M' (切り詰めなら file[0]='x')
    long p = sys_mmap(0, 4096, PROT_READ, MAP_PRIVATE, fd, off);
    if (p < 0) { put("MMAP_FAIL\n"); sys_exit(1); }
    char *m = (char *)p;
    put("m0="); sys_write(1, &m[0], 1); put("\n");

    sys_munmap((void *)p, 4096);
    sys_close(fd);
    sys_unlink("/tmp/mmap_large64.dat");
    sys_exit(0);
}
