// ----------------------------------------
//  LeakCheck — 終了時のリソースリーク検査 (非公開 conformance harness #99 用の計測フック)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  EMULIN_LEAKCHECK=1 のとき、JVM 終了時に「まだ握ったままの資源」を 1 行で stderr に出す。
//  テストハーネスがこの行を機械的に読み、リークを検出する。
//
//  出力例 (clean な終了):
//    [leakcheck] pool_alloc=1 pool_free=1 pool_live_mb=0 proc_live=0 thread_live=0
//                pipe_open=0 pty_open=0 guest_fd=0
//
//  設計:
//   - pool の alloc/free カウントは常時 (env 非依存で) 取る。HvVm.allocGuestRam /
//     freeGuestRam は process 単位の粗い頻度なので AtomicLong 2 本のコストは無視できる。
//     それ以外の集計 (proc/thread/pipe/pty/fd) は shutdown hook の中でだけ走る。
//   - guest プロセスが全て exit した後なら、いずれも 0 になるのが正しい状態。
//     0 でなければ「解放し忘れ」か「終了処理を通らない経路」があることを意味する。
//   - あくまで診断であり動作は変えない。env 未設定なら hook すら登録しない。
// ----------------------------------------
package emulin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LeakCheck {

  private static final String ENV = System.getenv( "EMULIN_LEAKCHECK" );
  public static final boolean ENABLED =
      ENV != null && !ENV.isEmpty() && !ENV.equals( "0" );

  // guest RAM pool の収支 (HvVm.allocGuestRam / freeGuestRam から更新)。
  //   pool_live_mb > 0 で終了 = teardown が解放していない (issue #379 系の leak)。
  public static final AtomicLong poolAllocs = new AtomicLong();
  public static final AtomicLong poolFrees  = new AtomicLong();
  public static final AtomicLong poolBytes  = new AtomicLong();   // 未解放バイト数

  private static volatile Kernel kernel;
  private static final AtomicBoolean installed = new AtomicBoolean( false );

  private LeakCheck() {}

  public static void poolAllocated( long bytes ) { poolAllocs.incrementAndGet(); poolBytes.addAndGet(  bytes ); }
  public static void poolFreed(     long bytes ) { poolFrees .incrementAndGet(); poolBytes.addAndGet( -bytes ); }

  // Kernel 生成時に 1 度だけ呼ぶ。EMULIN_LEAKCHECK 未設定なら何もしない。
  public static void install( Kernel k ) {
    kernel = k;
    if( !ENABLED ) return;
    if( !installed.compareAndSet( false, true ) ) return;
    Runtime.getRuntime().addShutdownHook( new Thread( () -> {
      try { System.err.println( snapshot() ); } catch( Throwable t ) {
        System.err.println( "[leakcheck] snapshot failed: " + t );
      }
    }, "EmulinLeakCheck" ) );
  }

  // 現時点で握っている資源を 1 行にまとめる (機械可読な key=value 形式)。
  public static String snapshot() {
    int procLive = 0, threadLive = 0, guestFd = 0, pipeOpen = 0, ptyOpen = 0;
    Kernel k = kernel;
    if( k != null ) {
      try {
        for( int i = 0; i < k.ptable.size(); i++ ) {
          ProcessInfo pi = (ProcessInfo)k.ptable.elementAt( i );
          if( pi == null || pi.process == null ) continue;
          if( pi.process.is_exited() ) continue;
          if( pi.process.init_process ) continue;   // init は emulin の常駐で leak ではない
          procLive++;
          threadLive += pi.process.active_thread_count.get();
          guestFd    += openFds( pi.process );
        }
      } catch( Throwable ignore ) {}
      try { pipeOpen = k.debugConnectedPipes(); } catch( Throwable ignore ) {}
      try { ptyOpen  = k.pty.debugPtyCount();   } catch( Throwable ignore ) {}
    }
    long live = poolBytes.get();
    return "[leakcheck]"
        + " pool_alloc=" + poolAllocs.get()
        + " pool_free="  + poolFrees.get()
        + " pool_live_mb=" + ( live / ( 1024L * 1024L ) )
        + " proc_live="  + procLive
        + " thread_live=" + threadLive
        + " pipe_open="  + pipeOpen
        + " pty_open="   + ptyOpen
        + " guest_fd="   + guestFd;
  }

  // std (0/1/2) を除く open 済 guest fd 数。
  private static int openFds( Process p ) {
    try {
      java.util.Vector fl = p.syscall.flist;
      int n = 0;
      for( int fd = 3; fd < fl.size(); fd++ ) if( fl.elementAt( fd ) != null ) n++;
      return n;
    } catch( Throwable t ) { return 0; }
  }
}
