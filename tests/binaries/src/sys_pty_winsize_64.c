/* sys_pty_winsize_64.c — pty の TIOCSWINSZ / TIOCGWINSZ (window size) を検証
 *
 * issue #225 (sshd 越し emacs/vim でウィンドウリサイズが反映されない): SSH client
 *   のリサイズは sshd が pty master に ioctl(TIOCSWINSZ) → カーネルが foreground
 *   process group に SIGWINCH → app が ioctl(TIOCGWINSZ) で新サイズ取得・再描画、
 *   という流れ。emulin は (a) TIOCSWINSZ が no-op で受信値を捨て、(b) pty に
 *   winsize を保持せず、(c) TIOCGWINSZ が pty でなく launcher console のサイズを
 *   返していたため追従しなかった。
 *
 * 本 test は SIGWINCH 配信を除く「winsize の保持と master↔slave 間共有」を検証する
 *   (SIGWINCH 配信は sys_pty_winch_64.c)。実 fs 不要の hermetic test:
 *   1. init       : 未設定の slave で TIOCGWINSZ → fallback 25x80
 *   2. slave_get  : master で TIOCSWINSZ(40x120) 後、slave の TIOCGWINSZ → 40x120
 *   3. master_get : slave で TIOCSWINSZ(50x160) 後、master の TIOCGWINSZ → 50x160
 *      (master/slave どちらの fd でも同じ pty(ptn) の winsize を指すこと)
 *
 * 期待 stdout:
 *   master=ok
 *   slave=ok
 *   init=25x80
 *   slave_get=40x120
 *   master_get=50x160
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430
#define TIOCSPTLCK 0x40045431
#define TIOCGWINSZ 0x5413
#define TIOCSWINSZ 0x5414

struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; };

static char ptspath[32];
static void build_pts_path( long n ) {
    const char *pfx = "/dev/pts/";
    int i = 0;
    while( pfx[i] ) { ptspath[i] = pfx[i]; i++; }
    char tmp[12];
    int j = 0;
    if( n == 0 ) { tmp[j++] = '0'; }
    else { while( n > 0 ) { tmp[j++] = (char)('0' + (n % 10)); n /= 10; } }
    while( j > 0 ) { ptspath[i++] = tmp[--j]; }
    ptspath[i] = 0;
}

/* "rowxcol" を 1 行で出力 */
static void put_size( const char *tag, struct winsize *w ) {
    put( tag ); put_dec( w->ws_row ); put( "x" ); put_dec( w->ws_col ); put( "\n" );
}

void _start( void ) {
    int  ptn  = -1;
    int  zero = 0;

    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    putln( master >= 0 ? "master=ok" : "master=FAIL" );
    sys_ioctl( master, TIOCGPTN, &ptn );
    sys_ioctl( master, TIOCSPTLCK, &zero );
    build_pts_path( (long)ptn );
    long slave = sys_open( ptspath, O_RDWR, 0 );
    putln( slave >= 0 ? "slave=ok" : "slave=FAIL" );

    /* 1. init: 未設定 → fallback 25x80 */
    struct winsize w0 = {0,0,0,0};
    sys_ioctl( slave, TIOCGWINSZ, &w0 );
    put_size( "init=", &w0 );

    /* 2. master で set → slave で get (cross-fd 共有) */
    struct winsize ws = {40,120,0,0};
    sys_ioctl( master, TIOCSWINSZ, &ws );
    struct winsize w1 = {0,0,0,0};
    sys_ioctl( slave, TIOCGWINSZ, &w1 );
    put_size( "slave_get=", &w1 );

    /* 3. slave で set → master で get (逆方向も同じ ptn を指す) */
    struct winsize ws2 = {50,160,0,0};
    sys_ioctl( slave, TIOCSWINSZ, &ws2 );
    struct winsize w2 = {0,0,0,0};
    sys_ioctl( master, TIOCGWINSZ, &w2 );
    put_size( "master_get=", &w2 );

    sys_close( slave );
    sys_close( master );
    sys_exit( 0 );
}
