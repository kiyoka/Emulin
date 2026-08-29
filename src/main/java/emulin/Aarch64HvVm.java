// ----------------------------------------
//  Apple Silicon AArch64 HVF VM contract (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.MemorySegment;

/**
 * Architecture-specific VM boundary for an AArch64 Hypervisor.framework
 * backend.  The existing {@link HvVm} contract is deliberately x86-oriented;
 * keeping this interface separate prevents x86 register and boot semantics
 * from leaking into the Apple Silicon implementation.
 */
public interface Aarch64HvVm extends AutoCloseable {
  void mapGuestRam( MemorySegment hostMemory, long ipa, long sizeBytes ) throws Throwable;
  void unmapGuestRam( long ipa, long sizeBytes ) throws Throwable;
  Aarch64HvVcpu createVcpu() throws Throwable;
  int maxVcpus() throws Throwable;
  @Override void close();
}
