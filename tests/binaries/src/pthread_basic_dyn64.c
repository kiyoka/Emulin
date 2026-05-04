/* pthread_basic_dyn64.c — pthread_create + join の基礎動作 (Phase 27 step 28)
 *
 * worker thread を 1 個作って int を加算してもらい、join して結果を読む。
 * pthread_create / pthread_join / mutex 経由の futex を確認する。
 *
 * 期待出力:
 *   start
 *   worker_done
 *   joined value=42
 */
#include <stdio.h>
#include <pthread.h>

static void *worker(void *arg) {
    int *p = (int *)arg;
    *p = 42;
    printf("worker_done\n");
    fflush(stdout);
    return NULL;
}

int main(void) {
    int value = 0;
    pthread_t t;
    printf("start\n");
    fflush(stdout);
    if (pthread_create(&t, NULL, worker, &value) != 0) {
        printf("pthread_create=fail\n");
        return 1;
    }
    pthread_join(t, NULL);
    printf("joined value=%d\n", value);
    return 0;
}
