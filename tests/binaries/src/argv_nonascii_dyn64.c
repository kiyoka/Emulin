/* issue #932: execve の argv / envp が **非 ASCII バイトをそのまま**運べるか。
 *
 *   guest にとって argv/envp はバイト列であって文字列ではない。Emulin は Java の
 *   String (char 列) を経由するので、読み (guest→Java) と書き (Java→guest) の
 *   charset が対になっていないと非 ASCII が壊れる。
 *
 *   0.8.2 で実際に壊れた形 (#921 の修正が読み側だけ raw(ISO-8859-1) に変わり、
 *   書き側 Process.buildInitialStack64 が既定 charset(UTF-8) のまま残った):
 *
 *     渡した   : F c5 91 t ...            (ő = U+0151 の UTF-8)
 *     受け取り : F c3 85 c2 91 t ...      1 バイトずつ UTF-8 で再エンコード
 *
 *   実害: apt の ca-certificates postinst が Hungarian 名の cert
 *   (NetLock_Arany_=Class_Gold=_Főtanúsítvány.crt) を開けず、依存する 6 パッケージが
 *   芋づるで configure 不能になった。**この穴は既存 313 テストを 1 件も落とさずに
 *   出荷された** = 非 ASCII の argv/env を通すテストが 1 本も無かった。
 *
 *   本テスト: 非 ASCII 名のファイルを作り、そのパスを argv で、日本語を env で
 *   自分自身に execve して、子が (1) argv (2) env (3) そのパスで open できるか を見る。
 *   文字列リテラルは 8 進エスケープで書く (16 進は後続文字を巻き込むため)。
 *     ő = \305\221   ú = \303\272   日本語 = \346\227\245\346\234\254\350\252\236
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/wait.h>

#define PATH_NONASCII "/tmp/emulin_argv_\305\221\303\272_\346\227\245\346\234\254\350\252\236.txt"
#define ENV_NONASCII  "\346\227\245\346\234\254\350\252\236"     /* 日本語 */
#define CONTENT       "ok"

static void hexdump( const char *label, const char *s )
{
    printf( "  %s: ", label );
    if( s == NULL ) { printf( "(null)\n" ); return; }
    for( const unsigned char *p = (const unsigned char *)s; *p; p++ ) printf( "%02x ", *p );
    printf( "\n" );
}

static int child( int argc, char **argv )
{
    int bad = 0;

    /* ★ 子の判定は ASCII の marker (argv[1]) で行い、検査対象の非 ASCII は argv[2] に置く。
       検査対象そのもので判定すると、**壊れたときに子が親の分岐に入って再帰**し、
       テストが FAIL ではなく hang する (最初にこの形で書いて実際にそうなった)。 */
    if( argc < 3 || strcmp( argv[2], PATH_NONASCII ) != 0 ) {
        printf( "FAIL: argv が壊れている\n" );
        hexdump( "expected", PATH_NONASCII );
        hexdump( "actual  ", argc >= 3 ? argv[2] : NULL );
        bad = 1;
    }

    const char *e = getenv( "EMULIN_TEST_NONASCII" );
    if( e == NULL || strcmp( e, ENV_NONASCII ) != 0 ) {
        printf( "FAIL: env が壊れている\n" );
        hexdump( "expected", ENV_NONASCII );
        hexdump( "actual  ", e );
        bad = 1;
    }

    /* ★ 実害が出たのはここ: 正しいバイト列で来ていなければ open が ENOENT になる。 */
    int fd = open( argc >= 3 ? argv[2] : "", O_RDONLY );
    if( fd < 0 ) {
        printf( "FAIL: 非 ASCII 名のファイルを argv 経由で open できない: %s\n", strerror( errno ) );
        bad = 1;
    } else {
        char buf[16];
        ssize_t n = read( fd, buf, sizeof(buf) - 1 );
        close( fd );
        if( n < 0 ) n = 0;
        buf[n] = '\0';
        if( strcmp( buf, CONTENT ) != 0 ) {
            printf( "FAIL: 内容が違う [%s]\n", buf );
            bad = 1;
        }
    }

    if( bad ) return 1;
    printf( "PASS argv_nonascii\n" );
    return 0;
}

int main( int argc, char **argv )
{
    if( argc >= 2 && strcmp( argv[1], "child" ) == 0 ) return child( argc, argv );

    /* 親: 非 ASCII 名のファイルを作る (ここは guest 内で完結するので必ず成功する) */
    int fd = open( PATH_NONASCII, O_CREAT | O_TRUNC | O_WRONLY, 0644 );
    if( fd < 0 ) { printf( "FAIL setup open: %s\n", strerror( errno ) ); return 1; }
    if( write( fd, CONTENT, strlen( CONTENT ) ) != (ssize_t)strlen( CONTENT ) ) {
        printf( "FAIL setup write: %s\n", strerror( errno ) ); close( fd ); return 1;
    }
    close( fd );

    pid_t pid = fork();
    if( pid < 0 ) { printf( "FAIL fork: %s\n", strerror( errno ) ); return 1; }
    if( pid == 0 ) {
        char *av[] = { argv[0], (char *)"child", (char *)PATH_NONASCII, NULL };
        char *ev[] = { (char *)"EMULIN_TEST_NONASCII=" ENV_NONASCII, NULL };
        execve( argv[0], av, ev );
        _exit( 99 );
    }
    int st = 0;
    waitpid( pid, &st, 0 );
    unlink( PATH_NONASCII );

    if( WIFEXITED( st ) && WEXITSTATUS( st ) == 99 ) { printf( "FAIL: execve 自体が失敗\n" ); return 1; }
    if( !WIFEXITED( st ) ) { printf( "FAIL: 子が異常終了 (status=0x%x)\n", st ); return 1; }
    return WEXITSTATUS( st );
}
