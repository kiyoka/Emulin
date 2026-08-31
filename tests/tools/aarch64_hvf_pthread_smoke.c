#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

static _Thread_local int tls_value = 7;
static int shared_value;

static void *worker(void *argument) {
    int expected = *(int *)argument;
    if (tls_value != 7) return (void *)1;
    tls_value = expected;
    shared_value = tls_value + 1;
    return NULL;
}

int main(void) {
    pthread_t thread;
    void *result = NULL;
    int worker_value = 41;
    if (pthread_create(&thread, NULL, worker, &worker_value) != 0) return 2;
    if (pthread_join(thread, &result) != 0) return 3;
    if (result != NULL || tls_value != 7 || shared_value != 42) return 4;
    puts("aarch64-hvf-pthread-tls-ok");
    return 0;
}
