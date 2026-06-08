/* dirlist_dyn64.c — 動的リンク (gcc -no-pie) の「coreutils 風」総合テスト
 *
 * issue #221 step 3d-2c-12: native (KVM) backend を実用 dynamic binary に近い syscall
 * surface で検証する。既存の _dyn64 テスト (hello/printf/regex/pthread 等) が触らない
 * ディレクトリ列挙 (opendir/readdir = getdents64) / getcwd / mkdir / stat (newfstatat) /
 * file 作成・読み書き / unlink / rmdir を 1 本で通す。glibc の malloc/qsort も経由する。
 *
 * /tmp/dlt64 を自分で作って files を置き、readdir で列挙 → sort → 内容 read までやるので
 * 出力は環境非依存に決まる (hermetic)。
 *
 * 期待出力:
 *   cwd_ok=1
 *   entries: a.txt b.txt c.txt
 *   size a.txt: 6
 *   content: hello
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/stat.h>

static int cmp( const void *a, const void *b ) {
    return strcmp( *(const char * const *)a, *(const char * const *)b );
}

int main( void ) {
    char cwd[4096];
    if( getcwd( cwd, sizeof cwd ) == NULL ) { perror( "getcwd" ); return 1; }
    printf( "cwd_ok=%d\n", cwd[0] == '/' );        /* 決定的: 1 */

    const char *dir = "/tmp/dlt64";
    mkdir( dir, 0755 );
    const char *names[] = { "b.txt", "a.txt", "c.txt" };  /* わざと非ソート順で作る */
    for( int i = 0; i < 3; i++ ) {
        char path[256];
        snprintf( path, sizeof path, "%s/%s", dir, names[i] );
        int fd = open( path, O_WRONLY | O_CREAT | O_TRUNC, 0644 );
        if( fd < 0 ) { perror( "open w" ); return 1; }
        write( fd, "hello\n", 6 );
        close( fd );
    }

    /* opendir/readdir = getdents64 → 列挙して sort */
    DIR *d = opendir( dir );
    if( !d ) { perror( "opendir" ); return 1; }
    char *ents[64];
    int n = 0;
    struct dirent *de;
    while( ( de = readdir( d ) ) != NULL ) {
        if( de->d_name[0] == '.' ) continue;        /* "." ".." を除く */
        ents[n++] = strdup( de->d_name );
    }
    closedir( d );
    qsort( ents, n, sizeof( char * ), cmp );
    printf( "entries:" );
    for( int i = 0; i < n; i++ ) printf( " %s", ents[i] );
    printf( "\n" );

    /* stat = newfstatat */
    char path[256];
    snprintf( path, sizeof path, "%s/a.txt", dir );
    struct stat st;
    if( stat( path, &st ) != 0 ) { perror( "stat" ); return 1; }
    printf( "size a.txt: %ld\n", (long)st.st_size );

    /* 内容 read */
    int fd = open( path, O_RDONLY );
    char buf[64];
    ssize_t r = read( fd, buf, sizeof buf - 1 );
    close( fd );
    if( r < 0 ) r = 0;
    buf[r] = 0;
    if( r > 0 && buf[r - 1] == '\n' ) buf[r - 1] = 0;   /* 末尾改行を除く */
    printf( "content: %s\n", buf );

    /* cleanup */
    for( int i = 0; i < 3; i++ ) {
        snprintf( path, sizeof path, "%s/%s", dir, names[i] );
        unlink( path );
    }
    rmdir( dir );
    for( int i = 0; i < n; i++ ) free( ents[i] );
    return 0;
}
