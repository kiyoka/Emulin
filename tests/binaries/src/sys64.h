/* sys64.h — x86-64 syscall テスト用共通ヘルパー (-nostdlib)
 *
 * 各テストファイルで必要な syscall ラッパーと小さなユーティリティを集約。
 * 1 テスト 1 syscall を原則とするが、テスト結果を stdout に書き出す
 * write/exit や 整数表示用の write_decimal は共通で使う。
 *
 * AMD64 syscall ABI:
 *   番号  RAX
 *   引数  RDI, RSI, RDX, R10, R8, R9
 *   戻り値 RAX (失敗時は -errno が入る)
 */
#ifndef SYS64_H
#define SYS64_H

/* ---- 基本 syscall ---- */

static long sys_read(long fd, void *buf, long count) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(0LL), "D"(fd), "S"(buf), "d"(count) : "rcx", "r11", "memory");
    return ret;
}

static long sys_write(long fd, const void *buf, long count) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(1LL), "D"(fd), "S"(buf), "d"(count) : "rcx", "r11", "memory");
    return ret;
}

static long sys_open(const char *path, long flags, long mode) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(2LL), "D"(path), "S"(flags), "d"(mode) : "rcx", "r11", "memory");
    return ret;
}

static long sys_close(long fd) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(3LL), "D"(fd) : "rcx", "r11");
    return ret;
}

static long sys_stat(const char *path, void *statbuf) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(4LL), "D"(path), "S"(statbuf) : "rcx", "r11", "memory");
    return ret;
}

static long sys_fstat(long fd, void *statbuf) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(5LL), "D"(fd), "S"(statbuf) : "rcx", "r11", "memory");
    return ret;
}

static long sys_lseek(long fd, long off, long whence) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(8LL), "D"(fd), "S"(off), "d"(whence) : "rcx", "r11");
    return ret;
}

static long sys_mmap(void *addr, long len, long prot, long flags, long fd, long off) {
    long ret;
    register long r10 __asm__("r10") = flags;
    register long r8  __asm__("r8")  = fd;
    register long r9  __asm__("r9")  = off;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(9LL), "D"(addr), "S"(len), "d"(prot), "r"(r10), "r"(r8), "r"(r9)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_munmap(void *addr, long len) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(11LL), "D"(addr), "S"(len) : "rcx", "r11", "memory");
    return ret;
}

static long sys_brk(void *addr) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(12LL), "D"(addr) : "rcx", "r11");
    return ret;
}

static long sys_ioctl(long fd, long req, void *arg) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(16LL), "D"(fd), "S"(req), "d"(arg) : "rcx", "r11", "memory");
    return ret;
}

static long sys_writev(long fd, const void *iov, long iovcnt) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(20LL), "D"(fd), "S"(iov), "d"(iovcnt) : "rcx", "r11", "memory");
    return ret;
}

static long sys_access(const char *path, long mode) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(21LL), "D"(path), "S"(mode) : "rcx", "r11");
    return ret;
}

static long sys_dup(long fd) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(32LL), "D"(fd) : "rcx", "r11");
    return ret;
}

static long sys_nanosleep(const void *req, void *rem) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(35LL), "D"(req), "S"(rem) : "rcx", "r11", "memory");
    return ret;
}

static long sys_getpid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(39LL) : "rcx", "r11");
    return ret;
}

static long sys_uname(void *buf) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(63LL), "D"(buf) : "rcx", "r11", "memory");
    return ret;
}

static long sys_ftruncate(long fd, long length) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(77LL), "D"(fd), "S"(length) : "rcx", "r11");
    return ret;
}

static long sys_chdir(const char *path) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(80LL), "D"(path) : "rcx", "r11", "memory");
    return ret;
}

static long sys_rename(const char *old, const char *neu) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(82LL), "D"(old), "S"(neu) : "rcx", "r11", "memory");
    return ret;
}

static long sys_mkdir(const char *path, long mode) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(83LL), "D"(path), "S"(mode) : "rcx", "r11", "memory");
    return ret;
}

static long sys_rmdir(const char *path) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(84LL), "D"(path) : "rcx", "r11", "memory");
    return ret;
}

static long sys_unlink(const char *path) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(87LL), "D"(path) : "rcx", "r11", "memory");
    return ret;
}

static long sys_chmod(const char *path, long mode) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(90LL), "D"(path), "S"(mode) : "rcx", "r11", "memory");
    return ret;
}

static long sys_umask(long mask) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(95LL), "D"(mask) : "rcx", "r11");
    return ret;
}

static long sys_gettimeofday(void *tv, void *tz) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(96LL), "D"(tv), "S"(tz) : "rcx", "r11", "memory");
    return ret;
}

static long sys_getuid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(102LL) : "rcx", "r11");
    return ret;
}

static long sys_getgid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(104LL) : "rcx", "r11");
    return ret;
}

static long sys_geteuid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(107LL) : "rcx", "r11");
    return ret;
}

static long sys_getegid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(108LL) : "rcx", "r11");
    return ret;
}

static long sys_getppid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(110LL) : "rcx", "r11");
    return ret;
}

static long sys_gettid(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(186LL) : "rcx", "r11");
    return ret;
}

static long sys_pipe(long *pipefd) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(22LL), "D"(pipefd) : "rcx", "r11", "memory");
    return ret;
}

static long sys_alarm(long sec) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(37LL), "D"(sec) : "rcx", "r11");
    return ret;
}

static long sys_pause(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(34LL) : "rcx", "r11");
    return ret;
}

static long sys_fork(void) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(57LL) : "rcx", "r11", "memory");
    return ret;
}

static long sys_execve(const char *path, char *const argv[], char *const envp[]) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(59LL), "D"(path), "S"(argv), "d"(envp) : "rcx", "r11", "memory");
    return ret;
}

static long sys_wait4(long pid, int *status, long opts, void *rusage) {
    long ret;
    register long r10 __asm__("r10") = (long)rusage;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(61LL), "D"(pid), "S"(status), "d"(opts), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

static long sys_kill(long pid, long sig) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(62LL), "D"(pid), "S"(sig) : "rcx", "r11");
    return ret;
}

static long sys_rt_sigaction(long signum, const void *act, void *old, long sigsetsize) {
    long ret;
    register long r10 __asm__("r10") = sigsetsize;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(13LL), "D"(signum), "S"(act), "d"(old), "r"(r10)
        : "rcx", "r11", "memory");
    return ret;
}

static void sys_exit(long code) {
    __asm__ volatile("syscall" : : "a"(60LL), "D"(code) : "rcx", "r11");
    __builtin_unreachable();
}

static long sys_socket(long domain, long type, long protocol) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(41LL), "D"(domain), "S"(type), "d"(protocol) : "rcx", "r11");
    return ret;
}

static long sys_getsockname(long fd, void *addr, void *addrlen) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(51LL), "D"(fd), "S"(addr), "d"(addrlen) : "rcx", "r11", "memory");
    return ret;
}

static long sys_getpeername(long fd, void *addr, void *addrlen) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(52LL), "D"(fd), "S"(addr), "d"(addrlen) : "rcx", "r11", "memory");
    return ret;
}

/* ---- 表示ヘルパー ---- */

static long ustrlen(const char *s) {
    long n = 0;
    while (s[n]) n++;
    return n;
}

/* puts 同等 (改行付き) */
static void put(const char *s) { sys_write(1, s, ustrlen(s)); }
static void putln(const char *s) { sys_write(1, s, ustrlen(s)); sys_write(1, "\n", 1); }

/* 10 進整数を stdout へ。負数は "-" を付ける */
static void put_dec(long v) {
    char buf[32];
    int i = 30;
    int neg = 0;
    unsigned long u;
    buf[31] = 0;
    if (v < 0) { neg = 1; u = (unsigned long)(-v); } else u = (unsigned long)v;
    if (u == 0) { buf[i--] = '0'; }
    else while (u) { buf[i--] = '0' + (char)(u % 10); u /= 10; }
    if (neg) buf[i--] = '-';
    sys_write(1, &buf[i+1], 30 - i);
}

/* 16 進整数を 0xXXXX 形式で stdout へ */
static void put_hex(unsigned long v) {
    char buf[20];
    int i;
    sys_write(1, "0x", 2);
    for (i = 15; i >= 0; i--) {
        unsigned int d = (unsigned int)((v >> (i*4)) & 0xF);
        buf[15-i] = (char)(d < 10 ? '0' + d : 'a' + d - 10);
    }
    /* 先頭の 0 を省略しつつ、最低 1 文字は出す */
    int start = 0;
    while (start < 15 && buf[start] == '0') start++;
    sys_write(1, &buf[start], 16 - start);
}

#endif /* SYS64_H */
