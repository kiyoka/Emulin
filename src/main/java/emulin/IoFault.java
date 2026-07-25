package emulin;

import java.util.concurrent.atomic.AtomicLong;

/** issue #104 / #794: host I/O 失敗の注入 (テスト専用・既定 off)。
 *
 *  emulator は本質的に「host の失敗を guest の errno に翻訳する層」だが、その翻訳経路は
 *  **guest 側からは踏めない** (host のディスクを壊さないと IOException が起きない)。
 *  そこで SQLite の I/O Error Testing と同じく「N 回目の host I/O を失敗させる」注入口を置き、
 *  失敗地点を 1 つずつ進めながら全経路を踏ませる。
 *
 *  使い方:
 *    EMULIN_IO_FAIL_NTH=12   … 12 回目の host file I/O (read/write) を失敗させる
 *    EMULIN_IO_FAIL_EVERY=5  … 5 回に 1 回失敗させる (連続失敗の耐性を見る)
 *
 *  ★ 既定 (env 未指定) では `ARMED=false` となり、hit() は volatile boolean 1 つの参照で
 *    返るのでホットパスに影響しない (counter も回さない)。
 */
final class IoFault {
  private static final long NTH   = envLong( "EMULIN_IO_FAIL_NTH",   0 );
  private static final long EVERY = envLong( "EMULIN_IO_FAIL_EVERY", 0 );
  static  final boolean ARMED = ( NTH > 0 || EVERY > 0 );
  private static final AtomicLong COUNT = new AtomicLong();

  private IoFault() {}

  private static long envLong( String k, long dflt ) {
    try {
      String s = System.getenv( k );
      if( s != null && s.length() > 0 ) return Long.parseLong( s.trim() );
    } catch( RuntimeException ignored ) {}
    return dflt;
  }

  /** この host I/O を失敗させるか。ARMED でなければ即 false (counter も進めない)。 */
  static boolean hit() {
    if( !ARMED ) return false;
    long n = COUNT.incrementAndGet();
    if( NTH   > 0 && n == NTH )          return true;
    if( EVERY > 0 && ( n % EVERY ) == 0 ) return true;
    return false;
  }

  /** 何回 host I/O が起きたか (診断用)。 */
  static long count() { return COUNT.get(); }
}
