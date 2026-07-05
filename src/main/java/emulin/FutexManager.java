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
  }

  private static final ConcurrentHashMap<Long, WaitNode> nodes = new ConcurrentHashMap<>();

  private static WaitNode node( long uaddr ) {
    return nodes.computeIfAbsent( uaddr, k -> new WaitNode() );
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
    WaitNode n = node( uaddr );
    long requeueTo = 0;
    synchronized( n ) {
      // lock 取得後に値を再 check (compare-and-block の atomic 風)
      int cur = mem.load32( uaddr );
      if( cur != expected ) return -11;  // -EAGAIN
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
    WaitNode n = node( uaddr );
    long requeueTo = 0;
    synchronized( n ) {
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
  public static int wake( long uaddr, int max ) {
    WaitNode n = nodes.get( uaddr );
    if( n == null ) return 0;
    synchronized( n ) {
      int can_wake = Math.min( n.waiters - n.wakers, max );
      if( can_wake <= 0 ) return 0;
      n.wakers += can_wake;
      n.notifyAll();
      return can_wake;
    }
  }

  // issue #549: FUTEX_(CMP_)REQUEUE。uaddr1 の待機者を nrWake 人 wake、残りを
  //   nrRequeue 人 uaddr2 へ移送する (移送分は起床後に uaddr2 で待ち直す)。
  //   戻り値 = wake + 移送した数 (Linux 互換)。glibc の pthread_cond_signal/
  //   broadcast が cond futex の待機者を関連 mutex futex へ移すのに使う (thundering
  //   herd 回避)。未対応だと cond で待つスレッドが signal/broadcast で起きず取り残される。
  public static int requeue( long uaddr1, int nrWake, int nrRequeue, long uaddr2 ) {
    WaitNode a = nodes.get( uaddr1 );
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
