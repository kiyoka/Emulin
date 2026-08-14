/* issue #926: poll/epoll の UDP 能動 peek が、blocking recvfrom で待っている側から
 *   到着データグラムを横取りしていないか。
 *
 *   Emulin は「UDP が読めるか」を判定するために実際に 1 データグラムを受信し
 *   Fileinfo.cachedDatagram に置く (次の recvfrom が消費する)。この peek が無同期だと、
 *   **guest が blocking な recvfrom で待っている最中に横取り**が起き、待機側は次の
 *   データグラムが来るまで永久に起きない。DNS のように応答が 1 つしか来ない用途では
 *   timeout になり、musl の resolver は EAI_AGAIN を返す (#926 の症状と同型)。
 *
 *   本テスト:
 *     - UDP socket を 1 つ作り、別スレッドが blocking recvfrom で待つ
 *     - main は同じ fd を poll() し続ける (= Emulin の peek を誘発する)
 *     - その状態でデータグラムを 1 つだけ送る
 *   期待値 (実 Linux): 待っているスレッドが必ず受け取る。
 *   横取りがあると受け取れず 3 秒で timeout する。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <poll.h>
#include <time.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

static int  sock_rx = -1;
static volatile int got = 0;
static volatile int rx_err = 0;

static void *receiver( void *arg )
{
    (void)arg;
    char buf[128];
    struct sockaddr_in from;
    socklen_t fl = sizeof(from);
    ssize_t n = recvfrom( sock_rx, buf, sizeof(buf), 0, (struct sockaddr *)&from, &fl );
    if( n < 0 ) { rx_err = errno; return NULL; }
    got = 1;
    return NULL;
}

int main( void )
{
    sock_rx = socket( AF_INET, SOCK_DGRAM, 0 );
    if( sock_rx < 0 ) { printf( "FAIL socket: %s\n", strerror(errno) ); return 1; }
    struct sockaddr_in a;
    memset( &a, 0, sizeof(a) );
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl( INADDR_LOOPBACK );
    a.sin_port = 0;
    if( bind( sock_rx, (struct sockaddr *)&a, sizeof(a) ) != 0 ) { printf( "FAIL bind: %s\n", strerror(errno) ); return 1; }
    socklen_t al = sizeof(a);
    if( getsockname( sock_rx, (struct sockaddr *)&a, &al ) != 0 ) { printf( "FAIL getsockname: %s\n", strerror(errno) ); return 1; }

    pthread_t th;
    if( pthread_create( &th, NULL, receiver, NULL ) != 0 ) { printf( "FAIL pthread_create\n" ); return 1; }
    usleep( 200 * 1000 );          /* 受信側が recvfrom に入るのを待つ */

    int sock_tx = socket( AF_INET, SOCK_DGRAM, 0 );
    if( sock_tx < 0 ) { printf( "FAIL socket(tx): %s\n", strerror(errno) ); return 1; }

    /* ★ ここから main は同じ fd を poll し続ける = Emulin の能動 peek を誘発する。
       その最中にデータグラムを 1 つだけ送り、受信スレッドが取れるかを見る。 */
    int sent = 0;
    for( int i = 0; i < 200 && !got; i++ ) {      /* 最大 ~2 秒 */
        struct pollfd p = { .fd = sock_rx, .events = POLLIN };
        poll( &p, 1, 5 );                          /* peek を誘発 */
        if( i == 10 && !sent ) {
            const char *msg = "dns-like-single-reply";
            if( sendto( sock_tx, msg, strlen(msg), 0, (struct sockaddr *)&a, sizeof(a) ) < 0 ) {
                printf( "FAIL sendto: %s\n", strerror(errno) ); return 1;
            }
            sent = 1;
        }
        usleep( 5 * 1000 );
    }

    /* ★ 受信スレッドは blocking recvfrom で止まったままになり得るので、
       失敗時は _exit で即座にプロセスごと終える (テストを hang させない)。 */
    if( rx_err ) { printf( "FAIL recvfrom: %s\n", strerror( rx_err ) ); fflush( stdout ); _exit( 1 ); }
    if( !got ) {
        printf( "FAIL: blocking recvfrom で待っているスレッドがデータグラムを受け取れない\n" );
        printf( "      = poll の能動 peek が横取りしている\n" );
        fflush( stdout );
        _exit( 1 );
    }
    printf( "PASS udp_peek_steal\n" );
    return 0;
}
