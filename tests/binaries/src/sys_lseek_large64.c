// sys_lseek_large64.c — lseek / read / write / pread64 / pwrite64 を 2GB 超オフセットで検証 (issue #336)
//   off_t は 64-bit。FileSeek が offset / size / 返り値を int に切り詰めていると
//   0x100000005 (4GiB+5) が 5 に化け、seek 位置・size・pread/pwrite 位置が壊れる。
//   sparse file で検証する (穴は実 disk/RAM を食わない)。
#include "sys64.h"

static long sys_pwrite(long fd, const void *buf, long count, long off) {
    long ret; register long r10 __asm__("r10") = off;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(18LL), "D"(fd), "S"((long)buf), "d"(count), "r"(r10) : "rcx","r11","memory");
    return ret;
}
static long sys_pread(long fd, void *buf, long count, long off) {
    long ret; register long r10 __asm__("r10") = off;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(17LL), "D"(fd), "S"((long)buf), "d"(count), "r"(r10) : "rcx","r11","memory");
    return ret;
}

#define O_RDWR   2
#define O_CREAT  0x40
#define O_TRUNC  0x200
#define SEEK_SET 0
#define SEEK_END 2

void _start(void) {
    sys_unlink("/tmp/lseek_large64.dat");
    long fd = sys_open("/tmp/lseek_large64.dat", O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (fd < 0) { put("OPEN_FAIL\n"); sys_exit(1); }

    long off = 0x100000005L;                 // 4GiB+5、低位 dword = 5

    // (1) lseek(SEEK_SET) が 64-bit offset をそのまま返すか (切り詰めだと 5)
    long set = sys_lseek(fd, off, SEEK_SET);
    put("set="); put_dec(set); put("\n");

    // (2) その位置に 'Z' を書く → seek(long) が効いているか (sparse)
    sys_write(fd, "Z", 1);

    // (3) SEEK_END で size = 4GiB+6 ((int)length() 切り詰めだと壊れる)
    long end = sys_lseek(fd, 0, SEEK_END);
    put("end="); put_dec(end); put("\n");

    // (4) seek し直して read → 'Z'
    sys_lseek(fd, off, SEEK_SET);
    char c = 0; sys_read(fd, &c, 1);
    put("rd="); sys_write(1, &c, 1); put("\n");

    // (5) pwrite64 で 4GiB+0x20 に 'Q' (offset の (int) 切り詰めが無いか)
    long pw = sys_pwrite(fd, "Q", 1, 0x100000020L);
    put("pw="); put_dec(pw); put("\n");

    // (6) pread64 で同 offset から読み戻し → 'Q'
    char q = 0; sys_pread(fd, &q, 1, 0x100000020L);
    put("pr="); sys_write(1, &q, 1); put("\n");

    // (7) 低位 offset 5 は穴 (0) = 切り詰め alias が無い証拠
    sys_lseek(fd, 5, SEEK_SET);
    char lo = 1; sys_read(fd, &lo, 1);
    put("lo="); put_dec((long)lo); put("\n");

    sys_close(fd);
    sys_unlink("/tmp/lseek_large64.dat");
    sys_exit(0);
}
