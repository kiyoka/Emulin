// ----------------------------------------
//  Emulin WaitHub (issue #709, 案A)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

// issue #709 (案A): 中央 event-driven 起こし機構。
//
//   背景: Emulin の poll/epoll_wait/pselect/wait4 は「readiness 再導出 → sleep(数ms) → 再チェック」の
//   ポーリング近似で、fd の状態を変える側 (eventfd write / pipe write / 子 exit / signal 送信) から
//   待機側を直接起こす経路が無かった。案C (PollKick) は全 poller 共有の単一 waker で sleep を短絡した。
//   案A はこれを per-object の待ち行列 (Linux の waitqueue 相当) に発展させる:
//
//   - Source: 待てるオブジェクト (Pipeinfo / eventfd / SockOut / Process の signal / 子 exit) ごとに
//     1 つ持つ待ち行列。状態を変えた側が wake() すると、subscribe 中の Waiter だけが起きる
//     (案C の「全 poller を起こす」から「その fd を待つ poller だけ起こす」への精密化)。
//   - Waiter: blocking syscall 1 回の待機主体。複数 Source を subscribe し、どれかの wake() か
//     timeout で起きる。Linux poll が複数 waitqueue に entry を積むのと同型。
//
//   lost-wakeup を防ぐプロトコル (Source 側 wake は signaled フラグを立てるので順序が要):
//       waiter.subscribe(source);   // 1. 先に subscribe
//       derive readiness;           // 2. その後に readiness 導出 (この間の wake は flag に残る)
//       waiter.await(backstop);     // 3. flag が立っていれば即 return → 再導出
//
//   backstop timeout の方針:
//   - 監視 set が event 配線済みの型 (pipe/pty(=pipe裏打ち)/eventfd/signal/子exit/通常file) のみ →
//     BACKSTOP_MS (既定 50ms)。wake が正しく配線されていれば backstop は発火しない保険。
//   - 能動 probe が必要な型 (実 TCP socket の受信 peek / listen socket / UDP / native TTY) を含む →
//     従来の短チャンク (5-10ms) を維持 (probe の周期そのものが readiness 検出手段のため)。
//     socket 受信の event 化は SocketChannel/Selector 移行が必要で本変更の範囲外 (設計書の注記どおり)。
//   - timerfd を含む → 次の expire までの残時間でさらに cap (timer 発火精度を保つ)。
//
//   キルスイッチ: EMULIN_POLLKICK=0 (案C と同じ env) で全機構を無効化し、subscribe/wake=no-op、
//   await=素の Thread.sleep (案C 導入前と同一のポーリング挙動) に落とす。A/B 比較用。
final class WaitHub {
  private WaitHub() {}

  // 案C と同じ env 名を引き継ぐ (同一 jar で有効/無効を切替可能に保つ)
  static final boolean ENABLED = !"0".equals( System.getenv( "EMULIN_POLLKICK" ) );

  // pure-event set 用の backstop (ms)。配線漏れがあっても最悪この間隔で再チェックされ恒久 hang しない。
  static final long BACKSTOP_MS = _envLong( "EMULIN_WAITHUB_BACKSTOP_MS", 50L );

  private static long _envLong( String key, long def ) {
    String v = System.getenv( key );
    if( v == null || v.isEmpty() ) return def;
    try { return Long.parseLong( v ); } catch( NumberFormatException e ) { return def; }
  }

  // 子プロセス exit の global source。exit は低頻度なので per-parent に分けず全 wait4 waiter を起こす
  //   (親プロセスの sigSource (SIGCHLD 経由) も並走するが、SIGCHLD が SIG_IGN の場合や
  //    init/exec_replacing 経路では recv されないため、こちらが確実な起こし経路)。
  static final Source CHILD = new Source();

  // 待てるオブジェクトごとの待ち行列。wake() は軽量 (waiter ゼロなら Set 走査ゼロで即帰る)。
  static final class Source {
    private final java.util.Set<Waiter> waiters =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
    void wake() {
      if( !ENABLED || waiters.isEmpty() ) return;
      for( Waiter w : waiters ) w.signalWake();
    }
    void add( Waiter w )    { waiters.add( w ); }
    void remove( Waiter w ) { waiters.remove( w ); }
  }

  // blocking syscall 1 回分の待機主体。生成した thread だけが subscribe/await/close を呼ぶ
  //   (subs は owner thread 専有)。signalWake は任意 thread から来る。
  static final class Waiter {
    private final java.util.HashSet<Source> subs = new java.util.HashSet<>();
    private boolean signaled = false;

    // 同じ Source への重複 subscribe は no-op (poller のループ内から毎周呼んでよい)。
    void subscribe( Source s ) {
      if( s == null || !ENABLED ) return;
      if( subs.add( s ) ) s.add( this );
    }
    // 必ず finally で呼ぶ (Source 側の waiter set から自分を外す)。
    void close() {
      for( Source s : subs ) s.remove( this );
      subs.clear();
    }
    synchronized void signalWake() { signaled = true; notifyAll(); }
    // 前回 await 以降に signalWake があれば即 return、なければ最大 ms 待つ。
    void await( long ms ) {
      if( ms <= 0 ) return;
      if( !ENABLED ) {  // 無効時は素の sleep (案C/案A 導入前と同一挙動)
        try { Thread.sleep( ms ); } catch( InterruptedException ie ) { Thread.currentThread().interrupt(); }
        return;
      }
      synchronized( this ) {
        if( !signaled ) {
          try { wait( ms ); } catch( InterruptedException ie ) { Thread.currentThread().interrupt(); }
        }
        signaled = false;
      }
    }
  }
}
