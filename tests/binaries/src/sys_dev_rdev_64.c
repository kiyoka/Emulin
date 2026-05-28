/* sys_dev_rdev_64.c — fstat() の st_rdev (major:minor) が Linux 規定値か検証
 *
 * tmux の daemonize は open("/dev/null", O_RDWR) → fstat → S_ISCHR(mode) を
 * 確認した後、更に st_rdev で「本物の /dev/null か」検証する。emulin が
 * st_rdev に pty 用の固定値 0x302 を返していた間は tmux server が exit 1
 * (PR #140 の S_IFCHR 通過後の次レイヤー)。
 *
 * Linux glibc gnu_dev_makedev(major, minor) ≈ (major << 8) | (minor & 0xFF)
 * (低位 8 bit minor + 12 bit major、低 minor では shift のみ):
 *   /dev/null      (1,3) → 0x103
 *   /dev/zero      (1,5) → 0x105
 *   /dev/urandom   (1,9) → 0x109
 */
#include "sys64.h"

#define O_RDWR 2
#define S_IFMT  0170000
#define S_IFCHR 0020000

/* AMD64 struct stat layout: st_mode @24, st_rdev @40
 *   off  0: st_dev (8)
 *   off  8: st_ino (8)
 *   off 16: st_nlink (8)
 *   off 24: st_mode (4) + st_uid (4)
 *   off 32: st_gid (4) + __pad0 (4)
 *   off 40: st_rdev (8)
 *   off 48: st_size (8) ...
 */
#define ST_MODE_OFF  24
#define ST_RDEV_OFF  40

static long fstat_and_print( const char *path, const char *label ) {
    long buf[20];   /* 144 byte (= 18*8) + 余裕 */
    long fd = sys_open( path, O_RDWR, 0 );
    if( fd < 0 ) { put(label); put(": open<0\n"); return fd; }
    long r = sys_fstat( fd, buf );
    if( r != 0 ) { put(label); put(": fstat<0\n"); sys_close(fd); return r; }

    /* st_mode は 32-bit at offset 24 — buf[3] の低 32 bit */
    unsigned int mode = (unsigned int)( buf[ST_MODE_OFF / 8] & 0xFFFFFFFFL );
    long rdev = buf[ST_RDEV_OFF / 8];
    int is_chr = ((mode & S_IFMT) == S_IFCHR);
    put(label); put(": S_IFCHR="); put_dec(is_chr); put(" st_rdev=0x");
    /* hex 16 bit 表示 (rdev は 12-bit major + 20-bit minor だが test 用は低 16 bit で十分) */
    const char *hex = "0123456789abcdef";
    char hb[5]; hb[4] = 0;
    for( int i = 0; i < 4; i++ ) hb[3-i] = hex[ (rdev >> (i*4)) & 0xF ];
    put(hb); put("\n");
    sys_close(fd);
    return 0;
}

void _start(void) {
    /* emulin sandbox は /dev/null と /dev/urandom を device marker で routing
     * している (<null>/<urandom>)。/dev/zero は sandbox に実 file が無いと
     * 開けないため本 test では対象外 (本 fix は st_rdev に限った検証)。 */
    fstat_and_print( "/dev/null",    "null"    );
    fstat_and_print( "/dev/urandom", "urandom" );
    sys_exit(0);
}
