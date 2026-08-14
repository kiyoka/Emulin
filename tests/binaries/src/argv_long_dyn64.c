/* issue #921: execve の argv が長くても切られないか。
 *
 *   Memory.loadString が 10000 byte で**無言で**打ち切っていたため、10KB を超える
 *   引数を渡す execve が壊れたコマンドを実行していた。codex (code mode) が生成する
 *   bash スクリプトが約 10KB で、`bash: -c: line 129: unexpected EOF` になっていた。
 *   guest からは「切られた」ことを知る手段が無く、症状が実行対象の構文エラーとして
 *   出るので原因に辿り着きにくい (実際そうなった)。
 *
 *   本テスト: 20000 byte の引数を自分自身に execve して、長さが保たれているかを見る。
 *   期待値 (実 Linux): 保たれる (MAX_ARG_STRLEN = 128KB)。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/wait.h>

#define ARGLEN 20000

int main( int argc, char **argv )
{
    if( argc >= 3 && strcmp( argv[1], "child" ) == 0 ) {
        /* 受け取った長さが一致するか。末尾も壊れていないか (先頭/末尾に marker) */
        size_t n = strlen( argv[2] );
        if( n != ARGLEN )                 _exit( 10 );
        if( argv[2][0] != 'A' )           _exit( 11 );
        if( argv[2][ARGLEN - 1] != 'Z' )  _exit( 12 );
        _exit( 0 );
    }

    char *big = malloc( ARGLEN + 1 );
    memset( big, 'x', ARGLEN );
    big[0] = 'A';
    big[ARGLEN - 1] = 'Z';
    big[ARGLEN] = '\0';

    pid_t pid = fork();
    if( pid < 0 ) { printf( "FAIL fork: %s\n", strerror(errno) ); return 1; }
    if( pid == 0 ) {
        char *av[] = { argv[0], (char*)"child", big, NULL };
        execve( argv[0], av, (char*[]){ NULL } );
        _exit( 99 );   /* execve 失敗 */
    }
    int st = 0;
    if( waitpid( pid, &st, 0 ) != pid ) { printf( "FAIL waitpid: %s\n", strerror(errno) ); return 1; }
    if( !WIFEXITED( st ) ) { printf( "FAIL child signaled: %d\n", st ); return 1; }
    switch( WEXITSTATUS( st ) ) {
      case 0:  printf( "PASS argv_long\n" ); return 0;
      case 10: printf( "FAIL: argv が切り詰められた (長さ不一致)\n" ); return 1;
      case 11: printf( "FAIL: argv 先頭が壊れている\n" ); return 1;
      case 12: printf( "FAIL: argv 末尾が壊れている (= truncate)\n" ); return 1;
      case 99: printf( "FAIL: execve 自体が失敗\n" ); return 1;
      default: printf( "FAIL: 予期しない exit=%d\n", WEXITSTATUS( st ) ); return 1;
    }
}
