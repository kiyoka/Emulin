// ----------------------------------------
//  Thread64 — pthread support (Phase 27 step 28)
//
//  Process と Memory / Syscall / FileAccess を共有する Java Thread。
//  各 pthread が独立した Cpu64 を持ち、同じアドレス空間で並列実行する。
//
//  Linux pthread の clone(CLONE_VM | CLONE_THREAD | ...) からの spawn で使う。
// ----------------------------------------
package emulin;

public class Thread64 extends Thread implements GuestThread {
  Cpu64 cpu;
  Process process;   // 親プロセス (Memory/Syscall/Signal を共有)

  // GuestThread (backend 非依存マーカ): clone 親選択 / gettid / thread exit 判定 / per-thread
  //   signal mask で native worker と共通に扱う。Cpu64 は AbstractCpu なので guestCpu はそのまま返せる。
  @Override public AbstractCpu guestCpu() { return cpu; }
  @Override public int         guestTid() { return tid; }
  @Override public long        getSignalMask() { return signal_mask; }
  @Override public void        setSignalMask( long m ) { signal_mask = m; }
  int tid;           // pthread tid (kernel TID — gettid が返す)
  volatile boolean done;
  // Phase 27 step 28: CLONE_CHILD_CLEARTID で登録された address。
  //   thread exit 時に *ctid_addr = 0 を書いて FUTEX_WAKE する。
  //   これが pthread_join 側の FUTEX_WAIT (val = tid) を起こす glibc の慣例。
  long ctid_addr;
  Memory mem;        // ctid_addr 書き込み用
  // Phase 27 step 34: per-thread signal mask (POSIX 仕様)。
  //   pthread_sigmask / rt_sigprocmask は呼び出し側 thread の mask を変更
  //   する (process 全体ではなく)。Thread64 spawn 時に親の現在 mask を
  //   inherit する (clone semantics)。bit i = signum (i+1) を mask する。
  volatile long signal_mask;

  public Thread64( Process _process, Cpu64 _cpu, int _tid, Memory _mem, long _ctid_addr, long _initial_mask ) {
    super( "emulin-pthread-" + _tid );
    process     = _process;
    cpu         = _cpu;
    tid         = _tid;
    mem         = _mem;
    ctid_addr   = _ctid_addr;
    signal_mask = _initial_mask;
    done        = false;
    setDaemon( true );
    // Phase 27 step 39: process の active thread counter を進める。
    //   main thread sys_exit が「worker 全部終わるまで待つ」のに使う。
    process.active_thread_count.incrementAndGet();
    // Phase 27 step 60: cross-thread cache invalidation のため、worker thread
    //   が live な間 Memory.globalStoreEpoch を有効化する。issue #113: FORCE_ST 時は
    //   amd64_clone_thread が spawn を -EAGAIN で拒否するので worker 自体走らない
    //   → Thread64 が存在する=必ず counter に反映 (旧 FORCE_ST guard は逆に worker
    //   並走中も coherency を切る corruption 源だったため撤去)。
    Memory.multiThreadActive++;  // issue #113
  }

  @Override
  public void run() {
    try {
      cpu.eval( );
    } catch( SyscallAmd64.ThreadExitException te ) {
      // 正常な thread exit — 騒がない
    } catch( Memory.SegfaultException se ) {
      // issue #113: worker thread の segfault は real Linux では process 全体を
      //   SIGSEGV で殺す。term_sig は Memory.raiseSegv で共有 process に set 済。
      //   set_exit_flag で親へ SIGCHLD + exit_flag → main thread の eval ループ
      //   (while(!process.is_exited())) が抜けて process 全体が終了する。
      if( process != null ) { process.term_sig = Signal.SIGSEGV; process.set_exit_flag( ); }
    } catch( Throwable t ) {
      System.err.println( "Thread64[" + tid + "] crashed: " + t );
    } finally {
      done = true;
      // CLONE_CHILD_CLEARTID 慣例: ctid_addr に 0 を書いて futex wake
      if( ctid_addr != 0 && mem != null ) {
        // issue #113 (review #2): ctid_addr 自体が破損 (#113 corruption の継承) で
        //   unmapped を指していると、この store32 が二次 segfault dump を吐いて
        //   本物の crash dump を埋もれさせる。事前に in() で写像済みか確認し、
        //   未写像なら store/wake を skip (dump も例外も出さない)。in()==true でも
        //   teardown 中の munmap race に備え SegfaultException は握り潰す。
        if( mem.in( ctid_addr ) ) {
          try { mem.store32( ctid_addr, 0 ); FutexManager.wake( ctid_addr, Integer.MAX_VALUE ); }
          catch( Memory.SegfaultException se2 ) { System.err.println("Thread64["+tid+"] ctid clear skipped (ctid_addr fault)"); }
        } else {
          System.err.println("Thread64["+tid+"] ctid clear skipped (ctid_addr=0x"+Long.toHexString(ctid_addr)+" unmapped)");
        }
      }
      FutexManager.onThreadExit( tid );
      // Phase 27 step 39: process の active thread counter を戻す。
      //   main thread が sys_exit 待機中ならここで 0 になり起き上がる。
      synchronized( process.active_thread_count ) {
        process.active_thread_count.decrementAndGet();
        process.active_thread_count.notifyAll();
      }
      Memory.multiThreadActive--;  // issue #113
    }
  }
}
