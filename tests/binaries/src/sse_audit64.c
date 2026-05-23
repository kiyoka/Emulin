/* sse_audit64.c — issue #87: claude/Bun 対応で追加した SSE/SSE2/SSE3/SSE4.1
 * 命令を host 実 x86 と差分検証するための単体テスト。
 *
 * 各命令を固定入力 (符号・飽和境界を含む) で実行し、結果を hex で stdout に
 * 出力する。同じ binary を host (native) と emulin で実行して diff し、
 * 「実装したが silent に結果が狂う」命令を特定する。
 *
 *   gcc -O0 -msse4.1 -static で build (定数畳み込み回避のため -O0)。
 */
#include <stdio.h>
#include <immintrin.h>

static void d128(const char *name, __m128i v) {
    unsigned char b[16];
    _mm_storeu_si128((__m128i*)b, v);
    printf("%-16s", name);
    for (int i = 0; i < 16; i++) printf("%02x", b[i]);
    printf("\n");
}
static void dps(const char *name, __m128 v) { d128(name, _mm_castps_si128(v)); }
static void dpd(const char *name, __m128d v) { d128(name, _mm_castpd_si128(v)); }
static void dval(const char *name, long v) { printf("%-16s%016lx\n", name, (unsigned long)v); }

int main(void) {
    /* 入力: 符号付き境界・飽和を踏む値を散りばめる */
    __m128i a = _mm_set_epi32(0x80000000, 0x7fffffff, 0xfffffff0, 0x00000010);
    __m128i b = _mm_set_epi32(0x00000005, 0x7ffffffe, 0x00000020, 0xfffffff5);
    __m128i wa = _mm_set_epi16(0x8000, 0x7fff, -3, 100, 0, -1, 0x00ff, 0x0102);
    __m128i wb = _mm_set_epi16(5, -32768, 7, -100, 0xfffe, 1, 0x0080, 0x7f7f);
    __m128i ba = _mm_set_epi8(-128,127,-1,1,0,5,-50,50,10,-10,99,-99,127,-128,3,-3);
    __m128i bb = _mm_set_epi8(5,-5,2,-2,127,-128,50,-50,-10,10,-99,99,1,-1,-3,3);

    d128("in_a:", a); d128("in_b:", b);
    d128("in_wa:", wa); d128("in_wb:", wb);
    d128("in_ba:", ba); d128("in_bb:", bb);

    /* PMIN/PMAX (SSE4.1) */
    d128("PMINSB:",  _mm_min_epi8(ba, bb));
    d128("PMAXSB:",  _mm_max_epi8(ba, bb));
    d128("PMINSD:",  _mm_min_epi32(a, b));
    d128("PMAXSD:",  _mm_max_epi32(a, b));
    d128("PMINUW:",  _mm_min_epu16(wa, wb));
    d128("PMAXUW:",  _mm_max_epu16(wa, wb));
    d128("PMINUD:",  _mm_min_epu32(a, b));
    d128("PMAXUD:",  _mm_max_epu32(a, b));

    /* PADDW / PSUBW */
    d128("PADDW:",   _mm_add_epi16(wa, wb));
    d128("PSUBW:",   _mm_sub_epi16(wa, wb));

    /* PACKSSWB / PACKSSDW */
    d128("PACKSSWB:", _mm_packs_epi16(wa, wb));
    d128("PACKSSDW:", _mm_packs_epi32(a, b));

    /* PBLENDW (imm mask 0b10110100 = 0xB4) */
    d128("PBLENDW:", _mm_blend_epi16(wa, wb, 0xB4));

    /* PEXTR (imm はコンパイル時定数) */
    dval("PEXTRB[5]:",  _mm_extract_epi8(ba, 5));
    dval("PEXTRB[12]:", _mm_extract_epi8(ba, 12));
    dval("PEXTRW[3]:",  _mm_extract_epi16(wa, 3));
    dval("PEXTRW[7]:",  _mm_extract_epi16(wa, 7));
    dval("PEXTRD[1]:",  _mm_extract_epi32(a, 1));
    dval("PEXTRD[3]:",  _mm_extract_epi32(a, 3));
    dval("PEXTRQ[0]:",  _mm_extract_epi64(a, 0));
    dval("PEXTRQ[1]:",  _mm_extract_epi64(a, 1));

    /* PINSRB */
    d128("PINSRB[7]:",  _mm_insert_epi8(ba, 0xAB, 7));
    d128("PINSRB[0]:",  _mm_insert_epi8(ba, 0xCD, 0));

    /* MOVMSKPS */
    dval("MOVMSKPS:", _mm_movemask_ps(_mm_castsi128_ps(a)));

    /* PTEST (ZF / CF) */
    dval("PTESTZ(a,b):", _mm_testz_si128(a, b));
    dval("PTESTC(a,b):", _mm_testc_si128(a, b));
    dval("PTESTZ(a,a):", _mm_testz_si128(a, a));

    /* packed single 算術 */
    __m128 fa = _mm_set_ps(-1.5f, 2.5f, 3.0f, -8.0f);
    __m128 fb = _mm_set_ps(2.0f, -4.0f, 0.5f, 8.0f);
    dps("ADDPS:", _mm_add_ps(fa, fb));
    dps("MULPS:", _mm_mul_ps(fa, fb));
    dps("SUBPS:", _mm_sub_ps(fa, fb));
    dps("MINPS:", _mm_min_ps(fa, fb));
    dps("MAXPS:", _mm_max_ps(fa, fb));
    dps("DIVPS:", _mm_div_ps(fa, fb));
    dps("SQRTPS:", _mm_sqrt_ps(_mm_set_ps(16.0f, 9.0f, 4.0f, 1.0f)));

    /* MOVSLDUP / MOVSHDUP */
    dps("MOVSLDUP:", _mm_moveldup_ps(fa));
    dps("MOVSHDUP:", _mm_movehdup_ps(fa));

    /* CVTTPS2DQ / CVTTPD2DQ / CVTDQ2PD / MOVDDUP */
    d128("CVTTPS2DQ:", _mm_cvttps_epi32(_mm_set_ps(-1.9f, 2.9f, 1e20f, -3.1f)));
    __m128d da = _mm_set_pd(-2.7, 3.7);
    d128("CVTTPD2DQ:", _mm_cvttpd_epi32(da));
    dpd("CVTDQ2PD:", _mm_cvtepi32_pd(_mm_set_epi32(0, 0, -5, 7)));
    dpd("MOVDDUP:", _mm_movedup_pd(_mm_set_pd(1.5, -2.5)));

    /* ROUND (mode: nearest=0, floor=1, ceil=2, trunc=3) */
    __m128d rv = _mm_set_pd(2.5, -2.5);
    dpd("ROUNDPD_near:", _mm_round_pd(rv, 0));
    dpd("ROUNDPD_floor:", _mm_round_pd(rv, 1));
    dpd("ROUNDPD_ceil:", _mm_round_pd(rv, 2));
    dpd("ROUNDPD_trunc:", _mm_round_pd(rv, 3));
    dps("ROUNDPS_floor:", _mm_round_ps(_mm_set_ps(-1.5f, 1.5f, 2.5f, -2.5f), 1));

    /* BLENDV (mask MSB) */
    __m128i mask = _mm_set_epi32(0x80000000, 0, 0xffffffff, 0);
    d128("BLENDVPS:", _mm_castps_si128(_mm_blendv_ps(_mm_castsi128_ps(a), _mm_castsi128_ps(b), _mm_castsi128_ps(mask))));
    d128("PBLENDVB:", _mm_blendv_epi8(a, b, mask));

    /* PMOVSX / PMOVZX */
    d128("PMOVSXBW:", _mm_cvtepi8_epi16(ba));
    d128("PMOVZXBW:", _mm_cvtepu8_epi16(ba));
    d128("PMOVSXBD:", _mm_cvtepi8_epi32(ba));
    d128("PMOVSXWD:", _mm_cvtepi16_epi32(wa));
    d128("PMOVZXWD:", _mm_cvtepu16_epi32(wa));
    d128("PMOVSXDQ:", _mm_cvtepi32_epi64(a));

    /* ---- simdutf (UTF-8→UTF-16) hot path で使う既存 SIMD も検証 ---- */
    d128("PSHUFB:",   _mm_shuffle_epi8(ba, bb));
    d128("PMADDUBSW:",_mm_maddubs_epi16(ba, bb));
    d128("PMADDWD:",  _mm_madd_epi16(wa, wb));
    d128("PCMPEQB:",  _mm_cmpeq_epi8(ba, bb));
    d128("PCMPGTB:",  _mm_cmpgt_epi8(ba, bb));
    d128("PCMPEQW:",  _mm_cmpeq_epi16(wa, wb));
    d128("PCMPGTW:",  _mm_cmpgt_epi16(wa, wb));
    d128("PCMPEQD:",  _mm_cmpeq_epi32(a, b));
    d128("PCMPGTD:",  _mm_cmpgt_epi32(a, b));
    dval("PMOVMSKB:", _mm_movemask_epi8(ba));
    d128("PALIGNR3:", _mm_alignr_epi8(ba, bb, 3));
    d128("PALIGNR11:",_mm_alignr_epi8(ba, bb, 11));
    d128("PADDB:",    _mm_add_epi8(ba, bb));
    d128("PSUBB:",    _mm_sub_epi8(ba, bb));
    d128("PMULLW:",   _mm_mullo_epi16(wa, wb));
    d128("PMULHW:",   _mm_mulhi_epi16(wa, wb));
    d128("PMULHUW:",  _mm_mulhi_epu16(wa, wb));
    d128("PMULHRSW:", _mm_mulhrs_epi16(wa, wb));
    d128("PSIGNB:",   _mm_sign_epi8(ba, bb));
    d128("PSIGNW:",   _mm_sign_epi16(wa, wb));
    d128("PABSB:",    _mm_abs_epi8(ba));
    d128("PABSW:",    _mm_abs_epi16(wa));
    d128("PABSD:",    _mm_abs_epi32(a));
    d128("PHADDW:",   _mm_hadd_epi16(wa, wb));
    d128("PHADDD:",   _mm_hadd_epi32(a, b));
    d128("PHSUBW:",   _mm_hsub_epi16(wa, wb));
    d128("PUNPCKLBW:",_mm_unpacklo_epi8(ba, bb));
    d128("PUNPCKHBW:",_mm_unpackhi_epi8(ba, bb));
    d128("PUNPCKLWD:",_mm_unpacklo_epi16(wa, wb));
    d128("PUNPCKLDQ:",_mm_unpacklo_epi32(a, b));
    d128("PUNPCKLQDQ:",_mm_unpacklo_epi64(a, b));
    d128("PSADBW:",   _mm_sad_epu8(ba, bb));
    d128("PMINUB:",   _mm_min_epu8(ba, bb));
    d128("PMAXUB:",   _mm_max_epu8(ba, bb));
    d128("PMINSW:",   _mm_min_epi16(wa, wb));
    d128("PMAXSW:",   _mm_max_epi16(wa, wb));
    d128("PSLLW3:",   _mm_slli_epi16(wa, 3));
    d128("PSRLW3:",   _mm_srli_epi16(wa, 3));
    d128("PSRAW3:",   _mm_srai_epi16(wa, 3));
    d128("PSLLD5:",   _mm_slli_epi32(a, 5));
    d128("PSRLD5:",   _mm_srli_epi32(a, 5));
    d128("PSRAD5:",   _mm_srai_epi32(a, 5));
    d128("PSLLDQ4:",  _mm_slli_si128(a, 4));
    d128("PSRLDQ4:",  _mm_srli_si128(a, 4));
    d128("PSHUFD:",   _mm_shuffle_epi32(a, 0x4E));
    d128("PSHUFLW:",  _mm_shufflelo_epi16(wa, 0x1B));
    d128("PSHUFHW:",  _mm_shufflehi_epi16(wa, 0x1B));
    d128("PACKUSWB:", _mm_packus_epi16(wa, wb));
    d128("PACKUSDW:", _mm_packus_epi32(a, b));

    return 0;
}
