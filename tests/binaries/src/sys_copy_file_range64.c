// sys_copy_file_range64.c — copy_file_range (syscall #326) の検証。
//   (1) NULL offset で src→dst 全コピー → 返り値 = byte 数・内容一致を確認。
//   (2) 明示 off_in 指定で部分コピー → 返り値・*off_in が進むことを確認 (fd 位置は不変)。
#include "sys64.h"

static long sys_copy_file_range(int fd_in, long *off_in, int fd_out, long *off_out,
                                unsigned long len, unsigned int flags) {
    long ret;
    register long r10 __asm__("r10") = (long)off_out;
    register long r8  __asm__("r8")  = (long)len;
    register long r9  __asm__("r9")  = (long)flags;
    __asm__ volatile("syscall"
        : "=a"(ret)
        : "0"(326LL), "D"((long)fd_in), "S"((long)off_in), "d"((long)fd_out),
          "r"(r10), "r"(r8), "r"(r9)
        : "rcx", "r11", "memory");
    return ret;
}

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT  0x40
#define O_TRUNC  0x200

static int mem_eq(const char *a, const char *b, long n) {
    for (long i = 0; i < n; i++) if (a[i] != b[i]) return 0;
    return 1;
}

void _start(void) {
    const char *msg = "HELLO-COPY-FILE-RANGE-0123456789\n";  // 33 byte
    long mlen = ustrlen(msg);

    sys_unlink("/tmp/cfr_src");
    sys_unlink("/tmp/cfr_dst");
    sys_unlink("/tmp/cfr_dst2");

    // src を作成して書く
    int sfd = sys_open("/tmp/cfr_src", O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (sfd < 0) { put("SRC_OPEN_FAIL\n"); sys_exit(1); }
    sys_write(sfd, msg, mlen);
    sys_close(sfd);

    // (1) NULL offset: src 全体を dst にコピー
    int fin  = sys_open("/tmp/cfr_src", O_RDONLY, 0);
    int fout = sys_open("/tmp/cfr_dst", O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fin < 0 || fout < 0) { put("OPEN_FAIL\n"); sys_exit(2); }
    long r = sys_copy_file_range(fin, 0, fout, 0, 4096, 0);
    sys_close(fin);
    sys_close(fout);
    put("copied=");  put_dec(r);  put("\n");

    // dst を読み返して内容一致を確認
    char buf[256];
    int rfd = sys_open("/tmp/cfr_dst", O_RDONLY, 0);
    long got = sys_read(rfd, buf, sizeof(buf));
    sys_close(rfd);
    put("readback="); put_dec(got); put("\n");
    put("match=");    put_dec((got == mlen && mem_eq(buf, msg, mlen)) ? 1 : 0); put("\n");

    // (2) 明示 off_in=6 から 4 byte コピー → off_in は 10 に進む
    int fin2  = sys_open("/tmp/cfr_src", O_RDONLY, 0);
    int fout2 = sys_open("/tmp/cfr_dst2", O_CREAT | O_WRONLY | O_TRUNC, 0644);
    long in_off = 6;
    long r2 = sys_copy_file_range(fin2, &in_off, fout2, 0, 4, 0);
    sys_close(fin2);
    sys_close(fout2);
    put("copied2=");      put_dec(r2);     put("\n");
    put("in_off_after="); put_dec(in_off); put("\n");

    sys_unlink("/tmp/cfr_src");
    sys_unlink("/tmp/cfr_dst");
    sys_unlink("/tmp/cfr_dst2");
    sys_exit(0);
}
