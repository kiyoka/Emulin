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
        return 0;
      } catch( InterruptedException e ) {
        return -4;  // -EINTR
      } finally {
        n.waiters--;
      }
    }
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

  // Thread が exit したときに呼ぶ (現状 no-op、将来 set_child_tid 連動に使う)
  public static void onThreadExit( int tid ) {
    // TODO
  }
}
