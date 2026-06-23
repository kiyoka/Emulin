/* sys_pf_demand64.c — issue #392 戦略B 4e: anonymous mmap の demand paging 回帰テスト。
 *
 * EMULIN_NATIVE_PF=1 の native(KVM) backend では anonymous mmap は reserve-only (PTE
 * not-present) になり、guest が触れた時の #PF → faultIn が demand 割当する。本テストは、
 * その demand paging が software backend (eager 割当) と byte 一致する観測可能な動作に
 * なることを 3 シナリオで検証する。出力は書いた/読んだ byte だけに依存し確保アドレスに
 * 依らないので software / native(eager) / native(NATIVE_PF) の 3 通りで同一になる。
 *
 *   A: sparse な 16 ページ (64KB) reserve-only mmap の zero-fill + write/read
 *      (b05eb61 — anon mmap を reserve-only 化 + #PF で demand 割当)
 *   B: head munmap で領域 entry が「分割保持」され、残った tail の faultIn が成功する
 *      (1c81c5f — partial munmap で領域 entry を分割保持。旧実装は remove(base) で大領域
 *       entry を丸ごと失い tail の faultIn が取りこぼし SIGSEGV した)
 *   C: 大領域内に MAP_FIXED 1 ページ (guard) を被せても entry が縮小せず tail が faultIn 可能
 *      (d87a2ab — MAP_FIXED 同一 base の大領域 entry 上書き縮小を修正。merge(max) で大 len 保持)
 *
 * 期待出力:
 *   A: zero=16 v=A,F,P
 *   B: tail=1,Y
 *   C: tail=1,Z
 *   PF_DEMAND ok
 */
#include "sys64.h"

#define PAGE 4096L

static void putc1(char c) { sys_write(1, &c, 1); }

void _start(void) {
    /* ---- A: sparse 多ページ reserve-only mmap の zero-fill + write/read ---- */
    long a = sys_mmap(0, 16 * PAGE, 0x3 /*RW*/, 0x22 /*PRIVATE|ANON*/, -1, 0);
    if (a < 0) { put("mmapA failed\n"); sys_exit(1); }
    volatile unsigned char *A = (volatile unsigned char *)a;

    /* write 前に各ページ先頭 byte を read → 全て 0 (read fault → demand-zero ページ)。 */
    int zero = 0;
    for (int i = 0; i < 16; i++) if (A[(long)i * PAGE] == 0) zero++;

    /* sparse なページに 'A'+index を書く (page 0/5/15)。各 write が #PF → faultIn(commit)。 */
    A[0L * PAGE]  = 'A';        /* 'A' */
    A[5L * PAGE]  = 'A' + 5;    /* 'F' */
    A[15L * PAGE] = 'A' + 15;   /* 'P' */

    put("A: zero="); put_dec(zero); put(" v=");
    putc1((char)A[0L * PAGE]);  putc1(',');
    putc1((char)A[5L * PAGE]);  putc1(',');
    putc1((char)A[15L * PAGE]); putc1('\n');

    /* ---- B: head munmap で entry が分割保持され、tail の初回 faultIn が成功する ---- */
    long b = sys_mmap(0, 8 * PAGE, 0x3, 0x22, -1, 0);
    if (b < 0) { put("mmapB failed\n"); sys_exit(1); }
    volatile unsigned char *B = (volatile unsigned char *)b;

    sys_munmap((void *)b, 2 * PAGE);          /* HEAD [0,2) を munmap → tail [2,8) を split 保持 */

    /* tail の page5 を初めて触る → split 保持された entry への faultIn が成功するはず。
     *   旧実装 (remove(base)) では 8 ページ entry が消え faultIn 失敗 → native SIGSEGV。 */
    int p5zero = (B[5L * PAGE] == 0) ? 1 : 0; /* read fault → demand-zero */
    B[5L * PAGE] = 'Y';                        /* write fault → commit */

    put("B: tail="); put_dec(p5zero); putc1(',');
    putc1((char)B[5L * PAGE]); putc1('\n');

    /* ---- C: 大領域内 MAP_FIXED guard で entry が縮小せず tail が faultIn 可能 ---- */
    long c = sys_mmap(0, 8 * PAGE, 0x3, 0x22, -1, 0);
    if (c < 0) { put("mmapC failed\n"); sys_exit(1); }
    volatile unsigned char *C = (volatile unsigned char *)c;

    /* base に MAP_FIXED 1 ページ (V8 が大領域先頭に張る guard page 相当)。 */
    long g = sys_mmap((void *)c, PAGE, 0x3, 0x32 /*PRIVATE|ANON|FIXED*/, -1, 0);
    if (g < 0) { put("mmapC-fixed failed\n"); sys_exit(1); }

    /* tail page7 を初めて触る → entry が (c,1page) に縮小していなければ faultIn 成功。 */
    int p7zero = (C[7L * PAGE] == 0) ? 1 : 0;
    C[7L * PAGE] = 'Z';

    put("C: tail="); put_dec(p7zero); putc1(',');
    putc1((char)C[7L * PAGE]); putc1('\n');

    /* ---- D: cage 内に 80 個の MAP_FIXED sub-region がある状態で gap page を fault → 包含 cage を発見 ----
     *   faultIn の包含領域探索が固定 64 回 cap だと、cage base との間に 64+ entry がある page を取りこぼし
     *   spurious SIGSEGV した (review #6)。maxReserveLen-bounded 下方走査ならその page も cage に解決できる。
     *   (eager/software は cage が全 present なので素通り、native(NATIVE_PF) のみ深い走査を実際に行う。) */
    long d = sys_mmap(0, 256 * PAGE, 0x3, 0x22, -1, 0);   /* 1MB cage = 256 page */
    if (d < 0) { put("mmapD failed\n"); sys_exit(1); }
    volatile unsigned char *D = (volatile unsigned char *)d;
    for (long k = 1; k <= 80; k++)                        /* cage 内 [d+1p, d+80p] に MAP_FIXED sub-region 80 個 */
        if (sys_mmap((void *)(d + k * PAGE), PAGE, 0x3, 0x32 /*FIXED*/, -1, 0) < 0) { put("mmapD-fixed failed\n"); sys_exit(1); }
    /* sub-region 群より上の gap page (d+100p) を初めて触る → floorEntry から 80+ entry 下方走査して cage を発見。 */
    int dz = (D[100L * PAGE] == 0) ? 1 : 0;
    D[100L * PAGE] = 'N';

    put("D: nested="); put_dec(dz); putc1(',');
    putc1((char)D[100L * PAGE]); putc1('\n');

    put("PF_DEMAND ok\n");
    sys_exit(0);
}
