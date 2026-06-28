/* sys_recvfrom_socketpair_64.c — recvfrom(2) on an AF_UNIX SOCK_STREAM
 *   socketpair endpoint (incl. a dup'd one) は、peer 生存・空・non-blocking の
 *   とき EAGAIN を返すべきで、ECONNRESET であってはならない (issue #427)。
 *
 * Rust の tokio の signal self-pipe は mio の UnixStream::pair() =
 *   socketpair(AF_UNIX, SOCK_STREAM|SOCK_NONBLOCK) で作られ、signal driver は
 *   receiver を dup() した fd を non-blocking で read/recv して空になるまで
 *   drain する。空読みは EAGAIN→break が唯一の正常終了で、それ以外の error
 *   (ECONNRESET 含む) や EOF(0) では panic する。
 * emulin は旧実装で amd64_recvfrom が socketpair を finfo.Read (f==null → -21)
 *   に落とし「r < 0 → -104 ECONNRESET」に化けていたため、OpenAI Codex が chat
 *   画面で "Bad read on self-pipe: Connection reset by peer (os error 104)" と
 *   panic していた。amd64_recvmsg と同じく socketpair を kernel.pipe_read で
 *   直接読む修正で空読みが EAGAIN になる。
 *
 * 検証: socketpair → dup(receiver) → 空 recvfrom = -11(EAGAIN) →
 *       sender に 1 byte write → recvfrom = 1 → 再び空 recvfrom = -11(EAGAIN)。
 */
#include "sys64.h"

#define AF_UNIX        1
#define SOCK_STREAM    1
#define SOCK_NONBLOCK  0x800

void _start(void) {
    int sv[2] = { 0, 0 };
    long r = sys_socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0, sv);
    put("socketpair="); put_dec(r); put("\n");

    long rfd = sys_dup((long)sv[0]);       /* tokio は receiver を dup した fd を読む */
    put("dup_ok="); put_dec(rfd >= 0 ? 1 : rfd); put("\n");

    char buf[128];
    long e1 = sys_recvfrom(rfd, buf, 128, 0, 0, 0);   /* 空・peer 生存 */
    put("recv_empty="); put_dec(e1); put("\n");        /* expect -11 (EAGAIN) */

    char one = 1;
    sys_write((long)sv[1], &one, 1);                   /* sender が 1 byte 書く */
    long e2 = sys_recvfrom(rfd, buf, 128, 0, 0, 0);
    put("recv_data="); put_dec(e2); put("\n");         /* expect 1 */

    long e3 = sys_recvfrom(rfd, buf, 128, 0, 0, 0);    /* 再び空 */
    put("recv_empty2="); put_dec(e3); put("\n");       /* expect -11 (EAGAIN) */

    sys_exit(0);
}
