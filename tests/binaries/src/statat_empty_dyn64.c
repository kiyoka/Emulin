/* statat_empty_dyn64.c — fstatat(2) の空 path の errno 検証 (glibc 動的)。
 *
 * issue #221 step 3d-2c-42: emulin は newfstatat の空 path を無条件に「dirfd 自身を fstat」扱いに
 * していて、Go の os.Stat("") = fstatat(AT_FDCWD,"",buf,0) を fstat(-100)=EBADF にしていた。Linux は
 * 空 path + AT_EMPTY_PATH 無し → ENOENT、AT_EMPTY_PATH 有 → dirfd 自身を stat。Go は ENOENT を
 * os.IsNotExist で握って先へ進む設計なので、EBADF だと go build が "bad file descriptor" で落ちる。
 *
 * 本テストは (1) fstatat(AT_FDCWD,"",&st,0) が -1/ENOENT (2) AT_EMPTY_PATH 付きは成功 を確認する。
 * software / native 両 backend で byte 一致 "statat-empty: noflag_errno=2 emptypath_ok=1"。
 */
#define _GNU_SOURCE   /* AT_EMPTY_PATH */
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

int main(void) {
    struct stat st;

    /* (1) 空 path + flags=0 → ENOENT(2) を期待 (Linux 準拠)。 */
    errno = 0;
    int r1 = fstatat(AT_FDCWD, "", &st, 0);
    int noflag_errno = (r1 == -1) ? errno : 0;

    /* (2) 空 path + AT_EMPTY_PATH → 実 fd (ここでは現在の作業 dir を開いた fd) を stat。
     *     成功 (0) を期待。dirfd は実在ディレクトリ fd を渡す。 */
    int dfd = open(".", O_RDONLY | O_DIRECTORY);
    int r2 = (dfd >= 0) ? fstatat(dfd, "", &st, AT_EMPTY_PATH) : -1;
    int emptypath_ok = (r2 == 0) ? 1 : 0;
    if (dfd >= 0) close(dfd);

    printf("statat-empty: noflag_errno=%d emptypath_ok=%d\n", noflag_errno, emptypath_ok);
    fflush(stdout);
    return 0;
}
