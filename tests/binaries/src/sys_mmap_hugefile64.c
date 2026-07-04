/* sys_mmap_hugefile64.c — issue #527: file-backed ≥2GiB mmap の demand paging 回帰。
 *
 * file-backed (fd>=0) の ≥2GiB mmap は旧実装で alloc_and_map(int) に落ち、長さの (int) 切り詰めが
 * 負値になると NegativeArraySizeException で guest thread が死んでいた (git index-pack の巨大 pack、
 * 3GiB ファイルの丸ごと read mmap 等)。修正後は native(NATIVE_PF) が alloc_huge_file (reserve +
 * faultIn の file 読み込み) で backing する。
 *
 * ★software / native(eager) は alloc_huge_file 未対応で ENOMEM を返す (明示的失敗 > silent 破壊)。
 *   よって本テストは native(EMULIN_NATIVE_PF=1) 専用の 1-way 期待値検証 (native-pf-oracle.sh が分岐)。
 *
 * sparse な 2GiB+2page ファイルを ftruncate で作り、マーカーを 先頭 / 1GiB / 2GiB境界前後 / 末尾 に
 * 書いてから全体を PROT_READ で mmap し、マーカーと hole(=0) を読み戻す。2GiB (=int 限界) を跨いだ
 * offset の内容一致が検証の本体。
 *
 * 期待出力:
 *   hugefile: size ok
 *   hugefile: v=A,B,C,D,E hole=0
 *   MMAP_HUGEFILE ok
 */
#include "sys64.h"

#define PAGE 4096L
#define O_RDONLY 0
#define O_WRONLY 01
#define O_CREAT  0100
#define O_TRUNC  01000

static void putc1(char c) { sys_write(1, &c, 1); }

static void wr_at(long fd, long off, char c) {
    sys_lseek(fd, off, 0 /*SEEK_SET*/);
    sys_write(fd, &c, 1);
}

void _start(void) {
    const char *path = "/tmp/hugefile527.bin";
    long size = 0x80000000L + 2 * PAGE;            /* 2GiB + 8KB (>int 上限、sparse なので disk は軽い) */

    long wfd = sys_open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (wfd < 0) { put("hugefile open(w) failed\n"); sys_exit(1); }
    if (sys_ftruncate(wfd, size) != 0) { put("hugefile ftruncate failed\n"); sys_exit(1); }
    /* マーカー: 先頭 / 1GiB / 2GiB-1page / 2GiB / 末尾 byte。間は sparse hole (=0)。 */
    wr_at(wfd, 0L,                    'A');
    wr_at(wfd, 0x40000000L,           'B');
    wr_at(wfd, 0x80000000L - PAGE,    'C');
    wr_at(wfd, 0x80000000L,           'D');
    wr_at(wfd, size - 1,              'E');
    sys_close(wfd);

    long rfd = sys_open(path, O_RDONLY, 0);
    if (rfd < 0) { put("hugefile open(r) failed\n"); sys_exit(1); }
    put("hugefile: size ok\n");

    long a = sys_mmap(0, size, 0x1 /*PROT_READ*/, 0x2 /*MAP_PRIVATE*/, rfd, 0);
    if (a < 0) { put("hugefile mmap failed\n"); sys_exit(1); }
    volatile unsigned char *H = (volatile unsigned char *)a;

    put("hugefile: v=");
    putc1((char)H[0L]);                 putc1(',');
    putc1((char)H[0x40000000L]);        putc1(',');
    putc1((char)H[0x80000000L - PAGE]); putc1(',');
    putc1((char)H[0x80000000L]);        putc1(',');
    putc1((char)H[size - 1]);
    put(" hole=");
    put_dec((long)H[PAGE]);             /* sparse hole は 0 (EOF/zero-fill 契約ではなく file の hole) */
    put("\n");

    sys_munmap((void *)a, size);
    sys_close(rfd);
    sys_unlink(path);
    put("MMAP_HUGEFILE ok\n");
    sys_exit(0);
}
