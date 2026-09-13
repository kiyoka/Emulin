/* sys_opath_symlink64.c — open(<symlink>, O_PATH) が「最終 component が symlink」の
 * 分岐を通ること (issue #349) を踏む唯一のテスト。
 *
 * ★ なぜ要るか: Syscall.open_resolved の O_PATH 分岐は **new Inode(...) より前に
 *   return する**ので、そこに載っている判定 (issue #732 の FsPolicy hook) は
 *   Inode 側の判定では代替できない。busybox 経由ではこの分岐に到達しないため、
 *   この binary が無いと **open 側の hook を消しても検査は緑のまま**になる。
 *   fspolicy-smoke.sh がこれを allowlist の外に対して起動し、負のコントロールにする。
 *
 * 引数:
 *   無し       … /tmp に「実在 target を指す symlink」を作ってから開く (単体テスト用)
 *   <linkpath> … 既にある symlink を開くだけ (fspolicy-smoke が host 側で用意する)
 *
 * 期待 (引数無し): symlink=0 / opath=ok / nofollow=-40
 *   ★ O_PATH|O_NOFOLLOW は **symlink 自身**の fd が返る。実 Linux でこれが symlink の
 *     fd を得る唯一の手段 (実測: fstat の st_mode=0120777)。
 *   ★ O_PATH 無しの O_NOFOLLOW は ELOOP(-40) のまま (issue #442)。**対で測る**:
 *     ELOOP を返さなくしただけの直し方をすると、こちらが赤くなる。
 */
#include "sys64.h"

/* symlink(2) = syscall 88。sys64.h には無いのでここで定義。 */
static long sys_symlink(const char *target, const char *linkpath) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(88LL), "D"(target), "S"(linkpath) : "rcx", "r11", "memory");
    return ret;
}

#define O_PATH     0x200000
#define O_NOFOLLOW 0x20000
#define O_WRONLY   1
#define O_CREAT    0x40
#define O_TRUNC    0x200

void _start(void) {
    long argc;
    char **argv;
    __asm__ volatile(
        "movq 8(%%rbp), %0\n"
        "leaq 16(%%rbp), %1\n"
        : "=r"(argc), "=r"(argv)
    );

    const char *link;
    if (argc >= 2) {
        link = argv[1];                    /* 既存の symlink を開くだけ */
    } else {
        const char *tgt = "/tmp/sys_opath_symlink.tgt";
        link            = "/tmp/sys_opath_symlink.lnk";
        sys_unlink(link);                  /* 前回の残骸掃除 (結果は無視) */
        long t = sys_open(tgt, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (t >= 0) sys_close(t);
        long s = sys_symlink(tgt, link);
        put("symlink=");
        put_dec(s);
        put("\n");
    }

    long fd = sys_open(link, O_PATH | O_NOFOLLOW, 0);
    put("opath=");
    if (fd >= 0) { put("ok"); sys_close(fd); }
    else         { put_dec(fd); }
    put("\n");

    if (argc < 2) {
        /* ★ 対: O_PATH が無ければ O_NOFOLLOW は ELOOP のまま (issue #442)。 */
        long nf = sys_open(link, O_NOFOLLOW, 0);
        put("nofollow=");
        if (nf >= 0) { put("ok"); sys_close(nf); }
        else         { put_dec(nf); }
        put("\n");
    }

    sys_exit(0);
}
