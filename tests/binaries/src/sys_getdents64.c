// sys_getdents64.c — Phase 18: getdents64 が "." と ".." を各 1 回のみ返すことを確認
// /etc を列挙し、エントリ毎にカウント。期待: . が 1, .. が 1, emulin.cnf が 1
#include "sys64.h"

static long sys_getdents64(int fd, void *buf, long count) {
    long ret;
    __asm__ volatile("syscall"
        : "=a"(ret) : "0"(217LL), "D"((long)fd), "S"(buf), "d"(count)
        : "rcx", "r11", "memory");
    return ret;
}

static int starts_with(const char *s, const char *p) {
    while (*p) { if (*s++ != *p++) return 0; }
    return 1;
}
static int str_eq(const char *a, const char *b) {
    while (*a && *b) { if (*a++ != *b++) return 0; }
    return *a == 0 && *b == 0;
}

void _start(void) {
    int fd = sys_open("/etc", 0, 0);  // O_RDONLY
    if (fd < 0) { put("OPEN_FAIL\n"); sys_exit(1); }

    char buf[4096];
    volatile long n = sys_getdents64(fd, buf, sizeof(buf));
    if (n < 0) { put("GETDENTS_FAIL\n"); sys_exit(2); }

    int count_dot = 0, count_dotdot = 0, count_emulin = 0, count_other = 0;
    long off = 0;
    while (off < n) {
        // dirent64: d_ino(8) d_off(8) d_reclen(2) d_type(1) d_name[]
        unsigned short reclen = (unsigned char)buf[off+16] | ((unsigned char)buf[off+17] << 8);
        if (reclen == 0) break;
        const char *name = buf + off + 19;
        if (str_eq(name, "."))            count_dot++;
        else if (str_eq(name, ".."))      count_dotdot++;
        else if (str_eq(name, "emulin.cnf")) count_emulin++;
        else                                count_other++;
        off += reclen;
    }

    sys_close(fd);

    put("dot=");      put(count_dot==1 ? "1" : "X");
    put(" dotdot="); put(count_dotdot==1 ? "1" : "X");
    put(" emulin=");  put(count_emulin==1 ? "1" : "X");
    put(" other=");   put(count_other==0 ? "0" : "X");
    put("\n");
    sys_exit(0);
}
