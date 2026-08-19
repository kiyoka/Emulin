// ----------------------------------------
//  Minimal AArch64 instruction semantics (issue #951 Phase 1)
// ----------------------------------------
package emulin;

final class Aarch64Executor {
  long execute( Aarch64State state, Aarch64DecodedInsn instruction,
                SyscallAarch64 syscall ) {
    long nextPc = state.pc + 4;
    switch( instruction.operation ) {
      case MOVZ -> state.writeX( instruction.rd,
          instruction.immediate << instruction.shiftAmount );
      case ADR -> state.writeX( instruction.rd, state.pc + instruction.immediate );
      case SVC -> {
        long result = syscall.callAarch64(
            (int)state.readX( 8 ),
            state.readX( 0 ), state.readX( 1 ), state.readX( 2 ),
            state.readX( 3 ), state.readX( 4 ), state.readX( 5 ) );
        state.writeX( 0, result );
      }
      default -> throw new UnsupportedOperationException(
          "AArch64 execution semantics not implemented for " + instruction.operation );
    }
    return nextPc;
  }
}
