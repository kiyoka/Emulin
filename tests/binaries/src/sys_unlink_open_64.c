/* sys_unlink_open_64.c — open 中の file を unlink できること (POSIX セマンティクス)
 *
 * issue #355: Linux は open 中の file の unlink を許す (dir から外れ、fd は close
 *   まで有効、内容も読める)。Windows NTFS は open handle が FILE_SHARE_DELETE 無しだと
 *   削除を ERROR_SHARING_VIOLATION で拒否し、emulin の unlink が EPERM を返していた
 *   (dpkg --unpack が open 中の trigger file を unlink できず systemd 等の install が
 *   失敗)。
 *
 *   検証: file を open したまま unlink → ret=0、その後も open fd で read/write でき、
 *   path は消えている (再 open で ENOENT)。
 */
#include "sys64.h"

#define O_RDWR   2
#define O_CREAT  0100
#define O_RDONLY 0

void _start(void) {
    const char *path = "/tmp/unlink_open_test_355";

    long fd = sys_open( path, O_RDWR | O_CREAT, 0644 );
    put( "open_fd_ge0=" );
    put_dec( fd >= 0 ? 1 : 0 );
    put( "\n" );

    sys_write( fd, "ABCD", 4 );

    /* open 中に unlink (Linux はここで成功する) */
    long r = sys_unlink( path );
    put( "unlink_ret=" );
    put_dec( r );                 /* 0 = 成功 (期待)。負 = EPERM 等 (#355 のバグ) */
    put( "\n" );

    /* unlink 後も open fd で内容を読めること (POSIX: fd は生きている) */
    char buf[8];
    sys_lseek( fd, 0, O_RDONLY );
    long n = sys_read( fd, buf, 4 );
    put( "read_after_unlink=" );
    put_dec( n );
    if( n == 4 ) { put( " bytes=" ); sys_write( 1, buf, 4 ); }
    put( "\n" );

    sys_close( fd );

    /* path はもう存在しない → 再 open は ENOENT(-2) */
    long fd2 = sys_open( path, O_RDONLY, 0 );
    put( "reopen_ret=" );
    put_dec( fd2 );               /* -2 = ENOENT (期待: 消えている) */
    put( "\n" );
    if( fd2 >= 0 ) { sys_close( fd2 ); sys_unlink( path ); }

    sys_exit( 0 );
}
