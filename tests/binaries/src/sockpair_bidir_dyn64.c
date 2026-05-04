/* sockpair_bidir_dyn64.c — AF_UNIX socketpair の双方向動作を回帰固定 (Phase 27 step 21)
 *
 * 双方向通信 (write fds[0] → read fds[1] と write fds[1] → read fds[0]) が
 * 両方とも成立することを検証。
 *
 * 期待出力:
 *   socketpair_rc=0
 *   forward got=4 msg=ping
 *   backward got=4 msg=pong
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

    /* 順方向: fds[0] write → fds[1] read */
    write(fds[0], "ping", 4);
    char buf1[16] = {0};
    int n1 = read(fds[1], buf1, 15);
    printf("forward got=%d msg=%s\n", n1, buf1);

    /* 逆方向: fds[1] write → fds[0] read */
    write(fds[1], "pong", 4);
    char buf2[16] = {0};
    int n2 = read(fds[0], buf2, 15);
    printf("backward got=%d msg=%s\n", n2, buf2);

    close(fds[0]); close(fds[1]);
    return 0;
}
