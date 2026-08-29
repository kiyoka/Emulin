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
    state.writeX( 2, 0x89abcdefL );
    execute( state, 0x4e040c40, null ); // dup v0.4s,w2
    require( state.readV64( 0, false ) == 0x89abcdef89abcdefL
        && state.readV64( 0, true ) == 0x89abcdef89abcdefL, "DUP V.4S" );
    execute( state, 0x4e020c5e, null ); // dup v30.8h,w2
    require( state.readV64( 30, false ) == 0xcdefcdefcdefcdefL
        && state.readV64( 30, true ) == 0xcdefcdefcdefcdefL, "DUP V.8H" );
    execute( state, 0x4e010c20, null ); // restore v0.16b for following checks
    state.writeV128( 4, 0x1122334455667788L, 0x99aabbccddeeff00L );
    execute( state, 0x4e080483, null ); // dup v3.2d,v4.d[0]
    require( state.readV64( 3, false ) == 0x1122334455667788L
        && state.readV64( 3, true ) == 0x1122334455667788L,
        "DUP V.2D lane zero" );
    state.writeV128( 6, 0x0123456789abcdefL, 0xfedcba9876543210L );
    execute( state, 0x4e1804c5, null ); // dup v5.2d,v6.d[1]
    require( state.readV64( 5, false ) == state.readV64( 6, true )
        && state.readV64( 5, true ) == state.readV64( 6, true ),
        "DUP V.2D lane one" );
    state.writeV128( 27, 0x0123456789abcdefL, -1L );
    execute( state, 0x7f78077e, null ); // ushr d30,d27,#8
    require( state.readV64( 30, false ) == 0x000123456789abcdL
        && state.readV64( 30, true ) == 0, "USHR D" );
    state.writeV128( 1, 0x0011223344556677L, 0x8877665544332211L );
    state.writeV128( 2, 8, -8L );
    execute( state, 0x6ee24423, null ); // ushl v3.2d,v1.2d,v2.2d
    require( state.readV64( 3, false ) == 0x1122334455667700L
        && state.readV64( 3, true ) == 0x0088776655443322L,
        "USHL V.2D" );
    state.writeV128( 3, 0x1111111111111111L, 0x2222222222222222L );
    state.writeV128( 4, 0x3333333333333333L, 0x4444444444444444L );
    execute( state, 0x6e184483, null ); // mov v3.d[1],v4.d[1]
    require( state.readV64( 3, false ) == 0x1111111111111111L
        && state.readV64( 3, true ) == 0x4444444444444444L,
        "MOV vector D lane" );
    state.writeV128( 1, 0x2222222211111111L, 0x4444444433333333L );
    state.writeV128( 2, 0x6666666655555555L, 0x8888888877777777L );
    execute( state, 0x4e821823, null ); // uzp1 v3.4s,v1.4s,v2.4s
    require( state.readV64( 3, false ) == 0x3333333311111111L
        && state.readV64( 3, true ) == 0x7777777755555555L,
        "UZP1 V.4S" );
    state.writeV128( 7, 0x0004000300020001L, 0x0008000700060005L );
    state.writeV128( 20, 0x000c000b000a0009L, 0x0010000f000e000dL );
    execute( state, 0x4e5418f4, null ); // uzp1 v20.8h,v7.8h,v20.8h
    require( state.readV64( 20, false ) == 0x0007000500030001L
        && state.readV64( 20, true ) == 0x000f000d000b0009L,
        "UZP1 V.8H" );
    state.writeV128( 20, 0x0706050403020100L, 0x0f0e0d0c0b0a0908L );
    state.writeV128( 16, 0x1716151413121110L, 0x1f1e1d1c1b1a1918L );
    execute( state, 0x4e101a90, null ); // uzp1 v16.16b,v20.16b,v16.16b
    require( state.readV64( 16, false ) == 0x0e0c0a0806040200L
        && state.readV64( 16, true ) == 0x1e1c1a1816141210L,
        "UZP1 V.16B" );
    state.writeV128( 28, 0x2222222211111111L, 0x4444444433333333L );
    state.writeV128( 26, 0x6666666655555555L, 0x8888888877777777L );
    execute( state, 0x4e9a5b9c, null ); // uzp2 v28.4s,v28.4s,v26.4s
    require( state.readV64( 28, false ) == 0x4444444422222222L
        && state.readV64( 28, true ) == 0x8888888866666666L,
        "UZP2 V.4S" );
    state.writeV128( 27, 0x1122334455667788L, 0x99aabbccddeeff00L );
    execute( state, 0x0ea12b7a, null ); // xtn v26.2s,v27.2d
    require( state.readV64( 26, false ) == 0xddeeff0055667788L,
        "XTN V.2S" );
    execute( state, 0x0f20877b, null ); // shrn v27.2s,v27.2d,#32
    require( state.readV64( 27, false ) == 0x99aabbcc11223344L,
        "SHRN V.2S" );
    state.writeV128( 25, 0x0000000300000002L, 0x0000000500000004L );
    state.writeV128( 28, 0x0000000700000006L, 0x0000000900000008L );
    state.writeV128( 29, 10, 20 );
    execute( state, 0x2ebc833d, null ); // umlal v29.2d,v25.2s,v28.2s
    require( state.readV64( 29, false ) == 22
        && state.readV64( 29, true ) == 41, "UMLAL V.2D" );
    state.writeV128( 30, 100, 200 );
    execute( state, 0x6ebc833e, null ); // umlal2 v30.2d,v25.4s,v28.4s
    require( state.readV64( 30, false ) == 132
        && state.readV64( 30, true ) == 245, "UMLAL2 V.2D" );
    state.writeX( 0, 0x123456789abcdef0L );
    execute( state, 0xf980c000, null ); // prfm pldl1keep,[x0,#0x180]
    require( state.readX( 0 ) == 0x123456789abcdef0L,
        "PRFM must not clobber Rt-shaped bits" );
    state.writeV128( 31, 0xffff000000000000L, 0x8000000000000000L );
    execute( state, 0x6f5107fe, null ); // ushr v30.2d,v31.2d,#47
    require( state.readV64( 30, false ) == 0x1fffeL
        && state.readV64( 30, true ) == 0x10000L, "USHR V.2D" );
    state.writeV128( 31, 0x0000000300000002L, 0x0000000500000004L );
    state.writeV128( 21, 0x0000000700000006L, 0x0000000900000008L );
    execute( state, 0x4eb59fff, null ); // mul v31.4s,v31.4s,v21.4s
    require( state.readV64( 31, false ) == 0x000000150000000cL
        && state.readV64( 31, true ) == 0x0000002d00000020L,
        "MUL V.4S" );
    state.writeV64( 31, Double.doubleToRawLongBits( 42.75 ) );
    execute( state, 0x7ee1bbff, null ); // fcvtzu d31,d31
    require( state.readV64( 31, false ) == 42, "FCVTZU D,D" );
    state.writeV64( 30, Double.doubleToRawLongBits( 2.0 ) );
    state.writeV64( 26, Double.doubleToRawLongBits( 3.0 ) );
    state.writeV64( 31, Double.doubleToRawLongBits( 4.0 ) );
    execute( state, 0x1f5a7fdf, null ); // fmadd d31,d30,d26,d31
    require( Double.longBitsToDouble( state.readV64( 31, false ) ) == 10.0,
        "FMADD D" );
    state.writeV128( 15, 0xaabbccdd11223344L, -1L );
    execute( state, 0x0e0405ff, null ); // dup v31.2s,v15.s[0]
    require( state.readV64( 31, false ) == 0x1122334411223344L
        && state.readV64( 31, true ) == 0, "DUP V.2S lane" );
    state.writeV64( 10, Double.doubleToRawLongBits( -3.5 ) );
    execute( state, 0x1e60c15e, null ); // fabs d30,d10
    require( Double.longBitsToDouble( state.readV64( 30, false ) ) == 3.5,
        "FABS D" );
    execute( state, 0x1e61415e, null ); // fneg d30,d10
    require( Double.longBitsToDouble( state.readV64( 30, false ) ) == 3.5,
        "FNEG D" );
    state.writeX( 2, 0x7c00000L );
    execute( state, 0xd51b4402, null ); // msr fpcr,x2
    state.writeX( 2, 0 );
    execute( state, 0xd53b4402, null ); // mrs x2,fpcr
    require( state.readX( 2 ) == 0x7c00000L, "FPCR MRS/MSR" );
    state.writeX( 4, 0xffffffffL );
    state.writeX( 5, 0 );
    state.setNzcv( false, false, true, false );
    execute( state, 0x1a050083, null ); // adc w3,w4,w5
    require( state.readX( 3 ) == 0, "ADC W" );
    execute( state, 0x3a050083, null ); // adcs w3,w4,w5
    require( state.readX( 3 ) == 0 && state.zero() && state.carry(), "ADCS W" );
    state.writeV128( 24, 0x08070605040302a1L, 0 );
    state.writeV128( 31, 0x1122334455667788L, 0x99aabbccddeeff00L );
    execute( state, 0x6e03071f, null ); // mov v31.b[1],v24.b[0]
    require( state.readV64( 31, false ) == 0x112233445566a188L,
        "MOV vector byte lane" );
    state.writeV128( 30, 0x7766554433221100L, 0xffeeddccbbaa9988L );
    execute( state, 0x5e0607ca, null ); // mov h10,v30.h[1]
    require( state.readV64( 10, false ) == 0x3322L
        && state.readV64( 10, true ) == 0, "MOV scalar from vector lane" );
    state.writeV128( 10, 0x1111111111111111L, 0x2222222222222222L );
    state.writeV128( 24, 0x8877665544332211L, 0 );
    execute( state, 0x6e06170a, null ); // mov v10.h[1],v24.h[1]
    require( state.readV64( 10, false ) == 0x1111111144331111L,
        "MOV vector halfword lane" );
    state.writeV64( 28, -2L );
    state.writeV64( 30, 5 );
    execute( state, 0x5efe879d, null ); // add d29,d28,d30
    require( state.readV64( 29, false ) == 3
        && state.readV64( 29, true ) == 0, "ADD scalar D" );
    state.writeV128( 2, 10, 20 );
    state.writeV128( 27, 3, 4 );
    execute( state, 0x6efb8442, null ); // sub v2.2d,v2.2d,v27.2d
    require( state.readV64( 2, false ) == 7
        && state.readV64( 2, true ) == 16, "SUB V.2D" );

    execute( state, 0x4f00041f, null ); // movi v31.4s,#0
    require( state.readV64( 31, false ) == 0
        && state.readV64( 31, true ) == 0, "MOVI zero" );
    execute( state, 0x0f00043f, null ); // movi v31.2s,#1
    require( state.readV64( 31, false ) == 0x0000000100000001L
        && state.readV64( 31, true ) == 0, "MOVI V.2S nonzero" );
    state.writeV64( 30, 0x00000003ffffffffL );
    execute( state, 0x0ebe87fe, null ); // add v30.2s,v31.2s,v30.2s
    require( state.readV64( 30, false ) == 0x0000000400000000L,
        "ADD V.2S lane wrapping" );
    state.writeV128( 5, 0x0102030405060708L, 0x1112131415161718L );
    state.writeV128( 4, -1L, -1L );
    execute( state, 0x4e2484a4, null ); // add v4.16b,v5.16b,v4.16b
    require( state.readV64( 4, false ) == 0x0001020304050607L
        && state.readV64( 4, true ) == 0x1011121314151617L,
        "ADD V.16B lane wrapping" );
    state.writeV128( 22, 0x0000000200000001L, 3 );
    execute( state, 0x4f6056d6, null ); // shl v22.2d,v22.2d,#32
    require( state.readV64( 22, false ) == 0x0000000100000000L
        && state.readV64( 22, true ) == 0x0000000300000000L,
        "SHL V.2D" );
    state.writeV128( 25, 0xff80402010080402L, 0x0102040810204080L );
    execute( state, 0x6f0f0725, null ); // ushr v5.16b,v25.16b,#1
    require( state.readV64( 5, false ) == 0x7f40201008040201L
        && state.readV64( 5, true ) == 0x0001020408102040L,
        "USHR V.16B" );
    state.writeV64( 4, 0x0807060504030201L );
    state.writeV64( 23, 0x0101010101010101L );
    execute( state, 0x2e372096, null ); // usubl v22.8h,v4.8b,v23.8b
    require( state.readV64( 22, false ) == 0x0003000200010000L
        && state.readV64( 22, true ) == 0x0007000600050004L,
        "USUBL V.8H" );
    state.writeV128( 4, 0, 0x100f0e0d0c0b0a09L );
    state.writeV128( 23, 0, 0x0202020202020202L );
    execute( state, 0x6e372097, null ); // usubl2 v23.8h,v4.16b,v23.16b
    require( state.readV64( 23, false ) == 0x000a000900080007L
        && state.readV64( 23, true ) == 0x000e000d000c000bL,
        "USUBL2 V.8H" );
    state.writeV128( 22, 0x0004000300020001L, 0x0008000700060005L );
    state.writeV64( 3, 0x0101010101010101L );
    execute( state, 0x2e2332d6, null ); // usubw v22.8h,v22.8h,v3.8b
    require( state.readV64( 22, false ) == 0x0003000200010000L
        && state.readV64( 22, true ) == 0x0007000600050004L,
        "USUBW V.8H" );
    state.writeV128( 22, 0x80000001ffff0000L, 0x7fff0000ffff0001L );
    execute( state, 0x4e60aadc, null ); // cmlt v28.8h,v22.8h,#0
    require( state.readV64( 28, false ) == 0xffff0000ffff0000L
        && state.readV64( 28, true ) == 0x00000000ffff0000L,
        "CMLT V.8H zero" );
    state.writeV128( 28, 0x0004000300020001L, 0x0008000700060005L );
    state.writeV128( 29, 0x0014001300120011L, 0x0018001700160015L );
    state.writeX( 1, 0x1800 );
    execute( state, 0x4c9f843c, memory ); // st2 {v28.8h,v29.8h},[x1],#32
    require( image.read( 0x1800, 8 ) == 0x0012000200110001L
        && image.read( 0x1818, 8 ) == 0x0018000800170007L
        && state.readX( 1 ) == 0x1820, "ST2 V.8H post-index" );
    state.writeX( 5, 0x1800 );
    execute( state, 0x4cdf84b0, memory ); // ld2 {v16.8h,v17.8h},[x5],#32
    require( state.readV64( 16, false ) == 0x0004000300020001L
        && state.readV64( 16, true ) == 0x0008000700060005L
        && state.readV64( 17, false ) == 0x0014001300120011L
        && state.readV64( 17, true ) == 0x0018001700160015L
        && state.readX( 5 ) == 0x1820, "LD2 V.8H post-index" );
    state.writeV64( 31, 0x8000000000000001L );
    execute( state, 0x2ea0bbff, null ); // neg v31.2s,v31.2s
    require( state.readV64( 31, false ) == 0x80000000ffffffffL,
        "NEG V.2S" );
    state.writeV64( 31, 0x8000000100000001L );
    execute( state, 0x0f2157ff, null ); // shl v31.2s,v31.2s,#1
    require( state.readV64( 31, false ) == 0x0000000200000002L,
        "SHL V.2S lane wrapping" );
    state.writeV64( 30, 0x0000000080000000L );
    state.writeV64( 29, 0x00000001ffffffffL );
    execute( state, 0x2ebd87dd, null ); // sub v29.2s,v30.2s,v29.2s
    require( state.readV64( 29, false ) == 0xffffffff80000001L,
        "SUB V.2S lane wrapping" );
    state.writeV64( 30, 0x0004000300020001L );
    state.writeV64( 23, 0x0008000700060005L );
    execute( state, 0x2e77c3dd, null ); // umull v29.4s,v30.4h,v23.4h
    require( state.readV64( 29, false ) == 0x0000000c00000005L
        && state.readV64( 29, true ) == 0x0000002000000015L,
        "UMULL V.4S" );
    state.writeV128( 29, 0x8000000000000001L, 0xffffffff00000008L );
    state.writeV128( 24, 0xffffffff00000001L, 0xfffffffc00000002L );
    execute( state, 0x6eb847bd, null ); // ushl v29.4s,v29.4s,v24.4s
    require( state.readV64( 29, false ) == 0x4000000000000002L
        && state.readV64( 29, true ) == 0x0fffffff00000020L,
        "USHL V.4S" );
    state.writeV128( 4, 0x0004000300020001L, 0x0008000700060005L );
    state.writeV128( 25, 0x000e000d000c000bL, 0x001200110010000fL );
    execute( state, 0x4e59389e, null ); // zip1 v30.8h,v4.8h,v25.8h
    require( state.readV64( 30, false ) == 0x000c0002000b0001L
        && state.readV64( 30, true ) == 0x000e0004000d0003L,
        "ZIP1 V.8H" );
    state.writeV64( 31, 0xffffffff00000002L );
    state.writeV64( 29, 0x0000000300000004L );
    execute( state, 0x2ebdc3fc, null ); // umull v28.2d,v31.2s,v29.2s
    require( state.readV64( 28, false ) == 8
        && state.readV64( 28, true ) == 0x00000002fffffffdL,
        "UMULL V.2D" );
    state.writeV128( 30, 0x000000140000000aL, 0x000000280000001eL );
    state.writeV64( 6, 0x0004000300020001L );
    state.writeV64( 16, 0x0008000700060005L );
    execute( state, 0x2e7080de, null ); // umlal v30.4s,v6.4h,v16.4h
    require( state.readV64( 30, false ) == 0x000000200000000fL
        && state.readV64( 30, true ) == 0x0000004800000033L,
        "UMLAL V.4S" );
    state.writeV128( 26, 0x1122334455667788L, 0 );
    state.writeV128( 19, 0x000000123456789aL, 0x000000abcdef0123L );
    execute( state, 0x4f28867a, null ); // shrn2 v26.4s,v19.2d,#24
    require( state.readV64( 26, false ) == 0x1122334455667788L
        && state.readV64( 26, true ) == 0x0000abcd00001234L,
        "SHRN2 V.4S" );
    state.writeV128( 27, 0x000a000a000a000aL, 0x000a000a000a000aL );
    state.writeV128( 31, 0x0002000200020002L, 0x0002000200020002L );
    state.writeV128( 20, 0x0003000300030003L, 0x0003000300030003L );
    execute( state, 0x6e7497fb, null ); // mls v27.8h,v31.8h,v20.8h
    require( state.readV64( 27, false ) == 0x0004000400040004L
        && state.readV64( 27, true ) == 0x0004000400040004L,
        "MLS V.8H" );
    state.writeV128( 26, 0xffff0000ffff0000L, 0xaaaaaaaaaaaaaaaaL );
    state.writeV128( 27, 0x1111111111111111L, 0xffffffffffffffffL );
    state.writeV128( 5, 0x2222222222222222L, 0 );
    execute( state, 0x6e651f7a, null ); // bsl v26.16b,v27.16b,v5.16b
    require( state.readV64( 26, false ) == 0x1111222211112222L
        && state.readV64( 26, true ) == 0xaaaaaaaaaaaaaaaaL,
        "BSL V.16B" );
    state.writeV128( 23, 0x8000000000000001L, 0xffffffff00000002L );
    state.writeV128( 29, 0xffffffff00000001L, 0xfffffffe00000002L );
    execute( state, 0x4ebd46f7, null ); // sshl v23.4s,v23.4s,v29.4s
    require( state.readV64( 23, false ) == 0xc000000000000002L
        && state.readV64( 23, true ) == 0xffffffff00000008L,
        "SSHL V.4S" );
    state.writeV64( 24, 0x123456789abcdef0L );
    state.writeX( 3, 0x1900 );
    execute( state, 0x3c001478, memory ); // str b24,[x3],#1
    require( image.read( 0x1900, 1 ) == 0xf0 && state.readX( 3 ) == 0x1901,
        "STR B post-index" );
    execute( state, 0x6f00041f, null ); // mvni v31.4s,#0
    require( state.readV64( 31, false ) == -1L
        && state.readV64( 31, true ) == -1L, "MVNI zero" );
    execute( state, 0x2f00041f, null ); // mvni v31.2s,#0
    require( state.readV64( 31, false ) == -1L
        && state.readV64( 31, true ) == 0, "MVNI zero 2S" );
    execute( state, 0x6f00a5b5, null ); // mvni v21.8h,#13,lsl #8
    require( state.readV64( 21, false ) == 0xf2fff2fff2fff2ffL
        && state.readV64( 21, true ) == 0xf2fff2fff2fff2ffL,
        "MVNI V.8H shifted" );
    execute( state, 0x4f01e664, null ); // movi v4.16b,#0x33
    require( state.readV64( 4, false ) == 0x3333333333333333L
        && state.readV64( 4, true ) == 0x3333333333333333L, "MOVI byte" );
    state.writeV128( 15, 0, -1L );
    execute( state, 0x0f06e7ef, null ); // movi v15.8b,#0xdf
    require( state.readV64( 15, false ) == 0xdfdfdfdfdfdfdfdfL
        && state.readV64( 15, true ) == 0, "MOVI byte 8B" );
    state.writeV128( 31, 0, -1L );
    execute( state, 0x2f00e5ff, null ); // movi d31,#0x00000000ffffffff
    require( state.readV64( 31, false ) == 0x00000000ffffffffL
        && state.readV64( 31, true ) == 0, "MOVI D byte mask" );

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
    state.writeV128( 30, 0x0102030405060708L, 0x1112131415161718L );
    state.writeV128( 31, 0x2122232425262728L, 0x3132333435363738L );
    state.writeX( 21, 0x1100 );
    execute( state, 0x4c00a2be, memory ); // st1 {v30.16b,v31.16b},[x21]
    require( image.read( 0x1100, 8 ) == 0x0102030405060708L
        && image.read( 0x1118, 8 ) == 0x3132333435363738L,
        "ST1 two V.16B" );
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
    execute( state, 0x4ea21c23, null ); // orr v3.16b,v1.16b,v2.16b
    require( state.readV64( 3, false ) == 0xffff00ffffff00ffL
        && state.readV64( 3, true ) == -1L, "ORR V.16B" );
    execute( state, 0x0ea21c23, null ); // orr v3.8b,v1.8b,v2.8b
    require( state.readV64( 3, false ) == 0xffff00ffffff00ffL
        && state.readV64( 3, true ) == 0, "ORR V.8B" );

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
    state.writeV128( 31, 0xffffffff00000001L, 0x0000000300000002L );
    execute( state, 0x6ebfa7ff, null ); // umaxp v31.4s,v31.4s,v31.4s
    require( state.readV64( 31, false ) == 0x00000003ffffffffL
        && state.readV64( 31, true ) == 0x00000003ffffffffL,
        "UMAXP V.4S" );
    state.writeV64( 31, 0x80000000ffffffffL );
    execute( state, 0x0f20a7ff, null ); // sshll v31.2d,v31.2s,#0
    require( state.readV64( 31, false ) == -1L
        && state.readV64( 31, true ) == 0xffffffff80000000L,
        "SSHLL V.2D" );
    state.writeV128( 28, 0x807f0403020100ffL, 0 );
    execute( state, 0x2f08a79a, null ); // ushll v26.8h,v28.8b,#0
    require( state.readV64( 26, false ) == 0x00020001000000ffL
        && state.readV64( 26, true ) == 0x0080007f00040003L,
        "USHLL V.8H" );
    state.writeV128( 24, 0x0001000100010001L, 0x0001000100010001L );
    state.writeV128( 26, 0x0002000200020002L, 0x0002000200020002L );
    state.writeV128( 3, 0x0003000300030003L, 0x0003000300030003L );
    execute( state, 0x4e639758, null ); // mla v24.8h,v26.8h,v3.8h
    require( state.readV64( 24, false ) == 0x0007000700070007L
        && state.readV64( 24, true ) == 0x0007000700070007L,
        "MLA V.8H" );
    state.writeV128( 2, 0x0807060504030201L, 0x100f0e0d0c0b0a09L );
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
    state.writeX( 3, 0x0123456789abcdefL );
    execute( state, 0x9e67007f, null ); // fmov d31,x3
    require( state.readV64( 31, false ) == 0x0123456789abcdefL
        && state.readV64( 31, true ) == 0, "FMOV D,X" );
    state.writeX( 1, 0x1234567887654321L );
    execute( state, 0x1e27003e, null ); // fmov s30,w1
    require( state.readV64( 30, false ) == 0x87654321L
        && state.readV64( 30, true ) == 0, "FMOV S,W" );
    execute( state, 0x1e2603c2, null ); // fmov w2,s30
    require( state.readX( 2 ) == 0x87654321L, "FMOV W,S" );
    state.writeV128( 31, 0x0102030405060708L, 0x1112131415161718L );
    state.writeX( 21, 0x2122232425262728L );
    execute( state, 0x4e181ebf, null ); // mov v31.d[1],x21
    require( state.readV64( 31, false ) == 0x0102030405060708L
        && state.readV64( 31, true ) == 0x2122232425262728L,
        "MOV V.D,X" );
    state.writeX( 0, 0xaabbccddL );
    execute( state, 0x4e0c1c1e, null ); // mov v30.s[1],w0
    require( state.readV64( 30, false ) == 0xaabbccdd87654321L,
        "MOV V.S,W" );
    state.writeV128( 31, 0xffffffffffffffffL, 0x123456789abcdef0L );
    state.writeV128( 30, 2, 0xfedcba9876543210L );
    execute( state, 0x4efe87ff, null ); // add v31.2d,v31.2d,v30.2d
    require( state.readV64( 31, false ) == 1
        && state.readV64( 31, true ) == 0x1111111111111100L,
        "ADD V.2D" );
    execute( state, 0x4f00a49f, null ); // movi v31.8h,#4,lsl #8
    require( state.readV64( 31, false ) == 0x0400040004000400L
        && state.readV64( 31, true ) == 0x0400040004000400L,
        "MOVI V.8H shifted" );
    state.writeV128( 30, 0xfffffffe00000003L, 0 );
    execute( state, 0x0ebe13ff, null ); // saddw v31.2d,v31.2d,v30.2s
    require( state.readV64( 31, false ) == 0x0400040004000403L
        && state.readV64( 31, true ) == 0x04000400040003feL,
        "SADDW V.2D" );
    state.writeV64( 31, Double.doubleToRawLongBits( -1.0 ) );
    execute( state, 0x1e6023e8, null ); // fcmp d31,#0.0
    require( state.negative() && !state.zero() && !state.carry()
        && !state.overflow(), "FCMP D,#0 less" );
    state.writeV64( 30, Double.doubleToRawLongBits( -1.0 ) );
    execute( state, 0x1e7e23e0, null ); // fcmp d31,d30
    require( !state.negative() && state.zero() && state.carry()
        && !state.overflow(), "FCMP D,D equal" );
    execute( state, 0x1e7e23f0, null ); // fcmpe d31,d30
    require( !state.negative() && state.zero() && state.carry()
        && !state.overflow(), "FCMPE D,D equal" );
    state.writeV64( 31, 0x7ff8000000000000L );
    execute( state, 0x1e6023e8, null );
    require( !state.negative() && !state.zero() && state.carry()
        && state.overflow(), "FCMP D unordered" );
    state.writeV128( 30, 0x0807060504030201L, 0x100f0e0d0c0b0a09L );
    execute( state, 0x6e200bde, null ); // rev32 v30.16b,v30.16b
    require( state.readV64( 30, false ) == 0x0506070801020304L
        && state.readV64( 30, true ) == 0x0d0e0f10090a0b0cL,
        "REV32 V.16B" );
    state.writeV128( 29, 0x0807060504030201L, 0x100f0e0d0c0b0a09L );
    state.writeV128( 25, 0x1817161514131211L, 0x201f1e1d1c1b1a19L );
    execute( state, 0x4e193bba, null ); // zip1 v26.16b,v29.16b,v25.16b
    require( state.readV64( 26, false ) == 0x1404130312021101L
        && state.readV64( 26, true ) == 0x1808170716061505L,
        "ZIP1 V.16B" );
    execute( state, 0x4e197bbc, null ); // zip2 v28.16b,v29.16b,v25.16b
    require( state.readV64( 28, false ) == 0x1c0c1b0b1a0a1909L
        && state.readV64( 28, true ) == 0x20101f0f1e0e1d0dL,
        "ZIP2 V.16B" );
    state.writeV64( 29, 0xff807f0100fe0280L );
    execute( state, 0x0f08a7bb, null ); // sxtl v27.8h,v29.8b
    require( state.readV64( 27, false ) == 0x0000fffe0002ff80L
        && state.readV64( 27, true ) == 0xffffff80007f0001L,
        "SXTL V.8H" );
    state.writeV128( 29, 0, 0x7f80ff0100fe0280L );
    execute( state, 0x4f08a7bd, null ); // sxtl2 v29.8h,v29.16b
    require( state.readV64( 29, false ) == 0x0000fffe0002ff80L
        && state.readV64( 29, true ) == 0x007fff80ffff0001L,
        "SXTL2 V.8H" );
    state.writeV128( 30, 0xffffffff00000001L, 0x00000003fffffffeL );
    state.writeV64( 26, 0xffff000300020001L );
    execute( state, 0x2e7a13de, null ); // uaddw v30.4s,v30.4s,v26.4h
    require( state.readV64( 30, false ) == 0x0000000100000002L
        && state.readV64( 30, true ) == 0x0001000200000001L,
        "UADDW V.4S" );
    state.writeV128( 31, 0x00000001ffffffffL, 0xffffffff00000003L );
    state.writeV64( 27, 0x8000ffff0002fffeL );
    execute( state, 0x0e7b13ff, null ); // saddw v31.4s,v31.4s,v27.4h
    require( state.readV64( 31, false ) == 0x00000003fffffffdL
        && state.readV64( 31, true ) == 0xffff7fff00000002L,
        "SADDW V.4S" );
    state.writeV128( 30, 0xffffffff00000001L, 0x00000003fffffffeL );
    state.writeV128( 26, 0, 0xffff000300020001L );
    execute( state, 0x6e7a13de, null ); // uaddw2 v30.4s,v30.4s,v26.8h
    require( state.readV64( 30, false ) == 0x0000000100000002L
        && state.readV64( 30, true ) == 0x0001000200000001L,
        "UADDW2 V.4S" );
    state.writeV128( 30, 0x00000001ffffffffL, 0xffffffff00000003L );
    state.writeV128( 26, 0, 0x8000ffff0002fffeL );
    execute( state, 0x4e7a13de, null ); // saddw2 v30.4s,v30.4s,v26.8h
    require( state.readV64( 30, false ) == 0x00000003fffffffdL
        && state.readV64( 30, true ) == 0xffff7fff00000002L,
        "SADDW2 V.4S" );
    state.writeV128( 30, 0x00000002ffffffffL, 0xfffffffe00000003L );
    execute( state, 0x4eb1bbde, null ); // addv s30,v30.4s
    require( state.readV64( 30, false ) == 2
        && state.readV64( 30, true ) == 0, "ADDV V.4S" );
    state.writeX( 0, 0x2200 );
    image.write( 0x2200, 0x0123456789abcdefL, 8 );
    execute( state, 0x4d40cc1f, memory ); // ld1r {v31.2d},[x0]
    require( state.readV64( 31, false ) == 0x0123456789abcdefL
        && state.readV64( 31, true ) == 0x0123456789abcdefL,
        "LD1R V.2D" );
    state.writeX( 1, -42 );
    execute( state, 0x9e62003f, null ); // scvtf d31,x1
    require( Double.longBitsToDouble( state.readV64( 31, false ) ) == -42.0,
        "SCVTF D,X" );
    state.writeX( 1, -1 );
    execute( state, 0x9e63003f, null ); // ucvtf d31,x1
    require( Double.longBitsToDouble( state.readV64( 31, false ) )
        == 0x1.0p64, "UCVTF D,X" );
    state.writeV64( 31, Double.doubleToRawLongBits( 42.0 ) );
    state.writeV64( 30, Double.doubleToRawLongBits( 2.0 ) );
    execute( state, 0x1e7e1bff, null ); // fdiv d31,d31,d30
    require( Double.longBitsToDouble( state.readV64( 31, false ) ) == 21.0,
        "FDIV D" );
    execute( state, 0x1e60407f, null ); // fmov d31,d3
    require( state.readV64( 31, false ) == 0x9a897867564534ffL,
        "FMOV D,D" );
    execute( state, 0x1e20400f, null ); // fmov s15,s0
    require( state.readV64( 15, false ) == 0x41414141L, "FMOV S,S" );
    state.writeV64( 15, Float.floatToRawIntBits( -1.0f ) & 0xffffffffL );
    execute( state, 0x1e2021e8, null ); // fcmp s15,#0.0
    require( state.negative() && !state.zero() && !state.carry()
        && !state.overflow(), "FCMP S,#0 less" );
    state.writeV64( 30, Float.floatToRawIntBits( 40.0f ) & 0xffffffffL );
    state.writeV64( 31, Float.floatToRawIntBits( 2.0f ) & 0xffffffffL );
    execute( state, 0x1e3f2bdd, null ); // fadd s29,s30,s31
    require( Float.intBitsToFloat( (int)state.readV64( 29, false ) ) == 42.0f,
        "FADD S" );
    execute( state, 0x1e601016, null ); // fmov d22,#2.0
    require( state.readV64( 22, false )
        == Double.doubleToRawLongBits( 2.0 ), "FMOV D,#imm" );
    execute( state, 0x1e201017, null ); // fmov s23,#2.0
    require( state.readV64( 23, false )
        == (Float.floatToRawIntBits( 2.0f ) & 0xffffffffL), "FMOV S,#imm" );
    state.writeV64( 28, -1L );
    execute( state, 0x7e61db95, null ); // ucvtf d21,d28
    require( Double.longBitsToDouble( state.readV64( 21, false ) )
        == 0x1.0p64, "UCVTF D,D" );
    state.writeV64( 30, Float.floatToRawIntBits( 1.5f ) & 0xffffffffL );
    execute( state, 0x1e22c3de, null ); // fcvt d30,s30
    require( Double.longBitsToDouble( state.readV64( 30, false ) ) == 1.5,
        "FCVT D,S" );
    execute( state, 0x1e6243de, null ); // fcvt s30,d30
    require( state.readV64( 30, false )
        == (Float.floatToRawIntBits( 1.5f ) & 0xffffffffL), "FCVT S,D" );
    state.writeV64( 31, Double.doubleToRawLongBits( -1.5 ) );
    execute( state, 0x1e6543ff, null ); // frintm d31,d31
    require( Double.longBitsToDouble( state.readV64( 31, false ) ) == -2.0,
        "FRINTM D" );
    state.writeV64( 29, Double.doubleToRawLongBits( 42.75 ) );
    execute( state, 0x9e7903a2, null ); // fcvtzu x2,d29
    require( state.readX( 2 ) == 42, "FCVTZU X,D" );
    state.writeV64( 29, Double.doubleToRawLongBits( -42.75 ) );
    execute( state, 0x9e7803a2, null ); // fcvtzs x2,d29
    require( state.readX( 2 ) == -42, "FCVTZS X,D" );
    state.writeV64( 28, Double.doubleToRawLongBits( 42.75 ) );
    execute( state, 0x9e710381, null ); // fcvtmu x1,d28
    require( state.readX( 1 ) == 42, "FCVTMU X,D" );
    state.writeV64( 28, Double.doubleToRawLongBits( -42.25 ) );
    execute( state, 0x9e700381, null ); // fcvtms x1,d28
    require( state.readX( 1 ) == -43, "FCVTMS X,D" );
    state.writeV64( 24, Double.doubleToRawLongBits( 42.25 ) );
    execute( state, 0x9e690301, null ); // fcvtpu x1,d24
    require( state.readX( 1 ) == 43, "FCVTPU X,D" );
    state.writeV64( 24, Double.doubleToRawLongBits( -42.75 ) );
    execute( state, 0x9e680301, null ); // fcvtps x1,d24
    require( state.readX( 1 ) == -42, "FCVTPS X,D" );
    state.writeV64( 24, Double.doubleToRawLongBits( -42.5 ) );
    execute( state, 0x9e640301, null ); // fcvtas x1,d24
    require( state.readX( 1 ) == -43, "FCVTAS X,D" );
    state.writeV64( 24, Double.doubleToRawLongBits( 42.5 ) );
    execute( state, 0x9e650301, null ); // fcvtau x1,d24
    require( state.readX( 1 ) == 43, "FCVTAU X,D" );
    state.writeV64( 31, Float.floatToRawIntBits( 1.0f ) & 0xffffffffL );
    state.writeV64( 0, Float.floatToRawIntBits( 2.0f ) & 0xffffffffL );
    state.setNzcv( false, true, false, false );
    execute( state, 0x1e200fe0, null ); // fcsel s0,s31,s0,eq
    require( state.readV64( 0, false )
        == (Float.floatToRawIntBits( 1.0f ) & 0xffffffffL), "FCSEL S EQ" );
    state.writeV64( 0, 0x4141414141414141L );
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

    state.writeV128( 31, 0x7766554433221100L, 0xffeeddccbbaa9988L );
    state.writeX( 18, 0x1020 );
    execute( state, 0x4d005a5f, memory ); // st1 {v31.h}[7],[x18]
    require( image.read( 0x1020, 2 ) == 0xffeeL, "ST1 vector H lane" );
    state.writeV64( 29, 0x1234L );
    state.writeX( 0, 0x1020 );
    execute( state, 0x7d00101d, memory ); // str h29,[x0,#8]
    require( image.read( 0x1028, 2 ) == 0x1234L, "STR H unsigned offset" );

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
