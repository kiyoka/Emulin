// ----------------------------------------
//  Apple Silicon AArch64 HVF syscall bridge (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

/**
 * Transfers an EL0 Linux SVC captured by the EL1 HVF vector to Emulin's
 * architecture-neutral syscall implementation.
 */
final class Aarch64HvSyscallBridge {
  private static final int ESR_EC_HVC64 = 0x16;
  private static final int ESR_EC_SVC64 = 0x15;
  private static final int SYSCALL_REGISTER = Aarch64HvBindings.HV_REG_X0 + 8;

  record Dispatch( int number, int immediate, long result, long resumePc ) {}

  Dispatch dispatch( Aarch64HvVcpu vcpu, Aarch64HvVcpu.Exit exit,
                     SyscallAarch64 syscall ) throws Throwable {
    if( exit.reason() != Aarch64HvVcpu.ExitReason.EXCEPTION
        || exit.exceptionClass() != ESR_EC_HVC64 ) {
      throw new IllegalArgumentException( "expected an EL1 HVC exit, got " + exit );
    }

    long guestEsr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ESR_EL1 );
    int guestExceptionClass = (int)(guestEsr >>> 26) & 0x3f;
    if( guestExceptionClass != ESR_EC_SVC64 ) {
      throw new IllegalStateException( "EL1 HVC did not originate from an EL0 SVC: ESR_EL1=0x"
          + Long.toHexString( guestEsr ) );
    }

    int number = (int)vcpu.getRegister( SYSCALL_REGISTER );
    long result = syscall.callAarch64( number,
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 ),
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 1 ),
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 2 ),
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 3 ),
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 4 ),
        vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 5 ) );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_X0, result );

    // On an HVF HVC exit PC already points at the following instruction. The
    // vector places ERET there, so resuming restores SPSR_EL1/ELR_EL1 and EL0.
    long resumePc = vcpu.getRegister( Aarch64HvBindings.HV_REG_PC );
    return new Dispatch( number, (int)(guestEsr & 0xffffL), result, resumePc );
  }
}
