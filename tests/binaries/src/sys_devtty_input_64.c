/* sys_devtty_input_64.c — 制御端末 /dev/tty 経由の「入力」が forked child に届くか検証
 *
 * issue #219: sshd 越しに emacs を動かすと、画面は client に出る (出力 fix 済) のに
 *   キー入力が一切届かない不具合があった。emacs は制御端末 /dev/tty を open して
 *   そこから入力を poll/read する。/dev/tty を解決する際に「新規 Fileinfo」を割り
 *   当てると pipe の接続計数 (i_connected/o_connected) が fd 0 と割れ、poll の
 *   is_pipe_connected 判定や入力経路が一致せず入力が届かなかった。fix は
 *   /dev/tty を制御 pty slave fd の dup にして同一 Fileinfo を共有させる。
 *
 * 本 test は sshd セッション (master=sshd / slave 側で emacs が fork されて動く)
 *   を最小再現する:
 *   parent (= sshd 役): /dev/ptmx を master に持ち、client の typing を模して
 *     master へ "abc" を書き、child の echo を master から読む。
 *   child  (= emacs 役): fork 継承した pty slave (fd 0/1/2) を持ち、制御端末
 *     /dev/tty を open。poll(/dev/tty, POLLIN) → read → 受け取った入力を
 *     "GOT:" 付きで /dev/tty(=master) へ echo back。
 *
 * 入力が正しく届けば parent は "GOT:abc" を受け取る。届かなければ "GOT:none"
 *   (poll が読めると言わず read が空) になる。
 *
 * 期待 stdout:
 *   master_recv=GOT:abc
 */
#include "sys64.h"

#define O_RDWR   2
#define TIOCGPTN 0x80045430
#define POLLIN   0x1

static long sys_dup2( long oldfd, long newfd ) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(33LL), "D"(oldfd), "S"(newfd) : "rcx", "r11");
    return ret;
}

void _start( void ) {
    char buf[32];
    int  ptn = -1;

    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    sys_ioctl( master, TIOCGPTN, &ptn );
    long slave = sys_open( "/dev/pts/0", O_RDWR, 0 );
    sys_dup2( slave, 0 );   /* fd 0 のみ pty slave に (fd 1/2 は結果出力用に残す) */

    long pid = sys_fork( );
    if( pid == 0 ) {
        /* child = emacs 役: 制御端末 /dev/tty から poll+read して echo */
        long tty = sys_open( "/dev/tty", O_RDWR, 0 );
        /* pollfd { int fd; short events; short revents; } を 8byte に詰める */
        long pfd = ((long)tty & 0xFFFFFFFFL) | ((long)POLLIN << 32);
        sys_poll( &pfd, 1, 3000 );                 /* 入力到着まで最大 3s 待つ */
        long n = sys_read( tty, buf + 4, 16 );      /* "GOT:" の後ろに入力を置く */
        buf[0]='G'; buf[1]='O'; buf[2]='T'; buf[3]=':';
        if( n > 0 ) { sys_write( tty, buf, 4 + n ); }   /* /dev/tty → master へ */
        else        { sys_write( tty, "GOT:none", 8 ); }
        sys_exit( 0 );
    }

    /* parent = sshd 役: child が poll に入るのを少し待ってから "abc" を流す */
    long ts[2]; ts[0]=0; ts[1]=300000000;           /* 300ms */
    sys_nanosleep( ts, 0 );
    sys_write( master, "abc", 3 );                   /* client typing 相当 */
    long n = sys_read( master, buf, 16 );            /* child の echo を回収 */
    buf[ n > 0 ? n : 0 ] = 0;
    put( "master_recv=" ); put( n > 0 ? buf : "<none>" ); put( "\n" );
    sys_exit( 0 );
}
