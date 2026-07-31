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
  // issue #740: FUTEX_WAIT/WAKE (bitset 無し) は「全 bit に一致」と等価。
  public static final int FUTEX_BITSET_MATCH_ANY = 0xFFFFFFFF;

  // アドレスごとの状態。waiters は wait に入っている thread 数。wake は
  //   real waiter count に基づいて実数を返す必要がある。
  // issue #740: 待機者 1 人分の札。**bitset で選んで起こす**ために待機者を個別に識別する。
  //   従来は waiters/wakers のカウンタ credit だけで、誰を起こすかを選べなかった。
  //   その結果 FUTEX_WAKE_BITSET が bitset を無視して任意の待機者を起こし、
  //   **起こす枠を別 phase の待機者が消費して狙った相手が永久に起きない**という
  //   lost wakeup を作っていた (V8 の rwlock が readers/writers を bitset で分ける)。
  static final class Ticket {
    final int bitset;      // この待機者が WAIT_BITSET で指定した mask (素の WAIT は MATCH_ANY)
    boolean   granted;     // wake で起こす許可が出た
    long      requeueTo;   // issue #549: 移送先 uaddr (0 = 移送しない)
    Ticket( int b ) { bitset = b; }
  }

  static class WaitNode {
    // 待機者の FIFO。Linux も FIFO で起こすので順序を合わせる。
    final java.util.ArrayDeque<Ticket> q = new java.util.ArrayDeque<Ticket>();
    int waiters;
    int wakers;  // 診断用: 許可済みでまだ抜けていない数
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
    // issue #709 診断: 現在の全 waiter (診断時のみ記録。従来は最後の 1 人しか見えず、
    //   「行方不明のスレッド」が実は匿名の相方だったことを見逃した)。監視は node monitor。
    final java.util.ArrayList<String> curWaiters = new java.util.ArrayList<>();
    // issue #709 診断: 直近 8 件の wake/requeue イベント (絶対 ms:種別:要求:実起床)。
    //   「wake が発行されたのに誰も起きなかった/そもそも発行されていない」を凍結後に判別する。
    final java.util.ArrayDeque<String> wakeHist = new java.util.ArrayDeque<>();
  }
  private static void _histAdd( WaitNode n, String ev ) {   // node monitor 下で呼ぶ
    n.wakeHist.addLast( ev );
    if( n.wakeHist.size() > 8 ) n.wakeHist.pollFirst();
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

  // issue #788: Linux が (mm, uaddr) で照合するのは **private futex だけ**。
  //   FUTEX_PRIVATE_FLAG の無い shared futex は「物理ページ (inode+offset)」で照合されるので、
  //   MAP_SHARED を共有する別プロセス同士でも同じキーになり wake が届く。#709 の修正で
  //   全 futex を (mm, uaddr) にしたため、**正当な cross-process wake まで分断**されていた
  //   (子の FUTEX_WAIT が親の FUTEX_WAKE を受け取れず ETIMEDOUT)。
  //   shared のときは mm を null にして全プロセス共通の名前空間に置く。異なる共有領域が
  //   同一 VA に載ると偽 wake の可能性があるが、futex の契約上 spurious wake は許容される
  //   (呼び出し側は必ず条件を再検査する) ので安全側。private futex は従来どおり mm で分離する。
  private static MemoryBackend keyMm( MemoryBackend mem, boolean shared ) {
    return shared ? null : mem;
  }

  private static WaitNode node( MemoryBackend mem, long uaddr, boolean shared ) {
    return nodes.computeIfAbsent( new Key( keyMm( mem, shared ), uaddr ), k -> new WaitNode() );
  }

  // FUTEX_WAIT: *uaddr が val と等しければ block。
  //   timeout_ms < 0 なら無期限。0 なら即 timeout 扱い。
  //   戻り値: 0 (woken), -EAGAIN (-11) (val 不一致), -ETIMEDOUT (-110), -EINTR (-4)
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem ) {
    return wait( uaddr, expected, timeout_ms, mem, null, false );
  }
  // issue #533: FUTEX_WAIT は Linux ではシグナル到達で -EINTR する (handler は syscall 復帰時に
  //   実行され、glibc の futex 呼び出し側は EINTR 後に再待機する)。旧実装は無限 Object.wait() で
  //   guest シグナルに割り込まれず、futex で park 中の thread へ宛てたシグナル (JSC/Bun の
  //   thread suspend-resume handshake の suspend 信号等) が syscall 境界に到達できず永遠に
  //   配送されなかった。sigPending (呼び出し guest thread の pending シグナル有無) を渡された
  //   場合は待ちを 25ms 単位に刻み、pending を検知したら -EINTR で復帰する
  //   (通常の FUTEX_WAKE は従来どおり notifyAll で即時 wake、レイテンシ影響なし)。
  private static final long SIG_POLL_MS = 25L;

  // issue #759 診断: EMULIN_FUTEX_STUCK_MS=N — N ms 以上 wake されない待機者を N ms ごとに
  //   報告する。**報告時に uaddr の「現在値」を読み直す**のが肝で、これで
  //     cur != exp → 値は動いたのに起こされていない = wake の取りこぼし (Emulin 側のバグ)
  //     cur == exp → 値が動いていない = guest が unlock/signal していない (保持者側の問題)
  //   を一目で切り分けられる。既定 off (0) でホットパス無影響。
  private static final long STUCK_MS;
  static {
    long v = 0;
    try {
      String s = System.getenv( "EMULIN_FUTEX_STUCK_MS" );
      if( s != null ) v = Long.parseLong( s );
    } catch( Exception ignored ) { }
    STUCK_MS = v;
  }
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem,
                          java.util.function.BooleanSupplier sigPending ) {
    return wait( uaddr, expected, timeout_ms, mem, sigPending, false );
  }

  // issue #788: shared=true は FUTEX_PRIVATE_FLAG の無い shared futex (cross-process)
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem,
                          java.util.function.BooleanSupplier sigPending, boolean shared ) {
    return wait( uaddr, expected, timeout_ms, mem, sigPending, shared, FUTEX_BITSET_MATCH_ANY );
  }

  // issue #740: bitset 付き。素の FUTEX_WAIT は MATCH_ANY (全ての wake に反応) と等価。
  public static int wait( long uaddr, int expected, long timeout_ms, MemoryBackend mem,
                          java.util.function.BooleanSupplier sigPending, boolean shared,
                          int bitset ) {
    WaitNode n = node( mem, uaddr, shared );
    long requeueTo = 0;
    Ticket t = new Ticket( bitset );
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
      String wtag = null;
      if( SyscallAmd64.EPOLL_STUCK_MS > 0 ) {   // 診断時のみ: 全 waiter 列挙用
        wtag = n.dbgSince + "ms " + n.dbgThread + " exp=" + expected
             + ( n.dbgCaller != null ? " " + n.dbgCaller : "" );
        n.curWaiters.add( wtag );
      }
      n.waiters++;
      n.q.addLast( t );
      try {
        if( timeout_ms == 0 ) return -110;
        long deadline = (timeout_ms < 0) ? -1 : System.currentTimeMillis() + timeout_ms;
        long lastReport = n.dbgSince;
        while( !t.granted ) {
          if( sigPending != null && sigPending.getAsBoolean() ) return -4;  // -EINTR
          long chunk;
          if( deadline < 0 ) {
            // issue #759 診断: 無期限待ちでも stuck 報告のために刻む (診断時のみ)。
            chunk = (sigPending != null) ? SIG_POLL_MS : ( STUCK_MS > 0 ? STUCK_MS : 0 );
          } else {
            long remain = deadline - System.currentTimeMillis();
            if( remain <= 0 ) return -110;
            chunk = (sigPending != null) ? Math.min( remain, SIG_POLL_MS ) : remain;
          }
          n.wait( chunk );
          if( STUCK_MS > 0 && !t.granted ) {
            long now = System.currentTimeMillis();
            if( now - lastReport >= STUCK_MS ) {
              lastReport = now;
              int nowVal = mem.load32( uaddr );
              System.err.println( "[futex-stuck] uaddr=0x" + Long.toHexString( uaddr )
                  + " exp=" + expected + " cur=" + nowVal
                  + ( nowVal != expected ? " VALUE-CHANGED-BUT-NOT-WOKEN" : " value-unchanged" )
                  + " waited=" + ( now - n.dbgSince ) + "ms waiters=" + n.waiters
                  + " to_ms=" + timeout_ms + " " + Thread.currentThread().getName()
                  + ( n.dbgCaller != null ? " " + n.dbgCaller : "" ) );
            }
          }
        }
        n.wakers--;
        // issue #549: FUTEX_CMP_REQUEUE で移送指定された待機者は、起床後に移送先
        //   uaddr で待ち直す (pthread_cond_signal/broadcast の cond→mutex requeue)。
        if( t.requeueTo != 0 ) {
          requeueTo = t.requeueTo;
        } else {
          return 0;
        }
      } catch( InterruptedException e ) {
        return -4;  // -EINTR
      } finally {
        n.waiters--;
        n.q.remove( t );
        if( wtag != null ) n.curWaiters.remove( wtag );
      }
    }
    // requeueTo != 0: 元 uaddr の monitor を抜けて移送先で待ち直す
    return waitRequeued( requeueTo, timeout_ms, mem, sigPending, shared );
  }

  // issue #549: requeue された待機者の再待機。移送先 uaddr で wake を待つ (値チェック
  //   なし = 既に移送済み)。移送先でさらに requeue される場合 (稀) は再帰する。
  private static int waitRequeued( long uaddr, long timeout_ms, MemoryBackend mem,
                                   java.util.function.BooleanSupplier sigPending, boolean shared ) {
    WaitNode n = node( mem, uaddr, shared );
    long requeueTo = 0;
    // issue #740: 移送先での待ち直しに bitset 意味論は無い (Linux も requeue 先は素の待ち)。
    Ticket t = new Ticket( FUTEX_BITSET_MATCH_ANY );
    synchronized( n ) {
      // issue #709 診断: requeue 先での再待機も記録 (値チェック無しなので expected は据置)
      n.dbgTimeoutMs = timeout_ms;
      n.dbgSince     = System.currentTimeMillis();
      n.dbgThread    = Thread.currentThread().getName() + "(requeued)";
      String wtag = null;
      if( SyscallAmd64.EPOLL_STUCK_MS > 0 ) {
        wtag = n.dbgSince + "ms " + n.dbgThread;
        n.curWaiters.add( wtag );
      }
      n.waiters++;
      n.q.addLast( t );
      try {
        long deadline = (timeout_ms < 0) ? -1 : System.currentTimeMillis() + timeout_ms;
        while( !t.granted ) {
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
        if( t.requeueTo != 0 ) {
          requeueTo = t.requeueTo;
        } else {
          return 0;
        }
      } catch( InterruptedException e ) {
        return -4;
      } finally {
        n.waiters--;
        n.q.remove( t );
        if( wtag != null ) n.curWaiters.remove( wtag );
      }
    }
    return waitRequeued( requeueTo, timeout_ms, mem, sigPending, shared );
  }

  // FUTEX_WAKE: uaddr の waiter を最大 max 個 wake。
  //   戻り値: 実際に起こした数 (glibc が信頼する)
  public static int wake( long uaddr, int max, MemoryBackend mem ) {
    return wake( uaddr, max, mem, false );
  }

  // issue #788: shared=true は cross-process の shared futex (mm を跨いで照合する)
  public static int wake( long uaddr, int max, MemoryBackend mem, boolean shared ) {
    return wake( uaddr, max, mem, shared, FUTEX_BITSET_MATCH_ANY );
  }

  // issue #740: bitset 付き。FUTEX_WAKE_BITSET は **bitset が交差する待機者だけ**を起こす。
  //   従来はここを無視して任意の待機者を起こしていたため、例えば
  //   「writer を 1 人起こす」つもりの wake を reader が消費し、条件不成立で寝直す
  //   → 狙った writer は永久に起きない、という lost wakeup になっていた
  //   (V8 の rwlock が readers/writers phase を bitset で分けるので直撃する)。
  public static int wake( long uaddr, int max, MemoryBackend mem, boolean shared, int bitset ) {
    WaitNode n = nodes.get( new Key( keyMm( mem, shared ), uaddr ) );
    if( n == null ) return 0;
    synchronized( n ) {
      n.dbgWakeCalls++;   // issue #709 診断: 「wake は呼ばれたが起こす相手が居なかった」も記録
      int woke = 0;
      if( max > 0 ) {
        for( Ticket t : n.q ) {                       // FIFO 順 (Linux と同じ)
          if( woke >= max ) break;
          if( t.granted ) continue;
          if( ( t.bitset & bitset ) == 0 ) continue;  // ★ bitset が交差しない待機者は対象外
          t.granted = true;
          woke++;
        }
      }
      if( SyscallAmd64.EPOLL_STUCK_MS > 0 )
        _histAdd( n, System.currentTimeMillis() + "ms wake n=" + max + " bs=0x"
                     + Integer.toHexString( bitset ) + " del=" + woke
                     + " thr=" + Thread.currentThread().getName() );
      if( woke <= 0 ) return 0;
      n.wakers += woke;
      n.dbgWakeDelivered += woke;
      n.notifyAll();
      return woke;
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
        for( String w : n.curWaiters )
          sb.append( "      waiter: " ).append( w ).append( '\n' );
        for( String h : n.wakeHist )
          sb.append( "      hist:   " ).append( h ).append( '\n' );
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
    return requeue( uaddr1, nrWake, nrRequeue, uaddr2, mem, false );
  }

  // issue #788: shared=true は cross-process の shared futex
  public static int requeue( long uaddr1, int nrWake, int nrRequeue, long uaddr2,
                             MemoryBackend mem, boolean shared ) {
    WaitNode a = nodes.get( new Key( keyMm( mem, shared ), uaddr1 ) );
    if( a == null ) return 0;
    synchronized( a ) {
      int avail = a.waiters - a.wakers;
      if( SyscallAmd64.EPOLL_STUCK_MS > 0 )
        _histAdd( a, System.currentTimeMillis() + "ms requeue wake=" + nrWake + " rq=" + nrRequeue
                     + " avail=" + avail + " -> 0x" + Long.toHexString( uaddr2 ) );
      if( avail <= 0 ) return 0;
      int wake = Math.min( Math.max( nrWake, 0 ), avail );
      int req  = Math.min( Math.max( nrRequeue, 0 ), avail - wake );
      // issue #740: credit でなく**チケット単位**で許可する。先頭から wake 人は素の起床、
      //   続く req 人は移送先を書いてから起こす (起床後に uaddr2 で待ち直す)。
      int done = 0;
      for( Ticket t : a.q ) {
        if( done >= wake + req ) break;
        if( t.granted ) continue;
        if( done >= wake ) t.requeueTo = uaddr2;
        t.granted = true;
        done++;
      }
      if( done <= 0 ) return 0;
      a.wakers += done;
      a.notifyAll();
      return done;
    }
  }

  // Thread が exit したときに呼ぶ (現状 no-op、将来 set_child_tid 連動に使う)
  public static void onThreadExit( int tid ) {
    // TODO
  }
}
