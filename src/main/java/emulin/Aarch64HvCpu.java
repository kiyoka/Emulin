// ----------------------------------------
//  Apple Silicon AArch64 HVF GuestCpu (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

/** Initial single-vCPU process backend for Linux AArch64 guests. */
final class Aarch64HvCpu implements GuestCpu {
  private static final int ESR_EC_HVC64 = 0x16;
  private static final int ESR_EC_SVC64 = 0x15;
  private static final long VECTOR_BASE = 0x1_0000L;
  private static final long LOWER_A64_SYNC_VECTOR = VECTOR_BASE + 0x400L;
  private static final long DEFAULT_POOL_MB = 512L;
  private static final boolean TRACE_HVF = System.getenv( "EMULIN_TRACE_HVF" ) != null;

  private final Sysinfo sysinfo;
  private final Process process;
  private Aarch64State state = new Aarch64State();
  private Memory softwareMemory;
  private SyscallAarch64 syscall;

  Aarch64HvCpu( Sysinfo sysinfo, Process process ) {
    this.sysinfo = sysinfo;
    this.process = process;
  }

  @Override public GuestCpu duplicate( Process child ) {
    Aarch64HvCpu result = new Aarch64HvCpu( sysinfo, child );
    result.state = state.copy();
    return result;
  }

  @Override public void setPc( long pc ) { state.pc = pc; }
  @Override public long getPc() { return state.pc; }
  @Override public void setSp( long sp ) { state.sp = sp; }
  @Override public long getSp() { return state.sp; }
  @Override public void setReturnValue( long value ) { state.writeX( 0, value ); }
  @Override public void advancePastSyscall() { state.pc += 4L; }
  @Override public void setFsBase( long base ) { state.tpidrEl0 = base; }
  @Override public long getFsBase() { return state.tpidrEl0; }

  @Override public void connectDevices( Memory memory, Syscall syscall ) {
    if( !(syscall instanceof SyscallAarch64 aarch64) ) {
      throw new IllegalArgumentException( "Aarch64HvCpu requires SyscallAarch64" );
    }
    softwareMemory = memory;
    this.syscall = aarch64;
  }

  @Override public long eval() {
    if( softwareMemory == null || syscall == null ) {
      throw new IllegalStateException( "Aarch64HvCpu devices are not connected" );
    }
    long poolBytes = poolSizeBytes();
    try( Aarch64HvAddressSpace addressSpace = new Aarch64HvAddressSpace( poolBytes );
         Aarch64HvVm vm = new HvfAarch64Vm() ) {
      Aarch64HvMemoryBackend memory =
          new Aarch64HvMemoryBackend( addressSpace, softwareMemory );
      memory.importInitialImage();
      addressSpace.mapPrivilegedZeroed( VECTOR_BASE, 0x1000L );
      addressSpace.store32( LOWER_A64_SYNC_VECTOR, 0xd4000002 );     // hvc #0
      // Syscalls may alter the stage-1 page tables (mmap/brk/munmap). Complete
      // the table writes and invalidate cached translations before EL0 resumes.
      addressSpace.store32( LOWER_A64_SYNC_VECTOR + 4, 0xd5033a9f );  // dsb ishst
      addressSpace.store32( LOWER_A64_SYNC_VECTOR + 8, 0xd508871f );  // tlbi vmalle1
      addressSpace.store32( LOWER_A64_SYNC_VECTOR + 12, 0xd5033b9f ); // dsb ish
      addressSpace.store32( LOWER_A64_SYNC_VECTOR + 16, 0xd5033fdf ); // isb
      addressSpace.store32( LOWER_A64_SYNC_VECTOR + 20, 0xd69f03e0 ); // eret
      addressSpace.mapInto( vm );
      syscall.connect_mem( memory );

      try( Aarch64HvVcpu vcpu = vm.createVcpu() ) {
        addressSpace.installTranslation( vcpu );
        vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_VBAR_EL1, VECTOR_BASE );
        Aarch64HvStateSync.load( state, vcpu );
        Aarch64HvSyscallBridge bridge = new Aarch64HvSyscallBridge();
        long exits = 0;
        while( !process.is_exited() ) {
          Aarch64HvVcpu.Exit exit = vcpu.run();
          if( exit.reason() != Aarch64HvVcpu.ExitReason.EXCEPTION
              || exit.exceptionClass() != ESR_EC_HVC64 ) {
            throw new IllegalStateException( "unexpected AArch64 HVF exit: " + exit );
          }
          long guestEsr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ESR_EL1 );
          if( ((guestEsr >>> 26) & 0x3f) != ESR_EC_SVC64 ) {
            long fault = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_FAR_EL1 );
            if( TRACE_HVF ) {
              long elr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ELR_EL1 );
              long spsr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_SPSR_EL1 );
              long spEl0 = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_SP_EL0 );
              System.err.println( "[aarch64-hvf] EL0 synchronous exception"
                  + " ESR_EL1=0x" + Long.toHexString( guestEsr )
                  + " EC=0x" + Long.toHexString( (guestEsr >>> 26) & 0x3f )
                  + " ISS=0x" + Long.toHexString( guestEsr & 0x1ff_ffffL )
                  + " ELR_EL1=0x" + Long.toHexString( elr )
                  + " FAR_EL1=0x" + Long.toHexString( fault )
                  + " SP_EL0=0x" + Long.toHexString( spEl0 )
                  + " SPSR_EL1=0x" + Long.toHexString( spsr ) );
            }
            process.term_sig = Signal.SIGSEGV;
            throw new Memory.SegfaultException( fault );
          }

          captureUserState( vcpu );
          Aarch64HvSyscallBridge.Dispatch dispatch = bridge.dispatch( vcpu, exit, syscall );
          state.writeX( 0, dispatch.result() );
          exits++;
          process.evals = exits;
        }
        captureUserState( vcpu );
        return exits;
      } finally {
        // Process owns the software Memory metadata until its normal cleanup.
        // Avoid leaving SyscallAarch64 attached to a closed native segment.
        syscall.connect_mem( softwareMemory );
      }
    } catch( Memory.SegfaultException fault ) {
      throw fault;
    } catch( Throwable t ) {
      throw new RuntimeException( "AArch64 HVF execution failed", t );
    }
  }

  private void captureUserState( Aarch64HvVcpu vcpu ) throws Throwable {
    Aarch64HvStateSync.save( vcpu, state );
    state.pc = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ELR_EL1 );
    state.nzcv = (int)vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_SPSR_EL1 )
        & 0xf000_0000;
  }

  private static long poolSizeBytes() {
    String configured = System.getenv( "EMULIN_AARCH64_HVF_POOL_MB" );
    long megabytes = DEFAULT_POOL_MB;
    if( configured != null && !configured.isBlank() ) {
      try { megabytes = Long.parseLong( configured.trim() ); }
      catch( NumberFormatException error ) {
        throw new IllegalArgumentException( "invalid EMULIN_AARCH64_HVF_POOL_MB", error );
      }
    }
    if( megabytes < 32 || megabytes > 4096 ) {
      throw new IllegalArgumentException( "EMULIN_AARCH64_HVF_POOL_MB must be 32..4096" );
    }
    return megabytes << 20;
  }

  @Override public void setSignalHandler( long pc, long handler ) { state.pc = handler; }
  @Override public boolean isInterruptDone() { return true; }

  @Override public String registerString() {
    return "x0=0x" + Long.toHexString( state.readX( 0 ) )
        + " x1=0x" + Long.toHexString( state.readX( 1 ) )
        + " x8=0x" + Long.toHexString( state.readX( 8 ) )
        + " sp=0x" + Long.toHexString( state.sp );
  }

  @Override public String pcString() { return "pc=0x" + Long.toHexString( state.pc ); }
  @Override public String flagString() { return " nzcv=0x" + Integer.toHexString( state.nzcv ); }

  @Override public String disassemble( long address ) {
    return "AArch64 HVF @0x" + Long.toHexString( address );
  }
}
