// ----------------------------------------
//  Guest component factory (issue #951 Phase 0)
// ----------------------------------------
package emulin;

public final class GuestFactory {
  private GuestFactory() {}

  static GuestAbi abiFor( GuestArch arch ) {
    return switch( arch ) {
      case I386 -> I386Abi.INSTANCE;
      case X86_64 -> Amd64Abi.INSTANCE;
      case AARCH64 -> Aarch64Abi.INSTANCE;
    };
  }

  public static GuestComponents create( ElfIdentity identity, CpuBackend backend,
                                        Sysinfo sysinfo, Process process,
                                        Syscall inheritedSyscall ) {
    GuestAbi abi = abiFor( identity.arch() );
    Syscall syscall = abi.bindSyscall( sysinfo, process, inheritedSyscall );
    GuestCpu cpu = abi.createCpu( backend, sysinfo, process );
    return new GuestComponents( abi, syscall, cpu, abi.runner() );
  }
}
