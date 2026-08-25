// ----------------------------------------
//  AArch64 architectural state (issue #951 Phase 1)
// ----------------------------------------
package emulin;

final class Aarch64State {
  static final int REGISTER_COUNT = 31;
  static final int N_FLAG = 1 << 31;
  static final int Z_FLAG = 1 << 30;
  static final int C_FLAG = 1 << 29;
  static final int V_FLAG = 1 << 28;

  final long[] x = new long[ REGISTER_COUNT ];
  long sp;
  long pc;
  int nzcv;
  long tpidrEl0;

  long readX( int register ) {
    return register == 31 ? 0L : x[ register ];
  }

  void writeX( int register, long value ) {
    if( register != 31 ) x[ register ] = value;
  }

  long readRegister( int register, int width, boolean stackPointer ) {
    long value = register == 31 ? (stackPointer ? sp : 0L) : x[ register ];
    return width == 32 ? value & 0xffffffffL : value;
  }

  void writeRegister( int register, long value, int width, boolean stackPointer ) {
    value = width == 32 ? value & 0xffffffffL : value;
    if( register == 31 ) {
      if( stackPointer ) sp = value;
    } else {
      x[ register ] = value;
    }
  }

  void setNzcv( boolean negative, boolean zero, boolean carry, boolean overflow ) {
    nzcv = (negative ? N_FLAG : 0) | (zero ? Z_FLAG : 0)
        | (carry ? C_FLAG : 0) | (overflow ? V_FLAG : 0);
  }

  boolean negative() { return (nzcv & N_FLAG) != 0; }
  boolean zero() { return (nzcv & Z_FLAG) != 0; }
  boolean carry() { return (nzcv & C_FLAG) != 0; }
  boolean overflow() { return (nzcv & V_FLAG) != 0; }

  Aarch64State copy() {
    Aarch64State result = new Aarch64State();
    System.arraycopy( x, 0, result.x, 0, x.length );
    result.sp = sp;
    result.pc = pc;
    result.nzcv = nzcv;
    result.tpidrEl0 = tpidrEl0;
    return result;
  }
}
