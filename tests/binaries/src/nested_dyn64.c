/* nested_dyn64.c — GCC ネストした関数 (実行可能スタック上のトランポリン)
 * の動作回帰固定 (Phase 25 続き)。
 *
 * GCC 拡張: 関数内に関数を定義できる。クロージャの実装としてスタック
 * 上にコード片 (トランポリン) を生成し、外側変数をキャプチャする。
 * このため実行可能スタックが必要 (-Wl,-z,execstack)。
 *
 * 期待出力:
 *   result=42
 */
#include <stdio.h>

typedef int (*fn_t)(int);

int run(fn_t f) { return f(10); }

int main(void) {
    int x = 32;
    int add(int n) { return x + n; }   /* nested fn — outer x をキャプチャ */
    printf("result=%d\n", run(add));
    return 0;
}
