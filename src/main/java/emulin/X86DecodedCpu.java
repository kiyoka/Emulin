// ----------------------------------------
//  Legacy x86 decoded-execution contract (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** x86-only decoder/cache operations used by the legacy i386 runner. */
public interface X86DecodedCpu extends GuestCpu {
  void fetchInstruction( long address, byte[] buffer );
  boolean instructionCacheHit( long address );
  int decodeInstruction( long address, byte[] buffer, boolean cached );
  int currentInstructionId();
  void expireInstructionCache();
}
