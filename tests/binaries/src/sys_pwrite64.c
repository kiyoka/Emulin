// sys_pwrite64.c — pwrite64(#18) / pread64(#17) / fdatasync(#75) の検証 (-nostdlib, syscall 直叩き)。
//   sqlite 等が positioned I/O + durability に使う。emulin は pread64 のみ実装で pwrite64/fdatasync
//   が ENOSYS → sqlite が "disk I/O error" になっていた (issue #221 step 3d-2c-43 で実装)。
//   (1) pwrite で offset 指定書込 → file position は不変 (POSIX)。
//   (2) その後の write は元 position に着地。
//   (3) pread で offset 指定読込 → 内容一致。
//   (4) fdatasync が 0 (成功) を返す。
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
static long sys_fdatasync(long fd) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(75LL), "D"(fd) : "rcx","r11","memory");
    return ret;
}

#define O_RDONLY 0
#define O_RDWR   2
#define O_CREAT  0x40
#define O_TRUNC  0x200

void _start(void) {
    sys_unlink("/tmp/pw_test64");
    long fd = sys_open("/tmp/pw_test64", O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (fd < 0) { put("OPEN_FAIL\n"); sys_exit(1); }
    sys_write(fd, "AAAAAAAAAA", 10);          // file pos = 10
    long w = sys_pwrite(fd, "XYZ", 3, 3);     // offset 3 に上書き、pos は不変
    put("pwrite_ret="); put_dec(w); put("\n");
    long pos = sys_lseek(fd, 0, 1);           // SEEK_CUR → 10 のまま (pwrite は pos を進めない)
    put("pos_after_pwrite="); put_dec(pos); put("\n");
    sys_write(fd, "ZZ", 2);                    // pos 10 に append → 全 12 byte
    put("fdatasync="); put_dec(sys_fdatasync(fd)); put("\n");
    sys_close(fd);

    char buf[32];
    long rfd = sys_open("/tmp/pw_test64", O_RDONLY, 0);
    long n = sys_read(rfd, buf, sizeof(buf));
    char pb[8];
    long pr = sys_pread(rfd, pb, 3, 3);        // offset 3 から "XYZ"
    sys_close(rfd);
    put("readlen="); put_dec(n); put("\n");
    put("content="); sys_write(1, buf, n); put("\n");
    put("pread_ret="); put_dec(pr); put("\n");
    put("pread_data="); sys_write(1, pb, pr); put("\n");
    sys_unlink("/tmp/pw_test64");
    sys_exit(0);
}
