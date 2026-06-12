/* sys_pty_winch_64.c — pty の TIOCSWINSZ が foreground process group へ SIGWINCH
 *   を配信することを検証 (issue #225)
 *
 * SSH client がリサイズすると sshd が pty master に TIOCSWINSZ する。emulin は
 *   その pty の foreground process group (TIOCSPGRP で設定) へ SIGWINCH を配信し、
 *   emacs/vim 等が再描画する。この「TIOCSWINSZ → SIGWINCH 配信」を hermetic に検証。
 *
 * 構成 (master を持つ親と、slave を持つ子の 2 プロセス。親=pid1 は broadcast/
 *   pgrp-kill 対象外なので、配信を受ける側は fork した子にする):
 *   - 親: /dev/ptmx を open (master)。fork。
 *   - 子: /dev/pts/0 を open (slave)。setpgid(0,0) で独立 pgrp leader になり、
 *         TIOCSPGRP で pty の foreground pgrp を自分にする。SIGWINCH ハンドラを
 *         登録し、準備完了を slave 書込で親へ通知。winch_fired を待つループ。
 *         配信されたら exit(7)、来なければ exit(8)。
 *   - 親: master から準備完了 byte を読む → master に TIOCSWINSZ(40x120) →
 *         emulin が kill(-child_pgrp, SIGWINCH) → 子のハンドラ発火。
 *         wait4 で子の exit code を取り、7 なら配信成功。
 *
 * 期待 stdout:
 *   winch_delivered=1
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430
#define TIOCSPTLCK 0x40045431
#define TIOCSWINSZ 0x5414
#define TIOCSPGRP  0x5410
#define SIGWINCH   28

struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; };
struct ktimespec { long tv_sec, tv_nsec; };
struct kernel_sigaction { long handler, flags, restorer, mask; };

/* setpgid(pid, pgid) = syscall #109 */
static long sys_setpgid( long pid, long pgid ) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(109LL), "D"(pid), "S"(pgid) : "rcx", "r11", "memory");
    return ret;
}

static volatile int winch_fired = 0;
static void on_winch( int sig ) { (void)sig; winch_fired = 1; }

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

void _start( void ) {
    int  ptn  = -1;
    int  zero = 0;

    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    sys_ioctl( master, TIOCGPTN, &ptn );
    sys_ioctl( master, TIOCSPTLCK, &zero );
    build_pts_path( (long)ptn );

    long pid = sys_fork();
    if( pid == 0 ) {
        /* ---- child: slave 側で SIGWINCH を待つ ---- */
        long slave = sys_open( ptspath, O_RDWR, 0 );
        sys_setpgid( 0, 0 );                 /* 独立 pgrp leader (pgrp = 自 pid) */
        int mypg = (int)sys_getpid();
        sys_ioctl( slave, TIOCSPGRP, &mypg ); /* pty fg pgrp = 自分 */
        /* aggregate-init ({0}) は SSE の movaps (16-byte aligned store) を生成し、
         * -nostdlib の _start が RSP を 16-align しないため native backend (実 CPU)
         * では #GP で triple fault する。明示的な field 代入で movq (アラインメント
         * 不要) を強制する。 */
        struct kernel_sigaction sa;
        sa.handler  = (long)on_winch;
        sa.flags    = 0;
        sa.restorer = 0;
        sa.mask     = 0;
        sys_rt_sigaction( SIGWINCH, &sa, 0, 8 );
        sys_write( slave, "R", 1 );          /* 準備完了を親へ通知 */
        struct ktimespec ts = { 0, 25000000 };  /* 25ms */
        for( int i = 0; i < 200 && !winch_fired; i++ ) sys_nanosleep( &ts, 0 );
        sys_exit( winch_fired ? 7 : 8 );
    }

    /* ---- parent: master 側からリサイズを発行 ---- */
    char buf[4];
    sys_read( master, buf, 1 );              /* 子の準備完了を待つ */
    struct ktimespec ts = { 0, 100000000 };  /* 100ms: 子が待機ループに入るのを待つ */
    sys_nanosleep( &ts, 0 );
    struct winsize ws = { 40, 120, 0, 0 };
    sys_ioctl( master, TIOCSWINSZ, &ws );    /* → kill(-child_pgrp, SIGWINCH) */
    int status = 0;
    sys_wait4( pid, &status, 0, 0 );
    int code = ( status >> 8 ) & 0xff;       /* WEXITSTATUS */
    put( "winch_delivered=" ); put_dec( code == 7 ? 1 : 0 ); put( "\n" );
    sys_exit( 0 );
}
