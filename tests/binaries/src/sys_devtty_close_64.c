/* sys_devtty_close_64.c — /dev/tty を close しても制御端末 pty が切れないことを検証
 *
 * issue #219: sshd 越し emacs で「画面は出るが入力が死ぬ」を直すため /dev/tty を
 *   制御端末 pty slave に解決した。当初 fd 0 と同一 Fileinfo を共有 (Dup) した
 *   ところ、/dev/tty fd を close すると共有 Fileinfo の close 処理が pty pipe を
 *   切断し、まだ生きている fd 0/1/2 が EOF を受けてセッションが落ちた
 *   (emacs: "Not a tty device" / ssh "closed by remote host")。
 *
 *   fix: /dev/tty を独立 Fileinfo にし duplicate_pipe で接続計数を +1 / close の
 *   disconnect -1 と対称化する。これで /dev/tty を開閉しても fd 0/1/2 の pty
 *   pipe は生きたまま。
 *
 * 本 test:
 *   1. open ptmx → master、open pts/0 → slave、dup2(slave,0)  [fd 0 = 制御端末]
 *   2. open("/dev/tty") → close()                              [問題の開閉]
 *   3. master へ "ALIVE" を書き、fd 0 (まだ開いている pty slave) から読む
 *      → pipe が生きていれば "ALIVE"、Dup バグだと close で切れて EOF
 *
 * 期待 stdout:
 *   after_devtty_close=ALIVE
 */
#include "sys64.h"

#define O_RDWR   2
#define TIOCGPTN 0x80045430

void _start( void ) {
    char buf[16];
    int  ptn = -1;

    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    sys_ioctl( master, TIOCGPTN, &ptn );
    long slave = sys_open( "/dev/pts/0", O_RDWR, 0 );
    sys_dup2( slave, 0 );                 /* fd 0 = 制御端末 pty slave */

    long tty = sys_open( "/dev/tty", O_RDWR, 0 );
    sys_close( tty );                     /* ← これで pty pipe を切ってはいけない */

    sys_write( master, "ALIVE", 5 );      /* 制御端末 pipe がまだ生きているか */
    long n = sys_read( 0, buf, 5 );       /* fd 0 (まだ開いている slave) から読む */
    buf[ n > 0 ? n : 0 ] = 0;
    put( "after_devtty_close=" );
    put( n > 0 ? buf : "<EOF>" );
    put( "\n" );

    sys_exit( 0 );
}
