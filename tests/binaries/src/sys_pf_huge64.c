/* sys_pf_huge64.c — issue #392 戦略B: alloc_huge (≥2GB anonymous mmap) の reserve-only demand paging 回帰。
 *
 * ≥2GB の anonymous mmap は amd64_mmap が alloc_huge 経路に回す (Java byte[] の 2GB 上限を超えるため)。
 * V8 / Bun は pointer-compression cage を ≥2GB (実測 128GB) で MAP_NORESERVE 予約し、触れたページだけ
 * 使う。戦略B + EMULIN_NATIVE_PF=1 の native では alloc_huge を reserve-only にし #PF で demand 割当する。
 *
 * ★native(eager、NATIVE_PF 無し)では alloc_huge は ENOMEM(-12) のままなので、本テストは
 *   software と native(EMULIN_NATIVE_PF=1) の 2-way で byte 一致を検証する (native-pf-oracle.sh が分岐)。
 *
 * 2GiB+1page を確保し、先頭 / 1GiB / 2GiB の sparse offset を read(→demand-zero) / write / read する。
 * 出力は書いた/読んだ byte だけに依存し確保アドレスに依らないので software==native(PF) になる。
 *
 * 期待出力:
 *   huge: zero=3 v=A,G,H
 *   PF_HUGE ok
 */
#include "sys64.h"

#define PAGE 4096L

static void putc1(char c) { sys_write(1, &c, 1); }

void _start(void) {
    /* 2GiB + 1page = alloc_huge 経路 (aligned > 0x7FFFFFFF)。flags は V8 と同じ MAP_NORESERVE 込み。 */
    long len = 0x80000000L + PAGE;                 /* 2 GiB + 4 KB */
    long a = sys_mmap(0, len, 0x3 /*RW*/, 0x4022 /*PRIVATE|ANON|NORESERVE*/, -1, 0);
    if (a < 0) { put("huge mmap failed\n"); sys_exit(1); }
    volatile unsigned char *H = (volatile unsigned char *)a;

    /* sparse offset: 先頭 / 1GiB / 2GiB。各 read が #PF→demand-zero、write が #PF→commit。 */
    long o0 = 0L, o1 = 0x40000000L /*1GiB*/, o2 = 0x80000000L /*2GiB*/;
    int zero = 0;
    if (H[o0] == 0) zero++;
    if (H[o1] == 0) zero++;
    if (H[o2] == 0) zero++;
    H[o0] = 'A';
    H[o1] = 'G';
    H[o2] = 'H';

    put("huge: zero="); put_dec(zero); put(" v=");
    putc1((char)H[o0]); putc1(',');
    putc1((char)H[o1]); putc1(',');
    putc1((char)H[o2]); putc1('\n');

    sys_munmap((void *)a, len);
    put("PF_HUGE ok\n");
    sys_exit(0);
}
