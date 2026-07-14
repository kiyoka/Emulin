/* sys_trailing_slash64.c — issue #722: 末尾 '/' ・'/.' の path はディレクトリを要求する
 * (POSIX)。実体が非ディレクトリなら ENOTDIR。
 *
 * Emulin は Util.realname の正規化で末尾 '/' と '/.' を落とすため、これらが全部
 * 「そのファイル自身」への成功になっていた。emacs の file-accessible-directory-p は
 * faccessat("<P>/.") で実装されており、これが誤成功すると magit の
 * magit--safe-default-directory が「ファイルをディレクトリ」と誤認 → default-directory が
 * ファイルになり、git の chdir が ENOTDIR で死ぬ ("Doing vfork: Not a directory")。
 *
 * 期待値は host Linux の実測 (ENOTDIR=20 / EISDIR=21):
 *   access(file/.)=ENOTDIR  access(file/)=ENOTDIR  faccessat(file/.)=ENOTDIR
 *   stat(file/)=ENOTDIR     lstat(file/)=ENOTDIR   open(file/)=ENOTDIR
 *   open(file/.)=ENOTDIR    unlink(file/)=ENOTDIR  open(nonexist/,O_CREAT)=EISDIR
 *   ディレクトリへの末尾 '/' / '/.' は従来どおり成功 (stat/open/access とも 0)
 *
 * 期待出力:
 *   file: acc.=20 acc/=20 facc=20 stat=20 lstat=20 open=20 opendot=20 unlink=20
 *   nonexist: creat=21
 *   dir: stat=0 statdot=0 acc=0 open=0
 *   TRAILING_SLASH ok
 */
#include "sys64.h"

/* sys64.h に無い分をここで定義 (lstat=6 / faccessat=269) */
static long ts_lstat(const char *path, void *statbuf) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(6LL), "D"(path), "S"(statbuf) : "rcx", "r11", "memory");
    return ret;
}
static long ts_faccessat(long dirfd, const char *path, long mode) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret)
        : "0"(269LL), "D"(dirfd), "S"(path), "d"(mode) : "rcx", "r11");
    return ret;
}

/* 失敗なら -errno、成功なら 0 を返す (rc>=0 は成功) */
static long e(long rc) { return (rc < 0) ? -rc : 0; }

static void kv(const char *k, long v) { put(k); put("="); put_dec(v); }

void _start(void) {
    /* 準備: 通常ファイルとディレクトリ */
    long fd = sys_open("/tmp/tsfile", 0x241 /*WRONLY|CREAT|TRUNC*/, 0644);
    if (fd < 0) { put("setup: open failed\n"); sys_exit(1); }
    sys_write(fd, "x", 1);
    sys_close(fd);
    sys_mkdir("/tmp/tsdir", 0755);

    char st[144];

    put("file: ");
    kv("acc.",    e(sys_access("/tmp/tsfile/.", 0)));            put(" ");
    kv("acc/",    e(sys_access("/tmp/tsfile/", 0)));             put(" ");
    kv("facc",    e(ts_faccessat(-100 /*AT_FDCWD*/, "/tmp/tsfile/.", 0)));  put(" ");
    kv("stat",    e(sys_stat("/tmp/tsfile/", st)));              put(" ");
    kv("lstat",   e(ts_lstat("/tmp/tsfile/", st)));              put(" ");
    kv("open",    e(sys_open("/tmp/tsfile/", 0, 0)));            put(" ");
    kv("opendot", e(sys_open("/tmp/tsfile/.", 0, 0)));           put(" ");
    kv("unlink",  e(sys_unlink("/tmp/tsfile/")));                put("\n");

    put("nonexist: ");
    kv("creat",   e(sys_open("/tmp/tsnone/", 0x41 /*WRONLY|CREAT*/, 0644)));  put("\n");

    put("dir: ");
    kv("stat",    e(sys_stat("/tmp/tsdir/", st)));               put(" ");
    kv("statdot", e(sys_stat("/tmp/tsdir/.", st)));              put(" ");
    kv("acc",     e(sys_access("/tmp/tsdir/.", 0)));             put(" ");
    { long d = sys_open("/tmp/tsdir/", 0 /*O_RDONLY*/, 0);
      kv("open", e(d)); if (d >= 0) sys_close(d); }
    put("\n");

    put("TRAILING_SLASH ok\n");
    sys_exit(0);
}
