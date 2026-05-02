/* regex_dyn64.c — POSIX regex の動的リンク回帰固定 (Phase 25)
 *
 * regcomp / regexec / regfree が動くこと、捕獲グループの位置が
 * 取れることを検証。glibc 内部の regex_internal は最適化された
 * SSE 系ループ (PMAXUB 等) を踏むので、Phase 25 で追加した命令の
 * 検収にもなる。
 *
 * 期待出力:
 *   'hello123' -> match num='123'
 *   'abc' -> nomatch
 *   '  spaces' -> nomatch
 *   'test42' -> match num='42'
 */
#include <stdio.h>
#include <regex.h>
#include <string.h>

int main(void) {
    regex_t re;
    if (regcomp(&re, "^[a-z]+([0-9]+)$", REG_EXTENDED) != 0) {
        puts("compile fail");
        return 1;
    }
    const char *cases[] = {"hello123", "abc", "  spaces", "test42"};
    for (int i = 0; i < 4; i++) {
        regmatch_t m[2];
        int r = regexec(&re, cases[i], 2, m, 0);
        if (r == 0) {
            char num[32];
            int len = m[1].rm_eo - m[1].rm_so;
            memcpy(num, cases[i] + m[1].rm_so, len);
            num[len] = 0;
            printf("'%s' -> match num='%s'\n", cases[i], num);
        } else {
            printf("'%s' -> nomatch\n", cases[i]);
        }
    }
    regfree(&re);
    return 0;
}
