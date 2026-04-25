/*
 * echo_stdin.c — stdin 読み込み + stdout 書き出し
 *
 * カバー対象:
 *   - sys_read (fd=0) … Console.read 経由
 *   - sys_write (fd=1)
 *
 * 入力:  "hello\n"
 * 期待出力: "hello\n"
 */

static int sys_read(int fd, char *buf, int len) {
    int ret;
    __asm__ volatile (
        "int $0x80"
        : "=a"(ret)
        : "0"(3), "b"(fd), "c"(buf), "d"(len)
    );
    return ret;
}

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

void _start(void) {
    char buf[256];
    int n;
    while ((n = sys_read(0, buf, sizeof(buf))) > 0) {
        sys_write(1, buf, n);
    }
    sys_exit(0);
}
