/* sys_grep_devnull_ino_64.c — console(stdout) と /dev/null の st_ino が異なるか検証 (issue #375)
 *
 * GNU grep は「stdout が /dev/null なら出力しても無駄」最適化を持ち、
 *   S_ISCHR(fstat(1)) かつ SAME_INODE(fstat(1), stat("/dev/null"))
 * (= st_dev と st_ino が一致) のときマッチ行の出力を抑制する。
 * emulin が console(STD fd) と /dev/null に同一の (st_dev, st_ino) を返すと、
 * grep が「stdout = /dev/null」と誤認し `echo aaa | grep aaa` が 0 件に化ける。
 * 実 Linux でも /dev/null と tty は別 inode。よって両者の st_ino は異なるべき。
 *
 * AMD64 struct stat: st_dev @0, st_ino @8 (それぞれ 8 byte)。
 */
#include "sys64.h"

#define O_RDWR 2

void _start(void) {
    long cbuf[20], nbuf[20];

    /* fd 1 (stdout = console) の fstat */
    if( sys_fstat( 1, cbuf ) != 0 ) { put("FSTAT1_FAIL\n"); sys_exit(1); }

    /* /dev/null の stat */
    long fd = sys_open( "/dev/null", O_RDWR, 0 );
    if( fd < 0 ) { put("NULL_OPEN_FAIL\n"); sys_exit(1); }
    if( sys_fstat( fd, nbuf ) != 0 ) { put("FSTATN_FAIL\n"); sys_close(fd); sys_exit(1); }
    sys_close(fd);

    long c_dev = cbuf[0], c_ino = cbuf[1];   /* console */
    long n_dev = nbuf[0], n_ino = nbuf[1];   /* /dev/null */

    /* grep の SAME_INODE 判定 = (dev 一致 かつ ino 一致)。これが真だと出力抑制。 */
    int same_inode = ( c_dev == n_dev && c_ino == n_ino );
    put("console_eq_devnull="); put_dec(same_inode); put("\n");  /* 0 であるべき */
    put("ino_differs=");        put_dec( c_ino != n_ino ); put("\n");  /* 1 であるべき */
    sys_exit(0);
}
