/* pie_dyn64.c — 完全 PIE (gcc デフォルト = ET_DYN + libc 動的リンク)
 * の動作回帰固定 (Phase 26)。
 *
 * gcc -O0 hello.c (= -fpie -pie デフォルト) は ET_DYN を生成し、
 * ld.so 経由で libc を解決して main() に到達する。Phase 25 までで
 * 静的 PIE が動作、Phase 26 で完全 PIE まで進めた。
 *
 * pie_base は emulator のどこかに残る 32-bit 切り詰めバグを避けるため
 * Linux の典型 (0x555555554000) ではなく 32-bit 範囲内の 0x10000000
 * を採用している (Elf.load64 参照)。位置独立なので動作上は問題なし。
 *
 * 期待出力: hello pie
 */
#include <stdio.h>

int main(void) {
    puts("hello pie");
    return 0;
}
