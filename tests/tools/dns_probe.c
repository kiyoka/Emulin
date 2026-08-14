/* issue #926: guest の名前解決が EAI_AGAIN を返すことがある件の測定用プローブ。
 *
 *   codex (musl static) は WebSocket 接続時だけ `getaddrinfo` が EAI_AGAIN ("Try again")
 *   を返すことがある。同じプロセスの HTTP は成功しているので、名前解決の経路差 (musl の
 *   getaddrinfo か、その下の UDP DNS) を疑っている。
 *
 *   本プローブは 2 つのモードで失敗率を測る:
 *     libc  … libc の getaddrinfo をそのまま呼ぶ (このバイナリは glibc)
 *     raw   … **musl と同じ形** = 1 つの UDP socket から /etc/resolv.conf の
 *             全 nameserver へ同時に問い合わせ、poll() で最初の応答を採る
 *
 *   raw だけ落ちるなら「複数宛先を 1 socket で扱う経路」(Emulin の UDP) が怪しい。
 *   両方落ちるなら UDP DNS 全般。両方通るなら musl 固有の別要因。
 *
 *   ★ これは**測定ツール**であって回帰テストではない (公開リゾルバへの実通信が要り、
 *     結果がネットワーク状況に依存するため)。tests/binaries/src/ に置くと run-all の
 *     自動発見に拾われて SKIP になるので tests/tools/ に置く。
 *
 *   ビルド: gcc -O1 -static -o /tmp/dns_probe tests/tools/dns_probe.c
 *   使い方: dns_probe <libc|raw> <回数> [ホスト名]
 *           DNS_PROBE_FDS=N を付けると先に N 個の socket を開いて fd 圧迫下で測る
 *   出力  : 最後に "RESULT mode=.. n=.. fail=.. ..." の 1 行 (機械可読)
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <time.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

static double now_ms( void )
{
    struct timespec t;
    clock_gettime( CLOCK_MONOTONIC, &t );
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

/* /etc/resolv.conf の nameserver を最大 3 つ読む (musl と同じ上限) */
static int read_nameservers( struct sockaddr_in ns[3] )
{
    FILE *f = fopen( "/etc/resolv.conf", "r" );
    if( !f ) return 0;
    char line[512];
    int n = 0;
    while( n < 3 && fgets( line, sizeof(line), f ) ) {
        char ip[128];
        if( sscanf( line, " nameserver %120s", ip ) != 1 ) continue;
        memset( &ns[n], 0, sizeof(ns[n]) );
        ns[n].sin_family = AF_INET;
        ns[n].sin_port   = htons( 53 );
        if( inet_pton( AF_INET, ip, &ns[n].sin_addr ) == 1 ) n++;
    }
    fclose( f );
    return n;
}

/* 最小の A クエリを組み立てる */
static int build_query( unsigned char *buf, const char *host, unsigned short id )
{
    int p = 0;
    buf[p++] = id >> 8; buf[p++] = id & 0xff;
    buf[p++] = 0x01; buf[p++] = 0x00;      /* RD */
    buf[p++] = 0x00; buf[p++] = 0x01;      /* QDCOUNT=1 */
    buf[p++] = 0x00; buf[p++] = 0x00;
    buf[p++] = 0x00; buf[p++] = 0x00;
    buf[p++] = 0x00; buf[p++] = 0x00;
    const char *s = host;
    while( *s ) {
        const char *dot = strchr( s, '.' );
        int len = dot ? (int)( dot - s ) : (int)strlen( s );
        if( len <= 0 || len > 63 ) return -1;
        buf[p++] = (unsigned char)len;
        memcpy( buf + p, s, len ); p += len;
        s = dot ? dot + 1 : s + len;
    }
    buf[p++] = 0;
    buf[p++] = 0x00; buf[p++] = 0x01;      /* QTYPE=A */
    buf[p++] = 0x00; buf[p++] = 0x01;      /* QCLASS=IN */
    return p;
}

/* musl 相当: 1 socket から全 nameserver へ送り、poll で応答を待つ */
static int resolve_raw( const char *host, int timeout_ms, const struct sockaddr_in *ns, int nns )
{
    int fd = socket( AF_INET, SOCK_DGRAM, 0 );
    if( fd < 0 ) return -1;
    unsigned char q[512];
    unsigned short id = (unsigned short)( now_ms() );
    int qlen = build_query( q, host, id );
    if( qlen < 0 ) { close( fd ); return -1; }
    for( int i = 0; i < nns; i++ )
        (void)sendto( fd, q, qlen, 0, (const struct sockaddr *)&ns[i], sizeof(ns[i]) );

    double deadline = now_ms() + timeout_ms;
    for( ;; ) {
        int remain = (int)( deadline - now_ms() );
        if( remain <= 0 ) { close( fd ); return 0; }        /* timeout = 応答なし */
        struct pollfd p = { .fd = fd, .events = POLLIN };
        int r = poll( &p, 1, remain );
        if( r < 0 ) { if( errno == EINTR ) continue; close( fd ); return -1; }
        if( r == 0 ) { close( fd ); return 0; }
        unsigned char a[1500];
        ssize_t n = recv( fd, a, sizeof(a), 0 );
        if( n < 12 ) continue;
        if( ( (a[0] << 8) | a[1] ) != id ) continue;        /* 別の応答 */
        int ancount = ( a[6] << 8 ) | a[7];
        close( fd );
        return ancount > 0 ? 1 : 0;
    }
}

int main( int argc, char **argv )
{
    const char *mode = ( argc > 1 ) ? argv[1] : "libc";
    int n            = ( argc > 2 ) ? atoi( argv[2] ) : 50;
    const char *host = ( argc > 3 ) ? argv[3] : "chatgpt.com";
    int israw = ( strcmp( mode, "raw" ) == 0 );

    /* issue #926: fd 圧迫下での挙動も見る (codex は多数の socket を抱えている)。
       DNS_PROBE_FDS=N で先に N 個の socket を開いてから測る。 */
    int hold = 0;
    { const char *e = getenv( "DNS_PROBE_FDS" ); if( e ) hold = atoi( e ); }
    int *held = NULL;
    if( hold > 0 ) {
        held = calloc( hold, sizeof(int) );
        int got = 0;
        for( int i = 0; i < hold; i++ ) {
            held[i] = socket( AF_INET, SOCK_DGRAM, 0 );
            if( held[i] >= 0 ) got++;
        }
        fprintf( stderr, "  (fd 圧迫: %d/%d 個の socket を確保)\n", got, hold );
    }

    struct sockaddr_in ns[3];
    int nns = read_nameservers( ns );
    if( israw && nns == 0 ) { printf( "SKIP dns_probe : /etc/resolv.conf に nameserver が無い\n" ); return 2; }

    int fail = 0, again = 0;
    double worst = 0, total = 0;
    for( int i = 0; i < n; i++ ) {
        double t0 = now_ms();
        int ok;
        if( israw ) {
            ok = ( resolve_raw( host, 5000, ns, nns ) == 1 );
        } else {
            struct addrinfo hints, *res = NULL;
            memset( &hints, 0, sizeof(hints) );
            hints.ai_family   = AF_INET;
            hints.ai_socktype = SOCK_STREAM;
            int rc = getaddrinfo( host, "443", &hints, &res );
            ok = ( rc == 0 );
            if( rc == EAI_AGAIN ) again++;
            if( !ok && rc != EAI_AGAIN )
                fprintf( stderr, "  [%d] getaddrinfo rc=%d (%s)\n", i, rc, gai_strerror( rc ) );
            if( res ) freeaddrinfo( res );
        }
        double d = now_ms() - t0;
        total += d;
        if( d > worst ) worst = d;
        if( !ok ) { fail++; fprintf( stderr, "  [%d] FAIL (%.0f ms)\n", i, d ); }
        usleep( 100 * 1000 );          /* 公開リゾルバに優しく */
    }
    printf( "RESULT mode=%s n=%d fail=%d eai_again=%d avg=%.0fms worst=%.0fms ns=%d\n",
            mode, n, fail, again, total / n, worst, nns );
    return fail > 0 ? 1 : 0;
}
