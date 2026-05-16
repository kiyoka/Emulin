/* sys_chown_64.c — chown / fchown / lchown / fchownat の no-op 成功を回帰固定 (issue #9)
 *
 * 旧実装は amd64 ABI で chown 系 (92/93/94/260) が全部未実装で、ssh や
 * git checkout 等が "Unsupported amd64 syscall" で abort していた。
 * emulator は actual な ownership 変更を行わないが、syscall 呼び出し自体は
 * 成功 (0) を返すことで実機 binary がそのまま動く。
 *
 * テスト:
 *   1) sys_open で /tmp/sys_chown_test 作成、fd 取得
 *   2) sys_fchown(fd, 0, 0) → 0
 *   3) sys_chown("/tmp/sys_chown_test", 0, 0) → 0
 *   4) sys_lchown("/tmp/sys_chown_test", 0, 0) → 0
 *   5) sys_fchownat(AT_FDCWD, "/tmp/sys_chown_test", 0, 0, 0) → 0
 *
 * 期待出力:
 *   open_ok=1
 *   fchown_rc=0
 *   chown_rc=0
 *   lchown_rc=0
 *   fchownat_rc=0
 */
#include "sys64.h"

#define AT_FDCWD  -100
#define O_CREAT   0x40
#define O_WRONLY  1
#define O_TRUNC   0x200

void _start(void) {
    long fd = sys_open("/tmp/sys_chown_test", O_CREAT | O_WRONLY | O_TRUNC, 0644);
    put("open_ok=");
    put_dec(fd >= 0 ? 1 : 0);
    put("\n");
    if (fd < 0) sys_exit(1);

    long r1 = sys_fchown(fd, 0, 0);
    put("fchown_rc=");
    put_dec(r1);
    put("\n");

    long r2 = sys_chown("/tmp/sys_chown_test", 0, 0);
    put("chown_rc=");
    put_dec(r2);
    put("\n");

    long r3 = sys_lchown("/tmp/sys_chown_test", 0, 0);
    put("lchown_rc=");
    put_dec(r3);
    put("\n");

    long r4 = sys_fchownat(AT_FDCWD, "/tmp/sys_chown_test", 0, 0, 0);
    put("fchownat_rc=");
    put_dec(r4);
    put("\n");

    sys_close(fd);
    sys_unlink("/tmp/sys_chown_test");
    sys_exit(0);
}
