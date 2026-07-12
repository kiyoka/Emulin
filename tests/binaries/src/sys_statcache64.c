/* sys_statcache64.c — issue #701: InodeCache (stat 属性キャッシュ) の整合性
 *
 * 各変更操作の直前に stat してキャッシュへ載せ、変更の直後 (TTL 2s 内) に
 * もう一度 stat して変更が見えることを確認する。invalidate フックの
 * 取りこぼしがあると直後の stat が古い属性を返して FAIL になる。
 *
 * 検証する変更: creat / write(追記)+close / 書き込み中 fstat / ftruncate /
 * chmod / rename / unlink / mkdir / rmdir / utimensat / link
 */
#include "sys64.h"

#define O_WRONLY 01
#define O_CREAT  0100
#define O_APPEND 02000

/* AMD64 struct stat: st_nlink=16(8B) st_mode=24(4B) st_size=48(8B) st_mtime=88(8B) */
static char sb[256];
static long st_size(void)  { return *(long *)(sb + 48); }
static long st_nlink(void) { return *(long *)(sb + 16); }
static long st_mode(void)  { return (long)(*(unsigned int *)(sb + 24) & 07777); }
static long st_mtime(void) { return *(long *)(sb + 88); }

static long sys_utimensat(long dirfd, const char *path, const void *times, long flags) {
    register long r10 __asm__("r10") = flags;
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(280LL), "D"(dirfd), "S"(path), "d"(times), "r"(r10) : "rcx", "r11", "memory");
    return ret;
}

static long sys_link(const char *oldp, const char *newp) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(86LL), "D"(oldp), "S"(newp) : "rcx", "r11", "memory");
    return ret;
}

static void check(const char *label, long got, long want) {
    put(label);
    put("=");
    put_dec(got == want ? 1 : 0);
    if (got != want) { put(" (got="); put_dec(got); put(" want="); put_dec(want); put(")"); }
    put("\n");
}

void _start(void) {
    const char *A = "/tmp/statcache_a";
    const char *B = "/tmp/statcache_b";
    const char *C = "/tmp/statcache_c";
    const char *D = "/tmp/statcache_d";
    const char *L2 = "/tmp/statcache_l2";
    long fd, r;

    /* 0. 負キャッシュ → 作成: stat ENOENT を先に引いてから creat し、直後の stat が見えるか */
    check("neg_before", sys_stat(A, sb), -2);
    fd = sys_open(A, O_WRONLY | O_CREAT, 0644);
    sys_write(fd, "aaaa", 4);
    sys_close(fd);
    check("create_seen", sys_stat(A, sb), 0);
    check("create_size", st_size(), 4);

    /* 1. 追記 + 書き込み中 fstat: open 中はキャッシュを迂回して生 size が見えるか */
    fd = sys_open(A, O_WRONLY | O_APPEND, 0);
    sys_write(fd, "bbbb", 4);
    r = sys_fstat(fd, sb);
    check("open_fstat", r, 0);
    check("open_fstat_size", st_size(), 8);
    sys_close(fd);
    sys_stat(A, sb);
    check("append_size", st_size(), 8);

    /* 2. chmod: 直前 stat でキャッシュに載せ、chmod 直後に新 mode が見えるか */
    sys_stat(A, sb);
    sys_chmod(A, 0640);
    sys_stat(A, sb);
    check("chmod_mode", st_mode(), 0640);

    /* 3. ftruncate: fd 経由の直接切詰めが直後の stat に見えるか */
    fd = sys_open(A, O_WRONLY, 0);
    sys_stat(A, sb);              /* 書き込み open 中の stat (キャッシュ迂回のはず) */
    sys_ftruncate(fd, 3);
    r = sys_fstat(fd, sb);
    check("ftrunc_fstat_size", st_size(), 3);
    sys_close(fd);
    sys_stat(A, sb);
    check("ftrunc_size", st_size(), 3);

    /* 4. utimensat: mtime 変更が直後の stat に見えるか */
    sys_stat(A, sb);
    long times[4];
    times[0] = 1000000000; times[1] = 0;   /* atime */
    times[2] = 1234567890; times[3] = 0;   /* mtime */
    r = sys_utimensat(-100, A, times, 0);
    check("utimens_ret", r, 0);
    sys_stat(A, sb);
    check("utimens_mtime", st_mtime(), 1234567890);

    /* 5. link: nlink 増加が直後の stat に見えるか */
    sys_stat(A, sb);
    r = sys_link(A, L2);
    check("link_ret", r, 0);
    sys_stat(A, sb);
    check("link_nlink", st_nlink(), 2);
    sys_unlink(L2);

    /* 6. rename: 旧 path が消え新 path が現れるのが直後の stat に見えるか */
    sys_stat(A, sb);
    check("rename_pre_b", sys_stat(B, sb), -2);   /* B の負キャッシュも載せる */
    sys_rename(A, B);
    check("rename_old_gone", sys_stat(A, sb), -2);
    check("rename_new_seen", sys_stat(B, sb), 0);
    check("rename_new_size", st_size(), 3);

    /* 7. unlink: 削除が直後の stat に見えるか */
    sys_stat(B, sb);
    sys_unlink(B);
    check("unlink_gone", sys_stat(B, sb), -2);

    /* 8. mkdir / rmdir */
    check("mkdir_pre", sys_stat(D, sb), -2);
    sys_mkdir(D, 0755);
    r = sys_stat(D, sb);
    check("mkdir_seen", r, 0);
    check("mkdir_isdir", (long)((*(unsigned int *)(sb + 24) & 0170000) == 0040000), 1);
    sys_rmdir(D);
    check("rmdir_gone", sys_stat(D, sb), -2);

    /* 9. 短時間の再 stat (キャッシュ hit 経路) が同じ値を返すか */
    fd = sys_open(C, O_WRONLY | O_CREAT, 0600);
    sys_write(fd, "cc", 2);
    sys_close(fd);
    sys_stat(C, sb);
    long s1 = st_size(), m1 = st_mode();
    sys_stat(C, sb);
    check("rehit_size", st_size(), s1);
    check("rehit_mode", st_mode(), m1);
    sys_unlink(C);

    sys_exit(0);
}
