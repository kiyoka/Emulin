// ----------------------------------------
//  AArch64 software/HVF architectural-state transfer (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

/** Copies the userspace-visible AArch64 state between both execution engines. */
final class Aarch64HvStateSync {
  private static final long PSTATE_EL0T_MASKED = 0x3c0L;
  private static final int NZCV_MASK = 0xf0000000;

  private Aarch64HvStateSync() {}

  static void load( Aarch64State state, Aarch64HvVcpu vcpu ) throws Throwable {
    for( int register = 0; register < Aarch64State.REGISTER_COUNT; register++ ) {
      vcpu.setRegister( Aarch64HvBindings.HV_REG_X0 + register, state.x[ register ] );
    }
    vcpu.setRegister( Aarch64HvBindings.HV_REG_PC, state.pc );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_CPSR,
        PSTATE_EL0T_MASKED | Integer.toUnsignedLong( state.nzcv & NZCV_MASK ) );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_FPCR, state.fpcr );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_FPSR, state.fpsr );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_SP_EL0, state.sp );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_TPIDR_EL0, state.tpidrEl0 );
    for( int register = 0; register < 32; register++ ) {
      vcpu.setVectorRegister( register,
          new Aarch64HvVcpu.Vector128( state.vLo[ register ], state.vHi[ register ] ) );
    }
  }

  /** Restores an EL0 context while the vCPU is stopped in the EL1 SVC vector. */
  static void loadExceptionReturn( Aarch64State state, Aarch64HvVcpu vcpu )
      throws Throwable {
    for( int register = 0; register < Aarch64State.REGISTER_COUNT; register++ ) {
      vcpu.setRegister( Aarch64HvBindings.HV_REG_X0 + register, state.x[ register ] );
    }
    vcpu.setRegister( Aarch64HvBindings.HV_REG_FPCR, state.fpcr );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_FPSR, state.fpsr );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_SP_EL0, state.sp );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_TPIDR_EL0, state.tpidrEl0 );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_ELR_EL1, state.pc );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_SPSR_EL1,
        PSTATE_EL0T_MASKED | Integer.toUnsignedLong( state.nzcv & NZCV_MASK ) );
    for( int register = 0; register < 32; register++ ) {
      vcpu.setVectorRegister( register,
          new Aarch64HvVcpu.Vector128( state.vLo[ register ], state.vHi[ register ] ) );
    }
  }

  static void save( Aarch64HvVcpu vcpu, Aarch64State state ) throws Throwable {
    for( int register = 0; register < Aarch64State.REGISTER_COUNT; register++ ) {
      state.x[ register ] = vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + register );
    }
    state.pc = vcpu.getRegister( Aarch64HvBindings.HV_REG_PC );
    state.nzcv = (int)vcpu.getRegister( Aarch64HvBindings.HV_REG_CPSR ) & NZCV_MASK;
    state.fpcr = vcpu.getRegister( Aarch64HvBindings.HV_REG_FPCR );
    state.fpsr = vcpu.getRegister( Aarch64HvBindings.HV_REG_FPSR );
    state.sp = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_SP_EL0 );
    state.tpidrEl0 = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_TPIDR_EL0 );
    for( int register = 0; register < 32; register++ ) {
      Aarch64HvVcpu.Vector128 vector = vcpu.getVectorRegister( register );
      state.vLo[ register ] = vector.low();
      state.vHi[ register ] = vector.high();
    }
    // The exclusive monitor is backend-private and cannot survive a switch.
    state.exclusiveAddress = -1L;
    state.exclusiveValue = 0L;
    state.exclusiveSize = 0;
  }
}
