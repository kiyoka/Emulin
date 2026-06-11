/* async_signal_dyn64.c — 実行中スレッドへの async signal 配信のテスト (glibc 動的)。
 *
 * issue #221 step 3d-2c-39: emulin native は従来 signal を syscall 境界でしか配信できず (#258)、
 * guest code を syscall 無しの tight-loop で実行中のスレッドには signal が届かなかった (Go の
 * async preemption が hang する原因、#287)。本テストはその「実行中スレッドへの async 配信」を検証する:
 *
 *   - worker スレッドは syscall を 1 つも呼ばない tight-loop で spin する (volatile flag を待つ)。
 *   - main は worker に SIGUSR1 を pthread_kill (= tgkill) で送る。
 *   - handler が flag を立て、worker が spin を抜けて終了する。
 *
 * software backend は per-instruction で pending signal を見るので配信できる。native は async signal
 * 配信 (vCPU を host signal で KVM_RUN から割込ませ guest signal frame を組む) が無いと worker が
 * 永久 spin して hang する。修正後は native==software で "async: delivered counter>0" を出力する。
 */
#include <stdio.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
#include <sys/syscall.h>

static volatile sig_atomic_t got = 0;
static volatile unsigned long handler_hits = 0;
static volatile long handler_tid = 0;
static volatile long worker_tid  = 0;
static volatile sig_atomic_t spinning = 0;   // worker が syscall-free spin に入ったら 1

static void on_usr1(int sig) {
    (void)sig;
    handler_hits++;
    handler_tid = syscall(SYS_gettid);
    got = 1;
}

static void *worker(void *arg) {
    (void)arg;
    worker_tid = syscall(SYS_gettid);
    /* syscall を一切呼ばない tight-loop。volatile + memory clobber で最適化除去を防ぐ。
     * got は handler (async 配信) でしか立たないので、配信されなければ無限ループ。 */
    unsigned long spins = 0;
    spinning = 1;   /* これ以降 syscall を一切呼ばない */
    while (!got) {
        spins++;
        __asm__ __volatile__("" ::: "memory");
    }
    /* spins は実行系で変わるので出力しない。got が立った事実だけが本質。 */
    return (void *)spins;
}

int main(void) {
    struct sigaction sa;
    sa.sa_handler = on_usr1;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGUSR1, &sa, NULL);

    pthread_t t;
    pthread_create(&t, NULL, worker, NULL);

    /* ★worker 作成後に main 自身は SIGUSR1 を block する。これで pthread_kill(worker) で
     * 送った SIGUSR1 は main では受けられず、必ず worker に配信されねばならない (worker は
     * mask を継承しない=作成時の default unblocked のまま)。worker は syscall を呼ばない
     * tight-loop なので、native の async signal 配信が無いと永久 spin して hang する。
     * = handler が「worker 上で」発火することを強制するテスト。 */
    sigset_t block;
    sigemptyset(&block);
    sigaddset(&block, SIGUSR1);
    pthread_sigmask(SIG_BLOCK, &block, NULL);

    /* worker が syscall-free spin に入るまで待つ。これで signal は確実に「spin 中」に届く。 */
    while (!spinning) usleep(1000);
    usleep(5000);   /* worker が深く spin してから送る */
    for (int i = 0; i < 5000 && !got; i++) {
        pthread_kill(t, SIGUSR1);
        usleep(2000);
    }

    pthread_join(t, NULL);
    /* got=1 かつ handler が 1 回以上発火 = worker への async 配信成功。回数は非決定的なので
     * ">0" で正規化して決定的出力にする。 */
    printf("async: delivered=%d onworker=%d\n", (int)got, (handler_tid==worker_tid && worker_tid!=0) ? 1 : 0);
    fflush(stdout);
    return 0;
}
