/*
 * arith.c — 算術命令の最小スモークテスト
 *
 * カバー対象:
 *   - ADD/SUB/IMUL/IDIV/CMP/J*
 *   - 関数呼び出しと RET
 *   - ループ (DEC + JNZ)
 *
 * 出力: "sum=55\nprod=120\n"
 */

static int sys_write(int fd, const char *buf, int len) {
    int ret;
    __asm__ volatile (
        "int $0x80"
        : "=a"(ret)
        : "0"(4), "b"(fd), "c"(buf), "d"(len)
    );
    return ret;
}

static void sys_exit(int code) {
    __asm__ volatile (
        "int $0x80"
        :
        : "a"(1), "b"(code)
    );
}

static int strlen_s(const char *s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}

static char *itoa_dec(int v, char *out_end) {
    *out_end-- = '\0';
    if (v == 0) {
        *out_end = '0';
        return out_end;
    }
    int neg = 0;
    if (v < 0) { neg = 1; v = -v; }
    while (v > 0) {
        *out_end-- = (char)('0' + v % 10);
        v /= 10;
    }
    if (neg) *out_end-- = '-';
    return out_end + 1;
}

static int sum_to(int n) {
    int s = 0;
    for (int i = 1; i <= n; i++) s += i;
    return s;
}

static int factorial(int n) {
    int r = 1;
    for (int i = 2; i <= n; i++) r *= i;
    return r;
}

void _start(void) {
    char buf[32];
    char *p;

    int s = sum_to(10);          /* 55 */
    p = itoa_dec(s, buf + 31);
    sys_write(1, "sum=", 4);
    sys_write(1, p, strlen_s(p));
    sys_write(1, "\n", 1);

    int f = factorial(5);        /* 120 */
    p = itoa_dec(f, buf + 31);
    sys_write(1, "prod=", 5);
    sys_write(1, p, strlen_s(p));
    sys_write(1, "\n", 1);

    sys_exit(0);
}
