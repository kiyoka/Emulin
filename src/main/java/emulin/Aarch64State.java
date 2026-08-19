// ----------------------------------------
//  AArch64 architectural state (issue #951 Phase 1)
// ----------------------------------------
package emulin;

final class Aarch64State {
  static final int REGISTER_COUNT = 31;

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
