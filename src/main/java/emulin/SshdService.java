package emulin;

import java.io.*;
import java.util.*;

// --------------------------------------------------------------------
//  SshdService — ランチャーから guest の sshd を起動する (issue #963)
//
//  ★ `emulin.bat sshd` を呼ばない。Windows では cmd.exe / java.exe がコンソールアプリで、
//    GUI から起動すると**必ず黒い窓が出る** (利用者の指摘)。javaw で直接起動する。
//
//  ★ そのぶん `emulin.bat sshd` が持っていた前処理を Java 側でやる必要がある。
//    #919 (launcher が 2 系統) を自分で作らないよう、やることは 3 つに絞って明示する:
//      1. 非 root ユーザーを用意し、root の authorized_keys を共有する (#380)
//      2. host key の permission を 600 にする
//      3. sshd -D -e -p <port> -f /etc/ssh/sshd_config
// --------------------------------------------------------------------
public final class SshdService {

  public static final int DEFAULT_PORT = 2222;

  /** sshd 経由で claude / codex を動かす前提の native pool (MB)。実運用の指示による。 */
  public static final int SSHD_POOL_MB = 1024;

  private final File home;
  private volatile java.lang.Process proc;
  private volatile int port = DEFAULT_PORT;

  public SshdService( File home ) { this.home = home; }

  public boolean isRunning() {
    java.lang.Process p = proc;
    return p != null && p.isAlive();
  }

  public int port() { return port; }

  /** 起動前に満たしているべき条件。満たしていない理由を返す (空なら OK)。
   *
   *  ★ **押す前に**出すためにある。authorized_keys が無いと sshd は起動するが
   *  **誰も繋げない**ので、起動してから気付く形にはしない。 */
  public List<String> preflight() {
    return preflight( port );
  }

  public List<String> preflight( int port ) {
    List<String> ng = new ArrayList<>();
    String busy = portInUse( port );
    if( busy != null ) ng.add( busy );
    File rootfs = GuestLaunch.rootfs( home );
    if( !new File( rootfs, "usr/sbin/sshd" ).isFile() )
      ng.add( "this build has no sshd (you need a zip built with INCLUDE_SSHD=1)" );
    File keys = new File( rootfs, "root/.ssh/authorized_keys" );
    if( !keys.isFile() || keys.length() == 0 )
      ng.add( "no public key: put your SSH client's public key in " + keys.getPath() + ""
            + " (sshd will start without it, but nobody can connect)" );
    return ng;
  }

  /** その port が既に使われていれば理由を返す (空いていれば null)。
   *
   *  ★ 実際に **bind してみる**のが確実。`netstat` の解析や台帳だけの判定では、
   *  Emulin 以外のプロセス (別の sshd / VM / トンネル) が掴んでいる場合を取りこぼす。
   *  bind できたらすぐ閉じる。SO_REUSEADDR は付けない (付けると使用中でも通る)。 */
  public static String portInUse( int port ) {
    if( port <= 0 || port > 65535 ) return "invalid port number: " + port;
    try ( java.net.ServerSocket ss = new java.net.ServerSocket() ) {
      ss.setReuseAddress( false );
      ss.bind( new java.net.InetSocketAddress( java.net.InetAddress.getByName( "127.0.0.1" ), port ) );
      return null;                       // 空いている
    } catch( java.net.BindException be ) {
      // 誰が掴んでいるかまで言えると原因追跡が早い (#949 の port 台帳)。
      long pid = HostLoopbackPolicy.listenerPid( port );
      String who = ( pid > 0 ) ? "  (another Emulin is using it: pid " + pid + ")"
                               : "  (a non-Emulin process is using it)";
      return "port " + port + " is already in use." + who
           + " Choose another port, or stop whatever is using it.";
    } catch( Exception e ) {
      return "cannot check port " + port + ": " + e;
    }
  }

  /** 接続コマンドの案内。 */
  public List<String> connectHints() {
    List<String> out = new ArrayList<>();
    out.add( "ssh -p " + port + " root@127.0.0.1" );
    String u = GuestLaunch.guestUser( home );
    if( u != null ) out.add( "ssh -p " + port + " " + u + "@127.0.0.1" );
    return out;
  }

  /** 起動する。出力は onLine へ 1 行ずつ渡す。既に動いていれば何もしない。 */
  public synchronized void start( int port, java.util.function.Consumer<String> onLine ) {
    if( isRunning() ) { onLine.accept( "sshd is already running (port " + this.port + ")" ); return; }
    // ★ 起動してから「Address already in use」で死ぬのではなく、**押した時点で**言う。
    String busy = portInUse( port );
    if( busy != null ) { onLine.accept( "★ " + busy ); return; }
    this.port = port;
    // 1. 非 root ユーザーと authorized_keys の共有 (#380)
    prepareUser( onLine );
    // 2. host key の permission (600 でないと sshd が起動を拒む)
    runOnce( "/bin/chmod 600 /etc/ssh/ssh_host_ed25519_key", onLine );
    // 3. sshd 本体 (前面で走り続ける)
    // ★ sshd 経由では claude / codex を動かす可能性が高いので pool を 1024 にする
    //   (実運用の指示)。host の env の値によらずここで固定する。
    ProcessBuilder pb = GuestLaunch.builderWithPool( home, Arrays.asList(
        "/usr/sbin/sshd", "-D", "-e", "-p", String.valueOf( port ),
        "-f", "/etc/ssh/sshd_config" ), true, SSHD_POOL_MB );
    if( pb == null ) { onLine.accept( "distribution not found: " + home ); return; }
    try {
      proc = pb.start();
      onLine.accept( "sshd started (127.0.0.1:" + port + ")" );
      for( String h : connectHints() ) onLine.accept( "  " + h );
      final java.lang.Process p = proc;
      Thread t = new Thread( () -> {
        try ( BufferedReader r = new BufferedReader( new InputStreamReader(
                  p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8 ) ) ) {
          String line;
          while( ( line = r.readLine() ) != null ) {
            String d = GuestJob.sanitizeForDisplay( line );
            if( !d.isEmpty() ) onLine.accept( d );
          }
        } catch( Exception ignore ) { }
        onLine.accept( "sshd exited (exit=" + p.exitValue() + ")" );
      }, "emulin-sshd-out" );
      t.setDaemon( true );
      t.start();
    } catch( Exception e ) {
      onLine.accept( "failed to start sshd: " + e );
    }
  }

  public synchronized void stop( java.util.function.Consumer<String> onLine ) {
    java.lang.Process p = proc;
    if( p == null || !p.isAlive() ) { onLine.accept( "sshd is not running" ); return; }
    p.destroy();
    onLine.accept( "sshd stopped" );
  }

  /** 非 root ユーザーを用意し、root の authorized_keys を共有する (#380 と同じこと)。 */
  private void prepareUser( java.util.function.Consumer<String> onLine ) {
    String u = GuestLaunch.guestUser( home );
    if( u == null ) {
      runOnce( "/bin/sh /usr/local/sbin/emulin-adduser --detect", onLine );
      u = GuestLaunch.guestUser( home );
      if( u == null ) return;            // 非 root ユーザーが無い bundle もある
    }
    runOnce( "mkdir -p /home/" + u + "/.ssh"
           + "; [ -f /root/.ssh/authorized_keys ] && cp -f /root/.ssh/authorized_keys"
           + " /home/" + u + "/.ssh/authorized_keys"
           + "; chmod 700 /home/" + u + " /home/" + u + "/.ssh 2>/dev/null"
           + "; chmod 600 /home/" + u + "/.ssh/authorized_keys 2>/dev/null"
           + "; chown -R 1000:1000 /home/" + u + " 2>/dev/null; true", onLine );
  }

  /** 短い前処理を 1 本走らせる (出力は失敗したときだけ見せる)。 */
  private void runOnce( String shellCommand, java.util.function.Consumer<String> onLine ) {
    GuestJob j = new GuestJob( "sshd setup", shellCommand, true );
    j.run( home, null );
    if( j.state != GuestJob.State.DONE ) {
      onLine.accept( "setup step failed (exit=" + j.exitCode + "): " + shellCommand );
      for( String l : j.tailLines() ) onLine.accept( "    " + l );
    }
  }
}
