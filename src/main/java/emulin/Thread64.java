// ----------------------------------------
//  Thread64 — pthread support (Phase 27 step 28)
//
//  Process と Memory / Syscall / FileAccess を共有する Java Thread。
//  各 pthread が独立した Cpu64 を持ち、同じアドレス空間で並列実行する。
//
//  Linux pthread の clone(CLONE_VM | CLONE_THREAD | ...) からの spawn で使う。
// ----------------------------------------
package emulin;

public class Thread64 extends Thread {
  Cpu64 cpu;
  Process process;   // 親プロセス (Memory/Syscall/Signal を共有)
  int tid;           // pthread tid (kernel TID — gettid が返す)
  volatile boolean done;
  // Phase 27 step 28: CLONE_CHILD_CLEARTID で登録された address。
  //   thread exit 時に *ctid_addr = 0 を書いて FUTEX_WAKE する。
  //   これが pthread_join 側の FUTEX_WAIT (val = tid) を起こす glibc の慣例。
  long ctid_addr;
  Memory mem;        // ctid_addr 書き込み用

  public Thread64( Process _process, Cpu64 _cpu, int _tid, Memory _mem, long _ctid_addr ) {
    super( "emulin-pthread-" + _tid );
    process   = _process;
    cpu       = _cpu;
    tid       = _tid;
    mem       = _mem;
    ctid_addr = _ctid_addr;
    done      = false;
    setDaemon( true );
  }

  @Override
  public void run() {
    try {
      cpu.eval( );
    } catch( SyscallAmd64.ThreadExitException te ) {
      // 正常な thread exit — 騒がない
    } catch( Throwable t ) {
      System.err.println( "Thread64[" + tid + "] crashed: " + t );
    } finally {
      done = true;
      // CLONE_CHILD_CLEARTID 慣例: ctid_addr に 0 を書いて futex wake
      if( ctid_addr != 0 && mem != null ) {
        mem.store32( ctid_addr, 0 );
        FutexManager.wake( ctid_addr, Integer.MAX_VALUE );
      }
      FutexManager.onThreadExit( tid );
    }
  }
}
