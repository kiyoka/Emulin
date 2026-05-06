// Multi-threaded calloc/free repro - mimics libcurl pthread AsynchDNS
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define N 256
#define ITERS 5000
#define ALLOC_SIZE 0xb0
#define NTHREADS 4

void *pools[NTHREADS][N];

void *worker(void *arg) {
    int t = (int)(long)arg;
    void **pool = pools[t];
    for (int i = 0; i < N; i++) {
        pool[i] = calloc(1, ALLOC_SIZE);
        if (!pool[i]) { fprintf(stderr, "T%d calloc fail\n", t); return NULL; }
        *(long*)pool[i] = (long)((t<<24) | i) + 0xdeadbeef00000000L;
    }
    srand(42 + t);
    for (int it = 0; it < ITERS; it++) {
        int i = rand() % N;
        free(pool[i]);
        pool[i] = calloc(1, ALLOC_SIZE);
        if (!pool[i]) { fprintf(stderr, "T%d realloc fail\n", t); return NULL; }
        *(long*)pool[i] = (long)((t<<24) | i) + 0xdeadbeef00000000L;
        for (int j = 0; j < N; j++) {
            if (j == i) continue;
            long expected = (long)((t<<24) | j) + 0xdeadbeef00000000L;
            long actual = *(long*)pool[j];
            if (actual != expected) {
                fprintf(stderr, "T%d OVERLAP at iter %d: pool[%d]=%p mark=0x%lx (exp 0x%lx)\n",
                        t, it, j, pool[j], actual, expected);
                return (void*)2L;
            }
        }
    }
    return NULL;
}

int main(void) {
    pthread_t th[NTHREADS];
    for (int i = 0; i < NTHREADS; i++) {
        pthread_create(&th[i], NULL, worker, (void*)(long)i);
    }
    int ret = 0;
    for (int i = 0; i < NTHREADS; i++) {
        void *r;
        pthread_join(th[i], &r);
        if (r) ret = (int)(long)r;
    }
    fprintf(stderr, "DONE ret=%d\n", ret);
    return ret;
}
