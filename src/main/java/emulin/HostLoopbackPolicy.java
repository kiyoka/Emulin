package emulin;

// --------------------------------------------------------------------
//  HostLoopbackPolicy — guest から **host の localhost 上のサービス**への到達を制御する。
//
//  issue #949: guest の connect は host のソケットに写される (Fileinfo.client_socket)。
//    ネットワーク名前空間の分離は無いので、guest が 127.0.0.1:8080 に繋ぐと
//    **host の 8080 に届く**。host の localhost には認証の無いサービスが立っている
//    ことが多く (開発用 web / DB / ローカル API)、sandbox の外へ手が伸びる。
//    #732 (ファイルパスの sandbox) がファイル側なら、これはネットワーク側の同じ穴。
//
//  ★ ただし **guest 内のプロセス同士の loopback 通信も、同じ host loopback を通る**。
//    guest が listen した port も host 上の ServerSocket だからで、素朴に遮断すると
//    guest 内の client/server (sshd / codex のローカルサーバ / X / VNC 等) が全部壊れる。
//    → **guest 自身が bind した port を覚えておき、そこだけ通す**。
//
//  既定: guest が bind していない host の loopback port は **遮断** (ECONNREFUSED)。
//  逃げ道: EMULIN_ALLOW_HOST_LOOPBACK
//      1 / all / true  … 全部許可 (0.8.4 までの挙動)
//      8080,5432       … 指定 port だけ許可
//
//  ★ 遮断したときは**必ず 1 行出す**。黙って ECONNREFUSED を返すと、利用者には
//    「なぜか繋がらない」としか見えない (#867 と同型の失敗をしないため)。
// --------------------------------------------------------------------
public final class HostLoopbackPolicy {

  private HostLoopbackPolicy( ) { }

  /** guest (または Emulin 自身) が host 上で listen した port。ここ宛は通す。 */
  private static final java.util.Set<Integer> guestPorts =
      java.util.Collections.newSetFromMap( new java.util.concurrent.ConcurrentHashMap<Integer,Boolean>() );

  /** 既に警告した宛先 (同じ宛先で毎回出すとログが埋まる)。 */
  private static final java.util.Set<String> warned =
      java.util.Collections.newSetFromMap( new java.util.concurrent.ConcurrentHashMap<String,Boolean>() );

  private static final boolean ALLOW_ALL;
  private static final java.util.Set<Integer> ALLOW_PORTS = new java.util.HashSet<>();
  static {
    String e = System.getenv( "EMULIN_ALLOW_HOST_LOOPBACK" );
    boolean all = false;
    if( e != null && !e.trim().isEmpty() ) {
      String v = e.trim();
      if( v.equals( "1" ) || v.equalsIgnoreCase( "all" ) || v.equalsIgnoreCase( "true" ) ) all = true;
      else for( String p : v.split( "[,\\s]+" ) ) {
        try { ALLOW_PORTS.add( Integer.parseInt( p.trim() ) ); } catch( NumberFormatException ignore ) { }
      }
    }
    ALLOW_ALL = all;
  }

  // ------------------------------------------------------------------
  //  ★ 台帳は **Emulin インスタンス間で共有する**。
  //
  //  最初の実装はプロセス内の Set だけだったが、それだと
  //  **「Emulin A の guest が listen し、Emulin B の guest が繋ぐ」**形を遮断してしまう。
  //  実際 tests/scripts/ssh-client-smoke.sh が sshd 用と ssh client 用に Emulin を
  //  2 つ起動しており、この構成で落ちた (自分の設計の穴をテストが捕まえた)。
  //
  //  → bind した port を `~/.emulin/ports/<port>` に置き、中身に pid を書く。
  //    connect 時にそれを見て「**どれかの Emulin の guest が listen している port**」なら通す。
  //    pid が生きていないファイルは掃除する (異常終了で残っても効かないように)。
  // ------------------------------------------------------------------
  //  ★ 置き場所が `~/.emulin` 配下なのは偶然ではない: #401 が **guest から
  //    host の ~/.emulin へのアクセスを遮断**している (Mount が非存在 sentinel に
  //    差し替える) ため、**guest はこの台帳を書き換えられない**。
  //    もし guest から書けたら、任意の port を「guest が listen している」と
  //    偽装して遮断を回避できてしまう。他の場所に置いてはいけない。
  private static java.io.File portDir() {
    return new java.io.File( new java.io.File( System.getProperty( "user.home", "." ), ".emulin" ), "ports" );
  }

  private static final long MYPID = ProcessHandle.current().pid();

  /** guest / Emulin が host 上で listen を始めた port を登録する (0 以下は無視)。 */
  public static void noteListen( int port ) {
    if( port <= 0 ) return;
    guestPorts.add( port );
    try {
      java.io.File d = portDir();
      if( !d.isDirectory() && !d.mkdirs() ) return;
      java.io.File f = new java.io.File( d, String.valueOf( port ) );
      java.nio.file.Files.write( f.toPath(), String.valueOf( MYPID ).getBytes( "US-ASCII" ) );
      f.deleteOnExit();
    } catch( Exception ignore ) { }   // 共有台帳が書けなくても、プロセス内の判定は効く
  }

  /** その port を listen している Emulin の pid (いなければ 0)。issue #963 の表示用。 */
  public static long listenerPid( int port ) {
    try {
      java.io.File f = new java.io.File( portDir(), String.valueOf( port ) );
      if( !f.isFile() ) return 0;
      long p = Long.parseLong(
          new String( java.nio.file.Files.readAllBytes( f.toPath() ), "US-ASCII" ).trim() );
      if( ProcessHandle.of( p ).map( ProcessHandle::isAlive ).orElse( false ) ) return p;
      f.delete();          // 死んだインスタンスの残骸は掃除する
      return 0;
    } catch( Exception e ) { return 0; }
  }

  /** 他の Emulin インスタンスの guest が listen している port か。 */
  private static boolean listenedByAnyEmulin( int port ) {
    try {
      java.io.File f = new java.io.File( portDir(), String.valueOf( port ) );
      if( !f.isFile() ) return false;
      String pid = new String( java.nio.file.Files.readAllBytes( f.toPath() ), "US-ASCII" ).trim();
      long p = Long.parseLong( pid );
      if( ProcessHandle.of( p ).map( ProcessHandle::isAlive ).orElse( false ) ) return true;
      f.delete();          // 死んだインスタンスの残骸は掃除する
      return false;
    } catch( Exception e ) {
      return false;
    }
  }

  /** loopback 宛か (IPv4 の 127.0.0.0/8 と IPv6 の ::1)。 */
  public static boolean isLoopback( String ip ) {
    if( ip == null ) return false;
    String s = ip.trim();
    if( s.startsWith( "127." ) ) return true;
    return s.equals( "::1" ) || s.equals( "0:0:0:0:0:0:0:1" );
  }

  /** int 形式の宛先 (Fileinfo.client_socket と同じ表現) 用。
   *  ★ `Util.ip_str( Util.swap32( ip ) )` が呼び元と同じ変換なので、それに合わせる
   *    (ここを間違えるとバイト順が逆になり、127.x.x.x を取り違える)。 */
  public static boolean allowConnect( int ip, int port ) {
    return allowConnect( Util.ip_str( Util.swap32( ip ) ), port );
  }

  /**
   *  guest からの connect を通してよいか。
   *
   *  @return true = 通す / false = 遮断する (呼び元は ECONNREFUSED を返す)
   */
  public static boolean allowConnect( String ip, int port ) {
    if( !isLoopback( ip ) ) return true;           // 外向きは対象外 (#401 の MITM が見る)
    if( guestPorts.contains( port ) ) return true;      // 自分の guest が listen している
    if( listenedByAnyEmulin( port ) ) return true;      // 他の Emulin の guest が listen している
    if( ALLOW_ALL || ALLOW_PORTS.contains( port ) ) return true;
    if( warned.add( ip + ":" + port ) ) {
      SyscallAmd64.TRACE_OUT.println( "[sandbox] guest から host の " + ip + ":" + port
          + " への接続を遮断しました (issue #949)。" );
      SyscallAmd64.TRACE_OUT.println( "[sandbox]   guest が listen している port ではないため、"
          + "host 側のサービスとみなしています。" );
      SyscallAmd64.TRACE_OUT.println( "[sandbox]   許可するには EMULIN_ALLOW_HOST_LOOPBACK="
          + port + " (複数は カンマ区切り / 全部なら 1) を設定してください。" );
    }
    return false;
  }
}
