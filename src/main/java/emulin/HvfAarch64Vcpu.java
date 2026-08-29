// ----------------------------------------
//  Apple Hypervisor.framework AArch64 vCPU (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Thread-confined vCPU wrapper matching Hypervisor.framework ownership rules. */
final class HvfAarch64Vcpu implements Aarch64HvVcpu {
  private static final long EXIT_INFO_SIZE = 32L;

  private final Thread owner = Thread.currentThread();
  // hv_vcpus_exit is the sole operation Apple permits from a non-owning
  // thread, so its one-element ID array must live in a shared arena.
  private final Arena control = Arena.ofShared();
  private final MemorySegment valueOut = control.allocate( ValueLayout.JAVA_LONG );
  private final MemorySegment vcpuArray = control.allocate( ValueLayout.JAVA_LONG );
  private final MemorySegment exitInfo;
  private final long vcpu;
  private volatile boolean closed;

  HvfAarch64Vcpu() throws Throwable {
    MemorySegment vcpuOut = control.allocate( ValueLayout.JAVA_LONG );
    MemorySegment exitOut = control.allocate( ValueLayout.ADDRESS );
    long createdVcpu = 0L;
    MemorySegment createdExit;
    try {
      Aarch64HvBindings.check( Aarch64HvBindings.vcpuCreate( vcpuOut, exitOut ),
          "hv_vcpu_create" );
      createdVcpu = vcpuOut.get( ValueLayout.JAVA_LONG, 0L );
      createdExit = exitOut.get( ValueLayout.ADDRESS, 0L );
      if( createdExit.address() == 0L ) {
        throw new IllegalStateException( "hv_vcpu_create returned a null exit-info pointer" );
      }
    } catch( Throwable t ) {
      if( createdVcpu != 0L ) {
        try { Aarch64HvBindings.vcpuDestroy( createdVcpu ); } catch( Throwable ignore ) {}
      }
      control.close();
      throw t;
    }
    vcpu = createdVcpu;
    vcpuArray.set( ValueLayout.JAVA_LONG, 0L, vcpu );
    exitInfo = createdExit.reinterpret( EXIT_INFO_SIZE );
  }

  @Override public long getRegister( int register ) throws Throwable {
    ensureOwnerAndOpen();
    checkGeneralRegister( register );
    Aarch64HvBindings.check( Aarch64HvBindings.vcpuGetReg( vcpu, register, valueOut ),
        "hv_vcpu_get_reg" );
    return valueOut.get( ValueLayout.JAVA_LONG, 0L );
  }

  @Override public void setRegister( int register, long value ) throws Throwable {
    ensureOwnerAndOpen();
    checkGeneralRegister( register );
    Aarch64HvBindings.check( Aarch64HvBindings.vcpuSetReg( vcpu, register, value ),
        "hv_vcpu_set_reg" );
  }

  @Override public long getSystemRegister( int register ) throws Throwable {
    ensureOwnerAndOpen();
    checkSystemRegister( register );
    Aarch64HvBindings.check( Aarch64HvBindings.vcpuGetSysReg( vcpu, register, valueOut ),
        "hv_vcpu_get_sys_reg" );
    return valueOut.get( ValueLayout.JAVA_LONG, 0L );
  }

  @Override public void setSystemRegister( int register, long value ) throws Throwable {
    ensureOwnerAndOpen();
    checkSystemRegister( register );
    Aarch64HvBindings.check( Aarch64HvBindings.vcpuSetSysReg( vcpu, register, value ),
        "hv_vcpu_set_sys_reg" );
  }

  @Override public Exit run() throws Throwable {
    ensureOwnerAndOpen();
    Aarch64HvBindings.check( Aarch64HvBindings.vcpuRun( vcpu ), "hv_vcpu_run" );
    int rawReason = exitInfo.get( ValueLayout.JAVA_INT, 0L );
    ExitReason reason = switch( rawReason ) {
      case Aarch64HvBindings.HV_EXIT_REASON_CANCELED -> ExitReason.CANCELED;
      case Aarch64HvBindings.HV_EXIT_REASON_EXCEPTION -> ExitReason.EXCEPTION;
      case Aarch64HvBindings.HV_EXIT_REASON_VTIMER_ACTIVATED -> ExitReason.VTIMER_ACTIVATED;
      default -> ExitReason.UNKNOWN;
    };
    return new Exit( reason,
        exitInfo.get( ValueLayout.JAVA_LONG, 8L ),
        exitInfo.get( ValueLayout.JAVA_LONG, 16L ),
        exitInfo.get( ValueLayout.JAVA_LONG, 24L ) );
  }

  @Override public void requestExit() throws Throwable {
    if( closed ) return;
    Aarch64HvBindings.check( Aarch64HvBindings.vcpusExit( vcpuArray, 1 ), "hv_vcpus_exit" );
  }

  @Override public void close() {
    if( closed ) return;
    ensureOwner();
    try {
      Aarch64HvBindings.check( Aarch64HvBindings.vcpuDestroy( vcpu ), "hv_vcpu_destroy" );
    } catch( Throwable t ) {
      throw new IllegalStateException( "failed to destroy AArch64 HVF vCPU", t );
    } finally {
      closed = true;
      control.close();
    }
  }

  private void checkGeneralRegister( int register ) {
    if( register < Aarch64HvBindings.HV_REG_X0 || register > Aarch64HvBindings.HV_REG_CPSR ) {
      throw new IllegalArgumentException( "invalid AArch64 HVF general register: " + register );
    }
  }

  private void checkSystemRegister( int register ) {
    if( register < 0 || register > 0xffff ) {
      throw new IllegalArgumentException( "invalid AArch64 HVF system register: " + register );
    }
  }

  private void ensureOwnerAndOpen() {
    ensureOwner();
    if( closed ) throw new IllegalStateException( "AArch64 HVF vCPU is closed" );
  }

  private void ensureOwner() {
    if( Thread.currentThread() != owner ) {
      throw new IllegalStateException( "AArch64 HVF vCPU must be used by its owning thread" );
    }
  }
}
