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
  // issue #188: cache 時の start_pc 先頭バイト。hit 時に再フェッチして照合し、自己書き換え
  //   コード (JIT が同 RIP に別命令を書込) で stale な prefix-skip OFFSET を使う bug
  //   (新 jmp e9 を旧命令の OFFSET で誤デコード → STD と解釈 → +2 → wild deref で crash) を防ぐ。
  private final int[]  pfx_cache_b0   = new int[PFXCACHE_SIZE];

  // issue #113: decode_and_exec は内部で this.rip を進めるため、segfault dump の
  //   get_ip() は faulting 命令の rip からズレる。EMULIN_TRACK_INSN_RIP=1 のとき
  //   eval ループが実行直前の命令開始 rip をここへ退避し、dump はこれを
  //   「真の faulting RIP」として使う (0 のままなら get_ip() に fallback)。
  public long cur_insn_rip = 0;
  // issue #113: per-thread の直近実行 RIP リングバッファ (worker ごとに独立)。
  //   worker が壊れた pointer で wild jump した直前の正常命令列を segfault dump で特定する。
  //   EMULIN_TRACE_RING=1 のときだけ記録 (既定 off、perf neutral)。
  public static final int RIPRING_SIZE = 64;  // 2 の冪
  public static final boolean TRACE_RING = System.getenv("EMULIN_TRACE_RING") != null;
  public final long[] ripRing = new long[RIPRING_SIZE];
  public int ripRingPos = 0;

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
  static final int PFX_LOCK        = 1 << 12;  // issue #113 (H3): LOCK prefix seen
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
  // issue #78: x87 FPU stack。node は long double (80-bit x87) で
  //   unordered_map の rehash bucket 数を計算するため x87 算術が必要。
  //   80-bit 精度は double (64-bit) で近似する (bucket 数等の用途では十分)。
  final double[] fpu_st = new double[8];
  int fpu_top = 0;  // st(0) の物理 index
  private void   fpuPush( double v ) { fpu_top = (fpu_top-1)&7; fpu_st[fpu_top] = v; }
  private double fpuPop( )           { double v = fpu_st[fpu_top]; fpu_top = (fpu_top+1)&7; return v; }
  private double fpuSt( int i )      { return fpu_st[(fpu_top+i)&7]; }
  private void   fpuSetSt( int i, double v ) { fpu_st[(fpu_top+i)&7] = v; }
  // reg 0=ADD 1=MUL 2=COM 3=COMP 4=SUB(a-b) 5=SUBR(b-a) 6=DIV(a/b) 7=DIVR(b/a)
  private double x87arith( int op, double a, double b ) {
    switch( op ) {
      case 0: return a + b;
      case 1: return a * b;
      case 4: return a - b;
      case 5: return b - a;
      case 6: return a / b;
      case 7: return b / a;
      default: return a;  // FCOM/FCOMP は値を変えない
    }
  }
  // FCOMI / FUCOMI: st(0) と x を比較して ZF/PF/CF を ucomisd 同様に設定。
  private void fcomiFlags( double a, double b ) {
    if( Double.isNaN(a) || Double.isNaN(b) ) { zf=1; sf=0; of=0; cf=1; }  // unordered (PF も立つが pf 未追跡)
    else if( a > b )  { zf=0; cf=0; }
    else if( a < b )  { zf=0; cf=1; }
    else              { zf=1; cf=0; }
  }
  // FISTP の丸め: control word の RC (bit 10-11) に従って long に変換。
  private long fistRound( double d ) {
    if( Double.isNaN(d) || Double.isInfinite(d) ) return 0x8000000000000000L;  // integer indefinite
    switch( (fpu_cw >> 10) & 3 ) {
      case 1:  return (long)Math.floor(d);  // round down
      case 2:  return (long)Math.ceil(d);   // round up
      case 3:  return (long)d;              // truncate
      default: return Math.round(d);        // round to nearest (簡易)
    }
  }

  SyscallAmd64 syscall64;

  // issue #84: 差分トレーサ用 RIP trace 出力。EMULIN_TRACE_RIP_FILE=<path> で
  //   有効化、EMULIN_TRACE_RIP_LO/HI=<hex> で範囲フィルタ。実行した guest RIP を
  //   8-byte LE で逐次 file に書く。最初に範囲内を実行した 1 スレッドのみ記録
  //   (bootstrap は main thread)。host ptrace トレーサと突き合わせる。
  static java.io.OutputStream ripTraceOut = null;
  static long ripTraceLo = 0L, ripTraceHi = -1L;
  static volatile long ripTraceTid = -1L;
  static boolean ripTraceInited = false;
  static synchronized void ripTraceInit() {
    if( ripTraceInited ) return;
    ripTraceInited = true;
    String f = System.getenv("EMULIN_TRACE_RIP_FILE");
    if( f == null ) return;
    try {
      ripTraceOut = new java.io.BufferedOutputStream( new java.io.FileOutputStream(f), 1<<20 );
      String lo = System.getenv("EMULIN_TRACE_RIP_LO"); if( lo != null ) ripTraceLo = Long.parseLong(lo,16);
      String hi = System.getenv("EMULIN_TRACE_RIP_HI"); if( hi != null ) ripTraceHi = Long.parseLong(hi,16);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try { synchronized(Cpu64.class){ if(ripTraceOut!=null) ripTraceOut.flush(); } } catch(Exception e){}
      }));
    } catch( Exception e ) { ripTraceOut = null; }
  }
  private void ripTraceWrite( long rip ) {
    if( Long.compareUnsigned(rip, ripTraceLo) < 0 || Long.compareUnsigned(rip, ripTraceHi) > 0 ) return;
    long tid = Thread.currentThread().getId();
    if( ripTraceTid == -1L ) ripTraceTid = tid;
    if( tid != ripTraceTid ) return;  // 単一スレッドのみ記録
    try {
      java.io.OutputStream os = ripTraceOut;
      os.write((int)(rip));       os.write((int)(rip>>>8));  os.write((int)(rip>>>16)); os.write((int)(rip>>>24));
      os.write((int)(rip>>>32));  os.write((int)(rip>>>40)); os.write((int)(rip>>>48)); os.write((int)(rip>>>56));
    } catch( Exception e ) {}
  }

  // シグナルハンドラ復帰用トランポリン。ハンドラの ret でここに着地させて、
  // eval ループで保存済みレジスタを復元する。
  //   旧実装は未マップの sentinel (0xFFFFFFFFFFFEDEAD) を戻りアドレスにしていたが、
  //   libgcc のアンワインダ (emacs の SIGABRT backtrace / C++ 例外) が signal frame を
  //   識別するため戻りアドレスのバイトを読む → 未マップ番地で SEGV する
  //   (ddskk 入力時のクラッシュ)。実マップされた rt_sigreturn トランポリンページ
  //   (Memory.ensureSigtramp、mov $0xf,%rax; syscall のバイト列) の実番地を使う。
  //   sigtramp は signal 配信時に実番地へ更新する。default の sentinel は実トランポリン
  //   確保失敗時のフォールバック (この値は通常のユーザ rip と一致しないので安全)。
  private static final long SIGRETURN_TRAMPOLINE = 0xFFFFFFFFFFFEDEADL;
  private long sigtramp = SIGRETURN_TRAMPOLINE;

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
  // issue #548: SA_SIGINFO ハンドラに渡した ucontext のアドレス (SA_SIGINFO でなければ 0)。
  //   sigtramp 復帰時に、ハンドラが ucontext.gregs を書き替えていれば (RIP を fault 命令の
  //   次へ変えて継続する wasm trap / JS crash handler 等) それを反映するために参照する。
  private final java.util.ArrayDeque<Long> sigUcontextAddrs = new java.util.ArrayDeque<>();

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

  // ROUNDSD/SS/PD/PS (SSE4.1) の imm8 丸めモード。
  //   bit2 (0x04) が立つと MXCSR.RC を使う指定だが emulin は MXCSR を
  //   追跡しないので default の round-to-nearest-even (Math.rint) とする。
  //   else bit[1:0]: 0=nearest-even, 1=floor(-inf), 2=ceil(+inf), 3=trunc(0)
  private static double roundSSE( double x, int mode ) {
    if( Double.isNaN(x) || Double.isInfinite(x) ) return x;
    if( (mode & 0x04) != 0 ) return Math.rint(x);
    switch( mode & 0x03 ) {
      case 1:  return Math.floor(x);
      case 2:  return Math.ceil(x);
      case 3:  return (x < 0) ? Math.ceil(x) : Math.floor(x);
      default: return Math.rint(x);
    }
  }
  // packed 2-float (1 long) をそれぞれ丸める (ROUNDPS の半分)
  private static long roundPS2( long packed, int mode ) {
    int lo = Float.floatToRawIntBits( (float)roundSSE( Float.intBitsToFloat((int)packed),         mode ) );
    int hi = Float.floatToRawIntBits( (float)roundSSE( Float.intBitsToFloat((int)(packed >>> 32)), mode ) );
    return ((long)lo & 0xFFFFFFFFL) | (((long)hi & 0xFFFFFFFFL) << 32);
  }

  // BLENDVPS / PBLENDVB の variable blend: lane の mask MSB が立てば src、else dst。
  //   1 long 内の 2 dword (32-bit lane) を blend (BLENDVPS の半分)。
  private static long blendDword( long dst, long src, long mask ) {
    long r = 0;
    for( int i = 0; i < 2; i++ ) {
      int sh = i * 32;
      long lane = ((((mask >>> (sh + 31)) & 1L) != 0) ? src : dst) >>> sh & 0xFFFFFFFFL;
      r |= lane << sh;
    }
    return r;
  }
  //   1 long 内の 8 byte (8-bit lane) を blend (PBLENDVB の半分)。
  private static long blendByte( long dst, long src, long mask ) {
    long r = 0;
    for( int i = 0; i < 8; i++ ) {
      int sh = i * 8;
      long lane = ((((mask >>> (sh + 7)) & 1L) != 0) ? src : dst) >>> sh & 0xFFL;
      r |= lane << sh;
    }
    return r;
  }

  // double → int32 truncate (toward zero)。範囲外/NaN は x86 の integer
  //   indefinite (0x80000000) を返す (CVTTPD2DQ / CVTTSD2SI 共通)。
  private static int cvtTruncD2I( double d ) {
    if( Double.isNaN(d) || d >= 2147483648.0 || d < -2147483648.0 ) return Integer.MIN_VALUE;
    return (int)d;
  }
  // float → int32 truncate (CVTTPS2DQ / CVTTSS2SI)。範囲外/NaN は 0x80000000。
  private static int cvtTruncF2I( float f ) {
    if( Float.isNaN(f) || f >= 2147483648.0f || f < -2147483648.0f ) return Integer.MIN_VALUE;
    return (int)f;
  }
  // packed 2-float (1 long) → 2 int32 truncate (CVTTPS2DQ の半分)。
  private static long cvtTruncPS2( long p ) {
    int lo = cvtTruncF2I( Float.intBitsToFloat((int)p) );
    int hi = cvtTruncF2I( Float.intBitsToFloat((int)(p >>> 32)) );
    return ((long)lo & 0xFFFFFFFFL) | (((long)hi & 0xFFFFFFFFL) << 32);
  }

  // signed saturate: int32 → int16 / int16 → int8 (PACKSSDW / PACKSSWB 用)
  private static long satSWord( int v ) { return (v > 32767 ? 32767 : v < -32768 ? -32768 : v) & 0xFFFFL; }
  private static long satSByte( int v ) { return (v > 127 ? 127 : v < -128 ? -128 : v) & 0xFFL; }
  // unsigned saturate: int32 → uint16 (PACKUSDW 用)
  private static long satUWord( int v ) { return (v < 0 ? 0 : v > 65535 ? 65535 : v) & 0xFFFFL; }

  // PSIGNB/W/D (SSSE3): src 要素の符号で dst を符号反転(<0)/0化(==0)/保持(>0)。
  private static long pSign( long d, long s, int esize ) {
    int lanes = 64/esize; long mask = (1L<<esize)-1; int sext = 64-esize; long r = 0;
    for( int i=0; i<lanes; i++ ) {
      int sh = i*esize;
      long de = (d>>>sh)&mask;
      long se = (((s>>>sh)&mask)<<sext)>>sext;
      long v = (se<0) ? ((-de)&mask) : (se==0) ? 0 : de;
      r |= (v&mask)<<sh;
    }
    return r;
  }
  // PABSB/W/D (SSSE3): 各 lane の絶対値。
  private static long pAbs( long s, int esize ) {
    int lanes = 64/esize; long mask = (1L<<esize)-1; int sext = 64-esize; long r = 0;
    for( int i=0; i<lanes; i++ ) {
      int sh = i*esize;
      long se = (((s>>>sh)&mask)<<sext)>>sext;
      r |= (Math.abs(se)&mask)<<sh;
    }
    return r;
  }

  // SSE4.2 文字列命令 (PCMPESTRI/PCMPESTRM/PCMPISTRI/PCMPISTRM) の共通コア。
  //   Intel SDM の Operation 擬似コードに従い IntRes2 (numElems bit) と
  //   len1/len2 を計算。glibc の strlen/strcmp/memcmp や simdutf が使う。
  //   imm[1:0]=要素 (00:ubyte 01:uword 10:sbyte 11:sword)、
  //   imm[3:2]=集約 (00:EqualAny 01:Ranges 10:EqualEach 11:EqualOrdered)、
  //   imm[5:4]=極性、imm[6]=出力選択。explicit=true なら len は引数 (ESTRI/M)。
  //   返り値: {intRes2, len1, len2, numElems}。
  private static int[] pcmpStrCore( long s1lo, long s1hi, long s2lo, long s2hi,
                                    int imm, int eLen1, int eLen2, boolean explicit ) {
    int numElems = ((imm&1)!=0) ? 8 : 16;
    int elemBits = ((imm&1)!=0) ? 16 : 8;
    boolean signed = (imm&2)!=0;
    int[] e1 = pcmpExtract( s1lo, s1hi, numElems, elemBits, signed );
    int[] e2 = pcmpExtract( s2lo, s2hi, numElems, elemBits, signed );
    int len1, len2;
    if( explicit ) {
      len1 = Math.min( Math.abs(eLen1), numElems );
      len2 = Math.min( Math.abs(eLen2), numElems );
    } else {
      len1 = numElems; for(int k=0;k<numElems;k++){ if(e1[k]==0){ len1=k; break; } }
      len2 = numElems; for(int k=0;k<numElems;k++){ if(e2[k]==0){ len2=k; break; } }
    }
    int agg = (imm>>2)&3;
    boolean[][] b = new boolean[numElems][numElems];
    for(int i=0;i<numElems;i++) for(int j=0;j<numElems;j++){
      boolean v1=i<len1, v2=j<len2, cmp;
      if(agg==1) cmp = ((i&1)==0) ? (e2[j]>=e1[i]) : (e2[j]<=e1[i]);  // Ranges (even=lo, odd=hi)
      else       cmp = (e1[i]==e2[j]);
      if(!v1 && !v2) cmp = (agg>=2);   // 両 invalid: EqualEach/Ordered→1、else→0
      else if(!v1)   cmp = (agg==3);   // xmm1 のみ invalid: Ordered→1、else→0
      else if(!v2)   cmp = false;      // xmm2 のみ invalid→0
      b[i][j]=cmp;
    }
    int intRes1=0;
    if(agg==0){ for(int j=0;j<numElems;j++){ boolean r=false; for(int i=0;i<numElems;i++) r|=b[i][j]; if(r)intRes1|=(1<<j);} }
    else if(agg==1){ for(int j=0;j<numElems;j++){ boolean r=false; for(int i=0;i+1<numElems;i+=2) r|=(b[i][j]&&b[i+1][j]); if(r)intRes1|=(1<<j);} }
    else if(agg==2){ for(int j=0;j<numElems;j++){ if(b[j][j])intRes1|=(1<<j);} }
    else { for(int j=0;j<numElems;j++){ boolean r=true; for(int i=0;i<numElems-j;i++) r=r&&b[i][i+j]; if(r)intRes1|=(1<<j);} }
    int allMask=(1<<numElems)-1, intRes2;
    if((imm&0x10)==0) intRes2=intRes1;                       // 正極性
    else if((imm&0x20)==0) intRes2 = intRes1 ^ allMask;      // 全 bit 反転
    else { intRes2=intRes1; for(int j=0;j<len2;j++) intRes2 ^= (1<<j); }  // valid のみ反転
    intRes2 &= allMask;
    return new int[]{ intRes2, len1, len2, numElems };
  }
  private static int[] pcmpExtract( long lo, long hi, int numElems, int elemBits, boolean signed ) {
    int[] e=new int[numElems];
    for(int k=0;k<numElems;k++){
      long v;
      if(elemBits==8){ v=((k<8)?(lo>>>(k*8)):(hi>>>((k-8)*8)))&0xFF; if(signed) v=(byte)v; }
      else           { v=((k<4)?(lo>>>(k*16)):(hi>>>((k-4)*16)))&0xFFFF; if(signed) v=(short)v; }
      e[k]=(int)v;
    }
    return e;
  }

  // PMIN/PMAX 系 (SSE4.1)。1 long 内の esize-bit lane ごとに min/max。
  //   esize: 8/16/32、signed: 符号付き比較か、isMax: max か min か。
  private static long pMinMax( long d, long s, int esize, boolean signed, boolean isMax ) {
    int lanes = 64 / esize;
    long mask = (1L << esize) - 1;
    int sext = 64 - esize;
    long r = 0;
    for( int i = 0; i < lanes; i++ ) {
      int sh = i * esize;
      long de = (d >>> sh) & mask, se = (s >>> sh) & mask;
      boolean dGe;  // de >= se ?
      if( signed ) dGe = ((de << sext) >> sext) >= ((se << sext) >> sext);
      else         dGe = Long.compareUnsigned( de, se ) >= 0;
      long pick = ( isMax == dGe ) ? de : se;
      r |= (pick & mask) << sh;
    }
    return r;
  }

  // packed single (1 long = 2 float lane) の算術。ADDPS/MULPS/SUBPS/MINPS/
  //   DIVPS/MAXPS (58/59/5C/5D/5E/5F)、SQRTPS(51)。op は 0F の 2nd byte。
  private static long packedSingleOp( long d, long s, int op ) {
    long r = 0;
    for( int i = 0; i < 2; i++ ) {
      int sh = i * 32;
      float a = Float.intBitsToFloat( (int)(d >>> sh) );
      float b = Float.intBitsToFloat( (int)(s >>> sh) );
      float v;
      switch( op ) {
        case 0x58: v = a + b; break;
        case 0x59: v = a * b; break;
        case 0x5C: v = a - b; break;
        case 0x5D: v = Math.min(a, b); break;
        case 0x5E: v = a / b; break;
        case 0x5F: v = Math.max(a, b); break;
        default:   v = (float)Math.sqrt(b); break;   // 0x51 SQRTPS
      }
      r |= ((long)Float.floatToRawIntBits(v) & 0xFFFFFFFFL) << sh;
    }
    return r;
  }

  // ModRM デコード結果
  private int  mrm_mod, mrm_reg, mrm_rm;
  private long mrm_ea;
  // issue #567: 現在実行中の命令に LOCK prefix が付いているか。exec_grp5_ff 等が memory operand
  //   への RMW を VarHandle atomic (mem.atomicAdd) にするか従来の read+write にするかの判定に使う。
  private boolean curLockPrefix;
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
  @Override public void   set_fs_base( long base ) { fs_base = base; }
  @Override public long   get_fs_base()            { return fs_base; }

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

  // issue #233 (Step 3/3 of #221 refactor): pthread / CLONE_THREAD の子 vCPU 生成を
  //   Cpu64 内に集約。旧実装は SyscallAmd64.amd64_clone_thread が直接 `new Cpu64(...)`
  //   を呼んでいた。本 step では「移動だけ」で logic 変更ゼロ — 命令アドレス
  //   (set_ip / r64[R_RCX]=next_pc)、register コピー (copy_state_from)、stack 設定、
  //   TLS / CLONE_SETTLS、Thread64 生成 + start、CLONE_PARENT_SETTID / CTID 書込、
  //   parent signal mask 継承、TRACE_MMAP log を全てそのまま温存する。
  //
  // 注意: issue #113 ROOT CAUSE は「呼び出し thread の Cpu64 を親にする」選択 (worker
  //   なら Thread64.cpu、main なら process.cpu) であり、その選択は本メソッドの
  //   呼び出し元 (amd64_clone_thread) が行う。本メソッドは「親に対して呼ばれる」=
  //   `this` = parent_cpu。だから RCX (R_RCX=1)・register state はすべて `this` から
  //   読む。
  @Override
  public long spawnVCpu( long flags, long child_stack, long ptid, long ctid, long tls ) {
    final long CLONE_PARENT_SETTID  = 0x100000L;
    final long CLONE_CHILD_CLEARTID = 0x200000L;
    final long CLONE_SETTLS         = 0x80000L;

    Cpu64 child_cpu  = new Cpu64( sysinfo, process );
    // 親 (= this) のレジスタを子にコピー → 子側で rax=0、rsp=child_stack、rip=next を上書き
    child_cpu.copy_state_from( this );
    // exec_syscall が r64[R_RCX] = next_pc を設定済み (syscall ABI で rcx は
    //   syscall return address)。子はそこから実行を再開する。
    child_cpu.set_ip( this.r64[1] );  // R_RCX = 1 → next_pc
    child_cpu.set_ax( 0 );
    if( child_stack != 0 ) child_cpu.set_sp( child_stack );
    if( (flags & CLONE_SETTLS) != 0 ) child_cpu.fs_base = tls;
    child_cpu.connect_devices( mem, syscall );

    if( System.getenv("EMULIN_TRACE_MMAP") != null ) {
      System.err.println( "[clone] flags=0x"+Long.toHexString(flags)+" child_stack=0x"+Long.toHexString(child_stack)
        +" tls=0x"+Long.toHexString(tls) );
    }
    int tid = sysinfo.kernel.next_tid( );
    long ctid_for_clear = ((flags & CLONE_CHILD_CLEARTID) != 0) ? ctid : 0L;
    // Phase 27 step 34: 子 thread は親の現 signal_mask を継承 (POSIX clone 仕様)
    long parent_mask = process.get_signal_mask_bits();
    Thread64 t = new Thread64( process, child_cpu, tid, mem, ctid_for_clear, parent_mask );

    if( (flags & CLONE_PARENT_SETTID) != 0 && ptid != 0 ) {
      mem.store32( ptid, tid );
    }
    if( ctid != 0 ) mem.store32( ctid, tid );

    t.start();
    return tid;
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
    // EMULIN_TRACE_MALLOC_BIG=<HEX_MALLOC_RIP>: その RIP (= libc malloc entry)
    //   に到達したとき rdi (要求サイズ) が巨大 (>1GB) なら size + caller stack
    //   を dump。emulin の命令バグで size 計算が壊れて bad_alloc になる箇所の
    //   特定用 (issue #78)。
    long trace_malloc_entry = 0;
    String tmb = System.getenv("EMULIN_TRACE_MALLOC_BIG");
    if( tmb != null ) {
      try { trace_malloc_entry = Long.parseLong( tmb, 16 ); }
      catch ( NumberFormatException ignored ) { trace_malloc_entry = 0; }
    }
    // EMULIN_TRACE_RIP_STACK=<HEX_RIP>: その RIP 到達時に caller stack を
    //   無条件 dump (throw 元の特定用)。
    long trace_rip_stack = 0;
    String trs = System.getenv("EMULIN_TRACE_RIP_STACK");
    if( trs != null ) {
      try { trace_rip_stack = Long.parseLong( trs, 16 ); }
      catch ( NumberFormatException ignored ) { trace_rip_stack = 0; }
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
    // EMULIN_WATCH_GPR=<HEX_RIP>: RIP 到達時に主要 GPR + 戻りアドレスを最初の
    //   64 回だけ dump (hot loop で flood しないよう上限付き)。関数の引数や
    //   戻り値の遷移を追う汎用デバッグ用。
    long watch_gpr_rip = 0;
    long watch_gpr_count = 0;
    String wgpr = System.getenv("EMULIN_WATCH_GPR");
    if( wgpr != null ) { try { watch_gpr_rip = Long.parseLong( wgpr, 16 ); } catch ( NumberFormatException ignored ) {} }
    long watch_eval_lo = 0, watch_eval_hi = Long.MAX_VALUE;
    { String s = System.getenv("EMULIN_WATCH_EVAL_LO"); if( s != null ) try { watch_eval_lo = Long.parseLong(s); } catch( NumberFormatException e ){} }
    { String s = System.getenv("EMULIN_WATCH_EVAL_HI"); if( s != null ) try { watch_eval_hi = Long.parseLong(s); } catch( NumberFormatException e ){} }
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
    long badlookup_rip = 0;  // issue #113: EMULIN_BADLOOKUP_RIP で 0x124bc3 等の bad-lookup site を監視
    { String s = System.getenv("EMULIN_BADLOOKUP_RIP"); if( s != null ) try { badlookup_rip = Long.parseLong(s,16); } catch( NumberFormatException ignored ){} }
    ripTraceInit();
    final boolean any_trace_active = badlookup_rip != 0 || trace_rip_period > 0 || trace_fp || trace_sh
        || dump_at_rip != 0 || watch_rip_dump != 0 || watch_rip_dump2 != 0
        || watch_rip_dump3 != 0 || watch_rip_dump4 != 0 || trace_free_entry != 0
        || trace_malloc_entry != 0 || trace_rip_stack != 0 || ripTraceOut != null
        || watch_gpr_rip != 0;
    // issue #113: EMULIN_TRACK_INSN_RIP=1 のときだけ命令開始 rip を毎命令退避する。
    //   既定 off では下の if が not-taken で済み hot loop に追加 store を出さない
    //   (per-命令の無条件 long write は evals++ 同様に数% のコストになるため)。
    final boolean track_insn_rip = System.getenv("EMULIN_TRACK_INSN_RIP") != null;
    // issue #113: EMULIN_DETECT_TRUNC=1 で「あるレジスタが直前命令の別レジスタ
    //   (PIE/stack ポインタ ≥0x555555554000) の下位32bit ちょうどに化けた瞬間」
    //   = 64bit→32bit 切り詰めの発生命令を検出する。EMULIN_WATCH_EVAL_LO/HI で
    //   範囲を絞り、EMULIN_TRACK_INSN_RIP=1 と併用して真の RIP を得る。既定 off。
    final boolean detect_trunc = System.getenv("EMULIN_DETECT_TRUNC") != null;
    final long[] truncSnap = detect_trunc ? new long[16] : null;
    int truncDumps = 0;
    if( Memory.GLOBAL_LOCK ) mem.execLock.lock();   // issue #113 GIL: eval ループ開始で取得
    try {
    while( !process.is_exited() ) {
      executed++;
      // Phase 27 step 24: process.evals は segfault 診断と trace でしか
      //   使われないので、毎命令の write は無駄。1024 命令ごとに同期する
      //   (segfault 時のずれは最大 1023 命令、許容範囲)。
      if( (executed & 0x3FF) == 0 ) {
        process.evals = executed;
        // issue #113 GIL: 他 worker が lock 待ちなら release+yield+再取得 (CPU bound thread の
        //   lock 独占を防ぐ)。単一 thread (待ち無し) のときは hasQueuedThreads()=false で no-op。
        if( Memory.GLOBAL_LOCK && mem.execLock.hasQueuedThreads() ) {
          mem.execLock.unlock(); Thread.yield(); mem.execLock.lock();
        }
      }
      // シグナルハンドラからの復帰: トランポリンに着地したらレジスタを戻す。
      if( rip == sigtramp ) {
        long[] frame = sigSavedFrames.pollFirst();
        Long ucAddrObj = sigUcontextAddrs.pollFirst();   // issue #548: 対で pop
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
          // issue #119: PF と XMM0-15 を復元 (save と対称)
          pf  = (int)frame[NREGS + 6];
          System.arraycopy( frame, NREGS + 7,  xmm_lo, 0, 16 );
          System.arraycopy( frame, NREGS + 23, xmm_hi, 0, 16 );
          // issue #119 (継続): x87 FPU 状態を復元 (save と対称)
          fpu_cw  = (int)frame[NREGS + 39];
          fpu_sw  = (int)frame[NREGS + 40];
          fpu_tag = (int)frame[NREGS + 41];
          fpu_top = (int)frame[NREGS + 42];
          for( int i = 0; i < 8; i++ ) fpu_st[i] = Double.longBitsToDouble( frame[NREGS + 43 + i] );
          // issue #548: SA_SIGINFO ハンドラが ucontext.gregs を書き替えていれば反映する。
          //   Linux の rt_sigreturn はユーザスタック上の ucontext から復元するので、ハンドラが
          //   uc_mcontext.gregs[REG_RIP] を fault 命令の次へ変えて継続 (wasm trap / null-check
          //   elision / JS crash handler の中核パターン) できる。GPR/rip/rsp/eflags を ucontext
          //   から上書きする (mask/xmm/fpu は簡易 ucontext に未保存なので上の frame 由来を維持)。
          long ucAddr = (ucAddrObj != null) ? ucAddrObj : 0L;
          if( ucAddr != 0 ) {
            r64[8]     = mem.load64( ucAddr + 40  );  // r8
            r64[9]     = mem.load64( ucAddr + 48  );  // r9
            r64[10]    = mem.load64( ucAddr + 56  );  // r10
            r64[11]    = mem.load64( ucAddr + 64  );  // r11
            r64[12]    = mem.load64( ucAddr + 72  );  // r12
            r64[13]    = mem.load64( ucAddr + 80  );  // r13
            r64[14]    = mem.load64( ucAddr + 88  );  // r14
            r64[15]    = mem.load64( ucAddr + 96  );  // r15
            r64[R_RDI] = mem.load64( ucAddr + 104 );  // rdi
            r64[R_RSI] = mem.load64( ucAddr + 112 );  // rsi
            r64[R_RBP] = mem.load64( ucAddr + 120 );  // rbp
            r64[R_RBX] = mem.load64( ucAddr + 128 );  // rbx
            r64[R_RDX] = mem.load64( ucAddr + 136 );  // rdx
            r64[R_RAX] = mem.load64( ucAddr + 144 );  // rax
            r64[R_RCX] = mem.load64( ucAddr + 152 );  // rcx
            r64[R_RSP] = mem.load64( ucAddr + 160 );  // rsp
            rip        = mem.load64( ucAddr + 168 );  // rip (書き替えられた継続点)
            long efl   = mem.load64( ucAddr + 176 );
            cf = (int)( efl        & 1L);
            pf = (int)((efl >> 2 ) & 1L);
            zf = (int)((efl >> 6 ) & 1L);
            sf = (int)((efl >> 7 ) & 1L);
            of = (int)((efl >> 11) & 1L);
          }
        }
      }
      // pending シグナルがあればハンドラへ分岐
      check_pending_signal();
      if( any_trace_active ) {
      if( badlookup_rip != 0 && rip == badlookup_rip && r64[R_RDX] < 0x555555554000L ) {
        long rdi = r64[R_RDI], cell = rdi + 3;
        long cv = ( mem.in(cell) && mem.in(cell+7) ) ? mem.load64(cell) : -1L;
        System.err.println("DBG_BADLOOKUP eval="+executed+" rdi=0x"+Long.toHexString(rdi)
          +" cell(rdi+3)=0x"+Long.toHexString(cell)+" *cell=0x"+Long.toHexString(cv)
          +" rdx=0x"+Long.toHexString(r64[R_RDX])+" rbx=0x"+Long.toHexString(r64[R_RBX])
          +" r12=0x"+Long.toHexString(r64[12])+" r13=0x"+Long.toHexString(r64[13]));
        System.err.flush();
      }
      if( ripTraceOut != null ) ripTraceWrite( rip );
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
      if( watch_gpr_rip != 0 && rip == watch_gpr_rip && watch_gpr_count < 64 && executed >= watch_eval_lo && executed <= watch_eval_hi ) {
        watch_gpr_count++;
        long retaddr = mem.load64( r64[R_RSP] );
        System.err.println("DBG_GPR #"+watch_gpr_count+" rip=0x"+Long.toHexString(rip)
          +" ret=0x"+Long.toHexString(retaddr)
          +" rdi=0x"+Long.toHexString(r64[R_RDI])
          +" rsi=0x"+Long.toHexString(r64[R_RSI])
          +" rdx=0x"+Long.toHexString(r64[R_RDX])
          +" rcx=0x"+Long.toHexString(r64[1])
          +" rax=0x"+Long.toHexString(r64[R_RAX])
          +" r12=0x"+Long.toHexString(r64[12])
          +" r13=0x"+Long.toHexString(r64[13])
          +" r15=0x"+Long.toHexString(r64[15])
          +" eval="+executed);
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
      if( trace_malloc_entry != 0 && rip == trace_malloc_entry ) {
        long sz = r64[R_RDI];
        if( Long.compareUnsigned(sz, 0x40000000L) > 0 ) {  // > 1GB は不審
          long sp = r64[R_RSP];
          StringBuilder sb = new StringBuilder();
          sb.append("DBG_MALLOC_BIG size=0x").append(Long.toHexString(sz));
          sb.append(" rsp=0x").append(Long.toHexString(sp));
          for( int k = 0; k < 10; k++ ) {
            try {
              long ra = mem.load64(sp + 8L*k);
              sb.append(" [+").append(k*8).append("]=0x").append(Long.toHexString(ra));
            } catch( Throwable t ) { break; }
          }
          System.err.println(sb.toString());
          System.err.flush();
        }
      }
      if( trace_rip_stack != 0 && rip == trace_rip_stack ) {
        long sp = r64[R_RSP];
        StringBuilder sb = new StringBuilder();
        sb.append("DBG_RIP_STACK rip=0x").append(Long.toHexString(rip));
        sb.append(" rsp=0x").append(Long.toHexString(sp));
        sb.append(" rdi=0x").append(Long.toHexString(r64[R_RDI]));
        sb.append(" rsi=0x").append(Long.toHexString(r64[R_RSI]));
        sb.append(" xmm0=").append(Double.longBitsToDouble(xmm_lo[0])).append("(0x").append(Long.toHexString(xmm_lo[0])).append(")");
        sb.append(" xmm1=").append(Double.longBitsToDouble(xmm_lo[1])).append("(0x").append(Long.toHexString(xmm_lo[1])).append(")");
        sb.append(" xmm2=").append(Double.longBitsToDouble(xmm_lo[2]));
        // rsi が pointer (std::vector ref 等) のとき [rsi+0/8/16] を dump
        for( int k = 0; k <= 16; k += 8 ) {
          try { sb.append(" *[rsi+").append(k).append("]=0x").append(Long.toHexString(mem.load64(r64[R_RSI]+k))); }
          catch( Throwable t ) {}
        }
        for( int k = 0; k < 8; k++ ) {
          try {
            long ra = mem.load64(sp + 8L*k);
            sb.append(" [+").append(k*8).append("]=0x").append(Long.toHexString(ra));
          } catch( Throwable t ) { break; }
        }
        System.err.println(sb.toString());
        System.err.flush();
      }
      } // end if( any_trace_active )
      if( track_insn_rip ) cur_insn_rip = rip;  // issue #113: segfault dump 用 (既定 off)
      if( TRACE_RING ) { ripRing[ ripRingPos & (RIPRING_SIZE-1) ] = rip; ripRingPos++; }  // issue #113: 直近 RIP 記録
      if( detect_trunc && executed >= watch_eval_lo && executed <= watch_eval_hi )
        System.arraycopy( r64, 0, truncSnap, 0, 16 );  // issue #113: 命令前の GPR を退避
      // Phase 34-A3 step 23: JIT 経路は jitStep() に extract。eval() の
      // method body を小さく保つことで C2 が hot loop 全体を fully optimize
      // しやすくなる。ENABLED が false のときは static final 定数畳み込みで
      // 完全に dead code になる。
      if( emulin.jit.Translator.ENABLED ) {
        rip = jitStep( rip );
      } else {
        rip = decode_and_exec( rip );
      }
      if( detect_trunc && truncDumps < 40 && executed >= watch_eval_lo && executed <= watch_eval_hi ) {
        for( int ti = 0; ti < 16; ti++ ) {
          long nv = r64[ti];
          if( nv == truncSnap[ti] || nv < 0x10000L || nv >= 0x100000000L ) continue;
          for( int tj = 0; tj < 16; tj++ ) {
            long ov = truncSnap[tj];
            // ov が本物のポインタ (PIE code/heap 0x5555__ / stack-heap 0x7fff__) の
            //   ときだけ対象。型タグ付き Lisp 値 (0x40000000_) や mask は除外。
            boolean ovIsPtr = (ov >= 0x555555554000L && ov < 0x556000000000L)
                           || (ov >= 0x7f0000000000L && ov < 0x800000000000L);
            if( ovIsPtr && (ov & 0xFFFFFFFFL) == nv ) {
              // REX.W (= 本来 64bit operand) の命令が truncate したものだけ報告。
              //   REX.W 無し (本来 32bit op) の truncate は正規なので除外 (false positive 抑制)。
              boolean rexw = false;
              for( int k = 0; k < 8; k++ ) {
                int by = (int)mem.load8( cur_insn_rip + k ) & 0xFF;
                if( by==0x66||by==0x67||by==0xf0||by==0xf2||by==0xf3||by==0x2e||by==0x36||by==0x3e||by==0x26||by==0x64||by==0x65 ) continue;
                if( (by & 0xF0) == 0x40 ) rexw = (by & 0x08) != 0;
                break;
              }
              if( !rexw ) break;
              String[] rn = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
              StringBuilder bt = new StringBuilder(); long bsp = r64[4], pb = 0x555555554000L;
              for( int bo = 0; bo < 0x100 && bt.length() < 180; bo += 8 ) {
                if( !mem.in( bsp + bo ) ) break;
                long bv = mem.load64( bsp + bo );
                if( bv >= pb && bv < pb + 0x400000L ) bt.append(' ').append(Long.toHexString(bv - pb));
              }
              StringBuilder ins = new StringBuilder();
              for( int ib = 0; ib < 8; ib++ ) ins.append(String.format("%02x ", (int)mem.load8(cur_insn_rip+ib) & 0xFF));
              System.err.println("DBG_TRUNC rip=0x"+Long.toHexString(cur_insn_rip)+" insn="+ins+" "+rn[ti]+"=0x"+Long.toHexString(nv)
                +" = low32("+rn[tj]+"=0x"+Long.toHexString(ov)+") eval="+executed+" bt:"+bt);
              System.err.flush(); truncDumps++; break;
            }
          }
        }
      }
    }
    } finally {
      if( Memory.GLOBAL_LOCK ) mem.execLock.unlock();   // issue #113 GIL: release (SegfaultException 等の例外時も)
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
        try {
          return ci.execute( this );
        } catch( JitDeTrap t ) {
          // issue #537: block 内 DIV の #DE。trap pc から interpreter で再実行して SIGFPE を
          //   配送する。jit_lookup_next=false で次の 1 step は必ず interpreter に落とす
          //   (trap pc 自身が block entry だと lookup が同じ block を再実行して無限 trap になるため)。
          jit_lookup_next = false;
          return t.pc;
        }
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
    // issue #615: 配送する siginfo を消費前に読む (RT signal は 1 つずつ、標準は合体消費)。
    int  siCode  = process.get_si_code( sig );
    long siValue = process.get_si_value( sig );
    int  siPid   = process.get_si_pid( sig );
    process.consume_one( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      int action = process.get_action_type( sig );
      if( action == Signal.SIGACTION_EXIT ) {
        process.term_sig = sig;   // issue #411: 死因 signal を記録 → wait4 が WIFSIGNALED(sig) を返す
        process.set_exit_flag();
      }
      return;
    }
    enterSignalHandler( sig, handler, siCode, 0L, siValue, siPid );
  }

  // issue #503: シグナルハンドラ起動の共通部 (async は check_pending_signal から si_code=0、
  //   同期例外 (div0 等) は deliverSyncSignal から si_code 付きで呼ぶ)。呼出し時 rip は
  //   ハンドラ復帰点 (割込み点 / fault した命令) を指していること。
  private void enterSignalHandler( int sig, long handler, int si_code ) {
    enterSignalHandler( sig, handler, si_code, 0L );
  }

  // issue #548: SegfaultException (unmapped/保護違反アクセス) を Process.run が catch した際、
  //   SIGSEGV ハンドラが登録されていれば起動して true を返す (呼び元は eval を再開する)。
  //   Linux ではハンドラが ucontext.rip を書き替えて fault 命令を skip / mprotect して再開できる
  //   (wasm trap / null-check elision / JS crash handler の中核)。未登録 (SIG_DFL/IGN) は false =
  //   呼び元が既定の SIGSEGV 終了を行う (従来動作)。eval のホットループには try/catch を置かない
  //   (C2 最適化阻害を避ける)。si_code: canonical 未 map=SEGV_MAPERR(1)、非 canonical=SI_KERNEL(0x80)、
  //   privileged instruction (issue #597 の HLT 等、seCode==3) も強制的に SI_KERNEL/si_addr=0。
  public boolean deliverSegvToHandler( long faultAddr, int seCode ) {
    return deliverFaultToHandler( Signal.SIGSEGV, faultAddr, seCode );
  }
  // issue #617: SIGSEGV / SIGBUS を fault 種別に応じて登録ハンドラへ配送する共通版。
  //   sig=SIGBUS (file map の EOF 越え) は si_code=BUS_ADRERR(2) / si_addr=fault 番地。
  public boolean deliverFaultToHandler( int sig, long faultAddr, int seCode ) {
    long h = process.get_func_adrs( sig );
    if( h == Siginfo.SIG_DFL || h == Siginfo.SIG_IGN ) return false;
    process.term_sig = 0;                                        // ハンドラで処理 → 死因クリア
    int  siCode;
    long siAddr;
    if( sig == Signal.SIGBUS ) {                                 // issue #617: EOF 越え file map
      siCode = 2 /*BUS_ADRERR*/;   siAddr = faultAddr;
    } else if( seCode == 2 ) {                                    // issue #559: mprotect 権限違反
      siCode = 2 /*SEGV_ACCERR*/;  siAddr = faultAddr;          //   map 済み・権限なし。si_addr は正確
    } else if( seCode == 3 ) {                                    // issue #597: privileged instruction (HLT 等)
      siCode = 0x80 /*SI_KERNEL*/; siAddr = 0L;                   //   #GP 由来、si_addr は常に 0
    } else {
      boolean canonical = ( faultAddr >= 0 && faultAddr < 0x800000000000L );
      siCode = canonical ? 1        : 0x80;                      // SEGV_MAPERR / SI_KERNEL
      siAddr = canonical ? faultAddr : 0L;                       // 非 canonical は si_addr=0
    }
    enterSignalHandler( sig, h, siCode, siAddr );                // rip をハンドラにセット
    return true;
  }
  // issue #548: si_addr 付き。SIGSEGV/SIGBUS 等の fault 番地を siginfo.si_addr に埋める。
  private void enterSignalHandler( int sig, long handler, int si_code, long si_addr ) {
    enterSignalHandler( sig, handler, si_code, si_addr, 0L, 0 );
  }
  // issue #615: si_value / si_pid も渡せる版。sigqueue(rt_sigqueueinfo) の SA_SIGINFO
  //   ハンドラへ si_value を届ける。si_code <= 0 (SI_USER/SI_QUEUE 等 = user 生成) の
  //   siginfo は si_pid@16 / si_uid@20 / si_value@24、si_code > 0 (kernel 同期 fault) は
  //   si_addr@16 (union) を書く。
  private void enterSignalHandler( int sig, long handler, int si_code, long si_addr, long si_value, int si_pid ) {
    // ユーザーハンドラ呼び出し:
    //   実機 Linux カーネルは ucontext に全レジスタ + flags を保存し、
    //   ハンドラ復帰時に sa_restorer → rt_sigreturn 経由で復元する。
    //   ここでは Java 側に保存し、ユーザスタックに SIGRETURN_TRAMPOLINE を
    //   push してハンドラの ret でその番地に着地させ、eval ループ側で
    //   復元する。
    //
    //   保存対象: 16 本の GPR + rip + flags(of,sf,zf,cf,pf) + XMM0-15 + x87 + mask
    //   ハンドラ進入時に rdi = sig をセット (POSIX `void(int)` ABI)
    //   issue #119: frame layout = [0..NREGS-1] GPR, [NREGS]=rip,
    //     [NREGS+1..+4]=of/sf/zf/cf, [NREGS+5]=mask, [NREGS+6]=pf,
    //     [NREGS+7..+22]=xmm_lo[0..15], [NREGS+23..+38]=xmm_hi[0..15],
    //     [NREGS+39]=fpu_cw, [NREGS+40]=fpu_sw, [NREGS+41]=fpu_tag,
    //     [NREGS+42]=fpu_top, [NREGS+43..+50]=fpu_st[0..7] (raw double bits)
    long[] frame = new long[NREGS + 51];
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
    // issue #119: PF と XMM0-15 (xmm_lo/xmm_hi) も保存。実機 Linux は ucontext の
    //   fpregs に全 XMM を保存し rt_sigreturn で復元する。ハンドラが SSE を使っても
    //   (glibc の memcpy/strlen 等はほぼ使う) 被中断側の live XMM data が壊れない
    //   ようにする。signal は稀なので 33 long copy のコストは無視できる。
    frame[NREGS + 6] = pf;
    System.arraycopy( xmm_lo, 0, frame, NREGS + 7,  16 );
    System.arraycopy( xmm_hi, 0, frame, NREGS + 23, 16 );
    // issue #119 (継続): x87 FPU 状態 (制御/状態/tag word + stack top + st(0..7)) も
    //   保存。64bit でも long double 演算 (printf %Lf や issue #78 の unordered_map
    //   hash 等) で x87 stack/制御ワードが live になりうる。ハンドラが x87 を使うと
    //   被中断側の fpu_st/fpu_top/fpu_cw が壊れるため XMM と対称に保存復元する。
    frame[NREGS + 39] = fpu_cw;
    frame[NREGS + 40] = fpu_sw;
    frame[NREGS + 41] = fpu_tag;
    frame[NREGS + 42] = fpu_top;
    for( int i = 0; i < 8; i++ ) frame[NREGS + 43 + i] = Double.doubleToRawLongBits( fpu_st[i] );
    sigSavedFrames.push( frame );
    long new_mask = saved_mask | process.get_sa_mask( sig );
    if( !process.has_sa_nodefer( sig )) {
      // sig 自身もマスク (POSIX デフォルト動作、sa_mask に bit を追加)
      if( sig >= 1 && sig < 32 ) new_mask |= (1L << (sig - 1));
    }
    process.set_signal_mask_bits( new_mask );
    // ハンドラ起動 stack の決定。
    //   SA_ONSTACK + sigaltstack 登録時は代替 stack の top から始める (Linux カーネル流儀)。
    //   Go runtime は全 handler に SA_ONSTACK を立て M ごとに alt stack を登録する。これを
    //   honor しないと handler が割込み点の goroutine stack 上で走り、Go の adjustSignalStack
    //   が foreign stack の signal と誤認 → needm → 無限 spin (issue #221 netpoller hang)。
    //   それ以外は従来通り割込み stack の red zone(128 byte) をスキップする。
    long altBase = process.sig_alt_stack_base( sig, r64[R_RSP] );
    if( altBase >= 0 ) r64[R_RSP] = altBase & ~15L;
    else               r64[R_RSP] -= 128;

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
      mem.store32( siginfo_addr + 8,    si_code );  // si_code (issue #503: sync 例外は FPE_INTDIV 等)
      if( si_code <= 0 ) {
        // issue #615: SI_USER(0) / SI_QUEUE(-1) 等 user 生成 signal の siginfo union:
        //   si_pid@16, si_uid@20, si_value(sival_int/ptr)@24。sigqueue の si_value を運ぶ。
        mem.store32( siginfo_addr + 16, si_pid );        // si_pid
        mem.store32( siginfo_addr + 20, process.uid );   // si_uid
        mem.store64( siginfo_addr + 24, si_value );      // si_value (sival_int は下位32bit)
      } else {
        mem.store64( siginfo_addr + 16,   si_addr );  // issue #548: si_addr (SIGSEGV の fault 番地)
      }

      r64[R_RSP] -= 256;
      ucontext_addr = r64[R_RSP];
      // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
      mem.bulkZero( ucontext_addr, 256 );
      // uc_flags=0, uc_link=NULL。uc_mcontext (struct sigcontext) は ucontext+40
      //   から。割込み時の GPR/rip/eflags を埋めて、libgcc アンワインダ
      //   (emacs の SIGABRT backtrace 等) が signal frame を越えて呼出し元へ正しく
      //   遡れるようにする。全 0 のままだと割込み rip=0 になり、アンワインダが
      //   signal frame の次フレームとして [0] を読みに行き再 SEGV する。
      //   x86-64 sigcontext gregs offset (ucontext 基準): r8=40 r9=48 r10=56 r11=64
      //   r12=72 r13=80 r14=88 r15=96 rdi=104 rsi=112 rbp=120 rbx=128 rdx=136
      //   rax=144 rcx=152 rsp=160 rip=168 eflags=176。
      long uc = ucontext_addr;
      mem.store64( uc + 40,  frame[8]      );  // r8
      mem.store64( uc + 48,  frame[9]      );  // r9
      mem.store64( uc + 56,  frame[10]     );  // r10
      mem.store64( uc + 64,  frame[11]     );  // r11
      mem.store64( uc + 72,  frame[12]     );  // r12
      mem.store64( uc + 80,  frame[13]     );  // r13
      mem.store64( uc + 88,  frame[14]     );  // r14
      mem.store64( uc + 96,  frame[15]     );  // r15
      mem.store64( uc + 104, frame[R_RDI]  );  // rdi
      mem.store64( uc + 112, frame[R_RSI]  );  // rsi
      mem.store64( uc + 120, frame[R_RBP]  );  // rbp
      mem.store64( uc + 128, frame[R_RBX]  );  // rbx
      mem.store64( uc + 136, frame[R_RDX]  );  // rdx
      mem.store64( uc + 144, frame[R_RAX]  );  // rax
      mem.store64( uc + 152, frame[R_RCX]  );  // rcx
      mem.store64( uc + 160, frame[R_RSP]  );  // rsp
      mem.store64( uc + 168, frame[NREGS]  );  // rip (割込み点)
      long efl = 2L                                  // bit1 は常に 1
               | ((frame[NREGS + 4] != 0) ? 0x001L : 0)   // CF
               | ((frame[NREGS + 6] != 0) ? 0x004L : 0)   // PF
               | ((frame[NREGS + 3] != 0) ? 0x040L : 0)   // ZF
               | ((frame[NREGS + 2] != 0) ? 0x080L : 0)   // SF
               | ((frame[NREGS + 1] != 0) ? 0x800L : 0);  // OF
      mem.store64( uc + 176, efl );
    }

    // ハンドラの ret 着地先 = 実マップ済み rt_sigreturn トランポリン番地。
    //   未マップ sentinel だと libgcc アンワインダがバイト読みで SEGV する。
    //   確保失敗時のみ従来 sentinel にフォールバック。
    long tramp = mem.ensureSigtramp();
    sigtramp = (tramp > 0) ? tramp : SIGRETURN_TRAMPOLINE;
    push64( sigtramp );
    rip = handler;
    r64[R_RDI] = (long)sig;
    // issue #548: ucontext のアドレスを記録 (SA_SIGINFO でなければ 0)。sigtramp 復帰時に
    //   ハンドラが gregs を書き替えていれば反映する。sigSavedFrames と対で LIFO push。
    sigUcontextAddrs.push( ucontext_addr );
    if( siginfo_addr != 0 ) {
      r64[R_RSI] = siginfo_addr;
      r64[R_RDX] = ucontext_addr;
    }
  }

  // issue #503: CPU 例外 (#DE 等) を同期シグナルとして配送する。ハンドラ未設定 (SIG_DFL/IGN)
  //   なら既定動作 = その signal でプロセス終了 (core)。ハンドラ有りなら起動して着地 rip を返す。
  private long deliverSyncSignal( int sig, int si_code, long faultPc ) {
    long handler = process.get_func_adrs( sig );
    if( handler == Siginfo.SIG_IGN || handler == Siginfo.SIG_DFL ) {
      // SIGFPE の default / (hw 例外の) ignore はいずれもプロセス終了 (POSIX: 例外由来の
      //   SIG_IGN は undefined、Linux は terminate)。term_sig を記録し wait4 が WIFSIGNALED。
      process.term_sig = sig;
      process.set_exit_flag();
      // main process (親=init) の signal-kill は JVM 終了コードに反映 (128+sig、
      //   raiseSegv の 128+SIGSEGV と同じ流儀)。fork 子は親が wait4 で読む。
      ProcessInfo mp = sysinfo.kernel.get_pinfo( process.pid );
      if( mp != null && mp.ppid <= 1 ) sysinfo.kernel.last_exit_code = 128 + sig;
      return faultPc;
    }
    rip = faultPc;                    // ハンドラ復帰点 = fault した命令
    enterSignalHandler( sig, handler, si_code );
    return rip;                       // = handler
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

  // issue #508: PF/AF を全 ALU ヘルパで設定する。
  //   PF = result 下位 8bit の 1 の個数が偶数なら 1 (SDM Vol.1 §3.4.3.1)。
  //   AF = bit3→bit4 の桁上げ/借り = (a ^ b ^ result) の bit4。
  private void setPF( long result ) {
    int b = (int)(result & 0xFF);
    b ^= b >> 4; b ^= b >> 2; b ^= b >> 1;
    pf = (b & 1) ^ 1;
  }
  // issue #520/#521: E0-E3 (LOOP系/JECXZ) は addr-size prefix (0x67) の有無で RCX/ECX を
  //   使い分けるが、prefix scan は 0x67 を消費するだけで flag を残さない。prefix 列
  //   [start_pc, pc) の生バイトを再走査して判定する (高々数 byte、E0-E3 は cold path)。
  private boolean hasAddr32Prefix( long start_pc, long pc ) {
    for( long q = start_pc; q < pc; q++ ) if( (mem.load8(q) & 0xFF) == 0x67 ) return true;
    return false;
  }
  // REPE/REPNE の CMPS・SCAS 共通ループ (issue #519/#525 の string op 一般化)。
  //   isCmps=true: CMPS ([RSI] vs [RDI])、false: SCAS (acc vs [RDI])。sz: 1/2/4/8。
  //   repe=true: ZF=0 (不一致) で停止、false (REPNE): ZF=1 (一致) で停止。
  //   フラグは sbb* (CMP 相当、AF/PF 含む全6フラグ)。RCX=0 開始ならフラグ不変 (SDM)。
  //   DF (issue #519) を見て ± 方向に進む。
  private void repCmpsScas( boolean isCmps, int sz, boolean repe ) {
    long st = (df!=0) ? -sz : sz;
    while( r64[R_RCX] != 0 ) {
      long a, b;
      if( isCmps ) {
        if( sz==1 )      { a=mem.load8 (r64[R_RSI])&0xFFL;        b=mem.load8 (r64[R_RDI])&0xFFL; }
        else if( sz==2 ) { a=mem.load16(r64[R_RSI])&0xFFFFL;      b=mem.load16(r64[R_RDI])&0xFFFFL; }
        else if( sz==4 ) { a=mem.load32(r64[R_RSI])&0xFFFFFFFFL;  b=mem.load32(r64[R_RDI])&0xFFFFFFFFL; }
        else             { a=mem.load64(r64[R_RSI]);              b=mem.load64(r64[R_RDI]); }
        r64[R_RSI]+=st;
      } else {
        if( sz==1 )      { a=r64[R_RAX]&0xFFL;       b=mem.load8 (r64[R_RDI])&0xFFL; }
        else if( sz==2 ) { a=r64[R_RAX]&0xFFFFL;     b=mem.load16(r64[R_RDI])&0xFFFFL; }
        else if( sz==4 ) { a=r64[R_RAX]&0xFFFFFFFFL; b=mem.load32(r64[R_RDI])&0xFFFFFFFFL; }
        else             { a=r64[R_RAX];             b=mem.load64(r64[R_RDI]); }
      }
      r64[R_RDI]+=st; r64[R_RCX]--;
      if( sz==1 ) sbb8(a,b,0); else if( sz==2 ) sbb16(a,b,0); else if( sz==4 ) sbb32(a,b,0); else sbb64(a,b,0);
      if( repe ? zf==0 : zf==1 ) break;
    }
  }
  private void setAF( long a, long b, long result ) {
    af = (int)((a ^ b ^ result) >> 4) & 1;
  }

  private void setFlags64Sub( long a, long b ) {
    long result = a - b;
    zf = (result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(a,b)<0?1:0;
    setPF(result); setAF(a,b,result);
  }

  private void setFlags64Add( long a, long b ) {
    long result = a + b;
    zf=(result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^~b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(result,a)<0?1:0;
    setPF(result); setAF(a,b,result);
  }

  private void setFlags32Sub( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long r = (a - b) & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^b)&(a^r))>>31)&1;
    cf=Long.compareUnsigned(a,b)<0?1:0;
    setPF(r); setAF(a,b,r);
  }

  private void setFlags32Add( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long result = a + b;
    long r = result & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^~b)&(a^r))>>31)&1;
    cf=result>0xFFFFFFFFL?1:0;
    setPF(r); setAF(a,b,r);
  }

  private void setFlags16Add( long a, long b ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long result = a + b;
    long r = result & 0xFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>15)&1;
    of=(int)(((a^~b)&(a^r))>>15)&1;
    cf=result>0xFFFFL?1:0;
    setPF(r); setAF(a,b,r);
  }

  private void setFlags16Sub( long a, long b ) {
    a &= 0xFFFFL; b &= 0xFFFFL;
    long r = (a - b) & 0xFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>15)&1;
    of=(int)(((a^b)&(a^r))>>15)&1;
    cf=Long.compareUnsigned(a,b)<0?1:0;
    setPF(r); setAF(a,b,r);
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
    setPF(sum); af = (int)((a ^ b ^ sum) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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
    setPF(res); af = (int)((a ^ b ^ res) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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
    setPF(r); af = (int)((a ^ b ^ r) >> 4) & 1;
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

  // issue #191 (mozc): SSE2 unsigned 飽和の packed byte/word 演算。byte は [0,255]、
  //   word は [0,65535] にクランプ。mozc_server の SIMD コードが PSUBUSB (66 0F D8)
  //   を使う。dst - src / dst + src を unsigned saturating で計算する。
  private static long psubusb( long a, long b ) {  // unsigned sat byte subtract
    long r=0; for(int i=0;i<8;i++){int s=i*8;int d=((int)(a>>>s)&0xFF)-((int)(b>>>s)&0xFF);if(d<0)d=0;r|=((long)d)<<s;} return r;
  }
  private static long paddusb( long a, long b ) {  // unsigned sat byte add
    long r=0; for(int i=0;i<8;i++){int s=i*8;int d=((int)(a>>>s)&0xFF)+((int)(b>>>s)&0xFF);if(d>255)d=255;r|=((long)d)<<s;} return r;
  }
  private static long psubusw( long a, long b ) {  // unsigned sat word subtract
    long r=0; for(int i=0;i<4;i++){int s=i*16;int d=((int)(a>>>s)&0xFFFF)-((int)(b>>>s)&0xFFFF);if(d<0)d=0;r|=((long)d)<<s;} return r;
  }
  private static long paddusw( long a, long b ) {  // unsigned sat word add
    long r=0; for(int i=0;i<4;i++){int s=i*16;int d=((int)(a>>>s)&0xFFFF)+((int)(b>>>s)&0xFFFF);if(d>0xFFFF)d=0xFFFF;r|=((long)d)<<s;} return r;
  }
  // node(V8) 等が使う SSE2 signed 飽和 packed byte/word 演算。byte は [-128,127]、word は
  //   [-32768,32767] にクランプ (66 0F E8=PSUBSB / E9=PSUBSW / EC=PADDSB / ED=PADDSW)。
  private static long psubsb( long a, long b ) {  // signed sat byte subtract (66 0F E8)
    long r=0; for(int i=0;i<8;i++){int s=i*8;int d=(byte)(a>>>s)-(byte)(b>>>s);if(d>127)d=127;if(d<-128)d=-128;r|=((long)(d&0xFF))<<s;} return r;
  }
  private static long psubsw( long a, long b ) {  // signed sat word subtract (66 0F E9)
    long r=0; for(int i=0;i<4;i++){int s=i*16;int d=(short)(a>>>s)-(short)(b>>>s);if(d>32767)d=32767;if(d<-32768)d=-32768;r|=((long)(d&0xFFFF))<<s;} return r;
  }
  private static long paddsb( long a, long b ) {  // signed sat byte add (66 0F EC)
    long r=0; for(int i=0;i<8;i++){int s=i*8;int d=(byte)(a>>>s)+(byte)(b>>>s);if(d>127)d=127;if(d<-128)d=-128;r|=((long)(d&0xFF))<<s;} return r;
  }
  private static long paddsw( long a, long b ) {  // signed sat word add (66 0F ED)
    long r=0; for(int i=0;i<4;i++){int s=i*16;int d=(short)(a>>>s)+(short)(b>>>s);if(d>32767)d=32767;if(d<-32768)d=-32768;r|=((long)(d&0xFFFF))<<s;} return r;
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

  // issue #48 (b) 第二段: MUL/IMUL/DIV r64 (Group 3 + 0F AF + 69 ib)。
  //   RSA modexp の BIGNUM 乗算/除算が hot。interpreter (exec_grp3_f7 /
  //   exec_imul_rm_imm) と同じ semantics を helper として export する。

  /** REX.W + F7 /4: MUL r/m64 — unsigned RDX:RAX = RAX * r/m64。CF/OF は RDX!=0。 */
  public void jitMulRAX_64( int srcReg ) {
    long a = r64[R_RAX], b = r64[srcReg];
    long hi = Math.multiplyHigh( a, b );
    // unsigned multiply の hi 補正 (Math.multiplyHigh は signed)
    if( a < 0 ) hi += b;
    if( b < 0 ) hi += a;
    r64[R_RDX] = hi;
    r64[R_RAX] = a * b;
    cf = of = (hi != 0) ? 1 : 0;
  }
  /** REX.W + F7 /5: IMUL r/m64 — signed RDX:RAX = RAX * r/m64。CF/OF は RDX != sign-ext。 */
  public void jitIMulRAX_64( int srcReg ) {
    long a = r64[R_RAX], b = r64[srcReg];
    r64[R_RDX] = Math.multiplyHigh( a, b );
    r64[R_RAX] = a * b;
    cf = of = (r64[R_RDX] != (r64[R_RAX] >> 63)) ? 1 : 0;
  }
  /** issue #537: JIT block 内の DIV が #DE 条件 (div0 / 商 overflow) を踏んだとき、block を
   *   その命令の guest pc で中断して interpreter に戻すための制御フロー例外。block 先頭〜DIV
   *   直前の副作用は commit 済みなので、trap pc から interpreter が DIV を再実行して SIGFPE を
   *   正しく配送する (jitStep の catch が受ける)。stack trace 不要 (writableStackTrace=false)。 */
  static final class JitDeTrap extends RuntimeException {
    final long pc;
    JitDeTrap( long pc ) { super( null, null, false, false ); this.pc = pc; }
  }
  /** REX.W + F7 /6: DIV r/m64 — unsigned (RDX:RAX) / r/m64 → RAX, 余 → RDX。
   *   128 / 64 は Java で BigInteger 経由 (interpreter と同じ)。
   *   issue #537: #DE 条件 (div0 / RDX >= 除数 = 商 overflow) は JitDeTrap で interpreter に
   *   委譲する (旧 div0 経路は println + exit_flag で SIGFPE を配送せず、しかも exit 0 に
   *   見えるため conformance の crashed 検出もすり抜けていた)。 */
  public void jitDivRAX_64( int srcReg, long pc ) {
    long val = r64[srcReg];
    if( val == 0 || Long.compareUnsigned( r64[R_RDX], val ) >= 0 )
      throw new JitDeTrap( pc );
    java.math.BigInteger MOD64 = java.math.BigInteger.ONE.shiftLeft(64);
    java.math.BigInteger lo = new java.math.BigInteger( Long.toUnsignedString( r64[R_RAX] ) );
    java.math.BigInteger hi = new java.math.BigInteger( Long.toUnsignedString( r64[R_RDX] ) );
    java.math.BigInteger d  = hi.shiftLeft(64).or(lo);
    java.math.BigInteger v  = new java.math.BigInteger( Long.toUnsignedString( val ) );
    java.math.BigInteger[] qr = d.divideAndRemainder( v );
    r64[R_RAX] = qr[0].mod( MOD64 ).longValue();
    r64[R_RDX] = qr[1].mod( MOD64 ).longValue();
  }
  /** REX.W + 0F AF: IMUL r64, r/m64 — dst = signed dst * src (low 64-bit のみ)。
   *   CF/OF: overflow if high 64-bit != sign-ext of low (= a * b の signed
   *   overflow があったか)。 */
  public void jitIMul64RR_dst( int dstReg, int srcReg ) {
    long a = r64[dstReg], b = r64[srcReg];
    long hi = Math.multiplyHigh( a, b );
    long lo = a * b;
    r64[dstReg] = lo;
    cf = of = (hi != (lo >> 63)) ? 1 : 0;
  }
  /** REX.W + 69 ib / 6B ib: IMUL r64, r/m64, imm — dst = signed src * imm。 */
  public void jitIMul64RI_dst( int dstReg, int srcReg, long imm ) {
    long a = r64[srcReg], b = imm;
    long hi = Math.multiplyHigh( a, b );
    long lo = a * b;
    r64[dstReg] = lo;
    cf = of = (hi != (lo >> 63)) ? 1 : 0;
  }

  // issue #48 (JIT BIGNUM 命令拡張): ADC / SBB r,r/m と imm8/imm32 (REX.W mod==3)。
  //   RSA modexp の multi-precision add/sub chain で頻出。
  //   既存 interpreter の adc64 / sbb64 と同じ semantics (CF 連鎖)。
  public void jitAdc64RR( int dstReg, int srcReg ) {
    r64[dstReg] = adc64( r64[dstReg], r64[srcReg], cf );
  }
  public void jitSbb64RR( int dstReg, int srcReg ) {
    r64[dstReg] = sbb64( r64[dstReg], r64[srcReg], cf );
  }
  public void jitAdc64RI( int dstReg, long imm ) {
    r64[dstReg] = adc64( r64[dstReg], imm, cf );
  }
  public void jitSbb64RI( int dstReg, long imm ) {
    r64[dstReg] = sbb64( r64[dstReg], imm, cf );
  }
  // adc64 / sbb64 は Cpu64 内 private なので JIT helper から呼ぶための
  //   package-private wrapper (Cpu64 自身のメソッドなので直接呼べる)。

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
      setPF(res);                       // issue #508: TEST も PF を設定する
    } else if( op66 ) {
      long res = (readRM16() & r64[mrm_reg]) & 0xFFFFL;
      zf = (res==0) ? 1 : 0;
      sf = (int)(res>>15) & 1;
      setPF(res);
    } else {
      long res = (readRM32() & r64[mrm_reg]) & 0xFFFFFFFFL;
      zf = (res==0) ? 1 : 0;
      sf = (int)(res>>31) & 1;
      setPF(res);
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
    of = 0; cf = 0; setPF(res);
  }
  private void setFlagsLogic16( long res ) {
    res &= 0xFFFFL;
    zf = (res==0) ? 1 : 0;
    sf = (int)(res>>15) & 1;
    of = 0; cf = 0; setPF(res);
  }
  private void setFlagsLogic32( long res ) {
    res &= 0xFFFFFFFFL;
    zf = (res==0) ? 1 : 0;
    sf = (int)(res>>31) & 1;
    of = 0; cf = 0; setPF(res);
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
    setPF(res);                         // issue #508: TEST r/m8 も PF
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
  // MOV r16/r32/r64, imm (opcode 0xB8-0xBF)
  private long exec_mov_r_imm( long pc, int b0, boolean rex_w, boolean rex_b, boolean op66 ) {
    int rd = (b0 & 7) | (rex_b ? 8 : 0);
    if( rex_w ) {
      r64[rd] = loadImm64( pc+1 );
      return pc + 9;
    } else if( op66 ) {
      // MOV r16, imm16: imm は 2 byte、上位 48bit は保持。0x66 prefix を見ずに
      //   imm32 を読むと RIP が 2 byte 進みすぎて次命令の途中に着地し制御フロー
      //   破損する (Bun 製 claude が踏んで segfault していた)。
      r64[rd] = (r64[rd] & ~0xFFFFL) | (loadImm16( pc+1 ) & 0xFFFFL);
      return pc + 3;
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
  // Grp1 r/m8, imm8 (opcode 0x80): ADD/OR/ADC/SBB/AND/SUB/XOR/CMP の 8-bit 版
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
      // case 2 (ADC) / case 3 (SBB) は CF (桁上げ/借り) を必ず含める。旧実装は
      //   この 2 つが欠落し default で no-op (CF 無視) になっており、`setcc; sbb
      //   $0` イディオム (memcmp / REPE CMPSB の 3-way 比較) が CF=1 で 0 を返し、
      //   V8 parser の __proto__ 名比較が 9 文字 (= "__proto__" 長) の名前を
      //   __proto__ と誤判定して "Duplicate __proto__" を誤発生させていた
      //   (issue #87)。adc8/sbb8 が全 flag を設定。
      case 2: writeRM8( adc8(dst, imm, cf) ); return next;  // ADC r/m8, imm8
      case 3: writeRM8( sbb8(dst, imm, cf) ); return next;  // SBB r/m8, imm8
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
    // RIP-relative の基準は imm を含む命令末尾。imm8 (1 byte) を足してから fixEA。
    //   (旧実装は next=imm 手前を渡しており、RIP-relative memory operand のとき
    //    EA が 1 byte ずれて誤った値を読んでいた。0x6B の RIP-rel memory 形が該当。)
    fixEA( next + 1, fs_prefix );
    long src = rex_w ? readRM64()
             : (op66 ? (long)(short)readRM16() : (long)(int)readRM32());
    long imm = (long)(byte)mem.load8(next); next++;
    long res = src * imm;
    if( rex_w )       r64[mrm_reg] = res;
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    else              r64[mrm_reg] = res & 0xFFFFFFFFL;
    // CF/OF = 結果が dest 幅に収まらないとき 1 (3d-2c-34: 旧実装は flags 未設定で stale)
    if( rex_w )     cf=of=(Math.multiplyHigh(src,imm)!=(res>>63))?1:0;
    else if( op66 ) cf=of=(res!=(short)res)?1:0;
    else            cf=of=(res!=(int)res)?1:0;
    return next;
  }
  // IMUL r, r/m, imm32/imm16 (opcode 0x69)
  private long exec_imul_rm_imm( long pc, boolean rex_w, boolean rex_r,
                                 boolean rex_b, boolean rex_x,
                                 boolean op66, boolean fs_prefix ) {
    long next = decodeModRM( pc+1, rex_r, rex_b, rex_x, false );
    // RIP-relative の基準は imm を含む命令末尾。imm (op66 なら 2、既定 4 byte) を
    //   足してから fixEA。旧実装は next=imm 手前を渡しており、RIP-relative memory
    //   operand のとき EA が imm サイズ分ずれて誤った値を読み、巨大な積になっていた
    //   (ddskk 入力時に __memcpy_chk が len=巨大値で buffer overflow 誤検出 → abort)。
    fixEA( next + (op66 ? 2 : 4), fs_prefix );
    long src = rex_w ? readRM64()
             : (op66 ? (long)(short)readRM16() : (long)(int)readRM32());
    long imm;
    if( op66 ) { imm = (long)(short)loadImm16(next); next += 2; }
    else       { imm = (long)(int)loadImm32u(next);  next += 4; }
    long res = src * imm;
    if( rex_w )       r64[mrm_reg] = res;
    else if( op66 )   r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (res & 0xFFFFL);
    else              r64[mrm_reg] = res & 0xFFFFFFFFL;
    // CF/OF = 結果が dest 幅に収まらないとき 1 (3d-2c-34: 旧実装は flags 未設定で stale)
    if( rex_w )     cf=of=(Math.multiplyHigh(src,imm)!=(res>>63))?1:0;
    else if( op66 ) cf=of=(res!=(short)res)?1:0;
    else            cf=of=(res!=(int)res)?1:0;
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
        // issue #499: count >= size (16-bit で count>16 等) の CF は符号ビット。
        if( count > 0 ) cf = (count-1 < size) ? (int)((val & mask) >> (count-1)) & 1
                                              : (int)((val & mask) >> (size-1)) & 1;
        break;
      case 0: // ROL
        if( count > 0 ) {
          int n = count % size;
          res = ((val << n) | ((val & mask) >>> (size-n))) & mask;
          cf = (int)res & 1;
          // issue #499: OF は 1-bit rotate のみ定義 (SDM)。ROL: MSB(res) ^ CF。
          if( count == 1 ) of = ( (int)(res >>> (size-1)) & 1 ) ^ ( cf & 1 );
        }
        break;
      case 1: // ROR
        if( count > 0 ) {
          int n = count % size;
          res = (((val & mask) >>> n) | (val << (size-n))) & mask;
          cf = (int)(res >> (size-1)) & 1;
          // issue #499: ROR の OF (1-bit) = 上位 2 bit の XOR。
          if( count == 1 ) of = ( (int)(res >>> (size-1)) & 1 ) ^ ( (int)(res >>> (size-2)) & 1 );
        }
        break;
      case 2: // RCL (rotate left through carry: (size+1)-bit [CF:operand] を左回転)
      case 3: // RCR (rotate right through carry)。step 3d-2c-37: Go の div-by-const magic 等で使用。
        {
          // count は (size+1) を法に: 64-bit は size+1=65>masked(≤63) で実質無変換、16-bit は %17。
          int n = count % (size + 1);
          long v = val & mask;
          int  c = cf & 1;
          for( int k = 0; k < n; k++ ) {
            if( mrm_reg == 2 ) {                       // RCL: MSB を CF へ、CF を bit0 へ
              int newc = (int)(v >>> (size-1)) & 1;
              v = ((v << 1) | c) & mask;
              c = newc;
            } else {                                   // RCR: bit0 を CF へ、CF を MSB へ
              int newc = (int)v & 1;
              v = ((v >>> 1) | ((long)c << (size-1))) & mask;
              c = newc;
            }
          }
          res = v;
          cf  = c;
          // OF は 1-bit rotate のみ定義 (Intel SDM)。RCL: MSB(res)^CF、RCR: 上位 2 bit の XOR。
          if( count == 1 ) {
            if( mrm_reg == 2 ) of = ( (int)(res >>> (size-1)) & 1 ) ^ ( cf & 1 );
            else               of = ( (int)(res >>> (size-1)) & 1 ) ^ ( (int)(res >>> (size-2)) & 1 );
          }
        }
        break;
      default:
        process.println("Cpu64: unsupported Grp2 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    // 結果を書き戻す (count==0 でも値は不変なので無害)。
    if( rex_w )     { writeRM64(res); }
    else if( op66 ) { res &= 0xFFFFL;     writeRM16(res); }
    else            { res &= 0xFFFFFFFFL; writeRM32(res); }
    // issue #499: フラグ更新は count(マスク後) != 0 のときだけ。count==0 は全フラグ不変。
    //   shift (SHL/SHR/SAR) は SF/ZF/PF を result から設定 (OF は count==1 のみ、AF は undefined)。
    //   rotate (ROL/ROR/RCL/RCR) は CF/OF のみ影響、SF/ZF/PF/AF は不変。
    if( count != 0 ) {
      boolean isRotate = ( mrm_reg <= 3 );   // 0..3 = ROL/ROR/RCL/RCR
      if( !isRotate ) {
        zf = (res==0)?1:0;
        sf = (int)(res >>> (size-1)) & 1;
        setPF(res);
        if( count == 1 ) {
          if( mrm_reg == 4 )      of = ((int)(res >>> (size-1)) & 1) ^ (cf & 1);  // SHL
          else if( mrm_reg == 5 ) of = (int)((val & mask) >>> (size-1)) & 1;      // SHR: 元 MSB
          else                    of = 0;                                          // SAR
        }
      }
    }
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
        // issue #567: lock inc + memory operand は VarHandle atomic RMW にする (fork した親子の
        //   共有メモリ跨ぎで atomic。setFlags* は加算前の値 v で計算)。16bit / register 直接は従来。
        if( curLockPrefix && mrm_mod != 3 && !op66 ) {
          if( rex_w ) { long v = mem.atomicAdd64( mrm_ea,  1L );              setFlags64Add(v,1); }
          else        { long v = mem.atomicAdd32( mrm_ea,  1 ) & 0xFFFFFFFFL; setFlags32Add(v,1); }
        }
        else if( rex_w )  { long v=readRM64(), r=v+1; setFlags64Add(v,1); writeRM64(r); }
        else if( op66 )   { long v=readRM16()&0xFFFFL, r=(v+1)&0xFFFFL; setFlags16Add(v,1); writeRM16(r); }
        else              { long v=readRM32()&0xFFFFFFFFL, r=(v+1)&0xFFFFFFFFL; setFlags32Add(v,1); writeRM32(r); }
        cf = saved_cf;
        break;
      }
      case 1: { // DEC r/m (CF 保存)
        int saved_cf = cf;
        if( curLockPrefix && mrm_mod != 3 && !op66 ) {   // issue #567: lock dec を atomic RMW に
          if( rex_w ) { long v = mem.atomicAdd64( mrm_ea, -1L );              setFlags64Sub(v,1); }
          else        { long v = mem.atomicAdd32( mrm_ea, -1 ) & 0xFFFFFFFFL; setFlags32Sub(v,1); }
        }
        else if( rex_w )  { long v=readRM64(), r=v-1; setFlags64Sub(v,1); writeRM64(r); }
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
      case 0: res = adc8(dst, src, 0);   break;                    // ADD (00/02) — issue #508: adc8 で全フラグ更新
      case 1: res = (dst | src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; setPF(res); break; // OR
      case 2: res = adc8(dst, src, cf); break;                     // ADC (adc8 内でフラグ更新)
      case 3: res = sbb8(dst, src, cf); break;                     // SBB
      case 4: res = (dst & src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; setPF(res); break; // AND
      case 5: res = (dst - src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1;
              of=(int)(((dst^src)&(dst^res))>>7)&1;
              cf=Long.compareUnsigned(dst,src)<0?1:0; setPF(res); setAF(dst,src,res); break; // SUB
      case 6: res = (dst ^ src) & 0xFF;
              zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; setPF(res); break; // XOR
      case 7: res = (dst - src) & 0xFF;                            // CMP — 書き戻さない
              zf=(res==0)?1:0; sf=(int)(res>>7)&1;
              of=(int)(((dst^src)&(dst^res))>>7)&1;
              cf=Long.compareUnsigned(dst,src)<0?1:0; setPF(res); setAF(dst,src,res);
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
      case 0x04: res = adc8(al, imm, 0);  break;  // ADD AL, imm8 — carry-out/overflow を正しく立てる (旧実装は cf=of=0 固定)
      case 0x14:  // ADC AL, imm8 — carry-in (CF) を必ず加算 (issue #185、#98/#87 と同根)。
                  //   旧実装は 0x04 ADD と同一処理で CF を無視 (0x1C SBB は sbb8 で
                  //   修正済だが ADC は取りこぼし)。node の文字列 comparator
                  //   (codePointCompare) が `cmp; adc $0xff,%al; or $1,%al` で 3-way 結果
                  //   (-1/0/+1) を作る idiom で CF=1 を落として符号反転 → std::sort の
                  //   comparator が strict weak ordering 違反 → partition ポインタが配列
                  //   境界外を 9026 要素暴走 → unmapped 命中で claude --version が SIGSEGV。
        res = adc8(al, imm, cf); break;
      case 0x0C: res=(al|imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      case 0x24: res=(al&imm)&0xFF; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; break;
      case 0x1C:  // SBB AL, imm8 — borrow (CF) を必ず減算 (issue #98、#87 と同根)。
                  //   旧実装は 0x2C SUB と同一で CF を無視しており、node の
                  //   `setcc; sbb $0,%al` 3-way 比較 idiom (BuiltinLoader の
                  //   module-id prefix 分類) が CF=1 でも 0 を返し、
                  //   internal/main/eval_string を per_context と誤判定して
                  //   require 無し wrapper で compile → "require is not defined"。
        res = sbb8(al, imm, cf); break;
      case 0x2C:  // SUB AL, imm8
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
    if( b0==0x05 ) {  // ADD rAX, imm
      res = (a+imm) & mask;
      if( rex_w )     setFlags64Add(a,imm);
      else if( op66 ) { a&=0xFFFFL; imm&=0xFFFFL; long r2=a+imm; cf=((r2>>16)&1)==1?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm^0xFFFFL)&(a^r2))>>15)&1; }
      else            setFlags32Add(a,imm);
    } else if( b0==0x15 ) {  // ADC rAX, imm — carry-in を含める (issue #98、#87 と同根)
      res = rex_w ? adc64(a,imm,cf) : op66 ? adc16(a,imm,cf) : adc32(a,imm,cf);
    } else if( b0==0x0D ) { res=(a|imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
    else if( b0==0x25 )   { res=(a&imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
    else if( b0==0x2D ) {  // SUB rAX, imm
      res = (a-imm) & mask;
      if( rex_w )     setFlags64Sub(a,imm);
      else if( op66 ) { a&=0xFFFFL; imm&=0xFFFFL; long r2=(a-imm)&0xFFFFFFFFL; cf=Long.compareUnsigned(a,imm)<0?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm)&(a^r2))>>15)&1; }
      else            setFlags32Sub(a,imm);
    } else if( b0==0x1D ) {  // SBB rAX, imm — borrow-in を含める (issue #98、#87 と同根)
      res = rex_w ? sbb64(a,imm,cf) : op66 ? sbb16(a,imm,cf) : sbb32(a,imm,cf);
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
      zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0; setPF(res); return next;   // issue #508
    }
    fixEA(next, fs_prefix);
    switch( mrm_reg ) {
      case 2: writeRM8((~readRM8()) & 0xFF); break;          // NOT
      case 3: val=readRM8(); res=(-val)&0xFF; writeRM8(res); // NEG
              cf=(val!=0)?1:0; zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=(val==0x80)?1:0;
              setPF(res); setAF(0, val, res); break;   // issue #508
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
        if( src == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );  // issue #503
        long ax = r64[R_RAX] & 0xFFFFL;
        long q = ax / src, r = ax % src;
        // issue #537: 商が 8bit に収まらない場合も #DE (SDM)。旧実装は wrap した誤値で silent 続行。
        if( q > 0xFFL ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
        r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | ((r << 8) & 0xFF00L) | (q & 0xFFL);
        break; }
      case 7: { // IDIV r/m8
        long src = (long)(byte)(readRM8() & 0xFFL);
        if( src == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );  // issue #503
        long ax = (long)(short)(r64[R_RAX] & 0xFFFFL);
        long q = ax / src, r = ax % src;
        // issue #537: 商が [-128,127] を外れたら #DE (INT8_MIN÷-1 含む)。
        if( q < -0x80L || q > 0x7FL ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
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
      // issue #523: MUL/IMUL/DIV/IDIV に 16bit (0x66 prefix) 形を追加。NOT/NEG (case 2/3) は
      //   3分岐が揃っていたが乗除算は rex_w ? 64 : 32 の2分岐しか無く、divw 等が 32bit として
      //   実行され EAX/EDX の上位16bit まで演算に巻き込まれていた。16bit 形の DX/AX 書込は
      //   上位48bit 保存 (x86 の 16bit 書込規則)。
      case 4: // MUL
        if( rex_w ) { long a=r64[R_RAX], b=readRM64(); long hi=Math.multiplyHigh(a,b); if(a<0)hi+=b; if(b<0)hi+=a; r64[R_RDX]=hi; r64[R_RAX]=a*b; cf=of=(hi!=0)?1:0; }
        else if( op66 ) {
          long p=(r64[R_RAX]&0xFFFFL)*(readRM16()&0xFFFFL);   // DX:AX = AX * r/m16
          r64[R_RDX]=(r64[R_RDX]&~0xFFFFL)|((p>>16)&0xFFFFL);
          r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(p&0xFFFFL);
          cf=of=((p>>16)!=0)?1:0;
        }
        else { long p=(r64[R_RAX]&0xFFFFFFFFL)*(readRM32()&0xFFFFFFFFL); r64[R_RDX]=(p>>32)&0xFFFFFFFFL; r64[R_RAX]=p&0xFFFFFFFFL; cf=of=(r64[R_RDX]!=0)?1:0; }
        break;
      case 5: // IMUL
        if( rex_w ) { long a=r64[R_RAX], b=readRM64(); r64[R_RDX]=Math.multiplyHigh(a,b); r64[R_RAX]=a*b; cf=of=(r64[R_RDX]!=(r64[R_RAX]>>63))?1:0; }
        else if( op66 ) {
          int p=(short)r64[R_RAX]*(short)readRM16();          // DX:AX = AX * r/m16 (signed)
          r64[R_RDX]=(r64[R_RDX]&~0xFFFFL)|((p>>16)&0xFFFFL);
          r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(p&0xFFFFL);
          cf=of=(p!=(short)p)?1:0;
        }
        else { long p=(long)(int)r64[R_RAX]*(long)(int)readRM32(); r64[R_RDX]=(p>>32)&0xFFFFFFFFL; r64[R_RAX]=p&0xFFFFFFFFL; cf=of=(p!=(int)p)?1:0; }  // EDX != sign-ext(EAX) で overflow (3d-2c-34、旧 cf=of=0 固定)
        break;
      case 6: // DIV
        // issue #537: 商が dest 幅に収まらない場合も #DE (SDM)。unsigned DIV は
        //   「被除数の上位半分 >= 除数」が overflow の同値判定 (旧実装は wrap して silent 続行)。
        if( rex_w ) {
          val = readRM64();
          if( val == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );  // issue #503
          if( Long.compareUnsigned( r64[R_RDX], val ) >= 0 )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          java.math.BigInteger MOD64 = java.math.BigInteger.ONE.shiftLeft(64);
          java.math.BigInteger lo = new java.math.BigInteger(Long.toUnsignedString(r64[R_RAX]));
          java.math.BigInteger hi = new java.math.BigInteger(Long.toUnsignedString(r64[R_RDX]));
          java.math.BigInteger d  = hi.shiftLeft(64).or(lo);
          java.math.BigInteger v  = new java.math.BigInteger(Long.toUnsignedString(val));
          java.math.BigInteger[] qr = d.divideAndRemainder(v);
          r64[R_RAX] = qr[0].mod(MOD64).longValue();
          r64[R_RDX] = qr[1].mod(MOD64).longValue();
        } else if( op66 ) {
          long v = readRM16()&0xFFFFL;                        // DX:AX / r/m16 → AX=商, DX=剰余
          if( v == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
          if( (r64[R_RDX]&0xFFFFL) >= v )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          long d = ((r64[R_RDX]&0xFFFFL)<<16)|(r64[R_RAX]&0xFFFFL);
          r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|((d/v)&0xFFFFL);
          r64[R_RDX]=(r64[R_RDX]&~0xFFFFL)|((d%v)&0xFFFFL);
        } else {
          long v = readRM32()&0xFFFFFFFFL;
          if( v == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
          if( (r64[R_RDX]&0xFFFFFFFFL) >= v )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          long d=((r64[R_RDX]&0xFFFFFFFFL)<<32)|(r64[R_RAX]&0xFFFFFFFFL);
          r64[R_RAX]=Long.divideUnsigned(d,v)&0xFFFFFFFFL;
          r64[R_RDX]=Long.remainderUnsigned(d,v)&0xFFFFFFFFL;
        }
        break;
      case 7: // IDIV
        // issue #537: 商が dest 幅の符号付き範囲を外れたら #DE (INT_MIN÷-1 含む、SDM)。
        //   旧実装は wrap した誤値で silent 続行していた。
        if( rex_w ) {
          val = readRM64();
          if( val == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );  // issue #503
          java.math.BigInteger MOD64 = java.math.BigInteger.ONE.shiftLeft(64);
          java.math.BigInteger lo = new java.math.BigInteger(Long.toUnsignedString(r64[R_RAX]));
          java.math.BigInteger hi = java.math.BigInteger.valueOf(r64[R_RDX]);
          java.math.BigInteger d  = hi.shiftLeft(64).or(lo);
          java.math.BigInteger v  = java.math.BigInteger.valueOf(val);
          java.math.BigInteger[] qr = d.divideAndRemainder(v);
          if( qr[0].compareTo( java.math.BigInteger.valueOf( Long.MIN_VALUE ) ) < 0
              || qr[0].compareTo( java.math.BigInteger.valueOf( Long.MAX_VALUE ) ) > 0 )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          r64[R_RAX] = qr[0].mod(MOD64).longValue();
          r64[R_RDX] = qr[1].mod(MOD64).longValue();
        } else if( op66 ) {
          long v = (short)readRM16();                         // DX:AX (signed 32bit) / r/m16
          if( v == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
          long d = (int)(((r64[R_RDX]&0xFFFFL)<<16)|(r64[R_RAX]&0xFFFFL));
          long q = d / v;
          if( q < -0x8000L || q > 0x7FFFL )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(q&0xFFFFL);
          r64[R_RDX]=(r64[R_RDX]&~0xFFFFL)|((d%v)&0xFFFFL);
        } else {
          // issue #524: 被除数は EDX:EAX の 64bit (旧実装は EDX を無視し EAX の符号拡張のみを
          //   使っており、cltd イディオム以外 (EDX 独立構成) で商が誤っていた)。
          long v = (long)(int)readRM32();
          if( v == 0 ) return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );
          long d=(r64[R_RDX]<<32)|(r64[R_RAX]&0xFFFFFFFFL);
          long q = d / v;
          if( q < -0x80000000L || q > 0x7FFFFFFFL )
            return deliverSyncSignal( Signal.SIGFPE, /*FPE_INTDIV*/1, pc );               // issue #537
          r64[R_RAX]=q&0xFFFFFFFFL;
          r64[R_RDX]=(d%v)&0xFFFFFFFFL;
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
                zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x7F)?1:0;
                setPF(r); setAF(val, 1, r); break; }   // issue #508 (CF 不変)
      case 1: { long r=(val-1)&0xFF; writeRM8(r);
                zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x80)?1:0;
                setPF(r); setAF(val, 1, r); break; }    // issue #508
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
    long val = readRM8() & 0xFFL, res = val;
    final int size = 8; final long mask = 0xFFL;
    switch( mrm_reg ) {
      case 4: // SHL
        res = (val << count) & mask;
        if( count > 0 && count <= size ) cf = (int)(val >> (size-count)) & 1;
        break;
      case 5: // SHR
        res = (val & mask) >>> count;
        if( count > 0 && count <= size ) cf = (int)(val >> (count-1)) & 1;
        break;
      case 7: // SAR
        res = (((long)(byte)val) >> count) & mask;
        if( count > 0 ) cf = (count <= size) ? (int)(val >> (count-1)) & 1 : ((int)(val>>7)&1);
        break;
      case 0: // ROL (issue #500: 8-bit rotate を実装)
        if( count > 0 ) {
          int n = count % size;
          res = ((val << n) | ((val & mask) >>> (size-n))) & mask;
          cf = (int)res & 1;
          if( count == 1 ) of = ((int)(res>>>(size-1))&1) ^ (cf&1);
        }
        break;
      case 1: // ROR
        if( count > 0 ) {
          int n = count % size;
          res = (((val & mask) >>> n) | (val << (size-n))) & mask;
          cf = (int)(res>>(size-1)) & 1;
          if( count == 1 ) of = ((int)(res>>>(size-1))&1) ^ ((int)(res>>>(size-2))&1);
        }
        break;
      case 2: case 3: // RCL / RCR (8-bit は count % 9)
        {
          int n = count % (size + 1);
          long v = val & mask; int c = cf & 1;
          for( int k = 0; k < n; k++ ) {
            if( mrm_reg == 2 ) { int nc=(int)(v>>>(size-1))&1; v=((v<<1)|c)&mask; c=nc; }
            else               { int nc=(int)v&1; v=((v>>>1)|((long)c<<(size-1)))&mask; c=nc; }
          }
          res = v; cf = c;
          if( count == 1 ) {
            if( mrm_reg == 2 ) of = ((int)(res>>>(size-1))&1) ^ (cf&1);
            else               of = ((int)(res>>>(size-1))&1) ^ ((int)(res>>>(size-2))&1);
          }
        }
        break;
      default:
        process.println("Cpu64: unsupported Grp2b /"+mrm_reg+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag();
    }
    writeRM8(res);
    // issue #499: フラグは count(マスク後) != 0 のみ。shift は SF/ZF/PF を、
    //   rotate は CF/OF のみ (SF/ZF/PF/AF 不変)。
    if( count != 0 ) {
      boolean isRotate = ( mrm_reg <= 3 );
      if( !isRotate ) {
        zf = (res==0)?1:0; sf = (int)(res>>>(size-1))&1; setPF(res);
        if( count == 1 ) {
          if( mrm_reg == 4 )      of = ((int)(res>>>(size-1))&1) ^ (cf&1);   // SHL
          else if( mrm_reg == 5 ) of = (int)((val & mask) >>> (size-1)) & 1; // SHR
          else                    of = 0;                                     // SAR
        }
      }
    }
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
    int rm  = mb & 7;
    if( mod == 3 ) {
      // ---- register / stack 形式 (issue #78: x87 算術を実装) ----
      switch( b0 ) {
        case 0xD9:
          if( mb>=0xC0 && mb<=0xC7 )      { double t=fpuSt(rm); fpuPush(t); }       // FLD st(i)
          else if( mb>=0xC8 && mb<=0xCF ) { double t=fpuSt(0); fpuSetSt(0,fpuSt(rm)); fpuSetSt(rm,t); } // FXCH
          else if( mb==0xE0 )             fpuSetSt(0, -fpuSt(0));                    // FCHS
          else if( mb==0xE1 )             fpuSetSt(0, Math.abs(fpuSt(0)));           // FABS
          else if( mb==0xE8 )             fpuPush(1.0);                              // FLD1
          else if( mb==0xE9 )             fpuPush(Math.log(10)/Math.log(2));         // FLDL2T
          else if( mb==0xEA )             fpuPush(1.0/Math.log(2));                  // FLDL2E (approx)
          else if( mb==0xEB )             fpuPush(Math.PI);                          // FLDPI
          else if( mb==0xEC )             fpuPush(Math.log10(2));                    // FLDLG2
          else if( mb==0xED )             fpuPush(Math.log(2));                      // FLDLN2
          else if( mb==0xEE )             fpuPush(0.0);                              // FLDZ
          else if( mb==0xFA )             fpuSetSt(0, Math.sqrt(fpuSt(0)));          // FSQRT
          else if( mb==0xE4 )             { /* FTST */ fcomiFlags(fpuSt(0),0.0); }
          else if( mb==0xF8 || mb==0xF5 ) {
            // FPREM (F8、truncating 剰余 = C fmod) / FPREM1 (F5、round-to-nearest = IEEE remainder)。
            //   issue #221 step 3d-2c-34: V8 の Float64Mod は「fprem; fnstsw ax; C2 が立つ間ループ」
            //   で実装される。旧実装は silent no-op + C2=0 だったため、ループが即終了して dividend
            //   がそのまま剰余として返り、node の `90000000000 % 1000003` が dividend を返す silent
            //   wrong answer になっていた (smi を超える数値の % は全部この経路)。
            //   実 fprem は指数差 ≥64 で部分剰余 (C2=1) を返すが、Java の double % は exact な
            //   完全剰余を一発で出せるので、常に完全剰余 + C2=0 を返す (consumer は C2 でループ
            //   するだけなので as-if 等価)。quotient 下位 3 bit は SDM 通り C0←Q2/C3←Q1/C1←Q0。
            double d0 = fpuSt(0), d1 = fpuSt(1);
            double r = (mb==0xF8) ? (d0 % d1) : Math.IEEEremainder(d0, d1);
            fpuSetSt(0, r);
            long q = 0;
            if( d1 != 0 && !Double.isNaN(d0) && !Double.isNaN(d1) && !Double.isInfinite(d0) ) {
              double ad = Math.abs(d0 / d1);
              double qd = (mb==0xF8) ? Math.floor(ad) : Math.rint(ad);
              if( qd < 9.007199254740992E15 ) q = ((long)qd) & 7;   // 2^53 未満なら正確
            }
            fpu_sw &= ~0x4700;                                       // C3/C2/C1/C0 clear (C2=0 = 完了)
            fpu_sw |= (int)( ((q&1)<<9) | (((q>>1)&1)<<14) | (((q>>2)&1)<<8) );
          }
          // D0 (FNOP) / E0 系の未対応は no-op
          return pc+2;
        case 0xD8: {  // FADD/.../FDIV st(0), st(i)
          int op=(mb>>3)&7; fpuSetSt(0, x87arith(op, fpuSt(0), fpuSt(rm)));
          if(op==3) fpuPop();  // FCOMP は pop
          return pc+2;
        }
        case 0xDC: {  // FADD/.../FDIVR st(i), st(0)  (dest = st(i)、SUB/DIV は方向反転)
          int op=(mb>>3)&7;
          int rop = (op==4)?5 : (op==5)?4 : (op==6)?7 : (op==7)?6 : op;  // DC は sub/div の R が逆
          fpuSetSt(rm, x87arith(rop, fpuSt(rm), fpuSt(0)));
          return pc+2;
        }
        case 0xDE: {  // FADDP/.../FDIVP st(i), st(0); pop
          if( mb==0xD9 ) { fcomiFlags(fpuSt(0),fpuSt(1)); fpuPop(); fpuPop(); return pc+2; } // FCOMPP
          int op=(mb>>3)&7;
          int rop = (op==4)?5 : (op==5)?4 : (op==6)?7 : (op==7)?6 : op;
          fpuSetSt(rm, x87arith(rop, fpuSt(rm), fpuSt(0)));
          fpuPop();
          return pc+2;
        }
        case 0xDB:
          if( mb==0xE3 ) { fpu_cw = 0x037F; fpu_sw = 0; fpu_tag = 0xFFFF; fpu_top = 0; } // FNINIT
          else if( mb>=0xE8 && mb<=0xEF ) fcomiFlags(fpuSt(0), fpuSt(rm));  // FUCOMI
          else if( mb>=0xF0 && mb<=0xF7 ) fcomiFlags(fpuSt(0), fpuSt(rm));  // FCOMI
          return pc+2;
        case 0xDF:
          if( mb==0xE0 )                  r64[R_RAX] = (r64[R_RAX]&~0xFFFFL)|(fpu_sw&0xFFFF); // FNSTSW ax
          else if( mb>=0xE8 && mb<=0xEF ){ fcomiFlags(fpuSt(0),fpuSt(rm)); fpuPop(); }  // FUCOMIP
          else if( mb>=0xF0 && mb<=0xF7 ){ fcomiFlags(fpuSt(0),fpuSt(rm)); fpuPop(); }  // FCOMIP
          return pc+2;
        case 0xDD:
          if( mb>=0xC0 && mb<=0xC7 )      { /* FFREE */ }
          else if( mb>=0xD0 && mb<=0xD7 ) fpuSetSt(rm, fpuSt(0));                  // FST st(i)
          else if( mb>=0xD8 && mb<=0xDF ) { fpuSetSt(rm, fpuSt(0)); fpuPop(); }    // FSTP st(i)
          else if( mb>=0xE0 && mb<=0xE7 ) fcomiFlags(fpuSt(0), fpuSt(rm));         // FUCOM (簡易: EFLAGS)
          else if( mb>=0xE8 && mb<=0xEF ) { fcomiFlags(fpuSt(0), fpuSt(rm)); fpuPop(); } // FUCOMP
          return pc+2;
        case 0xDA:  // FCMOVcc 等 — 未対応 no-op
        default:
          return pc+2;
      }
    }
    long next = decodeModRM(pc+1, rex_r, rex_b, rex_x, false);
    fixEA(next, fs_prefix);
    // ---- メモリオペランド形式 ----
    switch( b0 ) {
      case 0xD9:
        if( reg==0 )      fpuPush( (double)Float.intBitsToFloat(mem.load32(mrm_ea)) );          // FLD m32
        else if( reg==2 ) mem.store32(mrm_ea, Float.floatToRawIntBits((float)fpuSt(0)));        // FST m32
        else if( reg==3 ) mem.store32(mrm_ea, Float.floatToRawIntBits((float)fpuPop()));        // FSTP m32
        else if( reg==5 ) fpu_cw = mem.load16(mrm_ea) & 0xFFFF;                                 // FLDCW
        else if( reg==7 ) mem.store16(mrm_ea, (short)(fpu_cw & 0xFFFF));                         // FNSTCW
        break;
      case 0xDD:
        if( reg==0 )      fpuPush( Double.longBitsToDouble(mem.load64(mrm_ea)) );                // FLD m64
        else if( reg==2 ) mem.store64(mrm_ea, Double.doubleToRawLongBits(fpuSt(0)));             // FST m64
        else if( reg==3 ) mem.store64(mrm_ea, Double.doubleToRawLongBits(fpuPop()));             // FSTP m64
        else if( reg==7 ) mem.store16(mrm_ea, (short)(fpu_sw & 0xFFFF));                         // FNSTSW
        break;
      case 0xDB:
        if( reg==0 )      fpuPush( (double)mem.load32(mrm_ea) );                                 // FILD m32
        else if( reg==2 ) mem.store32(mrm_ea, (int)fistRound(fpuSt(0)));                         // FIST m32
        else if( reg==3 ) mem.store32(mrm_ea, (int)fistRound(fpuPop()));                         // FISTP m32
        break;
      case 0xDF:
        if( reg==0 )      fpuPush( (double)(short)mem.load16(mrm_ea) );                          // FILD m16
        else if( reg==3 ) mem.store16(mrm_ea, (short)fistRound(fpuPop()));                       // FISTP m16
        else if( reg==5 ) fpuPush( (double)mem.load64(mrm_ea) );                                 // FILD m64 (fildll)
        else if( reg==7 ) mem.store64(mrm_ea, fistRound(fpuPop()));                              // FISTP m64 (fistpll)
        break;
      case 0xD8: { double b=(double)Float.intBitsToFloat(mem.load32(mrm_ea));                    // arith m32real
        fpuSetSt(0, x87arith(reg, fpuSt(0), b)); if(reg==3) fpuPop(); break; }
      case 0xDC: { double b=Double.longBitsToDouble(mem.load64(mrm_ea));                          // arith m64real
        fpuSetSt(0, x87arith(reg, fpuSt(0), b)); if(reg==3) fpuPop(); break; }
      case 0xDA: { double b=(double)mem.load32(mrm_ea);                                           // arith m32int
        fpuSetSt(0, x87arith(reg, fpuSt(0), b)); if(reg==3) fpuPop(); break; }
      case 0xDE: { double b=(double)(short)mem.load16(mrm_ea);                                    // arith m16int
        fpuSetSt(0, x87arith(reg, fpuSt(0), b)); if(reg==3) fpuPop(); break; }
    }
    return next;
  }

  // SSE の CMPPS/CMPPD/CMPSS/CMPSD (0F C2 系) 共通の比較 predicate。imm8 の
  // 下位 3bit で 8 種。一致なら true (呼び出し側で全 1 マスク化)。float も
  // double に widen して呼べる (== / < / <= の順序関係と NaN 性は厳密に保たれる)。
  private static boolean sseCmpMatch(double a, double b, int pred){
    boolean unord = Double.isNaN(a) || Double.isNaN(b);
    switch(pred & 7){
      case 0: return !unord && a == b;     // EQ
      case 1: return !unord && a <  b;     // LT
      case 2: return !unord && a <= b;     // LE
      case 3: return unord;                // UNORD
      case 4: return unord || a != b;      // NEQ
      case 5: return unord || !(a <  b);   // NLT
      case 6: return unord || !(a <= b);   // NLE
      default: return !unord;              // ORD
    }
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
        // issue #597/#628 と同型: imm8 を持つ F2 0F 命令 (PSHUFLW=0x70 / CMPSD=0xC2) は
        //   RIP-relative memory operand の EA が imm8 を含む命令末尾基準。共通の fixEA(sn) は
        //   imm 手前を渡すため RIP-rel 時に EA が 1 byte ずれる。imm を持つ opcode のみ +1 する
        //   (66 経路の imm_after 検出と同流儀。imm 無し命令は従来どおり)。
        long sn = decodeModRM(pc+2,rex_r,rex_b,rex_x,false);
        fixEA(sn + ((b1==0x70 || b1==0xC2) ? 1 : 0), fs_prefix);
        int xd=mrm_reg, xs=mrm_rm;
        // F2 0F 12: MOVDDUP xmm1, xmm2/m64 (SSE3) — src 低 64bit を両 qword に複製
        if( b1==0x12 ) {
          long s = (mrm_mod==3) ? xmm_lo[xs] : mem.load64(mrm_ea);
          xmm_lo[xd] = s; xmm_hi[xd] = s;
          return sn;
        }
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
        if(evalCond(b1&0xF)) {
          if(rex_w)      r64[mrm_reg]=readRM64();
          // r16 形 (66 prefix): 16bit 幅で読み、上位 48bit 保存 (旧実装は r32 として実行し
          //   上位まで潰していた。issue #522 の同一 dispatch 行のついで修正)。
          else if(op66)  r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|(readRM16()&0xFFFFL);
          else           r64[mrm_reg]=readRM32();
        }
        // issue #522: r32 形は条件不成立でも dest の上位 32bit をゼロ化する (SDM 3.4.1.1:
        //   32bit 書込は値が変わらなくても常に zero-extend)。r16 (66 prefix) は上位保存なので対象外。
        else if( !rex_w && !op66 ) r64[mrm_reg] &= 0xFFFFFFFFL;
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
        long dst=readRM8()&0xFFL, al=r64[R_RAX]&0xFFL;
        // issue #526: CMP AL,r/m8 相当の全6フラグを sbb8 で立てる (旧実装は ZF/SF のみで
        //   CF/OF/AF/PF 未計算。16/32/64bit 形 (0F B1) は共通ヘルパ経由で元々正しい)。
        sbb8(al, dst, 0);
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
          // issue #526: adc8 で AF/PF 含む全6フラグを立てる (旧実装は ZF/SF/OF/CF のみ)。
          long sum=adc8(dst, src, 0);
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
            // issue #526: setFlags16Add で AF/PF 含む全6フラグを立てる (旧実装は ZF/SF/OF/CF のみ。
            //   32/64bit 形は共通ヘルパ経由で元々正しい)。
            setFlags16Add(dst, src);
            long sum=(dst+src)&0xFFFFL;
            writeRM16((short)sum); r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|dst;
          } else {
            long dst=readRM32()&0xFFFFFFFFL, src=r64[mrm_reg]&0xFFFFFFFFL;
            setFlags32Add(dst,src);
            writeRM32((dst+src)&0xFFFFFFFFL); r64[mrm_reg]=dst;
          }
        }
        return next;
      }
      if( b1==0xAF ) { // IMUL r, r/m (signed 2-operand)
        // issue #221 step 3d-2c-34: 旧実装は of=0;cf=0 ハードコード + 32-bit 形が符号無視。
        //   IMUL の CF/OF は「結果が dest 幅に収まらないとき 1」(Intel SDM)。V8 は smi 乗算の
        //   overflow を `imul r32; jo deopt` で検出するので、OF が立たないと int32 が silent
        //   wrap して node の `46341*46341` が負値になる (deopt 不発の silent wrong answer)。
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        if( rex_w ) {
          long a=r64[mrm_reg], b=readRM64();
          long hi=Math.multiplyHigh(a,b), lo=a*b;
          r64[mrm_reg]=lo; cf=of=(hi!=(lo>>63))?1:0;
        } else if( op66 ) {
          int res=(short)r64[mrm_reg]*(short)readRM16();
          r64[mrm_reg]=(r64[mrm_reg]&~0xFFFFL)|(res&0xFFFFL);
          cf=of=(res!=(short)res)?1:0;
        } else {
          long res=(long)(int)r64[mrm_reg]*(long)(int)readRM32();
          r64[mrm_reg]=res&0xFFFFFFFFL;
          cf=of=(res!=(int)res)?1:0;
        }
        return next;
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
        // issue #509: bit offset は operand size で mod する (64→63 / 32→31 / 16→15)。
        int bit=(mem.load8(next)&0xFF)&(rex_w?63:(op66?15:31)); next++;
        fixEA(next,fs_prefix);
        long dst=rex_w?readRM64():(op66?readRM16()&0xFFFFL:readRM32()&0xFFFFFFFFL);
        cf=(int)(dst>>bit)&1;
        // /5=BTS /6=BTR /7=BTC
        long r = dst;
        if(mrm_reg==5)      r=dst|(1L<<bit);
        else if(mrm_reg==6) r=dst&~(1L<<bit);
        else if(mrm_reg==7) r=dst^(1L<<bit);
        if(mrm_reg!=4){ if(rex_w)writeRM64(r); else if(op66)writeRM16(r&0xFFFFL); else writeRM32(r&0xFFFFFFFFL); }
        return next;
      }
      if( b1==0xA3 || b1==0xAB || b1==0xB3 || b1==0xBB ) { // BT/BTS/BTR/BTC r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        // issue #509: register bit operand は operand size で mod する。
        int bit=(int)(r64[mrm_reg]&(rex_w?63:(op66?15:31)));
        long dst=rex_w?readRM64():(op66?readRM16()&0xFFFFL:readRM32()&0xFFFFFFFFL);
        cf=(int)(dst>>bit)&1;
        long r = dst;
        if( b1==0xAB )      r=dst|(1L<<bit);    // BTS
        else if( b1==0xB3 ) r=dst&~(1L<<bit);   // BTR
        else if( b1==0xBB ) r=dst^(1L<<bit);    // BTC
        if( b1!=0xA3 ){ if(rex_w)writeRM64(r); else if(op66)writeRM16(r&0xFFFFL); else writeRM32(r&0xFFFFFFFFL); }
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
          setPF(r2);                        // issue #502: SHRD も PF
          if( n == 1 )                      // OF (1-bit) = 符号変化
            of = ((int)(r2 >>> (size-1)) & 1) ^ ((int)(dst >>> (size-1)) & 1);
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
          long r2 = rex_w ? res : (res & mask);
          zf = (r2 == 0) ? 1 : 0;
          sf = (int)(r2 >>> (size-1)) & 1;
          cf = (int)((dst >>> (size - n)) & 1);
          setPF(r2);                        // issue #501: SHLD も PF
          if( n == 1 )                      // OF (1-bit) = 符号変化
            of = ((int)(r2 >>> (size-1)) & 1) ^ ((int)(dst >>> (size-1)) & 1);
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
          // ECX: SSE3(0) PCLMUL(1) SSSE3(9) CX16(13) SSE4.1(19) SSE4.2(20) POPCNT(23)
          //   AES-NI(25) を立てる。SSSE3/SSE4.1/SSE4.2(PCMPESTR/ISTR)/POPCNT は
          //   実装済 (sse_audit64 で host 一致を確認)。これにより simdutf/glibc が
          //   AVX 抜きで SSE4.2 kernel を選び、スカラーフォールバック (claude の
          //   UTF-8 デコード hang) を回避する。AVX(28) は emu 非対応で False。
          //   issue #535: CX16(13) を追加 — CMPXCHG16B は 0F C7 /1 (REX.W) で実装済みなのに
          //   未申告で、DWCAS を使う lock-free 構造 (glibc __atomic_*_16 等) が fallback に落ちていた。
          r64[R_RCX] = 0x02982203L;
          // issue #98 調査用: ECX を env で上書きして SSE4.x 等を選択的に無効化
          //   し、node --jitless の require バグが特定機能由来か二分探索する。
          String ecxOv = System.getenv("EMULIN_CPUID_ECX");
          if( ecxOv != null ) {
            try { r64[R_RCX] = Long.parseLong( ecxOv.replace("0x","").replace("0X",""), 16 ); }
            catch( NumberFormatException e ) {}
          }
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
          if( b2==0x17 ) {
            // PTEST xmm1, xmm2/m128 (SSE4.1): ZF=((dst & src)==0)、
            //   CF=((dst & ~src)==0)。OF/SF/PF=0。flag のみ更新。
            long dl = xmm_lo[xd], dh = xmm_hi[xd];
            zf = ( ((dl & sl) == 0) && ((dh & sh) == 0) ) ? 1 : 0;
            cf = ( ((dl & ~sl) == 0) && ((dh & ~sh) == 0) ) ? 1 : 0;
            of = 0; sf = 0; pf = 0;
            return n3;
          }
          if( b2==0x41 ) { // PHMINPOSUW (SSE4.1): 8 word の最小 unsigned 値 + index
            int minVal=0x10000, minIdx=0;
            for(int i=0;i<8;i++){
              int w=(int)((((i<4)?sl:sh)>>>((i&3)*16))&0xFFFF);
              if(w<minVal){ minVal=w; minIdx=i; }
            }
            xmm_lo[xd] = (minVal & 0xFFFFL) | ((long)minIdx << 16);
            xmm_hi[xd] = 0;
            return n3;
          }
          if( b2==0x29 ) { // PCMPEQQ (SSE4.1): 64-bit 等値比較
            xmm_lo[xd] = (xmm_lo[xd]==sl) ? -1L : 0L;
            xmm_hi[xd] = (xmm_hi[xd]==sh) ? -1L : 0L;
            return n3;
          }
          if( b2==0x37 ) { // PCMPGTQ (SSE4.2): signed 64-bit greater-than
            xmm_lo[xd] = (xmm_lo[xd]>sl) ? -1L : 0L;
            xmm_hi[xd] = (xmm_hi[xd]>sh) ? -1L : 0L;
            return n3;
          }
          if( b2==0x40 ) { // PMULLD (SSE4.1): 4 dword 乗算の低 32bit
            long rl=0,rh=0;
            for(int i=0;i<2;i++){ int s=i*32;
              rl |= ((long)((int)(xmm_lo[xd]>>>s) * (int)(sl>>>s)) & 0xFFFFFFFFL)<<s;
              rh |= ((long)((int)(xmm_hi[xd]>>>s) * (int)(sh>>>s)) & 0xFFFFFFFFL)<<s; }
            xmm_lo[xd]=rl; xmm_hi[xd]=rh;
            return n3;
          }
          if( b2==0x28 ) { // PMULDQ (SSE4.1): signed 32x32→64、even dword ×2
            xmm_lo[xd] = (long)(int)xmm_lo[xd] * (long)(int)sl;
            xmm_hi[xd] = (long)(int)xmm_hi[xd] * (long)(int)sh;
            return n3;
          }
          if( b2>=0x38 && b2<=0x3F ) {
            // PMIN/PMAX (SSE4.1): PMINSB(38)/PMINSD(39)/PMINUW(3A)/PMINUD(3B)/
            //   PMAXSB(3C)/PMAXSD(3D)/PMAXUW(3E)/PMAXUD(3F)。
            int esize; boolean signed, isMax;
            switch( b2 ) {
              case 0x38: esize=8;  signed=true;  isMax=false; break;
              case 0x3C: esize=8;  signed=true;  isMax=true;  break;
              case 0x3A: esize=16; signed=false; isMax=false; break;
              case 0x3E: esize=16; signed=false; isMax=true;  break;
              case 0x39: esize=32; signed=true;  isMax=false; break;
              case 0x3D: esize=32; signed=true;  isMax=true;  break;
              case 0x3B: esize=32; signed=false; isMax=false; break;
              default:   esize=32; signed=false; isMax=true;  break;  // 0x3F PMAXUD
            }
            xmm_lo[xd] = pMinMax( xmm_lo[xd], sl, esize, signed, isMax );
            xmm_hi[xd] = pMinMax( xmm_hi[xd], sh, esize, signed, isMax );
            return n3;
          }
          if( b2==0x01 || b2==0x02 || b2==0x03 || b2==0x05 || b2==0x06 || b2==0x07 ) {
            // PHADDW(01)/PHADDD(02)/PHADDSW(03)/PHSUBW(05)/PHSUBD(06)/PHSUBSW(07)
            //   SSSE3 horizontal add/sub。dst pair → 結果低半分、src pair → 高半分。
            long dl=xmm_lo[xd], dh=xmm_hi[xd];
            boolean isW = (b2==0x01||b2==0x03||b2==0x05||b2==0x07);
            boolean isSub = (b2>=0x05);
            boolean sat = (b2==0x03||b2==0x07);
            if( isW ) {
              int[] w = new int[16];
              for(int i=0;i<4;i++){ w[i]=(short)(dl>>>(i*16)); w[i+4]=(short)(dh>>>(i*16)); w[i+8]=(short)(sl>>>(i*16)); w[i+12]=(short)(sh>>>(i*16)); }
              long rl=0, rh=0;
              for(int i=0;i<4;i++){
                int a=w[2*i], b=w[2*i+1];   int r0 = isSub? a-b : a+b;
                int c=w[8+2*i], d=w[8+2*i+1]; int r1 = isSub? c-d : c+d;
                rl |= (sat? satSWord(r0) : (r0&0xFFFFL)) << (i*16);
                rh |= (sat? satSWord(r1) : (r1&0xFFFFL)) << (i*16);
              }
              xmm_lo[xd]=rl; xmm_hi[xd]=rh;
            } else {
              int d0=(int)dl, d1=(int)(dl>>>32), d2=(int)dh, d3=(int)(dh>>>32);
              int s0=(int)sl, s1=(int)(sl>>>32), s2=(int)sh, s3=(int)(sh>>>32);
              int r0=isSub?d0-d1:d0+d1, r1=isSub?d2-d3:d2+d3;
              int r2=isSub?s0-s1:s0+s1, r3=isSub?s2-s3:s2+s3;
              xmm_lo[xd]=((long)r0&0xFFFFFFFFL)|((long)r1<<32);
              xmm_hi[xd]=((long)r2&0xFFFFFFFFL)|((long)r3<<32);
            }
            return n3;
          }
          if( b2==0x08 || b2==0x09 || b2==0x0A ) { // PSIGNB/W/D (SSSE3)
            int esize = (b2==0x08)?8 : (b2==0x09)?16 : 32;
            xmm_lo[xd] = pSign(xmm_lo[xd], sl, esize);
            xmm_hi[xd] = pSign(xmm_hi[xd], sh, esize);
            return n3;
          }
          if( b2==0x0B ) { // PMULHRSW (SSSE3): ((d*s>>14)+1)>>1、signed words
            long rl=0,rh=0;
            for(int i=0;i<4;i++){ int s=i*16;
              int p=(((short)(xmm_lo[xd]>>>s)*(short)(sl>>>s))>>14)+1; rl|=((long)(p>>1)&0xFFFFL)<<s;
              int q=(((short)(xmm_hi[xd]>>>s)*(short)(sh>>>s))>>14)+1; rh|=((long)(q>>1)&0xFFFFL)<<s; }
            xmm_lo[xd]=rl; xmm_hi[xd]=rh; return n3;
          }
          if( b2>=0x1C && b2<=0x1E ) { // PABSB/W/D (SSSE3)
            int esize=(b2==0x1C)?8:(b2==0x1D)?16:32;
            xmm_lo[xd]=pAbs(sl,esize); xmm_hi[xd]=pAbs(sh,esize);
            return n3;
          }
          if( b2==0x2B ) { // PACKUSDW (SSE4.1): 4+4 signed dwords → 8 unsigned-sat words
            long dl=xmm_lo[xd], dh=xmm_hi[xd];
            xmm_lo[xd] = satUWord((int)dl)|(satUWord((int)(dl>>>32))<<16)|(satUWord((int)dh)<<32)|(satUWord((int)(dh>>>32))<<48);
            xmm_hi[xd] = satUWord((int)sl)|(satUWord((int)(sl>>>32))<<16)|(satUWord((int)sh)<<32)|(satUWord((int)(sh>>>32))<<48);
            return n3;
          }
          if( b2==0x04 ) {
            // PMADDUBSW (SSSE3): dst byte (unsigned) * src byte (signed) を
            //   隣接ペアで加算し int16 飽和。simdutf 等が使用。
            long dl=xmm_lo[xd], dh=xmm_hi[xd], rl=0, rh=0;
            for( int i=0; i<4; i++ ) {
              int bs = i*16;
              int da0=(int)((dl>>>bs)&0xFF), db0=(int)((dl>>>(bs+8))&0xFF);
              int sa0=(byte)(sl>>>bs),       sb0=(byte)(sl>>>(bs+8));
              rl |= satSWord(da0*sa0 + db0*sb0) << bs;
              int da1=(int)((dh>>>bs)&0xFF), db1=(int)((dh>>>(bs+8))&0xFF);
              int sa1=(byte)(sh>>>bs),       sb1=(byte)(sh>>>(bs+8));
              rh |= satSWord(da1*sa1 + db1*sb1) << bs;
            }
            xmm_lo[xd]=rl; xmm_hi[xd]=rh;
            return n3;
          }
          if( b2==0x10 || b2==0x14 || b2==0x15 ) {
            // PBLENDVB(10)/BLENDVPS(14)/BLENDVPD(15) xmm1, xmm2/m128, <XMM0>
            //   SSE4.1。implicit mask = XMM0。要素ごとに mask の MSB が立てば
            //   src、else dst を採用。
            long dl = xmm_lo[xd], dh = xmm_hi[xd];
            long ml = xmm_lo[0],  mh = xmm_hi[0];
            if( b2==0x15 ) {        // BLENDVPD: 64-bit x2
              xmm_lo[xd] = (((ml >>> 63) & 1L) != 0) ? sl : dl;
              xmm_hi[xd] = (((mh >>> 63) & 1L) != 0) ? sh : dh;
            } else if( b2==0x14 ) { // BLENDVPS: 32-bit x4
              xmm_lo[xd] = blendDword( dl, sl, ml );
              xmm_hi[xd] = blendDword( dh, sh, mh );
            } else {                // PBLENDVB: 8-bit x16
              xmm_lo[xd] = blendByte( dl, sl, ml );
              xmm_hi[xd] = blendByte( dh, sh, mh );
            }
            return n3;
          }
          if( (b2>=0x20 && b2<=0x25) || (b2>=0x30 && b2<=0x35) ) {
            // PMOVSX (20-25) / PMOVZX (30-35) xmm1, xmm2/m: packed sign/zero
            //   extend。src は低 64bit (sl) に収まる。dst は常に 128bit。
            //   下位 nibble で size combo: BW/BD/BQ/WD/WQ/DQ。
            boolean sign = (b2 <= 0x25);
            int low = b2 & 0x0F;
            int srcBits, dstBits;
            switch( low ) {
              case 0: srcBits=8;  dstBits=16; break; // BW
              case 1: srcBits=8;  dstBits=32; break; // BD
              case 2: srcBits=8;  dstBits=64; break; // BQ
              case 3: srcBits=16; dstBits=32; break; // WD
              case 4: srcBits=16; dstBits=64; break; // WQ
              default:srcBits=32; dstBits=64; break; // DQ
            }
            int count = 128 / dstBits;
            long srcMask = (1L << srcBits) - 1;
            long rl=0, rh=0;
            for( int i=0; i<count; i++ ) {
              long elem = (sl >>> (i*srcBits)) & srcMask;
              if( sign ) { int s = 64 - srcBits; elem = (elem << s) >> s; }
              long dstMask = (dstBits==64) ? -1L : ((1L << dstBits) - 1);
              elem &= dstMask;
              int bitpos = i * dstBits;
              if( bitpos < 64 ) rl |= elem << bitpos;
              else              rh |= elem << (bitpos - 64);
            }
            xmm_lo[xd] = rl; xmm_hi[xd] = rh;
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
          if( b2==0x21 ) { // INSERTPS xmm1, xmm2/m32, imm8 (SSE4.1)
            int countS=(imm>>6)&3, countD=(imm>>4)&3, zmask=imm&0xF;
            long temp = (mrm_mod==3) ? ((((countS<2)?sl:sh) >>> ((countS&1)*32)) & 0xFFFFFFFFL)
                                     : (sl & 0xFFFFFFFFL);
            long[] dw = { xmm_lo[xd]&0xFFFFFFFFL, (xmm_lo[xd]>>>32)&0xFFFFFFFFL,
                          xmm_hi[xd]&0xFFFFFFFFL, (xmm_hi[xd]>>>32)&0xFFFFFFFFL };
            dw[countD] = temp;
            for(int i=0;i<4;i++) if((zmask>>i&1)!=0) dw[i]=0;
            xmm_lo[xd] = dw[0] | (dw[1]<<32);
            xmm_hi[xd] = dw[2] | (dw[3]<<32);
            return n3 + 1;
          }
          if( b2==0x20 ) { // PINSRB xmm1, r/m8, imm8 (SSE4.1)
            int v8 = (mrm_mod == 3) ? (int)(r64[xs] & 0xFFL) : (mem.load8(mrm_ea) & 0xFF);
            int pos = imm & 15, bitsh = (pos & 7) * 8;
            long mask = 0xFFL << bitsh, val = ((long)v8) << bitsh;
            if( pos < 8 ) xmm_lo[xd] = (xmm_lo[xd] & ~mask) | val;
            else          xmm_hi[xd] = (xmm_hi[xd] & ~mask) | val;
            return n3 + 1;
          }
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
          if( b2==0x60 || b2==0x61 || b2==0x62 || b2==0x63 ) {
            // SSE4.2 文字列比較: PCMPESTRM(60)/PCMPESTRI(61)/PCMPISTRM(62)/PCMPISTRI(63)
            //   xmm1=reg(xd), xmm2/m128=rm(sl/sh)。explicit (E*) は len を EAX/EDX から。
            boolean explicit = (b2==0x60 || b2==0x61);
            boolean maskOut  = (b2==0x60 || b2==0x62);  // M=mask→XMM0、I=index→ECX
            int[] r = pcmpStrCore( xmm_lo[xd], xmm_hi[xd], sl, sh, imm,
                                   (int)r64[R_RAX], (int)r64[R_RDX], explicit );
            int intRes2 = r[0], len1 = r[1], len2 = r[2], numElems = r[3];
            if( maskOut ) {
              long ml=0, mh=0;
              if( (imm & 0x40) == 0 ) {  // bit mask: XMM0 低 bit に intRes2、上位 0
                ml = intRes2 & ((numElems==16)?0xFFFFL:0xFFL);
              } else {                   // element mask: 各要素 all-1/all-0
                for(int j=0;j<numElems;j++){
                  if( (intRes2>>j&1)!=0 ){
                    if(numElems==16){ if(j<8) ml|=0xFFL<<(j*8); else mh|=0xFFL<<((j-8)*8); }
                    else            { if(j<4) ml|=0xFFFFL<<(j*16); else mh|=0xFFFFL<<((j-4)*16); }
                  }
                }
              }
              xmm_lo[0]=ml; xmm_hi[0]=mh;  // XMM0
            } else {                    // index → ECX
              int idx;
              if( (imm & 0x40)==0 ) idx = (intRes2==0)?numElems:Integer.numberOfTrailingZeros(intRes2);
              else                  idx = (intRes2==0)?numElems:(31-Integer.numberOfLeadingZeros(intRes2));
              r64[R_RCX] = idx & 0xFFFFFFFFL;
            }
            cf = (intRes2!=0)?1:0;
            zf = (len2<numElems)?1:0;
            sf = (len1<numElems)?1:0;
            of = intRes2 & 1;
            pf = 0;
            return n3 + 1;
          }
          if( b2==0x17 ) { // EXTRACTPS r/m32, xmm, imm8 (SSE4.1) — single を r/m32 へ
            int lane = imm & 3;
            long v32 = (((lane<2)?xmm_lo[xd]:xmm_hi[xd]) >>> ((lane&1)*32)) & 0xFFFFFFFFL;
            if( mrm_mod == 3 ) r64[mrm_rm] = v32;
            else               mem.store32( mrm_ea, (int)v32 );
            return n3 + 1;
          }
          if( b2==0x14 || b2==0x15 || b2==0x16 ) {
            // PEXTRB(14)/PEXTRW(15)/PEXTRD・PEXTRQ(16) — xmm (ModRM.reg) から
            //   要素を r/m32/64/mem へ抽出。この族は reg=xmm src / rm=GPR・mem
            //   dst の逆向き encoding。
            long xl = xmm_lo[mrm_reg], xh = xmm_hi[mrm_reg];
            long val; int nbytes;
            if( b2==0x14 ) {            // PEXTRB
              int lane = imm & 15;
              val = (((lane < 8) ? xl : xh) >>> ((lane & 7)*8)) & 0xFFL; nbytes = 1;
            } else if( b2==0x15 ) {     // PEXTRW
              int lane = imm & 7;
              val = (((lane < 4) ? xl : xh) >>> ((lane & 3)*16)) & 0xFFFFL; nbytes = 2;
            } else if( rex_w ) {        // PEXTRQ
              val = ((imm & 1) != 0) ? xh : xl; nbytes = 8;
            } else {                    // PEXTRD
              int lane = imm & 3;
              val = (((lane < 2) ? xl : xh) >>> ((lane & 1)*32)) & 0xFFFFFFFFL; nbytes = 4;
            }
            if( mrm_mod == 3 ) {        // GPR dest (PEXTRB/W/D は 32-bit zero-ext)
              r64[mrm_rm] = (nbytes==8) ? val
                          : val & ((nbytes==1)?0xFFL:(nbytes==2)?0xFFFFL:0xFFFFFFFFL);
            } else {
              if( nbytes==1 )      mem.store8 ( mrm_ea, (byte)val );
              else if( nbytes==2 ) mem.store16( mrm_ea, (short)val );
              else if( nbytes==4 ) mem.store32( mrm_ea, (int)val );
              else                 mem.store64( mrm_ea, val );
            }
            return n3 + 1;
          }
          if( b2==0x0C ) { // BLENDPS xmm1, xmm2/m128, imm8 (SSE4.1): 4 dword blend
            long dl=xmm_lo[xd], dh=xmm_hi[xd];
            long rl = ((imm&1)!=0 ? sl : dl) & 0xFFFFFFFFL;
            rl     |= ((imm&2)!=0 ? sl : dl) & 0xFFFFFFFF00000000L;
            long rh = ((imm&4)!=0 ? sh : dh) & 0xFFFFFFFFL;
            rh     |= ((imm&8)!=0 ? sh : dh) & 0xFFFFFFFF00000000L;
            xmm_lo[xd]=rl; xmm_hi[xd]=rh;
            return n3 + 1;
          }
          if( b2==0x0D ) { // BLENDPD xmm1, xmm2/m128, imm8 (SSE4.1): 2 qword blend
            if((imm&1)!=0) xmm_lo[xd]=sl;
            if((imm&2)!=0) xmm_hi[xd]=sh;
            return n3 + 1;
          }
          if( b2==0x0E ) { // PBLENDW xmm1, xmm2/m128, imm8 (SSE4.1)
            // imm8 の bit i (i=0..7) が立つと dst.word[i] = src.word[i]、else 保持。
            long dl = xmm_lo[xd], dh = xmm_hi[xd], rl = 0, rh = 0;
            for( int i = 0; i < 4; i++ ) {
              int shl = i * 16;
              long sw = (((imm >> i)     & 1) != 0) ? ((sl >>> shl) & 0xFFFF) : ((dl >>> shl) & 0xFFFF);
              long swh= (((imm >> (i+4)) & 1) != 0) ? ((sh >>> shl) & 0xFFFF) : ((dh >>> shl) & 0xFFFF);
              rl |= sw  << shl;
              rh |= swh << shl;
            }
            xmm_lo[xd] = rl; xmm_hi[xd] = rh;
            return n3 + 1;
          }
          if( b2==0x08 || b2==0x09 || b2==0x0A || b2==0x0B ) {
            // ROUNDPS(08)/ROUNDPD(09)/ROUNDSS(0A)/ROUNDSD(0B) xmm1, xmm2/m, imm8
            //   SSE4.1。JS の Math.floor/ceil/round/trunc 等で多用。
            //   scalar 形 (SS/SD) は低位 element のみ更新、残りは dst 保持。
            int mode = imm & 0x07;
            if( b2==0x0B ) {        // ROUNDSD: low double のみ
              xmm_lo[xd] = Double.doubleToRawLongBits( roundSSE( Double.longBitsToDouble(sl), mode ) );
            } else if( b2==0x0A ) { // ROUNDSS: low float のみ
              int rf = Float.floatToRawIntBits( (float)roundSSE( Float.intBitsToFloat((int)sl), mode ) );
              xmm_lo[xd] = (xmm_lo[xd] & 0xFFFFFFFF00000000L) | ((long)rf & 0xFFFFFFFFL);
            } else if( b2==0x09 ) { // ROUNDPD: 2 doubles
              xmm_lo[xd] = Double.doubleToRawLongBits( roundSSE( Double.longBitsToDouble(sl), mode ) );
              xmm_hi[xd] = Double.doubleToRawLongBits( roundSSE( Double.longBitsToDouble(sh), mode ) );
            } else {                // ROUNDPS: 4 floats
              xmm_lo[xd] = roundPS2( sl, mode );
              xmm_hi[xd] = roundPS2( sh, mode );
            }
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
        if( b1==0x14 ) { // UNPCKLPD xmm, xmm/m128: DEST[127:64]=SRC[63:0], 低 64 保持
          xmm_hi[dst]=sl; return next;
        }
        if( b1==0x15 ) { // UNPCKHPD xmm, xmm/m128: DEST[63:0]=DEST[127:64], DEST[127:64]=SRC[127:64]
          xmm_lo[dst]=xmm_hi[dst]; xmm_hi[dst]=sh; return next;
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
        if( b1==0xF4 ) { // PMULUDQ xmm, xmm/m128 — issue #20
          // 2 つの xmm の low-32bit を unsigned 乗算して 64bit 結果を
          // dst の 0-63 / 64-127 に入れる。curve25519 / ed25519 の
          // 64-bit limb multiplication で必須。
          //   dst.q[0] = (uint32)dst.d[0] * (uint32)src.d[0]
          //   dst.q[1] = (uint32)dst.d[2] * (uint32)src.d[2]
          // Java の long * long で十分 (uint32 * uint32 = max 2^64 - 2^33 + 1
          // で long 範囲内、unsigned 乗算と signed 乗算は下位 64bit が一致)。
          xmm_lo[dst] = (xmm_lo[dst] & 0xFFFFFFFFL) * (sl & 0xFFFFFFFFL);
          xmm_hi[dst] = (xmm_hi[dst] & 0xFFFFFFFFL) * (sh & 0xFFFFFFFFL);
          return next;
        }
        if( b1==0xFC ) { // PADDB
          xmm_lo[dst]=paddb(xmm_lo[dst],sl); xmm_hi[dst]=paddb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xFD ) { // PADDW (8 words wrapping)
          long lo=0,hi=0; for(int i=0;i<4;i++){int s16=i*16;lo|=(((xmm_lo[dst]>>>s16)+(sl>>>s16))&0xFFFFL)<<s16;hi|=(((xmm_hi[dst]>>>s16)+(sh>>>s16))&0xFFFFL)<<s16;}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xF9 ) { // PSUBW (8 words wrapping)
          long lo=0,hi=0; for(int i=0;i<4;i++){int s16=i*16;lo|=(((xmm_lo[dst]>>>s16)-(sl>>>s16))&0xFFFFL)<<s16;hi|=(((xmm_hi[dst]>>>s16)-(sh>>>s16))&0xFFFFL)<<s16;}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xD5 ) { // PMULLW: signed word products の低 16bit
          long lo=0,hi=0; for(int i=0;i<4;i++){int s=i*16;int p=(short)(xmm_lo[dst]>>>s)*(short)(sl>>>s);lo|=((long)p&0xFFFFL)<<s;int q=(short)(xmm_hi[dst]>>>s)*(short)(sh>>>s);hi|=((long)q&0xFFFFL)<<s;}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xE5 ) { // PMULHW: signed word products の高 16bit
          long lo=0,hi=0; for(int i=0;i<4;i++){int s=i*16;int p=((short)(xmm_lo[dst]>>>s)*(short)(sl>>>s))>>16;lo|=((long)p&0xFFFFL)<<s;int q=((short)(xmm_hi[dst]>>>s)*(short)(sh>>>s))>>16;hi|=((long)q&0xFFFFL)<<s;}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xE4 ) { // PMULHUW: unsigned word products の高 16bit
          long lo=0,hi=0; for(int i=0;i<4;i++){int s=i*16;int p=(((int)(xmm_lo[dst]>>>s)&0xFFFF)*((int)(sl>>>s)&0xFFFF))>>>16;lo|=((long)p&0xFFFFL)<<s;int q=(((int)(xmm_hi[dst]>>>s)&0xFFFF)*((int)(sh>>>s)&0xFFFF))>>>16;hi|=((long)q&0xFFFFL)<<s;}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xEA ) { // PMINSW (signed word min)
          xmm_lo[dst]=pMinMax(xmm_lo[dst],sl,16,true,false); xmm_hi[dst]=pMinMax(xmm_hi[dst],sh,16,true,false); return next;
        }
        if( b1==0xEE ) { // PMAXSW (signed word max)
          xmm_lo[dst]=pMinMax(xmm_lo[dst],sl,16,true,true); xmm_hi[dst]=pMinMax(xmm_hi[dst],sh,16,true,true); return next;
        }
        if( b1==0xF6 ) { // PSADBW: |unsigned byte 差| の和を低/高 qword の word0 に
          long s0=0,s1=0;
          for(int i=0;i<8;i++){ int b=i*8;
            s0+=Math.abs(((int)(xmm_lo[dst]>>>b)&0xFF)-((int)(sl>>>b)&0xFF));
            s1+=Math.abs(((int)(xmm_hi[dst]>>>b)&0xFF)-((int)(sh>>>b)&0xFF)); }
          xmm_lo[dst]=s0&0xFFFF; xmm_hi[dst]=s1&0xFFFF; return next;
        }
        if( b1==0xF5 ) { // PMADDWD: signed words multiply-add → 4 dwords
          long lo=0,hi=0;
          for(int i=0;i<2;i++){
            int bs=i*32;
            int dl0=(short)(xmm_lo[dst]>>>bs),    sl0=(short)(sl>>>bs);
            int dl1=(short)(xmm_lo[dst]>>>(bs+16)),sl1=(short)(sl>>>(bs+16));
            lo|=((long)(dl0*sl0 + dl1*sl1)&0xFFFFFFFFL)<<bs;
            int dh0=(short)(xmm_hi[dst]>>>bs),    sh0=(short)(sh>>>bs);
            int dh1=(short)(xmm_hi[dst]>>>(bs+16)),sh1=(short)(sh>>>(bs+16));
            hi|=((long)(dh0*sh0 + dh1*sh1)&0xFFFFFFFFL)<<bs;
          }
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xE8 ) { // PSUBSB (signed saturate byte subtract) — 旧実装は subb(wrapping)で誤りだった
          xmm_lo[dst]=psubsb(xmm_lo[dst],sl); xmm_hi[dst]=psubsb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xF8 ) { // PSUBB (wrapping byte subtract)
          long lo=0,hi=0; for(int i=0;i<8;i++){lo|=(((xmm_lo[dst]>>(i*8))-(sl>>(i*8)))&0xFFL)<<(i*8);hi|=(((xmm_hi[dst]>>(i*8))-(sh>>(i*8)))&0xFFL)<<(i*8);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xD8 ) { // PSUBUSB (unsigned saturate byte subtract)
          xmm_lo[dst]=psubusb(xmm_lo[dst],sl); xmm_hi[dst]=psubusb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xD9 ) { // PSUBUSW (unsigned saturate word subtract)
          xmm_lo[dst]=psubusw(xmm_lo[dst],sl); xmm_hi[dst]=psubusw(xmm_hi[dst],sh); return next;
        }
        if( b1==0xDC ) { // PADDUSB (unsigned saturate byte add)
          xmm_lo[dst]=paddusb(xmm_lo[dst],sl); xmm_hi[dst]=paddusb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xDD ) { // PADDUSW (unsigned saturate word add)
          xmm_lo[dst]=paddusw(xmm_lo[dst],sl); xmm_hi[dst]=paddusw(xmm_hi[dst],sh); return next;
        }
        if( b1==0xE9 ) { // PSUBSW (signed saturate word subtract)
          xmm_lo[dst]=psubsw(xmm_lo[dst],sl); xmm_hi[dst]=psubsw(xmm_hi[dst],sh); return next;
        }
        if( b1==0xEC ) { // PADDSB (signed saturate byte add) — node(V8) が使用
          xmm_lo[dst]=paddsb(xmm_lo[dst],sl); xmm_hi[dst]=paddsb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xED ) { // PADDSW (signed saturate word add)
          xmm_lo[dst]=paddsw(xmm_lo[dst],sl); xmm_hi[dst]=paddsw(xmm_hi[dst],sh); return next;
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
        // 66 0F 28/10: MOVAPD/MOVUPD xmm, xmm/m128 (load) /
        // 66 0F 29/11: MOVAPD/MOVUPD xmm/m128, xmm (store)。emulin では
        //   aligned(28/29) と unaligned(10/11) は同一 (byte[] への 128bit copy)。
        if( b1==0x2B ) {  // MOVNTPD m128, xmm — non-temporal 128bit store (hint 無視)
          long mn=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(mn,fs_prefix);
          if(mrm_mod!=3){ mem.store64(mrm_ea,xmm_lo[mrm_reg]); mem.store64(mrm_ea+8,xmm_hi[mrm_reg]); }
          return mn;
        }
        if( b1==0x28 || b1==0x29 || b1==0x10 || b1==0x11 ) {
          long mn=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(mn,fs_prefix);
          int xd=mrm_reg, xs=mrm_rm;
          if( b1==0x28 || b1==0x10 ) {  // load to xmm
            if(mrm_mod==3){ xmm_lo[xd]=xmm_lo[xs]; xmm_hi[xd]=xmm_hi[xs]; }
            else{ xmm_lo[xd]=mem.load64(mrm_ea); xmm_hi[xd]=mem.load64(mrm_ea+8); }
          } else {                      // store from xmm
            if(mrm_mod==3){ xmm_lo[xs]=xmm_lo[xd]; xmm_hi[xs]=xmm_hi[xd]; }
            else{ mem.store64(mrm_ea, xmm_lo[xd]); mem.store64(mrm_ea+8, xmm_hi[xd]); }
          }
          return mn;
        }
        // 66 0F C6 /r ib: SHUFPD xmm1, xmm2/m128, imm8
        //   imm8 bit 0: 0 = dest lo を維持, 1 = dest hi を dest lo にコピー
        //   imm8 bit 1: 0 = src lo を dest hi に, 1 = src hi を dest hi に
        if( b1==0xC6 ) {
          // issue #597: SHUFPD は imm8 を持つので RIP-relative memory operand の EA は
          //   imm8 を含む命令末尾 (shufpd_next + 1) が基準。旧実装は imm8 手前の
          //   shufpd_next を fixEA に渡し、RIP-rel 時に EA が 1 byte 手前へずれて
          //   定数 (hashbrown insert の items 減算 -1) を 1 byte ずれて読み、-0x100 に化けて
          //   items が +0x100/insert される → HashMap iterator が over-scan して隣接
          //   guard/未map ページで SIGSEGV。imm8 分を足して修正。
          long shufpd_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(shufpd_next + 1,fs_prefix);
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
        // 66 0F 63: PACKSSWB (8+8 signed words → 16 signed-sat bytes)
        // 66 0F 6B: PACKSSDW (4+4 signed dwords → 8 signed-sat words)
        if( b1==0x63 || b1==0x6B ) {
          long pk_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(pk_next,fs_prefix);
          int xd=mrm_reg, xs=mrm_rm;
          long psl, psh;
          if(mrm_mod==3){ psl=xmm_lo[xs]; psh=xmm_hi[xs]; }
          else          { psl=mem.load64(mrm_ea); psh=mem.load64(mrm_ea+8); }
          long dl=xmm_lo[xd], dh=xmm_hi[xd], rl=0, rh=0;
          if( b1==0x6B ) {  // dwords → words
            rl |= satSWord((int)dl) | (satSWord((int)(dl>>>32))<<16) | (satSWord((int)dh)<<32) | (satSWord((int)(dh>>>32))<<48);
            rh |= satSWord((int)psl)| (satSWord((int)(psl>>>32))<<16)| (satSWord((int)psh)<<32)| (satSWord((int)(psh>>>32))<<48);
          } else {          // words → bytes
            for(int i=0;i<4;i++){ rl |= satSByte((short)(dl>>>(i*16)))  << (i*8);    rl |= satSByte((short)(dh>>>(i*16)))  << ((i+4)*8); }
            for(int i=0;i<4;i++){ rh |= satSByte((short)(psl>>>(i*16))) << (i*8);    rh |= satSByte((short)(psh>>>(i*16))) << ((i+4)*8); }
          }
          xmm_lo[xd]=rl; xmm_hi[xd]=rh;
          return pk_next;
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
        // 66 0F C2 ib: CMPPD xmm1, xmm2/m128, imm8 — packed double 2 lane を
        //   imm8 predicate で比較、一致 lane は全 1 (-1L)、不一致は 0。V8 の
        //   JIT 生成コード (Float64x2 比較) で使われる。
        if( b1==0xC2 ) {
          long cp_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(cp_next,fs_prefix);
          int cp_xd=mrm_reg, cp_xs=mrm_rm;
          long bl, bh;
          if(mrm_mod==3){ bl=xmm_lo[cp_xs]; bh=xmm_hi[cp_xs]; }
          else          { bl=mem.load64(mrm_ea); bh=mem.load64(mrm_ea+8); }
          int pred = mem.load8(cp_next) & 0xFF;
          double a0=Double.longBitsToDouble(xmm_lo[cp_xd]), b0=Double.longBitsToDouble(bl);
          double a1=Double.longBitsToDouble(xmm_hi[cp_xd]), b1d=Double.longBitsToDouble(bh);
          xmm_lo[cp_xd] = sseCmpMatch(a0,b0,pred) ? -1L : 0L;
          xmm_hi[cp_xd] = sseCmpMatch(a1,b1d,pred) ? -1L : 0L;
          return cp_next + 1;
        }
        // 66 0F D1/D2/D3 (PSRLW/PSRLD/PSRLQ), E1/E2 (PSRAW/PSRAD), F1/F2/F3
        //   (PSLLW/PSLLD/PSLLQ): shift count = source operand の低 64bit (全要素
        //   共通)。imm 形 (66 0F 71/72/73) は実装済だが、count を xmm/m128 で渡す
        //   この形は未実装で OpenSSL/V8 の TLS/SIMD で踏む。
        if( b1==0xD1||b1==0xD2||b1==0xD3||b1==0xE1||b1==0xE2||b1==0xF1||b1==0xF2||b1==0xF3 ) {
          long sn2=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(sn2,fs_prefix);
          int xd=mrm_reg;
          long cnt = (mrm_mod==3) ? xmm_lo[mrm_rm] : mem.load64(mrm_ea);
          int c = (Long.compareUnsigned(cnt,64)>=0) ? 64 : (int)cnt;
          long dl=xmm_lo[xd], dh=xmm_hi[xd], rl=0, rh=0;
          if( b1==0xD1||b1==0xE1||b1==0xF1 ) {       // 16-bit lane ×8
            for(int i=0;i<8;i++){
              int wl=(int)((dl>>>(i*16))&0xFFFF), wh=(int)((dh>>>(i*16))&0xFFFF), ol, oh;
              if(b1==0xD1){ ol=(c>=16)?0:(wl>>>c); oh=(c>=16)?0:(wh>>>c); }
              else if(b1==0xE1){ short swl=(short)wl, swh=(short)wh; ol=(c>=16)?(swl>>15):(swl>>c); oh=(c>=16)?(swh>>15):(swh>>c); }
              else { ol=(c>=16)?0:(wl<<c); oh=(c>=16)?0:(wh<<c); }
              rl|=((long)(ol&0xFFFF))<<(i*16); rh|=((long)(oh&0xFFFF))<<(i*16);
            }
          } else if( b1==0xD2||b1==0xE2||b1==0xF2 ) { // 32-bit lane ×4
            for(int i=0;i<4;i++){
              int dlo=(int)((dl>>>(i*32))&0xFFFFFFFFL), dhi=(int)((dh>>>(i*32))&0xFFFFFFFFL), ol, oh;
              if(b1==0xD2){ ol=(c>=32)?0:(dlo>>>c); oh=(c>=32)?0:(dhi>>>c); }
              else if(b1==0xE2){ ol=(c>=32)?(dlo>>31):(dlo>>c); oh=(c>=32)?(dhi>>31):(dhi>>c); }
              else { ol=(c>=32)?0:(dlo<<c); oh=(c>=32)?0:(dhi<<c); }
              rl|=((long)ol&0xFFFFFFFFL)<<(i*32); rh|=((long)oh&0xFFFFFFFFL)<<(i*32);
            }
          } else {                                    // 64-bit lane ×2 (D3 PSRLQ / F3 PSLLQ)
            if(b1==0xD3){ rl=(c>=64)?0:(dl>>>c); rh=(c>=64)?0:(dh>>>c); }
            else        { rl=(c>=64)?0:(dl<<c);  rh=(c>=64)?0:(dh<<c); }
          }
          xmm_lo[xd]=rl; xmm_hi[xd]=rh;
          return sn2;
        }
        if( b1==0xE6 ) { // CVTTPD2DQ xmm1, xmm2/m128: 2 double → 2 int32 (trunc)
          //   dst[31:0]=trunc(src.dbl[0]), dst[63:32]=trunc(src.dbl[1]),
          //   dst[127:64]=0。
          int lo = cvtTruncD2I( Double.longBitsToDouble(sl) );
          int hi = cvtTruncD2I( Double.longBitsToDouble(sh) );
          xmm_lo[dst] = ((long)lo & 0xFFFFFFFFL) | (((long)hi & 0xFFFFFFFFL) << 32);
          xmm_hi[dst] = 0;
          return next;
        }
        if( b1==0xE7 ) { // MOVNTDQ m128, xmm — non-temporal 128bit store (hint 無視)
          if( mrm_mod != 3 ) { mem.store64(mrm_ea, xmm_lo[dst]); mem.store64(mrm_ea+8, xmm_hi[dst]); }
          return next;
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
      if( b1==0x2B ) { // MOVNTPS m128, xmm — non-temporal 128bit store (hint 無視)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        if(mrm_mod!=3){ mem.store64(mrm_ea,xmm_lo[mrm_reg]); mem.store64(mrm_ea+8,xmm_hi[mrm_reg]); }
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
      // 0F C2 ib: CMPPS xmm1, xmm2/m128, imm8 — packed single 4 lane を imm8
      //   predicate で比較、一致 lane は全 1 (0xFFFFFFFF)、不一致は 0。V8 の JIT
      //   生成コード (Float32x4 比較) で使われる。
      if( b1==0xC2 ) {
        long cps_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(cps_next,fs_prefix);
        int xd=mrm_reg, xs=mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[xs]; sh=xmm_hi[xs]; }
        else          { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        int pred = mem.load8(cps_next) & 0xFF;
        long dl=xmm_lo[xd], dh=xmm_hi[xd];
        long r_lo=0, r_hi=0;
        // lane 0,1 は low quad、lane 2,3 は high quad。各 32bit を float 比較。
        if( sseCmpMatch(Float.intBitsToFloat((int)dl),       Float.intBitsToFloat((int)sl),       pred) ) r_lo |= 0xFFFFFFFFL;
        if( sseCmpMatch(Float.intBitsToFloat((int)(dl>>>32)),Float.intBitsToFloat((int)(sl>>>32)),pred) ) r_lo |= 0xFFFFFFFF00000000L;
        if( sseCmpMatch(Float.intBitsToFloat((int)dh),       Float.intBitsToFloat((int)sh),       pred) ) r_hi |= 0xFFFFFFFFL;
        if( sseCmpMatch(Float.intBitsToFloat((int)(dh>>>32)),Float.intBitsToFloat((int)(sh>>>32)),pred) ) r_hi |= 0xFFFFFFFF00000000L;
        xmm_lo[xd]=r_lo; xmm_hi[xd]=r_hi;
        return cps_next + 1;
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
      // 0F 50: MOVMSKPS r32, xmm — 4 single の符号 bit を GPR 下位 4bit に。
      if( b1==0x50 ) {
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long xl=xmm_lo[mrm_rm], xh=xmm_hi[mrm_rm];
        long m = ((xl>>>31)&1) | (((xl>>>63)&1)<<1) | (((xh>>>31)&1)<<2) | (((xh>>>63)&1)<<3);
        r64[mrm_reg] = m & 0xFFFFFFFFL;
        return next;
      }
      // 0F 54-57: ANDPS/ANDNPS/ORPS/XORPS — packed single bitwise (128bit 全体に
      //   対する bit 演算なので PD 版と同一)。無印 0F 57(XORPS) だけ実装済だったが
      //   54(ANDPS)/55(ANDNPS)/56(ORPS) も Bun 製 claude が使用。
      if( b1>=0x54 && b1<=0x57 ) {
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        if( b1==0x54 )      { xmm_lo[dst]&=sl;        xmm_hi[dst]&=sh; }        // ANDPS
        else if( b1==0x55 ) { xmm_lo[dst]=(~xmm_lo[dst])&sl; xmm_hi[dst]=(~xmm_hi[dst])&sh; } // ANDNPS
        else if( b1==0x56 ) { xmm_lo[dst]|=sl;        xmm_hi[dst]|=sh; }        // ORPS
        else                { xmm_lo[dst]^=sl;        xmm_hi[dst]^=sh; }        // XORPS
        return next;
      }
      // 0F 58/59/5C/5D/5E/5F: ADDPS/MULPS/SUBPS/MINPS/DIVPS/MAXPS、0F 51 SQRTPS
      //   — packed single (4 float lane) 算術。Bun/JSC が JIT で使用。
      if( b1==0x58 || b1==0x59 || b1==0x5C || b1==0x5D || b1==0x5E || b1==0x5F || b1==0x51 ) {
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        xmm_lo[dst] = packedSingleOp( xmm_lo[dst], sl, b1 );
        xmm_hi[dst] = packedSingleOp( xmm_hi[dst], sh, b1 );
        return next;
      }
      if( b1==0x5A ) { // CVTPS2PD xmm1, xmm2/m64 — 2 single (低 64) → 2 double
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long s = (mrm_mod==3) ? xmm_lo[src] : mem.load64(mrm_ea);
        xmm_lo[dst] = Double.doubleToRawLongBits( (double)Float.intBitsToFloat((int)s) );
        xmm_hi[dst] = Double.doubleToRawLongBits( (double)Float.intBitsToFloat((int)(s>>>32)) );
        return next;
      }
      if( b1==0x5B ) { // CVTDQ2PS xmm1, xmm2/m128 — 4 int32 → 4 single
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl,sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; } else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        long rl = ((long)Float.floatToRawIntBits((float)(int)sl)&0xFFFFFFFFL) | ((long)Float.floatToRawIntBits((float)(int)(sl>>>32))<<32);
        long rh = ((long)Float.floatToRawIntBits((float)(int)sh)&0xFFFFFFFFL) | ((long)Float.floatToRawIntBits((float)(int)(sh>>>32))<<32);
        xmm_lo[dst]=rl; xmm_hi[dst]=rh;
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
      // 0F C7 /n: modrm.reg で分岐
      //   /1 = CMPXCHG8B m64 (REX.W: CMPXCHG16B m128)
      //   /6 = RDRAND r16/32/64, /7 = RDSEED r16/32/64
      if( b1==0xC7 ) {
        long c7_next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(c7_next,fs_prefix);
        int sub = mrm_reg;
        if( sub==6 || sub==7 ) { // RDRAND / RDSEED — 乱数を dest に、CF=1 で成功
          long rnd = java.util.concurrent.ThreadLocalRandom.current().nextLong();
          if( rex_w )     r64[mrm_rm] = rnd;
          else if( op66 ) r64[mrm_rm] = (r64[mrm_rm] & ~0xFFFFL) | (rnd & 0xFFFFL);
          else            r64[mrm_rm] = rnd & 0xFFFFFFFFL;
          cf=1; of=0; sf=0; zf=0; pf=0;
          return c7_next;
        }
        if( sub==1 ) {
          if( rex_w ) { // CMPXCHG16B m128: RDX:RAX vs [mem]、一致なら RCX:RBX を store
            long lo = mem.load64(mrm_ea), hi = mem.load64(mrm_ea+8);
            if( lo==r64[R_RAX] && hi==r64[R_RDX] ) {
              mem.store64(mrm_ea, r64[R_RBX]); mem.store64(mrm_ea+8, r64[R_RCX]); zf=1;
            } else { r64[R_RAX]=lo; r64[R_RDX]=hi; zf=0; }
          } else {       // CMPXCHG8B m64: EDX:EAX vs [mem]、一致なら ECX:EBX を store
            long val = mem.load64(mrm_ea);
            long edxeax = ((r64[R_RDX]&0xFFFFFFFFL)<<32) | (r64[R_RAX]&0xFFFFFFFFL);
            if( val==edxeax ) {
              long ecxebx = ((r64[R_RCX]&0xFFFFFFFFL)<<32) | (r64[R_RBX]&0xFFFFFFFFL);
              mem.store64(mrm_ea, ecxebx); zf=1;
            } else {
              r64[R_RAX] = val & 0xFFFFFFFFL;
              r64[R_RDX] = (val>>>32) & 0xFFFFFFFFL;
              zf=0;
            }
          }
          return c7_next;
        }
        process.println("Cpu64: unsupported 0F C7 /"+sub+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }
      process.println("Cpu64: unsupported 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
  }

  // F3 prefix: ENDBR64 / REP string ops / F3 0F XX (SSE scalar single 等)。
  // REP は内部で string op を loop で完走させる。F3 0F XX は SSE/BMI 命令。
  private long exec_f3_prefix( long pc, boolean rex_w, boolean rex_r,
                               boolean rex_b, boolean rex_x,
                               boolean op66, boolean fs_prefix ) {
      int b1 = mem.load8(pc+1) & 0xFF;
      if( PROFILE_OP ) OP_F3_COUNT[ b1 ]++;  // b1 = first byte after F3 (REX/0F/opcode)
      if( b1 == 0x0F ) {
        int b2 = mem.load8(pc+2)&0xFF, b3 = mem.load8(pc+3)&0xFF;
        if( b2==0x1E && (b3==0xFA||b3==0xFB) ) return pc+4; // ENDBR64/32
      }
      // REP: optional REX / 0x66 between F3 and op。issue #525: 16bit 形は 66 F3 A5 と F3 66 A5 の
      //   両エンコード順があり得るので、F3 より前の op66 (引数) に加えて F3 の後ろの 0x66 も拾う。
      boolean rep_rexw = rex_w, rep66 = op66;
      boolean rep_rex_r=rex_r, rep_rex_x=rex_x, rep_rex_b=rex_b;
      int b_op; long rep_end;
      {
        long q = pc+1; int bq = b1;
        while( true ) {
          if( (bq&0xF0)==0x40 ) { rep_rexw=(bq&0x08)!=0; rep_rex_r=(bq&0x04)!=0; rep_rex_x=(bq&0x02)!=0; rep_rex_b=(bq&0x01)!=0; }
          else if( bq==0x66 )   { rep66=true; }
          else break;
          q++; bq=mem.load8(q)&0xFF;
        }
        b_op=bq; rep_end=q+1;
      }
      // issue #131 (Part A): PAUSE (F3 90)。spin-loop hint なので NOP として
      //   次命令へ進めるだけ。fd (Rust/crossbeam) の parallel directory 走査が
      //   spin-wait で使い、未対応だと "unsupported F3 op=90" で停止していた。
      if( b_op==0x90 ) return rep_end;
      // REP STOS/MOVS: issue #525 で 16bit (rep66) 幅を追加、issue #519 で DF (backward) 対応。
      if( b_op==0xAA ) {
        long st=(df!=0)?-1:1;
        while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],(byte)r64[R_RAX]);r64[R_RDI]+=st;r64[R_RCX]--;}
        return rep_end;
      }
      if( b_op==0xAB ) {
        long w = rep_rexw ? 8 : rep66 ? 2 : 4, st=(df!=0)?-w:w;
        if(rep_rexw)     while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],r64[R_RAX]);r64[R_RDI]+=st;r64[R_RCX]--;}
        else if(rep66)   while(r64[R_RCX]!=0){mem.store16(r64[R_RDI],(short)(r64[R_RAX]&0xFFFF));r64[R_RDI]+=st;r64[R_RCX]--;}
        else             while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],(int)r64[R_RAX]);r64[R_RDI]+=st;r64[R_RCX]--;}
        return rep_end;
      }
      if( b_op==0xA4 ) {
        long st=(df!=0)?-1:1;
        while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],mem.load8(r64[R_RSI]));r64[R_RDI]+=st;r64[R_RSI]+=st;r64[R_RCX]--;}
        return rep_end;
      }
      if( b_op==0xA5 ) {
        long w = rep_rexw ? 8 : rep66 ? 2 : 4, st=(df!=0)?-w:w;
        if(rep_rexw)     while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],mem.load64(r64[R_RSI]));r64[R_RDI]+=st;r64[R_RSI]+=st;r64[R_RCX]--;}
        else if(rep66)   while(r64[R_RCX]!=0){mem.store16(r64[R_RDI],(short)mem.load16(r64[R_RSI]));r64[R_RDI]+=st;r64[R_RSI]+=st;r64[R_RCX]--;}
        else             while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],mem.load32(r64[R_RSI]));r64[R_RDI]+=st;r64[R_RSI]+=st;r64[R_RCX]--;}
        return rep_end;
      }
      // REP LODS (F3 AC/AD): 意味的には最後の要素だけが acc に残る (使途は稀だが、未対応だと
      //   unknown F3 op でゲストプロセスが死ぬ)。issue #519/#525 の string op 一般化の一部。
      if( b_op==0xAC ) {
        long st=(df!=0)?-1:1;
        while(r64[R_RCX]!=0){ r64[R_RAX]=(r64[R_RAX]&~0xFFL)|((long)mem.load8(r64[R_RSI])&0xFFL); r64[R_RSI]+=st; r64[R_RCX]--; }
        return rep_end;
      }
      if( b_op==0xAD ) {
        long w = rep_rexw ? 8 : rep66 ? 2 : 4, st=(df!=0)?-w:w;
        while(r64[R_RCX]!=0){
          if(rep_rexw)     r64[R_RAX]=mem.load64(r64[R_RSI]);
          else if(rep66)   r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(mem.load16(r64[R_RSI])&0xFFFFL);
          else             r64[R_RAX]=mem.load32(r64[R_RSI])&0xFFFFFFFFL;
          r64[R_RSI]+=st; r64[R_RCX]--;
        }
        return rep_end;
      }
      // REPE CMPS (F3 A6/A7): [RSI] と [RDI] を一致する限り (ZF=1) 比較。memcmp / V8 が使用。
      //   issue #525/#519: 16bit (rep66) 幅と DF (backward) 対応。flags は sbb* (AF/PF 含む全6)。
      if( b_op==0xA6 || b_op==0xA7 ) {
        int sz = (b_op==0xA6) ? 1 : (rep_rexw ? 8 : rep66 ? 2 : 4);
        repCmpsScas( true, sz, true );
        return rep_end;
      }
      // REPE SCAS (F3 AE/AF): acc と [RDI] を一致する限り比較。
      //   旧実装は「常に not found (ZF=0, RCX=0)」を返す嘘 stub だった (実装の鉄則違反) →
      //   issue #519/#525 の string op 一般化に合わせて実ループ化。
      if( b_op==0xAE || b_op==0xAF ) {
        int sz = (b_op==0xAE) ? 1 : (rep_rexw ? 8 : rep66 ? 2 : 4);
        repCmpsScas( false, sz, true );
        return rep_end;
      }
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
        // F3 0F 12: MOVSLDUP / F3 0F 16: MOVSHDUP (SSE3) — packed single の
        //   even/odd lane を複製。MOVSLDUP: lane {0,0,2,2}、MOVSHDUP: {1,1,3,3}。
        if( b2==0x12 || b2==0x16 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          long sl = (mrm_mod==3) ? xmm_lo[src] : mem.load64(mrm_ea);
          long sh = (mrm_mod==3) ? xmm_hi[src] : mem.load64(mrm_ea+8);
          long l0 = sl & 0xFFFFFFFFL, l1 = (sl>>>32) & 0xFFFFFFFFL;
          long l2 = sh & 0xFFFFFFFFL, l3 = (sh>>>32) & 0xFFFFFFFFL;
          if( b2==0x12 ) {  // MOVSLDUP: {l0,l0,l2,l2}
            xmm_lo[dst] = l0 | (l0<<32);
            xmm_hi[dst] = l2 | (l2<<32);
          } else {          // MOVSHDUP: {l1,l1,l3,l3}
            xmm_lo[dst] = l1 | (l1<<32);
            xmm_hi[dst] = l3 | (l3<<32);
          }
          return xnext;
        }
        // F3 0F 5B: CVTTPS2DQ xmm1, xmm2/m128 — 4 float → 4 int32 (truncate)
        if( b2==0x5B ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          long sl = (mrm_mod==3) ? xmm_lo[src] : mem.load64(mrm_ea);
          long sh = (mrm_mod==3) ? xmm_hi[src] : mem.load64(mrm_ea+8);
          xmm_lo[dst] = cvtTruncPS2( sl );
          xmm_hi[dst] = cvtTruncPS2( sh );
          return xnext;
        }
        // F3 0F 1E (ENDBR/CET/NOP variants) — NOP
        if( b2==0x1E||b2==0x1F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); return xnext;
        }
        // F3 0F: scalar single-precision FP (issue #78)。旧実装は NOP だったが、
        //   libstdc++ の unordered_map load factor (float) 等が DIVSS を使い、
        //   NOP だと garbage → bad_alloc になる (node の BuiltinLoader)。
        //   58 ADDSS / 59 MULSS / 5C SUBSS / 5D MINSS / 5E DIVSS / 5F MAXSS
        //   (a=dst, b=src/mem)、51 SQRTSS / 52 RSQRTSS / 53 RCPSS (src 入力)。
        //   結果は xmm の低 32bit のみ書き、上位 96bit は保持。
        if( b2==0x58||b2==0x59||b2==0x5C||b2==0x5D||b2==0x5E||b2==0x5F||b2==0x51||b2==0x52||b2==0x53 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          float a = Float.intBitsToFloat((int)xmm_lo[dst]);
          float b = (mrm_mod==3) ? Float.intBitsToFloat((int)xmm_lo[src]) : Float.intBitsToFloat(mem.load32(mrm_ea));
          float r;
          if      (b2==0x58) r = a + b;
          else if (b2==0x59) r = a * b;
          else if (b2==0x5C) r = a - b;
          else if (b2==0x5D) r = Math.min(a, b);
          else if (b2==0x5E) r = a / b;
          else if (b2==0x5F) r = Math.max(a, b);
          else if (b2==0x51) r = (float)Math.sqrt(b);       // SQRTSS
          else if (b2==0x52) r = (float)(1.0/Math.sqrt(b)); // RSQRTSS (近似)
          else               r = (float)(1.0/b);            // RCPSS (近似)
          xmm_lo[dst] = (xmm_lo[dst] & 0xFFFFFFFF00000000L) | (Float.floatToRawIntBits(r) & 0xFFFFFFFFL);
          return xnext;
        }
        // F3 0F C2 ib: CMPSS xmm, xmm/m32, imm8 — scalar single 比較。一致なら
        //   低 32bit を全 1、不一致は 0。上位 96bit は保持。V8 の JIT 生成コード
        //   (float 比較) で使われる。
        if( b2==0xC2 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          float a = Float.intBitsToFloat((int)xmm_lo[dst]);
          float b = (mrm_mod==3) ? Float.intBitsToFloat((int)xmm_lo[src]) : Float.intBitsToFloat(mem.load32(mrm_ea));
          int pred = mem.load8(xnext) & 0xFF;
          long m = sseCmpMatch(a,b,pred) ? 0xFFFFFFFFL : 0L;
          xmm_lo[dst] = (xmm_lo[dst] & 0xFFFFFFFF00000000L) | m;
          return xnext + 1;
        }
        // F3 0F 54: scalar 文脈では稀。NOP (packed ANDPS 用)。
        if( b2==0x54 ) {
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
          // issue #597/#628 と同型: PSHUFHW は imm8 を持つので RIP-relative memory operand の
          //   EA は imm8 を含む命令末尾 (xnext + 1) が基準。旧実装は imm8 手前の xnext を fixEA に
          //   渡し、RIP-rel 時に EA が 1 byte 手前へずれて memory operand を誤読していた
          //   (66 の PSHUFD / F2 の PSHUFLW は正しく imm を勘定していたが F3 の PSHUFHW だけ漏れ)。
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext + 1,fs_prefix);
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
        // F3 0F B8: POPCNT r, r/m — population count。ZF=(src==0)、他 flag は 0。
        //   Bun/JavaScriptCore (claude 単一実行ファイル) が cpuid 非依存で使用。
        if( b2==0xB8 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          long src = rep_rexw ? readRM64() : (readRM32()&0xFFFFFFFFL);
          int n = Long.bitCount(src);
          if( rep_rexw ) r64[mrm_reg] = n; else r64[mrm_reg] = n & 0xFFFFFFFFL;
          zf = (src==0) ? 1 : 0; cf = 0; of = 0; sf = 0; pf = 0; af = 0;  // issue: AF も 0
          return xnext;
        }
        // F3 0F E6: CVTDQ2PD xmm1, xmm2/m64 — 2 int32 (src 低 64bit) → 2 double
        if( b2==0xE6 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          long s = (mrm_mod==3) ? xmm_lo[mrm_rm] : mem.load64(mrm_ea);
          int i0 = (int)(s & 0xFFFFFFFFL), i1 = (int)(s >>> 32);
          xmm_lo[mrm_reg] = Double.doubleToRawLongBits( (double)i0 );
          xmm_hi[mrm_reg] = Double.doubleToRawLongBits( (double)i1 );
          return xnext;
        }
        process.println("Cpu64: unsupported F3 0F "+Integer.toHexString(b2)+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }
      process.println("Cpu64: unsupported F3 op="+Integer.toHexString(b_op)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
  }

  private long decode_and_exec( long pc ) {
    boolean rex_w=false, rex_r=false, rex_x=false, rex_b=false;
    boolean fs_prefix=false, op66=false, opF2=false, lockPrefix=false;
    rex_present = false;
    final long start_pc = pc;
    int b0;

    // Phase 34-A2: per-RIP prefix cache。同じ rip を再実行する場合は
    // prefix scan loop を skip し、cache から flags を復元。
    int pfx_slot = (int)(start_pc & PFXCACHE_MASK);
    int pfx_info = pfx_cache_info[pfx_slot];
    if( pfx_cache_rip[pfx_slot] == start_pc && (pfx_info & PFX_VALID) != 0
        && pfx_cache_b0[pfx_slot] == fetchInsnByte(start_pc) ) {  // issue #188: SMC で先頭バイトが変われば再スキャン
      rex_w       = (pfx_info & PFX_REX_W) != 0;
      rex_r       = (pfx_info & PFX_REX_R) != 0;
      rex_x       = (pfx_info & PFX_REX_X) != 0;
      rex_b       = (pfx_info & PFX_REX_B) != 0;
      rex_present = (pfx_info & PFX_REX_PRESENT) != 0;
      op66        = (pfx_info & PFX_OP66) != 0;
      opF2        = (pfx_info & PFX_OPF2) != 0;
      fs_prefix   = (pfx_info & PFX_FS) != 0;
      lockPrefix  = (pfx_info & PFX_LOCK) != 0;
      pc = start_pc + (pfx_info & PFX_OFFSET_MASK);
      b0 = fetchInsnByte(pc);
      // 共通の F3 prefix / opcode dispatch 経路に jump (goto 替わりに else 落とす)
    } else {
      b0 = fetchInsnByte(pc);
      int firstByte = b0;  // issue #188: start_pc 先頭バイト (fill で保存し SMC 検出に使う)

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
        case 0xF0: lockPrefix=true; pc++; b0=fetchInsnByte(pc); break;  // LOCK (issue #113 H3: atomic RMW)
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
        if( lockPrefix  ) new_info |= PFX_LOCK;
        pfx_cache_rip[pfx_slot]  = start_pc;
        pfx_cache_info[pfx_slot] = new_info;
        pfx_cache_b0[pfx_slot]   = firstByte;  // issue #188: SMC 検出用に先頭バイトも保存
      }
    }
    }  // End of cache MISS branch

    // issue #113 (H3): LOCK 付き命令の read-modify-write を atomic にする。
    //   旧実装は LOCK prefix を捨てており、worker 並走時に lock add/or/cmpxchg 等の
    //   RMW が lost-update して glibc malloc free-list / pthread mutex word を破壊し、
    //   壊れた pointer が Lisp slot に混入 → near-null wild deref で crash していた。
    //   命令全体を mem monitor 下で実行 (既存 CMPXCHG/XADD/XCHG sites と同一 lock、
    //   reentrant なので合成安全)。single-thread (multiThreadActive==0) は競合相手が
    //   いないので monitor を取らず perf neutral。EMULIN_NO_LOCK_ATOMIC=1 で A/B 無効化。
    //   plain mov store の non-tearing は H1 (Memory の VarHandle aligned access) が担保。
    this.curLockPrefix = lockPrefix;   // issue #567: lock inc/dec の atomic RMW 判定に使う
    if( lockPrefix && Memory.multiThreadActive != 0 && !Memory.NO_LOCK_ATOMIC ) {
      synchronized( mem ) {
        return dispatch_insn( pc, start_pc, b0, rex_w, rex_r, rex_b, rex_x, op66, opF2, fs_prefix );
      }
    }
    return dispatch_insn( pc, start_pc, b0, rex_w, rex_r, rex_b, rex_x, op66, opF2, fs_prefix );
  }

  // issue #113 (H3): decode_and_exec の opcode dispatch 本体。LOCK 付き命令のとき
  //   呼び元が synchronized(mem) で囲んで RMW を atomic 化する。
  private long dispatch_insn( long pc, long start_pc, int b0, boolean rex_w, boolean rex_r,
                             boolean rex_b, boolean rex_x, boolean op66, boolean opF2, boolean fs_prefix ) {
    if( PROFILE_OP ) OP_COUNT[ b0 ]++;

    // F3 prefix: ENDBR64 / REP string ops / F3 0F XX (extracted)
    if( b0 == 0xF3 )
      return exec_f3_prefix(pc, rex_w, rex_r, rex_b, rex_x, op66, fs_prefix);

    // 0F escape
    if( b0 == 0x0F )
      return exec_0f_escape(pc, rex_w, rex_r, rex_b, rex_x, op66, opF2, fs_prefix);

    // REPNE (F2) CMPS/SCAS (issue #519/#525 の string op 一般化): 旧実装は F2 prefix を
    //   捨てて単発形の case に落ち、RCX を無視して 1 回だけ実行していた。ZF=1 (一致) で停止。
    //   strchr/memchr イディオム (repne scasb) 等が対象。
    if( opF2 && (b0==0xA6 || b0==0xA7 || b0==0xAE || b0==0xAF) ) {
      int sz = (b0==0xA6 || b0==0xAE) ? 1 : (rex_w ? 8 : op66 ? 2 : 4);
      repCmpsScas( b0==0xA6 || b0==0xA7, sz, false );
      return pc+1;
    }

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
        return exec_mov_r_imm(pc, b0, rex_w, rex_b, op66);
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
      case 0xF4: {
        // issue #597: HLT は ring0 専用命令で、ユーザモード (CPL=3) で実行すると実機 Linux /
        //   native backend (KVM) は #GP (vector 13) → SIGSEGV (si_code=SI_KERNEL, si_addr=0)
        //   をプロセスに配送する (NativeCpuBackend.java の vec==13 分岐参照)。旧実装は
        //   診断メッセージ無しで process.set_exit_flag() のみ呼び「静かな exit(0)」に
        //   していたため、本来クラッシュすべき状況が見えないまま消えていた (codex 0.77.0+
        //   の起動が software backend でだけ無音失敗する原因、issue #597)。native backend /
        //   実機と挙動を合わせ、他の unsupported opcode 系と同じく診断を出してから
        //   SIGSEGV 終了させる (SIGSEGV ハンドラ登録済みなら deliverSegvToHandler 経由で
        //   guest に配送、CoreType3 で SI_KERNEL/si_addr=0 を強制)。
        process.println("Cpu64: HLT(0xF4) in user mode at rip=0x"+Long.toHexString(pc)
          +" — #GP -> SIGSEGV (see issue #597)");
        process.term_sig = Signal.SIGSEGV;
        throw new Memory.SegfaultException( pc, 3 );  // siCode=3: 強制 SI_KERNEL (privileged-insn fault)
      }
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
      // JRCXZ / JECXZ rel8 (E3)。issue #521: 67 prefix (addr32) 付きは ECX (下位32bit) のみで
      //   判定する (旧実装は常に RCX 全 64bit を見ており JECXZ が JRCXZ 化していた)。
      case 0xE3: {
        byte rel8=mem.load8(pc+1);
        long cval = hasAddr32Prefix(start_pc, pc) ? (r64[R_RCX] & 0xFFFFFFFFL) : r64[R_RCX];
        return cval==0 ? pc+2+rel8 : pc+2;
      }
      // LOOP (E2) / LOOPE (E1) / LOOPNE (E0) rel8 (issue #520: 旧実装は case 無し = unknown opcode
      //   でゲストプロセスが死んでいた)。(R|E)CX-- してから RCX!=0 (+ E1: ZF==1 / E0: ZF==0) で分岐。
      //   ZF はデクリメントで変化しない (SDM)。67 prefix 時は ECX counter (32bit 書込 = zero-extend)。
      case 0xE0: case 0xE1: case 0xE2: {
        byte rel8=mem.load8(pc+1);
        long c;
        if( hasAddr32Prefix(start_pc, pc) ) { c=(r64[R_RCX]-1)&0xFFFFFFFFL; r64[R_RCX]=c; }
        else                                { c=r64[R_RCX]-1;               r64[R_RCX]=c; }
        boolean take = (c != 0);
        if(      b0==0xE1 ) take = take && zf==1;   // LOOPE
        else if( b0==0xE0 ) take = take && zf==0;   // LOOPNE
        return take ? pc+2+rel8 : pc+2;
      }
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
        // issue #536: 16bit 形 (66 prefix) は下位 16bit のみ交換し上位 48bit 保存 (旧実装は
        //   op66 を見ず 32bit 形として実行 = 32bit 交換 + 上位ゼロ化。ModRM 形 0x87 は元々正しい)。
        else if(op66){
          long t = r64[R_RAX] & 0xFFFFL;
          r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | (r64[reg] & 0xFFFFL);
          r64[reg]   = (r64[reg]   & ~0xFFFFL) | t;
        }
        else     { long t=r64[R_RAX]&0xFFFFFFFFL; r64[R_RAX]=r64[reg]&0xFFFFFFFFL; r64[reg]=t; }
        return pc+1;
      }
      case 0x9B: return pc+1;  // FWAIT/WAIT — NOP
      // LAHF/SAHF (issue #518): AH ↔ 下位フラグ (SF:ZF:0:AF:0:PF:1:CF)。64bit モードでも
      //   ほぼ全 CPU で使用可 (CPUID.80000001H:ECX.LAHF-SAHF)。旧実装は case 無しで
      //   unknown opcode 0x9e/0x9f としてゲストプロセスが死んでいた。
      case 0x9E: {  // SAHF: AH → SF/ZF/AF/PF/CF (OF は不変)
        int ah = (int)(r64[R_RAX] >> 8) & 0xFF;
        sf=(ah>>7)&1; zf=(ah>>6)&1; af=(ah>>4)&1; pf=(ah>>2)&1; cf=ah&1;
        return pc+1;
      }
      case 0x9F: {  // LAHF: SF:ZF:0:AF:0:PF:1:CF → AH (bit1 は常に 1)
        int ah = (sf<<7)|(zf<<6)|(af<<4)|(pf<<2)|0x2|cf;
        r64[R_RAX] = (r64[R_RAX] & ~0xFF00L) | ((long)ah << 8);
        return pc+1;
      }
      // PUSHFQ / POPFQ (issue #221 step 3d-2c-32: node/V8 が使用、従来 unknown opcode 0x9d)。
      //   RFLAGS は実 CPU の architectural layout で構成する (native backend は実 CPU で実行する
      //   ので、layout を偽ると native==software oracle が成立しない)。bit1 は常に 1、IF(bit9) は
      //   user mode (CPL=3) では常に 1 に見える。POPFQ の TF/IF/IOPL 等システムフラグは CPL=3 で
      //   実 CPU が黙って無視するので反映しない。AF/DF は software の追跡が部分的 (STD は NOP、
      //   AF は一部 ALU のみ) だが、push→pop の round-trip は field 経由で保存される。
      //   66 prefix (16-bit PUSHF/POPF) は未対応 — rsp を黙って壊さず unknown 報告に落とす。
      case 0x9C: {  // PUSHFQ
        if( op66 ) break;
        long efl = 2L
                 | ((cf != 0) ? 0x001L : 0)
                 | ((pf != 0) ? 0x004L : 0)
                 | ((af != 0) ? 0x010L : 0)
                 | ((zf != 0) ? 0x040L : 0)
                 | ((sf != 0) ? 0x080L : 0)
                 | 0x200L
                 | ((df != 0) ? 0x400L : 0)
                 | ((of != 0) ? 0x800L : 0);
        push64( efl );
        return pc+1;
      }
      case 0x9D: {  // POPFQ
        if( op66 ) break;
        long efl = pop64();
        cf = (int)(efl      ) & 1;
        pf = (int)(efl >>  2) & 1;
        af = (int)(efl >>  4) & 1;
        zf = (int)(efl >>  6) & 1;
        sf = (int)(efl >>  7) & 1;
        df = (int)(efl >> 10) & 1;
        of = (int)(efl >> 11) & 1;
        return pc+1;
      }
      case 0xD8: case 0xD9: case 0xDA: case 0xDB:
      case 0xDC: case 0xDD: case 0xDE: case 0xDF:
        return exec_x87_escape(pc, b0, rex_r, rex_b, rex_x, fs_prefix);
      // 単独 string ops (REP 無し) — 1 回だけ転送。F3 prefix 経由の REP path
      // は別経路。issue #519: DF (Direction Flag) を見て ± 方向に進む (旧: forward 固定)。
      case 0xA4: {  // MOVSB
        long st = (df!=0) ? -1 : 1;
        mem.store8(r64[R_RDI], (int)mem.load8(r64[R_RSI]));
        r64[R_RDI]+=st; r64[R_RSI]+=st;
        return pc+1;
      }
      case 0xA5: {  // MOVSW/D/Q
        long w = rex_w ? 8 : op66 ? 2 : 4, st = (df!=0) ? -w : w;
        if( rex_w )     mem.store64(r64[R_RDI], mem.load64(r64[R_RSI]));
        else if( op66 ) mem.store16(r64[R_RDI], (short)mem.load16(r64[R_RSI]));
        else            mem.store32(r64[R_RDI], mem.load32(r64[R_RSI]));
        r64[R_RDI]+=st; r64[R_RSI]+=st;
        return pc+1;
      }
      case 0xAA:  // STOSB
        mem.store8(r64[R_RDI], (int)(r64[R_RAX] & 0xFF));
        r64[R_RDI] += (df!=0) ? -1 : 1;
        return pc+1;
      case 0xAB: {  // STOSW/D/Q
        long w = rex_w ? 8 : op66 ? 2 : 4;
        if( rex_w )     mem.store64(r64[R_RDI], r64[R_RAX]);
        else if( op66 ) mem.store16(r64[R_RDI], (short)(r64[R_RAX] & 0xFFFF));
        else            mem.store32(r64[R_RDI], (int)r64[R_RAX]);
        r64[R_RDI] += (df!=0) ? -w : w;
        return pc+1;
      }
      case 0xAC:  // LODSB
        r64[R_RAX] = (r64[R_RAX] & ~0xFFL) | ((long)mem.load8(r64[R_RSI]) & 0xFFL);
        r64[R_RSI] += (df!=0) ? -1 : 1;
        return pc+1;
      case 0xAD: {  // LODSW/D/Q
        long w = rex_w ? 8 : op66 ? 2 : 4;
        if( rex_w )     r64[R_RAX] = mem.load64(r64[R_RSI]);
        else if( op66 ) r64[R_RAX] = (r64[R_RAX] & ~0xFFFFL) | (mem.load16(r64[R_RSI]) & 0xFFFFL);
        else            r64[R_RAX] = mem.load32(r64[R_RSI]) & 0xFFFFFFFFL;
        r64[R_RSI] += (df!=0) ? -w : w;
        return pc+1;
      }
      // CMPSB/W/D/Q (0xA6/0xA7) と SCASB/W/D/Q (0xAE/0xAF) — REP 無し単独形。
      //   フラグは SUB (CMP) 相当を sbb*(a,b,0) で立てる (cf/zf/sf/of 正確)。
      //   Bun/JSC の JIT 出力で出現 (claude --help で 0xAE 未実装 unknown opcode crash)。
      case 0xA6: {  // CMPSB — CMP [RSI],[RDI] (= [RSI]-[RDI])
        long st = (df!=0) ? -1 : 1;
        sbb8( mem.load8(r64[R_RSI]) & 0xFFL, mem.load8(r64[R_RDI]) & 0xFFL, 0 );
        r64[R_RSI]+=st; r64[R_RDI]+=st;
        return pc+1;
      }
      case 0xA7: {  // CMPSW/D/Q
        long w = rex_w ? 8 : op66 ? 2 : 4, st = (df!=0) ? -w : w;
        if( rex_w )     sbb64( mem.load64(r64[R_RSI]), mem.load64(r64[R_RDI]), 0 );
        else if( op66 ) sbb16( mem.load16(r64[R_RSI]) & 0xFFFFL, mem.load16(r64[R_RDI]) & 0xFFFFL, 0 );
        else            sbb32( mem.load32(r64[R_RSI]) & 0xFFFFFFFFL, mem.load32(r64[R_RDI]) & 0xFFFFFFFFL, 0 );
        r64[R_RSI]+=st; r64[R_RDI]+=st;
        return pc+1;
      }
      case 0xAE:  // SCASB — CMP AL,[RDI]
        sbb8( r64[R_RAX] & 0xFFL, mem.load8(r64[R_RDI]) & 0xFFL, 0 );
        r64[R_RDI] += (df!=0) ? -1 : 1;
        return pc+1;
      case 0xAF: {  // SCASW/D/Q — CMP (r/e)AX,[RDI]
        long w = rex_w ? 8 : op66 ? 2 : 4;
        if( rex_w )     sbb64( r64[R_RAX], mem.load64(r64[R_RDI]), 0 );
        else if( op66 ) sbb16( r64[R_RAX] & 0xFFFFL, mem.load16(r64[R_RDI]) & 0xFFFFL, 0 );
        else            sbb32( r64[R_RAX] & 0xFFFFFFFFL, mem.load32(r64[R_RDI]) & 0xFFFFFFFFL, 0 );
        r64[R_RDI] += (df!=0) ? -w : w;
        return pc+1;
      }
      // MOV accumulator ↔ moffs (絶対アドレス、64-bit mode の moffs は 8 byte)
      case 0xA0: { long mo=loadImm64(pc+1); r64[R_RAX]=(r64[R_RAX]&~0xFFL)|(mem.load8(mo)&0xFFL); return pc+9; }       // MOV AL, moffs8
      case 0xA1: { long mo=loadImm64(pc+1);                                                                            // MOV eAX/rAX, moffs
        if(rex_w)      r64[R_RAX]=mem.load64(mo);
        else if(op66)  r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(mem.load16(mo)&0xFFFFL);
        else           r64[R_RAX]=mem.load32(mo)&0xFFFFFFFFL;  // 32-bit dest は zero-extend
        return pc+9; }
      case 0xA2: { long mo=loadImm64(pc+1); mem.store8(mo,(int)(r64[R_RAX]&0xFF)); return pc+9; }                      // MOV moffs8, AL
      case 0xA3: { long mo=loadImm64(pc+1);                                                                            // MOV moffs, eAX/rAX
        if(rex_w)      mem.store64(mo, r64[R_RAX]);
        else if(op66)  mem.store16(mo, (short)r64[R_RAX]);
        else           mem.store32(mo, (int)r64[R_RAX]);
        return pc+9; }
      case 0x8F: {  // POP r/m64 — pop してから r/m に格納 (rsp は格納先 EA 計算前に増加)
        long val = mem.load64(r64[R_RSP]);
        r64[R_RSP] += 8;
        long n = decodeModRM(pc+1, rex_r, rex_b, rex_x, false); fixEA(n, fs_prefix);
        if( mrm_mod==3 ) r64[mrm_rm] = val;
        else             mem.store64(mrm_ea, val);
        return n;
      }
      // flag 操作命令 (1 byte)
      case 0xF5: cf ^= 1;       return pc+1;  // CMC
      case 0xF8: cf = 0;        return pc+1;  // CLC
      case 0xF9: cf = 1;        return pc+1;  // STC
      // CLD/STD (issue #519): Direction Flag を実際に更新する。旧実装は両方 NOP で、
      //   STD 後も pushfq の bit10 が変化せず、string ops も常に forward だった。
      //   string ops (単発 + REP) は df を見て ± 方向に進む (本 case 群と exec_f3_prefix)。
      case 0xFC: df = 0; return pc+1;  // CLD
      case 0xFD: df = 1; return pc+1;  // STD
      // CLI/STI: 割り込みフラグ。user-mode emulation では NOP。
      case 0xFA: return pc+1;  // CLI
      case 0xFB: return pc+1;  // STI
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
    long result;
    // issue #113 GIL: syscall は futex wait/read/poll/wait4/join 等で block しうる。lock を
    //   保持したまま待つと waker が lock を取れず deadlock するため、syscall 中は release し
    //   復帰後に再取得する。call_amd64 は register を値で受け取り、結果は再取得後に
    //   r64[R_RAX] へ書くので guest CPU 状態は GIL 保護下のまま。
    if( Memory.GLOBAL_LOCK ) mem.execLock.unlock();
    try {
      result = syscall64.call_amd64(
        syscall_no, r64[R_RDI], r64[R_RSI], r64[R_RDX],
        r64[10],    r64[8],     r64[9] );
    } finally {
      if( Memory.GLOBAL_LOCK ) mem.execLock.lock();
    }
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
      // issue #562: SA_RESTART があっても「決して再開されない」syscall (man 7 signal) は
      //   除外し、EINTR をユーザに返す。nanosleep/clock_nanosleep/poll/select/epoll_wait 系が
      //   該当 (tokio/glibc はこれらの EINTR を自前で処理する。再開すると rem が壊れ寝続ける)。
      if( sig >= 0 && process.has_sa_restart( sig ) && !syscallNeverRestarts( (int)syscall_no ) ) {
        r64[R_RAX] = syscall_no;
        return next_pc - 2;
      }
    }
    return next_pc;
  }

  // issue #562: SA_RESTART があっても常に EINTR で中断する syscall (man 7 signal の
  //   "The following interfaces are never restarted after being interrupted by a signal
  //   handler, regardless of the use of SA_RESTART")。poll/select/nanosleep 族。
  private static boolean syscallNeverRestarts( int sysno ) {
    switch( sysno ) {
      case 35:   // nanosleep
      case 230:  // clock_nanosleep
      case 7:    // poll
      case 271:  // ppoll
      case 23:   // select
      case 270:  // pselect6
      case 232:  // epoll_wait
      case 281:  // epoll_pwait
        return true;
      default:
        return false;
    }
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
