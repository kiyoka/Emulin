/* sigaltstack_dyn64.c — SA_ONSTACK + sigaltstack(2) のシグナル代替スタック配信テスト (glibc 動的)。
 *
 * issue #221 step 3d-2c-41: emulin は従来 sigaltstack(#131) を no-op stub にしていて SA_ONSTACK を
 * 無視し、ハンドラを「割込み点のスタック」で走らせていた。Go runtime は全 handler に SA_ONSTACK を
 * 立て M ごとに alt stack を登録するため、これを honor しないと handler が goroutine stack 上で走り、
 * Go の adjustSignalStack が "foreign stack 上の signal" と誤認 → needm → lockextra 無限 spin
 * (go env / go build の netpoller hang)。
 *
 * 本テストは「SA_ONSTACK ハンドラが登録済 alt stack の中で走る」ことを検証する:
 *   - sigaltstack で altbuf を登録 → sigaltstack(NULL,&oss) で読み戻せる (query)。
 *   - SA_ONSTACK 付きで SIGUSR1 を登録し raise。
 *   - handler 内のローカル変数アドレスが altbuf の範囲内なら onalt=1。
 *
 * software backend / native backend のどちらでも byte 一致で "query=1 handled=1 onalt=1" を出力する。
 */
#include <stdio.h>
#include <signal.h>
#include <string.h>
#include <stdint.h>

/* runtime 値の SIGSTKSZ に依存しない固定 1MB の alt stack。 */
static char altbuf[1 << 20];
static volatile int handled = 0;
static volatile int on_alt  = 0;
static uintptr_t alt_lo, alt_hi;

static void handler(int sig) {
    (void)sig;
    int local;                       /* handler フレーム上の変数 = 現在の stack */
    uintptr_t sp = (uintptr_t)&local;
    on_alt  = (sp >= alt_lo && sp < alt_hi) ? 1 : 0;
    handled = 1;
}

int main(void) {
    stack_t ss;
    ss.ss_sp    = altbuf;
    ss.ss_size  = sizeof(altbuf);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);
    alt_lo = (uintptr_t)altbuf;
    alt_hi = alt_lo + sizeof(altbuf);

    /* sigaltstack(NULL,&oss) で登録内容が読み戻せること (Go の minit がこれで判定する)。 */
    stack_t oss;
    memset(&oss, 0, sizeof(oss));
    sigaltstack(NULL, &oss);
    int query_ok = (oss.ss_sp == (void *)altbuf
                    && oss.ss_size == sizeof(altbuf)
                    && (oss.ss_flags & SS_DISABLE) == 0);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handler;
    sa.sa_flags   = SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGUSR1, &sa, NULL);

    raise(SIGUSR1);

    printf("sigaltstack: query=%d handled=%d onalt=%d\n", query_ok, handled, on_alt);
    fflush(stdout);
    return 0;
}
