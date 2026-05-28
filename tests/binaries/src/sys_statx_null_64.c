/* sys_statx_null_64.c — statx(2) の NULL 引数ハンドリング (issue #131 layer 2)
 *
 * Rust std (glibc 含む) は statx の kernel 対応を probe するため
 *   statx(0, NULL, 0, 0xfff, NULL)
 * のような全 NULL 引数で呼び出すことがある。実 Linux は EFAULT を返すが、
 * 旧 emulin は NULL buf に対して _fill_statx_char(0) を実行し address 0 への
 * store8 で内部 segfault → ripgrep / fd が "Segmentation Fault address(store8)=0"
 * で死んでいた。
 *
 * このテストは 4 通りの probe パターンで挙動を検証する:
 *   1. 全 NULL probe                          → -EFAULT (-14)
 *   2. NULL path, AT_EMPTY_PATH 無し          → -EFAULT
 *   3. valid path, NULL buf                   → -EFAULT
 *   4. AT_FDCWD + valid path + valid buf      → 0 (成功)
 */
#include "sys64.h"

#define AT_FDCWD (-100)
#define AT_EMPTY_PATH 0x1000
#define STATX_BASIC_STATS 0x000007ff

static long sys_statx(long dirfd, const char *path, long flags, long mask, void *buf) {
    long ret;
    register long r10 __asm__("r10") = mask;
    register long r8  __asm__("r8")  = (long)buf;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(332LL), "D"(dirfd), "S"(path), "d"(flags)
        , "r"(r10), "r"(r8)
        : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    long buf[32];  /* 256 byte struct statx 相当 */
    long r;

    r = sys_statx(0, 0, 0, STATX_BASIC_STATS, 0);
    put("null_probe=");    put_dec(r); put("\n");

    r = sys_statx(AT_FDCWD, 0, 0, STATX_BASIC_STATS, buf);
    put("null_path=");     put_dec(r); put("\n");

    r = sys_statx(AT_FDCWD, "/tmp", 0, STATX_BASIC_STATS, 0);
    put("null_buf=");      put_dec(r); put("\n");

    r = sys_statx(AT_FDCWD, "/tmp", 0, STATX_BASIC_STATS, buf);
    put("valid=");         put_dec(r); put("\n");

    sys_exit(0);
}
