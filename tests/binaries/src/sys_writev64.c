/* sys_writev64.c — writev (syscall #20)
 *
 * 3 つの iovec を 1 度の writev で stdout へ送る。
 */
#include "sys64.h"

struct iovec { const void *iov_base; long iov_len; };

void _start(void) {
    struct iovec v[3];
    v[0].iov_base = "foo";  v[0].iov_len = 3;
    v[1].iov_base = " ";    v[1].iov_len = 1;
    v[2].iov_base = "bar\n"; v[2].iov_len = 4;

    long n = sys_writev(1, v, 3);
    put("written=");
    put_dec(n);
    put("\n");
    sys_exit(0);
}
