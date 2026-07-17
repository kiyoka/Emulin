// ----------------------------------------
//  FutexManager — pthread sync support (Phase 27 step 28-29)
//
//  Linux futex(2) の最小実装:
//    FUTEX_WAIT (0):  *uaddr == val なら block (timeout まで)、else -EAGAIN
//    FUTEX_WAKE (1):  uaddr の waiter を最大 n 個起こす、起こした実数を返す
//    その他: ENOSYS で諦めさせる (PI lock 等)
//
//  実装: アドレスごとに WaitNode (monitor + waiter count) を持ち、
//  wait/notifyAll で同期する。FUTEX_WAKE は real waiter count に基づいて
//  実数を返す (glibc が嘘の wake count を見ると pthread_mutex_lock で
//  __assert_perror_fail → abort するので重要)。
// ----------------------------------------
package emulin;

import java.util.concurrent.ConcurrentHashMap;

public class FutexManager {
  public static final int FUTEX_WAIT = 0;
  public static final int FUTEX_WAKE = 1;
  public static final int FUTEX_FD   = 2;
  public static final int FUTEX_REQUEUE = 3;
  public static final int FUTEX_CMP_REQUEUE = 4;
  public static final int FUTEX_WAKE_OP = 5;
  public static final int FUTEX_LOCK_PI = 6;
  public static final int FUTEX_UNLOCK_PI = 7;
  public static final int FUTEX_WAIT_BITSET = 9;
  public static final int FUTEX_WAKE_BITSET = 10;
  public static final int FUTEX_PRIVATE_FLAG = 128;
  public static final int FUTEX_CLOCK_REALTIME = 256;
  public static final int FUTEX_OP_MASK = 0x7F;

  // アドレスごとの状態。waiters は wait に入っている thread 数。wake は
  //   real waiter count に基づいて実数を返す必要がある。
  static class WaitNode {
    int waiters;
    int wakers;  // notifyAll で起こした分のうち、まだ抜けていない数
    long requeueTarget;   // issue #549: FUTEX_CMP_REQUEUE の移送先 uaddr
    int  requeuePending;  // issue #549: この node から移送予定の待機者数
    // issue #709 診断: dump 用 (直近 waiter の入場情報と wake 統計)。ロックは node monitor。
    int    dbgExpected;      // 直近 waiter が待ち始めたときの期待値
    long   dbgTimeoutMs;     // 直近 waiter の timeout (相対 ms、-1=無期限)
    long   dbgSince;         // 直近 waiter の入場時刻 (currentTimeMillis)
    String dbgThread;        // 直近 waiter の Java thread 名 (jstack と突き合わせる)
    String dbgCaller;        // 直近 waiter の guest pid:プロセス名 (CALLER 経由、診断時のみ)
    long   dbgWakeCalls;     // wake() 呼出回数 (起こせなかった呼出も含む)
    long   dbgWakeDelivered; // 実際に起こした延べ数
  }

  // issue #709 診断: 呼び出し guest プロセスの識別子 (pid:name)。stuck dump 有効時のみ
  //   amd64_futex が設定する (通常運転では null のまま = ゼロコスト)。
  public static final ThreadLocal<String> CALLER = new ThreadLocal<>();

  // issue #709 (真因修正): Linux の private futex は「(mm, uaddr) = アドレス空間ごと」に照合される。
  //   旧実装は uaddr のみのグローバル表だったため、ASLR 無しの決定的メモリレイアウトでは
  //   親プロセスと全ツール子プロセス (rg/bash 等) の futex アドレスが必ず衝突し、FUTEX_WAKE が
  //   他プロセスの待機者に「盗まれて」(先に wakers を消費した方が勝ち)、本来起きるべき待機者が
  //   永眠する cross-process lost-wakeup が起きていた。claude 凍結 (#709) の真因:
  //   ツール実行のたびに子と親の pthread 内部 futex が衝突し、condvar/rwlock の起こしが
  //   確率的に失われて event loop ごと固まる (実 Linux ではカーネルが mm で分離するので起きない)。
  //   mm の同一性は MemoryBackend インスタンスで表す (clone/スレッド=共有、fork=duplicate で別、
  //   vfork=共有 — いずれも Linux の mm 共有関係と一致する)。
  static final class Key {
    final MemoryBackend mm;
    final long uaddr;
    Key( MemoryBackend m, long u ) { mm = m; uaddr = u; }
    @Override public boolean equals( Object o ) {
      return ( o instanceof Key k ) && k.mm == mm && k.uaddr == uaddr;
    }
    @Override public int hashCode() {
      return System.identityHashCode( mm ) * 31 + Long.hashCode( uaddr );
    }
  }

  private static final ConcurrentHashMap<Key, WaitNode> nodes = new ConcurrentHashMap<>();

  private static WaitNode node( MemoryBackend mem, long uaddr ) {
    return nodes.computeIfAbsent( new Key( mem, uaddr ), k -> new WaitNode() );
  }

  // FUTEX_WAIT: *uaddr が val と等しければ block。
  //   timeout_ms < 0 なら無期限。0 なら即 timeout 扱い。
  //   戻り値: 0 (woken), -EAGAIN (-11) (val 不一致), -ETIMEDOUT (-110), -EINTR (-4)
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem ) {
    return wait( uaddr, expected, timeout_ms, mem, null );
  }
  // issue #533: FUTEX_WAIT は Linux ではシグナル到達で -EINTR する (handler は syscall 復帰時に
  //   実行され、glibc の futex 呼び出し側は EINTR 後に再待機する)。旧実装は無限 Object.wait() で
  //   guest シグナルに割り込まれず、futex で park 中の thread へ宛てたシグナル (JSC/Bun の
  //   thread suspend-resume handshake の suspend 信号等) が syscall 境界に到達できず永遠に
  //   配送されなかった。sigPending (呼び出し guest thread の pending シグナル有無) を渡された
  //   場合は待ちを 25ms 単位に刻み、pending を検知したら -EINTR で復帰する
  //   (通常の FUTEX_WAKE は従来どおり notifyAll で即時 wake、レイテンシ影響なし)。
  private static final long SIG_POLL_MS = 25L;
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem,
                          java.util.function.BooleanSupplier sigPending ) {
    WaitNode n = node( mem, uaddr );
    long requeueTo = 0;
    synchronized( n ) {
      // lock 取得後に値を再 check (compare-and-block の atomic 風)
      int cur = mem.load32( uaddr );
      if( cur != expected ) return -11;  // -EAGAIN
      // issue #709 診断: 入場情報を記録 (dump 用、hot path への影響は field 書込 5 つのみ)
      n.dbgExpected  = expected;
      n.dbgTimeoutMs = timeout_ms;
      n.dbgSince     = System.currentTimeMillis();
      n.dbgThread    = Thread.currentThread().getName();
      n.dbgCaller    = CALLER.get();
      n.waiters++;
      try {
        if( timeout_ms == 0 ) return -110;
        long deadline = (timeout_ms < 0) ? -1 : System.currentTimeMillis() + timeout_ms;
        while( n.wakers == 0 ) {
          if( sigPending != null && sigPending.getAsBoolean() ) return -4;  // -EINTR
          long chunk;
          if( deadline < 0 ) {
            chunk = (sigPending != null) ? SIG_POLL_MS : 0;   // 0 = 無期限 (supplier なし = 従来挙動)
          } else {
            long remain = deadline - System.currentTimeMillis();
            if( remain <= 0 ) return -110;
            chunk = (sigPending != null) ? Math.min( remain, SIG_POLL_MS ) : remain;
          }
          n.wait( chunk );
        }
        n.wakers--;
        // issue #549: FUTEX_CMP_REQUEUE で移送指定された待機者は、起床後に移送先
        //   uaddr で待ち直す (pthread_cond_signal/broadcast の cond→mutex requeue)。
        if( n.requeuePending > 0 ) {
          n.requeuePending--;
          requeueTo = n.requeueTarget;
        } else {
          return 0;
        }
      } catch( InterruptedException e ) {
        return -4;  // -EINTR
      } finally {
        n.waiters--;
      }
    }
    // requeueTo != 0: 元 uaddr の monitor を抜けて移送先で待ち直す
    return waitRequeued( requeueTo, timeout_ms, mem, sigPending );
  }

  // issue #549: requeue された待機者の再待機。移送先 uaddr で wake を待つ (値チェック
  //   なし = 既に移送済み)。移送先でさらに requeue される場合 (稀) は再帰する。
  private static int waitRequeued( long uaddr, long timeout_ms, MemoryBackend mem,
                                   java.util.function.BooleanSupplier sigPending ) {
    WaitNode n = node( mem, uaddr );
    long requeueTo = 0;
    synchronized( n ) {
      // issue #709 診断: requeue 先での再待機も記録 (値チェック無しなので expected は据置)
      n.dbgTimeoutMs = timeout_ms;
      n.dbgSince     = System.currentTimeMillis();
      n.dbgThread    = Thread.currentThread().getName() + "(requeued)";
      n.waiters++;
      try {
        long deadline = (timeout_ms < 0) ? -1 : System.currentTimeMillis() + timeout_ms;
        while( n.wakers == 0 ) {
          if( sigPending != null && sigPending.getAsBoolean() ) return -4;
          long chunk;
          if( deadline < 0 ) {
            chunk = (sigPending != null) ? SIG_POLL_MS : 0;
          } else {
            long remain = deadline - System.currentTimeMillis();
            if( remain <= 0 ) return -110;
            chunk = (sigPending != null) ? Math.min( remain, SIG_POLL_MS ) : remain;
          }
          n.wait( chunk );
        }
        n.wakers--;
        if( n.requeuePending > 0 ) {
          n.requeuePending--;
          requeueTo = n.requeueTarget;
        } else {
          return 0;
        }
      } catch( InterruptedException e ) {
        return -4;
      } finally {
        n.waiters--;
      }
    }
    return waitRequeued( requeueTo, timeout_ms, mem, sigPending );
  }

  // FUTEX_WAKE: uaddr の waiter を最大 max 個 wake。
  //   戻り値: 実際に起こした数 (glibc が信頼する)
  public static int wake( long uaddr, int max, MemoryBackend mem ) {
    WaitNode n = nodes.get( new Key( mem, uaddr ) );
    if( n == null ) return 0;
    synchronized( n ) {
      n.dbgWakeCalls++;   // issue #709 診断: 「wake は呼ばれたが起こす相手が居なかった」も記録
      int can_wake = Math.min( n.waiters - n.wakers, max );
      if( can_wake <= 0 ) return 0;
      n.wakers += can_wake;
      n.dbgWakeDelivered += can_wake;
      n.notifyAll();
      return can_wake;
    }
  }

  // issue #709 診断: 現在 futex で待機中の全 waiter を 1 行ずつ dump する (EMULIN_EPOLL_STUCK_MS
  //   の stuck dump から呼ばれる)。cur/raw は waiter 自身のアドレス空間 (Key.mm) から読むので
  //   他プロセスの waiter でも正確。
  //   判定: cur != expected なのに waited が大きい → 起こし取りこぼし (値は進んだのに wake が
  //   届いていない = Emulin バグ) / cur == expected → 本当に誰も値を進めていない (guest 側)。
  public static String debugDump( MemoryBackend memUnused ) {
    StringBuilder sb = new StringBuilder();
    long now = System.currentTimeMillis();
    for( java.util.Map.Entry<Key, WaitNode> e : nodes.entrySet() ) {
      WaitNode n = e.getValue();
      MemoryBackend mm = e.getKey().mm;
      long ua = e.getKey().uaddr;
      synchronized( n ) {
        if( n.waiters <= 0 ) continue;
        String cur, raw;
        try { cur = String.valueOf( mm.load32( ua ) ); }
        catch( Throwable t ) { cur = "?"; }
        // issue #709 診断: uaddr+4/+8/+12 も出す。pthread_mutex_t なら +8 が __owner (保持者の
        //   guest tid) = 「誰がロックを握ったまま走っていないか」を [thread] clone の tid と
        //   突き合わせて特定できる。condvar/sem では単なる周辺状態。
        try {
          raw = mm.load32( ua + 4 ) + "," + mm.load32( ua + 8 )
              + "," + mm.load32( ua + 12 );
        } catch( Throwable t ) { raw = "?"; }
        sb.append( "    uaddr=0x" ).append( Long.toHexString( ua ) )
          .append( " waiters=" ).append( n.waiters ).append( " wakers=" ).append( n.wakers )
          .append( " expected=" ).append( n.dbgExpected ).append( " cur=" ).append( cur )
          .append( " raw+4/8/12=[" ).append( raw ).append( ']' )
          .append( " to_ms=" ).append( n.dbgTimeoutMs )
          .append( " waited=" ).append( now - n.dbgSince ).append( "ms" )
          .append( " thr=" ).append( n.dbgThread )
          .append( n.dbgCaller != null ? " proc=" + n.dbgCaller : "" )
          .append( " wake=" ).append( n.dbgWakeDelivered ).append( "/" ).append( n.dbgWakeCalls )
          .append( '\n' );
      }
    }
    if( sb.length() == 0 ) sb.append( "    (no futex waiters)\n" );
    return sb.toString();
  }

  // issue #549: FUTEX_(CMP_)REQUEUE。uaddr1 の待機者を nrWake 人 wake、残りを
  //   nrRequeue 人 uaddr2 へ移送する (移送分は起床後に uaddr2 で待ち直す)。
  //   戻り値 = wake + 移送した数 (Linux 互換)。glibc の pthread_cond_signal/
  //   broadcast が cond futex の待機者を関連 mutex futex へ移すのに使う (thundering
  //   herd 回避)。未対応だと cond で待つスレッドが signal/broadcast で起きず取り残される。
  public static int requeue( long uaddr1, int nrWake, int nrRequeue, long uaddr2, MemoryBackend mem ) {
    WaitNode a = nodes.get( new Key( mem, uaddr1 ) );
    if( a == null ) return 0;
    synchronized( a ) {
      int avail = a.waiters - a.wakers;
      if( avail <= 0 ) return 0;
      int wake = Math.min( Math.max( nrWake, 0 ), avail );
      int req  = Math.min( Math.max( nrRequeue, 0 ), avail - wake );
      if( req > 0 ) {
        a.requeueTarget = uaddr2;
        a.requeuePending += req;
      }
      a.wakers += wake + req;
      a.notifyAll();
      return wake + req;
    }
  }

  // Thread が exit したときに呼ぶ (現状 no-op、将来 set_child_tid 連動に使う)
  public static void onThreadExit( int tid ) {
    // TODO
  }
}
