/* bb_decode_static64.c — busybox 1.36 の decode_base64 をそのまま使う再現テスト
 *
 * libbb/uuencode.c の decode_base64 をコピーして、入力を直接 NUL 終端
 * 文字列として渡して何バイト decode できるか調べる。
 * busybox base64 -d は 4 chars (1 group) しか処理しない症状を示すので、
 * その関数自体が原因か、呼び出し側 (read_base64) が原因かを切り分ける。
 */
#include <stdio.h>
#include <string.h>

/* busybox-1.36.1/libbb/uuencode.c のコピー (FAST_FUNC を除去) */
static char* decode_base64_copy(char *dst, const char **pp_src)
{
    const char *src = pp_src ? *pp_src : dst;
    unsigned ch = 0;
    unsigned t;
    int i = 0;

    while ((t = (unsigned char)*src) != '\0') {
        src++;
        if (t >= '0' && t <= '9') t = t - '0' + 52;
        else if (t >= 'A' && t <= 'Z') t = t - 'A';
        else if (t >= 'a' && t <= 'z') t = t - 'a' + 26;
        else if (t == '+') t = 62;
        else if (t == '/') t = 63;
        else if (t == '=' && (i == 3 || (i == 2 && *src == '=')))
            t = 0x1000000;
        else
            continue;

        ch = (ch << 6) | t;
        i = (i + 1) & 3;
        if (i == 0) {
            *dst++ = (char)(ch >> 16);
            *dst++ = (char)(ch >> 8);
            *dst++ = (char)ch;
            if (ch & 0x1000000) {
                dst--;
                if (ch & (0x1000000 << 6)) dst--;
                break;
            }
            ch = 0;
        }
    }
    if (pp_src) *pp_src = src - i;
    return dst;
}

int main(int argc, char **argv) {
    /* busybox 1.36 と同じく、入力と出力に同じバッファを使う */
    static const char inputs[][32] = {
        "YWJj",
        "YWJjZGVm",
        "YWJjZGVmZ2hp",
        "YWJjZGVmZ2hpamtsbW5vcA==",
    };
    char buf[82];
    for (int k = 0; k < (int)(sizeof(inputs)/sizeof(inputs[0])); k++) {
        memset(buf, 0, sizeof(buf));
        strcpy(buf, inputs[k]);
        const char *src = buf;
        char *out = decode_base64_copy(buf, &src);  /* SAME buffer */
        int outlen = (int)(out - buf);
        printf("input='%s' (%zu chars) -> %d bytes: ", inputs[k], strlen(inputs[k]), outlen);
        for (int j = 0; j < outlen; j++) putchar(buf[j]);
        printf(" (in_tail offset=%ld)\n", (long)(src - buf));
    }
    return 0;
}
