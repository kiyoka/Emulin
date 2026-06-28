/* sys_procfs64.c — issue #411: procfs (ps/top/kill 用に process table を /proc に露出) 回帰。
 *
 * 検証:
 *   1. /proc を getdents64 で列挙すると "self" と自 pid のディレクトリが現れる
 *   2. /proc/self/stat が "<pid> (comm) <state> ..." 形式で読める
 *   3. /proc/self/cmdline が非空で読める (argv NUL 区切り)
 *   4. /proc/self/comm が非空で読める
 *
 * 出力は自プロセスの不変量だけに依存するので software / native(KVM/WHP) で同一。
 * 期待出力:
 *   procfs: self=1 mypid=1 stat=1 cmdline=1 comm=1
 *   PROCFS ok
 */
#include "sys64.h"

static long sys_getdents64(int fd, void *buf, long count) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(217LL), "D"((long)fd), "S"(buf), "d"(count)
        : "rcx", "r11", "memory");
    return ret;
}

static int str_eq(const char *a, const char *b) {
    while (*a && *b) { if (*a++ != *b++) return 0; }
    return *a == 0 && *b == 0;
}
static int starts_with(const char *s, const char *p) {
    while (*p) { if (*s++ != *p++) return 0; }
    return 1;
}
/* 符号なし long → 10 進文字列 (out に NUL 終端、長さ返す) */
static int itoa_u(long v, char *out) {
    char tmp[24];
    int n = 0;
    if (v == 0) { out[0] = '0'; out[1] = 0; return 1; }
    while (v > 0) { tmp[n++] = (char)('0' + (v % 10)); v /= 10; }
    for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
    out[n] = 0;
    return n;
}

void _start(void) {
    long mypid = sys_getpid();
    char pidstr[24];
    int pidlen = itoa_u(mypid, pidstr);

    /* ---- 1. /proc getdents: "self" と自 pid dir ---- */
    int self_found = 0, mypid_found = 0;
    int fd = sys_open("/proc", 0, 0);   /* O_RDONLY */
    if (fd >= 0) {
        char buf[8192];
        long n;
        while ((n = sys_getdents64(fd, buf, sizeof(buf))) > 0) {
            long off = 0;
            while (off < n) {
                unsigned short reclen = (unsigned char)buf[off+16] | ((unsigned char)buf[off+17] << 8);
                if (reclen == 0) break;
                const char *name = buf + off + 19;
                if (str_eq(name, "self"))   self_found = 1;
                if (str_eq(name, pidstr))   mypid_found = 1;
                off += reclen;
            }
        }
        sys_close(fd);
    }

    /* ---- 2. /proc/self/stat: "<pid> (comm) <state> ..." ---- */
    int stat_ok = 0;
    {
        int f = sys_open("/proc/self/stat", 0, 0);
        if (f >= 0) {
            char b[512];
            long r = sys_read(f, b, sizeof(b) - 1);
            sys_close(f);
            if (r > 0) {
                b[r] = 0;
                if (starts_with(b, pidstr) && b[pidlen] == ' ' && b[pidlen+1] == '(') {
                    int i = 0;
                    while (b[i] && b[i] != ')') i++;
                    if (b[i] == ')' && b[i+1] == ' ' &&
                        (b[i+2] == 'S' || b[i+2] == 'R' || b[i+2] == 'Z'))
                        stat_ok = 1;
                }
            }
        }
    }

    /* ---- 3. /proc/self/cmdline: 非空 ---- */
    int cmd_ok = 0;
    {
        int f = sys_open("/proc/self/cmdline", 0, 0);
        if (f >= 0) {
            char b[256];
            long r = sys_read(f, b, sizeof(b));
            sys_close(f);
            if (r > 0 && b[0] != 0) cmd_ok = 1;
        }
    }

    /* ---- 4. /proc/self/comm: 非空 ---- */
    int comm_ok = 0;
    {
        int f = sys_open("/proc/self/comm", 0, 0);
        if (f >= 0) {
            char b[64];
            long r = sys_read(f, b, sizeof(b));
            sys_close(f);
            if (r > 0 && b[0] != '\n' && b[0] != 0) comm_ok = 1;
        }
    }

    /* ---- 5. /proc/meminfo: MemTotal を読め、lseek(0) で再読込できる (libproc2/ps aux パターン) ---- */
    int meminfo_ok = 0;
    {
        int f = sys_open("/proc/meminfo", 0, 0);
        if (f >= 0) {
            char b[256];
            long r1 = sys_read(f, b, sizeof(b) - 1);
            sys_lseek(f, 0, 0);                     /* SEEK_SET — libproc2 は query 毎に再読込 */
            long r2 = sys_read(f, b, sizeof(b) - 1);
            sys_close(f);
            /* 再読込が初回と同じ長さ (>0) かつ "Mem" で始まる = lseek が memPos を戻せている */
            if (r1 > 0 && r2 == r1 && b[0] == 'M' && b[1] == 'e' && b[2] == 'm') meminfo_ok = 1;
        }
    }

    put("procfs: self="); put(self_found ? "1" : "X");
    put(" mypid=");       put(mypid_found ? "1" : "X");
    put(" stat=");        put(stat_ok ? "1" : "X");
    put(" cmdline=");     put(cmd_ok ? "1" : "X");
    put(" comm=");        put(comm_ok ? "1" : "X");
    put(" meminfo=");     put(meminfo_ok ? "1" : "X");
    put("\n");
    if (self_found && mypid_found && stat_ok && cmd_ok && comm_ok && meminfo_ok) put("PROCFS ok\n");
    else put("PROCFS FAIL\n");
    sys_exit(0);
}
