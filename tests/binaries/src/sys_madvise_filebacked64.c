/* sys_madvise_filebacked64.c — issue #403: madvise(MADV_DONTNEED) は file-backed page を
 * zero 化しない (anonymous は従来どおり zero 化 = #113 維持) ことの回帰テスト。
 *
 * 背景: emulin の madvise(MADV_DONTNEED) は対象 page を bulkZero する (#113 — pthread
 *   stack cache の thread 再利用で garbage を残さないため)。しかし file-backed page
 *   (fd>=0 mmap / ELF PT_LOAD) まで zero 化すると、実 Linux なら file 内容に再フォールト
 *   する所が emulin では内容を失う。Bun/V8 (claude) は ELF 埋め込み JS ソースを
 *   madvise(DONTNEED) で decommit しつつ zero-copy 文字列 view を保持するため、module 名が
 *   garbage 化して ENOENT 起動失敗していた (#403)。
 *
 * 本テストは観測可能な動作 (確保アドレスに依らない byte) のみ出力するので software /
 * native(eager) / native(NATIVE_PF) の 3 通りで同一になる:
 *
 *   FILE: fd>=0 mmap (MAP_PRIVATE) に file の canary を載せ、madvise(DONTNEED) 後に再 read
 *         → canary が残る (#403 修正。旧実装は 0 に化けて FAIL)。
 *   ANON: 匿名 mmap に 'X' を書き madvise(DONTNEED) 後に再 read → 0 に戻る (#113 維持)。
 *
 * 期待出力:
 *   FILE b0=65 b100=87
 *   ANON b0=0
 *   MADV_FB ok
 */
#include "sys64.h"

#define PAGE 4096L

/* madvise(addr, length, advice) — x86-64 syscall #28 (sys64.h に wrapper が無いので定義)。 */
static long sys_madvise(void *addr, long length, long advice) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(28LL), "D"(addr), "S"(length), "d"(advice) : "rcx", "r11", "memory");
    return ret;
}

void _start(void) {
    const long O_RDWR = 2, O_CREAT = 0100, O_TRUNC = 01000;
    const long PROT_RW = 0x3, MAP_PRIVATE = 0x2, MAP_ANON = 0x20, MADV_DONTNEED = 4;

    /* ---- FILE: canary を file に書き、MAP_PRIVATE で mmap、madvise(DONTNEED) 後に再 read ---- */
    long fd = sys_open("/tmp/madv_fb.dat", O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { put("open failed\n"); sys_exit(1); }

    unsigned char canary[PAGE];
    for (int i = 0; i < PAGE; i++) canary[i] = (unsigned char)('A' + (i % 26));
    if (sys_write(fd, canary, PAGE) != PAGE) { put("write failed\n"); sys_exit(1); }

    long f = sys_mmap(0, PAGE, PROT_RW, MAP_PRIVATE, fd, 0);
    if (f < 0) { put("mmap(file) failed\n"); sys_exit(1); }
    volatile unsigned char *F = (volatile unsigned char *)f;

    /* mapping には書き込まない (read-only に file 内容を見るだけ)。MAP_PRIVATE への write 後の
     *   DONTNEED は実 Linux では「元 file 内容」に戻る = emulin の保存挙動と意味が変わるため、
     *   ここでは未書込のまま file 内容の保存だけを検証する。 */
    sys_madvise((void *)f, PAGE, MADV_DONTNEED);

    int f0   = F[0];      /* madvise 後に読む → canary が残っているはず ('A'=65) */
    int f100 = F[100];    /* 'A' + (100 % 26) = 65 + 22 = 87 */

    /* ---- ANON: 匿名 mmap に書いて madvise(DONTNEED) 後に再 read → 0 に戻る (#113 維持) ---- */
    long a = sys_mmap(0, PAGE, PROT_RW, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (a < 0) { put("mmap(anon) failed\n"); sys_exit(1); }
    volatile unsigned char *A = (volatile unsigned char *)a;
    A[0] = 'X';
    sys_madvise((void *)a, PAGE, MADV_DONTNEED);
    int a0 = A[0];        /* madvise 後に読む → 0 に戻っているはず */

    put("FILE b0="); put_dec(f0); put(" b100="); put_dec(f100); put("\n");
    put("ANON b0="); put_dec(a0); put("\n");

    sys_close(fd);
    sys_unlink("/tmp/madv_fb.dat");

    if (f0 == 'A' && f100 == ('A' + (100 % 26)) && a0 == 0) {
        put("MADV_FB ok\n");
        sys_exit(0);
    }
    put("MADV_FB FAIL\n");
    sys_exit(1);
}
