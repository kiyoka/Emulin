// ----------------------------------------
//  Cpu64: x86-64 CPU エミュレータ
//
//  対応命令 (Phase 4-6):
//    プレフィックス: REX, 66/67, 64(FS)/65(GS), 2E/3E/F0/F2, F3(REP)
//    ENDBR64 / NOP / LEAVE / RET / HLT(→exit)
//    PUSH/POP r64, PUSH r/m64 (FF/6), PUSH imm8/imm32
//    MOV r/m64↔r64, MOV r/m64←imm32s, MOV r32←imm32u, MOV r64←imm64
//    MOV r/m8↔r8, MOV r/m8←imm8
//    ALU r/m←r, r←r/m (ADD/OR/AND/SUB/CMP, 32/64-bit)
//    ALU imm forms (81/83): ADD/OR/AND/SUB/XOR/CMP
//    ALU 8-bit: XOR/CMP/TEST r/m8
//    XOR r/m64←r64 (31), r64←r/m64 (33)
//    TEST r/m, r (84/85)
//    MOVSXD (63), MOVSX/MOVZX (0F BE/B6/BF/B7)
//    IMUL (6B/69/0F AF)
//    LEA, CALL rel32, CALL r/m64 (FF/2), JMP rel8/rel32, JMP r/m64 (FF/4)
//    Jcc rel8/rel32 (70-7F / 0F 80-8F)
//    CMOVcc (0F 40-4F)
//    NOT/NEG/MUL/IMUL/DIV/IDIV (F6/F7)
//    INC/DEC r/m (FF/0, FF/1)
//    CDQE (98), CQO (99)
//    SYSCALL (0F 05)
//    REP STOS/MOVS (F3 AA/AB/A4/A5)
//    REPNZ SCAS (F2/F3 AE/AF — simplified)
//    Multi-byte NOP (0F 1F)
// ----------------------------------------
package emulin;

public class Cpu64 extends AbstractCpu
{
  // 16 本の 64-bit 汎用レジスタ (AMD64 エンコーディングと同じ順序)
  static final int R_RAX = 0, R_RCX = 1, R_RDX = 2, R_RBX = 3;
  static final int R_RSP = 4, R_RBP = 5, R_RSI = 6, R_RDI = 7;
  static final int NREGS = 16;

  // Phase 34-A3: emulin.jit から生成 bytecode が直接 r64 を読み書きするので
  // public にしている (package-private だと別 package の generated class
  // から見えない)。
  public long[] r64;
  long   rip;
  long   fs_base;

  // Phase 34-A3: x86-64 → Java bytecode 翻訳器。EMULIN_USE_JIT=1 で有効化。
  // disabled 時は null (static final boolean dead-code 化で perf overhead ゼロ)。
  private final emulin.jit.Translator translator =
    emulin.jit.Translator.ENABLED ? new emulin.jit.Translator() : null;

  // Phase 34-A3 step 23: JIT path で「直前命令が制御転送だったか」のフラグ。
  // eval() loop と jitStep() で共有するため field 化。
  private boolean jit_lookup_next = true;
  // Phase 34-A5: emulin.jit から SIMD 命令の生成 bytecode が直接 read/write
  // するため public 化。
  public long[] xmm_lo = new long[16];  // XMM0-15 下位 64bit
  public long[] xmm_hi = new long[16];  // XMM0-15 上位 64bit

  // Phase 27 step 62: 命令バイトの per-thread prefetch buffer。
  //   decode_and_exec の prefix scan / opcode read で連続して mem.load8(pc)
  //   を呼ぶ overhead を削減。pc が buffer 外に出たら refill (mem.load8 を
  //   16 回呼ぶ)。命令長平均 3-4 byte なので 4-5 命令ごとに refill。
  //   Cpu64 instance field なので thread 安全 (pthread でも各 Thread64 が
  //   独立した Cpu64 を持つ)。
  private static final int INSN_BUF_SIZE = 16;
  private final byte[] insn_buf = new byte[INSN_BUF_SIZE];
  private long insn_buf_base = -1L;

  // Phase 34-A2: per-RIP prefix cache。decode_and_exec の prefix scan
  // 結果を rip ごとに保存し、同じ命令を再実行する際は scan を skip する。
  // 1 entry = packed int: low 4 bits = opcode offset (0..15)、bits 4-15 =
  // prefix flags (REX_W/R/X/B/REX_PRESENT/OP66/OPF2/FS_PREFIX 等)。
  // HashMap よりも line-direct-mapped array の方が高速。size は 2^N の
  // 直接 mod hash (= rip & mask)。conflict は overwrite (LRU っぽい)。
  private static final int PFXCACHE_SIZE = 1 << 14;  // 16K entries
  private static final int PFXCACHE_MASK = PFXCACHE_SIZE - 1;
  private final long[] pfx_cache_rip = new long[PFXCACHE_SIZE];  // 0 = empty
  private final int[]  pfx_cache_info = new int[PFXCACHE_SIZE];

  // packing:
  //   bits 0..3   : opcode offset (0..15)
  //   bit 4..10   : flags (rex_w/r/x/b, rex_present, op66, opF2, fs_prefix)
  static final int PFX_OFFSET_MASK = 0xF;
  static final int PFX_REX_W       = 1 << 4;
  static final int PFX_REX_R       = 1 << 5;
  static final int PFX_REX_X       = 1 << 6;
  static final int PFX_REX_B       = 1 << 7;
  static final int PFX_REX_PRESENT = 1 << 8;
  static final int PFX_OP66        = 1 << 9;
  static final int PFX_OPF2        = 1 << 10;
  static final int PFX_FS          = 1 << 11;
  static final int PFX_VALID       = 1 << 31;  // この bit が立ってないと cache 無効

  // Phase 34-A2 step 20: per-RIP decoded ModRM cache。
  //   decodeModRM の結果 (mrm_mod / mrm_reg / mrm_rm + ea 計算式 + 命令長) を
  //   ModRM byte の絶対 address keyed で cache し、再実行時に ModRM/SIB/disp
  //   byte 読込みを skip する。mrm_ea は register 値依存なので「計算式」を
  //   cache (base/index/scale/disp + has_base/has_index flag) し、HIT 時に
  //   現在の register 値で評価する。
  //
  //   info encoding (32-bit packed):
  //     bits 0..1   kind (REG / RIP_REL / REG_DISP / SIB)
  //     bits 2..3   mrm_mod
  //     bits 4..7   mrm_reg (0-15)
  //     bits 8..11  mrm_rm  (0-15、KIND_RIP_REL では未使用 — HIT 時に -1 を直接 set)
  //     bits 12..15 SIB base reg (0-15)
  //     bits 16..19 SIB index reg (0-15)
  //     bits 20..21 SIB scale (0..3)
  //     bit  22     has_base (SIB only)
  //     bit  23     has_index (SIB only)
  //     bits 24..28 next_off (ModRM byte からの命令長、5 bit = 最大 31)
  //     bit  31     VALID
  private static final int DEC_KIND_REG      = 0;
  private static final int DEC_KIND_RIP_REL  = 1;
  private static final int DEC_KIND_REG_DISP = 2;
  private static final int DEC_KIND_SIB      = 3;
  private static final int DEC_HAS_BASE      = 1 << 22;
  private static final int DEC_HAS_INDEX     = 1 << 23;
  private static final int DEC_VALID         = 1 << 31;
  private static final int DECCACHE_SIZE     = 1 << 14;  // 16K entries
  private static final int DECCACHE_MASK     = DECCACHE_SIZE - 1;
  private final long[] dec_cache_pc   = new long[DECCACHE_SIZE];
  private final int[]  dec_cache_info = new int[DECCACHE_SIZE];
  private final int[]  dec_cache_disp = new int[DECCACHE_SIZE];  // signed 32-bit displacement

  // 命中率計測用 (EMULIN_PROFILE_OP=1 時のみ累積)
  private static long dec_cache_hits   = 0;
  private static long dec_cache_misses = 0;

  private final void refillInsnBuf( long pc ) {
    insn_buf_base = pc;
    // Phase 34-A4-perf (issue #4): 16 回 mem.load8 ループを 1 発 arraycopy に。
    // text segment 内連続実行が hot path で、lastSegment cache hit すれば
    // System.arraycopy 1 発で済み load8 method call overhead が消える。
    mem.bulkLoad( pc, insn_buf, INSN_BUF_SIZE );
  }
  // pc 位置の 1 byte を読む (fast path: buffer 内なら配列アクセスのみ)
  private final int fetchInsnByte( long pc ) {
    long off = pc - insn_buf_base;
    if( off < 0 || off >= INSN_BUF_SIZE ) {
      refillInsnBuf( pc );
      off = 0;
    }
    return insn_buf[(int)off] & 0xFF;
  }
  // x87 FPU 状態 (最小限のスタブ実装)
  // 64-bit Linux では float/double は SSE で扱うため x87 は startup
  // 周辺の制御 (fnstcw/fldcw/fninit) と例外なしストア程度のみ必要。
  int    fpu_cw = 0x037F;  // FPU control word (default: round-to-nearest, all exceptions masked)
  int    fpu_sw = 0;       // FPU status word
  int    fpu_tag = 0xFFFF; // FPU tag word (all empty)

  SyscallAmd64 syscall64;

  // シグナルハンドラ復帰用トランポリン。ハンドラの ret でここに着地させて、
  // eval ループで保存済みレジスタを復元する。ユーザ空間に絶対に存在しない
  // 値ならよい (上位 16bit が全 1 = カーネル空間相当)。
  private static final long SIGRETURN_TRAMPOLINE = 0xFFFFFFFFFFFEDEADL;

  // Phase 34-A2 step 19: opcode 分布プロファイラ。EMULIN_PROFILE_OP=1 で
  // 有効化。shutdown hook で「single-byte opcode / 0F escape sub-opcode /
  // F3 prefix sub-opcode」をそれぞれ count 降順で top 25 dump する。
  // A3 (ASM bytecode emission) でどの opcode を優先 emit すべきか決める
  // ための一次計測。race tolerant (++ on long, no sync) で counter overhead
  // を最小化。
  private static final boolean PROFILE_OP = System.getenv("EMULIN_PROFILE_OP") != null;
  private static final long[] OP_COUNT     = new long[ 256 ];  // post-prefix b0
  private static final long[] OP_0F_COUNT  = new long[ 256 ];  // 0F XX sub-opcode
  private static final long[] OP_F3_COUNT  = new long[ 256 ];  // F3 XX sub-opcode
  static {
    if( PROFILE_OP ) {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        long total = 0L;
        for( int i = 0; i < 256; i++ ) total += OP_COUNT[ i ];
        System.err.println( "===== EMULIN_PROFILE_OP =====" );
        System.err.println( String.format(
          "instructions dispatched: total=%d", total ) );
        dumpOpHist( "single-byte opcode (post-prefix b0)", OP_COUNT, total, 25 );
        long total_0f = 0L;
        for( int i = 0; i < 256; i++ ) total_0f += OP_0F_COUNT[ i ];
        dumpOpHist( "0F XX sub-opcode (b1)", OP_0F_COUNT, total_0f, 20 );
        long total_f3 = 0L;
        for( int i = 0; i < 256; i++ ) total_f3 += OP_F3_COUNT[ i ];
        dumpOpHist( "F3 XX sub-opcode (b_op)", OP_F3_COUNT, total_f3, 20 );
        long dec_total = dec_cache_hits + dec_cache_misses;
        double dec_pct = dec_total > 0 ? 100.0 * dec_cache_hits / dec_total : 0.0;
        System.err.println( String.format(
          "ModRM decode cache: hits=%d misses=%d (%.2f%% hit rate)",
          dec_cache_hits, dec_cache_misses, dec_pct ) );
        System.err.println( "=============================" );
      }, "EmulinProfileOpDump" ) );
    }
  }
  private static void dumpOpHist( String label, long[] cnt, long total, int topN ) {
    System.err.println( "----- " + label + " (sum=" + total + ") -----" );
    if( total == 0 ) { System.err.println( "  (no entries)" ); return; }
    Integer[] idx = new Integer[ 256 ];
    for( int i = 0; i < 256; i++ ) idx[ i ] = i;
    java.util.Arrays.sort( idx, ( a, b ) -> Long.compare( cnt[ b ], cnt[ a ] ) );
    System.err.println( "  opcode    count       pct" );
    for( int k = 0; k < topN; k++ ) {
      int op = idx[ k ];
      long c = cnt[ op ];
      if( c == 0 ) break;
      double pct = 100.0 * c / total;
      System.err.println( String.format(
        "  0x%02X    %10d  %6.2f%%", op, c, pct ) );
    }
  }
  private final java.util.ArrayDeque<long[]> sigSavedFrames = new java.util.ArrayDeque<>();

  // AES-NI 用 S-box / 逆 S-box (FIPS-197 準拠)
  private static final int[] AES_SBOX = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
  };
  private static final int[] AES_INV_SBOX = {
    0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
    0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb,
    0x54,0x7b,0x94,0x32,0xa6,0xc2,0x23,0x3d,0xee,0x4c,0x95,0x0b,0x42,0xfa,0xc3,0x4e,
    0x08,0x2e,0xa1,0x66,0x28,0xd9,0x24,0xb2,0x76,0x5b,0xa2,0x49,0x6d,0x8b,0xd1,0x25,
    0x72,0xf8,0xf6,0x64,0x86,0x68,0x98,0x16,0xd4,0xa4,0x5c,0xcc,0x5d,0x65,0xb6,0x92,
    0x6c,0x70,0x48,0x50,0xfd,0xed,0xb9,0xda,0x5e,0x15,0x46,0x57,0xa7,0x8d,0x9d,0x84,
    0x90,0xd8,0xab,0x00,0x8c,0xbc,0xd3,0x0a,0xf7,0xe4,0x58,0x05,0xb8,0xb3,0x45,0x06,
    0xd0,0x2c,0x1e,0x8f,0xca,0x3f,0x0f,0x02,0xc1,0xaf,0xbd,0x03,0x01,0x13,0x8a,0x6b,
    0x3a,0x91,0x11,0x41,0x4f,0x67,0xdc,0xea,0x97,0xf2,0xcf,0xce,0xf0,0xb4,0xe6,0x73,
    0x96,0xac,0x74,0x22,0xe7,0xad,0x35,0x85,0xe2,0xf9,0x37,0xe8,0x1c,0x75,0xdf,0x6e,
    0x47,0xf1,0x1a,0x71,0x1d,0x29,0xc5,0x89,0x6f,0xb7,0x62,0x0e,0xaa,0x18,0xbe,0x1b,
    0xfc,0x56,0x3e,0x4b,0xc6,0xd2,0x79,0x20,0x9a,0xdb,0xc0,0xfe,0x78,0xcd,0x5a,0xf4,
    0x1f,0xdd,0xa8,0x33,0x88,0x07,0xc7,0x31,0xb1,0x12,0x10,0x59,0x27,0x80,0xec,0x5f,
    0x60,0x51,0x7f,0xa9,0x19,0xb5,0x4a,0x0d,0x2d,0xe5,0x7a,0x9f,0x93,0xc9,0x9c,0xef,
    0xa0,0xe0,0x3b,0x4d,0xae,0x2a,0xf5,0xb0,0xc8,0xeb,0xbb,0x3c,0x83,0x53,0x99,0x61,
    0x17,0x2b,0x04,0x7e,0xba,0x77,0xd6,0x26,0xe1,0x69,0x14,0x63,0x55,0x21,0x0c,0x7d
  };

  // GF(2^8) 倍算 (irreducible polynomial = 0x11B)
  private static int gfmul( int a, int b ) {
    int r = 0;
    for( int i = 0; i < 8; i++ ) {
      if( (b & 1) != 0 ) r ^= a;
      boolean hi = (a & 0x80) != 0;
      a = (a << 1) & 0xFF;
      if( hi ) a ^= 0x1B;
      b >>>= 1;
    }
    return r & 0xFF;
  }

  // 128-bit AES state を long[2] (lo, hi) で扱う。byte 配列に展開して操作後、
  // long[2] に戻す。bytes[] index は AES の標準順 (column-major, bytes[r + 4*c])。
  private static byte[] aesUnpack( long lo, long hi ) {
    byte[] s = new byte[16];
    for( int i = 0; i < 8; i++ ) s[i]   = (byte)((lo >>> (i*8)) & 0xFF);
    for( int i = 0; i < 8; i++ ) s[i+8] = (byte)((hi >>> (i*8)) & 0xFF);
    return s;
  }
  private static long aesPackLo( byte[] s ) {
    long v = 0;
    for( int i = 0; i < 8; i++ ) v |= ((long)(s[i]   & 0xFF)) << (i*8);
    return v;
  }
  private static long aesPackHi( byte[] s ) {
    long v = 0;
    for( int i = 0; i < 8; i++ ) v |= ((long)(s[i+8] & 0xFF)) << (i*8);
    return v;
  }

  private static void aesSubBytes( byte[] s ) {
    for( int i = 0; i < 16; i++ ) s[i] = (byte)AES_SBOX[s[i] & 0xFF];
  }
  private static void aesInvSubBytes( byte[] s ) {
    for( int i = 0; i < 16; i++ ) s[i] = (byte)AES_INV_SBOX[s[i] & 0xFF];
  }
  // ShiftRows: 行 r を左に r バイト循環シフト (state は column-major、bytes[r + 4*c])
  private static void aesShiftRows( byte[] s ) {
    byte t;
    // row 1: (1,5,9,13) → (5,9,13,1)
    t = s[1];  s[1]=s[5];   s[5]=s[9];   s[9]=s[13];   s[13]=t;
    // row 2: swap pairs (2,10) and (6,14)
    t = s[2];  s[2]=s[10];  s[10]=t;
    t = s[6];  s[6]=s[14];  s[14]=t;
    // row 3: (3,7,11,15) → (15,3,7,11) (右に 1 = 左に 3)
    t = s[15]; s[15]=s[11]; s[11]=s[7]; s[7]=s[3]; s[3]=t;
  }
  private static void aesInvShiftRows( byte[] s ) {
    byte t;
    // row 1: 右に 1
    t = s[13]; s[13]=s[9]; s[9]=s[5]; s[5]=s[1]; s[1]=t;
    // row 2: 同じ (swap pairs)
    t = s[2];  s[2]=s[10];  s[10]=t;
    t = s[6];  s[6]=s[14];  s[14]=t;
    // row 3: 右に 3 = 左に 1
    t = s[3];  s[3]=s[7];   s[7]=s[11];  s[11]=s[15]; s[15]=t;
  }
  // MixColumns: 各列に [2,3,1,1; 1,2,3,1; 1,1,2,3; 3,1,1,2] を GF(2^8) で乗算
  private static void aesMixColumns( byte[] s ) {
    for( int c = 0; c < 4; c++ ) {
      int a0 = s[4*c+0] & 0xFF, a1 = s[4*c+1] & 0xFF;
      int a2 = s[4*c+2] & 0xFF, a3 = s[4*c+3] & 0xFF;
      s[4*c+0] = (byte)(gfmul(a0,2) ^ gfmul(a1,3) ^ a2 ^ a3);
      s[4*c+1] = (byte)(a0 ^ gfmul(a1,2) ^ gfmul(a2,3) ^ a3);
      s[4*c+2] = (byte)(a0 ^ a1 ^ gfmul(a2,2) ^ gfmul(a3,3));
      s[4*c+3] = (byte)(gfmul(a0,3) ^ a1 ^ a2 ^ gfmul(a3,2));
    }
  }
  // InvMixColumns: 逆行列 [0e,0b,0d,09; 09,0e,0b,0d; 0d,09,0e,0b; 0b,0d,09,0e]
  private static void aesInvMixColumns( byte[] s ) {
    for( int c = 0; c < 4; c++ ) {
      int a0 = s[4*c+0] & 0xFF, a1 = s[4*c+1] & 0xFF;
      int a2 = s[4*c+2] & 0xFF, a3 = s[4*c+3] & 0xFF;
      s[4*c+0] = (byte)(gfmul(a0,0x0e) ^ gfmul(a1,0x0b) ^ gfmul(a2,0x0d) ^ gfmul(a3,0x09));
      s[4*c+1] = (byte)(gfmul(a0,0x09) ^ gfmul(a1,0x0e) ^ gfmul(a2,0x0b) ^ gfmul(a3,0x0d));
      s[4*c+2] = (byte)(gfmul(a0,0x0d) ^ gfmul(a1,0x09) ^ gfmul(a2,0x0e) ^ gfmul(a3,0x0b));
      s[4*c+3] = (byte)(gfmul(a0,0x0b) ^ gfmul(a1,0x0d) ^ gfmul(a2,0x09) ^ gfmul(a3,0x0e));
    }
  }

  // AESKEYGENASSIST 用の SubWord / RotWord
  private static int aesSubWord( int w ) {
    return  AES_SBOX[ w        & 0xFF]
         | (AES_SBOX[(w >>> 8)  & 0xFF] << 8)
         | (AES_SBOX[(w >>> 16) & 0xFF] << 16)
         | (AES_SBOX[(w >>> 24) & 0xFF] << 24);
  }
  private static int aesRotWord( int w ) {
    return ((w >>> 8) | (w << 24));
  }

  // ModRM デコード結果
  private int  mrm_mod, mrm_reg, mrm_rm;
  private long mrm_ea;
  // REX prefix presence (for 8-bit reg encoding: with REX, rm=4-7 means SPL/BPL/SIL/DIL;
  // without REX, rm=4-7 means AH/CH/DH/BH)
  private boolean rex_present;

  public Cpu64( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
    init();
  }

  @Override
  public void init() {
    r64 = new long[NREGS];
    rip = 0;
    fs_base = 0;
    interrupt_done = false;
    of = sf = zf = cf = 0;
  }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    Cpu64 c = new Cpu64( sysinfo, _process );
    copy_state_into( c );
    return c;
  }

  // Phase 27 step 28: clone(CLONE_VM) で子 Cpu64 に親の register state を移す。
  //   xmm レジスタも含む全 GPR + flags + fs_base + xmm。
  public void copy_state_from( Cpu64 src ) {
    src.copy_state_into( this );
  }
  public void copy_state_into( Cpu64 dst ) {
    System.arraycopy( r64, 0, dst.r64, 0, NREGS );
    dst.rip = rip;
    dst.fs_base = fs_base;
    dst.of = of; dst.sf = sf; dst.zf = zf; dst.cf = cf;
    if( xmm_lo != null && dst.xmm_lo != null ) {
      System.arraycopy( xmm_lo, 0, dst.xmm_lo, 0, xmm_lo.length );
      System.arraycopy( xmm_hi, 0, dst.xmm_hi, 0, xmm_hi.length );
    }
  }

  @Override public void   set_ip( long _ip )    { rip = _ip; }
  @Override public long   get_ip()               { return rip; }
  @Override public void   set_sp( long sp )      { r64[R_RSP] = sp; }
  @Override public long   get_sp()               { return r64[R_RSP]; }
  @Override public void   set_ax( int value )    { r64[R_RAX] = value & 0xFFFFFFFFL; }

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    mem       = _mem;
    syscall   = _syscall;
    syscall64 = (SyscallAmd64)_syscall;
    syscall.connect_mem( _mem );
  }

  @Override
  public void fetch( long address, byte[] buf ) { mem.fetch( address, buf ); }

  // --- stack ---

  private void push64( long value ) {
    r64[R_RSP] -= 8;
    mem.store64( r64[R_RSP], value );
  }

  private long pop64() {
    long v = mem.load64( r64[R_RSP] );
    r64[R_RSP] += 8;
    return v;
  }

  @Override public void push32( long value ) {
    r64[R_RSP] -= 4;
    mem.store32( r64[R_RSP], (int)value );
  }

  @Override public int pop32() {
    int v = mem.load32( r64[R_RSP] );
    r64[R_RSP] += 4;
    return v;
  }

  @Override
  public long pushString( String str ) {
    byte[] bytes = str.getBytes();
    r64[R_RSP] -= bytes.length + 1;
    mem.storeString( r64[R_RSP], str );
    return r64[R_RSP];
  }

  @Override
  public void set_signal_handler( long _ip, long goto_adrs ) {
    push64( _ip );
    rip = goto_adrs;
  }

  @Override
  public boolean is_interrupt_done() { return interrupt_done; }

  // --- CPU ループ ---

  // IRELATIVE リロケーション解決用: addr の関数を呼び出し RAX を返す。
  // ret_addr は既にマップ済みで RET (0xC3) が置かれていること。
  long call_resolver( long addr, long ret_addr ) {
    r64[R_RSP] -= 8;
    mem.store64( r64[R_RSP], ret_addr );
    rip = addr;
    int limit = 200_000;
    while( !process.is_exited() && rip != ret_addr && limit-- > 0 ) {
      rip = decode_and_exec( rip );
    }
    return r64[R_RAX];
  }

  @Override
  public long eval() {
    long executed = 0;
    jit_lookup_next = true;          // 起動直後は block entry 候補
    // EMULIN_TRACE_RIP=N: N 命令ごとに rip と簡易なレジスタを stderr に出す。
    //   curl 等が syscall を一切呼ばずに CPU loop に落ちている時に「どの
    //   命令範囲を周回しているか」を特定するための簡易プローブ。
    long trace_rip_period = 0;
    String trp = System.getenv("EMULIN_TRACE_RIP");
    if( trp != null ) {
      try { trace_rip_period = Long.parseLong( trp ); }
      catch ( NumberFormatException ignored ) { trace_rip_period = 1_000_000; }
    }
    // Phase 27 step 24: 旧実装は EMULIN_TRACE_FP / EMULIN_TRACE_SH を毎命令
    //   System.getenv で問い合わせていた (HashMap lookup × 命令数)。
    //   起動時に 1 回読んで boolean に固定する。これで Python 起動が
    //   約 30% 高速化する。
    final boolean trace_fp = System.getenv("EMULIN_TRACE_FP") != null;
    final boolean trace_sh = System.getenv("EMULIN_TRACE_SH") != null;
    // EMULIN_TRACE_FREE_BAD=<HEX_ENTRY>: RIP がそのアドレス (= libc free
    // のエントリ) に到達したとき、rdi (pointer being freed) を見て
    // 「不審に小さい値」(< 0x10000、real heap は 0x55..) なら caller stack
    // を 8 段まで dump する。libtasn1 が free に渡している bogus pointer
    // 0x1440 の出所を一発で特定するためのデバッグ用 hook
    long trace_free_entry = 0;
    String tfb = System.getenv("EMULIN_TRACE_FREE_BAD");
    if( tfb != null ) {
      try { trace_free_entry = Long.parseLong( tfb, 16 ); }
      catch ( NumberFormatException ignored ) { trace_free_entry = 0; }
    }
    long watch_rip_dump = 0;
    String wrd = System.getenv("EMULIN_TRACE_RIP_DUMP");
    if( wrd != null ) {
      try { watch_rip_dump = Long.parseLong( wrd, 16 ); }
      catch ( NumberFormatException ignored ) { watch_rip_dump = 0; }
    }
    long watch_rip_dump2 = 0;
    String wrd2 = System.getenv("EMULIN_TRACE_RIP_DUMP2");
    if( wrd2 != null ) {
      try { watch_rip_dump2 = Long.parseLong( wrd2, 16 ); }
      catch ( NumberFormatException ignored ) { watch_rip_dump2 = 0; }
    }
    long watch_rip_dump3 = 0;
    String wrd3 = System.getenv("EMULIN_TRACE_RIP_DUMP3");
    if( wrd3 != null ) {
      try { watch_rip_dump3 = Long.parseLong( wrd3, 16 ); }
      catch ( NumberFormatException ignored ) { watch_rip_dump3 = 0; }
    }
    long watch_rip_dump4 = 0;
    String wrd4 = System.getenv("EMULIN_TRACE_RIP_DUMP4");
    if( wrd4 != null ) {
      try { watch_rip_dump4 = Long.parseLong( wrd4, 16 ); }
      catch ( NumberFormatException ignored ) { watch_rip_dump4 = 0; }
    }
    long dump_at_rip = 0, dump_at_addr = 0;
    String dar = System.getenv("EMULIN_DUMP_AT_RIP");
    if( dar != null ) {
      String[] parts = dar.split(":");
      if( parts.length == 2 ) {
        try {
          dump_at_rip  = Long.parseLong( parts[0], 16 );
          dump_at_addr = Long.parseUnsignedLong( parts[1], 16 );
        } catch ( NumberFormatException ignored ) { dump_at_rip = 0; }
      }
    }
    // Phase 34-A13 (issue #4): trace 系全 env 変数を 1 つの boolean に集約。
    // どれも 0/false が default なので、any_trace_active が false の hot path
    // (= 通常実行) では 7 箇所の if check を完全 skip できる。HotSpot C2 が
    // dead-code 削除で生成 bytecode を短縮 → eval() loop 本体が tight に。
    final boolean any_trace_active = trace_rip_period > 0 || trace_fp || trace_sh
        || dump_at_rip != 0 || watch_rip_dump != 0 || watch_rip_dump2 != 0
        || watch_rip_dump3 != 0 || watch_rip_dump4 != 0 || trace_free_entry != 0;
    while( !process.is_exited() ) {
      executed++;
      // Phase 27 step 24: process.evals は segfault 診断と trace でしか
      //   使われないので、毎命令の write は無駄。1024 命令ごとに同期する
      //   (segfault 時のずれは最大 1023 命令、許容範囲)。
      if( (executed & 0x3FF) == 0 ) process.evals = executed;
      // シグナルハンドラからの復帰: トランポリンに着地したらレジスタを戻す。
      if( rip == SIGRETURN_TRAMPOLINE ) {
        long[] frame = sigSavedFrames.pollFirst();
        if( frame != null ) {
          System.arraycopy( frame, 0, r64, 0, NREGS );
          rip = frame[NREGS    ];
          of  = (int)frame[NREGS + 1];
          sf  = (int)frame[NREGS + 2];
          zf  = (int)frame[NREGS + 3];
          cf  = (int)frame[NREGS + 4];
          // Phase 27 step 23: ハンドラ進入時に保存した signal mask を復元
          //   (sa_mask 中の self-mask 等を解除)
          process.set_signal_mask_bits( frame[NREGS + 5] );
        }
      }
      // pending シグナルがあればハンドラへ分岐
      check_pending_signal();
      if( any_trace_active ) {
      if( trace_rip_period > 0 && (executed % trace_rip_period) == 0 ) {
        System.err.println("DBG rip=0x"+Long.toHexString(rip)
          +" rax=0x"+Long.toHexString(r64[R_RAX])
          +" rsp=0x"+Long.toHexString(r64[R_RSP])
          +" rdi=0x"+Long.toHexString(r64[R_RDI])
          +" rsi=0x"+Long.toHexString(r64[R_RSI])
          +" eval="+executed);
        System.err.flush();
      }
      if( trace_fp ) {
        if( rip == 0x440ea0L || rip == 0x440f7eL ) {
          System.err.println("DBG hack_digit ret r12="+r64[12]+" ('"+(char)((int)r64[12]&0xFF)+"') rip=0x"+Long.toHexString(rip));
        }
      }
      if( trace_sh ) {
        if( rip == 0x548cc4L ) {
          long head = r64[R_RDI];
          long e0 = mem.load64(head);
          if( e0 == 0x100000000000L ) {
            StringBuilder sb = new StringBuilder();
            for( int i = -16; i < 32; i += 8 ) {
              long v = mem.load64(head + i);
              sb.append(String.format(" [+%d]=0x%x", i, v));
            }
            System.err.println("DBG BAD hash_lookup(rdi=0x"+Long.toHexString(head)+") dump:"+sb);
          }
          System.err.println("DBG hash_lookup(rdi=0x"+Long.toHexString(head)+") *head=0x"+Long.toHexString(e0)+" lr=0x"+Long.toHexString(mem.load64(r64[R_RSP]))+" eval="+executed);
        }
        if( rip == 0x5490a7L ) {
          System.err.println("DBG before mov(rax),r12: rax=0x"+Long.toHexString(r64[R_RAX])+" *rax=0x"+Long.toHexString(r64[R_RAX]!=0?mem.load64(r64[R_RAX]):0)+" eval="+executed);
        }
        if( rip == 0x548cdaL ) {
          System.err.println("  hash_loop r9=0x"+Long.toHexString(r64[9])+" eval="+executed);
        }
        if( rip == 0x548ce7L ) {
          System.err.println("  hash_advance r9=0x"+Long.toHexString(r64[9])+" *(r9)=0x"+Long.toHexString(mem.load64(r64[9])));
        }
      }
      // EMULIN_TRACE_RIP_DUMP=<HEX_RIP>: その RIP に到達したとき rbx と rbx+0x70
      // の memory 8 byte を dump する。libtasn1 のリンクリスト走査で
      // bad pointer がどの node に書き込まれているか特定するため
      // EMULIN_DUMP_AT_RIP=<HEX_RIP>:<HEX_ADDR>: dump 256 bytes at HEX_ADDR
      // when reaching HEX_RIP AND when rbx == HEX_ADDR (= the bad pointer).
      // Wrapped in try/catch so a segfault in our dump doesn't propagate.
      if( dump_at_rip != 0 && rip == dump_at_rip && r64[R_RBX] == dump_at_addr
          && mem.in(dump_at_addr) && mem.in(dump_at_addr + 0xff) ) {
        StringBuilder sb = new StringBuilder();
        sb.append("DBG_DAR rip=0x").append(Long.toHexString(rip));
        sb.append(" addr=0x").append(Long.toHexString(dump_at_addr));
        sb.append("\n  text: ");
        for( int k = 0; k < 0x80; k++ ) {
          int b = mem.load8(dump_at_addr + k) & 0xFF;
          if( b >= 32 && b < 127 ) sb.append((char)b); else sb.append('.');
        }
        sb.append("\n  hex:");
        for( int k = 0; k < 0x100; k += 8 ) {
          sb.append(" +0x").append(Integer.toHexString(k)).append("=0x").append(Long.toHexString(mem.load64(dump_at_addr + k)));
        }
        System.err.println(sb.toString());
        System.err.flush();
      }
      // EMULIN_TRACE_RIP_DUMP4=<HEX_RIP>: dump rdi + the value at 0x60(%rdi)
      // — used to catch when libtasn1's `mov 0x60(%rdi), %rbx` loads a bad pointer
      if( watch_rip_dump4 != 0 && rip == watch_rip_dump4 ) {
        long rdi_val = r64[R_RDI];
        long down_val = mem.load64( rdi_val + 0x60 );
        if( down_val == 0x555555b78f00L ) {
          StringBuilder sb = new StringBuilder();
          sb.append("DBG_RD4 rip=0x").append(Long.toHexString(rip));
          sb.append(" rdi=0x").append(Long.toHexString(rdi_val));
          sb.append(" *(rdi+0x60)=0x").append(Long.toHexString(down_val));
          // dump rdi struct context
          sb.append(" name[0..15]=");
          for( int k = 0; k < 16; k++ ) {
            int b = mem.load8( rdi_val + k ) & 0xFF;
            if( b >= 32 && b < 127 ) sb.append((char)b);
            else sb.append("\\x").append(String.format("%02x", b));
          }
          sb.append(" *(rdi+0x44)=0x").append(Long.toHexString(mem.load32(rdi_val + 0x44) & 0xFFFFFFFFL));
          sb.append(" *(rdi+0x48)=0x").append(Long.toHexString(mem.load32(rdi_val + 0x48) & 0xFFFFFFFFL));
          System.err.println(sb.toString());
          System.err.flush();
        }
      }
      // EMULIN_TRACE_RIP_DUMP3=<HEX_RIP>: at the given RIP dump rdx/rbx/rcx/r12.
      // Used to inspect glibc malloc's `lea (%rdx,%rbx,1),%rcx` to see what
      // chunk pointer + size produced a suspicious address
      if( watch_rip_dump3 != 0 && rip == watch_rip_dump3 ) {
        System.err.println("DBG_RD3 rip=0x"+Long.toHexString(rip)
          +" rdi=0x"+Long.toHexString(r64[R_RDI])
          +" rsi=0x"+Long.toHexString(r64[R_RSI])
          +" rdx=0x"+Long.toHexString(r64[R_RDX])
          +" rbx=0x"+Long.toHexString(r64[R_RBX])
          +" rcx=0x"+Long.toHexString(r64[R_RCX])
          +" r12=0x"+Long.toHexString(r64[12])
          +" rax=0x"+Long.toHexString(r64[R_RAX])
          +" r14=0x"+Long.toHexString(r64[14])
          +" r15=0x"+Long.toHexString(r64[15]));
        System.err.flush();
      }
      if( watch_rip_dump != 0 && rip == watch_rip_dump ) {
        long rbx_val = r64[R_RBX];
        StringBuilder sb = new StringBuilder();
        sb.append("DBG_RD rip=0x").append(Long.toHexString(rip));
        sb.append(" rbx=0x").append(Long.toHexString(rbx_val));
        sb.append(" r13=0x").append(Long.toHexString(r64[13]));
        sb.append(" r12=0x").append(Long.toHexString(r64[12]));
        sb.append(" *(rbx+0x70)=0x").append(Long.toHexString(mem.load64(rbx_val + 0x70)));
        sb.append(" *(rbx+0x68)=0x").append(Long.toHexString(mem.load64(rbx_val + 0x68)));
        sb.append(" *(rbx+0x60)=0x").append(Long.toHexString(mem.load64(rbx_val + 0x60)));
        sb.append(" *(rbx+0x50)=0x").append(Long.toHexString(mem.load64(rbx_val + 0x50)));
        System.err.println(sb.toString());
        System.err.flush();
      }
      // EMULIN_TRACE_RIP_DUMP2=<HEX_RIP>: dump r13 and 16 bytes from r13 plus
      // selected fields (used for libtasn1 root-node analysis)
      if( watch_rip_dump2 != 0 && rip == watch_rip_dump2 ) {
        long r13v = r64[13];
        StringBuilder sb = new StringBuilder();
        sb.append("DBG_RD2 rip=0x").append(Long.toHexString(rip));
        sb.append(" r13=0x").append(Long.toHexString(r13v));
        sb.append(" r12=0x").append(Long.toHexString(r64[12]));
        sb.append(" name[0..7]=");
        for( int k = 0; k < 8; k++ ) {
          int b = mem.load8(r13v + k) & 0xFF;
          if( b >= 32 && b < 127 ) sb.append((char)b); else sb.append("\\x").append(String.format("%02x", b));
        }
        sb.append(" *(r13+0x44)=0x").append(Long.toHexString(mem.load32(r13v + 0x44) & 0xFFFFFFFFL));
        sb.append(" *(r13+0x48)=0x").append(Long.toHexString(mem.load32(r13v + 0x48) & 0xFFFFFFFFL));
        sb.append(" *(r13+0x70)=0x").append(Long.toHexString(mem.load64(r13v + 0x70)));
        sb.append(" *(r13+0xa4)=0x").append(Long.toHexString(mem.load32(r13v + 0xa4) & 0xFFFFFFFFL));
        sb.append(" *(r13+0xa8)=0x").append(Long.toHexString(mem.load32(r13v + 0xa8) & 0xFFFFFFFFL));
        System.err.println(sb.toString());
        System.err.flush();
      }
      if( trace_free_entry != 0 && rip == trace_free_entry ) {
        long ptr = r64[R_RDI];
        if( ptr != 0 && ptr < 0x10000L ) {
          long sp = r64[R_RSP];
          StringBuilder sb = new StringBuilder();
          sb.append("DBG_FREE_BAD ptr=0x").append(Long.toHexString(ptr));
          sb.append(" rsp=0x").append(Long.toHexString(sp));
          for( int k = 0; k < 8; k++ ) {
            try {
              long ra = mem.load64(sp + 8L*k);
              sb.append(" [+").append(k*8).append("]=0x").append(Long.toHexString(ra));
            } catch( Throwable t ) {
              sb.append(" [+").append(k*8).append("]=??");
              break;
            }
          }
          System.err.println(sb.toString());
          System.err.flush();
        }
      }
      } // end if( any_trace_active )
      // Phase 34-A3 step 23: JIT 経路は jitStep() に extract。eval() の
      // method body を小さく保つことで C2 が hot loop 全体を fully optimize
      // しやすくなる。ENABLED が false のときは static final 定数畳み込みで
      // 完全に dead code になる。
      if( emulin.jit.Translator.ENABLED ) {
        rip = jitStep( rip );
      } else {
        rip = decode_and_exec( rip );
      }
    }
    return executed;
  }

  /**
   * Phase 34-A3 step 23: JIT 経路の 1 命令 step。eval() loop から呼ばれる。
   *
   * jit_lookup_next フラグで block entry 候補かどうか判定:
   * - block entry 候補なら lookup() し、HIT なら compiled block を実行
   * - そうでなければ interpreter で 1 命令進めた後、必要なら tryCompileBlock
   * - block 内連続命令では lookup も tryCompileBlock も skip
   *
   * @return 次の rip
   */
  private long jitStep( long rip ) {
    boolean entry_candidate = jit_lookup_next;
    if( entry_candidate ) {
      emulin.jit.CompiledInsn ci = translator.lookup( rip );
      if( ci != null ) {
        // block exit 後はまた block entry 候補
        jit_lookup_next = true;
        return ci.execute( this );
      }
    }
    long start_pc = rip;
    long newRip = decode_and_exec( rip );
    long delta = newRip - start_pc;
    boolean wasControlTransfer = !( delta > 0 && delta <= 15 );
    jit_lookup_next = wasControlTransfer;
    if( entry_candidate && translator.shouldAttemptCompile( start_pc ) ) {
      int insnLen = wasControlTransfer ? jit_insn_length( start_pc ) : (int)delta;
      if( insnLen > 0 ) {
        byte[] bytes = new byte[ insnLen ];
        for( int i = 0; i < insnLen; i++ ) bytes[i] = mem.load8( start_pc + i );
        translator.tryCompileBlock( start_pc, mem, bytes, insnLen );
      }
    }
    return newRip;
  }

  /**
   * Phase 34-A3: JIT 翻訳可能な制御転送命令 (RET / 短 JMP / Jcc / 長 JMP /
   * CALL) の byte 長を返す。non-zero ならそのまま tryCompileBlock に渡せる。
   * 0 を返すと tryCompileBlock を skip する。Translator の対応 opcode と
   * 同期させること。
   */
  private int jit_insn_length( long pc ) {
    int b0 = mem.load8( pc ) & 0xFF;
    if( b0 == 0xC3 ) return 1;                       // RET
    if( b0 == 0xEB ) return 2;                       // JMP rel8
    if( (b0 & 0xF0) == 0x70 ) return 2;              // Jcc rel8
    if( b0 == 0xE9 ) return 5;                       // JMP rel32
    if( b0 == 0xE8 ) return 5;                       // CALL rel32
    if( b0 == 0x0F ) {                               // 0F 80-8F: Jcc rel32 — 6 byte
      int b1 = mem.load8( pc + 1 ) & 0xFF;
      if( (b1 & 0xF0) == 0x80 ) return 6;
    }
    // 0xFF /2 CALL r/m, /4 JMP r/m — mod==3 or memory operand
    if( b0 == 0xFF ) {
      return jitInsnLengthFF( pc, 1 );
    }
    if( (b0 & 0xF0) == 0x40 ) {                      // REX prefix
      int op = mem.load8( pc + 1 ) & 0xFF;
      if( op == 0xFF ) {
        return jitInsnLengthFF( pc, 2 );
      }
    }
    return 0;
  }

  /** 0xFF /2 or /4 の命令長を返す。modrmOff = 1 (no REX) or 2 (REX)。 */
  private int jitInsnLengthFF( long pc, int modrmOff ) {
    int modrm = mem.load8( pc + modrmOff ) & 0xFF;
    int sub = (modrm >> 3) & 7;
    if( sub != 2 && sub != 4 ) return 0;
    int mod = (modrm >> 6) & 3;
    if( mod == 3 ) return modrmOff + 1;
    int rm_lo = modrm & 7;
    if( rm_lo == 4 ) {
      // SIB: ModRM(1) + SIB(1) + disp(0/1/4)
      if( mod == 0 ) return modrmOff + 2;
      if( mod == 1 ) return modrmOff + 3;
      if( mod == 2 ) return modrmOff + 6;
      return 0;
    }
    if( mod == 0 && rm_lo == 5 ) return modrmOff + 5;  // RIP-rel
    if( mod == 0 ) return modrmOff + 1;
    if( mod == 1 ) return modrmOff + 2;
    if( mod == 2 ) return modrmOff + 5;
    return 0;
  }

  // 保留中のシグナルを 1 件処理する (1 命令あたり 1 シグナルまで)
  private void check_pending_signal() {
    int sig = process.psig();
    if( sig < 0 ) return;
    long handler = process.get_func_adrs( sig );
    process.signal_cancel( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      int action = process.get_action_type( sig );
      if( action == Signal.SIGACTION_EXIT ) {
        process.set_exit_flag();
      }
      return;
    }
    // ユーザーハンドラ呼び出し:
    //   実機 Linux カーネルは ucontext に全レジスタ + flags を保存し、
    //   ハンドラ復帰時に sa_restorer → rt_sigreturn 経由で復元する。
    //   ここでは Java 側に保存し、ユーザスタックに SIGRETURN_TRAMPOLINE を
    //   push してハンドラの ret でその番地に着地させ、eval ループ側で
    //   復元する。
    //
    //   保存対象: 16 本の GPR + rip + (of, sf, zf, cf)
    //   ハンドラ進入時に rdi = sig をセット (POSIX `void(int)` ABI)
    long[] frame = new long[NREGS + 6];
    System.arraycopy( r64, 0, frame, 0, NREGS );
    frame[NREGS    ] = rip;
    frame[NREGS + 1] = of;
    frame[NREGS + 2] = sf;
    frame[NREGS + 3] = zf;
    frame[NREGS + 4] = cf;
    // Phase 27 step 23/27: 現在の signal mask を保存。ハンドラ実行中は
    //   sa_mask 経由で指定された signal をすべて block + (SA_NODEFER でなければ)
    //   配信中の sig 自身も block。ハンドラ復帰時 (SIGRETURN_TRAMPOLINE) に復元。
    long saved_mask = process.get_signal_mask_bits();
    frame[NREGS + 5] = saved_mask;
    sigSavedFrames.push( frame );
    long new_mask = saved_mask | process.get_sa_mask( sig );
    if( !process.has_sa_nodefer( sig )) {
      // sig 自身もマスク (POSIX デフォルト動作、sa_mask に bit を追加)
      if( sig >= 1 && sig < 32 ) new_mask |= (1L << (sig - 1));
    }
    process.set_signal_mask_bits( new_mask );
    // x86-64 ABI: rsp 直下 128 byte は "red zone" として被中断側が
    // rsp を下げずに使ってよい領域。ハンドラがそこを破壊しないよう、
    // Linux カーネルと同様にトランポリン push の前に red zone をスキップする。
    r64[R_RSP] -= 128;

    // SA_SIGINFO 対応: ハンドラ呼び出し規約は void(int, siginfo_t*, ucontext_t*)。
    // siginfo_t (128 byte) と ucontext_t (簡易 256 byte) をユーザスタックに
    // 確保し、最低限のフィールド (siginfo の si_signo) だけ埋める。残りは 0。
    // ハンドラ復帰時は saved frame の rsp に戻すので、これらの一時領域は
    // 自動的に「解放」される (handler のフレームより上に置くだけ)。
    long siginfo_addr = 0L;
    long ucontext_addr = 0L;
    if( process.has_sa_siginfo( sig ) ) {
      r64[R_RSP] -= 128;
      siginfo_addr = r64[R_RSP];
      // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
      mem.bulkZero( siginfo_addr, 128 );
      mem.store32( siginfo_addr,        sig );  // si_signo
      mem.store32( siginfo_addr + 4,    0   );  // si_errno
      mem.store32( siginfo_addr + 8,    0   );  // si_code (= 0; SI_USER 等は未対応)

      r64[R_RSP] -= 256;
      ucontext_addr = r64[R_RSP];
      // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
      mem.bulkZero( ucontext_addr, 256 );
      // uc_flags=0, uc_link=NULL; mcontext は全 0 で済ませる (ハンドラが
      // 実際に rip/レジスタを参照するケースは glibc backtrace 等限定)
    }

    push64( SIGRETURN_TRAMPOLINE );
    rip = handler;
    r64[R_RDI] = (long)sig;
    if( siginfo_addr != 0 ) {
      r64[R_RSI] = siginfo_addr;
      r64[R_RDX] = ucontext_addr;
    }
  }

  // --- ModRM デコード ---
  // Phase 34-A2 step 20: per-RIP decoded cache (DECCACHE_*) で
  //   ModRM/SIB/disp byte 読込みを skip。mrm_ea は register 依存なので
  //   毎回 cached 計算式 (base/index/scale/disp) を現 register 値で評価。
  //   addr32=true 経路 (i386 prefix 0x67 系) は稀かつ動作多様なので cache 対象外。
  private long decodeModRM( long pc, boolean rexR, boolean rexB, boolean rexX, boolean addr32 ) {
    if( !addr32 ) {
      int slot = (int)(pc & DECCACHE_MASK);
      if( dec_cache_pc[slot] == pc ) {
        int info = dec_cache_info[slot];
        if( (info & DEC_VALID) != 0 ) {
          if( PROFILE_OP ) dec_cache_hits++;
          int kind = info & 3;
          mrm_mod  = (info >> 2) & 3;
          mrm_reg  = (info >> 4) & 0xF;
          int disp = dec_cache_disp[slot];
          int next_off = (info >> 24) & 0x1F;
          switch( kind ) {
            case DEC_KIND_REG:
              mrm_rm = (info >> 8) & 0xF;
              mrm_ea = 0;
              break;
            case DEC_KIND_RIP_REL:
              mrm_rm = -1;
              mrm_ea = disp;
              break;
            case DEC_KIND_REG_DISP:
              mrm_rm = (info >> 8) & 0xF;
              mrm_ea = r64[mrm_rm] + disp;
              break;
            case DEC_KIND_SIB: {
              mrm_rm = (info >> 8) & 0xF;
              long ea = disp;
              if( (info & DEC_HAS_BASE) != 0 ) ea += r64[(info >> 12) & 0xF];
              if( (info & DEC_HAS_INDEX) != 0 ) ea += r64[(info >> 16) & 0xF] << ((info >> 20) & 3);
              mrm_ea = ea;
              break;
            }
          }
          return pc + next_off;
        }
      }
      if( PROFILE_OP ) dec_cache_misses++;
    }

    long start = pc;
    int b   = mem.load8( pc ) & 0xFF;
    mrm_mod = (b >> 6) & 3;
    mrm_reg = ((b >> 3) & 7) | (rexR ? 8 : 0);
    int rm_base = b & 7;
    mrm_rm  = rm_base | (rexB ? 8 : 0);
    long next = pc + 1;

    int kind, dispVal = 0, baseReg = 0, indexReg = 0, scale = 0;
    int hasBase = 0, hasIndex = 0;

    if( mrm_mod == 3 ) {
      mrm_ea = 0;
      kind = DEC_KIND_REG;
    }
    else if( rm_base == 4 ) {
      int sib     = mem.load8( next ) & 0xFF;
      next++;
      int ss      = (sib >> 6) & 3;
      int sib_idx = (sib >> 3) & 7;
      int sib_bas = sib & 7;
      long base   = 0L;
      if( !(sib_bas == 5 && mrm_mod == 0) ) {
        baseReg = sib_bas | (rexB ? 8 : 0);
        base = r64[ baseReg ];
        hasBase = DEC_HAS_BASE;
      }
      long index = 0L;
      if( !(sib_idx == 4 && !rexX) ) {
        indexReg = sib_idx | (rexX ? 8 : 0);
        index = r64[ indexReg ] << ss;
        scale = ss;
        hasIndex = DEC_HAS_INDEX;
      }
      mrm_ea = base + index;
      if( sib_bas == 5 && mrm_mod == 0 ) {
        dispVal = (int)loadImm32u( next );
        mrm_ea += dispVal; next += 4;
        if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
      } else if( mrm_mod == 1 ) {
        dispVal = (byte)mem.load8( next );
        mrm_ea += dispVal; next++;
      } else if( mrm_mod == 2 ) {
        dispVal = (int)loadImm32u( next );
        mrm_ea += dispVal; next += 4;
      }
      if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
      kind = DEC_KIND_SIB;
    }
    else if( mrm_mod == 0 && rm_base == 5 ) {
      dispVal = (int)loadImm32u( next );
      mrm_ea = dispVal;
      mrm_rm = -1;  // RIP 相対フラグ
      next += 4;
      kind = DEC_KIND_RIP_REL;
    }
    else {
      long reg_val = addr32 ? (r64[mrm_rm] & 0xFFFFFFFFL) : r64[mrm_rm];
      if( mrm_mod == 0 ) {
        mrm_ea = reg_val;
      } else if( mrm_mod == 1 ) {
        dispVal = (byte)mem.load8( next );
        mrm_ea = reg_val + dispVal; next++;
      } else {
        dispVal = (int)loadImm32u( next );
        mrm_ea = reg_val + dispVal; next += 4;
      }
      if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
      kind = DEC_KIND_REG_DISP;
    }

    if( !addr32 ) {
      int next_off = (int)(next - start);
      if( next_off <= 0x1F ) {  // 5-bit length
        int info = kind
                 | (mrm_mod << 2)
                 | ((mrm_reg & 0xF) << 4)
                 | (((mrm_rm < 0 ? 0 : mrm_rm) & 0xF) << 8)
                 | ((baseReg & 0xF) << 12)
                 | ((indexReg & 0xF) << 16)
                 | ((scale & 3) << 20)
                 | hasBase | hasIndex
                 | (next_off << 24)
                 | DEC_VALID;
        int slot = (int)(start & DECCACHE_MASK);
        dec_cache_pc[slot]   = start;
        dec_cache_info[slot] = info;
        dec_cache_disp[slot] = dispVal;
      }
    }
    return next;
  }

  // --- RM read/write helpers ---

  private long readRM64() { return (mrm_mod==3) ? r64[mrm_rm] : mem.load64(mrm_ea); }
  private long readRM32() { return (mrm_mod==3) ? (r64[mrm_rm]&0xFFFFFFFFL) : (mem.load32(mrm_ea)&0xFFFFFFFFL); }
  private long readRM16() { return (mrm_mod==3) ? (r64[mrm_rm]&0xFFFFL) : loadImm16(mrm_ea); }
  private void writeRM16( long v ) {
    if(mrm_mod==3) r64[mrm_rm]=(r64[mrm_rm]&~0xFFFFL)|(v&0xFFFFL);
    else mem.store16( mrm_ea, (short)v );
  }
  private long readRM8()  {
    if( mrm_mod != 3 ) return mem.load8(mrm_ea)&0xFFL;
    // Without REX: rm=4-7 selects AH/CH/DH/BH (high byte of A/C/D/B = r64[rm-4] >> 8)
    if( !rex_present && mrm_rm >= 4 && mrm_rm <= 7 ) return (r64[mrm_rm-4]>>8)&0xFFL;
    return r64[mrm_rm]&0xFFL;
  }

  private void writeRM64( long v ) { if(mrm_mod==3) r64[mrm_rm]=v; else mem.store64(mrm_ea,v); }
  private void writeRM32( long v ) { if(mrm_mod==3) r64[mrm_rm]=v&0xFFFFFFFFL; else mem.store32(mrm_ea,(int)v); }
  private void writeRM8( long v )  {
    if( mrm_mod != 3 ) { mem.store8(mrm_ea,(byte)v); return; }
    if( !rex_present && mrm_rm >= 4 && mrm_rm <= 7 ) {
      // Write high byte of r64[rm-4]
      int idx = mrm_rm - 4;
      r64[idx] = (r64[idx] & ~0xFF00L) | ((v & 0xFFL) << 8);
      return;
    }
    r64[mrm_rm]=(r64[mrm_rm]&~0xFFL)|(v&0xFFL);
  }


  // Used for mrm_reg in 8-bit MOV / TEST etc. (mrm_rm uses readRM8/writeRM8).
  private long readReg8( int idx ) {
    if( !rex_present && idx >= 4 && idx <= 7 ) return (r64[idx-4]>>8)&0xFFL;
    return r64[idx]&0xFFL;
  }
  private void writeReg8( int idx, long v ) {
    if( !rex_present && idx >= 4 && idx <= 7 ) {
      int base = idx - 4;
      r64[base] = (r64[base] & ~0xFF00L) | ((v & 0xFFL) << 8);
      return;
    }
    r64[idx] = (r64[idx] & ~0xFFL) | (v & 0xFFL);
  }

  // RIP-relative fix-up + FS segment adjust.
  // Call after every decodeModRM, passing the returned `next` and fs_prefix.
  private void fixEA( long next, boolean fs ) {
    if( mrm_rm == -1 ) mrm_ea += next;        // RIP-relative: mrm_ea was just disp32
    if( fs && mrm_mod != 3 ) mrm_ea += fs_base;
  }

  // --- フラグ計算 ---

  private void setFlags64Sub( long a, long b ) {
    long result = a - b;
    zf = (result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(a,b)<0?1:0;
  }

  private void setFlags64Add( long a, long b ) {
    long result = a + b;
    zf=(result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^~b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(result,a)<0?1:0;
  }

  private void setFlags32Sub( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long r = (a - b) & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^b)&(a^r))>>31)&1;
    cf=Long.compareUnsigned(a,b)<0?1:0;
  }

  private void setFlags32Add( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long result = a + b;
    long r = result & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^~b)&(a^r))>>31)&1;
    cf=result>0xFFFFFFFFL?1:0;
  }

  private void setFlags16Add( long a, long b ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long result = a + b;
    long r = result & 0xFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>15)&1;
    of=(int)(((a^~b)&(a^r))>>15)&1;
    cf=result>0xFFFFL?1:0;
  }

  private void setFlags16Sub( long a, long b ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long r = (a - b) & 0xFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>15)&1;
    of=(int)(((a^b)&(a^r))>>15)&1;
    cf=Long.compareUnsigned(a,b)<0?1:0;
  }

  // --- ADC / SBB ヘルパー (Phase 25): フラグ更新付きの加減算 ---
  // CF を入力にとり、結果の CF / ZF / SF / OF を本物のセマンティクスで設定する。
  // bignum 連鎖でこれらが正しく更新されないと __printf_fp が破綻する。

  private long adc64( long a, long b, int cin ) {
    long sum1 = a + b;
    long sum  = sum1 + cin;
    boolean ovf1 = Long.compareUnsigned(sum1, a) < 0;
    boolean ovf2 = Long.compareUnsigned(sum, sum1) < 0;
    cf = (ovf1 || ovf2) ? 1 : 0;
    zf = (sum == 0) ? 1 : 0;
    sf = (sum < 0) ? 1 : 0;
    of = (((a ^ ~b) & (a ^ sum)) < 0) ? 1 : 0;
    return sum;
  }
  private long adc32( long a, long b, int cin ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long sum = a + b + cin;
    long r = sum & 0xFFFFFFFFL;
    cf = (sum > 0xFFFFFFFFL) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 31) & 1;
    of = (int)(((a ^ ~b) & (a ^ r)) >> 31) & 1;
    return r;
  }
  private long adc16( long a, long b, int cin ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long sum = a + b + cin;
    long r = sum & 0xFFFFL;
    cf = (sum > 0xFFFFL) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 15) & 1;
    of = (int)(((a ^ ~b) & (a ^ r)) >> 15) & 1;
    return r;
  }
  private long sbb64( long a, long b, int cin ) {
    long sub1 = a - b;
    long res  = sub1 - cin;
    // borrow1: a < b, borrow2: sub1 < cin (= sub1 == 0 with cin=1)
    boolean bor1 = Long.compareUnsigned(a, b) < 0;
    boolean bor2 = Long.compareUnsigned(sub1, cin) < 0;
    cf = (bor1 || bor2) ? 1 : 0;
    zf = (res == 0) ? 1 : 0;
    sf = (res < 0) ? 1 : 0;
    of = (((a ^ b) & (a ^ res)) < 0) ? 1 : 0;
    return res;
  }
  private long sbb32( long a, long b, int cin ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long total = a - b - cin;
    long r = total & 0xFFFFFFFFL;
    cf = (a < (b + cin) || (cin == 1 && b == 0xFFFFFFFFL)) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 31) & 1;
    of = (int)(((a ^ b) & (a ^ r)) >> 31) & 1;
    return r;
  }
  private long sbb16( long a, long b, int cin ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long total = a - b - cin;
    long r = total & 0xFFFFL;
    cf = (a < (b + cin) || (cin == 1 && b == 0xFFFFL)) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 15) & 1;
    of = (int)(((a ^ b) & (a ^ r)) >> 15) & 1;
    return r;
  }
  private long adc8( long a, long b, int cin ) {
    a &= 0xFFL; b &= 0xFFL;
    long sum = a + b + cin;
    long r = sum & 0xFFL;
    cf = (sum > 0xFFL) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 7) & 1;
    of = (int)(((a ^ ~b) & (a ^ r)) >> 7) & 1;
    return r;
  }
  private long sbb8( long a, long b, int cin ) {
    a &= 0xFFL; b &= 0xFFL;
    long total = a - b - cin;
    long r = total & 0xFFL;
    cf = (a < (b + cin) || (cin == 1 && b == 0xFFL)) ? 1 : 0;
    zf = (r == 0) ? 1 : 0;
    sf = (int)(r >> 7) & 1;
    of = (int)(((a ^ b) & (a ^ r)) >> 7) & 1;
    return r;
  }

  // --- SSE2 バイト演算ヘルパー ---

  private static long pcmpeqb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      if(ab==bb) r|=(0xFFL<<(i*8));
    }
    return r;
  }

  private static long pminub( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      r|=((long)(ab<bb?ab:bb)<<(i*8));
    }
    return r;
  }

  private static long punpckhalf8( long lo_a, long lo_b ) {
    // lo_a: bytes 0..3, lo_b: bytes 0..3 → interleave into 8 bytes
    long r=0;
    for(int i=0;i<4;i++){
      r|=(long)((int)(lo_a>>(i*8))&0xFF)<<(i*16);
      r|=(long)((int)(lo_b>>(i*8))&0xFF)<<(i*16+8);
    }
    return r;
  }

  private static long punpckhalf16( long lo_a, long lo_b ) {
    long r=0;
    for(int i=0;i<2;i++){
      r|=(lo_a>>(i*16)&0xFFFFL)<<(i*32);
      r|=(lo_b>>(i*16)&0xFFFFL)<<(i*32+16);
    }
    return r;
  }

  private static long paddb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++) r|=((long)(((int)(a>>(i*8))+(int)(b>>(i*8)))&0xFF))<<(i*8);
    return r;
  }

  private static long subb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++) r|=((long)(((int)(a>>(i*8))-(int)(b>>(i*8)))&0xFF))<<(i*8);
    return r;
  }

  private static long pcmpgtb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      byte ab=(byte)(a>>(i*8)), bb=(byte)(b>>(i*8));
      if(ab>bb) r|=(0xFFL<<(i*8));
    }
    return r;
  }

  private static long pavgb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      r|=((long)((ab+bb+1)>>1)<<(i*8));
    }
    return r;
  }

  // Phase 34-A3 step 4: emulin.jit から JIT 生成 class が直接呼ぶので public 化。
  public boolean evalCond( int cond ) {
    switch( cond & 0xF ) {
      case  0: return of!=0;
      case  1: return of==0;
      case  2: return cf!=0;
      case  3: return cf==0;
      case  4: return zf!=0;
      case  5: return zf==0;
      case  6: return cf!=0||zf!=0;
      case  7: return cf==0&&zf==0;
      case  8: return sf!=0;
      case  9: return sf==0;
      case 10: return pf!=0;   // JP / JPE  (parity even = pf set)
      case 11: return pf==0;   // JNP / JPO
      case 12: return sf!=of;
      case 13: return sf==of;
      case 14: return zf!=0||(sf!=of);
      case 15: return zf==0&&(sf==of);
      default: return false;
    }
  }

  // ----------------------------------------------------------------------
  // Phase 34-A3 step 10: JIT 生成 class が直接呼ぶ ALU r64,r64 helpers。
  // 全て REX.W + ModRM mod==3 (register form) 専用。flag 計算は既存の
  // setFlags64Add / setFlags64Sub / setFlagsLogic64 を再利用するので、
  // interpreter と完全に同じ semantics になる。
  // dst, src は Cpu64 の汎用レジスタ index (0..15)。
  // ----------------------------------------------------------------------
  public void jitAdd64RR( int dstReg, int srcReg ) {
    long src = r64[srcReg];
    long dst = r64[dstReg];
    setFlags64Add( dst, src );
    r64[dstReg] = dst + src;
  }
  public void jitSub64RR( int dstReg, int srcReg ) {
    long src = r64[srcReg];
    long dst = r64[dstReg];
    setFlags64Sub( dst, src );
    r64[dstReg] = dst - src;
  }
  public void jitXor64RR( int dstReg, int srcReg ) {
    long res = r64[dstReg] ^ r64[srcReg];
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  public void jitAnd64RR( int dstReg, int srcReg ) {
    long res = r64[dstReg] & r64[srcReg];
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  public void jitOr64RR( int dstReg, int srcReg ) {
    long res = r64[dstReg] | r64[srcReg];
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  /** CMP r64, r64: subtract without storing, flags only。 */
  public void jitCmp64RR( int dstReg, int srcReg ) {
    setFlags64Sub( r64[dstReg], r64[srcReg] );
  }
  /** TEST r64, r64: AND without storing, flags only (logic 系)。 */
  public void jitTest64RR( int dstReg, int srcReg ) {
    setFlagsLogic64( r64[dstReg] & r64[srcReg] );
  }

  // ALU r64, imm (sign-extended): 0x83 /n + imm8 用。
  // imm は呼び出し側で既に long に sign-extend 済み。
  public void jitAdd64RI( int dstReg, long imm ) {
    long dst = r64[dstReg];
    setFlags64Add( dst, imm );
    r64[dstReg] = dst + imm;
  }
  public void jitSub64RI( int dstReg, long imm ) {
    long dst = r64[dstReg];
    setFlags64Sub( dst, imm );
    r64[dstReg] = dst - imm;
  }
  public void jitXor64RI( int dstReg, long imm ) {
    long res = r64[dstReg] ^ imm;
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  public void jitAnd64RI( int dstReg, long imm ) {
    long res = r64[dstReg] & imm;
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  public void jitOr64RI( int dstReg, long imm ) {
    long res = r64[dstReg] | imm;
    r64[dstReg] = res;
    setFlagsLogic64( res );
  }
  public void jitCmp64RI( int dstReg, long imm ) {
    setFlags64Sub( r64[dstReg], imm );
  }

  // Phase 34-A3 step 21: PUSH r64 / POP r64 / LEAVE 用 helper
  public void jitPush64( int srcReg ) {
    long sp = r64[ R_RSP ] - 8L;
    r64[ R_RSP ] = sp;
    mem.store64( sp, r64[ srcReg ] );
  }
  public void jitPop64( int dstReg ) {
    long sp = r64[ R_RSP ];
    r64[ dstReg ] = mem.load64( sp );
    r64[ R_RSP ] = sp + 8L;
  }
  /** LEAVE: rsp = rbp; rbp = pop64(); */
  public void jitLeave64() {
    long sp = r64[ R_RBP ];
    r64[ R_RBP ] = mem.load64( sp );
    r64[ R_RSP ] = sp + 8L;
  }

  // Phase 34-A3 step 25: Shift r64, imm8 用 helper (SHL/SHR/SAR)
  // 全て 64-bit 形 (count は & 0x3F)、interpreter exec_grp2_shift と同じ
  // flag semantics: zf/sf は result から、cf は最後に shift-out された bit、
  // of は 0 (1-bit shift の特殊例外は今は無視)
  public void jitShl64RI( int dstReg, int count ) {
    count &= 0x3F;
    long val = r64[ dstReg ];
    long res = val << count;
    if( count > 0 ) cf = (int)(val >> (64 - count)) & 1;
    zf = (res == 0) ? 1 : 0;
    sf = (res <  0) ? 1 : 0;
    of = 0;
    r64[ dstReg ] = res;
  }
  public void jitShr64RI( int dstReg, int count ) {
    count &= 0x3F;
    long val = r64[ dstReg ];
    long res = val >>> count;
    if( count > 0 ) cf = (int)(val >> (count - 1)) & 1;
    zf = (res == 0) ? 1 : 0;
    sf = (res <  0) ? 1 : 0;
    of = 0;
    r64[ dstReg ] = res;
  }
  public void jitSar64RI( int dstReg, int count ) {
    count &= 0x3F;
    long val = r64[ dstReg ];
    long res = val >> count;       // signed shift right
    if( count > 0 ) cf = (int)(val >> (count - 1)) & 1;
    zf = (res == 0) ? 1 : 0;
    sf = (res <  0) ? 1 : 0;
    of = 0;
    r64[ dstReg ] = res;
  }

  // Phase 34-A3 step 26: ALU [mem], imm 用 helper (0x83 /n の memory operand
  // 形)。read-modify-write + flag 更新。imm は呼び出し側で sign-extend 済み。
  public void jitAdd64MemImm( long addr, long imm ) {
    long dst = mem.load64( addr );
    setFlags64Add( dst, imm );
    mem.store64( addr, dst + imm );
  }
  public void jitSub64MemImm( long addr, long imm ) {
    long dst = mem.load64( addr );
    setFlags64Sub( dst, imm );
    mem.store64( addr, dst - imm );
  }
  public void jitXor64MemImm( long addr, long imm ) {
    long res = mem.load64( addr ) ^ imm;
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitAnd64MemImm( long addr, long imm ) {
    long res = mem.load64( addr ) & imm;
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitOr64MemImm( long addr, long imm ) {
    long res = mem.load64( addr ) | imm;
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitCmp64MemImm( long addr, long imm ) {
    setFlags64Sub( mem.load64( addr ), imm );
  }

  // Phase 34-A3 step 27: ALU [mem], r 用 helper (0x01/0x09/0x21/0x29/0x31/0x39)
  // r/m,r 形: dst = mem[addr] (RW), src = r64[srcReg]
  public void jitAdd64MemR( long addr, int srcReg ) {
    long dst = mem.load64( addr );
    long src = r64[ srcReg ];
    setFlags64Add( dst, src );
    mem.store64( addr, dst + src );
  }
  public void jitSub64MemR( long addr, int srcReg ) {
    long dst = mem.load64( addr );
    long src = r64[ srcReg ];
    setFlags64Sub( dst, src );
    mem.store64( addr, dst - src );
  }
  public void jitXor64MemR( long addr, int srcReg ) {
    long res = mem.load64( addr ) ^ r64[ srcReg ];
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitAnd64MemR( long addr, int srcReg ) {
    long res = mem.load64( addr ) & r64[ srcReg ];
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitOr64MemR( long addr, int srcReg ) {
    long res = mem.load64( addr ) | r64[ srcReg ];
    mem.store64( addr, res );
    setFlagsLogic64( res );
  }
  public void jitCmp64MemR( long addr, int srcReg ) {
    setFlags64Sub( mem.load64( addr ), r64[ srcReg ] );
  }
  public void jitTest64MemR( long addr, int srcReg ) {
    setFlagsLogic64( mem.load64( addr ) & r64[ srcReg ] );
  }

  // ALU r, [mem] 用 helper (0x03/0x0B/0x23/0x2B/0x33/0x3B)
  // r,r/m 形: dst = r64[dstReg] (RW), src = mem[addr]
  public void jitAdd64RMem( int dstReg, long addr ) {
    long dst = r64[ dstReg ];
    long src = mem.load64( addr );
    setFlags64Add( dst, src );
    r64[ dstReg ] = dst + src;
  }
  public void jitSub64RMem( int dstReg, long addr ) {
    long dst = r64[ dstReg ];
    long src = mem.load64( addr );
    setFlags64Sub( dst, src );
    r64[ dstReg ] = dst - src;
  }
  public void jitXor64RMem( int dstReg, long addr ) {
    long res = r64[ dstReg ] ^ mem.load64( addr );
    r64[ dstReg ] = res;
    setFlagsLogic64( res );
  }
  public void jitAnd64RMem( int dstReg, long addr ) {
    long res = r64[ dstReg ] & mem.load64( addr );
    r64[ dstReg ] = res;
    setFlagsLogic64( res );
  }
  public void jitOr64RMem( int dstReg, long addr ) {
    long res = r64[ dstReg ] | mem.load64( addr );
    r64[ dstReg ] = res;
    setFlagsLogic64( res );
  }
  public void jitCmp64RMem( int dstReg, long addr ) {
    setFlags64Sub( r64[ dstReg ], mem.load64( addr ) );
  }

  // ----------------------------------------------------------------------
  // Phase 34-A5: SIMD 命令 (AES-NI / PCLMULQDQ / PXOR / MOVDQA) JIT helper
  // 全て XMM register-register form (mod==3) 専用。interpreter の同等
  // ロジックを wrapping して public method として export。
  // ----------------------------------------------------------------------
  /** PXOR xmm_dst, xmm_src — xmm_dst ^= xmm_src */
  public void jitPxor( int dstIdx, int srcIdx ) {
    xmm_lo[ dstIdx ] ^= xmm_lo[ srcIdx ];
    xmm_hi[ dstIdx ] ^= xmm_hi[ srcIdx ];
  }
  /** MOVDQA / MOVDQU xmm_dst, xmm_src — xmm_dst = xmm_src (register form) */
  public void jitMovdqaReg( int dstIdx, int srcIdx ) {
    xmm_lo[ dstIdx ] = xmm_lo[ srcIdx ];
    xmm_hi[ dstIdx ] = xmm_hi[ srcIdx ];
  }

  /** AESENC xmm1, xmm2: ShiftRows; SubBytes; MixColumns; XOR xmm2 */
  public void jitAesEnc( int dstIdx, int srcIdx ) {
    long sl = xmm_lo[ srcIdx ], sh = xmm_hi[ srcIdx ];
    byte[] state = aesUnpack( xmm_lo[ dstIdx ], xmm_hi[ dstIdx ] );
    aesShiftRows( state );
    aesSubBytes( state );
    aesMixColumns( state );
    xmm_lo[ dstIdx ] = aesPackLo( state ) ^ sl;
    xmm_hi[ dstIdx ] = aesPackHi( state ) ^ sh;
  }
  /** AESENCLAST: 上記から MixColumns を除いたもの */
  public void jitAesEncLast( int dstIdx, int srcIdx ) {
    long sl = xmm_lo[ srcIdx ], sh = xmm_hi[ srcIdx ];
    byte[] state = aesUnpack( xmm_lo[ dstIdx ], xmm_hi[ dstIdx ] );
    aesShiftRows( state );
    aesSubBytes( state );
    xmm_lo[ dstIdx ] = aesPackLo( state ) ^ sl;
    xmm_hi[ dstIdx ] = aesPackHi( state ) ^ sh;
  }
  /** AESDEC: InvShiftRows; InvSubBytes; InvMixColumns; XOR */
  public void jitAesDec( int dstIdx, int srcIdx ) {
    long sl = xmm_lo[ srcIdx ], sh = xmm_hi[ srcIdx ];
    byte[] state = aesUnpack( xmm_lo[ dstIdx ], xmm_hi[ dstIdx ] );
    aesInvShiftRows( state );
    aesInvSubBytes( state );
    aesInvMixColumns( state );
    xmm_lo[ dstIdx ] = aesPackLo( state ) ^ sl;
    xmm_hi[ dstIdx ] = aesPackHi( state ) ^ sh;
  }
  /** AESDECLAST: 上記から InvMixColumns を除いたもの */
  public void jitAesDecLast( int dstIdx, int srcIdx ) {
    long sl = xmm_lo[ srcIdx ], sh = xmm_hi[ srcIdx ];
    byte[] state = aesUnpack( xmm_lo[ dstIdx ], xmm_hi[ dstIdx ] );
    aesInvShiftRows( state );
    aesInvSubBytes( state );
    xmm_lo[ dstIdx ] = aesPackLo( state ) ^ sl;
    xmm_hi[ dstIdx ] = aesPackHi( state ) ^ sh;
  }
  /** AESIMC xmm1, xmm2: dst = InvMixColumns(src) (XOR なし) */
  public void jitAesImc( int dstIdx, int srcIdx ) {
    byte[] state = aesUnpack( xmm_lo[ srcIdx ], xmm_hi[ srcIdx ] );
    aesInvMixColumns( state );
    xmm_lo[ dstIdx ] = aesPackLo( state );
    xmm_hi[ dstIdx ] = aesPackHi( state );
  }
  /** AESKEYGENASSIST xmm1, xmm2, imm8: AES key schedule helper */
  public void jitAesKeyGenAssist( int dstIdx, int srcIdx, int imm ) {
    long sl = xmm_lo[ srcIdx ], sh = xmm_hi[ srcIdx ];
    int x1 = (int)((sl >>> 32) & 0xFFFFFFFFL);
    int x3 = (int)((sh >>> 32) & 0xFFFFFFFFL);
    int sub1 = aesSubWord( x1 );
    int sub3 = aesSubWord( x3 );
    int rot1 = aesRotWord( sub1 ) ^ imm;
    int rot3 = aesRotWord( sub3 ) ^ imm;
    xmm_lo[ dstIdx ] = (((long)sub1) & 0xFFFFFFFFL) | (((long)rot1) << 32);
    xmm_hi[ dstIdx ] = (((long)sub3) & 0xFFFFFFFFL) | (((long)rot3) << 32);
  }
  /** PCLMULQDQ xmm1, xmm2, imm8: 64x64 → 128bit carry-less multiply (GHASH/GCM) */
  public void jitPclmulqdq( int dstIdx, int srcIdx, int imm ) {
    long da = ((imm & 0x01) != 0) ? xmm_hi[ dstIdx ] : xmm_lo[ dstIdx ];
    long db = ((imm & 0x10) != 0) ? xmm_hi[ srcIdx ] : xmm_lo[ srcIdx ];
    long rlo = 0, rhi = 0;
    for( int i = 0; i < 64; i++ ) {
      if( ((db >>> i) & 1L) != 0 ) {
        if( i == 0 ) rlo ^= da;
        else { rlo ^= (da << i); rhi ^= (da >>> (64 - i)); }
      }
    }
    xmm_lo[ dstIdx ] = rlo;
    xmm_hi[ dstIdx ] = rhi;
  }

  // Phase 34-A3 step 29: 0xFF /2 CALL r/m (mod==3) 用 helper
  // push next_rip then jump to r64[targetReg]
  // 戻り値で新 rip を返す (block 終端としてそのまま return される)
  public long jitCallIndirectReg( int targetReg, long nextRip ) {
    long sp = r64[ R_RSP ] - 8L;
    r64[ R_RSP ] = sp;
    mem.store64( sp, nextRip );
    return r64[ targetReg ];
  }

  // Phase 34-A3 step 31: 0xFF /2 CALL [mem] 用 helper (memory operand)
  // target は mem.load64(addr) で取得
  public long jitCallIndirectMem( long addr, long nextRip ) {
    long target = mem.load64( addr );
    long sp = r64[ R_RSP ] - 8L;
    r64[ R_RSP ] = sp;
    mem.store64( sp, nextRip );
    return target;
  }

  // --- メイン デコード+実行 ---

  // Phase 34-A2 incremental: opcode handler を decode_and_exec から個別 method に抽出。
  // 1 opcode ずつ extract → fast regression check の repetitive process。最終的に
  // decode_and_exec は thin dispatcher だけ残し、各 opcode は per-method で見通し向上。

  // MOV r/m, r (opcode 0x89): r → r/m
  private long exec_mov_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w )       writeRM64( r64[mrm_reg] );
    else if( op66 )   writeRM16( r64[mrm_reg] & 0xFFFFL );
    else              writeRM32( r64[mrm_reg] );
    return next;
  }
  // MOV r, r/m (opcode 0x8B): r/m → r
  private long exec_mov_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w )       r64[mrm_reg] = readRM64();
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (readRM16() & 0xFFFFL);
    else              r64[mrm_reg] = readRM32();
    return next;
  }
  // Grp1 r/m, imm8 (opcode 0x83): ADD/OR/SUB/AND/XOR/CMP
  private long exec_grp1_imm8( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    long imm  = (long)(byte)mem.load8(next); next++;
    fixEA( next, fs_prefix );
    return execGrp1( imm, rex_w, op66, next );
  }
  // CMP r/m, r (opcode 0x39)
  private long exec_cmp_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w )       setFlags64Sub( readRM64(), r64[mrm_reg] );
    else if( op66 )   setFlags16Sub( readRM16() & 0xFFFFL, r64[mrm_reg] & 0xFFFFL );
    else              setFlags32Sub( readRM32() & 0xFFFFFFFFL, r64[mrm_reg] & 0xFFFFFFFFL );
    return next;
  }
  // TEST r/m, r (opcode 0x85)
  private long exec_test_rm_r( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = readRM64() & r64[mrm_reg];
      zf = (res==0) ? 1 : 0;
      sf = (res<0)  ? 1 : 0;
    } else if( op66 ) {
      long res = (readRM16() & r64[mrm_reg]) & 0xFFFFL;
      zf = (res==0) ? 1 : 0;
      sf = (int)(res>>15) & 1;
    } else {
      long res = (readRM32() & r64[mrm_reg]) & 0xFFFFFFFFL;
      zf = (res==0) ? 1 : 0;
      sf = (int)(res>>31) & 1;
    }
    of = 0; cf = 0;
    return next;
  }
  // CMP r, r/m (opcode 0x3B)
  private long exec_cmp_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w )       setFlags64Sub( r64[mrm_reg], readRM64() );
    else if( op66 )   setFlags16Sub( r64[mrm_reg] & 0xFFFFL, readRM16() & 0xFFFFL );
    else              setFlags32Sub( r64[mrm_reg] & 0xFFFFFFFFL, readRM32() & 0xFFFFFFFFL );
    return next;
  }
  // ADD r/m, r (opcode 0x01)
  private long exec_add_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src = r64[mrm_reg], dst = readRM64();
      setFlags64Add( dst, src );
      writeRM64( dst + src );
    } else if( op66 ) {
      long src = r64[mrm_reg] & 0xFFFFL, dst = readRM16() & 0xFFFFL;
      setFlags16Add( dst, src );
      writeRM16( (dst+src) & 0xFFFFL );
    } else {
      long src = r64[mrm_reg] & 0xFFFFFFFFL, dst = readRM32() & 0xFFFFFFFFL;
      setFlags32Add( dst, src );
      writeRM32( (dst+src) & 0xFFFFFFFFL );
    }
    return next;
  }
  // ADD r, r/m (opcode 0x03)
  private long exec_add_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src = readRM64(), dst = r64[mrm_reg];
      setFlags64Add( dst, src );
      r64[mrm_reg] = dst + src;
    } else if( op66 ) {
      long src = readRM16() & 0xFFFFL, dst = r64[mrm_reg] & 0xFFFFL;
      setFlags16Add( dst, src );
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | ((dst+src) & 0xFFFFL);
    } else {
      long src = readRM32() & 0xFFFFFFFFL, dst = r64[mrm_reg] & 0xFFFFFFFFL;
      setFlags32Add( dst, src );
      r64[mrm_reg] = (dst+src) & 0xFFFFFFFFL;
    }
    return next;
  }
  // SUB r/m, r (opcode 0x29)
  private long exec_sub_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src = r64[mrm_reg], dst = readRM64();
      setFlags64Sub( dst, src );
      writeRM64( dst - src );
    } else if( op66 ) {
      long src = r64[mrm_reg] & 0xFFFFL, dst = readRM16() & 0xFFFFL;
      setFlags16Sub( dst, src );
      writeRM16( (dst-src) & 0xFFFFL );
    } else {
      long src = r64[mrm_reg] & 0xFFFFFFFFL, dst = readRM32() & 0xFFFFFFFFL;
      setFlags32Sub( dst, src );
      writeRM32( (dst-src) & 0xFFFFFFFFL );
    }
    return next;
  }
  // SUB r, r/m (opcode 0x2B)
  private long exec_sub_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src = readRM64(), dst = r64[mrm_reg];
      setFlags64Sub( dst, src );
      r64[mrm_reg] = dst - src;
    } else if( op66 ) {
      long src = readRM16() & 0xFFFFL, dst = r64[mrm_reg] & 0xFFFFL;
      setFlags16Sub( dst, src );
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | ((dst-src) & 0xFFFFL);
    } else {
      long src = readRM32() & 0xFFFFFFFFL, dst = r64[mrm_reg] & 0xFFFFFFFFL;
      setFlags32Sub( dst, src );
      r64[mrm_reg] = (dst-src) & 0xFFFFFFFFL;
    }
    return next;
  }
  // 共通 logic op flag セット
  private void setFlagsLogic64( long res ) {
    zf = (res==0) ? 1 : 0;
    sf = (res<0)  ? 1 : 0;
    of = 0; cf = 0;
  }
  private void setFlagsLogic16( long res ) {
    res &= 0xFFFFL;
    zf = (res==0) ? 1 : 0;
    sf = (int)(res>>15) & 1;
    of = 0; cf = 0;
  }
  private void setFlagsLogic32( long res ) {
    res &= 0xFFFFFFFFL;
    zf = (res==0) ? 1 : 0;
    sf = (int)(res>>31) & 1;
    of = 0; cf = 0;
  }
  // XOR r/m, r (opcode 0x31)
  private long exec_xor_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = readRM64() ^ r64[mrm_reg];
      writeRM64( res ); setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (readRM16() ^ r64[mrm_reg]) & 0xFFFFL;
      writeRM16( res ); setFlagsLogic16( res );
    } else {
      long res = (readRM32() ^ r64[mrm_reg]) & 0xFFFFFFFFL;
      writeRM32( res ); setFlagsLogic32( res );
    }
    return next;
  }
  // XOR r, r/m (opcode 0x33)
  private long exec_xor_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = r64[mrm_reg] ^ readRM64();
      r64[mrm_reg] = res; setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (r64[mrm_reg] ^ readRM16()) & 0xFFFFL;
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | res; setFlagsLogic16( res );
    } else {
      long res = (r64[mrm_reg] ^ readRM32()) & 0xFFFFFFFFL;
      r64[mrm_reg] = res; setFlagsLogic32( res );
    }
    return next;
  }
  // AND r/m, r (opcode 0x21)
  private long exec_and_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = readRM64() & r64[mrm_reg];
      writeRM64( res ); setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (readRM16() & r64[mrm_reg]) & 0xFFFFL;
      writeRM16( res ); setFlagsLogic16( res );
    } else {
      long res = (readRM32() & r64[mrm_reg]) & 0xFFFFFFFFL;
      writeRM32( res ); setFlagsLogic32( res );
    }
    return next;
  }
  // AND r, r/m (opcode 0x23)
  private long exec_and_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = r64[mrm_reg] & readRM64();
      r64[mrm_reg] = res; setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (r64[mrm_reg] & readRM16()) & 0xFFFFL;
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | res; setFlagsLogic16( res );
    } else {
      long res = (r64[mrm_reg] & readRM32()) & 0xFFFFFFFFL;
      r64[mrm_reg] = res; setFlagsLogic32( res );
    }
    return next;
  }
  // OR r/m, r (opcode 0x09)
  private long exec_or_rm_r( long pc, boolean rex_w, boolean rex_r,
                             boolean rex_b, boolean rex_x,
                             boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = readRM64() | r64[mrm_reg];
      writeRM64( res ); setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (readRM16() | r64[mrm_reg]) & 0xFFFFL;
      writeRM16( res ); setFlagsLogic16( res );
    } else {
      long res = (readRM32() | r64[mrm_reg]) & 0xFFFFFFFFL;
      writeRM32( res ); setFlagsLogic32( res );
    }
    return next;
  }
  // OR r, r/m (opcode 0x0B)
  private long exec_or_r_rm( long pc, boolean rex_w, boolean rex_r,
                             boolean rex_b, boolean rex_x,
                             boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long res = r64[mrm_reg] | readRM64();
      r64[mrm_reg] = res; setFlagsLogic64( res );
    } else if( op66 ) {
      long res = (r64[mrm_reg] | readRM16()) & 0xFFFFL;
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | res; setFlagsLogic16( res );
    } else {
      long res = (r64[mrm_reg] | readRM32()) & 0xFFFFFFFFL;
      r64[mrm_reg] = res; setFlagsLogic32( res );
    }
    return next;
  }
  // LEA r, m (opcode 0x8D)
  private long exec_lea( long pc, boolean rex_w, boolean rex_r,
                         boolean rex_b, boolean rex_x,
                         boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, false );
    if( rex_w )       r64[mrm_reg] = mrm_ea;
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (mrm_ea & 0xFFFFL);
    else              r64[mrm_reg] = mrm_ea & 0xFFFFFFFFL;
    return next;
  }
  // MOV r/m8, r8 (opcode 0x88)
  private long exec_mov8_rm_r( long pc, boolean rex_r, boolean rex_b,
                               boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    writeRM8( readReg8( mrm_reg ) );
    return next;
  }
  // MOV r8, r/m8 (opcode 0x8A)
  private long exec_mov8_r_rm( long pc, boolean rex_r, boolean rex_b,
                               boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    writeReg8( mrm_reg, readRM8() );
    return next;
  }
  // TEST r/m8, r8 (opcode 0x84)
  private long exec_test8_rm_r( long pc, boolean rex_r, boolean rex_b,
                                boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    long res = (readRM8() & readReg8(mrm_reg)) & 0xFFL;
    zf = (res==0) ? 1 : 0;
    sf = (int)(res>>7) & 1;
    of = 0; cf = 0;
    return next;
  }
  // Grp1 r/m, imm32 (opcode 0x81): ADD/OR/SUB/AND/XOR/CMP. op66 のとき imm16
  private long exec_grp1_imm32( long pc, boolean rex_w, boolean rex_r,
                                boolean rex_b, boolean rex_x,
                                boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    long imm;
    if( op66 ) {
      imm = (long)(short)loadImm16( next );
      next += 2;
    } else {
      imm = (long)(int)loadImm32u( next );
      next += 4;
    }
    fixEA( next, fs_prefix );
    return execGrp1( imm, rex_w, op66, next );
  }
  // MOV r/m64/32/16, imm (opcode 0xC7)
  private long exec_mov_rm_imm( long pc, boolean rex_w, boolean rex_r,
                                boolean rex_b, boolean rex_x,
                                boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    long imm;
    if( op66 && !rex_w ) { imm = mem.load16( next ) & 0xFFFFL; next += 2; }
    else                 { imm = (long)(int)loadImm32u(next); next += 4; }
    fixEA( next, fs_prefix );
    if( mrm_reg == 0 ) {
      if( rex_w )       writeRM64( imm );
      else if( op66 )   writeRM16( (short)imm );
      else              writeRM32( imm );
    }
    return next;
  }
  // MOV r8, imm8 (opcode 0xB0-0xB7)
  private long exec_mov8_r_imm( long pc, int b0, boolean rex_b ) {
    int idx = (b0 & 7) | (rex_b ? 8 : 0);
    long imm = mem.load8( pc+1 ) & 0xFFL;
    writeReg8( idx, imm );
    return pc + 2;
  }
  // MOV r32/r64, imm (opcode 0xB8-0xBF)
  private long exec_mov_r_imm( long pc, int b0, boolean rex_w, boolean rex_b ) {
    int rd = (b0 & 7) | (rex_b ? 8 : 0);
    if( rex_w ) {
      r64[rd] = loadImm64( pc+1 );
      return pc + 9;
    } else {
      r64[rd] = loadImm32u( pc+1 );
      return pc + 5;
    }
  }
  // ADC r/m, r (opcode 0x11) — CF chain は bignum で critical
  private long exec_adc_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src=r64[mrm_reg], dst=readRM64();
      writeRM64( adc64(dst,src,cf) );
    } else if( op66 ) {
      long src=r64[mrm_reg]&0xFFFFL, dst=readRM16()&0xFFFFL;
      writeRM16( adc16(dst,src,cf) & 0xFFFFL );
    } else {
      long src=r64[mrm_reg]&0xFFFFFFFFL, dst=readRM32()&0xFFFFFFFFL;
      writeRM32( adc32(dst,src,cf) & 0xFFFFFFFFL );
    }
    return next;
  }
  // ADC r, r/m (opcode 0x13)
  private long exec_adc_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      r64[mrm_reg] = adc64( r64[mrm_reg], readRM64(), cf );
    } else if( op66 ) {
      long res = adc16( r64[mrm_reg]&0xFFFFL, readRM16()&0xFFFFL, cf );
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    } else {
      long res = adc32( r64[mrm_reg]&0xFFFFFFFFL, readRM32()&0xFFFFFFFFL, cf );
      r64[mrm_reg] = res & 0xFFFFFFFFL;
    }
    return next;
  }
  // SBB r/m, r (opcode 0x19)
  private long exec_sbb_rm_r( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      long src=r64[mrm_reg], dst=readRM64();
      writeRM64( sbb64(dst,src,cf) );
    } else if( op66 ) {
      long src=r64[mrm_reg]&0xFFFFL, dst=readRM16()&0xFFFFL;
      writeRM16( sbb16(dst,src,cf) & 0xFFFFL );
    } else {
      long src=r64[mrm_reg]&0xFFFFFFFFL, dst=readRM32()&0xFFFFFFFFL;
      writeRM32( sbb32(dst,src,cf) & 0xFFFFFFFFL );
    }
    return next;
  }
  // SBB r, r/m (opcode 0x1B)
  private long exec_sbb_r_rm( long pc, boolean rex_w, boolean rex_r,
                              boolean rex_b, boolean rex_x,
                              boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w ) {
      r64[mrm_reg] = sbb64( r64[mrm_reg], readRM64(), cf );
    } else if( op66 ) {
      long res = sbb16( r64[mrm_reg]&0xFFFFL, readRM16()&0xFFFFL, cf );
      r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    } else {
      long res = sbb32( r64[mrm_reg]&0xFFFFFFFFL, readRM32()&0xFFFFFFFFL, cf );
      r64[mrm_reg] = res & 0xFFFFFFFFL;
    }
    return next;
  }
  // Grp1 r/m8, imm8 (opcode 0x80): ADD/OR/AND/SUB/XOR/CMP の 8-bit 版
  private long exec_grp1_imm8_8bit( long pc, boolean rex_r, boolean rex_b,
                                    boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    long imm = mem.load8(next) & 0xFFL; next++;
    fixEA( next, fs_prefix );
    long dst = readRM8(), res;
    switch( mrm_reg ) {
      case 0: res=(dst+imm)&0xFF; writeRM8(res); break;
      case 1: res=(dst|imm)&0xFF; writeRM8(res);
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; return next;
      case 4: res=(dst&imm)&0xFF; writeRM8(res);
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; return next;
      case 5: res=(dst-imm)&0xFF; writeRM8(res); break;
      case 6: res=(dst^imm)&0xFF; writeRM8(res);
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; return next;
      case 7: // CMP r/m8, imm8 — flag のみ
        res = (dst-imm) & 0xFF;
        zf = (res==0) ? 1 : 0;
        sf = (int)(res>>7) & 1;
        of = (int)(((dst^imm)&(dst^res))>>7) & 1;
        cf = Long.compareUnsigned(dst,imm) < 0 ? 1 : 0;
        return next;
      default: res = dst;
    }
    zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0;
    return next;
  }
  // MOV r/m8, imm8 (opcode 0xC6)
  private long exec_mov8_rm_imm( long pc, boolean rex_r, boolean rex_b,
                                 boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    long imm = mem.load8(next) & 0xFFL; next++;
    fixEA( next, fs_prefix );
    if( mrm_reg == 0 ) writeRM8( imm );
    return next;
  }
  // IMUL r, r/m, imm8 (opcode 0x6B): sign-extend imm8
  private long exec_imul_rm_imm8( long pc, boolean rex_w, boolean rex_r,
                                  boolean rex_b, boolean rex_x,
                                  boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    long src = rex_w ? readRM64()
             : (op66 ? (long)(short)readRM16() : (long)(int)readRM32());
    long imm = (long)(byte)mem.load8(next); next++;
    long res = src * imm;
    if( rex_w )       r64[mrm_reg] = res;
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    else              r64[mrm_reg] = res & 0xFFFFFFFFL;
    return next;
  }
  // IMUL r, r/m, imm32/imm16 (opcode 0x69)
  private long exec_imul_rm_imm( long pc, boolean rex_w, boolean rex_r,
                                 boolean rex_b, boolean rex_x,
                                 boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    long src = rex_w ? readRM64()
             : (op66 ? (long)(short)readRM16() : (long)(int)readRM32());
    long imm;
    if( op66 ) { imm = (long)(short)loadImm16(next); next += 2; }
    else       { imm = (long)(int)loadImm32u(next);  next += 4; }
    long res = src * imm;
    if( rex_w )       r64[mrm_reg] = res;
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    else              r64[mrm_reg] = res & 0xFFFFFFFFL;
    return next;
  }
  // MOVSXD r64, r/m32 (opcode 0x63): sign-extend 32→64
  private long exec_movsxd( long pc, boolean rex_w, boolean rex_r,
                            boolean rex_b, boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    if( rex_w )   r64[mrm_reg] = (long)(int)readRM32();
    else          r64[mrm_reg] = readRM32() & 0xFFFFFFFFL;
    return next;
  }
  // Grp2 shift/rotate (opcode 0xC1/0xD1/0xD3): SHL/SHR/SAR/ROL/ROR
  //   0xD1: count=1、0xD3: count=CL、0xC1: count=imm8
  //   mrm_reg: 0=ROL 1=ROR 4=SHL 5=SHR 7=SAR
  private long exec_grp2_shift( long pc, int b0, boolean rex_w, boolean rex_r,
                                boolean rex_b, boolean rex_x,
                                boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    int countMask = rex_w ? 0x3F : 0x1F;
    int count;
    if( b0 == 0xD1 )       count = 1;
    else if( b0 == 0xD3 )  count = (int)(r64[R_RCX] & countMask);
    else                   { count = mem.load8(next) & countMask; next++; }
    fixEA( next, fs_prefix );
    int  size = rex_w ? 64 : (op66 ? 16 : 32);
    long mask = rex_w ? -1L : (op66 ? 0xFFFFL : 0xFFFFFFFFL);
    long val  = rex_w ? readRM64()
              : (op66 ? (readRM16()&0xFFFFL) : (readRM32()&0xFFFFFFFFL));
    long res = val;
    switch( mrm_reg ) {
      case 4: // SHL
        res = (val << count) & mask;
        if( count > 0 ) cf = (int)(val >> (size-count)) & 1;
        break;
      case 5: // SHR (logical)
        res = (val & mask) >>> count;
        if( count > 0 ) cf = (int)(val >> (count-1)) & 1;
        break;
      case 7: // SAR (arithmetic)
        if( rex_w )     res = val >> count;
        else if( op66 ) res = ((long)(short)val >> count) & 0xFFFFL;
        else            res = ((long)(int)val   >> count) & 0xFFFFFFFFL;
        if( count > 0 ) cf = (int)(val >> (count-1)) & 1;
        break;
      case 0: // ROL
        if( count > 0 ) {
          int n = count % size;
          res = ((val << n) | ((val & mask) >>> (size-n))) & mask;
          cf = (int)res & 1;
        }
        break;
      case 1: // ROR
        if( count > 0 ) {
          int n = count % size;
          res = (((val & mask) >>> n) | (val << (size-n))) & mask;
          cf = (int)(res >> (size-1)) & 1;
        }
        break;
      default:
        process.println("Cpu64: unsupported Grp2 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    if( rex_w )         { zf=(res==0)?1:0; sf=(res<0)?1:0; writeRM64(res); }
    else if( op66 )     { res &= 0xFFFFL;     zf=(res==0)?1:0; sf=(int)(res>>15)&1; writeRM16(res); }
    else                { res &= 0xFFFFFFFFL; zf=(res==0)?1:0; sf=(int)(res>>31)&1; writeRM32(res); }
    of = 0;
    return next;
  }
  // Group 5 (opcode 0xFF): INC/DEC/CALL/JMP/PUSH r/m (sub-opcode は mrm_reg)
  private long exec_grp5_ff( long pc, boolean rex_w, boolean rex_r,
                             boolean rex_b, boolean rex_x,
                             boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    switch( mrm_reg ) {
      case 0: { // INC r/m (CF 保存)
        int saved_cf = cf;
        if( rex_w )       { long v=readRM64(), r=v+1; setFlags64Add(v,1); writeRM64(r); }
        else if( op66 )   { long v=readRM16()&0xFFFFL, r=(v+1)&0xFFFFL; setFlags16Add(v,1); writeRM16(r); }
        else              { long v=readRM32()&0xFFFFFFFFL, r=(v+1)&0xFFFFFFFFL; setFlags32Add(v,1); writeRM32(r); }
        cf = saved_cf;
        break;
      }
      case 1: { // DEC r/m (CF 保存)
        int saved_cf = cf;
        if( rex_w )       { long v=readRM64(), r=v-1; setFlags64Sub(v,1); writeRM64(r); }
        else if( op66 )   { long v=readRM16()&0xFFFFL, r=(v-1)&0xFFFFL; setFlags16Sub(v,1); writeRM16(r); }
        else              { long v=readRM32()&0xFFFFFFFFL, r=(v-1)&0xFFFFFFFFL; setFlags32Sub(v,1); writeRM32(r); }
        cf = saved_cf;
        break;
      }
      case 2: { // CALL r/m: push next then jump
        long tgt = readRM64();
        push64( next );
        return tgt;
      }
      case 4:    // JMP r/m
        return readRM64();
      case 6:    // PUSH r/m
        push64( readRM64() );
        break;
      default:
        process.println("Cpu64: unsupported FF /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    return next;
  }

  // 8-bit ALU r/m8 ⇔ r8 (00/02 ADD, 08/0A OR, 10/12 ADC, 18/1A SBB,
  // 20/22 AND, 28/2A SUB, 30/32 XOR, 38/3A CMP)。bit1=0 → dst=r/m,
  // bit1=1 → dst=r。bits[5:3] が op 種別 (0 ADD, 1 OR, 2 ADC, 3 SBB,
  // 4 AND, 5 SUB, 6 XOR, 7 CMP)。
  private long exec_alu8( long pc, int b0, boolean rex_r, boolean rex_b,
                          boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    fixEA( next, fs_prefix );
    int op   = (b0 >> 3) & 7;     // 0..7
    boolean to_reg = (b0 & 2) != 0;
    long dst = to_reg ? readReg8(mrm_reg) : readRM8();
    long src = to_reg ? readRM8()         : readReg8(mrm_reg);
    long res;
    switch( op ) {
      case 0: res = (dst + src) & 0xFF; break;                     // ADD (00/02) — フラグ未更新 (旧コードに合わせる)
      case 1: res = (dst | src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break; // OR
      case 2: res = adc8(dst, src, cf); break;                     // ADC (adc8 内でフラグ更新)
      case 3: res = sbb8(dst, src, cf); break;                     // SBB
      case 4: res = (dst & src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break; // AND
      case 5: res = (dst - src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1;
              of=(int)(((dst^src)&(dst^res))>>7)&1;
              cf=Long.compareUnsigned(dst,src)<0?1:0; break;       // SUB
      case 6: res = (dst ^ src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break; // XOR
      case 7: res = (dst - src) & 0xFF;                            // CMP — 書き戻さない
              zf=(res==0)?1:0; sf=(int)(res>>7)&1;
              of=(int)(((dst^src)&(dst^res))>>7)&1;
              cf=Long.compareUnsigned(dst,src)<0?1:0;
              return next;
      default: res = 0;
    }
    if( to_reg ) writeReg8(mrm_reg, res);
    else         writeRM8(res);
    return next;
  }

  // ALU accumulator, imm8 short forms (04 ADD/0C OR/14 ADC/1C SBB/
  // 24 AND/2C SUB/34 XOR/3C CMP).
  private long exec_alu_al_imm8( long pc, int b0 ) {
    long imm = mem.load8(pc+1) & 0xFFL;
    long al  = r64[R_RAX] & 0xFFL;
    long res;
    boolean write = true;
    switch( b0 ) {
      case 0x04: case 0x14: res=(al+imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      case 0x0C: res=(al|imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      case 0x24: res=(al&imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      case 0x1C: case 0x2C:
        res=(al-imm)&0xFF; cf=Long.compareUnsigned(al,imm)<0?1:0;
        zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; break;
      case 0x34: res=(al^imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      default:   // 0x3C CMP
        res=(al-imm)&0xFF; cf=Long.compareUnsigned(al,imm)<0?1:0;
        zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; write=false; break;
    }
    if( write ) r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res;
    return pc+2;
  }

  // ALU accumulator, imm short forms (05 ADD/0D OR/15 ADC/1D SBB/
  // 25 AND/2D SUB/35 XOR/3D CMP)。66 prefix=16bit, REX.W=64bit, default=32bit.
  private long exec_alu_rax_imm( long pc, int b0, boolean rex_w, boolean op66 ) {
    long imm, next;
    if( op66 && !rex_w ) { imm = (long)(short)mem.load16(pc+1); next = pc+3; }
    else                 { imm = (long)(int)loadImm32u(pc+1);   next = pc+5; }
    long a = rex_w ? r64[R_RAX]
           : op66  ? (r64[R_RAX]&0xFFFFL) : (r64[R_RAX]&0xFFFFFFFFL);
    long mask    = rex_w ? -1L : op66 ? 0xFFFFL : 0xFFFFFFFFL;
    int  signbit = rex_w ? 63  : op66 ? 15      : 31;
    long res;
    if( b0==0x05 || b0==0x15 ) {
      res = (a+imm) & mask;
      if( rex_w )     setFlags64Add(a,imm);
      else if( op66 ) { a&=0xFFFFL; imm&=0xFFFFL; long r2=a+imm; cf=((r2>>16)&1)==1?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm^0xFFFFL)&(a^r2))>>15)&1; }
      else            setFlags32Add(a,imm);
    } else if( b0==0x0D ) { res=(a|imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
    else if( b0==0x25 )   { res=(a&imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
    else if( b0==0x2D || b0==0x1D ) {
      res = (a-imm) & mask;
      if( rex_w )     setFlags64Sub(a,imm);
      else if( op66 ) { a&=0xFFFFL; imm&=0xFFFFL; long r2=(a-imm)&0xFFFFFFFFL; cf=Long.compareUnsigned(a,imm)<0?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm)&(a^r2))>>15)&1; }
      else            setFlags32Sub(a,imm);
    } else if( b0==0x35 ) { res=(a^imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
    else { // 0x3D CMP
      res = (a-imm) & mask;
      if( rex_w )     setFlags64Sub(a,imm);
      else if( op66 ) { a&=0xFFFFL; imm&=0xFFFFL; long r2=(a-imm)&0xFFFFFFFFL; cf=Long.compareUnsigned(a,imm)<0?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm)&(a^r2))>>15)&1; }
      else            setFlags32Sub(a,imm);
      return next;  // CMP doesn't write
    }
    if( rex_w )      r64[R_RAX]=res;
    else if( op66 )  r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(res&0xFFFFL);
    else             r64[R_RAX]=res&0xFFFFFFFFL;
    return next;
  }

  // TEST AL, imm8 (A8)
  private long exec_test_al_imm8( long pc ) {
    int imm = (int)mem.load8(pc+1) & 0xFF;
    long res = (r64[R_RAX]&0xFF) & imm;
    zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0;
    return pc+2;
  }

  // TEST AX/EAX/RAX, imm (A9)。REX.W: imm32 sign-ext, 0x66: imm16, default: imm32.
  private long exec_test_rax_imm( long pc, boolean rex_w, boolean op66 ) {
    if( rex_w ) {
      long imm=(long)(int)loadImm32u(pc+1);
      long res=r64[R_RAX]&imm;
      zf=(res==0)?1:0; sf=(res<0)?1:0; of=0; cf=0; return pc+5;
    }
    if( op66 ) {
      long imm=loadImm16(pc+1)&0xFFFFL;
      long res=(r64[R_RAX]&0xFFFFL)&imm;
      zf=(res==0)?1:0; sf=(int)(res>>15)&1; of=0; cf=0; return pc+3;
    }
    long imm=(long)(int)loadImm32u(pc+1);
    long res=(r64[R_RAX]&0xFFFFFFFFL)&imm&0xFFFFFFFFL;
    zf=(res==0)?1:0; sf=(int)(res>>31)&1; of=0; cf=0; return pc+5;
  }

  // Grp3 8-bit (F6): TEST/NOT/NEG/MUL/IMUL/DIV/IDIV r/m8
  private long exec_grp3_f6( long pc, boolean rex_r, boolean rex_b,
                             boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    long val, res, imm;
    if( mrm_reg == 0 ) {  // TEST has imm8 after ModRM
      imm = mem.load8(next) & 0xFFL; next++; fixEA(next, fs_prefix);
      res = readRM8() & imm;
      zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; return next;
    }
    fixEA(next, fs_prefix);
    switch( mrm_reg ) {
      case 2: writeRM8((~readRM8()) & 0xFF); break;          // NOT
      case 3: val=readRM8(); res=(-val)&0xFF; writeRM8(res); // NEG
              cf=(val!=0)?1:0; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=(val==0x80)?1:0; break;
      case 4: { // MUL r/m8: AX = AL * r/m8
        long al = r64[R_RAX] & 0xFFL, src = readRM8() & 0xFFL;
        long ax = al * src;
        r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | (ax & 0xFFFFL);
        cf = of = ((ax & 0xFF00L) != 0) ? 1 : 0;
        break; }
      case 5: { // IMUL r/m8
        long al  = (long)(byte)(r64[R_RAX] & 0xFFL);
        long src = (long)(byte)(readRM8() & 0xFFL);
        long ax  = al * src;
        r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | (ax & 0xFFFFL);
        long sx  = (long)(byte)(ax & 0xFFL);
        cf = of = (sx != ax) ? 1 : 0;
        break; }
      case 6: { // DIV r/m8: AL = AX/src, AH = AX%src
        long src = readRM8() & 0xFFL;
        if( src == 0 ) { process.println("Cpu64: DIV/0 (F6/6)"); process.set_exit_flag(); break; }
        long ax = r64[R_RAX] & 0xFFFFL;
        long q = ax / src, r = ax % src;
        r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | ((r << 8) & 0xFF00L) | (q & 0xFFL);
        break; }
      case 7: { // IDIV r/m8
        long src = (long)(byte)(readRM8() & 0xFFL);
        if( src == 0 ) { process.println("Cpu64: IDIV/0 (F6/7)"); process.set_exit_flag(); break; }
        long ax = (long)(short)(r64[R_RAX] & 0xFFFFL);
        long q = ax / src, r = ax % src;
        r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | ((r << 8) & 0xFF00L) | (q & 0xFFL);
        break; }
      default:
        process.println("Cpu64: unsupported F6 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    return next;
  }

  // Grp3 16/32/64-bit (F7): TEST/NOT/NEG/MUL/IMUL/DIV/IDIV r/m
  private long exec_grp3_f7( long pc, boolean rex_w, boolean rex_r,
                             boolean rex_b, boolean rex_x,
                             boolean op66, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    long val, res, imm;
    if( mrm_reg == 0 ) {  // TEST has imm after ModRM (operand-size dependent)
      if( rex_w ) { imm=(long)(int)loadImm32u(next); next+=4; fixEA(next,fs_prefix); val=readRM64(); res=val&imm;
                    zf=(res==0)?1:0; sf=(res<0)?1:0; of=0; cf=0; return next; }
      if( op66 )  { imm=loadImm16(next)&0xFFFFL; next+=2; fixEA(next,fs_prefix); val=readRM16()&0xFFFFL; res=val&imm;
                    zf=(res==0)?1:0; sf=(int)(res>>15)&1; of=0; cf=0; return next; }
      imm=(long)(int)loadImm32u(next); next+=4; fixEA(next,fs_prefix); val=readRM32()&0xFFFFFFFFL; res=val&imm&0xFFFFFFFFL;
      zf=(res==0)?1:0; sf=(int)(res>>31)&1; of=0; cf=0; return next;
    }
    fixEA(next, fs_prefix);
    switch( mrm_reg ) {
      case 2: // NOT (flags unchanged)
        if( rex_w )     writeRM64(~readRM64());
        else if( op66 ) writeRM16((~readRM16()) & 0xFFFFL);
        else            writeRM32((~readRM32()) & 0xFFFFFFFFL);
        break;
      case 3: // NEG
        if( rex_w )     { val=readRM64(); res=-val; setFlags64Sub(0,val); writeRM64(res); }
        else if( op66 ) { val=readRM16()&0xFFFFL; res=(-val)&0xFFFFL; setFlags16Sub(0,val); writeRM16(res); }
        else            { val=readRM32()&0xFFFFFFFFL; res=(-val)&0xFFFFFFFFL; setFlags32Sub(0,val); writeRM32(res); }
        break;
      case 4: // MUL
        val = rex_w ? readRM64() : readRM32();
        if( rex_w ) { long a=r64[R_RAX], b=val; long hi=Math.multiplyHigh(a,b); if(a<0)hi+=b; if(b<0)hi+=a; r64[R_RDX]=hi; r64[R_RAX]=a*b; cf=of=(hi!=0)?1:0; }
        else        { long p=(r64[R_RAX]&0xFFFFFFFFL)*(val&0xFFFFFFFFL); r64[R_RDX]=(p>>32)&0xFFFFFFFFL; r64[R_RAX]=p&0xFFFFFFFFL; cf=of=(r64[R_RDX]!=0)?1:0; }
        break;
      case 5: // IMUL
        val = rex_w ? readRM64() : (long)(int)readRM32();
        if( rex_w ) { long a=r64[R_RAX], b=val; r64[R_RDX]=Math.multiplyHigh(a,b); r64[R_RAX]=a*b; cf=of=(r64[R_RDX]!=(r64[R_RAX]>>63))?1:0; }
        else        { long p=(long)(int)r64[R_RAX]*(long)(int)val; r64[R_RDX]=(p>>32)&0xFFFFFFFFL; r64[R_RAX]=p&0xFFFFFFFFL; cf=of=0; }
        break;
      case 6: // DIV
        val = rex_w ? readRM64() : readRM32();
        if( val == 0 ) { process.println("Cpu64: DIV/0"); process.set_exit_flag(); break; }
        if( rex_w ) {
          java.math.BigInteger MOD64 = java.math.BigInteger.ONE.shiftLeft(64);
          java.math.BigInteger lo = new java.math.BigInteger(Long.toUnsignedString(r64[R_RAX]));
          java.math.BigInteger hi = new java.math.BigInteger(Long.toUnsignedString(r64[R_RDX]));
          java.math.BigInteger d  = hi.shiftLeft(64).or(lo);
          java.math.BigInteger v  = new java.math.BigInteger(Long.toUnsignedString(val));
          java.math.BigInteger[] qr = d.divideAndRemainder(v);
          r64[R_RAX] = qr[0].mod(MOD64).longValue();
          r64[R_RDX] = qr[1].mod(MOD64).longValue();
        } else {
          long d=((r64[R_RDX]&0xFFFFFFFFL)<<32)|(r64[R_RAX]&0xFFFFFFFFL); long v=val&0xFFFFFFFFL;
          r64[R_RAX]=Long.divideUnsigned(d,v)&0xFFFFFFFFL;
          r64[R_RDX]=Long.remainderUnsigned(d,v)&0xFFFFFFFFL;
        }
        break;
      case 7: // IDIV
        val = rex_w ? readRM64() : (long)(int)readRM32();
        if( val == 0 ) { process.println("Cpu64: IDIV/0"); process.set_exit_flag(); break; }
        if( rex_w ) {
          java.math.BigInteger MOD64 = java.math.BigInteger.ONE.shiftLeft(64);
          java.math.BigInteger lo = new java.math.BigInteger(Long.toUnsignedString(r64[R_RAX]));
          java.math.BigInteger hi = java.math.BigInteger.valueOf(r64[R_RDX]);
          java.math.BigInteger d  = hi.shiftLeft(64).or(lo);
          java.math.BigInteger v  = java.math.BigInteger.valueOf(val);
          java.math.BigInteger[] qr = d.divideAndRemainder(v);
          r64[R_RAX] = qr[0].mod(MOD64).longValue();
          r64[R_RDX] = qr[1].mod(MOD64).longValue();
        } else {
          long d=(long)(int)r64[R_RAX];
          r64[R_RAX]=(d/(long)(int)val)&0xFFFFFFFFL;
          r64[R_RDX]=(d%(long)(int)val)&0xFFFFFFFFL;
        }
        break;
      default:
        process.println("Cpu64: unsupported F7 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    return next;
  }

  // Grp4 (FE): INC/DEC r/m8 (CF unchanged)
  private long exec_grp4_fe( long pc, boolean rex_r, boolean rex_b,
                             boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    fixEA(next, fs_prefix);
    long val = readRM8();
    switch( mrm_reg ) {
      case 0: { long r=(val+1)&0xFF; writeRM8(r);
                zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x7F)?1:0; break; }
      case 1: { long r=(val-1)&0xFF; writeRM8(r);
                zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x80)?1:0; break; }
      default:
        process.println("Cpu64: unsupported FE /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    return next;
  }

  // Grp2 shift/rotate 8-bit (D0/D2/C0)
  private long exec_grp2_shift8( long pc, int b0, boolean rex_r, boolean rex_b,
                                 boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    int count;
    if( b0 == 0xD0 )      count = 1;
    else if( b0 == 0xD2 ) count = (int)(r64[R_RCX] & 0x1F);
    else                  { count = mem.load8(next) & 0x1F; next++; }
    fixEA(next, fs_prefix);
    long val = readRM8() & 0xFF, res = val;
    switch( mrm_reg ) {
      case 4: res=(val<<count)&0xFF;                   cf=(count>0)?(int)(val>>(8-count))&1:cf; break;  // SHL
      case 5: res=(val>>>count)&0xFF;                  if(count>0) cf=(int)(val>>(count-1))&1; break;  // SHR
      case 7: res=(long)((byte)val>>count)&0xFF;       if(count>0) cf=(int)(val>>(count-1))&1; break;  // SAR
      default:
        process.println("Cpu64: unsupported Grp2b /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    writeRM8(res); zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0;
    return next;
  }

  // XCHG r/m8, r8 (86) — implicit atomic; pthread spinlock 経由で使われる
  private long exec_xchg8_rm_r( long pc, boolean rex_r, boolean rex_b,
                                boolean rex_x, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    fixEA(next, fs_prefix);
    synchronized( mem ) {
      long tmp = readRM8();
      writeRM8(readReg8(mrm_reg));
      writeReg8(mrm_reg, tmp);
    }
    return next;
  }

  // XCHG r/m, r (87)
  private long exec_xchg_rm_r( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean op66, boolean fs_prefix ) {
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    fixEA(next, fs_prefix);
    synchronized( mem ) {
      if( rex_w )     { long tmp=readRM64(); writeRM64(r64[mrm_reg]); r64[mrm_reg]=tmp; }
      else if( op66 ) { long tmp=readRM16()&0xFFFFL; writeRM16(r64[mrm_reg]&0xFFFFL); r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|tmp; }
      else            { long tmp=readRM32()&0xFFFFFFFFL; writeRM32(r64[mrm_reg]&0xFFFFFFFFL); r64[mrm_reg]=tmp; }
    }
    return next;
  }

  // x87 FPU escape (D8-DF) — 制御ワード関連のみ実装 (FLDCW/FNSTCW/FNSTSW/
  // FNINIT)。その他は黙って NOP。FNSTCW/FNSTSW は 2 byte だけ store する
  // (32-bit に拡張すると saved rbp 破壊、Phase 29-B)。
  private long exec_x87_escape( long pc, int b0, boolean rex_r, boolean rex_b,
                                boolean rex_x, boolean fs_prefix ) {
    int mb  = mem.load8(pc+1) & 0xFF;
    int mod = (mb >> 6) & 3;
    int reg = (mb >> 3) & 7;
    if( mod == 3 ) {
      if( b0==0xDB && mb==0xE3 ) { fpu_cw = 0x037F; fpu_sw = 0; fpu_tag = 0xFFFF; }  // FNINIT
      return pc+2;
    }
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    fixEA(next, fs_prefix);
    if( b0==0xD9 && reg==5 )        fpu_cw = mem.load16(mrm_ea) & 0xFFFF;        // FLDCW
    else if( b0==0xD9 && reg==7 )   mem.store16(mrm_ea, (short)(fpu_cw & 0xFFFF)); // FNSTCW
    else if( b0==0xDD && reg==7 )   mem.store16(mrm_ea, (short)(fpu_sw & 0xFFFF)); // FNSTSW
    return next;
  }

  // 0F escape (two-byte opcode prefix)。SSE/SSE2/SSE3、CMOVcc、SETcc、
  // MOVZX/MOVSX、BSWAP、Jcc rel32、CPUID、SYSCALL、RDTSC、CMPXCHG など
  // 大量の 2-byte 命令を処理する。Phase 34-A2 step 17 で decode_and_exec
  // から本体を method 化 (1213 行)。
  private long exec_0f_escape( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean op66, boolean opF2, boolean fs_prefix ) {
      int b1 = mem.load8(pc+1) & 0xFF;
      if( PROFILE_OP ) OP_0F_COUNT[ b1 ]++;

      // F2 0F XX: SSE2 scalar double precision
      if( opF2 ) {
        long sn = decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(sn,fs_prefix);
        int xd=mrm_reg, xs=mrm_rm;
        // F2 0F 70: PSHUFLW xmm, xmm/m128, imm8 (SSE2)
        //   low 4 words shuffled by imm8, high 4 unchanged。Phase 27 step 41。
        if( b1==0x70 ) {
          long sl = (mrm_mod==3) ? xmm_lo[xs] : mem.load64(mrm_ea);
          long sh = (mrm_mod==3) ? xmm_hi[xs] : mem.load64(mrm_ea+8);
          int imm = mem.load8(sn) & 0xFF; sn++;
          int[] lw = new int[]{ (int)(sl & 0xFFFF), (int)((sl>>16) & 0xFFFF),
                                 (int)((sl>>32) & 0xFFFF), (int)((sl>>>48) & 0xFFFF) };
          long w0 = lw[imm&3], w1 = lw[(imm>>2)&3], w2 = lw[(imm>>4)&3], w3 = lw[(imm>>6)&3];
          xmm_lo[xd] = w0 | (w1<<16) | (w2<<32) | (w3<<48);
          xmm_hi[xd] = sh;  // high quad unchanged
          return sn;
        }
        // F2 0F 10: MOVSD xmm, xmm/m64
        if( b1==0x10 ) {
          if(mrm_mod==3) xmm_lo[xd]=xmm_lo[xs];
          else { xmm_lo[xd]=mem.load64(mrm_ea); xmm_hi[xd]=0; }
          return sn;
        }
        // F2 0F 11: MOVSD xmm/m64, xmm
        if( b1==0x11 ) {
          if(mrm_mod==3) xmm_lo[xs]=xmm_lo[xd];
          else mem.store64(mrm_ea, xmm_lo[xd]);
          return sn;
        }
        // F2 0F 2A: CVTSI2SD xmm, r/m32 (REX.W: r/m64)
        if( b1==0x2A ) {
          long src;
          if(mrm_mod==3) src = rex_w ? r64[mrm_rm] : (long)(int)r64[mrm_rm];
          else            src = rex_w ? mem.load64(mrm_ea) : (long)(int)mem.load32(mrm_ea);
          xmm_lo[xd] = Double.doubleToRawLongBits((double)src);
          return sn;
        }
        // F2 0F 2C: CVTTSD2SI r, xmm/m64 (truncate)
        // F2 0F 2D: CVTSD2SI r, xmm/m64 (round)
        if( b1==0x2C || b1==0x2D ) {
          long src;
          if(mrm_mod==3) src = xmm_lo[mrm_rm];
          else            src = mem.load64(mrm_ea);
          double d = Double.longBitsToDouble(src);
          long val = (b1==0x2C) ? (long)d : Math.round(d);
          if( rex_w ) r64[mrm_reg] = val;
          else        r64[mrm_reg] = val & 0xFFFFFFFFL;
          return sn;
        }
        // F2 0F 51: SQRTSD xmm, xmm/m64
        if( b1==0x51 ) {
          double d = (mrm_mod==3) ? Double.longBitsToDouble(xmm_lo[xs]) : Double.longBitsToDouble(mem.load64(mrm_ea));
          xmm_lo[xd] = Double.doubleToRawLongBits(Math.sqrt(d));
          return sn;
        }
        // F2 0F 5A: CVTSD2SS xmm, xmm/m64 — scalar double → single
        if( b1==0x5A ) {
          double d = (mrm_mod==3) ? Double.longBitsToDouble(xmm_lo[xs]) : Double.longBitsToDouble(mem.load64(mrm_ea));
          int bits = Float.floatToRawIntBits((float)d);
          xmm_lo[xd] = (xmm_lo[xd] & 0xFFFFFFFF00000000L) | (bits & 0xFFFFFFFFL);
          return sn;
        }
        // F2 0F C2 ib: CMPSD xmm, xmm/m64, imm8 — scalar double 比較し
        //   imm8 (0..7) の predicate に合致したら全 1 (-1L)、不合致は 0 を
        //   xmm の low 64bit に書く。glibc の libm / locale 経路で使われる。
        if( b1==0xC2 ) {
          double a = Double.longBitsToDouble(xmm_lo[xd]);
          double b = (mrm_mod==3) ? Double.longBitsToDouble(xmm_lo[xs]) : Double.longBitsToDouble(mem.load64(mrm_ea));
          int pred = mem.load8(sn) & 0xFF;
          boolean match;
          boolean unord = Double.isNaN(a) || Double.isNaN(b);
          switch( pred & 7 ) {
            case 0: match = !unord && a == b; break;             // EQ
            case 1: match = !unord && a <  b; break;             // LT
            case 2: match = !unord && a <= b; break;             // LE
            case 3: match = unord; break;                        // UNORD
            case 4: match = unord || a != b; break;              // NEQ
            case 5: match = unord || !(a <  b); break;           // NLT
            case 6: match = unord || !(a <= b); break;           // NLE
            default: match = !unord; break;                      // ORD
          }
          xmm_lo[xd] = match ? -1L : 0L;
          return sn + 1;  // imm8 を読み飛ばす
        }
        // F2 0F 58/59/5C/5D/5E/5F: ADDSD/MULSD/SUBSD/MINSD/DIVSD/MAXSD
        if( b1==0x58 || b1==0x59 || b1==0x5C || b1==0x5D || b1==0x5E || b1==0x5F ) {
          double a = Double.longBitsToDouble(xmm_lo[xd]);
          double b = (mrm_mod==3) ? Double.longBitsToDouble(xmm_lo[xs]) : Double.longBitsToDouble(mem.load64(mrm_ea));
          double r;
          if      (b1==0x58) r = a + b;
          else if (b1==0x59) r = a * b;
          else if (b1==0x5C) r = a - b;
          else if (b1==0x5D) r = Math.min(a, b);
          else if (b1==0x5E) r = a / b;
          else               r = Math.max(a, b);
          xmm_lo[xd] = Double.doubleToRawLongBits(r);
          return sn;
        }
        process.println("Cpu64: unsupported SSE2 F2 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }

      if( b1==0x05 ) return exec_syscall(pc+2); // SYSCALL
      if( (b1&0xF0)==0x80 ) { // Jcc rel32
        int rel32=(int)loadImm32u(pc+2); long next=pc+6;
        return evalCond(b1&0xF)?next+rel32:next;
      }
      if( (b1&0xF0)==0x40 ) { // CMOVcc r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        if(evalCond(b1&0xF)) { if(rex_w) r64[mrm_reg]=readRM64(); else r64[mrm_reg]=readRM32(); }
        return next;
      }
      if( (b1&0xF0)==0x90 ) { // SETcc r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long val=evalCond(b1&0xF)?1L:0L;
        writeRM8(val); return next;
      }
      if( b1==0x1F ) { // multi-byte NOP
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); return next;
      }
      if( b1==0xB0 ) { // CMPXCHG r/m8, r8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long dst=readRM8(), al=r64[R_RAX]&0xFFL;
        long diff=(al-dst)&0xFFL; zf=(diff==0)?1:0; sf=(int)(diff>>7)&1;
        if(zf==1) writeRM8(readReg8(mrm_reg)); else r64[R_RAX]=(r64[R_RAX]&~0xFFL)|dst;
        return next;
      }
      if( b1==0xB1 ) { // CMPXCHG r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        // Phase 27 step 29: pthread mutex の RMW を atomic にする。
        //   lock prefix は decoder 上は見ていないが、CMPXCHG は実機では
        //   ほぼ常に lock 付きで使われるので unconditionally synchronized。
        //   mem を共通 monitor にして全 thread の CMPXCHG を serialize。
        synchronized( mem ) {
          if(rex_w) {
            long dst=readRM64(), ax=r64[R_RAX];
            setFlags64Sub(ax,dst); zf=(ax==dst)?1:0;
            if(zf==1) writeRM64(r64[mrm_reg]); else r64[R_RAX]=dst;
          } else if(op66) {
            long dst=readRM16()&0xFFFFL, ax=r64[R_RAX]&0xFFFFL;
            setFlags16Sub(ax,dst); zf=(ax==dst)?1:0;
            if(zf==1) writeRM16(r64[mrm_reg]&0xFFFFL); else r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|dst;
          } else {
            long dst=readRM32()&0xFFFFFFFFL, ax=r64[R_RAX]&0xFFFFFFFFL;
            setFlags32Sub(ax,dst); zf=(ax==dst)?1:0;
            if(zf==1) writeRM32(r64[mrm_reg]); else r64[R_RAX]=dst;
          }
        }
        return next;
      }
      if( b1==0xC0 ) { // XADD r/m8, r8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        synchronized( mem ) {
          long dst=readRM8()&0xFFL, src=readReg8(mrm_reg)&0xFFL;
          long sum=(dst+src)&0xFFL;
          zf=(sum==0)?1:0; sf=(int)(sum>>7)&1;
          of=(int)(((dst^~src)&(dst^sum))>>7)&1;
          cf=((dst+src)>0xFFL)?1:0;
          writeRM8((short)sum); writeReg8(mrm_reg,(short)dst);
        }
        return next;
      }
      if( b1==0xC1 ) { // XADD r/m, r (16/32/64)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        synchronized( mem ) {
          if( rex_w ) {
            long dst=readRM64(), src=r64[mrm_reg];
            setFlags64Add(dst,src);
            writeRM64(dst+src); r64[mrm_reg]=dst;
          } else if( op66 ) {
            long dst=readRM16()&0xFFFFL, src=r64[mrm_reg]&0xFFFFL;
            long sum=(dst+src)&0xFFFFL;
            zf=(sum==0)?1:0; sf=(int)(sum>>15)&1;
            of=(int)(((dst^~src)&(dst^sum))>>15)&1;
            cf=((dst+src)>0xFFFFL)?1:0;
            writeRM16((short)sum); r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|dst;
          } else {
            long dst=readRM32()&0xFFFFFFFFL, src=r64[mrm_reg]&0xFFFFFFFFL;
            setFlags32Add(dst,src);
            writeRM32((dst+src)&0xFFFFFFFFL); r64[mrm_reg]=dst;
          }
        }
        return next;
      }
      if( b1==0xAF ) { // IMUL r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src=rex_w?readRM64():readRM32();
        long res=r64[mrm_reg]*src;
        if(rex_w) r64[mrm_reg]=res; else r64[mrm_reg]=res&0xFFFFFFFFL;
        of=0; cf=0; return next;
      }
      if( b1==0xB6 ) { // MOVZX r16/32/64, r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v = readRM8() & 0xFFL;
        // Phase 27 step 44: 0x66 prefix では下位 16 bit のみ書き上位 48 保持
        if( op66 ) r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | v;
        else r64[mrm_reg] = v; // zero-extend (rex_w 不問: 上位はどっちみち 0)
        return next;
      }
      if( b1==0xBE ) { // MOVSX r16/32/64, r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v=(long)(byte)readRM8();
        // Phase 27 step 44: 0x66 prefix で 16-bit dest の場合、上位 48 bit
        //   を保持しつつ下位 16 bit のみ書く。旧実装は 32-bit 書いていたため
        //   bits 16-31 を破壊。nettle の base64 table lookup
        //   (`66 0F BE 14 11` = movsbw (%rcx,%rdx,1),%dx) で edx 上位 16 bit
        //   が 0xFFFF に化け、後続の padding handler が誤動作 → cert load 失敗。
        if( op66 ) r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (v & 0xFFFFL);
        else if( rex_w ) r64[mrm_reg] = v;
        else r64[mrm_reg] = v & 0xFFFFFFFFL;
        return next;
      }
      if( b1==0xB7 ) { // MOVZX r16/32/64, r/m16
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v = readRM16() & 0xFFFFL;
        // op66 はこの命令では意味なし (src も dst も 16-bit になる) が、
        //   念のため上位 48 bit 保持で下位 16 bit のみ書く形に
        if( op66 ) r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | v;
        else if( rex_w ) r64[mrm_reg] = v;
        else r64[mrm_reg] = v;  // upper 32 zero-extended (元から)
        return next;
      }
      if( b1==0xBF ) { // MOVSX r32/64, r/m16
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v=(long)(short)readRM16();
        // op66 はこの命令では意味なし (16→16 で sign extend 不要)
        if( op66 ) r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (v & 0xFFFFL);
        else if( rex_w ) r64[mrm_reg] = v;
        else r64[mrm_reg] = v & 0xFFFFFFFFL;
        return next;
      }
      if( b1==0xBA ) { // Grp8 bit: BT/BTS/BTR/BTC r/m, imm8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false);
        int bit=(mem.load8(next)&0xFF)&(rex_w?63:31); next++;
        fixEA(next,fs_prefix);
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1;
        // /5=BTS /6=BTR /7=BTC
        if(mrm_reg==5){long r=dst|(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        else if(mrm_reg==6){long r=dst&~(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        else if(mrm_reg==7){long r=dst^(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        return next;
      }
      if( b1==0xA3 ) { // BT r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int bit=(int)(r64[mrm_reg]&(rex_w?63:31));
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1; return next;
      }
      if( b1==0xAB ) { // BTS r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int bit=(int)(r64[mrm_reg]&(rex_w?63:31));
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1;
        long r=dst|(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);
        return next;
      }
      if( b1==0x31 ) { // RDTSC
        r64[R_RAX]=System.nanoTime()&0xFFFFFFFFL;
        r64[R_RDX]=0; return pc+2;
      }
      // SHRD r/m, r, imm8 (0F AC) / SHRD r/m, r, CL (0F AD)
      if( b1==0xAC || b1==0xAD ) {
        long next = decodeModRM(pc+2, rex_r, rex_b, rex_x, false);
        long imm; long n;
        if( b1==0xAC ) { imm = mem.load8(next) & 0xFFL; next++; n = imm; }
        else           { n = r64[R_RCX] & 0xFFL; }
        fixEA(next, fs_prefix);
        long dst = rex_w ? readRM64() : (op66 ? readRM16()&0xFFFFL : readRM32()&0xFFFFFFFFL);
        long src = rex_w ? r64[mrm_reg] : (op66 ? r64[mrm_reg]&0xFFFFL : r64[mrm_reg]&0xFFFFFFFFL);
        int size = rex_w ? 64 : (op66 ? 16 : 32);
        n &= (rex_w ? 0x3F : 0x1F);
        if( n != 0 ) {
          long mask = (rex_w ? -1L : (1L << size) - 1L);
          long res;
          if( rex_w ) res = (dst >>> n) | (src << (64 - n));
          else        res = ((dst >>> n) | (src << (size - n))) & mask;
          if( rex_w )      writeRM64(res);
          else if( op66 )  writeRM16((short)res);
          else             writeRM32(res);
          long r2 = rex_w ? res : (res & mask);
          zf = (r2 == 0) ? 1 : 0;
          sf = (int)(r2 >>> (size-1)) & 1;
          cf = (int)((dst >>> (n-1)) & 1);
        }
        return next;
      }

      // SHLD r/m, r, imm8 (0F A4) / SHLD r/m, r, CL (0F A5)
      if( b1==0xA4 || b1==0xA5 ) {
        long next = decodeModRM(pc+2, rex_r, rex_b, rex_x, false);
        long imm; long n;
        if( b1==0xA4 ) { imm = mem.load8(next) & 0xFFL; next++; n = imm; }
        else           { n = r64[R_RCX] & 0xFFL; }
        fixEA(next, fs_prefix);
        long dst = rex_w ? readRM64() : (op66 ? readRM16()&0xFFFFL : readRM32()&0xFFFFFFFFL);
        long src = rex_w ? r64[mrm_reg] : (op66 ? r64[mrm_reg]&0xFFFFL : r64[mrm_reg]&0xFFFFFFFFL);
        int size = rex_w ? 64 : (op66 ? 16 : 32);
        n &= (rex_w ? 0x3F : 0x1F);  // shift count masked
        if( n != 0 ) {
          long mask = (rex_w ? -1L : (1L << size) - 1L);
          long res;
          if( rex_w ) res = (dst << n) | (src >>> (64 - n));
          else        res = ((dst << n) | (src >>> (size - n))) & mask;
          if( rex_w )      writeRM64(res);
          else if( op66 )  writeRM16((short)res);
          else             writeRM32(res);
          // フラグ: 簡易 (ZF/SF のみ更新、CF/OF は IA32 仕様に近い)
          long r2 = rex_w ? res : (res & mask);
          zf = (r2 == 0) ? 1 : 0;
          sf = (int)(r2 >>> (size-1)) & 1;
          cf = (int)((dst >>> (size - n)) & 1);
        }
        return next;
      }

      // BSWAP r — 0F C8+rd (32-bit unless REX.W: 64-bit)
      if( (b1 & 0xF8) == 0xC8 ) {
        int idx = (b1 & 7) | (rex_b ? 8 : 0);
        long v = r64[idx];
        if( rex_w ) {
          long r = ((v & 0xFFL) << 56) | ((v >> 56) & 0xFFL)
                 | ((v & 0xFF00L) << 40) | ((v >> 40) & 0xFF00L)
                 | ((v & 0xFF0000L) << 24) | ((v >> 24) & 0xFF0000L)
                 | ((v & 0xFF000000L) << 8) | ((v >> 8) & 0xFF000000L);
          r64[idx] = r;
        } else {
          int x = (int)v;
          int r = ((x & 0xFF) << 24) | ((x & 0xFF00) << 8)
                | ((x >> 8) & 0xFF00) | ((x >>> 24) & 0xFF);
          r64[idx] = ((long)r) & 0xFFFFFFFFL;
        }
        return pc+2;
      }
      if( b1==0xA2 ) { // CPUID
        // glibc / ld.so が x86-64-baseline (LM, FPU, CMOV, CX8, FXSR, MMX,
        // SSE, SSE2, OSFXSR, SCE) を要求するので、それを満たす最低限の
        // 値を返す。実装している命令セットを正直に申告する形。
        // EDX (leaf 1): bit  0=FPU, 4=TSC, 8=CX8, 11=SEP, 15=CMOV,
        //               23=MMX, 24=FXSR, 25=SSE, 26=SSE2
        //               => 0x078B_F011 (実装している/嘘ではない範囲)
        // ECX (leaf 1): 0 とする (SSSE3 / SSE4 等は嘘になる)
        long leaf  = r64[R_RAX] & 0xFFFFFFFFL;
        long sub   = r64[R_RCX] & 0xFFFFFFFFL;
        if( leaf == 0 ) {
          // 最大基本 leaf = 1、vendor = "GenuineIntel"
          r64[R_RAX] = 1L;
          r64[R_RBX] = 0x756E6547L; // "Genu"
          r64[R_RDX] = 0x49656E69L; // "ineI"
          r64[R_RCX] = 0x6C65746EL; // "ntel"
        } else if( leaf == 1 ) {
          r64[R_RAX] = 0x000506E3L; // family=6 model=0x4E (Skylake-ish)
          r64[R_RBX] = 0x00010800L; // brand_index=0, clflush=8 (×8=64B), apic=1
          // EDX: FPU(0) VME(1) DE(2) PSE(3) TSC(4) MSR(5) PAE(6) MCE(7)
          //      CX8(8) APIC(9) SEP(11) MTRR(12) PGE(13) MCA(14) CMOV(15)
          //      PAT(16) PSE36(17) CLFSH(19) MMX(23) FXSR(24) SSE(25) SSE2(26) HT(28)
          //   → x86-64-baseline (FPU/CMOV/CX8/FXSR/MMX/SSE/SSE2 等) を満たす
          r64[R_RDX] = 0x178BFBFFL;
          // ECX: SSE3(0)、PCLMUL(1)、AES-NI(25) を立てる。
          //   SSSE3/SSE4 等は未対応のままなので False。
          r64[R_RCX] = 0x02000003L;
        } else if( leaf == 0x80000000L ) {
          r64[R_RAX] = 0x80000001L;
          r64[R_RBX] = 0; r64[R_RCX] = 0; r64[R_RDX] = 0;
        } else if( leaf == 0x80000001L ) {
          // EDX bit 29 = LM (Long Mode) を立てる。ld.so が x86-64 と判定する
          r64[R_RAX] = 0; r64[R_RBX] = 0; r64[R_RCX] = 0;
          r64[R_RDX] = 0x20000000L;  // LM
        } else {
          r64[R_RAX] = 0; r64[R_RBX] = 0; r64[R_RCX] = 0; r64[R_RDX] = 0;
        }
        // sub-leaf は無視 (leaf 7 等で必要ならここで分岐)
        if( sub != 0 && false ) { /* placeholder */ }
        return pc+2;
      }
      if( b1==0xBC ) { // BSF r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src;
        if(rex_w) src=readRM64();
        else if(op66) src=readRM16()&0xFFFFL;
        else src=readRM32()&0xFFFFFFFFL;
        if(src==0){zf=1;}
        else {
          zf=0;
          int idx = Long.numberOfTrailingZeros(src);
          if(rex_w) r64[mrm_reg]=idx;
          else if(op66) r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|((long)idx & 0xFFFFL);
          else r64[mrm_reg]=(long)idx & 0xFFFFFFFFL;
        }
        return next;
      }
      if( b1==0xBD ) { // BSR r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src;
        if(rex_w) src=readRM64();
        else if(op66) src=readRM16()&0xFFFFL;
        else src=readRM32()&0xFFFFFFFFL;
        if(src==0){zf=1;}
        else {
          zf=0;
          int idx;
          if(rex_w) idx = 63 - Long.numberOfLeadingZeros(src);
          else if(op66) idx = 15 - Integer.numberOfLeadingZeros((int)(src&0xFFFFL) << 16);
          else idx = 31 - Integer.numberOfLeadingZeros((int)src);
          if(rex_w) r64[mrm_reg]=idx;
          else if(op66) r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|((long)idx & 0xFFFFL);
          else r64[mrm_reg]=(long)idx & 0xFFFFFFFFL;
        }
        return next;
      }
      // --- 3-byte opcode escapes: 66 0F 38 / 66 0F 3A (AES-NI / PCLMUL 等) ---
      if( op66 && (b1==0x38 || b1==0x3A) ) {
        int b2 = mem.load8(pc+2) & 0xFF;
        long n3 = decodeModRM(pc+3,rex_r,rex_b,rex_x,false);
        // Phase 27 step 44: 0x3A escape は全て imm8 を持つので RIP-relative
        //   address を 1 byte 補正 (命令末尾 RIP 仕様)。0x38 escape は基本
        //   imm 無しなので 0。
        fixEA(n3 + (b1==0x3A ? 1 : 0), fs_prefix);
        int xd = mrm_reg, xs = mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[xs]; sh=xmm_hi[xs]; }
        else          { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        if( b1==0x38 ) {
          // PSHUFB xmm1, xmm2/m128 (SSSE3): for each i, dst[i] = (src[i] & 0x80)
          //   ? 0 : dst[src[i] & 0x0F]。OpenSSL の SHA / AES routine が頻用。
          if( b2==0x00 ) {
            long dl = xmm_lo[xd], dh = xmm_hi[xd];
            byte[] dst = new byte[16], src = new byte[16];
            for( int i = 0; i < 8; i++ ) { dst[i]   = (byte)(dl >>> (i*8)); dst[i+8] = (byte)(dh >>> (i*8)); }
            for( int i = 0; i < 8; i++ ) { src[i]   = (byte)(sl >>> (i*8)); src[i+8] = (byte)(sh >>> (i*8)); }
            byte[] out = new byte[16];
            for( int i = 0; i < 16; i++ ) {
              int m = src[i] & 0xFF;
              out[i] = ((m & 0x80) != 0) ? 0 : dst[m & 0x0F];
            }
            long olo = 0, ohi = 0;
            for( int i = 0; i < 8; i++ ) olo |= ((long)(out[i]   & 0xFF)) << (i*8);
            for( int i = 0; i < 8; i++ ) ohi |= ((long)(out[i+8] & 0xFF)) << (i*8);
            xmm_lo[xd] = olo; xmm_hi[xd] = ohi;
            return n3;
          }
          // AES-NI: AESENC (DC) / AESENCLAST (DD) / AESDEC (DE) /
          //         AESDECLAST (DF) / AESIMC (DB)
          if( b2==0xDC || b2==0xDD || b2==0xDE || b2==0xDF || b2==0xDB ) {
            byte[] state = aesUnpack(xmm_lo[xd], xmm_hi[xd]);
            if( b2==0xDC || b2==0xDD ) {       // AESENC / AESENCLAST
              aesShiftRows(state);
              aesSubBytes(state);
              if( b2==0xDC ) aesMixColumns(state);
              long rl = aesPackLo(state), rh = aesPackHi(state);
              xmm_lo[xd] = rl ^ sl;             // AddRoundKey (XOR with src)
              xmm_hi[xd] = rh ^ sh;
            } else if( b2==0xDE || b2==0xDF ) { // AESDEC / AESDECLAST
              aesInvShiftRows(state);
              aesInvSubBytes(state);
              if( b2==0xDE ) aesInvMixColumns(state);
              long rl = aesPackLo(state), rh = aesPackHi(state);
              xmm_lo[xd] = rl ^ sl;
              xmm_hi[xd] = rh ^ sh;
            } else { // AESIMC: dst = InvMixColumns(src)
              byte[] s2 = aesUnpack(sl, sh);
              aesInvMixColumns(s2);
              xmm_lo[xd] = aesPackLo(s2);
              xmm_hi[xd] = aesPackHi(s2);
            }
            return n3;
          }
          process.println("Cpu64: unsupported 66 0F 38 "+Integer.toHexString(b2)+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag(); return pc;
        }
        // 66 0F 3A: imm8 を取る AES-NI / PCLMUL / SSSE3 PALIGNR / SSE4.1 PINSRD 等
        if( b1==0x3A ) {
          int imm = mem.load8(n3) & 0xFF;
          // PINSRD xmm1, r/m32, imm8 (SSE4.1, 66 0F 3A 22 /r ib): 32-bit を
          //   xmm1 の (imm & 3) 番目の dword に挿入。r/m32 は 32-bit GPR or
          //   memory。decodeModRM で sl/sh は xmm 想定で読まれているが、
          //   ここでは src として 32-bit 値だけ使うので mrm_mod==3 なら GPR の
          //   下位 32-bit、メモリなら 4 byte ロード。
          if( b2==0x22 ) {
            int v32;
            if( mrm_mod == 3 ) v32 = (int)(r64[xs] & 0xFFFFFFFFL);
            else v32 = mem.load32(mrm_ea);
            int slot = imm & 3;
            long mask = 0xFFFFFFFFL << ((slot & 1) * 32);
            long val  = ((long)v32 & 0xFFFFFFFFL) << ((slot & 1) * 32);
            if( slot < 2 ) {
              xmm_lo[xd] = (xmm_lo[xd] & ~mask) | val;
            } else {
              xmm_hi[xd] = (xmm_hi[xd] & ~mask) | val;
            }
            return n3 + 1;
          }
          if( b2==0x0F ) { // PALIGNR xmm1, xmm2/m128, imm8 (SSSE3)
            // Intel SDM: TEMP1[255:0] := ((DEST[127:0] << 128) OR SRC[127:0])
            //                            >> (imm8*8)
            //            DEST[127:0]  := TEMP1[127:0]
            // つまり concat の **低位 128bit が src**、高位 128bit が dst。
            //   concat byte 0..15  = src (low),  byte 16..31 = dst (high)
            //   imm=0 → 結果は src、imm=16 → 結果は dst、imm=32+ → 0。
            long dl = xmm_lo[xd], dh = xmm_hi[xd];
            byte[] concat = new byte[32];
            for( int i = 0; i < 8; i++ ) concat[i]      = (byte)(sl >>> (i*8));
            for( int i = 0; i < 8; i++ ) concat[i + 8]  = (byte)(sh >>> (i*8));
            for( int i = 0; i < 8; i++ ) concat[i + 16] = (byte)(dl >>> (i*8));
            for( int i = 0; i < 8; i++ ) concat[i + 24] = (byte)(dh >>> (i*8));
            byte[] out = new byte[16];
            for( int i = 0; i < 16; i++ ) {
              int idx = i + imm;
              out[i] = (idx < 32) ? concat[idx] : 0;
            }
            long olo = 0, ohi = 0;
            for( int i = 0; i < 8; i++ ) olo |= ((long)(out[i]     & 0xFF)) << (i*8);
            for( int i = 0; i < 8; i++ ) ohi |= ((long)(out[i + 8] & 0xFF)) << (i*8);
            xmm_lo[xd] = olo; xmm_hi[xd] = ohi;
            return n3 + 1;
          }
          if( b2==0xDF ) { // AESKEYGENASSIST xmm1, xmm2/m128, imm8
            // 入力 src の各 dword について SubWord と (imm が rcon)
            // word 1 と word 3 は SubWord(src) のみ
            // word 0 と word 2 は SubWord(RotWord(src)) ^ rcon (位置による)
            // 仕様: dst[31:0]   = SubWord(src[63:32])
            //       dst[63:32]  = RotWord(SubWord(src[63:32])) ^ rcon
            //       dst[95:64]  = SubWord(src[127:96])
            //       dst[127:96] = RotWord(SubWord(src[127:96])) ^ rcon
            int x1 = (int)((sl >>> 32) & 0xFFFFFFFFL);   // src[63:32]
            int x3 = (int)((sh >>> 32) & 0xFFFFFFFFL);   // src[127:96]
            int sub1 = aesSubWord(x1);
            int sub3 = aesSubWord(x3);
            int rot1 = aesRotWord(sub1) ^ imm;
            int rot3 = aesRotWord(sub3) ^ imm;
            xmm_lo[xd] = (((long)sub1) & 0xFFFFFFFFL) | (((long)rot1) << 32);
            xmm_hi[xd] = (((long)sub3) & 0xFFFFFFFFL) | (((long)rot3) << 32);
            return n3 + 1;
          }
          if( b2==0x44 ) { // PCLMULQDQ xmm1, xmm2/m128, imm8
            // imm8 bit 0 で dst の lo/hi を選択、bit 4 で src の lo/hi を選択
            long da = ((imm & 0x01) != 0) ? xmm_hi[xd] : xmm_lo[xd];
            long db = ((imm & 0x10) != 0) ? sh : sl;
            // 64x64 → 128bit carry-less multiply
            long rlo = 0, rhi = 0;
            for( int i = 0; i < 64; i++ ) {
              if( ((db >>> i) & 1L) != 0 ) {
                if( i == 0 ) rlo ^= da;
                else { rlo ^= (da << i); rhi ^= (da >>> (64 - i)); }
              }
            }
            xmm_lo[xd] = rlo; xmm_hi[xd] = rhi;
            return n3 + 1;
          }
          process.println("Cpu64: unsupported 66 0F 3A "+Integer.toHexString(b2)+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag(); return pc;
        }
      }
      // --- SSE2 (66 0F prefix) ---
      if( op66 ) {
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false);
        // Phase 27 step 44: RIP-relative addressing で命令末尾の imm bytes を
        //   考慮しないと address が ずれる (x86-64 spec: RIP-relative は命令
        //   全体の末尾 RIP を使う)。imm を持つ b1 を pre-detect して fixEA に
        //   正しい next を渡す。これが無いと nettle base64 decode で paddb 用
        //   定数 load が wrong addr → cert load 失敗。
        int imm_after = 0;
        if( b1==0x70 || b1==0xC4 || b1==0xC6 ||
            b1==0x71 || b1==0x72 || b1==0x73 ||
            b1==0x3A ) {
          imm_after = 1;
        }
        fixEA(next + imm_after, fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        // load 128-bit source (XMM or memory)
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        if( b1==0x12 ) { // MOVLPD xmm, m64 (load low 64 bits, keep high)
          if(mrm_mod!=3) xmm_lo[dst]=mem.load64(mrm_ea); return next;
        }
        if( b1==0x13 ) { // MOVLPD m64, xmm (store low 64 bits)
          if(mrm_mod!=3) mem.store64(mrm_ea,xmm_lo[dst]); return next;
        }
        if( b1==0x16 ) { // MOVHPD xmm, m64 (load high 64 bits, keep low)
          if(mrm_mod!=3) xmm_hi[dst]=mem.load64(mrm_ea); return next;
        }
        if( b1==0x17 ) { // MOVHPD m64, xmm (store high 64 bits)
          if(mrm_mod!=3) mem.store64(mrm_ea,xmm_hi[dst]); return next;
        }
        if( b1==0x6E ) { // MOVD xmm, r/m32 (or MOVQ with REX.W)
          xmm_lo[dst]= rex_w ? (mrm_mod==3?r64[src]:mem.load64(mrm_ea)) : ((mrm_mod==3?r64[src]:mem.load32(mrm_ea))&0xFFFFFFFFL);
          xmm_hi[dst]=0; return next;
        }
        if( b1==0x7E ) { // MOVD r/m32, xmm
          if(mrm_mod==3) r64[src]=(rex_w?xmm_lo[dst]:xmm_lo[dst]&0xFFFFFFFFL); else {if(rex_w)mem.store64(mrm_ea,xmm_lo[dst]);else mem.store32(mrm_ea,(int)xmm_lo[dst]);} return next;
        }
        if( b1==0x6F ) { // MOVDQA xmm, xmm/m128
          xmm_lo[dst]=sl; xmm_hi[dst]=sh; return next;
        }
        if( b1==0x7F ) { // MOVDQA xmm/m128, xmm
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
          else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);} return next;
        }
        if( b1==0xEF ) { // PXOR
          xmm_lo[dst]^=sl; xmm_hi[dst]^=sh; return next;
        }
        if( b1==0xEB ) { // POR
          xmm_lo[dst]|=sl; xmm_hi[dst]|=sh; return next;
        }
        if( b1==0xDB ) { // PAND
          xmm_lo[dst]&=sl; xmm_hi[dst]&=sh; return next;
        }
        if( b1==0xDF ) { // PANDN
          xmm_lo[dst]=(~xmm_lo[dst])&sl; xmm_hi[dst]=(~xmm_hi[dst])&sh; return next;
        }
        if( b1==0x75 ) { // PCMPEQW
          long lo=0,hi=0; for(int i=0;i<4;i++){long a=(xmm_lo[dst]>>(i*16))&0xFFFFL,b2=(sl>>(i*16))&0xFFFFL;lo|=(a==b2?0xFFFFL:0)<<(i*16);a=(xmm_hi[dst]>>(i*16))&0xFFFFL;b2=(sh>>(i*16))&0xFFFFL;hi|=(a==b2?0xFFFFL:0)<<(i*16);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x76 ) { // PCMPEQD
          long lo=0,hi=0; for(int i=0;i<2;i++){long a=(xmm_lo[dst]>>(i*32))&0xFFFFFFFFL,b2=(sl>>(i*32))&0xFFFFFFFFL;lo|=(a==b2?0xFFFFFFFFL:0)<<(i*32);a=(xmm_hi[dst]>>(i*32))&0xFFFFFFFFL;b2=(sh>>(i*32))&0xFFFFFFFFL;hi|=(a==b2?0xFFFFFFFFL:0)<<(i*32);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x74 ) { // PCMPEQB
          xmm_lo[dst]=pcmpeqb(xmm_lo[dst],sl); xmm_hi[dst]=pcmpeqb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xDA ) { // PMINUB
          xmm_lo[dst]=pminub(xmm_lo[dst],sl); xmm_hi[dst]=pminub(xmm_hi[dst],sh); return next;
        }
        if( b1==0x60 ) { // PUNPCKLBW
          xmm_hi[dst]=punpckhalf8(xmm_lo[dst]>>32, sl>>32);
          xmm_lo[dst]=punpckhalf8(xmm_lo[dst], sl); return next;
        }
        if( b1==0x61 ) { // PUNPCKLWD
          xmm_hi[dst]=punpckhalf16(xmm_lo[dst]>>32, sl>>32);
          xmm_lo[dst]=punpckhalf16(xmm_lo[dst], sl); return next;
        }
        if( b1==0x62 ) { // PUNPCKLDQ
          long old_lo=xmm_lo[dst];
          xmm_hi[dst]=((old_lo>>>32)&0xFFFFFFFFL)|(sl&0xFFFFFFFF00000000L);
          xmm_lo[dst]=(old_lo&0xFFFFFFFFL)|((sl&0xFFFFFFFFL)<<32); return next;
        }
        if( b1==0x68 ) { // PUNPCKHBW xmm, xmm/m128
          long lo=punpckhalf8(xmm_hi[dst],sh); xmm_hi[dst]=punpckhalf8(xmm_hi[dst]>>32,sh>>32); xmm_lo[dst]=lo; return next;
        }
        if( b1==0x69 ) { // PUNPCKHWD xmm, xmm/m128
          long lo=punpckhalf16(xmm_hi[dst],sh); xmm_hi[dst]=punpckhalf16(xmm_hi[dst]>>32,sh>>32); xmm_lo[dst]=lo; return next;
        }
        if( b1==0x6A ) { // PUNPCKHDQ xmm, xmm/m128
          long new_lo=(xmm_hi[dst]&0xFFFFFFFFL)|((sh&0xFFFFFFFFL)<<32);
          long new_hi=((xmm_hi[dst]>>>32)&0xFFFFFFFFL)|(sh&0xFFFFFFFF00000000L);
          xmm_lo[dst]=new_lo; xmm_hi[dst]=new_hi; return next;
        }
        if( b1==0x6C ) { // PUNPCKLQDQ xmm, xmm/m128
          xmm_hi[dst]=sl; return next; // lo stays, hi = src_lo
        }
        if( b1==0x6D ) { // PUNPCKHQDQ xmm, xmm/m128
          xmm_lo[dst]=xmm_hi[dst]; xmm_hi[dst]=sh; return next;
        }
        if( b1==0x70 ) { // PSHUFD xmm, xmm/m128, imm8
          int imm=mem.load8(next)&0xFF; next++;
          long[] t=new long[]{sl&0xFFFFFFFFL,(sl>>>32)&0xFFFFFFFFL,sh&0xFFFFFFFFL,(sh>>>32)&0xFFFFFFFFL};
          long r0=t[imm&3],r1=t[(imm>>2)&3],r2=t[(imm>>4)&3],r3=t[(imm>>6)&3];
          xmm_lo[dst]=r0|(r1<<32); xmm_hi[dst]=r2|(r3<<32); return next;
        }
        if( b1==0xD7 ) { // PMOVMSKB r32, xmm
          long mask=0;
          for(int i=0;i<8;i++) if(((xmm_lo[src]>>(i*8))&0x80)!=0) mask|=(1L<<i);
          for(int i=0;i<8;i++) if(((xmm_hi[src]>>(i*8))&0x80)!=0) mask|=(1L<<(i+8));
          r64[dst]=mask; return next;
        }
        if( b1==0xD6 ) { // MOVQ xmm/m64, xmm (store low 64 bits)
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=0;}
          else{mem.store64(mrm_ea,xmm_lo[dst]);} return next;
        }
        if( b1==0xFB ) { // PSUBQ
          xmm_lo[dst]-=sl; xmm_hi[dst]-=sh; return next;
        }
        if( b1==0xFA ) { // PSUBD (4 dwords each half)
          long lo=0,hi=0;
          for(int i=0;i<2;i++){
            int a=(int)(xmm_lo[dst]>>(i*32)), b=(int)(sl>>(i*32));
            lo|=((long)(a-b) & 0xFFFFFFFFL) << (i*32);
            a=(int)(xmm_hi[dst]>>(i*32)); b=(int)(sh>>(i*32));
            hi|=((long)(a-b) & 0xFFFFFFFFL) << (i*32);
          }
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xFE ) { // PADDD (4 dwords each half)
          long lo=0,hi=0;
          for(int i=0;i<2;i++){
            int a=(int)(xmm_lo[dst]>>(i*32)), b=(int)(sl>>(i*32));
            lo|=((long)(a+b) & 0xFFFFFFFFL) << (i*32);
            a=(int)(xmm_hi[dst]>>(i*32)); b=(int)(sh>>(i*32));
            hi|=((long)(a+b) & 0xFFFFFFFFL) << (i*32);
          }
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xD4 ) { // PADDQ
          xmm_lo[dst]+=sl; xmm_hi[dst]+=sh; return next;
        }
        if( b1==0xFC ) { // PADDB
          xmm_lo[dst]=paddb(xmm_lo[dst],sl); xmm_hi[dst]=paddb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xE8 ) { // PSUBSB (signed saturate)
          xmm_lo[dst]=subb(xmm_lo[dst],sl); xmm_hi[dst]=subb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xF8 ) { // PSUBB (wrapping byte subtract)
          long lo=0,hi=0; for(int i=0;i<8;i++){lo|=(((xmm_lo[dst]>>(i*8))-(sl>>(i*8)))&0xFFL)<<(i*8);hi|=(((xmm_hi[dst]>>(i*8))-(sh>>(i*8)))&0xFFL)<<(i*8);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x65 ) { // PCMPGTW
          long lo=0,hi=0; for(int i=0;i<4;i++){short a=(short)((xmm_lo[dst]>>(i*16))&0xFFFF),b2=(short)((sl>>(i*16))&0xFFFF);lo|=(a>b2?0xFFFFL:0)<<(i*16);a=(short)((xmm_hi[dst]>>(i*16))&0xFFFF);b2=(short)((sh>>(i*16))&0xFFFF);hi|=(a>b2?0xFFFFL:0)<<(i*16);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x66 ) { // PCMPGTD
          long lo=0,hi=0; for(int i=0;i<2;i++){int a=(int)(xmm_lo[dst]>>(i*32)),b2=(int)(sl>>(i*32));lo|=(a>b2?0xFFFFFFFFL:0)<<(i*32);a=(int)(xmm_hi[dst]>>(i*32));b2=(int)(sh>>(i*32));hi|=(a>b2?0xFFFFFFFFL:0)<<(i*32);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x64 ) { // PCMPGTB
          xmm_lo[dst]=pcmpgtb(xmm_lo[dst],sl); xmm_hi[dst]=pcmpgtb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xE0 ) { // PAVGB
          xmm_lo[dst]=pavgb(xmm_lo[dst],sl); xmm_hi[dst]=pavgb(xmm_hi[dst],sh); return next;
        }
        // 66 0F 71 /N ib: Grp12 — packed word (16-bit) shift by imm8
        //   /6: PSLLW (left logical), /4: PSRAW (right arith), /2: PSRLW (right logical)
        if( b1==0x71 ) {
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          int sft = imm & 0xFF;
          long shf_sl = xmm_lo[src], shf_sh = xmm_hi[src];
          long rl = 0, rh = 0;
          for(int i = 0; i < 4; i++) {
            short wlo = (short)((shf_sl >>> (i*16)) & 0xFFFFL);
            short whi = (short)((shf_sh >>> (i*16)) & 0xFFFFL);
            int slv, shv;
            if( grp == 6 ) {  // PSLLW
              slv = (sft >= 16) ? 0 : ((wlo & 0xFFFF) << sft) & 0xFFFF;
              shv = (sft >= 16) ? 0 : ((whi & 0xFFFF) << sft) & 0xFFFF;
            } else if( grp == 2 ) {  // PSRLW
              slv = (sft >= 16) ? 0 : ((wlo & 0xFFFF) >>> sft);
              shv = (sft >= 16) ? 0 : ((whi & 0xFFFF) >>> sft);
            } else if( grp == 4 ) {  // PSRAW (sign-extend)
              int s = (sft >= 16) ? 15 : sft;
              slv = ((int)wlo >> s) & 0xFFFF;
              shv = ((int)whi >> s) & 0xFFFF;
            } else { return next; }
            rl |= ((long)(slv & 0xFFFF)) << (i*16);
            rh |= ((long)(shv & 0xFFFF)) << (i*16);
          }
          xmm_lo[src] = rl; xmm_hi[src] = rh;
          return next;
        }
        // 66 0F 72 /N ib: Grp13 — packed dword (32-bit) shift by imm8
        if( b1==0x72 ) {
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          int sft = imm & 0xFF;
          long shf_sl = xmm_lo[src], shf_sh = xmm_hi[src];
          long rl = 0, rh = 0;
          for(int i = 0; i < 2; i++) {
            int dlo = (int)((shf_sl >>> (i*32)) & 0xFFFFFFFFL);
            int dhi = (int)((shf_sh >>> (i*32)) & 0xFFFFFFFFL);
            long slv, shv;
            if( grp == 6 ) {       // PSLLD
              slv = (sft >= 32) ? 0 : ((long)dlo << sft) & 0xFFFFFFFFL;
              shv = (sft >= 32) ? 0 : ((long)dhi << sft) & 0xFFFFFFFFL;
            } else if( grp == 2 ) { // PSRLD
              slv = (sft >= 32) ? 0 : ((long)(dlo & 0xFFFFFFFFL) >>> sft);
              shv = (sft >= 32) ? 0 : ((long)(dhi & 0xFFFFFFFFL) >>> sft);
            } else if( grp == 4 ) { // PSRAD
              int s = (sft >= 32) ? 31 : sft;
              slv = (long)(dlo >> s) & 0xFFFFFFFFL;
              shv = (long)(dhi >> s) & 0xFFFFFFFFL;
            } else { return next; }
            rl |= slv << (i*32);
            rh |= shv << (i*32);
          }
          xmm_lo[src] = rl; xmm_hi[src] = rh;
          return next;
        }
        if( b1==0x73 ) { // Grp14 (66 0F 73): per-element shift Q + 128-bit shift DQ
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          if(grp==7){ // PSLLDQ xmm, imm8 (shift left logical 128-bit by bytes)
            if(imm>=16){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>=8){xmm_hi[src]=xmm_lo[src]<<((imm-8)*8);xmm_lo[src]=0;}
            else if(imm>0){xmm_hi[src]=(xmm_hi[src]<<(imm*8))|(xmm_lo[src]>>>(64-imm*8));xmm_lo[src]<<=imm*8;}
          } else if(grp==3){ // PSRLDQ xmm, imm8 (shift right logical 128-bit by bytes)
            if(imm>=16){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>=8){xmm_lo[src]=xmm_hi[src]>>>((imm-8)*8);xmm_hi[src]=0;}
            else if(imm>0){xmm_lo[src]=(xmm_lo[src]>>>(imm*8))|(xmm_hi[src]<<(64-imm*8));xmm_hi[src]>>>=imm*8;}
          } else if(grp==2){ // PSRLQ xmm, imm8 (per-64-bit-lane logical right shift)
            if(imm>=64){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>0){xmm_lo[src]>>>=imm; xmm_hi[src]>>>=imm;}
          } else if(grp==6){ // PSLLQ xmm, imm8 (per-64-bit-lane logical left shift)
            if(imm>=64){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>0){xmm_lo[src]<<=imm; xmm_hi[src]<<=imm;}
          } return next;
        }
        // Grp12 (66 0F 71): per-16-bit-word shift — PSRLW (/2), PSRAW (/4), PSLLW (/6)
        if( b1==0x71 ) {
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          long lo=xmm_lo[src], hi=xmm_hi[src], rl=0, rh=0;
          for( int i=0; i<8; i++ ) {
            int wlo = (int)((lo >>> (i*16)) & 0xFFFF);
            int whi = (int)((hi >>> (i*16)) & 0xFFFF);
            int olo, ohi;
            if(grp==2){ olo=(imm>=16)?0:(wlo>>>imm)&0xFFFF; ohi=(imm>=16)?0:(whi>>>imm)&0xFFFF; }
            else if(grp==4){ // PSRAW (signed)
              olo=(imm>=16)?(((short)wlo)>>15)&0xFFFF:((short)wlo>>imm)&0xFFFF;
              ohi=(imm>=16)?(((short)whi)>>15)&0xFFFF:((short)whi>>imm)&0xFFFF;
            } else if(grp==6){ olo=(imm>=16)?0:(wlo<<imm)&0xFFFF; ohi=(imm>=16)?0:(whi<<imm)&0xFFFF; }
            else { olo=wlo; ohi=whi; }
            rl |= ((long)olo & 0xFFFF) << (i*16);
            rh |= ((long)ohi & 0xFFFF) << (i*16);
          }
          xmm_lo[src]=rl; xmm_hi[src]=rh;
          return next;
        }
        // Grp13 (66 0F 72): per-32-bit-dword shift — PSRLD (/2), PSRAD (/4), PSLLD (/6)
        if( b1==0x72 ) {
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          long lo=xmm_lo[src], hi=xmm_hi[src], rl=0, rh=0;
          for( int i=0; i<4; i++ ) {
            int dlo = (int)((lo >>> (i*32)) & 0xFFFFFFFFL);
            int dhi = (int)((hi >>> (i*32)) & 0xFFFFFFFFL);
            int olo, ohi;
            if(grp==2){ olo=(imm>=32)?0:dlo>>>imm; ohi=(imm>=32)?0:dhi>>>imm; }
            else if(grp==4){ olo=(imm>=32)?(dlo>>31):(dlo>>imm); ohi=(imm>=32)?(dhi>>31):(dhi>>imm); }
            else if(grp==6){ olo=(imm>=32)?0:dlo<<imm; ohi=(imm>=32)?0:dhi<<imm; }
            else { olo=dlo; ohi=dhi; }
            rl |= ((long)olo & 0xFFFFFFFFL) << (i*32);
            rh |= ((long)ohi & 0xFFFFFFFFL) << (i*32);
          }
          xmm_lo[src]=rl; xmm_hi[src]=rh;
          return next;
        }
        // 66 0F 54-57: ANDPD/ANDNPD/ORPD/XORPD (packed double bitwise)
        // 66 0F 58/59/5C/5E/5F: ADDPD/MULPD/SUBPD/DIVPD/MAXPD (packed double arith)
        if( b1>=0x54 && b1<=0x5F && b1!=0x5A && b1!=0x5B && b1!=0x5D ) {
          long pn=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pn,fs_prefix);
          int pd=mrm_reg, ps=mrm_rm;
          long psl, psh;
          if(mrm_mod==3){ psl=xmm_lo[ps]; psh=xmm_hi[ps]; }
          else{ psl=mem.load64(mrm_ea); psh=mem.load64(mrm_ea+8); }
          long pdl=xmm_lo[pd], pdh=xmm_hi[pd];
          if( b1==0x54 ) { xmm_lo[pd]=pdl&psl; xmm_hi[pd]=pdh&psh; }
          else if( b1==0x55 ) { xmm_lo[pd]=(~pdl)&psl; xmm_hi[pd]=(~pdh)&psh; }
          else if( b1==0x56 ) { xmm_lo[pd]=pdl|psl; xmm_hi[pd]=pdh|psh; }
          else if( b1==0x57 ) { xmm_lo[pd]=pdl^psl; xmm_hi[pd]=pdh^psh; }
          else if( b1==0x58 ) {
            xmm_lo[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdl)+Double.longBitsToDouble(psl));
            xmm_hi[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdh)+Double.longBitsToDouble(psh));
          } else if( b1==0x59 ) {
            xmm_lo[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdl)*Double.longBitsToDouble(psl));
            xmm_hi[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdh)*Double.longBitsToDouble(psh));
          } else if( b1==0x5C ) {
            xmm_lo[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdl)-Double.longBitsToDouble(psl));
            xmm_hi[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdh)-Double.longBitsToDouble(psh));
          } else if( b1==0x5E ) {
            xmm_lo[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdl)/Double.longBitsToDouble(psl));
            xmm_hi[pd] = Double.doubleToRawLongBits(Double.longBitsToDouble(pdh)/Double.longBitsToDouble(psh));
          } else if( b1==0x5F ) {
            xmm_lo[pd] = Double.doubleToRawLongBits(Math.max(Double.longBitsToDouble(pdl),Double.longBitsToDouble(psl)));
            xmm_hi[pd] = Double.doubleToRawLongBits(Math.max(Double.longBitsToDouble(pdh),Double.longBitsToDouble(psh)));
          }
          return pn;
        }
        // 66 0F 28: MOVAPD xmm, xmm/m128 / 66 0F 29: MOVAPD xmm/m128, xmm
        if( b1==0x28 || b1==0x29 ) {
          long mn=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(mn,fs_prefix);
          int xd=mrm_reg, xs=mrm_rm;
          if( b1==0x28 ) {
            if(mrm_mod==3){ xmm_lo[xd]=xmm_lo[xs]; xmm_hi[xd]=xmm_hi[xs]; }
            else{ xmm_lo[xd]=mem.load64(mrm_ea); xmm_hi[xd]=mem.load64(mrm_ea+8); }
          } else {
            if(mrm_mod==3){ xmm_lo[xs]=xmm_lo[xd]; xmm_hi[xs]=xmm_hi[xd]; }
            else{ mem.store64(mrm_ea, xmm_lo[xd]); mem.store64(mrm_ea+8, xmm_hi[xd]); }
          }
          return mn;
        }
        // 66 0F C6 /r ib: SHUFPD xmm1, xmm2/m128, imm8
        //   imm8 bit 0: 0 = dest lo を維持, 1 = dest hi を dest lo にコピー
        //   imm8 bit 1: 0 = src lo を dest hi に, 1 = src hi を dest hi に
        if( b1==0xC6 ) {
          long shufpd_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(shufpd_next,fs_prefix);
          int xd=mrm_reg, xs=mrm_rm;
          long sd_lo_old=xmm_lo[xd], sd_hi_old=xmm_hi[xd];
          long sd_src_lo, sd_src_hi;
          if(mrm_mod==3){ sd_src_lo=xmm_lo[xs]; sd_src_hi=xmm_hi[xs]; }
          else          { sd_src_lo=mem.load64(mrm_ea); sd_src_hi=mem.load64(mrm_ea+8); }
          int sd_imm = mem.load8(shufpd_next) & 0xFF;
          xmm_lo[xd] = ((sd_imm & 1) != 0) ? sd_hi_old : sd_lo_old;
          xmm_hi[xd] = ((sd_imm & 2) != 0) ? sd_src_hi  : sd_src_lo;
          return shufpd_next + 1;  // imm8 を読み飛ばす
        }
        // 66 0F C5 /r ib: PEXTRW r32, xmm, imm8 — xmm の指定 word を 16-bit
        //   抽出して GPR に書き込む (上位 zero-extend)。/usr/bin/file 等で使用。
        if( b1==0xC5 ) {
          long pe_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pe_next,fs_prefix);
          // mod=3 が仕様 (xmm を直接渡す)
          int pe_xs = mrm_rm;
          int pe_imm = mem.load8(pe_next) & 0x7;
          long w16;
          if( pe_imm < 4 ) w16 = (xmm_lo[pe_xs] >>> (pe_imm*16)) & 0xFFFFL;
          else             w16 = (xmm_hi[pe_xs] >>> ((pe_imm-4)*16)) & 0xFFFFL;
          // 32-bit 書き込み (上位はゼロ拡張)
          r64[mrm_reg] = w16;
          return pe_next + 1;  // imm8 を読み飛ばす
        }
        // 66 0F C4 /r ib: PINSRW xmm1, r32/m16, imm8
        //   imm8 の low 3bit が word 位置 (0..7)。指定位置に低 16bit を挿入。
        //   zlib の crc32 / adler32 等で使用。
        if( b1==0xC4 ) {
          long pi_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pi_next+1,fs_prefix);
          int pi_xd=mrm_reg;
          long w16;
          if(mrm_mod==3) w16 = r64[mrm_rm] & 0xFFFFL;
          else           w16 = mem.load16(mrm_ea) & 0xFFFFL;
          int pi_imm = mem.load8(pi_next) & 0x7;
          int bit = (pi_imm & 3) * 16;          // 0, 16, 32, 48
          long mask = ~(0xFFFFL << bit);
          if( pi_imm < 4 ) xmm_lo[pi_xd] = (xmm_lo[pi_xd] & mask) | (w16 << bit);
          else             xmm_hi[pi_xd] = (xmm_hi[pi_xd] & mask) | (w16 << bit);
          return pi_next + 1;  // imm8 を読み飛ばす
        }
        // 66 0F 67 /r: PACKUSWB xmm1, xmm2/m128 — packed words → unsigned bytes (saturate)
        //   各 16-bit を 0..255 に飽和して 8-bit に詰める (低位 8 個 + 高位 8 個 = 16 byte)
        //   busybox tr / glibc strxfrm 等で使われる。
        if( b1==0x67 ) {
          long pu_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pu_next,fs_prefix);
          int pu_xd=mrm_reg, pu_xs=mrm_rm;
          long pu_sl, pu_sh;
          if(mrm_mod==3){ pu_sl=xmm_lo[pu_xs]; pu_sh=xmm_hi[pu_xs]; }
          else          { pu_sl=mem.load64(mrm_ea); pu_sh=mem.load64(mrm_ea+8); }
          long pu_dl=xmm_lo[pu_xd], pu_dh=xmm_hi[pu_xd];
          long pu_rl=0, pu_rh=0;
          // 低位 64bit: dst の 4 word + src の 4 word の前半
          for(int i = 0; i < 4; i++) {
            short w = (short)((pu_dl >>> (i*16)) & 0xFFFFL);
            int sat = (w<0)?0:(w>255?255:w);
            pu_rl |= ((long)sat) << (i*8);
          }
          for(int i = 0; i < 4; i++) {
            short w = (short)((pu_dh >>> (i*16)) & 0xFFFFL);
            int sat = (w<0)?0:(w>255?255:w);
            pu_rl |= ((long)sat) << ((i+4)*8);
          }
          // 高位 64bit: src の 8 word
          for(int i = 0; i < 4; i++) {
            short w = (short)((pu_sl >>> (i*16)) & 0xFFFFL);
            int sat = (w<0)?0:(w>255?255:w);
            pu_rh |= ((long)sat) << (i*8);
          }
          for(int i = 0; i < 4; i++) {
            short w = (short)((pu_sh >>> (i*16)) & 0xFFFFL);
            int sat = (w<0)?0:(w>255?255:w);
            pu_rh |= ((long)sat) << ((i+4)*8);
          }
          xmm_lo[pu_xd]=pu_rl; xmm_hi[pu_xd]=pu_rh;
          return pu_next;
        }
        // 66 0F DE /r: PMAXUB xmm1, xmm2/m128 — packed unsigned 8-bit max (16 byte)
        //   glibc の SSE 最適 strlen/wcslen 等で使われる。
        if( b1==0xDE ) {
          long pm_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pm_next,fs_prefix);
          int pm_xd=mrm_reg, pm_xs=mrm_rm;
          long pm_sl, pm_sh;
          if(mrm_mod==3){ pm_sl=xmm_lo[pm_xs]; pm_sh=xmm_hi[pm_xs]; }
          else          { pm_sl=mem.load64(mrm_ea); pm_sh=mem.load64(mrm_ea+8); }
          long pm_dl = xmm_lo[pm_xd], pm_dh = xmm_hi[pm_xd];
          long pm_rl = 0, pm_rh = 0;
          for(int i = 0; i < 8; i++) {
            int da = (int)((pm_dl >>> (i*8)) & 0xFFL);
            int sa = (int)((pm_sl >>> (i*8)) & 0xFFL);
            pm_rl |= ((long)Math.max(da, sa)) << (i*8);
            int db = (int)((pm_dh >>> (i*8)) & 0xFFL);
            int sb = (int)((pm_sh >>> (i*8)) & 0xFFL);
            pm_rh |= ((long)Math.max(db, sb)) << (i*8);
          }
          xmm_lo[pm_xd] = pm_rl; xmm_hi[pm_xd] = pm_rh;
          return pm_next;
        }
        // 66 0F 50 /r: MOVMSKPD r32/r64, xmm — packed double 2 個の符号ビットを
        //   低位 2bit に抽出して GPR に書き込む。__printf_fp の NaN/Inf 判定で
        //   呼ばれる。上位ビットは 0 クリア。
        if( b1==0x50 ) {
          long mm_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(mm_next,fs_prefix);
          int xs = mrm_rm;        // mod=3 が仕様 (xmm を直接渡す)
          long mask = 0;
          if( (xmm_lo[xs] >>> 63) != 0 ) mask |= 1;
          if( (xmm_hi[xs] >>> 63) != 0 ) mask |= 2;
          // 32-bit 書き込み (上位はゼロ拡張)
          r64[mrm_reg] = mask;
          return mm_next;
        }
        // 66 0F 2E: UCOMISD / 66 0F 2F: COMISD — scalar double 比較し EFLAGS を設定
        if( b1==0x2E || b1==0x2F ) {
          long cmp_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(cmp_next,fs_prefix);
          double cmp_a = Double.longBitsToDouble(xmm_lo[mrm_reg]);
          double cmp_b;
          if(mrm_mod==3) cmp_b = Double.longBitsToDouble(xmm_lo[mrm_rm]);
          else           cmp_b = Double.longBitsToDouble(mem.load64(mrm_ea));
          // Intel SDM: UCOMISD/COMISD は ZF/PF/CF を以下のセットで設定する。
          //   Unordered (NaN): ZF=1, PF=1, CF=1
          //   Greater than:    ZF=0, PF=0, CF=0
          //   Less than:       ZF=0, PF=0, CF=1
          //   Equal:           ZF=1, PF=0, CF=0
          // SF/OF はクリア。glibc __printf_fp の NaN 判定が SETP (PF=1)
          // を読むので PF も正しく設定すること (Phase 25 で発覚)。
          if( Double.isNaN(cmp_a) || Double.isNaN(cmp_b) ) { zf=1; pf=1; cf=1; sf=0; of=0; }
          else if( cmp_a > cmp_b )  { zf=0; pf=0; cf=0; sf=0; of=0; }
          else if( cmp_a < cmp_b )  { zf=0; pf=0; cf=1; sf=0; of=0; }
          else                      { zf=1; pf=0; cf=0; sf=0; of=0; }
          return cmp_next;
        }
        process.println("Cpu64: unsupported SSE2 66 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }
      if( b1==0x16 ) { // MOVLHPS xmm, xmm2 (mod=3) / MOVHPS xmm, m64 (mod!=3)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3) xmm_hi[dst]=xmm_lo[src];
        else           xmm_hi[dst]=mem.load64(mrm_ea);
        return next;
      }
      if( b1==0x17 ) { // MOVHPS m64, xmm (store high 64 bits to memory)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg;
        mem.store64(mrm_ea,xmm_hi[dst]);
        return next;
      }
      if( b1==0x12 ) { // MOVHLPS xmm, xmm2 (mod=3) / MOVLPS xmm, m64 (mod!=3)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3) xmm_lo[dst]=xmm_hi[src];
        else           xmm_lo[dst]=mem.load64(mrm_ea);
        return next;
      }
      if( b1==0x13 ) { // MOVLPS m64, xmm (Phase 29-emacs: store low 64 bits)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg;
        if(mrm_mod!=3) mem.store64(mrm_ea, xmm_lo[dst]);
        return next;
      }
      if( b1==0x10 ) { // MOVUPS xmm, xmm/m128
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
        else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
        return next;
      }
      if( b1==0x14 ) { // UNPCKLPS xmm, xmm/m128 (Phase 29-emacs: emacs で使われる)
        // Interleave low 32-bit values from src with low 32-bit values of dst.
        //   DEST[31:0]   = DEST[31:0]
        //   DEST[63:32]  = SRC[31:0]
        //   DEST[95:64]  = DEST[63:32]
        //   DEST[127:96] = SRC[63:32]
        // 結果的に PUNPCKLDQ と同じ semantics (32-bit lane interleave)。
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl;
        if(mrm_mod==3) sl = xmm_lo[src]; else sl = mem.load64(mrm_ea);
        long old_lo = xmm_lo[dst];
        xmm_hi[dst] = ((old_lo>>>32)&0xFFFFFFFFL) | (sl & 0xFFFFFFFF00000000L);
        xmm_lo[dst] = (old_lo & 0xFFFFFFFFL)      | ((sl & 0xFFFFFFFFL) << 32);
        return next;
      }
      if( b1==0x15 ) { // UNPCKHPS xmm, xmm/m128 (上半分 32-bit interleave)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sh;
        if(mrm_mod==3) sh = xmm_hi[src]; else sh = mem.load64(mrm_ea+8);
        long old_hi = xmm_hi[dst];
        xmm_lo[dst] = (old_hi & 0xFFFFFFFFL)      | ((sh & 0xFFFFFFFFL) << 32);
        xmm_hi[dst] = ((old_hi>>>32)&0xFFFFFFFFL) | (sh & 0xFFFFFFFF00000000L);
        return next;
      }
      if( b1==0x11 ) { // MOVUPS xmm/m128, xmm
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
        else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
        return next;
      }
      if( b1==0x28 ) { // MOVAPS xmm, xmm/m128
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
        else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
        return next;
      }
      if( b1==0x29 ) { // MOVAPS xmm/m128, xmm
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
        else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
        return next;
      }
      // 0F C6 /r ib: SHUFPS xmm1, xmm2/m128, imm8 — packed single shuffle
      //   imm8 の 2bit ずつ 4 フィールドで dst の 4 dword (32-bit) を選択。
      //   bits 0-1 → out0 = dst[i], bits 2-3 → out1 = dst[i],
      //   bits 4-5 → out2 = src[i], bits 6-7 → out3 = src[i]。
      if( b1==0xC6 ) {
        long shps_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(shps_next,fs_prefix);
        int xd=mrm_reg, xs=mrm_rm;
        long d_lo=xmm_lo[xd], d_hi=xmm_hi[xd];
        long s_lo, s_hi;
        if(mrm_mod==3){ s_lo=xmm_lo[xs]; s_hi=xmm_hi[xs]; }
        else          { s_lo=mem.load64(mrm_ea); s_hi=mem.load64(mrm_ea+8); }
        int imm = mem.load8(shps_next) & 0xFF;
        // dst の 4 dword を [d_lo low, d_lo high, d_hi low, d_hi high] として
        // 取り出し、imm の 2bit ずつで選択。
        long[] dwds = new long[4];
        dwds[0] = d_lo & 0xFFFFFFFFL;
        dwds[1] = (d_lo >>> 32) & 0xFFFFFFFFL;
        dwds[2] = d_hi & 0xFFFFFFFFL;
        dwds[3] = (d_hi >>> 32) & 0xFFFFFFFFL;
        long[] swds = new long[4];
        swds[0] = s_lo & 0xFFFFFFFFL;
        swds[1] = (s_lo >>> 32) & 0xFFFFFFFFL;
        swds[2] = s_hi & 0xFFFFFFFFL;
        swds[3] = (s_hi >>> 32) & 0xFFFFFFFFL;
        long out0 = dwds[ imm        & 3];
        long out1 = dwds[(imm >>> 2) & 3];
        long out2 = swds[(imm >>> 4) & 3];
        long out3 = swds[(imm >>> 6) & 3];
        xmm_lo[xd] = (out0 & 0xFFFFFFFFL) | (out1 << 32);
        xmm_hi[xd] = (out2 & 0xFFFFFFFFL) | (out3 << 32);
        return shps_next + 1;  // imm8 を読み飛ばす
      }
      // 0F 2E: UCOMISS / 0F 2F: COMISS — scalar single 比較し EFLAGS を設定
      // (66 0F 2E/2F の double 版とフラグ規約は同じ。grep の locale 数値判定で必要)
      if( b1==0x2E || b1==0x2F ) {
        long cmp_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(cmp_next,fs_prefix);
        float cmp_a = Float.intBitsToFloat((int)xmm_lo[mrm_reg]);
        float cmp_b;
        if(mrm_mod==3) cmp_b = Float.intBitsToFloat((int)xmm_lo[mrm_rm]);
        else           cmp_b = Float.intBitsToFloat(mem.load32(mrm_ea));
        if( Float.isNaN(cmp_a) || Float.isNaN(cmp_b) ) { zf=1; pf=1; cf=1; sf=0; of=0; }
        else if( cmp_a > cmp_b )  { zf=0; pf=0; cf=0; sf=0; of=0; }
        else if( cmp_a < cmp_b )  { zf=0; pf=0; cf=1; sf=0; of=0; }
        else                      { zf=1; pf=0; cf=0; sf=0; of=0; }
        return cmp_next;
      }
      if( b1==0x57 ) { // XORPS xmm, xmm/m128 (= bitwise XOR; よくゼロクリアに使う)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        xmm_lo[dst]^=sl; xmm_hi[dst]^=sh;
        return next;
      }
      // 0F 18 /n: PREFETCH 系 (PREFETCHNTA /0, PREFETCHT0 /1, PREFETCHT1 /2,
      //   PREFETCHT2 /3) と NOP 拡張 (/4..7)。すべて副作用なしの hint
      //   なので no-op で正しい (ModRM はパースして次の rip まで進める)。
      if( b1==0x18 ) {
        long pf_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pf_next,fs_prefix);
        return pf_next;
      }
      // 0F 1F /n: NOP r/m (multi-byte NOP, /0..7 全て同じ意味で no-op)
      if( b1==0x1F ) {
        long n_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(n_next,fs_prefix);
        return n_next;
      }
      // 0F AE /n: FXSAVE / FXRSTOR / LDMXCSR / STMXCSR / XSAVE / XRSTOR / CLFLUSH
      //          (modrm.reg で分岐)、または mod=3 で LFENCE/MFENCE/SFENCE。
      // ld.so の dynamic resolution (lazy binding) パスで FXSAVE/FXRSTOR が
      // 必須。 FENCE 系は no-op、CLFLUSH も no-op、XSAVE/XRSTOR は最小実装。
      if( b1==0xAE ) {
        long ae_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(ae_next,fs_prefix);
        int sub = mrm_reg;
        if( mrm_mod == 3 ) {
          // LFENCE (sub=5), MFENCE (sub=6), SFENCE (sub=7) — no-op
          return ae_next;
        }
        if( sub == 0 ) {
          // FXSAVE m512: 512 byte の FPU/SSE state を保存。x87 / MXCSR 等は
          // 雑にゼロ詰めし、xmm0-15 のみ実値を書き出す。lazy binding 復帰時
          // の FXRSTOR で復元できれば十分。
          // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
          mem.bulkZero( mrm_ea, 512 );
          mem.store32( mrm_ea + 0x18, fpu_cw & 0xFFFF ); // MXCSR (placeholder)
          for( int i = 0; i < 16; i++ ) {
            mem.store64( mrm_ea + 0xA0 + i*16,     xmm_lo[i] );
            mem.store64( mrm_ea + 0xA0 + i*16 + 8, xmm_hi[i] );
          }
          return ae_next;
        }
        if( sub == 1 ) {
          // FXRSTOR m512: xmm レジスタを復元。FPU 状態は無視。
          for( int i = 0; i < 16; i++ ) {
            xmm_lo[i] = mem.load64( mrm_ea + 0xA0 + i*16 );
            xmm_hi[i] = mem.load64( mrm_ea + 0xA0 + i*16 + 8 );
          }
          return ae_next;
        }
        if( sub == 2 ) {
          // LDMXCSR — 32-bit MXCSR ロード (no-op)
          return ae_next;
        }
        if( sub == 3 ) {
          // STMXCSR — 32-bit MXCSR ストア (デフォルト値 0x1F80)
          mem.store32( mrm_ea, 0x1F80 );
          return ae_next;
        }
        if( sub == 7 ) {
          // CLFLUSH — no-op
          return ae_next;
        }
        process.println("Cpu64: unsupported 0F AE /"+sub+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }
      process.println("Cpu64: unsupported 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
  }

  // F3 prefix: ENDBR64 / REP string ops / F3 0F XX (SSE scalar single 等)。
  // REP は内部で string op を loop で完走させる。F3 0F XX は SSE/BMI 命令。
  private long exec_f3_prefix( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean fs_prefix ) {
      int b1 = mem.load8(pc+1) & 0xFF;
      if( PROFILE_OP ) OP_F3_COUNT[ b1 ]++;  // b1 = first byte after F3 (REX/0F/opcode)
      if( b1 == 0x0F ) {
        int b2 = mem.load8(pc+2)&0xFF, b3 = mem.load8(pc+3)&0xFF;
        if( b2==0x1E && (b3==0xFA||b3==0xFB) ) return pc+4; // ENDBR64/32
      }
      // REP: optional REX between F3 and op
      boolean rep_rexw = rex_w;
      boolean rep_rex_r=rex_r, rep_rex_x=rex_x, rep_rex_b=rex_b;
      int b_op; long rep_end;
      if( (b1&0xF0)==0x40 ) {
        rep_rexw=(b1&0x08)!=0; rep_rex_r=(b1&0x04)!=0; rep_rex_x=(b1&0x02)!=0; rep_rex_b=(b1&0x01)!=0;
        b_op=mem.load8(pc+2)&0xFF; rep_end=pc+3;
      }
      else { b_op=b1; rep_end=pc+2; }
      if( b_op==0xAA ) { while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],(byte)r64[R_RAX]);r64[R_RDI]++;r64[R_RCX]--;} return rep_end; }
      if( b_op==0xAB ) {
        if(rep_rexw) while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],r64[R_RAX]);r64[R_RDI]+=8;r64[R_RCX]--;}
        else         while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],(int)r64[R_RAX]);r64[R_RDI]+=4;r64[R_RCX]--;}
        return rep_end;
      }
      if( b_op==0xA4 ) { while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],mem.load8(r64[R_RSI]));r64[R_RDI]++;r64[R_RSI]++;r64[R_RCX]--;} return rep_end; }
      if( b_op==0xA5 ) {
        if(rep_rexw) while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],mem.load64(r64[R_RSI]));r64[R_RDI]+=8;r64[R_RSI]+=8;r64[R_RCX]--;}
        else         while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],mem.load32(r64[R_RSI]));r64[R_RDI]+=4;r64[R_RSI]+=4;r64[R_RCX]--;}
        return rep_end;
      }
      // REPE SCAS (F3 AE/AF) — treat as "not found" (ZF=0, RCX=0)
      if( b_op==0xAE||b_op==0xAF ) { r64[R_RCX]=0; zf=0; return rep_end; }
      // F3 C3: REP RET (AMD K8 alignment trick, semantically = RET)
      if( b_op==0xC3 ) { return pop64(); }
      // F3 0F (with or without embedded REX): SSE scalar / MOVDQU
      if( b_op==0x0F || b1==0x0F ) {
        int b2_off = (b_op==0x0F) ? (int)(rep_end-pc) : 2;  // offset of SSE opcode from pc
        int b2=mem.load8(pc+b2_off)&0xFF;
        // F3 0F 6F: MOVDQU xmm, xmm/m128
        if( b2==0x6F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
          else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
          return xnext;
        }
        // F3 0F 7F: MOVDQU xmm/m128, xmm
        if( b2==0x7F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
          else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
          return xnext;
        }
        // F3 0F 7E: MOVQ xmm, xmm/m64 (load low 64 bits, zero high)
        if( b2==0x7E ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[dst]=xmm_lo[src];
          else           xmm_lo[dst]=mem.load64(mrm_ea);
          xmm_hi[dst]=0;
          return xnext;
        }
        // F3 0F 10: MOVSS xmm, xmm/m32
        if( b2==0x10 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[dst]=(xmm_lo[dst]&0xFFFFFFFF00000000L)|(xmm_lo[src]&0xFFFFFFFFL);
          else           xmm_lo[dst]=(xmm_lo[dst]&0xFFFFFFFF00000000L)|(mem.load32(mrm_ea)&0xFFFFFFFFL);
          return xnext;
        }
        // F3 0F 11: MOVSS xmm/m32, xmm
        if( b2==0x11 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[src]=(xmm_lo[src]&0xFFFFFFFF00000000L)|(xmm_lo[dst]&0xFFFFFFFFL);
          else mem.store32(mrm_ea,(int)xmm_lo[dst]);
          return xnext;
        }
        // F3 0F 1E (ENDBR/CET/NOP variants) — NOP
        if( b2==0x1E||b2==0x1F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); return xnext;
        }
        // F3 0F 58/59/5C/5E/51/52/53/54: scalar FP — NOP (float ops not emulated)
        if( b2==0x58||b2==0x59||b2==0x5C||b2==0x5E||b2==0x51||b2==0x52||b2==0x53||b2==0x54 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); return xnext;
        }
        // F3 0F 5A: CVTSS2SD xmm, xmm/m32 (Phase 29-emacs: scalar single→double)
        if( b2==0x5A ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int bits = (mrm_mod==3) ? (int)xmm_lo[mrm_rm] : mem.load32(mrm_ea);
          float f = Float.intBitsToFloat(bits);
          long dbits = Double.doubleToRawLongBits((double)f);
          xmm_lo[mrm_reg] = dbits;
          return xnext;
        }
        // F3 0F 2A: CVTSI2SS xmm, r/m32 (REX.W: r/m64) — int→float (single)
        if( b2==0x2A ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          long src;
          if(mrm_mod==3) src = rep_rexw ? r64[mrm_rm] : (long)(int)r64[mrm_rm];
          else            src = rep_rexw ? mem.load64(mrm_ea) : (long)(int)mem.load32(mrm_ea);
          int bits = Float.floatToRawIntBits((float)src);
          xmm_lo[mrm_reg] = (xmm_lo[mrm_reg]&0xFFFFFFFF00000000L) | (bits & 0xFFFFFFFFL);
          return xnext;
        }
        // F3 0F 2C: CVTTSS2SI r, xmm/m32 (truncate)
        // F3 0F 2D: CVTSS2SI r, xmm/m32 (round)
        if( b2==0x2C || b2==0x2D ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int bits = (mrm_mod==3) ? (int)xmm_lo[mrm_rm] : mem.load32(mrm_ea);
          float f = Float.intBitsToFloat(bits);
          long val = (b2==0x2C) ? (long)f : Math.round((double)f);
          if( rep_rexw ) r64[mrm_reg] = val;
          else           r64[mrm_reg] = val & 0xFFFFFFFFL;
          return xnext;
        }
        // F3 0F BC: TZCNT r, r/m  (BMI1 — count trailing zeros)
        // F3 0F 70: PSHUFHW xmm, xmm/m128, imm8 (SSE2)
        //   high 4 words (16-bit) shuffled by imm8, low 4 unchanged。
        //   Phase 27 step 41: TLS handshake で必要。
        if( b2==0x70 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          long sh = (mrm_mod==3) ? xmm_hi[src] : mem.load64(mrm_ea+8);
          long sl = (mrm_mod==3) ? xmm_lo[src] : mem.load64(mrm_ea);
          int imm = mem.load8(xnext) & 0xFF; xnext++;
          // src high 4 words
          int[] hw = new int[]{ (int)(sh & 0xFFFF), (int)((sh>>16) & 0xFFFF),
                                 (int)((sh>>32) & 0xFFFF), (int)((sh>>>48) & 0xFFFF) };
          long w0 = hw[imm&3], w1 = hw[(imm>>2)&3], w2 = hw[(imm>>4)&3], w3 = hw[(imm>>6)&3];
          xmm_lo[dst] = sl;  // low quad unchanged
          xmm_hi[dst] = w0 | (w1<<16) | (w2<<32) | (w3<<48);
          return xnext;
        }
        // F3 0F BD: LZCNT r, r/m  (BMI1 — count leading zeros)
        if( b2==0xBC || b2==0xBD ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          long src = rep_rexw ? readRM64() : (readRM32()&0xFFFFFFFFL);
          int sz = rep_rexw ? 64 : 32;
          int n;
          if( b2==0xBC ) n = (src==0) ? sz : Long.numberOfTrailingZeros(src);
          else           n = (src==0) ? sz : (rep_rexw ? Long.numberOfLeadingZeros(src) : Integer.numberOfLeadingZeros((int)src));
          if( rep_rexw ) r64[mrm_reg] = n;
          else           r64[mrm_reg] = n & 0xFFFFFFFFL;
          cf = (src==0) ? 1 : 0;
          zf = (n==0) ? 1 : 0;
          return xnext;
        }
      }
      process.println("Cpu64: unsupported F3 op="+Integer.toHexString(b_op)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
  }

  private long decode_and_exec( long pc ) {
    boolean rex_w=false, rex_r=false, rex_x=false, rex_b=false;
    boolean fs_prefix=false, op66=false, opF2=false;
    rex_present = false;
    final long start_pc = pc;
    int b0;

    // Phase 34-A2: per-RIP prefix cache。同じ rip を再実行する場合は
    // prefix scan loop を skip し、cache から flags を復元。
    int pfx_slot = (int)(start_pc & PFXCACHE_MASK);
    int pfx_info = pfx_cache_info[pfx_slot];
    if( pfx_cache_rip[pfx_slot] == start_pc && (pfx_info & PFX_VALID) != 0 ) {
      rex_w       = (pfx_info & PFX_REX_W) != 0;
      rex_r       = (pfx_info & PFX_REX_R) != 0;
      rex_x       = (pfx_info & PFX_REX_X) != 0;
      rex_b       = (pfx_info & PFX_REX_B) != 0;
      rex_present = (pfx_info & PFX_REX_PRESENT) != 0;
      op66        = (pfx_info & PFX_OP66) != 0;
      opF2        = (pfx_info & PFX_OPF2) != 0;
      fs_prefix   = (pfx_info & PFX_FS) != 0;
      pc = start_pc + (pfx_info & PFX_OFFSET_MASK);
      b0 = fetchInsnByte(pc);
      // 共通の F3 prefix / opcode dispatch 経路に jump (goto 替わりに else 落とす)
    } else {
      b0 = fetchInsnByte(pc);

    // Phase 27 step 63: REX prefix (0x40-0x4F) は x86-64 で最頻出。switch ループを
    //   通る前に専用の if で処理することで、よくある「REX 1 個のみ」パターンを
    //   高速化 (switch dispatch を skip)。SIMD prefix 等は loop に残す。
    if( (b0 & 0xF0) == 0x40 ) {
      rex_w=(b0&0x08)!=0; rex_r=(b0&0x04)!=0;
      rex_x=(b0&0x02)!=0; rex_b=(b0&0x01)!=0;
      rex_present=true;
      pc++; b0=fetchInsnByte(pc);
      // common case: REX のみで他 prefix なし → loop skip
      // SIMD prefix が REX の後に来るケースは稀だが念のため switch も残す
    }

    // プレフィックス スキャン (REX 以外の rare prefix)
    prefix_scan:
    while( true ) {
      switch( b0 ) {
        case 0x66: op66=true; pc++; b0=fetchInsnByte(pc); break;
        case 0x67: pc++; b0=fetchInsnByte(pc); break;  // addr32 (handled in decodeModRM)
        case 0x64: fs_prefix=true; pc++; b0=fetchInsnByte(pc); break;
        case 0x65: pc++; b0=fetchInsnByte(pc); break;  // GS prefix (ignored)
        case 0x2E: pc++; b0=fetchInsnByte(pc); break;  // CS hint
        case 0x3E: pc++; b0=fetchInsnByte(pc); break;  // DS hint
        case 0xF0: pc++; b0=fetchInsnByte(pc); break;  // LOCK
        case 0xF2: opF2=true; pc++; b0=fetchInsnByte(pc); break;  // REPNZ / SSE scalar double
        default:
          if( (b0&0xF0)==0x40 ) {
            // REX が SIMD prefix の後ろに来た場合 (rare)
            rex_w=(b0&0x08)!=0; rex_r=(b0&0x04)!=0;
            rex_x=(b0&0x02)!=0; rex_b=(b0&0x01)!=0;
            rex_present=true;
            pc++; b0=fetchInsnByte(pc); break;
          }
          break prefix_scan;
      }
    }
    // Phase 34-A2: prefix scan 結果を cache に保存
    {
      long off = pc - start_pc;
      if( off >= 0 && off <= PFX_OFFSET_MASK ) {
        int new_info = (int)off | PFX_VALID;
        if( rex_w       ) new_info |= PFX_REX_W;
        if( rex_r       ) new_info |= PFX_REX_R;
        if( rex_x       ) new_info |= PFX_REX_X;
        if( rex_b       ) new_info |= PFX_REX_B;
        if( rex_present ) new_info |= PFX_REX_PRESENT;
        if( op66        ) new_info |= PFX_OP66;
        if( opF2        ) new_info |= PFX_OPF2;
        if( fs_prefix   ) new_info |= PFX_FS;
        pfx_cache_rip[pfx_slot]  = start_pc;
        pfx_cache_info[pfx_slot] = new_info;
      }
    }
    }  // End of cache MISS branch

    if( PROFILE_OP ) OP_COUNT[ b0 ]++;

    // F3 prefix: ENDBR64 / REP string ops / F3 0F XX (extracted)
    if( b0 == 0xF3 )
      return exec_f3_prefix(pc, rex_w, rex_r, rex_b, rex_x, fs_prefix);

    // 0F escape
    if( b0 == 0x0F )
      return exec_0f_escape(pc, rex_w, rex_r, rex_b, rex_x, op66, opF2, fs_prefix);

    // 単一 byte opcode の dispatch。Phase 27 step 33 で旧 70+ if-cascade から
    // 移行し、Phase 34-A2 で cascade を完全廃止。JIT は tableswitch
    // (jump table、O(1)) に compile する。default に到達するのは未知 opcode のみ。
    switch( b0 ) {
      case 0x89: return exec_mov_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x8B: return exec_mov_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x83: return exec_grp1_imm8(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x39: return exec_cmp_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x85: return exec_test_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x3B: return exec_cmp_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x01: return exec_add_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x03: return exec_add_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x29: return exec_sub_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x2B: return exec_sub_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x31: return exec_xor_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x33: return exec_xor_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x21: return exec_and_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x23: return exec_and_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x09: return exec_or_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x0B: return exec_or_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x8D: return exec_lea(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x88: return exec_mov8_rm_r(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0x8A: return exec_mov8_r_rm(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0x84: return exec_test8_rm_r(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0xFF: return exec_grp5_ff(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x81: return exec_grp1_imm32(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0xC7: return exec_mov_rm_imm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0xB0: case 0xB1: case 0xB2: case 0xB3:
      case 0xB4: case 0xB5: case 0xB6: case 0xB7:
        return exec_mov8_r_imm(pc, b0, rex_b);
      case 0xB8: case 0xB9: case 0xBA: case 0xBB:
      case 0xBC: case 0xBD: case 0xBE: case 0xBF:
        return exec_mov_r_imm(pc, b0, rex_w, rex_b);
      case 0x11: return exec_adc_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x13: return exec_adc_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x19: return exec_sbb_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x1B: return exec_sbb_r_rm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x80: return exec_grp1_imm8_8bit(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0xC6: return exec_mov8_rm_imm(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0x6B: return exec_imul_rm_imm8(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x69: return exec_imul_rm_imm(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x63: return exec_movsxd(pc, rex_w, rex_r, rex_b, rex_x, fs_prefix);
      case 0xC1: case 0xD1: case 0xD3:
        return exec_grp2_shift(pc, b0, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x90: {  // NOP / XCHG rAX, r8 (REX.B 付きで xchg rax, r8)
        if( rex_b ) {
          long t = r64[R_RAX]; r64[R_RAX] = r64[8]; r64[8] = t;
          if( !rex_w ) { r64[R_RAX] &= 0xFFFFFFFFL; r64[8] &= 0xFFFFFFFFL; }
        }
        return pc+1;
      }
      case 0xF4: process.set_exit_flag(); return pc+1;  // HLT — treat as exit(0)
      case 0x50: case 0x51: case 0x52: case 0x53:
      case 0x54: case 0x55: case 0x56: case 0x57:
        push64(r64[(b0&7)|(rex_b?8:0)]); return pc+1;   // PUSH r64
      case 0x58: case 0x59: case 0x5A: case 0x5B:
      case 0x5C: case 0x5D: case 0x5E: case 0x5F:
        r64[(b0&7)|(rex_b?8:0)]=pop64(); return pc+1;   // POP r64
      case 0x6A: push64((long)(byte)mem.load8(pc+1)); return pc+2;       // PUSH imm8
      case 0x68: push64((long)(int)loadImm32u(pc+1)); return pc+5;       // PUSH imm32
      case 0xC9: r64[R_RSP]=r64[R_RBP]; r64[R_RBP]=pop64(); return pc+1; // LEAVE
      case 0xC3: return pop64();                                         // RET
      case 0xC2: { long a=pop64(); r64[R_RSP]+=(loadImm16(pc+1)&0xFFFFL); return a; }  // RET imm16
      case 0xE8: { int rel32=(int)loadImm32u(pc+1); long next=pc+5; push64(next); return next+rel32; }  // CALL rel32
      case 0xEB: return pc+2+mem.load8(pc+1);                            // JMP rel8
      case 0xE9: return pc+5+(int)loadImm32u(pc+1);                      // JMP rel32
      // JRCXZ rel8 (E3) — 67 prefix で JECXZ だが、RCX 全 64bit 見る簡略実装
      case 0xE3: { byte rel8=mem.load8(pc+1); return r64[R_RCX]==0 ? pc+2+rel8 : pc+2; }
      case 0x70: case 0x71: case 0x72: case 0x73:
      case 0x74: case 0x75: case 0x76: case 0x77:
      case 0x78: case 0x79: case 0x7A: case 0x7B:
      case 0x7C: case 0x7D: case 0x7E: case 0x7F:
        { byte rel8=mem.load8(pc+1); return evalCond(b0&0xF)?pc+2+rel8:pc+2; }  // Jcc rel8
      case 0x98:  // CDQE / CWDE
        if(rex_w) r64[R_RAX]=(long)(int)r64[R_RAX];
        else r64[R_RAX]=(long)(short)(r64[R_RAX]&0xFFFFL)&0xFFFFFFFFL;
        return pc+1;
      case 0x99:  // CQO / CDQ
        if(rex_w) r64[R_RDX]=(r64[R_RAX]<0)?-1L:0L;
        else r64[R_RDX]=((int)r64[R_RAX]<0)?0xFFFFFFFFL:0L;
        return pc+1;
      // 8-bit ALU (00/02 ADD, 08/0A OR, 10/12 ADC, 18/1A SBB,
      //            20/22 AND, 28/2A SUB, 30/32 XOR, 38/3A CMP)
      case 0x00: case 0x02: case 0x08: case 0x0A:
      case 0x10: case 0x12: case 0x18: case 0x1A:
      case 0x20: case 0x22: case 0x28: case 0x2A:
      case 0x30: case 0x32: case 0x38: case 0x3A:
        return exec_alu8(pc, b0, rex_r, rex_b, rex_x, fs_prefix);
      // ALU accumulator, imm short forms
      case 0x04: case 0x0C: case 0x14: case 0x1C:
      case 0x24: case 0x2C: case 0x34: case 0x3C:
        return exec_alu_al_imm8(pc, b0);
      case 0x05: case 0x0D: case 0x15: case 0x1D:
      case 0x25: case 0x2D: case 0x35: case 0x3D:
        return exec_alu_rax_imm(pc, b0, rex_w, op66);
      case 0xA8: return exec_test_al_imm8(pc);
      case 0xA9: return exec_test_rax_imm(pc, rex_w, op66);
      case 0xF6: return exec_grp3_f6(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0xF7: return exec_grp3_f7(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0xFE: return exec_grp4_fe(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0xC0: case 0xD0: case 0xD2:
        return exec_grp2_shift8(pc, b0, rex_r, rex_b, rex_x, fs_prefix);
      case 0x86: return exec_xchg8_rm_r(pc, rex_r, rex_b, rex_x, fs_prefix);
      case 0x87: return exec_xchg_rm_r(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);
      case 0x91: case 0x92: case 0x93: case 0x94:
      case 0x95: case 0x96: case 0x97: {  // XCHG rAX, r
        int reg=(b0&7)|(rex_b?8:0);
        if(rex_w){ long t=r64[R_RAX]; r64[R_RAX]=r64[reg]; r64[reg]=t; }
        else     { long t=r64[R_RAX]&0xFFFFFFFFL; r64[R_RAX]=r64[reg]&0xFFFFFFFFL; r64[reg]=t; }
        return pc+1;
      }
      case 0x9B: return pc+1;  // FWAIT/WAIT — NOP
      case 0xD8: case 0xD9: case 0xDA: case 0xDB:
      case 0xDC: case 0xDD: case 0xDE: case 0xDF:
        return exec_x87_escape(pc, b0, rex_r, rex_b, rex_x, fs_prefix);
      // 単独 string ops (REP 無し) — 1 回だけ転送。F3 prefix 経由の REP path
      // は別経路。DF (Direction Flag) は未追跡なので forward (+1) のみ。
      case 0xA4:  // MOVSB
        mem.store8(r64[R_RDI], (int)mem.load8(r64[R_RSI]));
        r64[R_RDI]++; r64[R_RSI]++;
        return pc+1;
      case 0xA5:  // MOVSW/D/Q
        if( rex_w )     { mem.store64(r64[R_RDI], mem.load64(r64[R_RSI])); r64[R_RDI]+=8; r64[R_RSI]+=8; }
        else if( op66 ) { mem.store16(r64[R_RDI], (short)mem.load16(r64[R_RSI])); r64[R_RDI]+=2; r64[R_RSI]+=2; }
        else            { mem.store32(r64[R_RDI], mem.load32(r64[R_RSI])); r64[R_RDI]+=4; r64[R_RSI]+=4; }
        return pc+1;
      case 0xAA:  // STOSB
        mem.store8(r64[R_RDI], (int)(r64[R_RAX] & 0xFF));
        r64[R_RDI]++;
        return pc+1;
      case 0xAB:  // STOSW/D/Q
        if( rex_w )     { mem.store64(r64[R_RDI], r64[R_RAX]); r64[R_RDI]+=8; }
        else if( op66 ) { mem.store16(r64[R_RDI], (short)(r64[R_RAX] & 0xFFFF)); r64[R_RDI]+=2; }
        else            { mem.store32(r64[R_RDI], (int)r64[R_RAX]); r64[R_RDI]+=4; }
        return pc+1;
      case 0xAC:  // LODSB
        r64[R_RAX] = (r64[R_RAX] & ~0xFFL) | ((long)mem.load8(r64[R_RSI]) & 0xFFL);
        r64[R_RSI]++;
        return pc+1;
      case 0xAD:  // LODSW/D/Q
        if( rex_w )     { r64[R_RAX] = mem.load64(r64[R_RSI]); r64[R_RSI]+=8; }
        else if( op66 ) { r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | (mem.load16(r64[R_RSI]) & 0xFFFFL); r64[R_RSI]+=2; }
        else            { r64[R_RAX] = mem.load32(r64[R_RSI]) & 0xFFFFFFFFL; r64[R_RSI]+=4; }
        return pc+1;
      default: break;  // unknown opcode — fall through to error report
    }

    process.println("Cpu64: unknown opcode 0x"+Integer.toHexString(b0)+" at rip=0x"+Long.toHexString(pc));
    process.set_exit_flag();
    return pc;
  }

  // Grp1: ADD(0) OR(1) ADC(2) SBB(3) AND(4) SUB(5) XOR(6) CMP(7)
  private long execGrp1( long imm, boolean is64, boolean is16, long next_pc ) {
    long val, res;
    if(is64)      val=readRM64();
    else if(is16) val=readRM16();
    else          val=readRM32();
    switch(mrm_reg){
      case 0: res=val+imm;
              if(is64){setFlags64Add(val,imm);writeRM64(res);}
              else if(is16){res&=0xFFFFL;zf=(res==0)?1:0;sf=(int)(res>>15)&1;of=0;cf=0;writeRM16(res);}
              else{setFlags32Add(val&0xFFFFFFFFL,imm&0xFFFFFFFFL);writeRM32(res&0xFFFFFFFFL);} break;
      case 1: res=val|imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 2: // ADC: フラグ更新付き (Phase 25 の bignum 修正)
              if(is64){ res=adc64(val,imm,cf); writeRM64(res); }
              else if(is16){ res=adc16(val&0xFFFFL,imm&0xFFFFL,cf); writeRM16(res&0xFFFFL); }
              else{ res=adc32(val&0xFFFFFFFFL,imm&0xFFFFFFFFL,cf); writeRM32(res&0xFFFFFFFFL); } break;
      case 3: // SBB: フラグ更新付き
              if(is64){ res=sbb64(val,imm,cf); writeRM64(res); }
              else if(is16){ res=sbb16(val&0xFFFFL,imm&0xFFFFL,cf); writeRM16(res&0xFFFFL); }
              else{ res=sbb32(val&0xFFFFFFFFL,imm&0xFFFFFFFFL,cf); writeRM32(res&0xFFFFFFFFL); } break;
      case 4: res=val&imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 5: res=val-imm;
              if(is64){setFlags64Sub(val,imm);writeRM64(res);}
              else if(is16){res&=0xFFFFL;zf=(res==0)?1:0;sf=(int)(res>>15)&1;of=0;cf=Long.compareUnsigned(val&0xFFFFL,imm&0xFFFFL)<0?1:0;writeRM16(res);}
              else{setFlags32Sub(val&0xFFFFFFFFL,imm&0xFFFFFFFFL);writeRM32(res&0xFFFFFFFFL);} break;
      case 6: res=val^imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 7: // CMP
              if(is64)setFlags64Sub(val,imm);
              else if(is16){long r=(val-imm)&0xFFFFL;zf=(r==0)?1:0;sf=(int)(r>>15)&1;of=0;cf=Long.compareUnsigned(val&0xFFFFL,imm&0xFFFFL)<0?1:0;}
              else setFlags32Sub(val&0xFFFFFFFFL,imm&0xFFFFFFFFL); break;
      default:
        process.println("Cpu64: unsupported Grp1 /"+mrm_reg+" at 0x"+Long.toHexString(next_pc));
        process.set_exit_flag();
    }
    return next_pc;
  }

  // SYSCALL
  private long exec_syscall( long next_pc ) {
    long syscall_no = r64[R_RAX];          // syscall 番号 (再実行用に退避)
    r64[R_RCX] = next_pc;
    long result = syscall64.call_amd64(
        syscall_no, r64[R_RDI], r64[R_RSI], r64[R_RDX],
        r64[10],    r64[8],     r64[9] );
    r64[R_RAX] = result;
    interrupt_done = true;
    // SA_RESTART: syscall がシグナル割り込みで -EINTR を返し、その割り込み
    // シグナルのハンドラに SA_RESTART が設定されていれば、ハンドラ復帰後に
    // syscall を再実行する。Linux カーネルと同様、rip を syscall 命令
    // (0F 05, 2 byte) の手前に戻し、rax を syscall 番号に戻す。
    // check_pending_signal がハンドラを delivery し、復帰後 rip = syscall_pc
    // から再実行されると rax = syscall_no が保持されているので正しく動く。
    if( result == Syscall.EINTR ) {
      int sig = process.psig();
      if( sig >= 0 && process.has_sa_restart( sig ) ) {
        r64[R_RAX] = syscall_no;
        return next_pc - 2;
      }
    }
    return next_pc;
  }

  // --- 即値ロードユーティリティ ---

  // Phase 34-A10 (issue #4): mem.load32/16/64 経由で lastSegment fast path を共有。
  // 旧実装は per-byte load8 を 4/2/8 回呼び出していたため、命令 imm の
  // 取り回しで Memory.load8 sample が積み上がっていた。mem.load32 は
  // 2-LRU lookup で text segment 内 hit すれば arraycopy 風 inline で読める。
  private long loadImm32u( long addr ) {
    return mem.load32( addr ) & 0xFFFFFFFFL;
  }

  private long loadImm64( long addr ) { return mem.load64( addr ); }

  private long loadImm16( long addr ) { return mem.load16( addr ) & 0xFFFFL; }

  // --- デバッグ文字列 ---

  private static final String[] REG_NAMES = {
    "rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
    "r8","r9","r10","r11","r12","r13","r14","r15"
  };

  @Override
  public String reg_str() {
    StringBuilder sb = new StringBuilder();
    for(int i=0;i<NREGS;i++) sb.append(REG_NAMES[i]).append('=').append(Long.toHexString(r64[i])).append(' ');
    return sb.toString();
  }

  @Override public String ip_str()    { return "rip="+Long.toHexString(rip)+" "; }
  @Override public String flag_str()  { return "zf="+zf+" sf="+sf+" of="+of+" cf="+cf+" "; }
  @Override public String disasm_str(long a) { return "0x"+Long.toHexString(a); }
}
