// ----------------------------------------
//  Process-facing guest CPU contract (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/**
 * Architecture-neutral operations used by process, fork/clone, signal, and
 * execution orchestration. Instruction decoding is deliberately excluded.
 */
public interface GuestCpu {
  GuestCpu duplicate( Process process );

  void setPc( long pc );
  long getPc();
  void setSp( long sp );
  long getSp();
  void setReturnValue( long value );
  void advancePastSyscall();

  /** Flush backend-owned guest memory before Process duplicates its metadata image. */
  default void prepareProcessClone() {}

  long eval();
  void connectDevices( Memory memory, Syscall syscall );
  void setSignalHandler( long pc, long handler );
  boolean isInterruptDone();

  default void setFsBase( long base ) {
    throw new UnsupportedOperationException(
        "setFsBase not supported by " + getClass().getSimpleName() );
  }

  default long getFsBase() {
    throw new UnsupportedOperationException(
        "getFsBase not supported by " + getClass().getSimpleName() );
  }

  default long spawnVcpu( long flags, long childStack, long parentTid,
                          long childTid, long tls ) {
    throw new UnsupportedOperationException(
        "spawnVcpu not supported by " + getClass().getSimpleName() );
  }

  String registerString();
  String pcString();
  String flagString();
  String disassemble( long address );
}
