/* mmap_dyn64.c — 通常ファイルの mmap 回帰固定 (Phase 25 続き)
 *
 * MAP_PRIVATE / PROT_READ で /tmp 上のファイルをメモリにマップし、
 * 直接ポインタ参照で内容が読めることを検証。
 * Linux と同様、length はページ境界に切り上げてマップする。
 *
 * 期待出力:
 *   mapped: abcdefghij
 */
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <string.h>

int main(void) {
    int fd = open("/tmp/mtest.dat", O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { perror("open"); return 1; }
    write(fd, "abcdefghij", 10);

    void *p = mmap(NULL, 10, PROT_READ, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) { perror("mmap"); close(fd); return 1; }

    printf("mapped: %.10s\n", (char *)p);

    munmap(p, 10);
    close(fd);
    unlink("/tmp/mtest.dat");
    return 0;
}
