/* sys_devtty_ctty_64.c — /dev/tty が「制御端末」の pty slave に解決されるか検証
 *
 * issue #219: emulin を SSH サーバ化して sshd 越しに emacs を使うと、emacs の
 *   画面が SSH client ではなく emulin launcher の console (= サーバ側) に描画
 *   される不具合があった。bash は fd 0/1/2 (= sshd が dup2 した pty slave) を
 *   直接使うので client に出るが、emacs は制御端末 `/dev/tty` を open して
 *   描画するため、emulin が `/dev/tty` を無条件で `<std>` (global console) に
 *   route していたのが原因。
 *
 *   fix: プロセスの fd 0/1/2 が pty slave なら、それを制御端末とみなして
 *   `/dev/tty` をその pty slave に解決する (open_resolved / controlling_pty_ptn)。
 *
 * 本 test は sshd セッションの子の状況を再現する:
 *   1. open("/dev/ptmx")          → master fd
 *   2. open("/dev/pts/0")         → slave fd
 *   3. dup2(slave, 0)             → fd 0 = pty slave (= 制御端末を持つ状況)
 *   4. open("/dev/tty")           → fix 後は同じ pty slave に解決される
 *   5. master へ書いた byte を /dev/tty(=制御 pty slave) が読めれば解決成功
 *      (修正前は /dev/tty=<std>(console) なので読めず、harness の /dev/null
 *       stdin から EOF が返り tty_read=<none> になる)
 *
 * 期待 stdout:
 *   tty_open=ok
 *   tty_read=FROM-MASTER
 */
#include "sys64.h"

#define O_RDWR   2
#define TIOCGPTN 0x80045430

void _start( void ) {
    char buf[32];
    int  ptn = -1;

    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    sys_ioctl( master, TIOCGPTN, &ptn );           /* ptn=0 (process 先頭の ptmx) */
    long slave  = sys_open( "/dev/pts/0", O_RDWR, 0 );
    sys_dup2( slave, 0 );                           /* fd 0 を pty slave に */

    long tty = sys_open( "/dev/tty", O_RDWR, 0 );
    putln( tty >= 0 ? "tty_open=ok" : "tty_open=FAIL" );

    /* master が書いた bytes を /dev/tty(= 制御 pty slave) が読めれば解決成功 */
    sys_write( master, "FROM-MASTER", 11 );
    long n = sys_read( tty, buf, 11 );
    buf[ n > 0 ? n : 0 ] = 0;
    put( "tty_read=" ); put( n > 0 ? buf : "<none>" ); put( "\n" );

    sys_exit( 0 );
}
