/* bench64.c — compute-heavy ループ (native vs software 性能比較、issue #221)。
 *
 * argv[1] (10 進、既定 10000) 回だけ FNV 風の決定的ハッシュを回す。
 *   - 出力は N と最終ハッシュ値で deterministic → native==software (正しさ検証)。
 *   - syscall は argv 読み (stack) + write×数回 + exit のみ = trap コスト極小。
 *     よって wall-clock 差は「非 syscall 命令を実 CPU で走らせる native」対「per-instruction
 *     emulation の software」の純粋な比較になり、#221 の核心仮説を実測できる。
 *   - 回帰では既定 N=10000 で一瞬。性能測定は bench-native.sh が大きな N を argv で渡す。
 *
 * ループ本体は h ^= i; h *= prime; h = rotl(h,13) の 3 演算。-O0 -nostdlib なので
 *   loop は最適化されず、h の carry 依存で elide もされない。
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

    unsigned long n = 10000UL;  /* 既定 (回帰用に小さく) */
    if (argc > 1) {
        n = 0;
        for (const char *p = argv[1]; *p >= '0' && *p <= '9'; p++)
            n = n * 10UL + (unsigned long)(*p - '0');
    }

    unsigned long h = 1469598103934665603UL;   /* FNV offset basis */
    for (unsigned long i = 0; i < n; i++) {
        h ^= i;
        h *= 1099511628211UL;                   /* FNV prime */
        h = (h << 13) | (h >> 51);              /* rotl 13 */
    }

    put("bench n=");
    put_dec((long)n);
    put(" h=");
    put_hex(h);
    put("\n");
    sys_exit(0);
}
