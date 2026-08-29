// ----------------------------------------
//  Apple Silicon AArch64 Hypervisor.framework smoke (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes EL1/HVF lifecycle and a real EL0 SyscallAarch64 round trip. */
public final class Aarch64HvSmoke {
  private static final int ESR_EC_HVC64 = 0x16;
  private static final int ESR_EC_SVC64 = 0x15;
  private static final long PSTATE_EL1H_MASKED = 0x3c5L;
  private static final long PSTATE_EL0T_MASKED = 0x3c0L;
  private static final long USER_CODE = 0x100L;
  private static final long VECTOR_BASE = 0x800L;
  private static final long LOWER_A64_SYNC_VECTOR = VECTOR_BASE + 0x400L;

  private Aarch64HvSmoke() {}

  public static void main( String[] args ) throws Throwable {
    if( !Aarch64HvBindings.probe() ) {
      System.err.println( "[Aarch64HvSmoke] " + Aarch64HvBindings.describeAvailability() );
      System.exit( 2 );
    }

    int iterations = Integer.parseInt( System.getenv().getOrDefault(
        "EMULIN_HVF_SMOKE_ITERATIONS", "3" ) );
    require( iterations > 0, "EMULIN_HVF_SMOKE_ITERATIONS must be positive" );
    int page = Aarch64HvBindings.pageSize();
    MemorySegment ram = HvfAarch64Vm.allocateGuestRam( page );
    try {
      // movz x0,#0xcafe; movz x1,#0xbeef; hvc #0
      ram.set( ValueLayout.JAVA_INT, 0L, 0xd2995fc0 );
      ram.set( ValueLayout.JAVA_INT, 4L, 0xd297dde1 );
      ram.set( ValueLayout.JAVA_INT, 8L, 0xd4000002 );

      int maxVcpus = 0;
      for( int iteration = 0; iteration < iterations; iteration++ ) {
        maxVcpus = runOnce( ram, page );
      }
      runCrossThreadCancellation( ram, page );
      System.out.println( "AArch64 HVF smoke OK: iterations=" + iterations
          + " maxVcpus=" + maxVcpus
          + " EL1-HVC, EL0-SVC/getpid/ERET, and cross-thread cancellation passed" );
    } finally {
      HvfAarch64Vm.freeGuestRam( ram, page );
    }
  }

  private static void runCrossThreadCancellation( MemorySegment ram, int page )
      throws Throwable {
    final long loopAddress = 0x200L;
    ram.set( ValueLayout.JAVA_INT, loopAddress, 0x14000000 ); // b .
    try( Aarch64HvVm vm = new HvfAarch64Vm() ) {
      vm.mapGuestRam( ram, 0L, page );
      CountDownLatch ready = new CountDownLatch( 1 );
      AtomicReference<Aarch64HvVcpu> live = new AtomicReference<>();
      AtomicReference<Aarch64HvVcpu.Exit> result = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread worker = new Thread( () -> {
        try( Aarch64HvVcpu vcpu = vm.createVcpu() ) {
          vcpu.setRegister( Aarch64HvBindings.HV_REG_PC, loopAddress );
          vcpu.setRegister( Aarch64HvBindings.HV_REG_CPSR, PSTATE_EL1H_MASKED );
          live.set( vcpu );
          ready.countDown();
          result.set( vcpu.run() );
        } catch( Throwable t ) {
          failure.set( t );
          ready.countDown();
        }
      }, "aarch64-hvf-cancel-smoke" );
      worker.setDaemon( true );
      worker.start();
      require( ready.await( 5, TimeUnit.SECONDS ), "HVF cancellation worker did not start" );
      if( failure.get() != null ) throw new AssertionError( "HVF cancellation setup failed", failure.get() );
      live.get().requestExit();
      worker.join( 5000L );
      require( !worker.isAlive(), "hv_vcpus_exit did not stop the running vCPU" );
      if( failure.get() != null ) throw new AssertionError( "HVF cancellation failed", failure.get() );
      require( result.get() != null
              && result.get().reason() == Aarch64HvVcpu.ExitReason.CANCELED,
          "unexpected cancellation exit: " + result.get() );
    }
  }

  private static int runOnce( MemorySegment ram, int page ) throws Throwable {
    try( Aarch64HvVm vm = new HvfAarch64Vm() ) {
      vm.mapGuestRam( ram, 0L, page );
      int maxVcpus = vm.maxVcpus();
      try( Aarch64HvVcpu vcpu = vm.createVcpu() ) {
        vcpu.setRegister( Aarch64HvBindings.HV_REG_PC, 0L );
        vcpu.setRegister( Aarch64HvBindings.HV_REG_CPSR, PSTATE_EL1H_MASKED );
        Aarch64HvVcpu.Exit exit = vcpu.run();
        long x0 = vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 );
        long x1 = vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 1 );
        long pc = vcpu.getRegister( Aarch64HvBindings.HV_REG_PC );
        require( exit.reason() == Aarch64HvVcpu.ExitReason.EXCEPTION
            && exit.exceptionClass() == ESR_EC_HVC64
            && x0 == 0xcafeL && x1 == 0xbeefL,
            "unexpected EL1 HVC result: reason=" + exit.reason()
                + " ec=0x" + Integer.toHexString( exit.exceptionClass() )
                + " esr=0x" + Long.toHexString( exit.syndrome() )
                + " x0=0x" + Long.toHexString( x0 )
                + " x1=0x" + Long.toHexString( x1 )
                + " pc=0x" + Long.toHexString( pc ) );

        runEl0SvcRoundTrip( ram, vcpu );
      }
      return maxVcpus;
    }
  }

  private static void runEl0SvcRoundTrip( MemorySegment ram, Aarch64HvVcpu vcpu )
      throws Throwable {
    // EL0: movz x8,#172 (getpid); movz x0,#0xbeef; svc #0; svc #1; b .
    ram.set( ValueLayout.JAVA_INT, USER_CODE,      0xd2801588 );
    ram.set( ValueLayout.JAVA_INT, USER_CODE + 4,  0xd297dde0 );
    ram.set( ValueLayout.JAVA_INT, USER_CODE + 8,  0xd4000001 );
    ram.set( ValueLayout.JAVA_INT, USER_CODE + 12, 0xd4000021 );
    ram.set( ValueLayout.JAVA_INT, USER_CODE + 16, 0x14000000 );
    // EL1 lower-AArch64 synchronous vector: hvc #0; eret
    ram.set( ValueLayout.JAVA_INT, LOWER_A64_SYNC_VECTOR,     0xd4000002 );
    ram.set( ValueLayout.JAVA_INT, LOWER_A64_SYNC_VECTOR + 4, 0xd69f03e0 );

    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_VBAR_EL1, VECTOR_BASE );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_SP_EL0, 0x3000L );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_PC, USER_CODE );
    vcpu.setRegister( Aarch64HvBindings.HV_REG_CPSR, PSTATE_EL0T_MASKED );

    Aarch64HvVcpu.Exit first = vcpu.run();
    long firstEsr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ESR_EL1 );
    long firstElr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ELR_EL1 );
    require( first.reason() == Aarch64HvVcpu.ExitReason.EXCEPTION
            && first.exceptionClass() == ESR_EC_HVC64,
        "EL0 SVC did not reach the EL1 HVC bridge: " + first );
    require( ((firstEsr >>> 26) & 0x3f) == ESR_EC_SVC64
            && (firstEsr & 0xffffL) == 0L && firstElr == USER_CODE + 12,
        "unexpected first SVC state: ESR_EL1=0x" + Long.toHexString( firstEsr )
            + " ELR_EL1=0x" + Long.toHexString( firstElr ) );
    require( vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 ) == 0xbeefL
            && vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 8 )
                == Aarch64SyscallTable.SYS_GETPID,
        "SVC arguments were not preserved" );

    // Dispatch the register ABI through the real SyscallAarch64 table. ERET
    // restores SPSR_EL1/ELR_EL1 and returns to the second EL0 SVC.
    final int expectedPid = 0x1234;
    Sysinfo sysinfo = new Sysinfo( 0, false );
    Process process = new Process( expectedPid, sysinfo );
    SyscallAarch64 syscall = new SyscallAarch64( sysinfo, process );
    Aarch64HvSyscallBridge.Dispatch dispatch =
        new Aarch64HvSyscallBridge().dispatch( vcpu, first, syscall );
    require( dispatch.number() == Aarch64SyscallTable.SYS_GETPID
            && dispatch.immediate() == 0 && dispatch.result() == expectedPid
            && dispatch.resumePc() == LOWER_A64_SYNC_VECTOR + 4,
        "unexpected syscall dispatch result: " + dispatch );

    Aarch64HvVcpu.Exit second = vcpu.run();
    long secondEsr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ESR_EL1 );
    long secondElr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ELR_EL1 );
    require( second.reason() == Aarch64HvVcpu.ExitReason.EXCEPTION
            && second.exceptionClass() == ESR_EC_HVC64,
        "ERET did not return to the second EL0 SVC: " + second );
    require( ((secondEsr >>> 26) & 0x3f) == ESR_EC_SVC64
            && (secondEsr & 0xffffL) == 1L && secondElr == USER_CODE + 16,
        "unexpected second SVC state: ESR_EL1=0x" + Long.toHexString( secondEsr )
            + " ELR_EL1=0x" + Long.toHexString( secondElr ) );
    require( vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 ) == expectedPid,
        "SyscallAarch64 getpid result did not survive ERET" );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }
}
