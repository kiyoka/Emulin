/* sys_mmap_ptchurn64.c — issue #710: native MMU の page table 領域枯渇 (allocPt) 回帰テスト。
 *
 * native backend の mmap 仮想アドレスは MMAP_BASE から下方 bump 専用で、munmap しても
 * VA は再利用されない。新規 2MB VA ブロックに初めて触るたび leaf page table を 1 ページ
 * 消費し、leaf PT は munmap 後も回収されない (lock-free walk と併存できないため意図的)。
 * 旧実装は PT 固定枠 [PT_BASE, DATA_BASE) = 8MB (~2000 ページ = 累積 ~4GB の新規 VA) を
 * 使い切ると faultIn → allocPt が NativeOom を投げ、guest thread ごと例外死していた
 * (長時間の claude セッション + background task で実際に発生)。修正後は data 領域から
 * fallback 割当するので、上限が data プール全体に広がる。
 *
 * 本テストは 2MB mmap → 先頭 byte write/read → munmap を 2600 回繰り返し、旧実装の
 * 枠 (~2000) を確実に越える。data ページは munmap で freePages に回収されるので、
 * 枯渇するのは PT だけ = allocPt fallback を選択的に検証できる。仕上げに fork を 1 回
 * 行い、fallback PT ページを持つアドレス空間の duplicate() (子への複製 + rebase 走査)
 * も回帰網に入れる。出力は書いた/読んだ byte だけに依存するので software / native
 * (eager) / native (NATIVE_PF) の 3 通りで byte 一致する (native-pf-oracle.sh)。
 *
 * 期待出力:
 *   A: churn=2600
 *   B: fork=42
 *   PT_CHURN ok
 */
#include "sys64.h"

#define MB2  (2 * 1024 * 1024L)
#define ITER 2600

void _start(void) {
    /* ---- A: mmap/munmap churn で累積 5.2GB の新規 VA に触る ---- */
    long ok = 0;
    for (long i = 0; i < ITER; i++) {
        long a = sys_mmap(0, MB2, 0x3 /*RW*/, 0x22 /*PRIVATE|ANON*/, -1, 0);
        if (a < 0) { put("mmap failed at "); put_dec(i); put("\n"); sys_exit(1); }
        volatile unsigned char *p = (volatile unsigned char *)a;
        unsigned char v = (unsigned char)('A' + (i % 26));
        p[0] = v;                              /* 初回 touch = #PF → faultIn → 新 leaf PT */
        if (p[0] == v) ok++;
        if (sys_munmap((void *)a, MB2) != 0) { put("munmap failed at "); put_dec(i); put("\n"); sys_exit(1); }
    }
    put("A: churn="); put_dec(ok); put("\n");

    /* ---- B: fallback PT を持つ状態で fork → 子のアドレス空間複製の回帰 ---- */
    long pid = sys_fork();
    if (pid == 0) {
        long c = sys_mmap(0, MB2, 0x3, 0x22, -1, 0);
        if (c < 0) sys_exit(9);
        volatile unsigned char *q = (volatile unsigned char *)c;
        q[0] = 'Z';
        sys_exit(q[0] == 'Z' ? 42 : 8);
    }
    int st = 0;
    if (sys_wait4(pid, &st, 0, 0) != pid) { put("wait4 failed\n"); sys_exit(1); }
    put("B: fork="); put_dec((st >> 8) & 0xFF); put("\n");

    put("PT_CHURN ok\n");
    sys_exit(0);
}
