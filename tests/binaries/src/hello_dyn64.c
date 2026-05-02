/* hello_dyn64.c — 動的リンク (gcc -no-pie) hello world テスト
 *
 * Phase 24 step 2 で動作確認した dynamically-linked binary を
 * 回帰スイートに固定する。puts("...") は libc.so.6 経由で
 * stdout に書き出されるので、ld.so のロード〜 libc 初期化〜
 * main 呼び出し〜 exit までの全経路が通って初めて PASS する。
 *
 * Makefile では `gcc -no-pie -O0` でビルドする (ET_EXEC で
 * INTERP セクション付き、本体は固定アドレス、libc.so.6 を
 * 動的リンク)。
 *
 * 期待出力:
 *   hello dynamic
 */
#include <stdio.h>
int main(void) {
    puts("hello dynamic");
    return 0;
}
