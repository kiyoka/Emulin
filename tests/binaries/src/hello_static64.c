/* hello_static64.c — glibc -static でビルドする hello world
 *
 * gcc -O0 -static -o hello_static64 hello_static64.c
 *
 * glibc の _start → __libc_start_main → main() 経由で動く。
 * brk / mmap / rt_sigaction / arch_prctl / set_tid_address 等が
 * 初期化シーケンスで呼ばれる。
 * これらを正しく処理できるか確認する Phase 6 の主役テスト。
 */
#include <stdio.h>

int main(void) {
    puts("hello static");
    return 0;
}
