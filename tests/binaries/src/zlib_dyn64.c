/* zlib_dyn64.c — zlib 動的リンク回帰固定 (Phase 25 続き)
 *
 * libz.so.1 経由で compress/uncompress 往復が成功することを検証。
 * zlib 内部の SSE 経路で PINSRW (66 0F C4) を踏む。
 *
 * 期待出力:
 *   compress rc=0 srclen=62
 *   uncompress rc=0
 *   round_trip_ok=1
 */
#include <stdio.h>
#include <string.h>
#include <zlib.h>

int main(void) {
    const char *src = "Hello, zlib! This is a compression test. AAAAAAAAAA BBBB CCCC.";
    int srclen = strlen(src);
    Bytef compressed[256];
    uLongf clen = sizeof compressed;
    int rc = compress(compressed, &clen, (const Bytef*)src, srclen);
    printf("compress rc=%d srclen=%d\n", rc, srclen);

    char out[256] = {0};
    uLongf outlen = sizeof out - 1;
    rc = uncompress((Bytef*)out, &outlen, compressed, clen);
    printf("uncompress rc=%d\n", rc);

    printf("round_trip_ok=%d\n", strcmp(src, out) == 0);
    return 0;
}
