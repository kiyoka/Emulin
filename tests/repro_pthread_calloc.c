// Simplest pthread + malloc test
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

#define NTHREADS 4

void *worker(void *arg) {
    int t = (int)(long)arg;
    void *p = calloc(1, 0xb0);
    if (!p) { fprintf(stderr, "T%d calloc fail\n", t); return (void*)1L; }
    fprintf(stderr, "T%d got %p\n", t, p);
    free(p);
    return NULL;
}

int main(void) {
    pthread_t th[NTHREADS];
    for (int i = 0; i < NTHREADS; i++) pthread_create(&th[i], NULL, worker, (void*)(long)i);
    for (int i = 0; i < NTHREADS; i++) pthread_join(th[i], NULL);
    fprintf(stderr, "DONE\n");
    return 0;
}
