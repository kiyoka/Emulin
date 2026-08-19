// ----------------------------------------
//  Linux guest ABI profiles (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** Stateless architecture profile used by {@link GuestFactory}. */
public interface GuestAbi {
  GuestArch arch();
  Syscall bindSyscall( Sysinfo sysinfo, Process process, Syscall inherited );
  GuestCpu createCpu( CpuBackend backend, Sysinfo sysinfo, Process process );
  GuestRunner runner();
  long prepareEntry( Process process, long entry );
  void initializeProcess( Process process, String[] args, String[] envs );
}

final class I386Abi implements GuestAbi {
  static final I386Abi INSTANCE = new I386Abi();
  private I386Abi() {}

  @Override public GuestArch arch() { return GuestArch.I386; }

  @Override public Syscall bindSyscall( Sysinfo sysinfo, Process process, Syscall inherited ) {
    if( inherited instanceof SyscallI386 ) {
      inherited.process = process;
      return inherited;
    }
    SyscallI386 result = new SyscallI386( sysinfo, process );
    inheritFileTable( result, inherited );
    return result;
  }

  @Override public GuestCpu createCpu( CpuBackend backend, Sysinfo sysinfo, Process process ) {
    return backend.createCpu( sysinfo, process );
  }

  @Override public GuestRunner runner() { return LegacyI386Runner.INSTANCE; }

  @Override public long prepareEntry( Process process, long entry ) { return entry; }

  @Override public void initializeProcess( Process process, String[] args, String[] envs ) {
    process.cpu.connectDevices( process.mem, process.syscall );
    process.cpu.setPc( process.ip );
    process.cpu.setSp( process.sysinfo.get_stack_bottom() );
    process.stack_data_init( (AbstractCpu)process.cpu, args, envs );
  }

  static void inheritFileTable( Syscall target, Syscall inherited ) {
    if( inherited == null ) return;
    target.update_info( inherited );
    target.transferFdTableFrom( inherited );
  }
}

final class Amd64Abi implements GuestAbi {
  static final Amd64Abi INSTANCE = new Amd64Abi();
  private Amd64Abi() {}

  @Override public GuestArch arch() { return GuestArch.X86_64; }

  @Override public Syscall bindSyscall( Sysinfo sysinfo, Process process, Syscall inherited ) {
    if( inherited instanceof SyscallAmd64 ) {
      inherited.process = process;
      return inherited;
    }
    SyscallAmd64 result = new SyscallAmd64( sysinfo, process );
    I386Abi.inheritFileTable( result, inherited );
    return result;
  }

  @Override public GuestCpu createCpu( CpuBackend backend, Sysinfo sysinfo, Process process ) {
    return backend.createCpu64( sysinfo, process );
  }

  @Override public GuestRunner runner() { return SelfContainedRunner.INSTANCE; }

  @Override public long prepareEntry( Process process, long entry ) {
    if( process.mem.interp_path == null ) return entry;
    long interpBase = 0x7ffff7fc5000L;
    String interpNative = process.sysinfo.get_native_path( process.mem.interp_path );
    long interpEntry = process.mem.load_interp( interpNative, interpBase );
    if( interpEntry == 0 ) return entry;
    if( process.sysinfo.verbose() ) {
      process.println( "  [interp] override entry: 0x" + Long.toHexString( entry )
          + " -> 0x" + Long.toHexString( interpEntry ) );
    }
    return interpEntry;
  }

  @Override public void initializeProcess( Process process, String[] args, String[] envs ) {
    try {
      process.cpu.connectDevices( process.mem, process.syscall );
    } catch( NativeCpuBackend.PoolExhaustedException e ) {
      System.err.println( "[native] cannot allocate pool -> running this process only "
          + "on the software backend (issue #379 graceful fallback)" );
      process.cpu = new Cpu64( process.sysinfo, process );
      process.cpu.connectDevices( process.mem, process.syscall );
    }

    if( process.cpu instanceof Cpu64 cpu64 ) {
      process.mem.preallocate_brk();
      long preTls = process.mem.alloc_and_map( 0, 4096, -1, 0 );
      if( preTls > 0 ) cpu64.fs_base = preTls;
      long sp = process.stack_data_init64(
          process.sysinfo.get_stack_bottom_64(), args, envs );
      cpu64.set_sp( sp );
      process.resolve_irelative( cpu64 );
      for( int i = 0; i < 16; i++ ) cpu64.r64[i] = 0;
      cpu64.set_sp( sp );
    } else if( process.cpu instanceof NativeCpuBackend nativeCpu ) {
      nativeCpu.setup_initial_stack( args, envs );
    }
    process.cpu.setPc( process.ip );
  }
}

final class Aarch64Abi implements GuestAbi {
  static final Aarch64Abi INSTANCE = new Aarch64Abi();
  private Aarch64Abi() {}

  @Override public GuestArch arch() { return GuestArch.AARCH64; }

  @Override public Syscall bindSyscall( Sysinfo sysinfo, Process process, Syscall inherited ) {
    if( inherited instanceof SyscallAarch64 ) {
      inherited.process = process;
      return inherited;
    }
    SyscallAarch64 result = new SyscallAarch64( sysinfo, process );
    I386Abi.inheritFileTable( result, inherited );
    return result;
  }

  @Override public GuestCpu createCpu( CpuBackend backend, Sysinfo sysinfo, Process process ) {
    // Phase 1 is intentionally software-only. The x86 native backend contract
    // must never be reused for an AArch64 guest.
    return new Aarch64Cpu( sysinfo, process );
  }

  @Override public GuestRunner runner() { return SelfContainedRunner.INSTANCE; }

  @Override public long prepareEntry( Process process, long entry ) {
    if( process.mem.interp_path != null ) {
      throw new UnsupportedOperationException(
          "dynamic AArch64 ELF starts in issue #951 Phase 3" );
    }
    return entry;
  }

  @Override public void initializeProcess( Process process, String[] args, String[] envs ) {
    process.cpu.connectDevices( process.mem, process.syscall );
    process.cpu.setSp( Aarch64StackBuilder.build(
        process.mem, process.sysinfo.get_stack_bottom_64(), args, envs ) );
    process.cpu.setPc( process.ip );
  }
}
