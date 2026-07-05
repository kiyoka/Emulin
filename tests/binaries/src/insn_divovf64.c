/* insn_divovf64.c — issue #537: DIV/IDIV の商オーバーフロー (#DE) が SIGFPE として
 * 配送される回帰。旧実装はゼロ除算 (#503) だけをチェックし、商が dest 幅に収まらない
 * ケース (DIV: 上位半分 >= 除数、IDIV: INT_MIN÷-1 等) は wrap した誤値で silent 続行していた。
 * 各ケースは fork した子で実行する (ハンドラ復帰は同一命令の再実行で無限ループするため、
 * ハンドラから _exit(42) して親が exit code を検証する。1case=1fork)。
 * 期待値は実 Linux (実CPU) での実行結果と一致すること。 */
#include "sys64.h"

#define SIGFPE 8
#define SA_RESTORER 0x04000000

struct kernel_sigaction { long handler, flags, restorer, mask; };

static void on_fpe(int sig) { sys_exit(42); }

/* x86-64 の raw rt_sigaction は SA_RESTORER + restorer 必須 (無いと配送時の frame 構築が
 * 失敗して子が signal 死し、handler が呼ばれない)。rt_sigreturn(#15) を呼ぶだけの stub。 */
__attribute__((naked)) static void fpe_restorer(void) {
    __asm__ volatile("movq $15, %rax\n\t syscall");
}

/* 子で fn() を実行し、exit code を返す。42=SIGFPE 配送、それ以外=素通り等 */
static long run_child(void (*fn)(void)) {
    long pid = sys_fork();
    if (pid == 0) {
        struct kernel_sigaction sa;
        sa.handler = (long)on_fpe;
        sa.flags = SA_RESTORER; sa.restorer = (long)fpe_restorer; sa.mask = 0;
        sys_rt_sigaction(SIGFPE, &sa, 0, 8);
        fn();
        sys_exit(0);   /* SIGFPE が来なければ 0 */
    }
    int st = 0;
    sys_wait4(pid, &st, 0, 0);
    return (st >> 8) & 0xFF;   /* WEXITSTATUS */
}

/* --- DIV: 商 > 幅の最大値 --- */
static void div8_ovf(void)  { __asm__ volatile("movw $0x0100, %%ax\n\t movb $1, %%cl\n\t divb %%cl" ::: "rax", "rcx", "cc"); }
static void div16_ovf(void) { __asm__ volatile("movw $1, %%dx\n\t movw $0, %%ax\n\t movw $1, %%cx\n\t divw %%cx" ::: "rax", "rdx", "rcx", "cc"); }
static void div32_ovf(void) { __asm__ volatile("movl $1, %%edx\n\t movl $0, %%eax\n\t movl $1, %%ecx\n\t divl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }
static void div64_ovf(void) { __asm__ volatile("movq $1, %%rdx\n\t movq $0, %%rax\n\t movq $1, %%rcx\n\t divq %%rcx" ::: "rax", "rdx", "rcx", "cc"); }

/* --- IDIV: INT_MIN ÷ -1 --- */
static void idiv8_min(void)  { __asm__ volatile("movw $0xff80, %%ax\n\t movb $-1, %%cl\n\t idivb %%cl" ::: "rax", "rcx", "cc"); }   /* AX=-128 → q=+128 が int8 不能 */
static void idiv16_min(void) { __asm__ volatile("movw $0x8000, %%ax\n\t movw $0xffff, %%dx\n\t movw $-1, %%cx\n\t idivw %%cx" ::: "rax", "rdx", "rcx", "cc"); }
static void idiv32_min(void) { __asm__ volatile("movl $0x80000000, %%eax\n\t cltd\n\t movl $-1, %%ecx\n\t idivl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }
static void idiv64_min(void) { __asm__ volatile("movq $0x8000000000000000, %%rax\n\t cqto\n\t movq $-1, %%rcx\n\t idivq %%rcx" ::: "rax", "rdx", "rcx", "cc"); }

/* --- 非オーバーフロー対照: SIGFPE が来ないこと + 商が正しいこと --- */
static void div32_ok(void)  { __asm__ volatile("movl $0, %%edx\n\t movl $100, %%eax\n\t movl $7, %%ecx\n\t divl %%ecx" ::: "rax", "rdx", "rcx", "cc"); }

void _start(void) {
    put("div8 ovf: rc=");    put_dec(run_child(div8_ovf));    put("\n");
    put("div16 ovf: rc=");   put_dec(run_child(div16_ovf));   put("\n");
    put("div32 ovf: rc=");   put_dec(run_child(div32_ovf));   put("\n");
    put("div64 ovf: rc=");   put_dec(run_child(div64_ovf));   put("\n");
    put("idiv8 min/-1: rc=");  put_dec(run_child(idiv8_min));  put("\n");
    put("idiv16 min/-1: rc="); put_dec(run_child(idiv16_min)); put("\n");
    put("idiv32 min/-1: rc="); put_dec(run_child(idiv32_min)); put("\n");
    put("idiv64 min/-1: rc="); put_dec(run_child(idiv64_min)); put("\n");
    put("div32 ok: rc=");    put_dec(run_child(div32_ok));    put("\n");
    put("DIVOVF ok\n");
    sys_exit(0);
}
