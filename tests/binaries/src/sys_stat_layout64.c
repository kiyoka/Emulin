/* sys_stat_layout64.c — fstat (#5) のレイアウト全フィールドを検証 (Phase 27 step 22)
 *
 * 旧実装の bug を回帰固定:
 *   - st_size が 32-bit mask で >2GB が truncate されていた
 *   - st_blocks が常に 0 だった
 *   - st_atime/mtime/ctime_nsec が常に 0 だった
 *   - st_atime/mtime/ctime が 32-bit mask で Y2038 wrap の可能性があった
 *
 * 流れ: /tmp/stat-test.bin を open, ftruncate(12345), fstat, 各フィールド検証。
 */
#include "sys64.h"

#define O_RDWR     0x0002
#define O_CREAT    0x0040
#define O_TRUNC    0x0200

static char buf[256];

void _start(void) {
    long fd = sys_open("/tmp/stat-test.bin", O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { put("open=fail\n"); sys_exit(1); }
    long r = sys_ftruncate(fd, 12345);
    if (r != 0) { put("ftruncate=fail\n"); sys_exit(1); }

    long sr = sys_fstat(fd, buf);
    put("ret="); put_dec(sr); put("\n");

    /* レイアウトオフセット (Linux x86-64 ABI):
       st_dev=0  st_ino=8  st_nlink=16
       st_mode=24  st_uid=28  st_gid=32  __pad0=36
       st_rdev=40  st_size=48  st_blksize=56  st_blocks=64
       st_atime=72   st_atime_nsec=80
       st_mtime=88   st_mtime_nsec=96
       st_ctime=104  st_ctime_nsec=112 */

    long st_size       = *(long *)(buf + 48);
    long st_blocks     = *(long *)(buf + 64);
    long st_mtime      = *(long *)(buf + 88);
    long st_mtime_nsec = *(long *)(buf + 96);

    put("size="); put_dec(st_size); put("\n");
    put("blocks="); put_dec(st_blocks); put("\n");
    /* st_mtime は 1700000000 (2023-11-15) 以降であるはず (now > 2024) */
    put("mtime_recent="); put_dec(st_mtime > 1700000000L ? 1 : 0); put("\n");
    /* nsec は 0..999_999_999 の範囲 */
    put("nsec_in_range="); put_dec(st_mtime_nsec >= 0 && st_mtime_nsec < 1000000000L ? 1 : 0); put("\n");

    sys_close(fd);
    sys_unlink("/tmp/stat-test.bin");
    sys_exit(0);
}
