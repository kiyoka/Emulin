/* fgetc_static64.c — fgetc の早期 EOF バグ調査 (Phase 20)
 *
 * /tmp/test8.txt から fgetc で 1 文字ずつ読んで stdout に出力する。
 * 期待: 入力 "YWJjZGVm" (8 chars) → そのまま 8 chars 出力。
 * busybox 1.36 base64 -d は 4 chars 目で EOF を見ているように見えるので、
 * 同じ症状が glibc fgetc 単体で再現するか確認する。
 */
#include <stdio.h>

int main(int argc, char **argv) {
    FILE *f;
    if (argc > 1) {
        f = fopen(argv[1], "r");
        if (!f) { fputs("fopen failed\n", stderr); return 1; }
    } else {
        f = stdin;
    }
    int n = 0;
    int c;
    while ((c = fgetc(f)) != EOF) {
        putchar(c);
        n++;
    }
    if (argc > 1) fclose(f);
    printf("\n[read %d chars]\n", n);
    return 0;
}
