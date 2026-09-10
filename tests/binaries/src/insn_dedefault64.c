/* insn_dedefault64.c — issue #1024: **ハンドラ未設定**の CPU 例外は、その例外に対応する
 * signal で終了する (#DE なら SIGFPE=8)。
 *
 * ★ 実害: native backend は IDT stub 経路で **vector 13 (#GP) だけ**を guest signal として
 *   配送し、残りの vector を一律 SIGSEGV で殺していた。よって #DE で死んだ子の WTERMSIG が
 *   11 (SIGSEGV) になり、親から見た死因が Linux/software (8 = SIGFPE) と違っていた。
 *   ハンドラ有りの経路は insn_divovf64 が見ているが、**既定動作の経路は誰も見ていなかった**。
 *
 * 各ケースは fork した子で実行し、親が wait4 の status から WIFSIGNALED / WTERMSIG を見る
 * (子はハンドラを入れないので、生き残った場合だけ exit(0) して signaled=0 になる)。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

/* 子で fn() を実行し、wait4 の raw status を返す。signal 死なら下位 7bit が signo。 */
static long run_child_status(void (*fn)(void)) {
    long pid = sys_fork();
    if (pid == 0) {
        fn();
        sys_exit(0);      /* 例外が起きなければ (= 素通り) 0 で正常終了 */
    }
    int st = 0;
    sys_wait4(pid, &st, 0, 0);
    return (long)st;
}

/* #DE: 0 除算 */
static void div0(void)    { __asm__ volatile("movl $0, %%edx\n\t movl $1, %%eax\n\t movl $0, %%ecx\n\t divl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }
/* #DE: 商が 32bit に収まらない (issue #537 の形) */
static void divovf(void)  { __asm__ volatile("movl $1, %%edx\n\t movl $0, %%eax\n\t movl $1, %%ecx\n\t divl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }
/* 対照: 例外にならない除算 = 子は exit(0)、signaled=0 */
static void divok(void)   { __asm__ volatile("movl $0, %%edx\n\t movl $100, %%eax\n\t movl $7, %%ecx\n\t divl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }

static void report(const char *label, long st) {
    put(label);
    put(" signaled="); put_dec((st & 0x7f) != 0 ? 1 : 0);
    put(" termsig=");  put_dec(st & 0x7f);
    put("\n");
}

void _start(void) {
    report("div0",   run_child_status(div0));
    report("divovf", run_child_status(divovf));
    report("divok",  run_child_status(divok));
    put("DEDEFAULT ok\n");
    sys_exit(0);
}
