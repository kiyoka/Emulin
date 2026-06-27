/* sys_fcntl_getlk_64.c — fcntl(F_GETLK) が競合なしのとき l_type を F_UNLCK に
 * 書き換えることを検証する (issue #427)。
 *
 * POSIX: F_GETLK は、要求した範囲に競合する他者のロックが無ければ flock.l_type を
 * F_UNLCK に書き換える。自プロセスが F_SETLK で取得したロックは「競合しない」ので、
 * その後の F_GETLK は F_UNLCK を返す。
 *
 * emulin は単一プロセスで record lock を no-op (F_SETLK は常に成功) としている。旧
 * sys_fcntl は F_GETLK で flock を書き換えず 0 を返すだけで、l_type が入力 (F_WRLCK)
 * のまま残った。SQLite (WAL の deadman-switch probe, unixLockSharedMemory) は F_GETLK
 * 前に l_type=F_WRLCK を入れ、戻りが F_WRLCK のままだと「他プロセスが排他ロック保持」と
 * 誤認 → SQLITE_BUSY → WAL_RETRY → 100 回 retry で SQLITE_PROTOCOL(code 15)。これで
 * OpenAI Codex の state DB migration が失敗していた。修正は F_GETLK で l_type=F_UNLCK
 * を書き戻すこと (また arg は (int)dx で 32bit 切り詰め済なので書込先は full 64bit の dx)。
 *
 * 期待: F_SETLK=0 F_GETLK=0 l_type=2   (2 = F_UNLCK)
 */
#include "sys64.h"

#define F_GETLK  5
#define F_SETLK  6
#define F_WRLCK  1
#define F_UNLCK  2
#define O_RDWR   2
#define O_CREAT  0100

/* x86-64 の struct flock: l_type/l_whence(各 2byte) の後 l_start(off_t) が 8 境界に
 * 来るため 4byte padding が入る。l_type は offset 0。 */
struct flock { short l_type; short l_whence; long l_start; long l_len; int l_pid; };

void _start(void) {
    long fd = sys_open("/tmp/sys_fcntl_getlk.dat", O_RDWR | O_CREAT, 0644);
    sys_ftruncate(fd, 4096);

    struct flock fl;
    fl.l_type = F_WRLCK; fl.l_whence = 0; fl.l_start = 0; fl.l_len = 10;
    long s = sys_fcntl(fd, F_SETLK, (long)&fl);   /* 取得 (emulin は no-op success) */

    fl.l_type = F_WRLCK; fl.l_whence = 0; fl.l_start = 0; fl.l_len = 10;
    long g = sys_fcntl(fd, F_GETLK, (long)&fl);   /* 競合なし → l_type=F_UNLCK のはず */

    put("F_SETLK=");  put_dec(s);
    put(" F_GETLK="); put_dec(g);
    put(" l_type=");  put_dec(fl.l_type);
    put("\n");

    sys_close(fd);
    sys_unlink("/tmp/sys_fcntl_getlk.dat");
    sys_exit(0);
}
