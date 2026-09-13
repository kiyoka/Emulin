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
 * 期待 (引数無し): symlink=0 / opath=ok
 *   実 Linux でも O_PATH で symlink 経由に open できる (target 実在のため follow しても
 *   成功する)。「成功するか」だけを見るので host と emulator で期待値が割れない。
 *
 * ★ 未修正の差異 (別 issue): 実 Linux は O_PATH|O_NOFOLLOW で **symlink 自身**の fd を
 *   返す (systemd-tmpfiles が使う唯一の手段) が、Emulin は O_NOFOLLOW を先に見て
 *   ELOOP を返す (SyscallAmd64 の issue #442 の検査が #349 の分岐より手前にある)。
 *   ここでは O_NOFOLLOW を付けず、その差異には触れない。
 */
#include "sys64.h"

/* symlink(2) = syscall 88。sys64.h には無いのでここで定義。 */
static long sys_symlink(const char *target, const char *linkpath) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(88LL), "D"(target), "S"(linkpath) : "rcx", "r11", "memory");
    return ret;
}

#define O_PATH   0x200000
#define O_WRONLY 1
#define O_CREAT  0x40
#define O_TRUNC  0x200

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

    long fd = sys_open(link, O_PATH, 0);
    put("opath=");
    if (fd >= 0) { put("ok"); sys_close(fd); }
    else         { put_dec(fd); }
    put("\n");

    sys_exit(0);
}
