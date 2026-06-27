/* sys_epollet_eventfd_64.c — eventfd を EPOLLET 登録したとき、write 毎に edge が
 * 再 arm されることを検証する (issue #427)。
 *
 * Linux の EPOLLET セマンティクス: eventfd への write は毎回 poll waiter を wake し
 * ready-list へ再登録する。よって counter を drain (read) しなくても、write する度に
 * 次の epoll_wait は 1 件返る。
 *
 * tokio (OpenAI Codex の async runtime) は waker eventfd を EPOLLET で登録し、wake 時に
 * counter を drain しない。issue #416 で node/libuv の spin 対策として入れた「readable
 * level で edge 判定 (前回も readable なら抑制)」は、drain されない eventfd の 2 回目以降
 * の write を「edge でない」と誤抑制し、tokio の I/O driver が epoll_pwait で永久 block
 * (codex の startup deadlock) した。修正は eventfd の EPOLLET を write 世代ベースの
 * edge にすること。
 *
 * 検証:
 *   r1   : write 後の epoll_pwait → 1 (edge 0->1)
 *   r2   : drain せず再 write 後の epoll_pwait → 1 (Linux は write 毎に再 arm)
 *   guard: drain 後 write 無しの epoll_pwait → 0 (issue #416: spurious を出さない)
 * 期待: r1=1 r2=1 guard=0
 */
#include "sys64.h"

#define EPOLLIN        0x001
#define EPOLLET        0x80000000
#define EPOLL_CTL_ADD  1

static long sys_eventfd2(long initval, long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(290LL), "D"(initval), "S"(flags) : "rcx", "r11");
    return ret;
}

static long sys_epoll_create1(long flags) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(291LL), "D"(flags) : "rcx", "r11");
    return ret;
}

static long sys_epoll_ctl(long epfd, long op, long fd, void *ev) {
    long ret;
    register long r10 __asm__("r10") = (long)ev;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(233LL), "D"(epfd), "S"(op), "d"(fd), "r"(r10) : "rcx", "r11", "memory");
    return ret;
}

static long sys_epoll_pwait(long epfd, void *ev, long maxev, long timeout) {
    long ret;
    register long r10 __asm__("r10") = timeout;
    register long r8  __asm__("r8")  = 0;   /* sigmask = NULL */
    register long r9  __asm__("r9")  = 8;   /* sigsetsize */
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(281LL), "D"(epfd), "S"(ev), "d"(maxev), "r"(r10), "r"(r8), "r"(r9)
        : "rcx", "r11", "memory");
    return ret;
}

/* struct epoll_event は x86-64 で packed 12 byte (uint32 events + 8 byte data) */
struct ee { unsigned int events; unsigned long long data; } __attribute__((packed));

void _start(void) {
    long efd = sys_eventfd2(0, 0);
    long ep  = sys_epoll_create1(0);

    struct ee ev;
    ev.events = EPOLLIN | EPOLLET;
    ev.data   = 0x1234;
    sys_epoll_ctl(ep, EPOLL_CTL_ADD, efd, &ev);

    unsigned long long one = 1, v;
    struct ee out[4];

    sys_write(efd, &one, 8);
    long r1 = sys_epoll_pwait(ep, out, 4, 1000);   /* edge 0->1 */

    sys_write(efd, &one, 8);                         /* 2 回目 write、drain せず */
    long r2 = sys_epoll_pwait(ep, out, 4, 1000);   /* Linux: 再 arm → 1 */

    sys_read(efd, &v, 8);                            /* drain */
    long guard = sys_epoll_pwait(ep, out, 4, 300);  /* write 無し → 0 */

    put("r1=");     put_dec(r1);
    put(" r2=");    put_dec(r2);
    put(" guard="); put_dec(guard);
    put("\n");

    sys_close(efd);
    sys_close(ep);
    sys_exit(0);
}
