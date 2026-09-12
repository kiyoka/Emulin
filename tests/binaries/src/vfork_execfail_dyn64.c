/* vfork_execfail_dyn64.c — issue #1028: **vfork の子の execve が「後戻りできない地点」で
 * 失敗したとき、親が resume すること**の回帰。
 *
 * ★ 実害 (2026-09-10): guest 内で gcc を動かすと collect2 → ld の posix_spawn で
 *   execve が内部エラー (heap 不足 → OutOfMemoryError → ENOMEM) になり、
 *   `Process.vfork_signal_parent()` に到達しないまま子が止まった。親は
 *   `Kernel.vfork` の CountDownLatch で **永久に park** し、プロセスツリーごと停止した。
 *   countDown するのは execve 成功 / exit_group / exit の **3 か所だけ**で、
 *   それ以外の終わり方が 1 つでもあると固まる ("N 個のうち 1 個" 型)。
 *
 * glibc の posix_spawn は clone(CLONE_VM|CLONE_VFORK) を使うので、この経路を素直に通る。
 *
 * 通常時 (期待値 = 実 Linux と一致): 子が /bin/hello64 を exec して出力し、
 *   親は waitpid で回収して signaled=0 exit=0 を報告する。
 * 失敗時 (EMULIN_FORCE_EXEC_FAIL=hello64、tests/scripts/vfork-execfail-smoke.sh):
 *   exec が失敗しても **親が resume して** 報告できること (止まらないこと) を見る。
 */
#include <stdio.h>
#include <spawn.h>
#include <sys/wait.h>

extern char **environ;

/* ★ exec 対象は **argv[1] で渡された自分自身のパス**。他の binary を sandbox に置く必要が
 *   無く、host (実 Linux) でも guest でも同じ出力になる (= 期待値を実機で裏取りできる)。
 *     親: vfork_execfail_dyn64 <自分のパス>
 *     子: vfork_execfail_dyn64 <自分のパス> child   → すぐ 7 で終わる
 *   (/proc/self/exe は host の posix_spawn が ENOENT を返す環境があったので使わない) */
int main(int argc, char **argv) {
    if (argc > 2) {            /* 子: exec された側 */
        printf("child ran\n");
        fflush(stdout);
        return 7;
    }
    if (argc < 2) {
        printf("usage: %s <path-to-self>\n", argv[0]);
        return 2;
    }
    pid_t pid = -1;
    char *cargv[] = { argv[1], argv[1], "child", NULL };
    int rc = posix_spawn(&pid, argv[1], NULL, NULL, cargv, environ);
    int st = 0;
    if (rc == 0) waitpid(pid, &st, 0);
    /* 出力は waitpid の後にまとめて出す (子の出力と混ざらないように) */
    printf("spawn rc=%d signaled=%d exit=%d\n", rc, (st & 0x7f) != 0, (st >> 8) & 0xff);
    printf("VFORK_EXECFAIL ok\n");
    return 0;
}
