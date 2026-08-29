// ----------------------------------------
//  Apple Silicon AArch64 HVF vCPU contract (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

/** AArch64 register and exit interface independent of the x86 HvVcpu API. */
public interface Aarch64HvVcpu extends AutoCloseable {
  enum ExitReason { CANCELED, EXCEPTION, VTIMER_ACTIVATED, UNKNOWN }

  record Exit( ExitReason reason, long syndrome, long virtualAddress,
               long physicalAddress ) {
    /** ESR_ELx.Exception Class. */
    public int exceptionClass() { return (int)(syndrome >>> 26) & 0x3f; }
  }

  long getRegister( int register ) throws Throwable;
  void setRegister( int register, long value ) throws Throwable;
  long getSystemRegister( int register ) throws Throwable;
  void setSystemRegister( int register, long value ) throws Throwable;
  Exit run() throws Throwable;
  void requestExit() throws Throwable;
  @Override void close();
}
