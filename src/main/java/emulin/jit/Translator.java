package emulin.jit;

import emulin.Cpu64;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Phase 34-A3 step 1: x86-64 命令を Java bytecode に翻訳する skeleton。
 *
 * 対象 opcode は段階的に拡張する。最初の PoC は:
 *   0x89 MOV r/m, r (REX.W, mod==3, no 0x66 / FS) — 64-bit register-to-register
 *
 * 翻訳 flow:
 *   1. lookup(rip) で cache HIT なら CompiledInsn を返す
 *   2. miss なら tryCompile(rip, bytes...) で bytecode 生成 + class load + cache 登録
 *   3. cache に「翻訳不可」を意味する SENTINEL も置けるが、現状は null 返却で
 *      呼び出し側 interpreter にフォールバック
 *
 * EMULIN_USE_JIT=1 で有効化 (default off)。
 */
public final class Translator {
  public static final boolean ENABLED =
    System.getenv("EMULIN_USE_JIT") != null;

  // 同一 class 名は重複定義不可なので、生成 class ごとに unique 名を作る。
  private final java.util.concurrent.atomic.AtomicLong serial = new java.util.concurrent.atomic.AtomicLong();
  private final GeneratedClassLoader loader = new GeneratedClassLoader();

  // RIP -> 翻訳結果。null は「未翻訳」、SENTINEL_NOT_COMPILABLE は「翻訳不可」。
  private static final CompiledInsn SENTINEL_NOT_COMPILABLE = cpu -> 0L;
  private final ConcurrentHashMap<Long, CompiledInsn> cache = new ConcurrentHashMap<>();

  // 統計 (全 Translator instance 合計、static)
  public static java.util.concurrent.atomic.AtomicLong compile_hits      = new java.util.concurrent.atomic.AtomicLong();
  public static java.util.concurrent.atomic.AtomicLong compile_attempts  = new java.util.concurrent.atomic.AtomicLong();
  public static java.util.concurrent.atomic.AtomicLong compile_successes = new java.util.concurrent.atomic.AtomicLong();

  /** プロセス置換 (execve) で旧 binary の compile 結果を捨てる用。 */
  public void clear() {
    cache.clear();
  }

  // JVM 終了時に統計を dump する shutdown hook 登録。
  static {
    if( ENABLED ) {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        long hits      = compile_hits.get();
        long attempts  = compile_attempts.get();
        long successes = compile_successes.get();
        double success_rate = attempts > 0 ? 100.0 * successes / attempts : 0.0;
        System.err.println( "===== EMULIN_USE_JIT =====" );
        System.err.println( String.format(
          "compile attempts:  %d", attempts ) );
        System.err.println( String.format(
          "compile successes: %d (%.2f%%)", successes, success_rate ) );
        System.err.println( String.format(
          "execute hits:      %d (= compiled 命令の実行回数)", hits ) );
        System.err.println( "==========================" );
      }, "EmulinJitStatsDump" ) );
    }
  }

  /** cache を見て、翻訳済みなら返す。未翻訳または翻訳不可なら null。 */
  public CompiledInsn lookup( long rip ) {
    CompiledInsn ci = cache.get( rip );
    if( ci == null || ci == SENTINEL_NOT_COMPILABLE ) return null;
    compile_hits.incrementAndGet();
    return ci;
  }

  /**
   * rip 位置の命令を翻訳しようと試みる。成功すれば cache に登録。失敗すれば
   * SENTINEL_NOT_COMPILABLE を cache に登録 (次回も interpreter fallback)。
   *
   * insnBytes は instruction の生 byte 列 (length 1-15)。length は実命令長。
   */
  public void tryCompile( long rip, byte[] insnBytes, int length ) {
    if( cache.containsKey( rip ) ) return;
    compile_attempts.incrementAndGet();
    CompiledInsn ci;
    try {
      ci = compileOne( rip, insnBytes, length );
    } catch( LinkageError e ) {
      // ASM jar が classpath に無いと ClassWriter ロード時に発火する。JIT を
      // 黙って無効化して以後 interpreter にフォールバックする。
      if( asm_missing_warned.compareAndSet( false, true ) ) {
        System.err.println( "Translator: ASM not on classpath (" + e.getClass().getSimpleName()
          + "), JIT disabled; running interpreter only" );
      }
      cache.put( rip, SENTINEL_NOT_COMPILABLE );
      return;
    } catch( Throwable t ) {
      // 想定外の例外で emulator を巻き込まない (cache に NOT_COMPILABLE 入れて
      // 1 回限りにする)。最初の 1 件だけ出力。
      if( generic_compile_warned.compareAndSet( false, true ) ) {
        System.err.println( "Translator: unexpected error at rip=0x"
          + Long.toHexString( rip ) + ": " + t );
      }
      cache.put( rip, SENTINEL_NOT_COMPILABLE );
      return;
    }
    if( ci == null ) {
      cache.put( rip, SENTINEL_NOT_COMPILABLE );
    } else {
      compile_successes.incrementAndGet();
      cache.put( rip, ci );
    }
  }

  private final java.util.concurrent.atomic.AtomicBoolean asm_missing_warned     = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean generic_compile_warned = new java.util.concurrent.atomic.AtomicBoolean();

  /** 翻訳本体。null 返却 = 翻訳不可 (interpreter に任せる)。 */
  private CompiledInsn compileOne( long rip, byte[] bytes, int length ) {
    if( length < 1 ) return null;
    int b0 = bytes[0] & 0xFF;

    // ----- prefix なし (REX 不要) で完結する命令 -----
    // 0xEB ib: JMP rel8 short — 2 byte、flags 不変
    if( b0 == 0xEB && length == 2 ) {
      long target = rip + 2 + (long)(byte)bytes[1];
      return emitJmpAbsolute( rip, target );
    }
    // 0xE9 id: JMP rel32 near — 5 byte、flags 不変
    if( b0 == 0xE9 && length == 5 ) {
      int disp32 = (bytes[1] & 0xFF)
                 | ((bytes[2] & 0xFF) << 8)
                 | ((bytes[3] & 0xFF) << 16)
                 | ((bytes[4] & 0xFF) << 24);
      long target = rip + 5 + (long)disp32;   // disp32 は signed (int 範囲)
      return emitJmpAbsolute( rip, target );
    }
    // 0x70-0x7F ib: Jcc rel8 — 2 byte、cond は opcode の low 4 bits
    if( (b0 & 0xF0) == 0x70 && length == 2 ) {
      int cond = b0 & 0x0F;
      long takenTarget   = rip + 2 + (long)(byte)bytes[1];
      long notTakenTarget = rip + 2;
      return emitJcc( rip, cond, takenTarget, notTakenTarget );
    }

    // ----- REX prefix で始まる 64-bit 命令 -----
    if( (b0 & 0xF0) == 0x40 ) {
      boolean rex_w = (b0 & 0x08) != 0;
      boolean rex_r = (b0 & 0x04) != 0;
      boolean rex_b = (b0 & 0x01) != 0;
      if( !rex_w ) return null;                  // 64-bit のみ
      if( length < 2 ) return null;
      int op = bytes[1] & 0xFF;

      // REX.W + 0xB8+r id8: MOV r64, imm64 — 10 byte (REX + opcode + 8-byte imm)
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
        return emitMovRegImm64( rip, reg, imm64, rip + 10 );
      }

      // REX + opcode + ModRM (mod==3, register-register form) — 3 byte
      if( length == 3 ) {
        int modrm = bytes[2] & 0xFF;
        int mod = (modrm >> 6) & 3;
        if( mod != 3 ) return null;              // register form のみ
        int regField = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
        int rmField  = (modrm & 7)        | (rex_b ? 8 : 0);
        long nextRip = rip + 3;

        // 0x89: MOV r/m, r — dst = r/m, src = r
        // 0x8B: MOV r, r/m — dst = r,    src = r/m
        switch( op ) {
          case 0x89: return emitMovRegReg( rip, /*src*/regField, /*dst*/rmField,  nextRip );
          case 0x8B: return emitMovRegReg( rip, /*src*/rmField,  /*dst*/regField, nextRip );
          default:   return null;
        }
      }
    }

    return null;
  }

  /**
   * MOV r64[dst] = r64[src]; return nextRip
   * これを実装する class を生成する。
   */
  private CompiledInsn emitMovRegReg( long rip, int srcReg, int dstReg, long nextRip ) {
    String className = "emulin.jit.gen.Mov64_" + Long.toHexString(rip) + "_" + serial.incrementAndGet();
    String internalName = className.replace('.', '/');

    ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS );
    cw.visit( Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
             "java/lang/Object", new String[]{ "emulin/jit/CompiledInsn" } );

    // public Mov64_xxx() { super(); }
    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "<init>", "()V", null, null );
      mv.visitCode();
      mv.visitVarInsn( Opcodes.ALOAD, 0 );
      mv.visitMethodInsn( Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false );
      mv.visitInsn( Opcodes.RETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }

    // public long execute(Cpu64 cpu) {
    //   cpu.r64[dstReg] = cpu.r64[srcReg];
    //   return nextRip;
    // }
    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "execute", "(Lemulin/Cpu64;)J", null, null );
      mv.visitCode();
      // cpu.r64[dstReg] = cpu.r64[srcReg];
      // load destination array reference
      mv.visitVarInsn( Opcodes.ALOAD, 1 );           // cpu
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );  // r64[]
      mv.visitLdcInsn( dstReg );                      // dst idx
      // load source value
      mv.visitVarInsn( Opcodes.ALOAD, 1 );           // cpu
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );  // r64[]
      mv.visitLdcInsn( srcReg );                      // src idx
      mv.visitInsn( Opcodes.LALOAD );                 // r64[src]
      mv.visitInsn( Opcodes.LASTORE );                // r64[dst] = r64[src]
      // return nextRip;
      mv.visitLdcInsn( nextRip );
      mv.visitInsn( Opcodes.LRETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }
    cw.visitEnd();

    byte[] classBytes = cw.toByteArray();
    try {
      Class<?> cls = loader.define( className, classBytes );
      return (CompiledInsn) cls.getDeclaredConstructor().newInstance();
    } catch( Exception e ) {
      System.err.println( "Translator: failed to load generated class " + className + ": " + e );
      return null;
    }
  }

  /**
   * MOV r64[reg] = imm64 (constant); return nextRip
   * 生成 bytecode: cpu.r64[reg] = imm64; return nextRip;
   */
  private CompiledInsn emitMovRegImm64( long rip, int reg, long imm64, long nextRip ) {
    String className = "emulin.jit.gen.MovImm64_" + Long.toHexString(rip) + "_" + serial.incrementAndGet();
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

    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "execute", "(Lemulin/Cpu64;)J", null, null );
      mv.visitCode();
      // cpu.r64[reg] = imm64;
      mv.visitVarInsn( Opcodes.ALOAD, 1 );
      mv.visitFieldInsn( Opcodes.GETFIELD, "emulin/Cpu64", "r64", "[J" );
      mv.visitLdcInsn( reg );
      mv.visitLdcInsn( imm64 );
      mv.visitInsn( Opcodes.LASTORE );
      // return nextRip;
      mv.visitLdcInsn( nextRip );
      mv.visitInsn( Opcodes.LRETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }
    cw.visitEnd();

    byte[] classBytes = cw.toByteArray();
    try {
      Class<?> cls = loader.define( className, classBytes );
      return (CompiledInsn) cls.getDeclaredConstructor().newInstance();
    } catch( Exception e ) {
      System.err.println( "Translator: failed to load generated class " + className + ": " + e );
      return null;
    }
  }

  /**
   * Jcc rel8 / rel32 用。execute() は cpu.evalCond(cond) を呼び、true なら
   * takenTarget、false なら notTakenTarget を return する。
   */
  private CompiledInsn emitJcc( long rip, int cond, long takenTarget, long notTakenTarget ) {
    String className = "emulin.jit.gen.Jcc64_" + Long.toHexString(rip) + "_" + serial.incrementAndGet();
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

    // public long execute(Cpu64 cpu) {
    //   if( cpu.evalCond(cond) ) return takenTarget;
    //   return notTakenTarget;
    // }
    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "execute", "(Lemulin/Cpu64;)J", null, null );
      mv.visitCode();
      org.objectweb.asm.Label notTaken = new org.objectweb.asm.Label();
      mv.visitVarInsn( Opcodes.ALOAD, 1 );           // cpu
      mv.visitLdcInsn( cond );                        // cond
      mv.visitMethodInsn( Opcodes.INVOKEVIRTUAL, "emulin/Cpu64", "evalCond", "(I)Z", false );
      mv.visitJumpInsn( Opcodes.IFEQ, notTaken );
      mv.visitLdcInsn( takenTarget );
      mv.visitInsn( Opcodes.LRETURN );
      mv.visitLabel( notTaken );
      mv.visitLdcInsn( notTakenTarget );
      mv.visitInsn( Opcodes.LRETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }
    cw.visitEnd();

    byte[] classBytes = cw.toByteArray();
    try {
      Class<?> cls = loader.define( className, classBytes );
      return (CompiledInsn) cls.getDeclaredConstructor().newInstance();
    } catch( Exception e ) {
      System.err.println( "Translator: failed to load generated class " + className + ": " + e );
      return null;
    }
  }

  /**
   * 「flags も registers も触らず target に飛ぶだけ」の命令を生成する。
   * JMP rel8 / rel32 用。execute() は単に target を return する。
   */
  private CompiledInsn emitJmpAbsolute( long rip, long target ) {
    String className = "emulin.jit.gen.Jmp64_" + Long.toHexString(rip) + "_" + serial.incrementAndGet();
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

    // public long execute(Cpu64 cpu) { return target; }
    {
      MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC, "execute", "(Lemulin/Cpu64;)J", null, null );
      mv.visitCode();
      mv.visitLdcInsn( target );
      mv.visitInsn( Opcodes.LRETURN );
      mv.visitMaxs( 0, 0 );
      mv.visitEnd();
    }
    cw.visitEnd();

    byte[] classBytes = cw.toByteArray();
    try {
      Class<?> cls = loader.define( className, classBytes );
      return (CompiledInsn) cls.getDeclaredConstructor().newInstance();
    } catch( Exception e ) {
      System.err.println( "Translator: failed to load generated class " + className + ": " + e );
      return null;
    }
  }
}
