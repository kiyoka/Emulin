// ----------------------------------------
//  Linux AArch64 syscall dispatcher (issue #951 Phase 1)
// ----------------------------------------
package emulin;

public final class SyscallAarch64 extends Syscall {
  private final Aarch64SyscallTable table = new Aarch64SyscallTable();

  SyscallAarch64( Sysinfo sysinfo, Process process ) {
    super( sysinfo, process );
  }

  @Override public Syscall duplicate( Process child ) {
    SyscallAarch64 result = new SyscallAarch64( sysinfo, child );
    result.mem = mem;
    result.update_info( this );
    return result;
  }

  long callAarch64( int number, long x0, long x1, long x2,
                    long x3, long x4, long x5 ) {
    long result = table.dispatch( this, number, x0, x1, x2, x3, x4, x5 );
    if( traceSysEnabled() ) {
      traceSys( process.pid, process.pid, number, x0, x1, x2, x3, x4, x5, result );
    }
    return result;
  }
}
