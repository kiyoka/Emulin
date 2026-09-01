// ----------------------------------------
//  AArch64 guest pthread worker (issue #951)
// ----------------------------------------
package emulin;

/** Runs one CLONE_THREAD child with a private register file and shared memory. */
final class Aarch64Thread extends Thread implements GuestThread {
  private final GuestCpu cpu;
  private final Process process;
  private final int tid;
  private final MemoryBackend memory;
  private final long clearTidAddress;
  private volatile long signalMask;

  Aarch64Thread( Process process, GuestCpu cpu, int tid, MemoryBackend memory,
                 long clearTidAddress, long initialSignalMask ) {
    super( "emulin-aarch64-pthread-" + tid );
    this.process = process;
    this.cpu = cpu;
    this.tid = tid;
    this.memory = memory;
    this.clearTidAddress = clearTidAddress;
    this.signalMask = initialSignalMask;
    setDaemon( true );
    process.active_thread_count.incrementAndGet();
    Memory.multiThreadActive++;
  }

  @Override public GuestCpu guestCpu() { return cpu; }
  @Override public int guestTid() { return tid; }
  @Override public long getSignalMask() { return signalMask; }
  @Override public void setSignalMask( long mask ) { signalMask = mask; }

  @Override public void run() {
    try {
      cpu.eval();
    } catch( GuestThreadExitException normalExit ) {
      // exit(2) terminates only this member of the thread group.
    } catch( Memory.SegfaultException fault ) {
      process.term_sig = fault.sig > 0 ? fault.sig : Signal.SIGSEGV;
      process.exit_code = 128 + process.term_sig;
      process.set_exit_flag();
    } catch( Throwable error ) {
      System.err.println( "Aarch64Thread[" + tid
          + "] crashed -> kill thread group (SIGSEGV): " + error );
      process.term_sig = Signal.SIGSEGV;
      process.exit_code = 128 + Signal.SIGSEGV;
      process.set_exit_flag();
      process.recv( Signal.SIGKILL );
    } finally {
      if( clearTidAddress != 0 && memory.in( clearTidAddress ) ) {
        try {
          memory.store32( clearTidAddress, 0 );
          FutexManager.wake( clearTidAddress, Integer.MAX_VALUE, memory );
        } catch( Memory.SegfaultException ignored ) {
          // The process may concurrently unmap its thread-control block.
        }
      }
      FutexManager.onThreadExit( tid );
      synchronized( process.active_thread_count ) {
        process.active_thread_count.decrementAndGet();
        process.active_thread_count.notifyAll();
      }
      Memory.multiThreadActive--;
    }
  }
}
