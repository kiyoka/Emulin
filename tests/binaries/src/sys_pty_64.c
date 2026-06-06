/* sys_pty_64.c — openpty(3) が内部で叩く /dev/ptmx → /dev/pts/N の経路を生で検証
 *
 * issue #219 (emulin の SSH サーバ化): sshd 越しに emacs/対話 bash を使うには
 *   pty 確保が要る。glibc openpty(3) は posix_openpt(/dev/ptmx) → grantpt →
 *   unlockpt(TIOCSPTLCK) → ptsname(TIOCGPTN) → open(/dev/pts/N) の syscall 列で
 *   master/slave を取る。これが Windows host でだけ EPERM
 *   ("openpty: Operation not permitted") で失敗していた。
 *
 *   真因 (PR fix-issue219-pty-windows-eperm): Fileinfo.open() が virtual fd 名
 *   <pty-master> / <pty-slave> を特別扱いせず new RandomAccessFile("<pty-master>",
 *   "rw") を走らせていた。`<` `>` は NTFS の予約文字なので Windows では
 *   FileNotFoundException → FileOpen=-1 → open_resolved が -1(=-EPERM) を返す。
 *   Linux では `<` `>` が合法で junk file を作って偶然成功していた (= Windows
 *   のみ失敗の切り分けと一致)。fix は <pty-master>/<pty-slave> を <pipe> 等と
 *   同じ early-return 扱いにし、実 file を触らないようにする。
 *
 * 本 test はその syscall 列を再現し、さらに master↔slave の双方向 byte 往復で
 *   pipe pair の結線まで確認する。実 fs を一切使わない (sandbox 不要) hermetic:
 *   1. open("/dev/ptmx", O_RDWR)         → master fd (>=0)
 *   2. ioctl(master, TIOCGPTN, &ptn)     → slave 番号 (process 先頭なら 0)
 *   3. ioctl(master, TIOCSPTLCK, &zero)  → unlockpt (0)
 *   4. open("/dev/pts/<ptn>", O_RDWR)    → slave fd (>=0)
 *   5. master→slave / slave→master の byte 往復
 *
 * 期待 stdout:
 *   master=ok
 *   ptn=0
 *   unlock=0
 *   slave=ok
 *   m2s=Mtos!
 *   s2m=Stom?
 */
#include "sys64.h"

#define O_RDWR     2
#define TIOCGPTN   0x80045430   /* _IOR('T', 0x30, unsigned int) */
#define TIOCSPTLCK 0x40045431   /* _IOW('T', 0x31, int)          */

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

void _start( void ) {
    int  ptn  = -1;
    int  zero = 0;
    char buf[16];

    /* 1. master = open("/dev/ptmx") */
    long master = sys_open( "/dev/ptmx", O_RDWR, 0 );
    putln( master >= 0 ? "master=ok" : "master=FAIL" );

    /* 2. TIOCGPTN で slave 番号を取得 */
    long r = sys_ioctl( master, TIOCGPTN, &ptn );
    put( "ptn=" ); put_dec( r == 0 ? (long)ptn : r ); put( "\n" );

    /* 3. TIOCSPTLCK (unlockpt) */
    r = sys_ioctl( master, TIOCSPTLCK, &zero );
    put( "unlock=" ); put_dec( r ); put( "\n" );

    /* 4. slave = open("/dev/pts/<ptn>") */
    build_pts_path( (long)ptn );
    long slave = sys_open( ptspath, O_RDWR, 0 );
    putln( slave >= 0 ? "slave=ok" : "slave=FAIL" );

    /* 5a. master → slave */
    sys_write( master, "Mtos!", 5 );
    long n1 = sys_read( slave, buf, 5 );
    buf[ n1 > 0 ? n1 : 0 ] = 0;
    put( "m2s=" ); put( buf ); put( "\n" );

    /* 5b. slave → master */
    sys_write( slave, "Stom?", 5 );
    long n2 = sys_read( master, buf, 5 );
    buf[ n2 > 0 ? n2 : 0 ] = 0;
    put( "s2m=" ); put( buf ); put( "\n" );

    sys_close( slave );
    sys_close( master );
    sys_exit( 0 );
}
