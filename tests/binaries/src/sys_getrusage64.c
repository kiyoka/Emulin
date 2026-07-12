/* sys_getrusage64.c — getrusage (syscall #98) — issue #707
 *
 * who は int 引数。glibc は RUSAGE_CHILDREN(-1) を edi (32bit) で渡すため
 * rdi = 0x00000000FFFFFFFF (ゼロ拡張) になる。カーネルは下位 32bit を
 * 符号付き int として解釈するので成功しなければならない (実 Linux と同じ)。
 * 旧 Emulin は long のまま比較して EINVAL を返し、bash の time が未初期化
 * struct rusage を読んで user/sys にゴミ値を表示していた。
 *
 * struct rusage は 144 byte。呼び出し前に poison (0xFF) で埋め、成功時に
 * 先頭 (ru_utime.tv_sec) が書き換わることも確認する (Emulin=0 / host=実値、
 * どちらでも poison のままではない)。
 */
#include "sys64.h"

static long sys_getrusage_raw(long who, void *buf) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(98LL), "D"(who), "S"(buf) : "rcx", "r11", "memory");
    return ret;
}

static char ru[144];
static void poison(void) { for (int i = 0; i < 144; i++) ru[i] = (char)0xFF; }
static long sec0(void) { return *(long *)ru; }  /* ru_utime.tv_sec */

static void check(const char *label, long got, long want) {
    put(label);
    put("=");
    put_dec(got == want ? 1 : 0);
    if (got != want) { put(" (got="); put_dec(got); put(" want="); put_dec(want); put(")"); }
    put("\n");
}

void _start(void) {
    /* RUSAGE_SELF(0) */
    poison();
    check("self_ret", sys_getrusage_raw(0, ru), 0);
    check("self_written", sec0() != -1L ? 1 : 0, 1);

    /* RUSAGE_CHILDREN(-1) を 64bit 符号拡張で (long -1 = 0xFFFF...FFFF) */
    poison();
    check("children_sext_ret", sys_getrusage_raw(-1L, ru), 0);

    /* ★本命: RUSAGE_CHILDREN(-1) を glibc と同じ 32bit ゼロ拡張で
       (edi=-1 → rdi=0x00000000FFFFFFFF)。issue #707 の回帰チェック。 */
    poison();
    check("children_zext_ret", sys_getrusage_raw(0xFFFFFFFFL, ru), 0);
    check("children_zext_written", sec0() != -1L ? 1 : 0, 1);

    /* RUSAGE_THREAD(1) */
    check("thread_ret", sys_getrusage_raw(1, ru), 0);

    /* 無効 who=2 は EINVAL (32bit ゼロ拡張の 2 も同様) */
    check("invalid_ret", sys_getrusage_raw(2, ru), -22);

    sys_exit(0);
}
