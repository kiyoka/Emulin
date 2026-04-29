/* myls64.c — minimal `ls` (-nostdlib, x86-64)
 *
 * argv[1] のディレクトリを開いて getdents64 で列挙し、各エントリの
 * d_name を 1 行ずつ stdout へ書き出す。
 *
 * 使い方:
 *   /bin/myls64 /tmp
 *
 * AMD64 dirent64 layout:
 *   __u64 d_ino;       (offset 0,  8 bytes)
 *   __s64 d_off;       (offset 8,  8 bytes)
 *   unsigned short d_reclen; (offset 16, 2 bytes)
 *   unsigned char d_type;    (offset 18, 1 byte)
 *   char d_name[];     (offset 19, null-terminated)
 */
#include "sys64.h"

#define O_RDONLY    0
#define O_DIRECTORY 0200000

static long sys_getdents64(int fd, void *buf, long count) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(217LL), "D"((long)fd), "S"(buf), "d"(count)
        : "rcx", "r11", "memory");
    return ret;
}

static char buf[8192];

void _start(void) {
    /* gcc が _start に push %rbp; mov %rsp,%rbp プロローグを入れるので、
     *   [rbp+8]  = argc
     *   [rbp+16] = argv[0]
     *   [rbp+16 + 8*i] = argv[i]
     */
    long argc;
    char **argv;
    __asm__ volatile(
        "movq 8(%%rbp), %0\n"
        "leaq 16(%%rbp), %1\n"
        : "=r"(argc), "=r"(argv)
    );
    const char *path = (argc > 1) ? argv[1] : "/";

    long fd = sys_open(path, O_RDONLY | O_DIRECTORY, 0);
    if (fd < 0) {
        put("open_failed=");
        put_dec(fd);
        put("\n");
        sys_exit(1);
    }

    /* sys_write で RAX が clobber された後、register に乗っている n を
       gcc が再ロードしないことがあるため volatile で保持する */
    volatile long n = sys_getdents64((int)fd, buf, sizeof(buf));
    if (n < 0) {
        put("getdents_failed=");
        put_dec(n);
        put("\n");
        sys_exit(1);
    }

    long off = 0;
    while (off < n) {
        unsigned short reclen = *(unsigned short *)(buf + off + 16);
        const char *name = buf + off + 19;
        put(name);
        put("\n");
        if (reclen == 0) break;
        off += reclen;
    }
    sys_close(fd);
    sys_exit(0);
}
