/* printf_dyn64.c — 動的リンク + 浮動小数 printf 回帰固定
 *
 * Phase 25 で発見・修正した複数バグの検収:
 *   - PF (parity flag) を UCOMISD/COMISD で更新する
 *     → printf("%f", 0.0/0.0) の "-nan" 出力
 *   - ADC / SBB がフラグを更新するようにする
 *     → __printf_fp の bignum 演算で 1e96 以上の値が壊れる致命バグ
 *
 * libc.so.6 を動的リンクして glibc の __printf_fp を実行する
 * (= step 2 の hello_dyn より bignum 経路を深く叩く)。
 *
 * 期待出力:
 *   nan: -nan
 *   inf:  inf
 *   1e0  = 1
 *   1e10 = 1e+10
 *   1e96 = 1e+96
 *   1e308 = 1e+308
 *   DBL_MAX_dec = 1.79769e+308
 *   pi/g = 3.14159
 *   eul/e = 2.718281828459045e+00
 */
#include <stdio.h>
#include <float.h>
#include <math.h>

int main(void) {
    /* PF / NaN-Inf 検出 */
    printf("nan: %.0f\n", 0.0 / 0.0);
    printf("inf: %4.0f\n", 1.0 / 0.0);

    /* 小〜中規模 (bignum 浅い) */
    printf("1e0  = %g\n", 1.0);
    printf("1e10 = %g\n", 1e10);

    /* bignum 深い: ADC carry 連鎖が必要 */
    double v = 1.0;
    for (int i = 0; i < 96;  i++) v *= 10.0;
    printf("1e96 = %g\n", v);
    for (int i = 0; i < 212; i++) v *= 10.0;  /* 1e96 -> 1e308 */
    printf("1e308 = %g\n", v);

    /* DBL_MAX を %g で */
    printf("DBL_MAX_dec = %g\n", DBL_MAX);

    /* 中位精度の通常 double */
    printf("pi/g = %g\n", 3.14159265358979);
    printf("eul/e = %.15e\n", 2.718281828459045);

    return 0;
}
