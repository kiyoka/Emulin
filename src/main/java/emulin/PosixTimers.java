package emulin;

import java.util.HashMap;
import java.util.Map;

/** issue #797: POSIX per-process タイマ (timer_create / timer_settime / timer_gettime /
 *  timer_delete / timer_getoverrun)。
 *
 *  旧実装は「1 プロセス 1 タイマ・timerid は常に 0」の近似で、
 *    - `sevp` (sigev_signo) を無視して常に SIGALRM
 *    - `it_interval` (周期) を無視してワンショットのみ
 *    - `old_value` / `timer_gettime` を埋めない
 *    - **disarm / delete しても、arm 時に spawn したスレッドが後から SIGALRM を撃つ**
 *    - 無効な timerid でも成功を返す
 *  という状態だった (非公開 #120 のテストが 10/19 で検出)。
 *
 *  ここでは per-process のタイマ表として作り直す。
 *  ★ キャンセルは**世代 (gen) 方式**で行う: arm/disarm/delete のたびに世代を進め、
 *    走っている worker は「自分の世代が現行か」を発火直前に確認する。世代が古ければ
 *    何もせず終了するので、**disarm 済みタイマの幽霊シグナル**が起きない。
 *
 *  fork では継承しない (Linux も同じ) ので、子プロセスの Syscall には引き継がせないこと。
 */
final class PosixTimers {

  /** タイマ 1 個分の状態。全アクセスは PosixTimers の synchronized 下で行う。 */
  private static final class T {
    int  id;
    int  signo;          // sigev_signo (既定 SIGALRM=14)
    long intervalNs;     // it_interval。0 = ワンショット
    long deadlineNs;     // 発火予定 (nanoTime 基準)。0 = disarmed
    int  gen;            // arm 世代。worker はこれと一致するときだけ発火する
    int  overrun;        // timer_getoverrun 用 (取りこぼし数)
  }

  private final Map<Integer, T> timers = new HashMap<>();
  private final Sysinfo sysinfo;
  private final Process process;
  private int nextId = 1;   // 0 も有効な id だが、旧実装との混同を避けて 1 から配る

  PosixTimers( Sysinfo sysinfo, Process process ) {
    this.sysinfo = sysinfo;
    this.process = process;
  }

  private static long nowNs() { return System.nanoTime(); }

  /** timer_create: 新しいタイマを 1 個作り id を返す (負値は errno)。 */
  synchronized long create( int signo ) {
    T t = new T();
    t.id    = nextId++;
    t.signo = ( signo > 0 && signo < 64 ) ? signo : 14 /* SIGALRM */;
    timers.put( Integer.valueOf( t.id ), t );
    return t.id;
  }

  /** timer_settime。value/interval は ns。old_* に旧値 (残り時間) を書き戻す。
   *  戻り値 0 か -EINVAL。old が null なら書き戻さない。 */
  synchronized long setTime( int id, long valueNs, long intervalNs, long[] old ) {
    T t = timers.get( Integer.valueOf( id ) );
    if( t == null ) return -22L;                      // EINVAL: 無効な timerid
    if( valueNs < 0 || intervalNs < 0 ) return -22L;  // EINVAL: 負の時間
    if( old != null ) {
      // 旧設定の「残り時間」。disarmed なら 0 (Linux も 0 を返す)。
      long remain = ( t.deadlineNs == 0 ) ? 0 : ( t.deadlineNs - nowNs() );
      if( remain < 0 ) remain = 0;
      old[0] = remain;
      old[1] = t.intervalNs;
    }
    t.gen++;                       // ★ 既存 worker を無効化 (disarm 後の幽霊発火を防ぐ)
    t.intervalNs = intervalNs;
    if( valueNs == 0 ) {           // it_value = 0 → disarm
      t.deadlineNs = 0;
      return 0;
    }
    t.deadlineNs = nowNs() + valueNs;
    startWorker( t );
    return 0;
  }

  /** timer_gettime: 残り時間と interval を返す (負値は errno)。 */
  synchronized long getTime( int id, long[] out ) {
    T t = timers.get( Integer.valueOf( id ) );
    if( t == null ) return -22L;   // EINVAL
    long remain = ( t.deadlineNs == 0 ) ? 0 : ( t.deadlineNs - nowNs() );
    if( remain < 0 ) remain = 0;
    out[0] = remain;
    out[1] = t.intervalNs;
    return 0;
  }

  synchronized long getOverrun( int id ) {
    T t = timers.get( Integer.valueOf( id ) );
    if( t == null ) return -22L;   // EINVAL
    return t.overrun;
  }

  /** timer_delete: タイマを破棄する。走っている worker は世代で無効化される。 */
  synchronized long delete( int id ) {
    T t = timers.remove( Integer.valueOf( id ) );
    if( t == null ) return -22L;   // EINVAL
    t.gen++;                       // ★ 発火予定を取り消す
    t.deadlineNs = 0;
    return 0;
  }

  /** execve でタイマは消える (Linux も同じ)。 */
  synchronized void clear() {
    for( T t : timers.values() ) { t.gen++; t.deadlineNs = 0; }
    timers.clear();
  }

  // 発火用の worker。1 回の arm につき 1 本。周期タイマなら interval ごとに繰り返す。
  //   ★ 発火直前に必ず「世代が現行か」を確認する (disarm/delete/再 arm で無効化される)。
  private void startWorker( final T t ) {
    final int myGen = t.gen;
    final int id    = t.id;
    Thread th = new Thread( () -> {
      for(;;) {
        long waitNs;
        synchronized( PosixTimers.this ) {
          T cur = timers.get( Integer.valueOf( id ) );
          if( cur == null || cur.gen != myGen || cur.deadlineNs == 0 ) return;  // 無効化された
          waitNs = cur.deadlineNs - nowNs();
        }
        if( waitNs > 0 ) {
          try { Thread.sleep( waitNs / 1_000_000L, (int)( waitNs % 1_000_000L ) ); }
          catch( InterruptedException ie ) { return; }
        }
        int signo;
        synchronized( PosixTimers.this ) {
          T cur = timers.get( Integer.valueOf( id ) );
          if( cur == null || cur.gen != myGen || cur.deadlineNs == 0 ) return;  // 待っている間に無効化
          if( nowNs() < cur.deadlineNs ) continue;   // 早すぎる起床 (spurious) → 待ち直す
          signo = cur.signo;
          if( cur.intervalNs > 0 ) {
            // 周期タイマ: 次の発火予定へ進める。大きく遅れていたら overrun を数えて追いつく。
            cur.deadlineNs += cur.intervalNs;
            long behind = nowNs() - cur.deadlineNs;
            if( behind > 0 ) {
              long skip = behind / cur.intervalNs + 1;
              cur.overrun += (int)skip;
              cur.deadlineNs += skip * cur.intervalNs;
            }
          } else {
            cur.deadlineNs = 0;   // ワンショットは発火したら disarm
          }
        }
        try { sysinfo.kernel.kill( process.pid, signo ); }
        catch( RuntimeException re ) { return; }
        synchronized( PosixTimers.this ) {
          T cur = timers.get( Integer.valueOf( id ) );
          if( cur == null || cur.gen != myGen || cur.deadlineNs == 0 ) return;  // ワンショット終了
        }
      }
    }, "posix-timer-" + id );
    th.setDaemon( true );
    th.start();
  }
}
