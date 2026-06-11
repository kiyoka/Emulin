/* concurrent_fd_dyn64.c — 複数スレッドが同時に open/read/close する fd table 競合テスト (glibc 動的)。
 *
 * issue #221 step 3d-2c-42: go build が並列 goroutine で多数の source file を同時 open/read する際に
 * "bad file descriptor" で落ちる (software/native 共通)。真因候補は FileAccess の fd 確保
 * (search_empty_fd → addElement/setElementAt) が非アトミックで、2 thread が同じ空きスロットを掴む /
 * addElement の index ずれで返した fd が別 Fileinfo (または null) を指す競合。null スロットの fd を
 * read すると -1=EBADF を返す = go の "bad file descriptor"。
 *
 * 本テストは N thread が共有ファイル群を激しく open/read/verify/close して、read が EBADF を返したり
 * 内容が食い違わないことを確認する。正しい fd table なら open_err=0 ebadf=0 mismatch=0。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>

#define NFILES   8
#define NTHREADS 6
#define NITER    500

static char paths[NFILES][64];
static char expected[NFILES][128];
static int  explen[NFILES];
static volatile int open_err = 0;
static volatile int ebadf    = 0;
static volatile int mismatch = 0;

static void *worker(void *arg) {
    long id = (long)arg;
    for (int it = 0; it < NITER; it++) {
        int k = (int)((it + id) % NFILES);
        int fd = open(paths[k], O_RDONLY);
        if (fd < 0) { __sync_fetch_and_add(&open_err, 1); continue; }
        char buf[256];
        int total = 0, n, bad = 0;
        while ((n = read(fd, buf + total, sizeof(buf) - total)) > 0) total += n;
        if (n < 0) { __sync_fetch_and_add(&ebadf, 1); bad = 1; }
        if (!bad && (total != explen[k] || memcmp(buf, expected[k], total) != 0))
            __sync_fetch_and_add(&mismatch, 1);
        close(fd);
    }
    return NULL;
}

int main(void) {
    /* main が事前に逐次でファイルを作る (read-only 並列だけを競合させる = go build と同じ状況)。 */
    for (int k = 0; k < NFILES; k++) {
        snprintf(paths[k], sizeof(paths[k]), "/tmp/cfd_%d.txt", k);
        explen[k] = snprintf(expected[k], sizeof(expected[k]),
                             "file-%d-content-abcdefghijklmnopqrstuvwxyz\n", k);
        int fd = open(paths[k], O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd < 0) { printf("concurrent-fd: setup FAIL\n"); return 1; }
        write(fd, expected[k], explen[k]);
        close(fd);
    }
    pthread_t t[NTHREADS];
    for (long i = 0; i < NTHREADS; i++) pthread_create(&t[i], NULL, worker, (void *)i);
    for (int i = 0; i < NTHREADS; i++) pthread_join(t[i], NULL);
    for (int k = 0; k < NFILES; k++) unlink(paths[k]);
    printf("concurrent-fd: open_err=%d ebadf=%d mismatch=%d\n", open_err, ebadf, mismatch);
    fflush(stdout);
    return 0;
}
