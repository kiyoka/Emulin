/* syscall_storm64.c — syscall-heavy の worst case (issue #221 go/no-go 用)
 *
 * getpid() を N 回呼ぶだけ。命令/syscall 比 ≈ 最小 (ループ管理の数命令のみ) なので、native では
 * 1 syscall ごとの VM-exit trap コストが支配する = native vs software の break-even スペクトルの
 * 「syscall-heavy 端」を測る。compute-heavy の bench64 (215x native 勝ち) と対をなす。
 *
 * argv[1] (10 進、既定 10000) で N を渡す。出力は N のみ deterministic (getpid の戻り値は
 * process pid で software/native 一致するが出力に含めない)。bench-gonogo.sh が大きな N で計測。
 */
#include "sys64.h"

void _start(void) {
    long argc;
    char **argv;
    __asm__ volatile(
        "movq 8(%%rbp), %0\n"
        "leaq 16(%%rbp), %1\n"
        : "=r"(argc), "=r"(argv)
    );

    unsigned long n = 10000UL;
    if (argc > 1) {
        n = 0;
        for (const char *p = argv[1]; *p >= '0' && *p <= '9'; p++)
            n = n * 10UL + (unsigned long)(*p - '0');
    }

    /* N 回 getpid。sys_getpid は inline asm volatile (syscall) なので elide されない。 */
    volatile long sink = 0;
    for (unsigned long i = 0; i < n; i++) sink += sys_getpid();
    (void)sink;

    put("storm n=");
    put_dec((long)n);
    put("\n");
    sys_exit(0);
}
