// ----------------------------------------
//  AArch64 executor regression smoke (issue #951)
// ----------------------------------------
package emulin;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class Aarch64ExecutorSmoke {
  private final Aarch64Decoder decoder = new Aarch64Decoder();
  private final Aarch64DecodedInsn decoded = new Aarch64DecodedInsn();
  private final Aarch64Executor executor = new Aarch64Executor();

  public static void main( String[] args ) {
    Aarch64ExecutorSmoke smoke = new Aarch64ExecutorSmoke();
    smoke.checkMoveAndPcRelative();
    smoke.checkArithmeticAndFlags();
    smoke.checkLogical();
    smoke.checkBitfieldAndMultiply();
    smoke.checkBranches();
    smoke.checkMemory();
    System.out.println( "AArch64 executor smoke OK" );
  }

  private void checkMoveAndPcRelative() {
    Aarch64State state = new Aarch64State();
    execute( state, 0x92a24683, null ); // movn x3, #0x1234, lsl #16
    require( state.readX( 3 ) == ~0x12340000L, "MOVN" );

    execute( state, 0x529579a4, null ); // movz w4, #0xabcd
    require( state.readX( 4 ) == 0xabcd, "MOVZ W zero-extension" );

    state.writeX( 5, 0x1122334455667788L );
    execute( state, 0xf2f7dde5, null ); // movk x5, #0xbeef, lsl #48
    require( state.readX( 5 ) == 0xbeef334455667788L, "MOVK preserves other fields" );

    state.pc = 0x100;
    execute( state, 0x10000046, null ); // adr x6, +8
    require( state.readX( 6 ) == 0x108, "ADR" );

    state.pc = 0x1234;
    execute( state, 0x90000007, null ); // adrp x7, current page
    require( state.readX( 7 ) == 0x1000, "ADRP page base" );
  }

  private void checkArithmeticAndFlags() {
    Aarch64State state = new Aarch64State();
    state.writeX( 1, 0x1000 );
    execute( state, 0x91048c20, null ); // add x0, x1, #0x123
    require( state.readX( 0 ) == 0x1123, "ADD immediate" );

    state.writeX( 3, 0xffff9000L );
    execute( state, 0x31401c62, null ); // adds w2, w3, #0x7000
    require( state.readX( 2 ) == 0 && state.zero() && state.carry()
        && !state.negative() && !state.overflow(), "ADDS flags" );

    state.sp = 0x2000;
    execute( state, 0xd10043e4, null ); // sub x4, sp, #16
    require( state.readX( 4 ) == 0x1ff0 && state.sp == 0x2000, "SUB from SP" );

    state.writeX( 5, Long.MIN_VALUE );
    execute( state, 0xf10004bf, null ); // subs xzr, x5, #1
    require( state.carry() && state.overflow() && !state.negative() && !state.zero(),
        "SUBS overflow/no-borrow flags" );

    state.writeX( 7, 3 );
    state.writeX( 8, 4 );
    execute( state, 0x8b080ce6, null ); // add x6, x7, x8, lsl #3
    require( state.readX( 6 ) == 35, "ADD shifted register" );

    state.writeX( 23, 100 );
    state.writeX( 24, 0xffffffffL );
    execute( state, 0x8b38caf6, null ); // add x22, x23, w24, sxtw #2
    require( state.readX( 22 ) == 96, "ADD extended register" );
  }

  private void checkLogical() {
    Aarch64State state = new Aarch64State();
    state.writeX( 16, 0x123456789abcdef0L );
    execute( state, 0xaa1003ef, null ); // orr x15, xzr, x16
    require( state.readX( 15 ) == state.readX( 16 ), "ORR/MOV alias" );

    state.writeX( 8, 0x1234 );
    execute( state, 0x92401d07, null ); // and x7, x8, #0xff
    require( state.readX( 7 ) == 0x34, "AND logical immediate" );

    state.writeX( 20, 0x10 );
    state.writeX( 21, 0x20 );
    execute( state, 0xea15029f, null ); // ands xzr, x20, x21
    require( state.zero() && !state.negative() && !state.carry() && !state.overflow(),
        "ANDS/TST flags" );
  }

  private void checkBitfieldAndMultiply() {
    Aarch64State state = new Aarch64State();

    state.writeX( 14, 0x20001L << 3 );
    execute( state, 0x934351cd, null ); // sbfm x13,x14,#3,#20 (sbfx #3,#18)
    require( state.readX( 13 ) == -0x1ffffL, "SBFM signed extract" );

    state.writeX( 15, 0xaaaabbbbccccddddL );
    state.writeX( 16, 0x1122334455667788L );
    execute( state, 0xb3487e0f, null ); // bfm x15,x16,#8,#31 (bfxil #8,#24)
    long inserted = (0xaaaabbbbccccddddL & ~0xffffffL)
        | ((0x1122334455667788L >>> 8) & 0xffffffL);
    require( state.readX( 15 ) == inserted, "BFM insert" );

    state.writeX( 18, 0xdeadbeefL );
    execute( state, 0x53043e51, null ); // ubfm w17,w18,#4,#15 (ubfx #4,#12)
    require( state.readX( 17 ) == ((0xdeadbeefL >>> 4) & 0xfff), "UBFM extract" );

    state.writeX( 20, 0x0123456789abcdefL );
    state.writeX( 21, 0xfedcba9876543210L );
    execute( state, 0x93d53293, null ); // extr x19,x20,x21,#12
    long extracted = (0xfedcba9876543210L >>> 12) | (0x0123456789abcdefL << 52);
    require( state.readX( 19 ) == extracted, "EXTR" );

    state.writeX( 23, 3 );
    state.writeX( 24, 4 );
    state.writeX( 25, 5 );
    execute( state, 0x9b1866f6, null ); // madd x22,x23,x24,x25
    require( state.readX( 22 ) == 17, "MADD" );

    state.writeX( 27, 7 );
    state.writeX( 28, 8 );
    state.writeX( 29, 100 );
    execute( state, 0x1b1cf77a, null ); // msub w26,w27,w28,w29
    require( state.readX( 26 ) == 44, "MSUB W" );
  }

  private void checkBranches() {
    Aarch64State state = new Aarch64State();
    state.pc = 0x100;
    execute( state, 0x1400000a, null ); // b +40
    require( state.pc == 0x128, "B" );

    state.pc = 0x200;
    execute( state, 0x94000009, null ); // bl +36
    require( state.pc == 0x224 && state.readX( 30 ) == 0x204, "BL/LR" );

    state.pc = 0x300;
    state.setNzcv( false, true, false, false );
    execute( state, 0x54000100, null ); // b.eq +32
    require( state.pc == 0x320, "B.cond taken" );
    state.pc = 0x300;
    state.setNzcv( false, false, false, false );
    execute( state, 0x54000100, null );
    require( state.pc == 0x304, "B.cond not taken" );

    state.pc = 0x400;
    state.writeX( 0, 0 );
    execute( state, 0xb40000e0, null ); // cbz x0, +28
    require( state.pc == 0x41c, "CBZ" );

    state.pc = 0x500;
    state.writeX( 2, 1L << 40 );
    execute( state, 0xb64000a2, null ); // tbz x2, #40, +20
    require( state.pc == 0x504, "TBZ not taken" );

    state.pc = 0x600;
    state.writeX( 4, 0x7770 );
    execute( state, 0xd61f0080, null ); // br x4
    require( state.pc == 0x7770, "BR" );
  }

  private void checkMemory() {
    MemoryImage image = new MemoryImage();
    MemoryBackend memory = image.backend();
    Aarch64State state = new Aarch64State();

    state.writeX( 0, 0x1122334455667788L );
    state.writeX( 1, 0x1000 );
    execute( state, 0xf9000c20, memory ); // str x0, [x1,#24]
    require( image.read( 0x1018, 8 ) == state.readX( 0 ), "STR unsigned offset" );

    image.write( 0x100c, 0xdeadbeefL, 4 );
    state.writeX( 3, 0x1000 );
    execute( state, 0xb9400c62, memory ); // ldr w2, [x3,#12]
    require( state.readX( 2 ) == 0xdeadbeefL, "LDR W zero-extension" );

    state.writeX( 12, 0xaabbccddeeff0011L );
    state.writeX( 13, 0x2000 );
    execute( state, 0xf8010dac, memory ); // str x12, [x13,#16]!
    require( state.readX( 13 ) == 0x2010
        && image.read( 0x2010, 8 ) == state.readX( 12 ), "STR pre-index" );

    image.write( 0x3000, 0x8877665544332211L, 8 );
    state.writeX( 15, 0x3000 );
    execute( state, 0xf84185ee, memory ); // ldr x14, [x15],#24
    require( state.readX( 14 ) == 0x8877665544332211L
        && state.readX( 15 ) == 0x3018, "LDR post-index" );

    state.sp = 0x4000;
    state.writeX( 0, 0x1111 );
    state.writeX( 1, 0x2222 );
    execute( state, 0xa9bf07e0, memory ); // stp x0,x1,[sp,#-16]!
    require( state.sp == 0x3ff0 && image.read( 0x3ff0, 8 ) == 0x1111
        && image.read( 0x3ff8, 8 ) == 0x2222, "STP pre-index" );

    state.writeX( 17, 0x5000 );
    state.writeX( 18, 2 );
    image.write( 0x5010, 0x123456789abcdef0L, 8 );
    execute( state, 0xf8727a30, memory ); // ldr x16,[x17,x18,lsl#3]
    require( state.readX( 16 ) == 0x123456789abcdef0L, "LDR register offset" );

    state.pc = 0x6000;
    image.write( 0x6028, 0x0fedcba987654321L, 8 );
    execute( state, 0x5800014a, memory ); // ldr x10, pc+40
    require( state.readX( 10 ) == 0x0fedcba987654321L, "LDR literal" );
  }

  private void execute( Aarch64State state, int raw, MemoryBackend memory ) {
    decoder.decode( raw, decoded );
    state.pc = executor.execute( state, decoded, null, memory );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }

  private static final class MemoryImage {
    private final Map<Long,Byte> bytes = new HashMap<>();

    MemoryBackend backend() {
      return (MemoryBackend)Proxy.newProxyInstance(
          MemoryBackend.class.getClassLoader(), new Class<?>[]{ MemoryBackend.class },
          (proxy, method, args) -> switch( method.getName() ) {
            case "load8" -> (byte)read( (long)args[0], 1 );
            case "load16" -> (short)read( (long)args[0], 2 );
            case "load32" -> (int)read( (long)args[0], 4 );
            case "load64" -> read( (long)args[0], 8 );
            case "store8" -> { write( (long)args[0], (int)args[1], 1 ); yield true; }
            case "store16" -> { write( (long)args[0], (short)args[1], 2 ); yield null; }
            case "store32" -> { write( (long)args[0], (int)args[1], 4 ); yield null; }
            case "store64" -> { write( (long)args[0], (long)args[1], 8 ); yield null; }
            case "toString" -> "AArch64ExecutorSmoke.MemoryImage";
            default -> throw new UnsupportedOperationException( method.getName() );
          } );
    }

    long read( long address, int size ) {
      long value = 0;
      for( int i = 0; i < size; i++ ) {
        value |= (long)(bytes.getOrDefault( address + i, (byte)0 ) & 0xff) << (i * 8);
      }
      return value;
    }

    void write( long address, long value, int size ) {
      for( int i = 0; i < size; i++ ) bytes.put( address + i, (byte)(value >>> (i * 8)) );
    }
  }
}
