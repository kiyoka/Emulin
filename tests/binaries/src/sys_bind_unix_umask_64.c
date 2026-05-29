/* sys_bind_unix_umask_64.c — AF_UNIX bind が umask を反映した mode で socket を作る検証
 *
 * issue #131 (tmux): tmux client は server socket の mode を check し、
 * group/other bit が立っていれば "access not allowed" で拒否する
 * (tmux 3.4 server.c L396)。tmux server は `umask(S_IXUSR|S_IRWXG|S_IRWXO)`
 * (=0177) で 0600 を要求するが、emulin の旧 amd64_bind は Java の
 * ServerSocketChannel.bind を呼ぶだけで、host JVM の umask (通常 022) で
 * socket が 0644 で作られていた。
 *
 * 修正: bind 後に process.umask を反映して `Files.setPosixFilePermissions`
 * で 0666 & ~umask を適用する。
 *
 * シナリオ:
 *   1. umask(0177) を設定
 *   2. AF_UNIX SOCK_STREAM socket を bind
 *   3. stat で socket file の mode 下位 9bit を取得
 *   4. 0600 (= 十進 384) であることを確認
 */
#include "sys64.h"

void _start(void) {
    /* 既存の stale socket を削除 */
    sys_unlink("/tmp/sys_bind_unix_umask.sock");

    long old_mask = sys_umask(0177);  /* 0600 を要求 */
    put("old_umask="); put_dec(old_mask); put("\n");  /* 期待: 18 (= 022 oct, default) */

    long sock = sys_socket(1, 1, 0);  /* AF_UNIX=1, SOCK_STREAM=1 */
    put("socket="); put_dec(sock); put("\n");

    /* struct sockaddr_un: family(2) + path */
    char addr[112];
    addr[0] = 1; addr[1] = 0;
    const char *p = "/tmp/sys_bind_unix_umask.sock";
    int i = 0;
    for (; p[i] != 0; i++) addr[2 + i] = p[i];
    addr[2 + i] = 0;
    long br = sys_bind(sock, addr, 2 + i + 1);
    put("bind="); put_dec(br); put("\n");

    /* stat — buf 144 byte 確保 */
    char buf[144];
    for (i = 0; i < 144; i++) buf[i] = 0;
    long sr = sys_stat("/tmp/sys_bind_unix_umask.sock", buf);
    put("stat="); put_dec(sr); put("\n");

    /* st_mode は struct stat の offset 24 (4 byte int) */
    int mode = *(int *)(buf + 24);
    int perm = mode & 0777;
    put("mode_perm="); put_dec(perm); put("\n");  /* 期待: 384 (= 0600) */

    sys_close(sock);
    sys_unlink("/tmp/sys_bind_unix_umask.sock");

    sys_exit(0);
}
