// varexp_repro64.c — Phase 13: ash の memtodest / strtodest ループを最小再現
// 目的: emulator 上で (a) malloc + 書き込み (b) realloc + コピー
// (c) char ポインタの do-while ループでの *p++ = *src++ が壊れていないかを
// 個別に検証する。
//
// 期待出力:
//   plain_loop: hello (5)
//   realloc_loop: hello world (11)
//   nested_struct: aaaaa (5)
//   ptr_advance: 0,1,2,3,4 (5)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// (a) ash 風の do-while + --len + *p++
static void plain_loop(const char *src) {
    char buf[16] = {0};
    char *q = buf;
    size_t len = strlen(src);
    if (len == 0) { puts("plain_loop: empty"); return; }
    do {
        unsigned char c = *src++;
        *q++ = c;
    } while (--len);
    *q = 0;
    printf("plain_loop: %s (%ld)\n", buf, (long)(q - buf));
}

// (b) heap で realloc して書き込み (growstackblock 風)
static void realloc_loop(void) {
    size_t cap = 4;
    char *buf = malloc(cap);
    char *p = buf;
    const char *src = "hello world";
    size_t len = strlen(src);

    do {
        if ((size_t)(p - buf) >= cap) {
            size_t off = p - buf;
            cap *= 2;
            buf = realloc(buf, cap);
            p = buf + off;
        }
        *p++ = *src++;
    } while (--len);
    *p = 0;
    printf("realloc_loop: %s (%ld)\n", buf, (long)(p - buf));
    free(buf);
}

// (c) 構造体ポインタ経由の post-increment (g_parsefile->next_to_pgetc++ 風)
struct parsefile {
    int  left_in_line;
    char *next;
};
static struct parsefile g_pf;

static int pgetc(void) {
    if (--g_pf.left_in_line < 0) return -1;
    return (unsigned char)*g_pf.next++;
}

static void nested_struct(void) {
    static char src[] = "aaaaa";
    g_pf.next = src;
    g_pf.left_in_line = strlen(src);

    char out[16] = {0};
    int i = 0, c;
    while ((c = pgetc()) >= 0) {
        out[i++] = (char)c;
    }
    out[i] = 0;
    printf("nested_struct: %s (%d)\n", out, i);
}

// (d) ループ内ポインタが正しく前進するか、個別検証
static void ptr_advance(void) {
    char src[5] = {0,1,2,3,4};
    char *p = src;
    size_t len = 5;
    char buf[64];
    int n = 0;
    do {
        n += snprintf(buf+n, sizeof(buf)-n, "%d,", *p++);
    } while (--len);
    if (n > 0) buf[n-1] = 0; // strip trailing comma
    printf("ptr_advance: %s (%lu)\n", buf, (unsigned long)(p - src));
}

// (e) glibc の strlen / stpcpy を 0x40-0x47 範囲のバイトで試す
static void glibc_strops(void) {
    const char *s1 = "abc";              // lowercase only
    const char *s2 = "ABCDEFG";          // 0x41-0x47 only
    const char *s3 = "Hello";            // mixed (H = 0x48)
    const char *s4 = "abcABCDEFGxyz";    // mixed
    char buf[64];
    char *e;
    printf("strlen('%s')=%zu\n", s1, strlen(s1));
    printf("strlen('%s')=%zu\n", s2, strlen(s2));
    printf("strlen('%s')=%zu\n", s3, strlen(s3));
    printf("strlen('%s')=%zu\n", s4, strlen(s4));
    e = stpcpy(buf, s2);
    printf("stpcpy('%s'): result='%s', advance=%ld\n", s2, buf, (long)(e - buf));
    e = stpcpy(buf, s4);
    printf("stpcpy('%s'): result='%s', advance=%ld\n", s4, buf, (long)(e - buf));
}

int main(void) {
    plain_loop("hello");
    realloc_loop();
    nested_struct();
    ptr_advance();
    glibc_strops();
    return 0;
}
