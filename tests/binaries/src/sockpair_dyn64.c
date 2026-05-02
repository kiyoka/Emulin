/* sockpair_dyn64.c — AF_UNIX socketpair の単方向動作を回帰固定 (Phase 25 続き)
 *
 * socketpair(AF_UNIX, SOCK_STREAM, ...) で得た fd ペアに対し、
 *    write(fds[0], ...) → read(fds[1], ...)
 * の片方向通信が成立することを検証。
 *
 * 双方向 (write fds[1] → read fds[0]) は本実装ではまだ未対応 (TODO)。
 *
 * 期待出力:
 *   socketpair_rc=0
 *   got=5 msg=ping
 */
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>

int main(void) {
    int fds[2];
    int rc = socketpair(AF_UNIX, SOCK_STREAM, 0, fds);
    printf("socketpair_rc=%d\n", rc);
    if (rc != 0) return 1;

    write(fds[0], "ping", 4);
    char buf[16] = {0};
    int n = read(fds[1], buf, 15);
    /* 改行を取り除いて出す */
    printf("got=%d msg=%s\n", n, buf);

    close(fds[0]); close(fds[1]);
    return 0;
}
