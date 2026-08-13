/* issue #921: EPOLLET の EPOLLOUT が「buffer full → 空き」の遷移で再 arm されるか。
 *
 *   codex (code mode) は code-mode host へ 474KB の JSON を 1 回の write で送る。
 *   pipe buffer は 64KB なので部分書き込みになり、残りは EPOLLOUT を待って書く。
 *   Emulin は EPOLLOUT を **boolean ラッチ**で抑制していたため、full→空き の遷移を
 *   スキャンが観測しないとラッチが立ったままになり、EPOLLOUT が二度と来ない。
 *   → 送り手は残りを送れず、受け手は残りを待ち続けて双方停止する。
 *
 *   本テスト: 親が pipe へ大きな buffer を EPOLLET|EPOLLOUT で送り切れるか。
 *   子は少しずつ読む (= full/空き の遷移を作る)。
 *   期待値 (実 Linux): 全 byte を送り切って PASS。
 *   バグがあると EPOLLOUT が来ず epoll_wait が timeout し FAIL。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/wait.h>

#define TOTAL (400 * 1024)      /* pipe buffer (64KB) より十分大きい (codex は 474KB を送る) */

int main( void )
{
    int fds[2];
    if( pipe( fds ) != 0 ) { printf( "FAIL pipe: %s\n", strerror(errno) ); return 1; }

    pid_t child = fork();
    if( child < 0 ) { printf( "FAIL fork: %s\n", strerror(errno) ); return 1; }
    if( child == 0 ) {
        close( fds[1] );
        char buf[4096];
        long got = 0;
        for( ;; ) {
            ssize_t n = read( fds[0], buf, sizeof(buf) );
            if( n <= 0 ) break;
            got += n;
            usleep( 1000 );          /* ゆっくり読む = full/空き の遷移を作る */
        }
        _exit( got == TOTAL ? 0 : 1 );
    }
    close( fds[0] );

    if( fcntl( fds[1], F_SETFL, O_NONBLOCK ) != 0 ) { printf( "FAIL fcntl: %s\n", strerror(errno) ); return 1; }
    int ep = epoll_create1( 0 );
    struct epoll_event ev;
    memset( &ev, 0, sizeof(ev) );
    ev.events  = EPOLLOUT | EPOLLET;     /* ★ edge-triggered な writable 待ち (tokio と同じ) */
    ev.data.fd = fds[1];
    if( epoll_ctl( ep, EPOLL_CTL_ADD, fds[1], &ev ) != 0 ) { printf( "FAIL epoll_ctl: %s\n", strerror(errno) ); return 1; }

    char *data = malloc( TOTAL );
    memset( data, 'x', TOTAL );
    long sent = 0;
    int  waits = 0, timeouts = 0;
    while( sent < TOTAL ) {
        ssize_t n = write( fds[1], data + sent, TOTAL - sent );
        if( n > 0 ) { sent += n; continue; }
        if( n < 0 && errno != EAGAIN ) { printf( "FAIL write: %s\n", strerror(errno) ); return 1; }
        /* EAGAIN = buffer full。EPOLLOUT の edge を待つ */
        struct epoll_event out[4];
        int r = epoll_wait( ep, out, 4, 3000 );
        waits++;
        if( r == 0 ) {
            timeouts++;
            printf( "FAIL: EPOLLOUT が 3 秒来ない (送信済 %ld/%d, epoll_wait %d 回)\n",
                    sent, TOTAL, waits );
            printf( "      = full→空き の遷移で edge が再 arm されていない\n" );
            kill( child, 9 ); wait( NULL );
            return 1;
        }
    }
    close( fds[1] );
    int st = 0;
    waitpid( child, &st, 0 );
    if( !WIFEXITED( st ) || WEXITSTATUS( st ) != 0 ) { printf( "FAIL child status=%d\n", st ); return 1; }
    printf( "PASS epollet_out_pipe\n" );   /* 回数は環境で変わるので出さない (expected と diff するため) */
    return 0;
}
