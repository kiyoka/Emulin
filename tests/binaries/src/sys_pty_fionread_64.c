/* sys_pty_fionread_64.c — pty slave/master fd への ioctl(FIONREAD) を検証
 *
 * issue #223 (emulin SSH サーバ越し emacs の入力): pty 越しに emacs を動かすと
 *   画面は出るがキー入力が一切効かなかった。真因は FIONREAD (0x541B) を pty/pipe
 *   の read 端 fd に対して常に 0 byte と返していたこと。emacs は SIGIO
 *   (input_available_signal) を受けると interrupt_input 経路で keyboard fd に
 *   FIONREAD を発行し「実際に読める byte 数」を確認する。0 が返ると「select が
 *   ウソをついた」と判断して read を skip し続け、入力が pipe に届いていても
 *   永遠に読まない。fix は FIONREAD を pipe の read 端で pipe_available() を返す
 *   ようにした (SyscallAmd64 の 0x541B ハンドラに is_pipe(true) 分岐を追加)。
 *
 * 本 test は sys_pty_64.c と同じ pty 確保経路 (/dev/ptmx → /dev/pts/N) を再現し、
 *   master↔slave に byte を書いた前後で FIONREAD が正しい byte 数を返すか確認する。
 *   実 fs を一切使わない hermetic test (sandbox 不要):
 *   1. empty       : 何も書かない slave で FIONREAD → 0
 *   2. after_write : master が 5 byte 書いた後 slave で FIONREAD → 5
 *   3. after_drain : slave が 5 byte 読み切った後 FIONREAD → 0
 *   4. master_side : slave が 2 byte 書いた後 master で FIONREAD → 2
 *
 * 期待 stdout:
 *   master=ok
 *   slave=ok
 *   empty=0
 *   after_write=5
 *   after_drain=0
 *   master_side=2
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430   /* _IOR('T', 0x30, unsigned int) */
#define TIOCSPTLCK 0x40045431   /* _IOW('T', 0x31, int)          */
#define FIONREAD   0x541B       /* 読める byte 数を *addr (int) に書く */
#define TCGETS     0x5401
#define TCSETS     0x5402

/* issue #688: 既定 termios (ECHO on) だと master write のエコーが master 側
 * FIONREAD に加算され byte 数が狂う (実 Linux も同じ)。raw 化してから検証する。 */
static void pty_raw( long fd ) {
    char tio[64];
    if( sys_ioctl( fd, TCGETS, tio ) != 0 ) return;
    *(unsigned int *)(tio + 12) &= ~0x0aU;   /* c_lflag &= ~(ICANON|ECHO) */
    sys_ioctl( fd, TCSETS, tio );
}

static char ptspath[32];

/* "/dev/pts/" + decimal(n) を ptspath に組み立てる (n>=0) */
static void build_pts_path( long n ) {
    const char *pfx = "/dev/pts/";
    int i = 0;
    while( pfx[i] ) { ptspath[i] = pfx[i]; i++; }
    char tmp[12];
    int j = 0;
    if( n == 0 ) { tmp[j++] = '0'; }
    else { while( n > 0 ) { tmp[j++] = (char)('0' + (n % 10)); n /= 10; } }
    while( j > 0 ) { ptspath[i++] = tmp[--j]; }  /* 逆順に積んだので戻す */
    ptspath[i] = 0;
}

/* ioctl(fd, FIONREAD, &n) を呼んで n (=読める byte 数) を返す。失敗なら戻り値 */
static long fionread( long fd ) {
    int n = -1;
    long r = sys_ioctl( fd, FIONREAD, &n );
    return ( r == 0 ) ? (long)n : r;
}

void _start( void ) {
    int  ptn  = -1;
    int  zero = 0;
    char buf[16];

    /* pty 確保 (sys_pty_64.c と同じ経路) */
    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    putln( master >= 0 ? "master=ok" : "master=FAIL" );
    sys_ioctl( master, TIOCGPTN, &ptn );
    sys_ioctl( master, TIOCSPTLCK, &zero );
    build_pts_path( (long)ptn );
    long slave = sys_open( ptspath, O_RDWR, 0 );
    putln( slave >= 0 ? "slave=ok" : "slave=FAIL" );
    pty_raw( slave );   /* issue #688: raw 化 (エコー混入なしで byte 数を検証) */

    /* 1. empty: 何も書いていない slave で FIONREAD → 0 */
    put( "empty=" ); put_dec( fionread( slave ) ); put( "\n" );

    /* 2. after_write: master が 5 byte 書いた後 slave で FIONREAD → 5 */
    sys_write( master, "hello", 5 );
    put( "after_write=" ); put_dec( fionread( slave ) ); put( "\n" );

    /* 3. after_drain: slave が 5 byte 読み切った後 FIONREAD → 0 */
    sys_read( slave, buf, 5 );
    put( "after_drain=" ); put_dec( fionread( slave ) ); put( "\n" );

    /* 4. master_side: slave が 2 byte 書いた後 master で FIONREAD → 2 */
    sys_write( slave, "hi", 2 );
    put( "master_side=" ); put_dec( fionread( master ) ); put( "\n" );

    sys_close( slave );
    sys_close( master );
    sys_exit( 0 );
}
