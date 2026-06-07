/* sys_pty_onlcr_64.c — pty slave への write で OPOST/ONLCR が \n→\r\n 展開するか
 *
 * issue #229 (sshd 越し Tera Term で ls 出力の改行崩れ): emulin の pty slave が
 *   line discipline の出力 post-processing (OPOST + ONLCR) を未実装で、
 *   bash/ls 等の \n がそのまま wire に流れ、SSH client が CR 無しの LF を
 *   「下行同列」と解釈して階段崩れ (例: "lsSumibi  ..." 連結) になっていた。
 *   fix は FileAccess.FileWrite の pty slave 経路で c_oflag に OPOST|ONLCR が
 *   立っているとき \n を \r\n に展開する。raw mode app (emacs/vim) は
 *   tcsetattr で OPOST off を立てるので素通り。
 *
 * 本 test は実 fs 一切無しの hermetic 構成 (sandbox 不要):
 *   1. cooked  : 既定 (OPOST|ONLCR on) で slave に "ls\n" を書き、master 側で
 *                "ls\r\n" (3 byte の \n が 4 byte に展開) を読めるか
 *   2. opost_off : tcsetattr で c_oflag から OPOST を落とした後、slave に "ab\n"
 *                を書くと master 側で "ab\n" (展開無し) を読めるか
 *   3. multi   : 複数行 "a\nb\n" → master 側で "a\r\nb\r\n" (2 個の \n が
 *                それぞれ \r\n に独立展開) を読めるか
 *
 * 期待 stdout:
 *   master=ok
 *   slave=ok
 *   cooked_len=4
 *   cooked=ls\r\n
 *   opost_off_len=3
 *   opost_off=ab\n
 *   multi_len=6
 *   multi=a\r\nb\r\n
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430
#define TIOCSPTLCK 0x40045431
#define TCGETS     0x5401
#define TCSETS     0x5402
#define OPOST      0x01
#define ONLCR      0x04

/* termios layout (amd64 Linux 同等)
 *   0  : c_iflag  (4)
 *   4  : c_oflag  (4)
 *   8  : c_cflag  (4)
 *   12 : c_lflag  (4)
 *   16 : c_line   (1)
 *   17 : c_cc[19] (19)
 *   total 36 byte
 */
static unsigned char tio[64];

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

/* tio[4..7] を 32-bit little-endian で取り出す / 書き戻す */
static unsigned int read_oflag( void ) {
    return  (unsigned int)tio[4]
         | ((unsigned int)tio[5] << 8)
         | ((unsigned int)tio[6] << 16)
         | ((unsigned int)tio[7] << 24);
}
static void write_oflag( unsigned int v ) {
    tio[4] = (unsigned char)(v       & 0xff);
    tio[5] = (unsigned char)((v >>  8) & 0xff);
    tio[6] = (unsigned char)((v >> 16) & 0xff);
    tio[7] = (unsigned char)((v >> 24) & 0xff);
}

/* byte 列を C escape ("\r", "\n") で stdout に出す。0x20-0x7e はそのまま */
static void put_escaped( const unsigned char *p, long n ) {
    for( long i = 0; i < n; i++ ) {
        unsigned char c = p[i];
        if(      c == 0x0d ) { put( "\\r" ); }
        else if( c == 0x0a ) { put( "\\n" ); }
        else {
            char one[2]; one[0] = (char)c; one[1] = 0; put( one );
        }
    }
}

void _start( void ) {
    int  ptn  = -1;
    int  zero = 0;
    unsigned char buf[16];

    /* pty 確保 */
    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    putln( master >= 0 ? "master=ok" : "master=FAIL" );
    sys_ioctl( master, TIOCGPTN, &ptn );
    sys_ioctl( master, TIOCSPTLCK, &zero );
    build_pts_path( (long)ptn );
    long slave = sys_open( ptspath, O_RDWR, 0 );
    putln( slave >= 0 ? "slave=ok" : "slave=FAIL" );

    /* 1. cooked: 既定 (OPOST|ONLCR on) で slave に "ls\n" → master 側で "ls\r\n" */
    sys_write( slave, "ls\n", 3 );
    long n1 = sys_read( master, buf, sizeof(buf) );
    put( "cooked_len=" ); put_dec( n1 ); put( "\n" );
    put( "cooked=" ); put_escaped( buf, n1 ); put( "\n" );

    /* 2. opost_off: tcsetattr で OPOST を落としたら \n は展開されず素通り */
    sys_ioctl( slave, TCGETS, tio );
    write_oflag( read_oflag() & ~(unsigned int)OPOST );
    sys_ioctl( slave, TCSETS, tio );
    sys_write( slave, "ab\n", 3 );
    long n2 = sys_read( master, buf, sizeof(buf) );
    put( "opost_off_len=" ); put_dec( n2 ); put( "\n" );
    put( "opost_off=" ); put_escaped( buf, n2 ); put( "\n" );

    /* 3. multi: OPOST|ONLCR を戻して "a\nb\n" → master 側で "a\r\nb\r\n" */
    write_oflag( read_oflag() | OPOST | ONLCR );
    sys_ioctl( slave, TCSETS, tio );
    sys_write( slave, "a\nb\n", 4 );
    long n3 = sys_read( master, buf, sizeof(buf) );
    put( "multi_len=" ); put_dec( n3 ); put( "\n" );
    put( "multi=" ); put_escaped( buf, n3 ); put( "\n" );

    sys_close( slave );
    sys_close( master );
    sys_exit( 0 );
}
