/* issue #921: exec に失敗した子 (exit 127) の終了が、SIGCHLD 駆動の刈り取りで
 *   観測できるかを見る。codex (tokio) の子プロセス管理と同じ形:
 *     - SIGCHLD ハンドラは self-pipe に 1 byte 書くだけ
 *     - event loop は epoll で self-pipe を待つ
 *     - 起きたら waitpid(WNOHANG) で刈り取る
 *   実機の codex が "timeout waiting for child process to exit" を出しているので、
 *   この形で終了が観測できないのではないかを確かめる。
 *
 *   期待値 (実 Linux):
 *     - execve が失敗した子は _exit(127) する
 *     - 親に SIGCHLD が届く
 *     - waitpid(WNOHANG) が pid と status(exit=127) を返す
 *   PASS/FAIL を stdout に出す (1 テスト 1 syscall 群の原則に従い、判定は親のみ)。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <sys/wait.h>
#include <sys/epoll.h>

static int sigpipe[2];

static void on_sigchld( int sig )
{
    char b = 'c';
    ssize_t n = write( sigpipe[1], &b, 1 );   /* async-signal-safe */
    (void)sig; (void)n;
}

int main( void )
{
    if( pipe( sigpipe ) != 0 ) { printf( "FAIL pipe: %s\n", strerror(errno) ); return 1; }

    struct sigaction sa;
    memset( &sa, 0, sizeof(sa) );
    sa.sa_handler = on_sigchld;
    sa.sa_flags   = SA_RESTART | SA_NOCLDSTOP;
    if( sigaction( SIGCHLD, &sa, NULL ) != 0 ) { printf( "FAIL sigaction: %s\n", strerror(errno) ); return 1; }

    int ep = epoll_create1( 0 );
    if( ep < 0 ) { printf( "FAIL epoll_create1: %s\n", strerror(errno) ); return 1; }
    struct epoll_event ev;
    memset( &ev, 0, sizeof(ev) );
    ev.events  = EPOLLIN;
    ev.data.fd = sigpipe[0];
    if( epoll_ctl( ep, EPOLL_CTL_ADD, sigpipe[0], &ev ) != 0 ) { printf( "FAIL epoll_ctl: %s\n", strerror(errno) ); return 1; }

    const int ROUNDS = 20;
    int reaped = 0, notified = 0;
    for( int i = 0; i < ROUNDS; i++ ) {
        pid_t pid = fork();
        if( pid < 0 ) { printf( "FAIL fork: %s\n", strerror(errno) ); return 1; }
        if( pid == 0 ) {
            /* ★ 存在しない実行ファイル: execve は失敗し、子は 127 で終わる (shell と同じ) */
            char *argv[] = { (char*)"/nonexistent/emulin-921-probe", NULL };
            char *envp[] = { NULL };
            execve( argv[0], argv, envp );
            _exit( 127 );
        }
        /* --- 親: SIGCHLD の到着を epoll で待ち、waitpid(WNOHANG) で刈り取る --- */
        int got = 0;
        for( int spin = 0; spin < 50 && !got; spin++ ) {   /* 最大 5 秒 */
            struct epoll_event out[4];
            int n = epoll_wait( ep, out, 4, 100 );
            if( n > 0 ) {
                char drain[64];
                ssize_t r = read( sigpipe[0], drain, sizeof(drain) );
                (void)r;
                notified++;
            }
            int status = 0;
            pid_t w = waitpid( pid, &status, WNOHANG );
            if( w == pid ) {
                got = 1;
                reaped++;
                if( !WIFEXITED( status ) || WEXITSTATUS( status ) != 127 ) {
                    printf( "FAIL round=%d status: WIFEXITED=%d code=%d\n",
                            i, WIFEXITED( status ), WEXITSTATUS( status ) );
                    return 1;
                }
            } else if( w < 0 ) {
                printf( "FAIL waitpid round=%d: %s\n", i, strerror(errno) );
                return 1;
            }
        }
        if( !got ) {
            printf( "FAIL round=%d: 子 %d の終了が 5 秒で観測できない "
                    "(SIGCHLD 通知 %d 回)\n", i, (int)pid, notified );
            return 1;
        }
    }
    printf( "PASS sigchld_execfail\n" );   /* 通知回数は環境で変わるので出さない */
    return 0;
}
