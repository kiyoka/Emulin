/* echo_stdin64.c — stdin 読み込み + stdout 書き出し (x86-64 syscall)
 *
 * カバー対象 (AMD64 ABI):
 *   - read  syscall #0  (rax=0, rdi=fd, rsi=buf, rdx=len)
 *   - write syscall #1  (rax=1, rdi=fd, rsi=buf, rdx=len)
 *   - exit  syscall #60 (rax=60, rdi=code)
 *
 * i386 版 echo_stdin の AMD64 対応版。
 */

static long sys_read(long fd, void *buf, long count) {
    long ret;
    __asm__ volatile (
        "syscall"
        : "=a"(ret)
        : "0"(0LL), "D"(fd), "S"(buf), "d"(count)
        : "rcx", "r11", "memory"
    );
    return ret;
}

static long sys_write(long fd, const void *buf, long count) {
    long ret;
    __asm__ volatile (
        "syscall"
        : "=a"(ret)
        : "0"(1LL), "D"(fd), "S"(buf), "d"(count)
        : "rcx", "r11", "memory"
    );
    return ret;
}

static void sys_exit(long code) {
    __asm__ volatile (
        "syscall"
        :
        : "a"(60LL), "D"(code)
        : "rcx", "r11"
    );
    __builtin_unreachable();
}

void _start(void) {
    char buf[256];
    long n;
    while ((n = sys_read(0, buf, sizeof(buf))) > 0) {
        sys_write(1, buf, n);
    }
    sys_exit(0);
}
