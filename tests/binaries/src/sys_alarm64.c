/* sys_alarm64.c — alarm (syscall #37)
 *
 * alarm(0) は前のアラーム残時間を返す (まだ設定していなければ 0)。
 * alarm(N) は新規にアラームをセットして前の値を返す。
 * シグナル配信のテストではなく、戻り値だけ確認する。
 */
#include "sys64.h"

void _start(void) {
    long r1 = sys_alarm(5);
    put("first=");
    put_dec(r1);
    put("\n");

    long r2 = sys_alarm(0);
    put("second_nonneg=");
    put_dec(r2 >= 0 ? 1 : 0);
    put("\n");
    sys_exit(0);
}
