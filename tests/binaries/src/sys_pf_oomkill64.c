/* sys_pf_oomkill64.c — issue #713: pool 枯渇 (faultIn 経路) の OOM-kill graceful 化回帰テスト。
 *
 * NATIVE_PF の native backend では anon mmap は reserve-only で、touch 時の #PF → faultIn が
 * data プールから demand 割当する。pool (既定 512MB) を超える量を touch すると faultIn の
 * allocData が NativeOom (物理プール枯渇) を投げる。ここは guest から見れば commit 済み
 * ページへのアクセスなので ENOMEM を返す先が無く、旧実装は RuntimeException で guest thread
 * ごと例外死 + set_exit_flag 未呼出 → 親の wait4 が永久に返らずセッションが刺さっていた
 * (#710 と同じ刺さり方)。修正後は Linux の OOM killer と同じ縮退 = 当該プロセスだけ
 * SIGKILL 死し、親は WTERMSIG=9 で reap して継続できる。
 *
 * 本テスト: fork 子が 1GB anon mmap を全ページ touch (pool 512MB < 1GB なので途中で必ず
 * OOM-kill される)、親が wait4 で WTERMSIG=9 を観測し、その後も mmap/write できる
 * (= エミュレータと親プロセスが生存) ことを確認する。
 *
 * native(NATIVE_PF) 専用: software は pool 概念が無く、native(eager) は mmap 時点で
 * ENOMEM になる (amd64_mmap の変換) ため挙動が異なる。native-pf-oracle.sh の 1-way
 * (pf_oracle_native) で検証し、expected/*.stdout は置かない (run-fast は SKIP)。
 *
 * 期待出力:
 *   child sig=9 exit=0
 *   parent alive=P
 *   PF_OOMKILL ok
 */
#include "sys64.h"

#define PAGE 4096L
#define GB1  (1024L * 1024 * 1024)

void _start(void) {
    long pid = sys_fork();
    if (pid == 0) {
        long a = sys_mmap(0, GB1, 0x3 /*RW*/, 0x22 /*PRIVATE|ANON*/, -1, 0);
        if (a < 0) sys_exit(3);          /* eager 経路なら ENOMEM でここ (native(PF) では成功する) */
        for (long off = 0; off < GB1; off += PAGE)
            *(volatile unsigned char *)(a + off) = 1;   /* #PF → faultIn → 途中で pool 枯渇 = OOM-kill */
        sys_exit(0);                     /* 到達しないはず */
    }
    int st = 0;
    if (sys_wait4(pid, &st, 0, 0) != pid) { put("wait4 failed\n"); sys_exit(1); }
    put("child sig=");  put_dec(st & 0x7f);        /* WIFSIGNALED: SIGKILL=9 */
    put(" exit=");      put_dec((st >> 8) & 0xff); /* signal 死なので 0 */
    put("\n");

    /* 親が継続して mmap/write できる = OOM がプロセス局所で済んだ証明 */
    long b = sys_mmap(0, 2 * 1024 * 1024L, 0x3, 0x22, -1, 0);
    if (b < 0) { put("parent mmap failed\n"); sys_exit(1); }
    volatile unsigned char *p = (volatile unsigned char *)b;
    p[0] = 'P';
    put("parent alive=");
    { char c = (char)p[0]; sys_write(1, &c, 1); }
    put("\n");

    put("PF_OOMKILL ok\n");
    sys_exit(0);
}
