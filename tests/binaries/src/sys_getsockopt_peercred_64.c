/* sys_getsockopt_peercred_64.c — SO_PEERCRED で AF_UNIX peer の uid/gid を取得
 *
 * issue #131 (tmux): tmux server は `server_acl_join` で `getpeereid()` 経由で
 * peer uid を取得し、ACL list (= server 起動時の `getuid()` のみ) と比較する。
 * 一致しないと "access not allowed" で client を弾く。getpeereid は Linux libc
 * 上では `getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &ucred, &len)` を呼び出す。
 *
 * 旧実装: amd64_getsockopt は SO_TYPE 以外を「全部 0 返却 + len=4」で stub
 *   → peer uid=0 だが optlen=4 で getpeereid が "len < sizeof(ucred)" を見て fail
 *   → uid=(uid_t)-1 → ACL non-match → access denied
 *
 * 修正: SO_PEERCRED で struct ucred (pid, uid, gid: 各 4 byte = 12 byte) を返す。
 * emulin は sandbox 内で全 process が同じ uid (= process.uid) として動くので
 * peer uid = 自プロセスの uid を返せば実用 OK (= ACL list の getuid() に match)。
 *
 * シナリオ:
 *   1. AF_UNIX SOCK_STREAM の socketpair を作成
 *   2. sv[0] で getsockopt(SO_PEERCRED, &ucred, &len) を呼ぶ
 *   3. pid, uid, gid, optlen を確認
 */
#include "sys64.h"

void _start(void) {
    int sv[2];
    long r = sys_socketpair(1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0, sv);
    put("socketpair="); put_dec(r); put("\n");

    int ucred[3] = { 0xdead, 0xdead, 0xdead };  /* pid, uid, gid */
    int len = 12;
    long g = sys_getsockopt(sv[0], 1 /* SOL_SOCKET */, 17 /* SO_PEERCRED */, ucred, &len);
    put("getsockopt="); put_dec(g); put("\n");
    put("pid="); put_dec(ucred[0]); put("\n");
    put("uid="); put_dec(ucred[1]); put("\n");
    put("gid="); put_dec(ucred[2]); put("\n");
    put("optlen="); put_dec(len); put("\n");

    sys_close(sv[0]);
    sys_close(sv[1]);
    sys_exit(0);
}
