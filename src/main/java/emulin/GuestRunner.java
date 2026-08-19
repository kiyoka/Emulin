// ----------------------------------------
//  Guest execution strategy (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** Selects the process execution loop without testing ELF class in run(). */
public interface GuestRunner {
  void run( Process process );

  static GuestRunner forArch( GuestArch arch ) {
    return switch( arch ) {
      case I386 -> LegacyI386Runner.INSTANCE;
      case X86_64, AARCH64 -> SelfContainedRunner.INSTANCE;
    };
  }
}

final class LegacyI386Runner implements GuestRunner {
  static final LegacyI386Runner INSTANCE = new LegacyI386Runner();
  private LegacyI386Runner() {}
  @Override public void run( Process process ) { process.runLegacyI386Guest(); }
}

final class SelfContainedRunner implements GuestRunner {
  static final SelfContainedRunner INSTANCE = new SelfContainedRunner();
  private SelfContainedRunner() {}
  @Override public void run( Process process ) { process.runSelfContainedGuest(); }
}
