/* issue #921: kill(2) の **プロセスグループ宛** (pid <= 0) の意味論。
 *
 *   旧実装は「pid<=0 は self へ送信 (簡易実装)」だったため、子のプロセスグループを
 *   掃除する定番の `kill(-pgid, SIGKILL)` が **呼び出し元自身を殺していた**。
 *   codex が tool 実行の後始末でこれを呼び、codex 自身が SIGKILL されて
 *   「Killed」で落ちていた。
 *
 *   Linux の意味論:
 *     pid  >  0 … そのプロセス
 *     pid ==  0 … 呼び出し元と同じ process group の全員
 *     pid <  -1 … process group (-pid) の全員
 *
 *   本テスト: 子を新しい process group に入れて 2 つ走らせ、`kill(-pgid, SIGKILL)` で
 *   その 2 つだけが死に、**親が生き残る**ことを確認する。
 *   旧実装だと親自身が SIGKILL されるので、この test は出力を出せずに死ぬ (= FAIL)。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/wait.h>

static void child_forever( void )
{
    for( ;; ) pause();          /* signal で殺されるまで待つ */
}

int main( void )
{
    pid_t a = fork();
    if( a < 0 ) { printf( "FAIL fork a: %s\n", strerror(errno) ); return 1; }
    if( a == 0 ) {
        if( setpgid( 0, 0 ) != 0 ) _exit( 20 );   /* 自分を leader とする新しい group */
        pid_t b = fork();
        if( b == 0 ) child_forever();             /* group を継承した 2 人目 */
        child_forever();
    }
    /* 親: 子が setpgid を終えるまで少し待つ (pgid == a になる) */
    usleep( 200 * 1000 );
    if( setpgid( a, a ) != 0 && errno != EACCES && errno != ESRCH ) {
        /* 競合で既に子が設定済みなら EACCES。どちらでも良い */
    }

    if( kill( -a, SIGKILL ) != 0 ) { printf( "FAIL kill(-pgid): %s\n", strerror(errno) ); return 1; }

    /* ★ ここに到達できること自体が「親が巻き添えで死んでいない」証拠 */
    int st = 0;
    pid_t w = waitpid( a, &st, 0 );
    if( w != a ) { printf( "FAIL waitpid: %s\n", strerror(errno) ); return 1; }
    if( !WIFSIGNALED( st ) || WTERMSIG( st ) != SIGKILL ) {
        printf( "FAIL: 子が SIGKILL で死んでいない (signaled=%d sig=%d)\n",
                WIFSIGNALED( st ), WIFSIGNALED( st ) ? WTERMSIG( st ) : -1 );
        return 1;
    }
    /* 2 人目 (孫) も同じ group なので死んでいるはず。孫は親から見て子ではないので
       waitpid はできない。ここでは「親が生きていて子が SIGKILL された」ことを確認する。 */
    printf( "PASS killpg\n" );
    return 0;
}
