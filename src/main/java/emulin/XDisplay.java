package emulin;

import java.io.File;
import java.util.Arrays;
import java.util.List;

// --------------------------------------------------------------------
//  XDisplay — guest の X アプリを **host の X サーバ (VcXsrv 等)** に出す (issue #1021)。
//
//  経路は「guest の X client → TCP 127.0.0.1:(6000+display) → host の X サーバ」。
//  ssh も VNC も要らず、#1011 (WSLg + ssh -X) と違って **WSL ディストロも要らない**ので、
//  zip を展開して叩くだけの Windows 利用者に届く。
//
//  ★ 実測 (2026-09-11、本物の VcXsrv):
//    - **Windows の 127.0.0.1 からは認証なしで通る** (XLaunch の "Disable access control"
//      が既定オフでも、アクセス制御は**ホスト単位**で localhost は素通りするため)。
//      → この方式では `xauth` もクッキーも要らない。
//    - 一方 **WSL 上の Emulin から繋ぐと別ホスト扱いで拒否される**
//      ("Authorization required, but no authorization protocol specified")。
//      開発時に検証するなら `xhost +<WSL の IP>` が要る。**出荷経路と挙動が違う**ので注意。
//
//  ★ 前提が 2 つあり、**押す前に確かめて**から起動する (押してから失敗させない、#963 と同じ):
//    (1) host の 127.0.0.1:(6000+display) に X サーバが居ること
//    (2) guest に X アプリ (xterm) が入っていること — 出荷 rootfs には libX11 しか無い
//
//  ★ そして #949 のサンドボックスは guest → host loopback を**既定で遮断する**ので、
//    `EMULIN_ALLOW_HOST_LOOPBACK` に **X の port だけ**を許可する。`1` / `all` にしない。
// --------------------------------------------------------------------
public final class XDisplay {

  private XDisplay() { }

  /** X の TCP port は 6000 + display 番号 (X11 の決まり)。 */
  public static int port( int display ) { return 6000 + display; }

  /** 探す display 番号の上限。XLaunch の既定は 0 だが、利用者が別番号で起動していることもある。 */
  public static final int MAX_DISPLAY = 3;

  /** host の X サーバが居る display 番号を返す。居なければ -1。
   *
   *  ★ ランチャーは host (Windows) 側で動くので、**ここから直接 probe できる**。
   *    guest を起こしてから "Can't open display" を見せるより、押す前に分かる方がよい。 */
  public static int findServer( ) { return findServer( MAX_DISPLAY, 300 ); }

  public static int findServer( int maxDisplay, int timeoutMs ) {
    for( int d = 0; d <= maxDisplay; d++ ) {
      if( serverAt( d, timeoutMs ) ) return d;
    }
    return -1;
  }

  /** その display に X サーバが居るか (TCP が繋がるか)。 */
  public static boolean serverAt( int display, int timeoutMs ) {
    try( java.net.Socket s = new java.net.Socket() ) {
      s.connect( new java.net.InetSocketAddress( "127.0.0.1", port( display ) ), timeoutMs );
      return true;
    } catch( Exception e ) {
      return false;
    }
  }

  /** guest に X アプリが入っているか。出荷 rootfs には **libX11 はあるが xterm は無い**。 */
  public static boolean hasXterm( File home ) {
    return new File( GuestLaunch.rootfs( home ), "usr/bin/xterm" ).isFile();
  }

  /** guest 内で X アプリを起こす ProcessBuilder を作る。
   *
   *  ★ **起動と分けてあるのは検査のため** (GuestLaunch / SshdService と同じ)。検査側が
   *    自前で ProcessBuilder を組むと、ここが元に戻っても**緑のまま通る**。
   *
   *  ★ DISPLAY は **guest 側の `env` コマンドで与える**。host の環境変数に置いて
   *    EMULIN_INHERIT_ENV に頼る形にはしない — 継承は host の env を**丸ごと**渡す形で、
   *    credential サンドボックス (#401) の趣旨に反する上、「継承されたか」に依存すると
   *    経路が変わったとき黙って壊れる。argv に載っていれば読めば分かる。
   *
   *  @param display X の display 番号 (port は 6000+display)
   *  @param argv    guest 側で走らせる X アプリ (空なら xterm)
   *  @return ProcessBuilder。配布物が見つからなければ null。 */
  public static ProcessBuilder builder( File home, int display, boolean asRoot, String... argv ) {
    List<String> app = ( argv == null || argv.length == 0 )
                     ? Arrays.asList( "/usr/bin/xterm" )
                     : Arrays.asList( argv );
    java.util.List<String> guestArgv = new java.util.ArrayList<>();
    guestArgv.add( "/usr/bin/env" );
    guestArgv.add( "DISPLAY=127.0.0.1:" + display );
    guestArgv.addAll( app );
    // pool は端末と同じ扱い (X 端末の中でエージェントを動かすこともある)。
    ProcessBuilder pb = GuestLaunch.builderWithPool( home, guestArgv, asRoot,
                                                     GuestLaunch.AGENT_POOL_MB );
    if( pb == null ) return null;
    // ★ #949 の遮断を **X の port だけ** 開ける。1 / all にはしない。
    pb.environment().put( "EMULIN_ALLOW_HOST_LOOPBACK", String.valueOf( port( display ) ) );
    return GuestLaunch.withRole( pb, "xapp", port( display ) );
  }
}
