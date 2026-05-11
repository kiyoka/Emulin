package emulin.jit;

import emulin.Cpu64;
import emulin.Memory;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Phase 34-A3 step 9: x86-64 → Java bytecode 翻訳器 (basic-block 版)。
 *
 * step 1-8 は per-RIP 1-instruction class を生成していたが、5MB sha256sum で
 * +66% slowdown する致命問題があった。原因は ~1000 個の異なる CompiledInsn
 * 実装 class が ci.execute(this) call site を megamorphic 化し、JVM dispatch
 * cost が optimized interpreter pass を上回ったこと。
 *
 * 本 phase では「basic block (= 次の制御転送までの命令列) を 1 class にまとめる」
 * 方針に refactor。各 block の execute() は内部に複数命令ぶんの bytecode を
 * 持ち、megamorphism は「翻訳済み命令数」ではなく「block 数」に縮退する。
 *
 * 翻訳 flow:
 *   1. lookup(rip) で entry_rip == rip の block を探す。HIT なら execute()
 *   2. miss なら interpreter で 1 命令進めた後、tryCompileBlock(start_pc, mem,
 *      insn0_bytes, insn0_len) を呼ぶ
 *   3. compileBlock は entry から forward scan で命令を chain して 1 class
 *      に詰める。終端 (RET/JMP/Jcc/CALL) または unknown opcode で停止
 *   4. 終端なしで停止した場合は最後に「return current_pc」を出して
 *      interpreter にフォールバック
 *
 * EMULIN_USE_JIT=1 で有効化 (default off)。
 */
public final class Translator {
  public static final boolean ENABLED =
    System.getenv("EMULIN_USE_JIT") != null;

  // 性能切り分け用: 1=lookup だけして compile/execute はしない (lookup overhead 単独計測)
  private static final boolean LOOKUP_ONLY =
    System.getenv("EMULIN_JIT_LOOKUP_ONLY") != null;

  // emitInsnFragment の戻り値:
  //   UNKNOWN  ... 翻訳不能 (caller は block を打ち切って interpreter に戻す)
  //   NONTERM  ... 翻訳成功、命令は非終端 (block を続けてよい)
  //   TERM     ... 翻訳成功、命令は終端 (LRETURN を emit 済み、block 終了)
  private static final int EMIT_UNKNOWN = 0;
  private static final int EMIT_NONTERM = 1;
  private static final int EMIT_TERM    = 2;

  // 1 block あたり最大命令数 (生成 method size 暴走 + class 数縮減のため)
  private static final int MAX_BLOCK_INSNS = 16;

  // 同一 class 名は重複定義不可なので、生成 class ごとに unique 名を作る。
  private final java.util.concurrent.atomic.AtomicLong serial = new java.util.concurrent.atomic.AtomicLong();
  private final GeneratedClassLoader loader = new GeneratedClassLoader();

  // entry_rip -> 翻訳済み block。SENTINEL_NOT_COMPILABLE は「翻訳不可」マーク。
  private static final CompiledInsn SENTINEL_NOT_COMPILABLE = cpu -> 0L;

  // 「重複コンパイル防止 + execve 時の clear」用の保守用 map (低頻度パス専用)。
  // 実 lookup hot path はこれを参照しない (下記 flat-array cache を使う)。
  private final ConcurrentHashMap<Long, CompiledInsn> cache = new ConcurrentHashMap<>();

  // hot path 用 flat-array cache。Cpu64.run() から 1 命令ごとに lookup される
  // ため ConcurrentHashMap.get(Long) の boxing + hash overhead を避ける必要が
  // ある。direct-mapped (rip & mask) の単純な open-address。conflict は
  // 上書き (LRU っぽい)。size は 64K entries。
  private static final int FLAT_CACHE_SIZE = 1 << 16;
  private static final int FLAT_CACHE_MASK = FLAT_CACHE_SIZE - 1;
  private final long[]         flatRips = new long[ FLAT_CACHE_SIZE ];
  private final CompiledInsn[] flatCi   = new CompiledInsn[ FLAT_CACHE_SIZE ];
  // Phase 34-A3 step 18: hot-path threshold。block entry を観測するたびに
  // flatHits[idx] を increment し、HOT_THRESHOLD を超えたタイミングで
  // 初めて compile する。cold block (visited <= threshold) は compile せず
  // interpreter のままにすることで、compile overhead を hot code に
  // 集中させる。
  private static final int HOT_THRESHOLD = 16;
  private final int[]          flatHits = new int[ FLAT_CACHE_SIZE ];

  private static int flatIndex( long rip ) {
    // 命令アドレスは下位 4 bit のばらつきが小さい (1-15 byte 命令長) ので、
    // 上位 bit を mix してから mask する。
    long h = rip ^ (rip >>> 16);
    return (int)(h & FLAT_CACHE_MASK);
  }

  // 統計 (全 Translator instance 合計、static)
  public static java.util.concurrent.atomic.AtomicLong compile_hits      = new java.util.concurrent.atomic.AtomicLong();
  public static java.util.concurrent.atomic.AtomicLong compile_attempts  = new java.util.concurrent.atomic.AtomicLong();
  public static java.util.concurrent.atomic.AtomicLong compile_successes = new java.util.concurrent.atomic.AtomicLong();
  public static java.util.concurrent.atomic.AtomicLong translated_insns  = new java.util.concurrent.atomic.AtomicLong();

  /** プロセス置換 (execve) で旧 binary の compile 結果を捨てる用。 */
  public void clear() {
    cache.clear();
    java.util.Arrays.fill( flatRips, 0L );
    java.util.Arrays.fill( flatCi,   null );
    java.util.Arrays.fill( flatHits, 0 );
  }

  static {
    if( ENABLED ) {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        long hits      = compile_hits.get();
        long attempts  = compile_attempts.get();
        long successes = compile_successes.get();
        long insns     = translated_insns.get();
        double success_rate    = attempts  > 0 ? 100.0 * successes / attempts : 0.0;
        double avg_block_insns = successes > 0 ? (double) insns / successes  : 0.0;
        System.err.println( "===== EMULIN_USE_JIT (basic-block) =====" );
        System.err.println( String.format(
          "compile attempts:   %d", attempts ) );
        System.err.println( String.format(
          "compile successes:  %d (%.2f%%)", successes, success_rate ) );
        System.err.println( String.format(
          "translated insns:   %d (avg %.2f insns/block)", insns, avg_block_insns ) );
        System.err.println( String.format(
          "block executions:   %d (compiled block の実行回数)", hits ) );
        System.err.println( "========================================" );
      }, "EmulinJitStatsDump" ) );
    }
  }

  /**
   * tryCompileBlock を呼ぶ価値があるか軽量に判定する + hot 判定。
   *   - flat cache の同 slot に rip が登録済み (compiled / SENTINEL いずれ
   *     も flatCi に格納) → false。既決定の RIP に対する重複試行を防ぐ
   *   - そうでなければ flatHits をインクリメント、HOT_THRESHOLD 未満なら
   *     false (まだ cold、interpreter で進める)、超えたら true
   *
   * これにより 1 回しか実行されない RIP は compile されず、hot block
   * (16 回以上 entry された) のみ compile 対象になる。SENTINEL も flat-cache
   * に保存することで HashMap.containsKey を hot path から完全排除。
   */
  public boolean shouldAttemptCompile( long rip ) {
    int idx = flatIndex( rip );
    if( flatRips[ idx ] == rip && flatCi[ idx ] != null ) return false;
    // hot counter: idx を rip 専用カウンタとして使う。conflict (別 rip が
    // 同じ slot を hash した場合) は許容 — 結果として早く hot 判定されるだけ
    // で correctness には影響しない。
    int hits = ++flatHits[ idx ];
    return hits >= HOT_THRESHOLD;
  }

  /**
   * cache を見て、翻訳済み block があれば返す。未翻訳または翻訳不可なら null。
   * Cpu64.run() の hot path から 1 命令ごとに呼ばれるため、flat-array cache
   * への 2 array load + 1 比較だけで済むよう設計。
   */
  public CompiledInsn lookup( long rip ) {
    int idx = flatIndex( rip );
    if( flatRips[ idx ] == rip ) {
      CompiledInsn ci = flatCi[ idx ];
      if( ci != null && ci != SENTINEL_NOT_COMPILABLE ) {
        compile_hits.incrementAndGet();
        return ci;
      }
    }
    return null;
  }

  /**
   * entry_rip から始まる basic block を翻訳しようと試みる。
   *
   * @param entry_rip   block 開始 rip
   * @param mem         以降の命令を read するための Memory
   * @param insn0_bytes 最初の命令の byte 列 (interpreter で既に decode 済み)
   * @param insn0_len   最初の命令の byte 長
   */
  public void tryCompileBlock( long entry_rip, Memory mem, byte[] insn0_bytes, int insn0_len ) {
    if( LOOKUP_ONLY ) return;  // 性能切り分け: compile/execute path 無効化
    // 重複コンパイル防止: flat cache にも HashMap にも無いときだけ進む。
    // (flat cache は overwrite で entry が消えていることがあるので念のため
    //  HashMap で確認。HashMap は cold path でのみ参照される。)
    {
      int idx = flatIndex( entry_rip );
      if( flatRips[ idx ] == entry_rip && flatCi[ idx ] != null ) return;
    }
    if( cache.containsKey( entry_rip ) ) return;
    compile_attempts.incrementAndGet();
    CompiledInsn ci;
    try {
      ci = compileBlock( entry_rip, mem, insn0_bytes, insn0_len );
    } catch( LinkageError e ) {
      if( asm_missing_warned.compareAndSet( false, true ) ) {
        System.err.println( "Translator: ASM not on classpath (" + e.getClass().getSimpleName()
          + "), JIT disabled; running interpreter only" );
      }
      cache.put( entry_rip, SENTINEL_NOT_COMPILABLE );
      return;
    } catch( Throwable t ) {
      if( generic_compile_warned.compareAndSet( false, true ) ) {
        System.err.println( "Translator: unexpected error at rip=0x"
          + Long.toHexString( entry_rip ) + ": " + t );
      }
      cache.put( entry_rip, SENTINEL_NOT_COMPILABLE );
      return;
    }
    int idx = flatIndex( entry_rip );
    if( ci == null ) {
      cache.put( entry_rip, SENTINEL_NOT_COMPILABLE );
      // step 18: SENTINEL も flat-cache に書く。shouldAttemptCompile が
      // HashMap を見ずに済むようにするため。lookup() は SENTINEL を null と
      // 同じ扱いで filter するので問題なし。
      flatRips[ idx ] = entry_rip;
      flatCi  [ idx ] = SENTINEL_NOT_COMPILABLE;
    } else {
      compile_successes.incrementAndGet();
      cache.put( entry_rip, ci );
      flatRips[ idx ] = entry_rip;
      flatCi  [ idx ] = ci;
    }
  }

  private final java.util.concurrent.atomic.AtomicBoolean asm_missing_warned     = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean generic_compile_warned = new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * compileBlock 本体。少なくとも 1 命令翻訳できれば CompiledInsn を返す。
   * 1 命令も翻訳できなかった場合 (= 最初の命令が unknown) は null。
   */
  private CompiledInsn compileBlock( long entry_rip, Memory mem,
                                     byte[] insn0_bytes, int insn0_len ) {
    String className = "emulin.jit.gen.Block_" + Long.toHexString(entry_rip) + "_" + serial.incrementAndGet();
    String internalName = className.replace('.', '/');

    ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS );
    cw.visit( Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
             "java/lang/Object", new String[]{ "emulin/jit/CompiledInsn" } );

    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "<init>", "()V", null, null );
      mv.visitCode();
      mv.visitVarInsn( Opcodes.ALOAD, 0 );
      mv.visitMethodInsn( Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false );
      mv.visitInsn( Opcodes.RETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }

    MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "execute", "(Lemulin/Cpu64;)J", null, null );
    mv.visitCode();

    long pc = entry_rip;
    int translatedCount = 0;
    byte[] bytes = insn0_bytes;
    int len = insn0_len;

    for( int i = 0; i < MAX_BLOCK_INSNS; i++ ) {
      int result = emitInsnFragment( mv, pc, bytes, len );
      if( result == EMIT_UNKNOWN ) break;
      translatedCount++;
      pc += len;
      if( result == EMIT_TERM ) {
        // 終端命令 (LRETURN emit 済み) — method 完成
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();
        cw.visitEnd();
        translated_insns.addAndGet( translatedCount );
        return loadClass( className, cw.toByteArray() );
      }
      // 非終端: 次命令を mem から read
      int nextLen = peekInsnLength( mem, pc );
      if( nextLen <= 0 ) break;
      try {
        byte[] nb = new byte[ nextLen ];
        for( int j = 0; j < nextLen; j++ ) nb[j] = mem.load8( pc + j );
        bytes = nb;
        len   = nextLen;
      } catch( Throwable t ) {
        break;  // unmapped memory 等で stop
      }
    }

    if( translatedCount == 0 ) {
      // 1 命令も翻訳できなかった — block 自体作らない
      return null;
    }

    // 終端なしで loop 抜け: 最後に「return pc」を emit して interpreter に戻す
    mv.visitLdcInsn( pc );
    mv.visitInsn( Opcodes.LRETURN );
    mv.visitMaxs( 0, 0 );
    mv.visitEnd();
    cw.visitEnd();

    translated_insns.addAndGet( translatedCount );
    return loadClass( className, cw.toByteArray() );
  }

  private CompiledInsn loadClass( String className, byte[] classBytes ) {
    try {
      Class<?> cls = loader.define( className, classBytes );
      return (CompiledInsn) cls.getDeclaredConstructor().newInstance();
    } catch( Exception e ) {
      System.err.println( "Translator: failed to load generated class " + className + ": " + e );
      return null;
    }
  }

  /**
   * mem の pc 位置にある命令の byte 長を返す。-1 = 翻訳不能。
   * compileBlock 内の forward scan で使う。emitInsnFragment と必ず同期して
   * いる必要がある (length が違うと bytes の中身がズレて誤訳する)。
   */
  private int peekInsnLength( Memory mem, long pc ) {
    int b0;
    try { b0 = mem.load8( pc ) & 0xFF; }
    catch( Throwable t ) { return -1; }
    if( b0 == 0xC3 ) return 1;
    if( b0 == 0xEB ) return 2;
    if( (b0 & 0xF0) == 0x70 ) return 2;
    if( b0 == 0xE9 || b0 == 0xE8 ) return 5;
    // 0x0F 80-8F: Jcc rel32 — 6 byte (0F prefix + opcode + 4-byte disp)
    if( b0 == 0x0F ) {
      int b1;
      try { b1 = mem.load8( pc + 1 ) & 0xFF; }
      catch( Throwable t ) { return -1; }
      if( (b1 & 0xF0) == 0x80 ) return 6;
      return -1;
    }
    if( (b0 & 0xF0) == 0x40 ) {
      int op;
      try { op = mem.load8( pc + 1 ) & 0xFF; }
      catch( Throwable t ) { return -1; }
      if( (b0 & 0x08) == 0 ) return -1;        // REX.W not set: 32-bit form は未対応
      if( (op & 0xF8) == 0xB8 ) return 10;     // MOV r64, imm64
      if( op == 0x89 || op == 0x8B
       || op == 0x01 || op == 0x03   // ADD r/m,r / r,r/m
       || op == 0x09 || op == 0x0B   // OR
       || op == 0x21 || op == 0x23   // AND
       || op == 0x29 || op == 0x2B   // SUB
       || op == 0x31 || op == 0x33   // XOR
       || op == 0x39 || op == 0x3B   // CMP
       || op == 0x85                 // TEST r/m,r
       || op == 0x63                 // MOVSXD r64, r/m32
        ) {
        int modrm;
        try { modrm = mem.load8( pc + 2 ) & 0xFF; }
        catch( Throwable t ) { return -1; }
        int mod = (modrm >> 6) & 3;
        if( mod == 3 ) return 3;               // register form
        // Phase 34-A3 step 15/16/19: 0x89/0x8B の memory operand
        if( op == 0x89 || op == 0x8B ) {
          int rm_lo = modrm & 7;
          if( rm_lo == 4 ) {
            // SIB byte (step 19): no-index (REX.X=0 + SIB.index=4) のみ対応
            return memoryOpLengthSIB( mem, pc, mod, b0 );
          }
          // mod==0 && rm_lo==5: RIP-rel (step 16)、length 7
          if( mod == 0 && rm_lo == 5 ) return 7;
          // mod==0: [base], 3 byte
          // mod==1: [base+disp8], 4 byte
          // mod==2: [base+disp32], 7 byte
          if( mod == 0 ) return 3;
          if( mod == 1 ) return 4;
          if( mod == 2 ) return 7;
        }
        return -1;                             // 他 opcode の memory operand 未対応
      }
      // 0x8D LEA r64, m — memory operand 必須 (mod != 3)
      if( op == 0x8D ) {
        int modrm;
        try { modrm = mem.load8( pc + 2 ) & 0xFF; }
        catch( Throwable t ) { return -1; }
        int mod = (modrm >> 6) & 3;
        if( mod == 3 ) return -1;              // LEA mod==3 は invalid (実機で #UD)
        int rm_lo = modrm & 7;
        if( rm_lo == 4 ) {
          return memoryOpLengthSIB( mem, pc, mod, b0 );
        }
        if( mod == 0 && rm_lo == 5 ) return 7; // RIP-rel
        if( mod == 0 ) return 3;
        if( mod == 1 ) return 4;
        if( mod == 2 ) return 7;
        return -1;
      }
      // REX.W + 0F + 4x + ModRM (mod==3): CMOVcc r64, r64 — 4 byte
      if( op == 0x0F ) {
        int b2;
        try { b2 = mem.load8( pc + 2 ) & 0xFF; }
        catch( Throwable t ) { return -1; }
        if( (b2 & 0xF0) == 0x40 ) {
          int modrm;
          try { modrm = mem.load8( pc + 3 ) & 0xFF; }
          catch( Throwable t ) { return -1; }
          if( (modrm >> 6) == 3 ) return 4;
        }
        return -1;
      }
      // 0x83 /n + imm8: ALU r/m64, imm8 (sign-extended)
      // mod==3 のとき REX + 0x83 + ModRM + imm8 = 4 byte
      if( op == 0x83 ) {
        int modrm;
        try { modrm = mem.load8( pc + 2 ) & 0xFF; }
        catch( Throwable t ) { return -1; }
        if( (modrm >> 6) == 3 ) {
          int sub = (modrm >> 3) & 7;
          // ADC(2) / SBB(3) は CF input が要るため当面 skip
          if( sub == 2 || sub == 3 ) return -1;
          return 4;
        }
        return -1;
      }
      // 0x81 /n + imm32: ALU r/m64, imm32 (sign-extended)
      // mod==3 のとき REX + 0x81 + ModRM + imm32 = 7 byte
      if( op == 0x81 ) {
        int modrm;
        try { modrm = mem.load8( pc + 2 ) & 0xFF; }
        catch( Throwable t ) { return -1; }
        if( (modrm >> 6) == 3 ) {
          int sub = (modrm >> 3) & 7;
          if( sub == 2 || sub == 3 ) return -1;
          return 7;
        }
        return -1;
      }
    }
    return -1;
  }

  /**
   * 命令 1 個ぶんの bytecode fragment を mv に emit する。
   * peekInsnLength と必ず同期して length 指定すること。
   *
   * @return EMIT_UNKNOWN / EMIT_NONTERM / EMIT_TERM
   */
  private int emitInsnFragment( MethodVisitor mv, long pc, byte[] bytes, int length ) {
    if( length < 1 ) return EMIT_UNKNOWN;
    int b0 = bytes[0] & 0xFF;

    // ---------- 終端命令 ----------
    // 0xEB ib: JMP rel8
    if( b0 == 0xEB && length == 2 ) {
      long target = pc + 2 + (long)(byte)bytes[1];
      mv.visitLdcInsn( target );
      mv.visitInsn( Opcodes.LRETURN );
      return EMIT_TERM;
    }
    // 0xE9 id: JMP rel32
    if( b0 == 0xE9 && length == 5 ) {
      long target = pc + 5 + (long) loadDisp32( bytes, 1 );
      mv.visitLdcInsn( target );
      mv.visitInsn( Opcodes.LRETURN );
      return EMIT_TERM;
    }
    // 0x70-0x7F ib: Jcc rel8
    if( (b0 & 0xF0) == 0x70 && length == 2 ) {
      int cond = b0 & 0x0F;
      long takenTarget    = pc + 2 + (long)(byte)bytes[1];
      long notTakenTarget = pc + 2;
      emitJccBody( mv, cond, takenTarget, notTakenTarget );
      return EMIT_TERM;
    }
    // 0x0F 80-8F id: Jcc rel32 — 6 byte
    if( b0 == 0x0F && length == 6 ) {
      int b1 = bytes[1] & 0xFF;
      if( (b1 & 0xF0) == 0x80 ) {
        int cond = b1 & 0x0F;
        long takenTarget    = pc + 6 + (long) loadDisp32( bytes, 2 );
        long notTakenTarget = pc + 6;
        emitJccBody( mv, cond, takenTarget, notTakenTarget );
        return EMIT_TERM;
      }
      return EMIT_UNKNOWN;
    }
    // 0xC3: RET (near)
    //   long sp = cpu.r64[R_RSP];
    //   cpu.r64[R_RSP] = sp + 8;
    //   return cpu.mem.load64(sp);
    if( b0 == 0xC3 && length == 1 ) {
      final int R_RSP = 4;
      // long sp = cpu.r64[R_RSP];
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
      mv.visitLdcInsn( R_RSP );
      mv.visitInsn( Opcodes.LALOAD );
      mv.visitVarInsn( Opcodes.LSTORE, 2 );
      // cpu.r64[R_RSP] = sp + 8;
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
      mv.visitLdcInsn( R_RSP );
      mv.visitVarInsn( Opcodes.LLOAD, 2 );
      mv.visitLdcInsn( 8L );
      mv.visitInsn( Opcodes.LADD );
      mv.visitInsn( Opcodes.LASTORE );
      // return cpu.mem.load64(sp);
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/AbstractCpu", "mem", "Lemulin/Memory;" );
      mv.visitVarInsn( Opcodes.LLOAD, 2 );
      mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Memory", "load64", "(J)J", false );
      mv.visitInsn( Opcodes.LRETURN );
      return EMIT_TERM;
    }
    // 0xE8 id: CALL rel32
    //   long sp = cpu.r64[R_RSP] - 8;
    //   cpu.r64[R_RSP] = sp;
    //   cpu.mem.store64(sp, retAddr);
    //   return target;
    if( b0 == 0xE8 && length == 5 ) {
      final int R_RSP = 4;
      long retAddr = pc + 5;
      long target  = retAddr + (long) loadDisp32( bytes, 1 );
      // long sp = cpu.r64[R_RSP] - 8;
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
      mv.visitLdcInsn( R_RSP );
      mv.visitInsn( Opcodes.LALOAD );
      mv.visitLdcInsn( 8L );
      mv.visitInsn( Opcodes.LSUB );
      mv.visitVarInsn( Opcodes.LSTORE, 2 );
      // cpu.r64[R_RSP] = sp;
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
      mv.visitLdcInsn( R_RSP );
      mv.visitVarInsn( Opcodes.LLOAD, 2 );
      mv.visitInsn( Opcodes.LASTORE );
      // cpu.mem.store64(sp, retAddr);
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/AbstractCpu", "mem", "Lemulin/Memory;" );
      mv.visitVarInsn( Opcodes.LLOAD, 2 );
      mv.visitLdcInsn( retAddr );
      mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Memory", "store64", "(JJ)V", false );
      mv.visitLdcInsn( target );
      mv.visitInsn( Opcodes.LRETURN );
      return EMIT_TERM;
    }

    // ---------- REX prefix で始まる非終端命令 ----------
    if( (b0 & 0xF0) == 0x40 && length >= 2 ) {
      boolean rex_w = (b0 & 0x08) != 0;
      boolean rex_r = (b0 & 0x04) != 0;
      boolean rex_b = (b0 & 0x01) != 0;
      if( !rex_w ) return EMIT_UNKNOWN;        // 64-bit form のみ
      int op = bytes[1] & 0xFF;

      // REX.W + 0xB8+r + imm64: MOV r64, imm64 — 10 byte
      if( (op & 0xF8) == 0xB8 && length == 10 ) {
        int reg = (op & 7) | (rex_b ? 8 : 0);
        long imm64 = (long)(bytes[2] & 0xFF)
                   | ((long)(bytes[3] & 0xFF) <<  8)
                   | ((long)(bytes[4] & 0xFF) << 16)
                   | ((long)(bytes[5] & 0xFF) << 24)
                   | ((long)(bytes[6] & 0xFF) << 32)
                   | ((long)(bytes[7] & 0xFF) << 40)
                   | ((long)(bytes[8] & 0xFF) << 48)
                   | ((long)(bytes[9] & 0xFF) << 56);
        // cpu.r64[reg] = imm64;
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
        mv.visitLdcInsn( reg );
        mv.visitLdcInsn( imm64 );
        mv.visitInsn( Opcodes.LASTORE );
        return EMIT_NONTERM;
      }

      // REX.W + 0x89/0x8B + ModRM: MOV r64, r/m64 / MOV r/m64, r64
      //   mod==3: register form / mod=0/1/2: memory operand (SIB対応 step 19)
      if( op == 0x89 || op == 0x8B ) {
        int modrm = bytes[2] & 0xFF;
        int mod = (modrm >> 6) & 3;
        int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
        int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);

        if( mod == 3 && length == 3 ) {
          int srcReg = (op == 0x89) ? regField : rmField;
          int dstReg = (op == 0x89) ? rmField  : regField;
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( dstReg );
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( srcReg );
          mv.visitInsn( Opcodes.LALOAD );
          mv.visitInsn( Opcodes.LASTORE );
          return EMIT_NONTERM;
        }

        // memory operand: 解析して address-push bytecode を emit
        MemOp mo = decodeMemOp( bytes, length, pc, mod, rmField, b0 );
        if( mo == null ) return EMIT_UNKNOWN;

        if( op == 0x89 ) {
          // mem.store64(addr, r64[reg])
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/AbstractCpu", "mem", "Lemulin/Memory;" );
          emitMemAddr( mv, mo );
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( regField );
          mv.visitInsn( Opcodes.LALOAD );
          mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Memory", "store64", "(JJ)V", false );
        } else {
          // r64[reg] = mem.load64(addr)
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( regField );
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/AbstractCpu", "mem", "Lemulin/Memory;" );
          emitMemAddr( mv, mo );
          mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Memory", "load64", "(J)J", false );
          mv.visitInsn( Opcodes.LASTORE );
        }
        return EMIT_NONTERM;
      }

      // ---------- REX.W + 0x83 /n + imm8 or 0x81 /n + imm32: ALU r64, imm ----------
      //   sub-opcode は ModRM.reg field: 0=ADD 1=OR 2=ADC 3=SBB 4=AND 5=SUB 6=XOR 7=CMP
      //   ADC/SBB は CF input が要るため当面 skip
      if( (op == 0x83 || op == 0x81) ) {
        int needLen = (op == 0x83) ? 4 : 7;
        if( length != needLen ) return EMIT_UNKNOWN;
        int modrm = bytes[2] & 0xFF;
        if( (modrm >> 6) != 3 ) return EMIT_UNKNOWN;
        int sub = (modrm >> 3) & 7;
        if( sub == 2 || sub == 3 ) return EMIT_UNKNOWN;
        int rmField = (modrm & 7) | (rex_b ? 8 : 0);
        long imm;
        if( op == 0x83 ) imm = (long)(byte)bytes[3];               // imm8 sign-ext
        else             imm = (long) loadDisp32( bytes, 3 );      // imm32 sign-ext
        String helperName;
        switch( sub ) {
          case 0: helperName = "jitAdd64RI"; break;
          case 1: helperName = "jitOr64RI";  break;
          case 4: helperName = "jitAnd64RI"; break;
          case 5: helperName = "jitSub64RI"; break;
          case 6: helperName = "jitXor64RI"; break;
          case 7: helperName = "jitCmp64RI"; break;
          default: return EMIT_UNKNOWN;
        }
        // cpu.jitXxx64RI(rmField, imm);
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitLdcInsn( rmField );
        mv.visitLdcInsn( imm );
        mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Cpu64", helperName, "(IJ)V", false );
        return EMIT_NONTERM;
      }

      // ---------- REX.W + 0x8D + ModRM (mod != 3): LEA r64, m ----------
      //   r64[reg] = effective_address (memory access なし)
      if( op == 0x8D ) {
        int modrm = bytes[2] & 0xFF;
        int mod = (modrm >> 6) & 3;
        if( mod == 3 ) return EMIT_UNKNOWN;       // invalid
        int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
        int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);
        MemOp mo = decodeMemOp( bytes, length, pc, mod, rmField, b0 );
        if( mo == null ) return EMIT_UNKNOWN;
        // r64[regField] = address;
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
        mv.visitLdcInsn( regField );
        emitMemAddr( mv, mo );
        mv.visitInsn( Opcodes.LASTORE );
        return EMIT_NONTERM;
      }

      // ---------- REX.W + 0F + 4x + ModRM (mod==3): CMOVcc r64, r64 — 4 byte ----------
      //   cond = b2 & 0xF
      //   if (cpu.evalCond(cond)) cpu.r64[regField] = cpu.r64[rmField];
      if( op == 0x0F && length == 4 ) {
        int b2 = bytes[2] & 0xFF;
        if( (b2 & 0xF0) == 0x40 ) {
          int modrm = bytes[3] & 0xFF;
          if( (modrm >> 6) != 3 ) return EMIT_UNKNOWN;
          int cond = b2 & 0x0F;
          int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
          int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);
          Label skip = new Label();
          // if (!cpu.evalCond(cond)) goto skip;
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitLdcInsn( cond );
          mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Cpu64", "evalCond", "(I)Z", false );
          mv.visitJumpInsn( Opcodes.IFEQ, skip );
          // cpu.r64[regField] = cpu.r64[rmField];
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( regField );
          mv.visitVarInsn( Opcodes.ALOAD, 1 );
          mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
          mv.visitLdcInsn( rmField );
          mv.visitInsn( Opcodes.LALOAD );
          mv.visitInsn( Opcodes.LASTORE );
          mv.visitLabel( skip );
          return EMIT_NONTERM;
        }
        return EMIT_UNKNOWN;
      }

      // ---------- REX.W + 0x63 + ModRM (mod==3): MOVSXD r64, r/m32 — 3 byte ----------
      // r64[dst] = (long)(int)r64[src]   (32-bit を signed で 64-bit に拡張)
      if( op == 0x63 && length == 3 ) {
        int modrm = bytes[2] & 0xFF;
        if( (modrm >> 6) != 3 ) return EMIT_UNKNOWN;
        int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
        int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);
        // cpu.r64[regField] = (long)(int)cpu.r64[rmField];
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
        mv.visitLdcInsn( regField );
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
        mv.visitLdcInsn( rmField );
        mv.visitInsn( Opcodes.LALOAD );
        mv.visitInsn( Opcodes.L2I );          // long → int (truncate to low 32 bits)
        mv.visitInsn( Opcodes.I2L );          // int → long (sign-extend back)
        mv.visitInsn( Opcodes.LASTORE );
        return EMIT_NONTERM;
      }

      // ---------- REX.W + ALU r/r (mod==3) — 3 byte ----------
      // 0x01/0x03 ADD, 0x09/0x0B OR, 0x21/0x23 AND, 0x29/0x2B SUB,
      // 0x31/0x33 XOR, 0x39/0x3B CMP, 0x85 TEST
      // Intel encoding の direction bit は opcode の bit 1:
      //   d=0 (01/09/21/29/31/39): dst = r/m (rmField)、src = r (regField)
      //   d=1 (03/0B/23/2B/33/3B): dst = r   (regField)、src = r/m (rmField)
      // 0x85 TEST は bit 1 = 0 で dst = rmField (CMP/TEST は書き戻さないが
      // operand 解釈は同じ)。
      // Cpu64 helper を INVOKEVIRTUAL で呼ぶ (4 bytecode/insn のみ)。
      if( length == 3 && isAluRROpcode( op ) ) {
        int modrm = bytes[2] & 0xFF;
        if( (modrm >> 6) != 3 ) return EMIT_UNKNOWN;
        int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
        int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);
        boolean rm_dst = ((op >> 1) & 1) == 0;            // direction bit
        int dstReg = rm_dst ? rmField  : regField;
        int srcReg = rm_dst ? regField : rmField;
        String helperName = aluHelperName( op );
        // cpu.jitXxx64RR(dstReg, srcReg);
        mv.visitVarInsn( Opcodes.ALOAD, 1 );
        mv.visitLdcInsn( dstReg );
        mv.visitLdcInsn( srcReg );
        mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Cpu64", helperName, "(II)V", false );
        return EMIT_NONTERM;
      }
    }

    return EMIT_UNKNOWN;
  }

  private static boolean isAluRROpcode( int op ) {
    switch( op ) {
      case 0x01: case 0x03:  // ADD
      case 0x09: case 0x0B:  // OR
      case 0x21: case 0x23:  // AND
      case 0x29: case 0x2B:  // SUB
      case 0x31: case 0x33:  // XOR
      case 0x39: case 0x3B:  // CMP
      case 0x85:             // TEST r/m,r
        return true;
    }
    return false;
  }

  private static String aluHelperName( int op ) {
    switch( op ) {
      case 0x01: case 0x03: return "jitAdd64RR";
      case 0x09: case 0x0B: return "jitOr64RR";
      case 0x21: case 0x23: return "jitAnd64RR";
      case 0x29: case 0x2B: return "jitSub64RR";
      case 0x31: case 0x33: return "jitXor64RR";
      case 0x39: case 0x3B: return "jitCmp64RR";
      case 0x85:            return "jitTest64RR";
      default: throw new IllegalArgumentException( "not ALU r/r: " + Integer.toHexString(op) );
    }
  }

  /** Jcc rel8 / rel32 共通の bytecode emit。終端 LRETURN を 2 個出す。 */
  private static void emitJccBody( MethodVisitor mv, int cond,
                                   long takenTarget, long notTakenTarget ) {
    Label notTaken = new Label();
    mv.visitVarInsn( Opcodes.ALOAD, 1 );
    mv.visitLdcInsn( cond );
    mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Cpu64", "evalCond", "(I)Z", false );
    mv.visitJumpInsn( Opcodes.IFEQ, notTaken );
    mv.visitLdcInsn( takenTarget );
    mv.visitInsn( Opcodes.LRETURN );
    mv.visitLabel( notTaken );
    mv.visitLdcInsn( notTakenTarget );
    mv.visitInsn( Opcodes.LRETURN );
  }

  /**
   * 解析済み memory operand。emitMemAddr で JVM stack に address (long) を
   * push するときに使う。
   * - constAddr が non-null: address は compile time 定数 (RIP-rel 等)
   * - そうでなければ baseReg + disp
   */
  private static final class MemOp {
    final int  baseReg;     // -1 if constAddr is used instead
    final long disp;
    final Long constAddr;   // non-null if address is a compile-time constant
    MemOp( int baseReg, long disp, Long constAddr ) {
      this.baseReg = baseReg; this.disp = disp; this.constAddr = constAddr;
    }
    static MemOp ofRegDisp( int baseReg, long disp ) { return new MemOp( baseReg, disp, null ); }
    static MemOp ofConst  ( long addr )              { return new MemOp( -1, 0L, addr ); }
  }

  /**
   * ModRM (+ SIB + disp) を解析して MemOp を返す。
   * length が想定と違ったり、未対応 form (with-index SIB / [disp32 only] 等)
   * の場合は null。
   *
   * @param bytes 命令全体の byte 列
   * @param length 命令長
   * @param pc 命令先頭 RIP (RIP-rel 計算で必要)
   * @param mod ModRM の mod field
   * @param rmField REX.B 適用済みの rm (0..15)
   * @param rex REX prefix byte (REX.X 判定で SIB 解析に使う)
   */
  private MemOp decodeMemOp( byte[] bytes, int length, long pc,
                             int mod, int rmField, int rex ) {
    int rm_lo = rmField & 7;
    // RIP-relative (mod==0, rm&7==5)
    if( mod == 0 && rm_lo == 5 ) {
      if( length != 7 ) return null;
      long disp = (long) loadDisp32( bytes, 3 );
      return MemOp.ofConst( pc + 7L + disp );    // 命令末尾 RIP + disp32
    }
    // SIB byte (rm&7 == 4)
    if( rm_lo == 4 ) {
      boolean rex_x = (rex & 0x02) != 0;
      if( rex_x ) return null;                   // index あり: 未対応
      int sib = bytes[3] & 0xFF;
      int sib_index = (sib >> 3) & 7;
      if( sib_index != 4 ) return null;          // no-index でない: 未対応
      int sib_base = sib & 7;
      if( mod == 0 && sib_base == 5 ) return null; // [disp32 only]: 未対応
      int baseReg = sib_base | ((rex & 0x01) != 0 ? 8 : 0);  // REX.B 適用
      int dispOff = 4;                           // SIB の直後
      long disp;
      int expectedLen;
      switch( mod ) {
        case 0: disp = 0L;                                  expectedLen = 4; break;
        case 1: disp = (long)(byte)bytes[ dispOff ];        expectedLen = 5; break;
        case 2: disp = (long) loadDisp32( bytes, dispOff ); expectedLen = 8; break;
        default: return null;
      }
      if( length != expectedLen ) return null;
      return MemOp.ofRegDisp( baseReg, disp );
    }
    // 通常の [base + disp]
    long disp;
    int expectedLen;
    switch( mod ) {
      case 0: disp = 0L;                            expectedLen = 3; break;
      case 1: disp = (long)(byte)bytes[3];          expectedLen = 4; break;
      case 2: disp = (long) loadDisp32( bytes, 3 ); expectedLen = 7; break;
      default: return null;
    }
    if( length != expectedLen ) return null;
    return MemOp.ofRegDisp( rmField, disp );
  }

  /** address (long) を JVM stack に push する bytecode を emit。 */
  private static void emitMemAddr( MethodVisitor mv, MemOp mo ) {
    if( mo.constAddr != null ) {
      mv.visitLdcInsn( mo.constAddr.longValue() );
      return;
    }
    mv.visitVarInsn( Opcodes.ALOAD, 1 );
    mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
    mv.visitLdcInsn( mo.baseReg );
    mv.visitInsn( Opcodes.LALOAD );
    if( mo.disp != 0L ) {
      mv.visitLdcInsn( mo.disp );
      mv.visitInsn( Opcodes.LADD );
    }
  }

  /**
   * SIB byte 付き memory operand の命令長を返す。
   * REX prefix + opcode + ModRM (rm&7==4) + SIB の前提。pc は REX の先頭。
   * 対応するのは「no-index (REX.X=0 + SIB.index=4)」かつ「base != 5 mod==0」
   * の単純 [base+disp] 形のみ。それ以外は -1。
   */
  private int memoryOpLengthSIB( Memory mem, long pc, int mod, int rex ) {
    boolean rex_x = (rex & 0x02) != 0;
    if( rex_x ) return -1;                       // index あり: 未対応
    int sib;
    try { sib = mem.load8( pc + 3 ) & 0xFF; }
    catch( Throwable t ) { return -1; }
    int sib_index = (sib >> 3) & 7;
    if( sib_index != 4 ) return -1;              // no-index でない: 未対応
    int sib_base = sib & 7;
    if( mod == 0 && sib_base == 5 ) return -1;   // [disp32] only: 未対応
    // mod=0/1/2 で disp 長: 0/1/4 byte。総長は REX(1) + op(1) + ModRM(1) + SIB(1) + disp
    if( mod == 0 ) return 4;
    if( mod == 1 ) return 5;
    if( mod == 2 ) return 8;
    return -1;
  }

  /** little-endian 4 byte signed disp を bytes[offset..offset+3] から読む。 */
  private static int loadDisp32( byte[] bytes, int offset ) {
    return (bytes[offset]     & 0xFF)
         | ((bytes[offset+1]  & 0xFF) <<  8)
         | ((bytes[offset+2]  & 0xFF) << 16)
         | ((bytes[offset+3]  & 0xFF) << 24);
  }
}
