// ----------------------------------------
//  FutexManager — pthread sync support (Phase 27 step 28)
//
//  Linux futex(2) の最小実装:
//    FUTEX_WAIT (0):  *uaddr == val なら block (timeout まで)、else -EAGAIN
//    FUTEX_WAKE (1):  uaddr の waiter を最大 n 個起こす、起こした数を返す
//    その他: ENOSYS で諦めさせる (glibc は通常 fallback path に入る)
//
//  実装: アドレスごとに Object monitor を作り、wait/notifyAll で同期する。
//  uaddr は Long.valueOf(address) を key にした ConcurrentHashMap で管理。
// ----------------------------------------
package emulin;

import java.util.concurrent.ConcurrentHashMap;

public class FutexManager {
  // FUTEX_WAIT (0), FUTEX_WAKE (1) and FUTEX_PRIVATE_FLAG (128)
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
  public static final int FUTEX_OP_MASK = 0x7F;  // PRIVATE / CLOCK_REALTIME を除く

  // アドレスごとの monitor。ConcurrentHashMap で thread-safe に lazy-init。
  private static final ConcurrentHashMap<Long, Object> monitors = new ConcurrentHashMap<>();

  // 内部: アドレスの monitor を取得/作成
  private static Object monitor( long uaddr ) {
    return monitors.computeIfAbsent( uaddr, k -> new Object() );
  }

  // FUTEX_WAIT: *uaddr が val と等しければ block。timeout 経過 or wake で復帰。
  //   timeout_ms < 0 なら無期限待ち。0 なら即時 timeout 扱い。
  //   戻り値: 0 (woken / spurious), -EAGAIN (-11) (val 不一致), -ETIMEDOUT (-110)
  public static int wait( long uaddr, int expected, long timeout_ms, Memory mem ) {
    Object m = monitor( uaddr );
    synchronized( m ) {
      // 競合避けのため lock 取得後に値を再 check (compare-and-block の atomic 風)
      int cur = mem.load32( uaddr );
      if( cur != expected ) return -11;  // -EAGAIN
      try {
        if( timeout_ms < 0 ) {
          m.wait();
        } else if( timeout_ms == 0 ) {
          return -110;  // -ETIMEDOUT 即返し
        } else {
          long t0 = System.currentTimeMillis();
          m.wait( timeout_ms );
          long elapsed = System.currentTimeMillis() - t0;
          if( elapsed >= timeout_ms ) return -110;  // -ETIMEDOUT
        }
      } catch( InterruptedException e ) {
        return -4;  // -EINTR
      }
      return 0;
    }
  }

  // FUTEX_WAKE: uaddr の waiter を最大 n 個 wake。実装上は notifyAll で全員起こす
  //   (n を厳密に守るには Object monitor では足りない)。glibc は wake count を
  //   厳密に検証しないので問題なし。
  //   戻り値: 起こした (見込みの) 数 = n と現在の waiter 数の min
  public static int wake( long uaddr, int n ) {
    Object m = monitors.get( uaddr );
    if( m == null ) return 0;  // 待機者ゼロ
    synchronized( m ) {
      m.notifyAll();
    }
    return n;  // 厳密な count は返せないが pessimistic に「全部起きた」を返す
  }

  // Thread が exit したときに呼ぶ。set_child_tid に登録された futex を wake する。
  //   現状 set_child_tid 登録経路がまだ無いので no-op スタブ。
  public static void onThreadExit( int tid ) {
    // TODO: ctid_addr に 0 を書き、futex wake する (pthread_join 経由)
  }
}
