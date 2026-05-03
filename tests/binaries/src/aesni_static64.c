#include <stdio.h>
#include <string.h>
#include <wmmintrin.h>

void dump(const char *name, __m128i v) {
    unsigned char b[16];
    _mm_storeu_si128((__m128i*)b, v);
    printf("%-20s", name);
    for(int i=0; i<16; i++) printf("%02x", b[i]);
    printf("\n");
}

int main(){
    // FIPS-197 test vectors
    unsigned char state_in[16] = {
        0x32,0x88,0x31,0xe0, 0x43,0x5a,0x31,0x37,
        0xf6,0x30,0x98,0x07, 0xa8,0x8d,0xa2,0x34
    };
    unsigned char rkey[16] = {
        0x2b,0x28,0xab,0x09, 0x7e,0xae,0xf7,0xcf,
        0x15,0xd2,0x15,0x4f, 0x16,0xa6,0x88,0x3c
    };
    
    __m128i s = _mm_loadu_si128((__m128i*)state_in);
    __m128i k = _mm_loadu_si128((__m128i*)rkey);
    
    dump("state_in:",  s);
    dump("rkey:",      k);
    dump("AESENC:",     _mm_aesenc_si128(s, k));
    dump("AESENCLAST:", _mm_aesenclast_si128(s, k));
    dump("AESDEC:",     _mm_aesdec_si128(s, k));
    dump("AESDECLAST:", _mm_aesdeclast_si128(s, k));
    dump("AESIMC:",     _mm_aesimc_si128(k));
    dump("AESKEYGEN(rcon=01):", _mm_aeskeygenassist_si128(k, 0x01));
    
    // PCLMULQDQ
    __m128i a = _mm_set_epi64x(0x1234567890abcdefLL, 0xfedcba0987654321LL);
    __m128i b = _mm_set_epi64x(0x1111111111111111LL, 0x2222222222222222LL);
    dump("PCLMUL(0x00):", _mm_clmulepi64_si128(a, b, 0x00));
    dump("PCLMUL(0x11):", _mm_clmulepi64_si128(a, b, 0x11));
    return 0;
}
