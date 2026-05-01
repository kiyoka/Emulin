/* sys_signal_regsave64.c — シグナル配信時のレジスタ保存・復元テスト
 *
 * commit 032f5ac (Phase 22: Cpu64 のシグナル配信でレジスタ保存・復元) の回帰固定。
 *
 * 実機 Linux カーネルはシグナル配信時に ucontext に全レジスタを保存し、
 * sa_restorer → rt_sigreturn 経由で復元する。我々の最小実装は当初 rip しか
 * 保存しておらず、ハンドラが rax 等の caller-saved を破壊して、
 * syscall 直後の `cmp $-0x1000,%rax` 等で誤動作するバグがあった。
 *
 * 本テストは以下を直接検証する:
 *   1. syscall で既知の値を rax に得る
 *   2. その直後に SIGUSR1 が配信される
 *   3. ハンドラが意図的に rax を別値で上書きする
 *   4. ハンドラ復帰後、rax が元の値に戻っているかを確認
 *
 * 期待出力:
 *   H
 *   rax=0
 *   ok
 */
#include "sys64.h"

#define SIGUSR1 10

static long sa[4];

void handler(long sig) {
    /* ハンドラ内で write を呼んで rax を破壊する。
       ここで rax = 2 (write の戻り値) になる。
       レジスタ保存が効いていなければ、main 側の rax がこの値で上書きされる。 */
    sys_write(1, "H\n", 2);
}

void _start(void) {
    sa[0] = (long)&handler;
    sa[1] = 0;
    sa[2] = 0;
    sa[3] = 0;
    sys_rt_sigaction(SIGUSR1, sa, 0, 8);

    long pid = sys_getpid();

    /* sys_kill をインラインアセンブリで叩く。
       syscall 直後 (= 次命令の手前) で SIGUSR1 が配信されるはず。
       "=a"(rax_after) で syscall 完了後 (= シグナルハンドラ復帰後) の
       rax をキャプチャする。レジスタ保存が効いていれば rax_after = 0。 */
    long rax_after;
    __asm__ volatile (
        "syscall\n\t"
        : "=a"(rax_after)
        : "0"(62L /* sys_kill */), "D"(pid), "S"((long)SIGUSR1)
        : "rcx", "r11", "memory"
    );

    /* rax_after が 0 (kill 成功) なら OK。
       レジスタ保存が壊れていれば 2 (write 戻り値) または別の値になる。 */
    put("rax=");
    put_dec(rax_after);
    put("\n");
    if (rax_after == 0) put("ok\n");
    else                put("ng\n");
    sys_exit(0);
}
