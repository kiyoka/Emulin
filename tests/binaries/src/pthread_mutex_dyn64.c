/* pthread_mutex_dyn64.c — 4 worker threads + mutex で counter 増加 (Phase 27 step 29)
 *
 * 4 つの thread が共有 counter を 1000 回 ++ する。
 * mutex がなければ race で counter < 4000 になる、mutex があれば 4000 になる。
 *
 * 期待出力:
 *   counter=4000
 */
#include <stdio.h>
#include <pthread.h>

#define NTHREADS 4
#define NITER    1000

static int counter = 0;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void *worker(void *arg) {
    for (int i = 0; i < NITER; i++) {
        pthread_mutex_lock(&lock);
        counter++;
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

int main(void) {
    pthread_t t[NTHREADS];
    for (int i = 0; i < NTHREADS; i++) {
        pthread_create(&t[i], NULL, worker, NULL);
    }
    for (int i = 0; i < NTHREADS; i++) {
        pthread_join(t[i], NULL);
    }
    printf("counter=%d\n", counter);
    return 0;
}
