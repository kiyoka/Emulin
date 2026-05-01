/* sys_base64decode64.c — busybox 1.36 base64 -d 切断バグの再現テスト
 *
 * busybox 1.36 の base64 -d は 4 文字までしか decode しないという症状が
 * Phase 19 で観察された。手書きの単純な base64 デコーダを動かして、
 * 同じ症状が出るか (= emulator の基本命令の問題) / 出ないか (= busybox 固有)
 * を切り分ける。
 *
 * 入力: "YWJjZGVmZ2hpamtsbW5vcA==" (16 chars → 12 bytes "abcdefghijklmnop")
 * 期待出力: "abcdefghijklmnop\n"
 */

static long sys_write(long fd, const void *buf, long len) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(1LL), "D"(fd), "S"(buf), "d"(len)
        : "rcx", "r11", "memory");
    return ret;
}
static void sys_exit(long code) {
    __asm__ volatile("syscall"
        : : "a"(60LL), "D"(code) : "rcx", "r11");
    __builtin_unreachable();
}

/* 1 文字 → 6bit 値 (-1 = 不正) */
static int b64val(int c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    if (c == '=') return 0;
    return -1;
}

void _start(void) {
    static const char input[] = "YWJjZGVmZ2hpamtsbW5vcA==";
    static char out[64];
    int outlen = 0;
    int i;

    for (i = 0; input[i] && input[i+1] && input[i+2] && input[i+3]; i += 4) {
        int a = b64val(input[i]);
        int b = b64val(input[i+1]);
        int c = b64val(input[i+2]);
        int d = b64val(input[i+3]);
        int npad = 0;
        if (input[i+2] == '=') npad++;
        if (input[i+3] == '=') npad++;
        out[outlen++] = (char)((a << 2) | (b >> 4));
        if (npad < 2) out[outlen++] = (char)(((b & 0xF) << 4) | (c >> 2));
        if (npad < 1) out[outlen++] = (char)(((c & 0x3) << 6) | d);
        if (npad > 0) break;
    }
    out[outlen++] = '\n';

    sys_write(1, out, outlen);
    sys_exit(0);
}
