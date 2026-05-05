/* pthread_sigmask_dyn64.c — per-thread signal mask + thread-targeted signal (Phase 27 step 34/35)
 *
 * 親 thread が SIGUSR1 を block。子 thread が pthread_sigmask で自分の
 * mask を確認 → 親と独立した値であることを確認。
 * 子が pthread_kill(parent, SIGUSR1) → 親の pending に入る (子は受け取らない)
 * 子が unblock + pthread_kill(self, SIGUSR1) → 子の handler 動作
 * 親が unblock → pending の SIGUSR1 が配信される
 *
 * 期待出力:
 *   parent_blocks_usr1
 *   child_initial_mask_inherited
 *   child_unblocked
 *   child_handler_fired
 *   parent_handler_fired
 */
#include <stdio.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>

static volatile int parent_handler_fired = 0;
static volatile int child_handler_fired = 0;
static pthread_t main_tid;

static void on_usr1(int sig) {
    if (pthread_equal(pthread_self(), main_tid)) parent_handler_fired = 1;
    else child_handler_fired = 1;
}

static void *worker(void *arg) {
    sigset_t cur;
    pthread_sigmask(SIG_BLOCK, NULL, &cur);
    if (sigismember(&cur, SIGUSR1)) {
        printf("child_initial_mask_inherited\n");
        fflush(stdout);
    }
    /* 親に kill (親は block 中なので配信されず pending) */
    pthread_kill(main_tid, SIGUSR1);
    usleep(50*1000);
    /* 子だけ unblock */
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGUSR1);
    pthread_sigmask(SIG_UNBLOCK, &set, NULL);
    pthread_sigmask(SIG_BLOCK, NULL, &cur);
    if (!sigismember(&cur, SIGUSR1)) {
        printf("child_unblocked\n");
        fflush(stdout);
    }
    /* 子は unblock 状態で自分に kill → 子の handler */
    pthread_kill(pthread_self(), SIGUSR1);
    usleep(100*1000);
    if (child_handler_fired && !parent_handler_fired) {
        printf("child_handler_fired\n");
        fflush(stdout);
    }
    return NULL;
}

int main(void) {
    main_tid = pthread_self();

    /* SIGUSR1 handler */
    struct sigaction sa = {0};
    sa.sa_handler = on_usr1;
    sigaction(SIGUSR1, &sa, NULL);

    /* 親 thread で SIGUSR1 を block */
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGUSR1);
    pthread_sigmask(SIG_BLOCK, &set, NULL);
    printf("parent_blocks_usr1\n");
    fflush(stdout);

    pthread_t w;
    pthread_create(&w, NULL, worker, NULL);
    pthread_join(w, NULL);

    /* 親が unblock → 子から送られていた pending SIGUSR1 が配信される */
    pthread_sigmask(SIG_UNBLOCK, &set, NULL);
    usleep(50*1000);
    if (parent_handler_fired) {
        printf("parent_handler_fired\n");
    }
    return 0;
}
