/*
 * args.c — argc/argv が正しくスタックに積まれているかを確認
 *
 * カバー対象:
 *   - Process.stack_data_init() による argc/argv のレイアウト
 *   - Memory.loadString() 経由のポインタ走査
 *
 * 出力例:  実行 "args foo bar"
 *   argc=3
 *   argv[0]=args
 *   argv[1]=foo
 *   argv[2]=bar
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
    if (v == 0) { *out_end = '0'; return out_end; }
    while (v > 0) {
        *out_end-- = (char)('0' + v % 10);
        v /= 10;
    }
    return out_end + 1;
}

void _start(int argc, char **argv) {
    char buf[16];
    char *p;

    p = itoa_dec(argc, buf + 15);
    sys_write(1, "argc=", 5);
    sys_write(1, p, strlen_s(p));
    sys_write(1, "\n", 1);

    for (int i = 0; i < argc; i++) {
        p = itoa_dec(i, buf + 15);
        sys_write(1, "argv[", 5);
        sys_write(1, p, strlen_s(p));
        sys_write(1, "]=", 2);
        sys_write(1, argv[i], strlen_s(argv[i]));
        sys_write(1, "\n", 1);
    }
    sys_exit(0);
}
