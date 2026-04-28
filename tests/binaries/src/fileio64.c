/* fileio64.c — open/write/read/close の動作検証 (x86-64 -nostdlib)
 *
 * カバー対象 (AMD64 ABI):
 *   open  syscall #2  (rax=2, rdi=path, rsi=flags, rdx=mode)
 *   write syscall #1  (fd, buf, len)
 *   read  syscall #0  (fd, buf, len)
 *   close syscall #3  (fd)
 *   exit  syscall #60
 *
 * 手順:
 *   1. /tmp/test.txt を O_WRONLY|O_CREAT|O_TRUNC で open
 *   2. "hello file\n" を write
 *   3. close
 *   4. /tmp/test.txt を O_RDONLY で open
 *   5. 読んで stdout へ write
 *   6. close
 *   7. exit(0)
 *
 * 期待出力: "hello file\n"
 */

#define O_RDONLY   0
#define O_WRONLY   1
#define O_CREAT    0100
#define O_TRUNC    01000

static long sys_open(const char *path, long flags, long mode) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(2LL), "D"(path), "S"(flags), "d"(mode)
        : "rcx", "r11", "memory");
    return ret;
}
static long sys_write(long fd, const void *buf, long len) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(1LL), "D"(fd), "S"(buf), "d"(len)
        : "rcx", "r11", "memory");
    return ret;
}
static long sys_read(long fd, void *buf, long len) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(0LL), "D"(fd), "S"(buf), "d"(len)
        : "rcx", "r11", "memory");
    return ret;
}
static long sys_close(long fd) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(3LL), "D"(fd)
        : "rcx", "r11");
    return ret;
}
static void sys_exit(long code) {
    __asm__ volatile("syscall"
        : : "a"(60LL), "D"(code) : "rcx", "r11");
    __builtin_unreachable();
}

static const char path[] = "/tmp/test.txt";
static const char msg[]  = "hello file\n";

void _start(void) {
    char buf[64];
    long fd, n;

    /* write */
    fd = sys_open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { sys_write(2, "open(w) failed\n", 15); sys_exit(1); }
    sys_write(fd, msg, 11);
    sys_close(fd);

    /* read back */
    fd = sys_open(path, O_RDONLY, 0);
    if (fd < 0) { sys_write(2, "open(r) failed\n", 15); sys_exit(1); }
    n = sys_read(fd, buf, sizeof(buf));
    sys_close(fd);

    sys_write(1, buf, n);
    sys_exit(0);
}
