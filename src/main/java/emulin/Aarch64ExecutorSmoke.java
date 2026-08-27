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
    smoke.checkExtendedScalar();
    smoke.checkVariableShiftAndVector();
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

    state.writeX( 2, 30 );
    state.writeX( 10, 20 );
    state.setNzcv( false, false, true, false );
    execute( state, 0x9a8a2042, null ); // csel x2,x2,x10,hs
    require( state.readX( 2 ) == 30, "CSEL true" );
    state.setNzcv( false, false, false, false );
    execute( state, 0x9a8a2042, null );
    require( state.readX( 2 ) == 20, "CSEL false" );

    state.writeX( 0, 47 );
    state.setNzcv( false, false, false, false );
    execute( state, 0x7a401804, null ); // ccmp w0,#0,#4,ne
    require( !state.zero() && state.carry(), "CCMP immediate executed" );
    state.setNzcv( false, true, false, false );
    execute( state, 0x7a401804, null );
    require( state.zero() && !state.carry(), "CCMP fallback NZCV" );
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

    state.writeX( 4, 3 );
    state.writeX( 5, 4 );
    execute( state, 0x9ba57c84, null ); // umull x4,w4,w5
    require( state.readX( 4 ) == 12, "UMULL/UMADDL" );
    state.writeX( 19, -1L );
    state.writeX( 0, 2 );
    execute( state, 0x9bc07e73, null ); // umulh x19,x19,x0
    require( state.readX( 19 ) == 1, "UMULH" );

    state.writeX( 0, 100 );
    state.writeX( 1, 9 );
    execute( state, 0x9ac10800, null ); // udiv x0,x0,x1
    require( state.readX( 0 ) == 11, "UDIV" );
    state.writeX( 1, 0 );
    execute( state, 0x9ac10800, null );
    require( state.readX( 0 ) == 0, "UDIV by zero" );

    state.writeX( 4, 0x0123456789abcdefL );
    execute( state, 0xdac00c84, null ); // rev x4,x4
    require( state.readX( 4 ) == 0xefcdab8967452301L, "REV X" );
    state.writeX( 4, 0x0000001000000000L );
    execute( state, 0xdac01084, null ); // clz x4,x4
    require( state.readX( 4 ) == 27, "CLZ X" );
    state.writeX( 1, 1 );
    execute( state, 0xdac00021, null ); // rbit x1,x1
    require( state.readX( 1 ) == Long.MIN_VALUE, "RBIT X" );
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

  private void checkExtendedScalar() {
    Aarch64State state = new Aarch64State();
    state.writeX( 1, 0xfffffffdL );
    state.writeX( 2, 7 );
    state.writeX( 3, 100 );
    execute( state, 0x9b220c20, null ); // smaddl x0,w1,w2,x3
    require( state.readX( 0 ) == 79, "SMADDL" );
    execute( state, 0x9b228c20, null ); // smsubl x0,w1,w2,x3
    require( state.readX( 0 ) == 121, "SMSUBL" );

    state.writeX( 1, Long.MIN_VALUE );
    state.writeX( 2, 2 );
    execute( state, 0x9b427c20, null ); // smulh x0,x1,x2
    require( state.readX( 0 ) == -1, "SMULH" );

    state.writeX( 1, 0x0000ffff );
    execute( state, 0x5ac01420, null ); // cls w0,w1
    require( state.readX( 0 ) == 15, "CLS W" );
    state.writeX( 1, -2 );
    execute( state, 0xdac01420, null ); // cls x0,x1
    require( state.readX( 0 ) == 62, "CLS X" );

    state.writeX( 1, 0x12345678L );
    state.writeX( 2, 0x1122334455667788L );
    execute( state, 0x1ac24020, null ); // crc32b w0,w1,w2
    require( state.readX( 0 ) == 0xbdafc64aL, "CRC32B" );
    execute( state, 0x9ac25c20, null ); // crc32cx w0,w1,x2
    require( state.readX( 0 ) == 0x693b8e8bL, "CRC32CX" );
  }

  private void checkVariableShiftAndVector() {
    MemoryImage image = new MemoryImage();
    MemoryBackend memory = image.backend();
    Aarch64State state = new Aarch64State();

    state.writeX( 5, 1 );
    state.writeX( 6, 65 );
    execute( state, 0x9ac620a5, null ); // lsl x5,x5,x6
    require( state.readX( 5 ) == 2, "LSLV masks shift amount" );

    state.writeX( 1, 0x41 );
    execute( state, 0x4e010c20, null ); // dup v0.16b,w1
    require( state.readV64( 0, false ) == 0x4141414141414141L
        && state.readV64( 0, true ) == 0x4141414141414141L, "DUP V.16B" );

    execute( state, 0x4f00041f, null ); // movi v31.4s,#0
    require( state.readV64( 31, false ) == 0
        && state.readV64( 31, true ) == 0, "MOVI zero" );
    execute( state, 0x6f00041f, null ); // mvni v31.4s,#0
    require( state.readV64( 31, false ) == -1L
        && state.readV64( 31, true ) == -1L, "MVNI zero" );
    execute( state, 0x4f01e664, null ); // movi v4.16b,#0x33
    require( state.readV64( 4, false ) == 0x3333333333333333L
        && state.readV64( 4, true ) == 0x3333333333333333L, "MOVI byte" );
    state.writeV128( 15, 0, -1L );
    execute( state, 0x0f06e7ef, null ); // movi v15.8b,#0xdf
    require( state.readV64( 15, false ) == 0xdfdfdfdfdfdfdfdfL
        && state.readV64( 15, true ) == 0, "MOVI byte 8B" );

    image.write( 0x1000, 0x4142414341444145L, 8 );
    image.write( 0x1008, 0x4141414141414141L, 8 );
    state.writeX( 3, 0x1000 );
    execute( state, 0x4c407061, memory ); // ld1 {v1.16b},[x3]
    require( state.readV64( 1, false ) == 0x4142414341444145L
        && state.readV64( 1, true ) == 0x4141414141414141L, "LD1 V.16B" );
    state.writeX( 2, 0x1000 );
    execute( state, 0x4cdf7041, memory ); // ld1 {v1.16b},[x2],#16
    require( state.readX( 2 ) == 0x1010
        && state.readV64( 1, false ) == 0x4142414341444145L,
        "LD1 V.16B post-index" );
    image.write( 0x1010, 0x1122334455667788L, 8 );
    image.write( 0x1018, 0x99aabbccddeeff00L, 8 );
    state.writeX( 3, 0x1000 );
    execute( state, 0x4c40a066, memory ); // ld1 {v6.16b,v7.16b},[x3]
    require( state.readV64( 6, false ) == 0x4142414341444145L
        && state.readV64( 6, true ) == 0x4141414141414141L
        && state.readV64( 7, false ) == 0x1122334455667788L
        && state.readV64( 7, true ) == 0x99aabbccddeeff00L,
        "LD1 two V.16B" );
    execute( state, 0x4cdfa068, memory ); // ld1 {v8.16b,v9.16b},[x3],#32
    require( state.readX( 3 ) == 0x1020
        && state.readV64( 9, true ) == 0x99aabbccddeeff00L,
        "LD1 two V.16B post-index" );
    state.writeV128( 31, 0x1122334455667788L, 0 );
    state.writeX( 2, 0x1008 );
    execute( state, 0x4d40845f, memory ); // ld1 {v31.d}[1],[x2]
    require( state.readV64( 31, false ) == 0x1122334455667788L
        && state.readV64( 31, true ) == 0x4141414141414141L,
        "LD1 V.D lane" );

    execute( state, 0x6e208c22, null ); // cmeq v2.16b,v1.16b,v0.16b
    require( state.readV64( 2, false ) == 0xff00ff00ff00ff00L
        && state.readV64( 2, true ) == -1L, "CMEQ V.16B" );

    execute( state, 0x4e209822, null ); // cmeq v2.16b,v1.16b,#0
    require( state.readV64( 2, false ) == 0
        && state.readV64( 2, true ) == 0, "CMEQ V.16B zero" );

    state.writeV128( 1, 0x0102030405060708L, -1L );
    state.writeV128( 2, 0x0100030005000700L, -1L );
    execute( state, 0x2e228c23, null ); // cmeq v3.8b,v1.8b,v2.8b
    require( state.readV64( 3, false ) == 0xff00ff00ff00ff00L
        && state.readV64( 3, true ) == 0, "CMEQ V.8B" );
    state.writeV128( 5, 0x0100030005000700L, -1L );
    execute( state, 0x0e2098a4, null ); // cmeq v4.8b,v5.8b,#0
    require( state.readV64( 4, false ) == 0x00ff00ff00ff00ffL
        && state.readV64( 4, true ) == 0, "CMEQ V.8B zero" );

    state.writeV128( 1, 0xffff0000ffff0000L, 0xaaaaaaaaaaaaaaaaL );
    state.writeV128( 2, 0x00ff00ff00ff00ffL, 0x5555555555555555L );
    execute( state, 0x4e221c23, null ); // and v3.16b,v1.16b,v2.16b
    require( state.readV64( 3, false ) == 0x00ff000000ff0000L
        && state.readV64( 3, true ) == 0, "AND V.16B" );
    state.writeV128( 3, -1L, -1L );
    execute( state, 0x0e221c23, null ); // and v3.8b,v1.8b,v2.8b
    require( state.readV64( 3, false ) == 0x00ff000000ff0000L
        && state.readV64( 3, true ) == 0, "AND V.8B" );

    state.writeV128( 2, 0xaaaaaaaaaaaaaaaaL, 0x5555555555555555L );
    state.writeV128( 3, 0xffff0000ffff0000L, 0x0000ffff0000ffffL );
    state.writeV128( 4, 0x00ff00ff00ff00ffL, 0xff00ff00ff00ff00L );
    execute( state, 0x6ea41c62, null ); // bit v2.16b,v3.16b,v4.16b
    require( state.readV64( 2, false ) == 0xaaffaa00aaffaa00L
        && state.readV64( 2, true ) == 0x0055ff550055ff55L, "BIT V.16B" );
    state.writeV64( 30, 0xffff0000ffff0000L );
    state.writeV64( 31, 0x00ff00ff00ff00ffL );
    execute( state, 0x2e3f1fdf, null ); // eor v31.8b,v30.8b,v31.8b
    require( state.readV64( 31, false ) == 0xff0000ffff0000ffL
        && state.readV64( 31, true ) == 0, "EOR V.8B" );

    state.writeV128( 4, -1L, -1L );
    execute( state, 0x6f079604, null ); // bic v4.8h,#0xf0
    require( state.readV64( 4, false ) == 0xff0fff0fff0fff0fL
        && state.readV64( 4, true ) == 0xff0fff0fff0fff0fL,
        "BIC V.8H immediate" );
    state.writeV128( 2, -1L, -1L );
    execute( state, 0x6f00b5e2, null ); // bic v2.8h,#0xf,lsl#8
    require( state.readV64( 2, false ) == 0xf0fff0fff0fff0ffL
        && state.readV64( 2, true ) == 0xf0fff0fff0fff0ffL,
        "BIC V.8H shifted immediate" );

    state.writeV128( 2, 0x0807060504030201L, 0x100f0e0d0c0b0a09L );
    execute( state, 0x6e22a445, null ); // umaxp v5.16b,v2.16b,v2.16b
    require( state.readV64( 5, false ) == 0x100e0c0a08060402L
        && state.readV64( 5, true ) == 0x100e0c0a08060402L,
        "UMAXP V.16B" );
    execute( state, 0x6e22ac45, null ); // uminp v5.16b,v2.16b,v2.16b
    require( state.readV64( 5, false ) == 0x0f0d0b0907050301L
        && state.readV64( 5, true ) == 0x0f0d0b0907050301L,
        "UMINP V.16B" );
    execute( state, 0x4e22bc45, null ); // addp v5.16b,v2.16b,v2.16b
    require( state.readV64( 5, false ) == 0x1f1b17130f0b0703L
        && state.readV64( 5, true ) == 0x1f1b17130f0b0703L,
        "ADDP V.16B" );

    state.writeV128( 31, 0x0123456789abcdefL, 0xfedcba9876543210L );
    execute( state, 0x6e1f43ff, null ); // ext v31.16b,v31.16b,v31.16b,#8
    require( state.readV64( 31, false ) == 0xfedcba9876543210L
        && state.readV64( 31, true ) == 0x0123456789abcdefL,
        "EXT V.16B" );

    state.writeV128( 3, 0x0807060504030201L, 0x100f0e0d0c0b0a09L );
    state.writeV128( 1, 0x0808060604040202L, 0x11100f0e0d0c0b0aL );
    execute( state, 0x6e213c63, null ); // cmhs v3.16b,v3.16b,v1.16b
    require( state.readV64( 3, false ) == 0xff00ff00ff00ff00L
        && state.readV64( 3, true ) == 0,
        "CMHS V.16B" );

    state.writeV128( 2, 0x4560345023400ff0L, 0x89a0789067805670L );
    execute( state, 0x0f0c8443, null ); // shrn v3.8b,v2.8h,#4
    require( state.readV64( 3, false ) == 0x9a897867564534ffL
        && state.readV64( 3, true ) == 0, "SHRN V.8B" );
    state.writeV128( 1, 0x0080008000800080L, 0x0080008000800080L );
    execute( state, 0x0e214022, null ); // addhn v2.8b,v1.8h,v1.8h
    require( state.readV64( 2, false ) == 0x0101010101010101L
        && state.readV64( 2, true ) == 0, "ADDHN V.8B" );

    execute( state, 0x9e660065, null ); // fmov x5,d3
    require( state.readX( 5 ) == 0x9a897867564534ffL, "FMOV X,D" );
    execute( state, 0x1e60407f, null ); // fmov d31,d3
    require( state.readV64( 31, false ) == 0x9a897867564534ffL,
        "FMOV D,D" );
    state.writeV128( 31, 0x0123456789abcdefL, 0xfedcba9876543210L );
    execute( state, 0x0e143fe1, null ); // mov w1,v31.s[2]
    require( state.readX( 1 ) == 0x76543210L, "MOV W,V.S lane" );

    state.writeX( 0, 0x2000 );
    execute( state, 0x3d800003, memory ); // str q3,[x0]
    require( image.read( 0x2000, 8 ) == 0x9a897867564534ffL
        && image.read( 0x2008, 8 ) == 0, "STR Q" );
    state.writeV128( 4, 0, 0 );
    execute( state, 0x3dc00004, memory ); // ldr q4,[x0]
    require( state.readV64( 4, false ) == 0x9a897867564534ffL
        && state.readV64( 4, true ) == 0, "LDR Q" );
    state.writeX( 0, 0x2100 );
    state.writeX( 3, 0x20 );
    execute( state, 0x3ca36800, memory ); // str q0,[x0,x3]
    require( state.readX( 0 ) == 0x2100
        && image.read( 0x2120, 8 ) == 0x4141414141414141L,
        "STR Q register offset preserves X registers" );
    state.writeX( 4, 0x2200 );
    execute( state, 0x3c9f0080, memory ); // stur q0,[x4,#-16]
    require( image.read( 0x21f0, 8 ) == 0x4141414141414141L, "STUR Q" );
    state.writeX( 2, 0x21e0 );
    execute( state, 0x3cc10c40, memory ); // ldr q0,[x2,#16]!
    require( state.readX( 2 ) == 0x21f0
        && state.readV64( 0, false ) == 0x4141414141414141L,
        "LDR Q pre-index" );
    state.sp = 0x2300;
    state.writeV64( 30, 0x0123456789abcdefL );
    execute( state, 0xfd0033fe, memory ); // str d30,[sp,#0x60]
    state.writeV64( 30, 0 );
    execute( state, 0xfd4033fe, memory ); // ldr d30,[sp,#0x60]
    require( state.readV64( 30, false ) == 0x0123456789abcdefL,
        "STR/LDR D unsigned offset" );
    state.writeX( 0, 0x2400 );
    state.writeV64( 0, 0xdeadbeefL );
    execute( state, 0xbd000000, memory ); // str s0,[x0]
    state.writeV64( 0, 0 );
    execute( state, 0xbd400000, memory ); // ldr s0,[x0]
    require( state.readV64( 0, false ) == 0xdeadbeefL,
        "STR/LDR S unsigned offset" );
    state.writeV64( 0, 0x12345678L );
    state.writeX( 3, 4 );
    execute( state, 0xbc237800, memory ); // str s0,[x0,x3,lsl#2]
    state.writeV64( 0, 0 );
    execute( state, 0xbc637800, memory ); // ldr s0,[x0,x3,lsl#2]
    require( state.readV64( 0, false ) == 0x12345678L,
        "STR/LDR S register offset" );
    state.writeX( 5, 0x2504 );
    state.writeV64( 0, 0xabcdef01L );
    execute( state, 0xbc1fc0a0, memory ); // stur s0,[x5,#-4]
    state.writeV64( 0, 0 );
    execute( state, 0xbc5fc0a0, memory ); // ldur s0,[x5,#-4]
    require( state.readV64( 0, false ) == 0xabcdef01L,
        "STUR/LDUR S" );

    execute( state, 0xd53b00e5, null ); // mrs x5,DCZID_EL0
    require( state.readX( 5 ) == 0x10, "DCZID_EL0 advertises DC ZVA prohibited" );
    state.writeX( 19, 0x123456789abcdef0L );
    execute( state, 0xd51bd053, null ); // msr TPIDR_EL0,x19
    execute( state, 0xd53bd042, null ); // mrs x2,TPIDR_EL0
    require( state.readX( 2 ) == 0x123456789abcdef0L, "TPIDR_EL0 read/write" );

    state.writeV128( 0, 0x0102030405060708L, 0x1112131415161718L );
    state.writeV128( 1, 0x2122232425262728L, 0x3132333435363738L );
    state.writeX( 3, 0x3000 );
    execute( state, 0xad010460, memory ); // stp q0,q1,[x3,#32]
    require( image.read( 0x3020, 8 ) == 0x0102030405060708L
        && image.read( 0x3028, 8 ) == 0x1112131415161718L
        && image.read( 0x3030, 8 ) == 0x2122232425262728L
        && image.read( 0x3038, 8 ) == 0x3132333435363738L, "STP Q" );
    state.writeV128( 2, 0, 0 );
    state.writeV128( 3, 0, 0 );
    execute( state, 0xad410c62, memory ); // ldp q2,q3,[x3,#32]
    require( state.readV64( 2, false ) == 0x0102030405060708L
        && state.readV64( 3, true ) == 0x3132333435363738L, "LDP Q" );

    state.writeV64( 8, 0x1020304050607080L );
    state.writeV64( 9, 0x90a0b0c0d0e0f000L );
    state.writeX( 0, 0x3100 );
    execute( state, 0x6d072408, memory ); // stp d8,d9,[x0,#0x70]
    require( image.read( 0x3170, 8 ) == 0x1020304050607080L
        && image.read( 0x3178, 8 ) == 0x90a0b0c0d0e0f000L, "STP D" );
    state.writeV64( 8, 0 );
    state.writeV64( 9, 0 );
    execute( state, 0x6d472408, memory ); // ldp d8,d9,[x0,#0x70]
    require( state.readV64( 8, false ) == 0x1020304050607080L
        && state.readV64( 9, false ) == 0x90a0b0c0d0e0f000L, "LDP D" );
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

    image.write( 0x1100, 0xfffffffeL, 4 );
    state.writeX( 1, 0x1100 );
    execute( state, 0xb9800021, memory ); // ldrsw x1,[x1]
    require( state.readX( 1 ) == -2L, "LDRSW sign-extension" );

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

    image.write( 0x5800, 7, 4 );
    state.writeX( 2, 0x5800 );
    execute( state, 0x885ffc40, memory ); // ldaxr w0,[x2]
    require( state.readX( 0 ) == 7, "LDAXR W" );
    state.writeX( 1, 9 );
    execute( state, 0x88117c41, memory ); // stxr w17,w1,[x2]
    require( state.readX( 17 ) == 0 && image.read( 0x5800, 4 ) == 9,
        "STXR W success" );
    state.writeX( 1, 11 );
    execute( state, 0x88117c41, memory );
    require( state.readX( 17 ) == 1 && image.read( 0x5800, 4 ) == 9,
        "STXR W fails after reservation is consumed" );

    image.write( 0x5900, 0x1122334455667788L, 8 );
    state.writeX( 24, 0x5900 );
    execute( state, 0xc8dfff19, memory ); // ldar x25,[x24]
    require( state.readX( 25 ) == 0x1122334455667788L, "LDAR X" );
    state.writeX( 23, 0x8877665544332211L );
    state.writeX( 28, 0x5900 );
    execute( state, 0xc89fff97, memory ); // stlr x23,[x28]
    require( image.read( 0x5900, 8 ) == 0x8877665544332211L, "STLR X" );

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
            case "atomicCompareAndSet32" -> {
              long address = (long)args[0];
              int expected = (int)args[1];
              int value = (int)args[2];
              if( (int)read( address, 4 ) != expected ) yield false;
              write( address, Integer.toUnsignedLong( value ), 4 );
              yield true;
            }
            case "atomicCompareAndSet64" -> {
              long address = (long)args[0];
              long expected = (long)args[1];
              long value = (long)args[2];
              if( read( address, 8 ) != expected ) yield false;
              write( address, value, 8 );
              yield true;
            }
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
