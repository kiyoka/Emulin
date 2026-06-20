/* sys_pty_blockread_64.c — 別プロセス + blocking read over pty + ICRNL (実 apt 再現)
 *
 * issue #377 の深層確認: hermetic sys_pty_icrnl_64 は同一スレッドで write→read のため
 *   read が block せず、「別プロセスで blocking read 中に後から master へ書く(async
 *   wakeup)」という実 sshd→apt [Y/n] の条件を捉えていなかった。本 test は忠実に再現:
 *     parent (sshd 役): 遅延後 master に "Y\r" を書く (Enter=CR)
 *     child  (apt 役) : slave で 1 byte ずつ 2 回 blocking read する
 *   child の最初の read はデータ未着で block し、parent の write で起きる。slave 既定
 *   c_iflag は ICRNL(0x100) を含むので CR は NL に変換され、child は byte0='Y' /
 *   byte1='\n' を受ける。修正前は byte1='\r' のまま (canonical reader は行終端できず
 *   永久 block していた)。
 *
 *   hang 安全: child は「ちょうど 2 byte」read し、parent は「ちょうど 2 byte」write
 *   する (ICRNL は 1:1 で byte 数不変)。修正が壊れても byte1 が 0d になるだけで
 *   出力差異 = FAIL になり、無限 block にはならない。
 *
 * 期待 stdout:
 *   master=ok
 *   slave=ok
 *   child: byte0=Y byte1=\n nl=1
 *   parent_done
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430
#define TIOCSPTLCK 0x40045431

static char ptspath[32];
static void build_pts_path( long n ) {
    const char *p = "/dev/pts/"; int i = 0;
    while( p[i] ) { ptspath[i] = p[i]; i++; }
    char t[12]; int j = 0;
    if( n == 0 ) { t[j++] = '0'; }
    else { while( n > 0 ) { t[j++] = (char)('0' + (n % 10)); n /= 10; } }
    while( j > 0 ) { ptspath[i++] = t[--j]; }
    ptspath[i] = 0;
}

/* 1 byte を C escape ("\r", "\n") で出す。0x20-0x7e はそのまま */
static void put_byte_escaped( unsigned char c ) {
    if(      c == 0x0d ) { put( "\\r" ); }
    else if( c == 0x0a ) { put( "\\n" ); }
    else { char one[2]; one[0] = (char)c; one[1] = 0; put( one ); }
}

void _start( void ) {
    int ptn = -1, zero = 0;
    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    putln( master >= 0 ? "master=ok" : "master=FAIL" );
    sys_ioctl( master, TIOCGPTN, &ptn );
    sys_ioctl( master, TIOCSPTLCK, &zero );
    build_pts_path( (long)ptn );
    long slave = sys_open( ptspath, O_RDWR, 0 );
    putln( slave >= 0 ? "slave=ok" : "slave=FAIL" );

    long pid = sys_fork( );
    if( pid == 0 ) {
        /* child = apt 役: slave で 2 byte blocking read。最初の read は block する。 */
        sys_close( master );
        unsigned char b0 = 0, b1 = 0;
        sys_read( slave, &b0, 1 );   /* blocking; parent の write で起きる → 'Y' */
        sys_read( slave, &b1, 1 );   /* terminator: ICRNL で '\n' (修正前は '\r') */
        put( "child: byte0=" ); put_byte_escaped( b0 );
        put( " byte1=" );        put_byte_escaped( b1 );
        put( " nl=" ); put( (b1 == 0x0a) ? "1" : "0" ); putln( "" );
        sys_exit( 0 );
    }

    /* parent = sshd 役: child が block するのを待ってから master に "Y\r" を書く */
    sys_close( slave );
    struct { long s; long ns; } ts; ts.s = 0; ts.ns = 200000000L; /* 200ms */
    sys_nanosleep( &ts, 0 );
    sys_write( master, "Y\r", 2 );
    int st = 0; sys_wait4( pid, &st, 0, 0 );
    putln( "parent_done" );
    sys_close( master );
    sys_exit( 0 );
}
