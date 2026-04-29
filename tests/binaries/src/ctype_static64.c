// ctype_static64.c — Phase 12: isalnum / isalpha が壊れていないか static-link で確認
// 期待出力: "isalpha('A')=1 isalnum('5')=1 isalnum('_')=0 strlen=4\n"
#include <ctype.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    char buf[64];
    int n = 0;
    for (int c = 'A'; c <= 'F'; c++) {
        buf[n++] = c;
        buf[n++] = '=';
        buf[n++] = '0' + (isalnum(c) ? 1 : 0);
        buf[n++] = ' ';
    }
    buf[n] = 0;
    printf("alnum: %s\n", buf);
    // Also test the typical 'walk identifier' loop pattern
    const char *p = "PATH123_X";
    int len = 0;
    while (isalnum((unsigned char)p[len]) || p[len] == '_') len++;
    printf("walk_len(\"%s\")=%d\n", p, len);
    return 0;
}
