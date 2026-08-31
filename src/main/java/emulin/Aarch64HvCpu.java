// ----------------------------------------
//  Apple Silicon AArch64 HVF GuestCpu (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.util.ArrayDeque;

/** Shared-VM, per-thread-vCPU backend for Linux AArch64 guests. */
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
  private final ArrayDeque<SignalFrame> signalFrames = new ArrayDeque<>();
  private long signalTrampoline;
  private Aarch64HvAddressSpace activeAddressSpace;
  private Aarch64HvVm activeVm;
  private Aarch64HvMemoryBackend activeMemory;
  private boolean worker;

  private record SignalFrame( Aarch64State state, long signalMask ) {}

  Aarch64HvCpu( Sysinfo sysinfo, Process process ) {
    this.sysinfo = sysinfo;
    this.process = process;
  }

  @Override public GuestCpu duplicate( Process child ) {
    Aarch64HvCpu result = new Aarch64HvCpu( sysinfo, child );
    result.state = state.copy();
    return result;
  }

  Aarch64HvCpu duplicateVforkChild( Process child, long childStack ) {
    if( activeVm == null || activeAddressSpace == null || activeMemory == null ) {
      throw new IllegalStateException( "AArch64 HVF vfork outside an active VM" );
    }
    if( !(child.syscall instanceof SyscallAarch64 childSyscall) ) {
      throw new IllegalArgumentException( "AArch64 HVF vfork requires SyscallAarch64" );
    }
    Aarch64HvCpu result = new Aarch64HvCpu( sysinfo, child );
    result.state = state.copy();
    result.state.writeX( 0, 0L );
    if( childStack != 0 ) result.state.sp = childStack;
    result.softwareMemory = softwareMemory;
    result.syscall = childSyscall;
    result.activeAddressSpace = activeAddressSpace;
    result.activeVm = activeVm;
    result.activeMemory = activeMemory;
    result.worker = true;
    childSyscall.connect_mem( activeMemory );
    return result;
  }

  @Override public void setPc( long pc ) { state.pc = pc; }
  @Override public long getPc() { return state.pc; }
  @Override public void setSp( long sp ) { state.sp = sp; }
  @Override public long getSp() { return state.sp; }
  @Override public void setReturnValue( long value ) { state.writeX( 0, value ); }
  @Override public void advancePastSyscall() {
    // Hypervisor.framework reports ELR_EL1 after the trapped SVC instruction.
    // The software AArch64 backend keeps PC on SVC until Kernel.fork/vfork
    // advances it, but doing that here would skip the first child instruction.
  }
  @Override public void setFsBase( long base ) { state.tpidrEl0 = base; }
  @Override public long getFsBase() { return state.tpidrEl0; }

  @Override public void prepareProcessClone() {
    // Guest stores happen directly in the HVF RAM mapping. Process.duplicate()
    // copies Memory metadata, so make its byte arrays current before that copy.
    Aarch64HvMemoryBackend memory = activeMemory;
    if( memory != null ) memory.exportRuntimeImage();
  }

  @Override
  public long spawnVcpu( long flags, long childStack, long parentTid,
                         long childTid, long tls ) {
    final long CLONE_PARENT_SETTID  = 0x100000L;
    final long CLONE_CHILD_CLEARTID = 0x200000L;
    final long CLONE_CHILD_SETTID   = 0x1000000L;
    final long CLONE_SETTLS         = 0x80000L;
    if( activeVm == null || activeMemory == null ) return -11L; // EAGAIN

    Aarch64HvCpu child = new Aarch64HvCpu( sysinfo, process );
    child.state = state.copy();
    // captureUserState has already copied ELR_EL1, which points after clone's SVC.
    child.state.writeX( 0, 0L );
    if( childStack != 0 ) child.state.sp = childStack;
    if( (flags & CLONE_SETTLS) != 0 ) child.state.tpidrEl0 = tls;
    child.softwareMemory = softwareMemory;
    child.syscall = syscall;
    child.activeAddressSpace = activeAddressSpace;
    child.activeVm = activeVm;
    child.activeMemory = activeMemory;
    child.worker = true;

    int tid = sysinfo.kernel.next_tid();
    long clearTid = (flags & CLONE_CHILD_CLEARTID) != 0 ? childTid : 0L;
    if( (flags & CLONE_PARENT_SETTID) != 0 && parentTid != 0 ) {
      activeMemory.store32( parentTid, tid );
    }
    if( (flags & CLONE_CHILD_SETTID) != 0 && childTid != 0 ) {
      activeMemory.store32( childTid, tid );
    }
    Aarch64Thread thread = new Aarch64Thread( process, child, tid, activeMemory,
        clearTid, process.get_signal_mask_bits() );
    thread.start();
    return tid;
  }

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
    if( worker ) return runVcpu();
    long poolBytes = poolSizeBytes();
    Aarch64HvVmPool.Lease lease = null;
    Aarch64HvAddressSpace addressSpace = null;
    try {
      if( TRACE_HVF ) {
        System.err.println( "[aarch64-hvf] pid=" + process.pid + " acquire VM slot" );
      }
      lease = Aarch64HvVmPool.acquire();
      addressSpace = new Aarch64HvAddressSpace( poolBytes, lease.ipaBase() );
      Aarch64HvVm vm = lease.vm();
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
      lease.map( addressSpace );
      if( TRACE_HVF ) {
        System.err.println( "[aarch64-hvf] pid=" + process.pid
            + " mapped IPA=0x" + Long.toHexString( lease.ipaBase() ) );
      }
      syscall.connect_mem( memory );
      activeAddressSpace = addressSpace;
      activeVm = vm;
      activeMemory = memory;
      return runVcpu();
    } catch( GuestThreadExitException exit ) {
      throw exit;
    } catch( Memory.SegfaultException fault ) {
      throw fault;
    } catch( Throwable t ) {
      throw new RuntimeException( "AArch64 HVF execution failed", t );
    } finally {
      Aarch64HvMemoryBackend connectedMemory = activeMemory;
      activeAddressSpace = null;
      activeVm = null;
      activeMemory = null;
      try {
        if( lease != null ) lease.close();
      } finally {
        try {
          if( addressSpace != null ) addressSpace.close();
        } finally {
          // Process owns the software Memory metadata until normal cleanup.
          // execve transfers SyscallAarch64 (and its fd table) to the replacement
          // Process, so do not overwrite that Process's new HVF connection.
          if( syscall.mem == connectedMemory ) syscall.connect_mem( softwareMemory );
        }
      }
    }
  }

  private long runVcpu() {
    Aarch64HvAddressSpace addressSpace = activeAddressSpace;
    Aarch64HvVm vm = activeVm;
    Aarch64HvMemoryBackend memory = activeMemory;
    if( addressSpace == null || vm == null || memory == null ) {
      throw new IllegalStateException( "AArch64 HVF runtime is not active" );
    }
    try( Aarch64HvVcpu vcpu = vm.createVcpu() ) {
      if( TRACE_HVF ) {
        System.err.println( "[aarch64-hvf] pid=" + process.pid + " created vCPU" );
      }
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
        int syscallNumber = (int)vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 8 );
        if( TRACE_HVF && exits < 12 ) {
          System.err.println( "[aarch64-hvf] pid=" + process.pid
              + " syscall=" + syscallNumber
              + " pc=0x" + Long.toHexString( state.pc ) );
        }
        if( syscallNumber == Aarch64SyscallTable.SYS_RT_SIGRETURN
            && restoreSignalFrame( vcpu ) ) {
          exits++;
          process.evals = exits;
          continue;
        }
        Aarch64HvSyscallBridge.Dispatch dispatch = bridge.dispatch( vcpu, exit, syscall );
        state.writeX( 0, dispatch.result() );
        deliverPendingSignal( vcpu, memory );
        exits++;
        process.evals = exits;
      }
      captureUserState( vcpu );
      return exits;
    } catch( GuestThreadExitException exit ) {
      throw exit;
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

  private void deliverPendingSignal( Aarch64HvVcpu vcpu,
                                     Aarch64HvMemoryBackend memory ) throws Throwable {
    int signal = process.psig();
    if( signal < 0 ) return;
    long handler = process.get_func_adrs( signal );
    process.consume_one( signal );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      if( process.get_action_type( signal ) == Signal.SIGACTION_EXIT ) {
        process.term_sig = signal;
        process.exit_code = 128 + signal;
        ProcessInfo info = sysinfo.kernel.get_pinfo( process.pid );
        if( info != null && info.ppid <= 1 ) {
          sysinfo.kernel.last_exit_code = 128 + signal;
        }
        process.set_exit_flag();
      }
      return;
    }

    long savedMask = process.get_signal_mask_bits();
    signalFrames.push( new SignalFrame( state.copy(), savedMask ) );
    long newMask = savedMask | process.get_sa_mask( signal );
    if( !process.has_sa_nodefer( signal ) ) newMask |= 1L << (signal - 1);
    process.set_signal_mask_bits( newMask );

    signalTrampoline = memory.ensureSigtramp();
    if( signalTrampoline <= 0 ) throw new OutOfMemoryError( "AArch64 HVF sigtramp" );
    state.exclusiveAddress = -1L;
    state.writeX( 0, signal );
    state.writeX( 30, signalTrampoline );
    state.pc = handler;
    Aarch64HvStateSync.loadExceptionReturn( state, vcpu );
  }

  private boolean restoreSignalFrame( Aarch64HvVcpu vcpu ) throws Throwable {
    SignalFrame frame = signalFrames.pollFirst();
    if( frame == null ) return false;
    state = frame.state();
    process.set_signal_mask_bits( frame.signalMask() );
    Aarch64HvStateSync.loadExceptionReturn( state, vcpu );
    return true;
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
