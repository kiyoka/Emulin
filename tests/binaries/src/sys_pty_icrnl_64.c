/* sys_pty_icrnl_64.c — pty master への write を slave 側 read で ICRNL が CR→NL 変換するか
 *
 * issue #377 (対話 sshd の apt [Y/n] に応答できない): emulin の pty は出力側
 *   line discipline (OPOST/ONLCR, #229) は実装済だが入力側 (ICRNL 等) が未実装で、
 *   termios に保存されるだけで作用しなかった。SSH client は Enter を CR(0x0d) で
 *   送るが、apt 等の canonical-mode reader は libc の getline が NL(0x0a) でしか
 *   行終端しないため、CR→NL 変換が無いと [Y/n] 確認が永久にハングしていた。
 *   fix は FileAccess.FileRead の pty slave 経路で c_iflag に ICRNL が立っている
 *   とき、master→slave に流れた CR を NL に変換する。raw mode app (vim/emacs) は
 *   tcsetattr で ICRNL off を立てるので素通り。出力側 ONLCR (sys_pty_onlcr_64) の
 *   対称テスト。
 *
 * 方向: SSH client → sshd が master fd に write、app は slave fd から read。
 *   よって本 test は master に書いて slave から読む (onlcr は slave→master の逆)。
 *
 * 本 test は実 fs 一切無しの hermetic 構成 (sandbox 不要):
 *   1. cooked    : 既定 (ICRNL on) で master に "Y\r" を書き、slave 側で "Y\n"
 *                  (CR が NL に変換) を読めるか
 *   2. icrnl_off : tcsetattr で slave の c_iflag から ICRNL を落とした後、master に
 *                  "Y\r" を書くと slave 側で "Y\r" (変換無し) を読めるか
 *   3. multi     : ICRNL を戻して "a\rb\r" → slave 側で "a\nb\n" (2 個の CR が
 *                  それぞれ NL に独立変換) を読めるか
 *
 * 期待 stdout:
 *   master=ok
 *   slave=ok
 *   cooked_len=2
 *   cooked=Y\n
 *   icrnl_off_len=2
 *   icrnl_off=Y\r
 *   multi_len=4
 *   multi=a\nb\n
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430
#define TIOCSPTLCK 0x40045431
#define TCGETS     0x5401
#define TCSETS     0x5402
#define ICRNL      0x100

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

/* tio[0..3] を 32-bit little-endian で取り出す / 書き戻す (c_iflag) */
static unsigned int read_iflag( void ) {
    return  (unsigned int)tio[0]
         | ((unsigned int)tio[1] << 8)
         | ((unsigned int)tio[2] << 16)
         | ((unsigned int)tio[3] << 24);
}
static void write_iflag( unsigned int v ) {
    tio[0] = (unsigned char)(v       & 0xff);
    tio[1] = (unsigned char)((v >>  8) & 0xff);
    tio[2] = (unsigned char)((v >> 16) & 0xff);
    tio[3] = (unsigned char)((v >> 24) & 0xff);
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

    /* 1. cooked: 既定 (ICRNL on) で master に "Y\r" → slave 側で "Y\n" */
    sys_write( master, "Y\r", 2 );
    long n1 = sys_read( slave, buf, sizeof(buf) );
    put( "cooked_len=" ); put_dec( n1 ); put( "\n" );
    put( "cooked=" ); put_escaped( buf, n1 ); put( "\n" );

    /* 2. icrnl_off: tcsetattr で slave の ICRNL を落としたら CR は変換されず素通り */
    sys_ioctl( slave, TCGETS, tio );
    write_iflag( read_iflag() & ~(unsigned int)ICRNL );
    sys_ioctl( slave, TCSETS, tio );
    sys_write( master, "Y\r", 2 );
    long n2 = sys_read( slave, buf, sizeof(buf) );
    put( "icrnl_off_len=" ); put_dec( n2 ); put( "\n" );
    put( "icrnl_off=" ); put_escaped( buf, n2 ); put( "\n" );

    /* 3. multi: ICRNL を戻して "a\rb\r" → slave 側で "a\nb\n" */
    write_iflag( read_iflag() | ICRNL );
    sys_ioctl( slave, TCSETS, tio );
    sys_write( master, "a\rb\r", 4 );
    long n3 = sys_read( slave, buf, sizeof(buf) );
    put( "multi_len=" ); put_dec( n3 ); put( "\n" );
    put( "multi=" ); put_escaped( buf, n3 ); put( "\n" );

    sys_close( slave );
    sys_close( master );
    sys_exit( 0 );
}
