/*
 * hello.c — Phase 0 回帰テスト用最小プログラム
 *
 * カバー対象:
 *   - sys_write (fd=1)
 *   - sys_exit
 *   - 文字列リテラルへの読み込み (.rodata)
 *
 * ビルド: i386-linux-gnu-gcc -m32 -static -nostdlib -o hello hello.c
 *   (libc 抜きで syscall を直接叩く。Emulin が glibc 依存に振り回されない)
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

void _start(void) {
    static const char msg[] = "hello, emulin\n";
    sys_write(1, msg, sizeof(msg) - 1);
    sys_exit(0);
}
