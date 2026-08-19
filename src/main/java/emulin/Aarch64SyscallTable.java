// ----------------------------------------
//  Linux AArch64 syscall number table (issue #951 Phase 1)
// ----------------------------------------
package emulin;

final class Aarch64SyscallTable {
  static final int SYS_WRITE = 64;
  static final int SYS_EXIT = 93;
  static final int SYS_EXIT_GROUP = 94;

  long dispatch( SyscallAarch64 syscall, int number, long x0, long x1, long x2,
                 long x3, long x4, long x5 ) {
    return switch( number ) {
      case SYS_WRITE -> syscall.sys_write( x0, x1, x2, x3, x4 );
      case SYS_EXIT, SYS_EXIT_GROUP -> syscall.sys_exit( x0, x1, x2, x3, x4 );
      default -> Syscall.ENOSYS;
    };
  }
}
