/* integ_dyn64.c — native backend の総合 (integration) テスト
 *
 * issue #221 step 3d-2c-15: native が今まで個別に検証してきた機能 (動的リンク / pthread /
 * mutex=futex / signal 配信 / file I/O) を 1 binary で組み合わせ、連携して software と byte
 * 一致で動くことを検証する。実 workload は複数機能が同時に絡むので、個別テストの和では拾えない
 * 機能間の相互作用 (worker vCPU が回っている最中の signal、mutex 競合下の futex 等) を 1 本で通す。
 *
 * (1) SIGUSR1 handler を登録
 * (2) NT worker thread を起こし mutex 下で共有 counter を NITER 回 ++ (futex 競合)
 * (3) join 後、自分に SIGUSR1 を 3 回 raise (syscall 境界で handler 発火)
 * (4) file に書いて読み戻す (open/write/lseek/read/close/unlink)
 *
 * 期待出力 (決定的):
 *   counter=8000
 *   sig_count=3
 *   file: HELLO-INTEG
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>

#define NT    8
#define NITER 1000

static int counter = 0;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t sig_count = 0;

static void on_usr1( int s ) { (void)s; sig_count++; }

static void *worker( void *a ) {
    (void)a;
    for( int i = 0; i < NITER; i++ ) {
        pthread_mutex_lock( &lock );
        counter++;
        pthread_mutex_unlock( &lock );
    }
    return NULL;
}

int main( void ) {
    /* (1) signal handler */
    struct sigaction sa;
    memset( &sa, 0, sizeof sa );
    sa.sa_handler = on_usr1;
    sigaction( SIGUSR1, &sa, NULL );

    /* (2) NT worker + mutex (futex 競合) */
    pthread_t t[NT];
    for( int i = 0; i < NT; i++ ) pthread_create( &t[i], NULL, worker, NULL );
    for( int i = 0; i < NT; i++ ) pthread_join( t[i], NULL );
    printf( "counter=%d\n", counter );
    fflush( stdout );

    /* (3) 自分に SIGUSR1 ×3 (各 raise=tgkill の syscall 境界で handler 発火) */
    for( int i = 0; i < 3; i++ ) raise( SIGUSR1 );
    printf( "sig_count=%d\n", (int)sig_count );
    fflush( stdout );

    /* (4) file I/O */
    int fd = open( "/tmp/integ64.dat", O_RDWR | O_CREAT | O_TRUNC, 0644 );
    if( fd < 0 ) { perror( "open" ); return 1; }
    write( fd, "HELLO-INTEG", 11 );
    lseek( fd, 0, SEEK_SET );
    char buf[32];
    memset( buf, 0, sizeof buf );
    read( fd, buf, 11 );
    close( fd );
    unlink( "/tmp/integ64.dat" );
    printf( "file: %s\n", buf );

    return 0;
}
