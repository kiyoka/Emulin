package emulin;

import java.io.File;

// --------------------------------------------------------------------
//  EmulinStatus — 「いま Emulin の中で何が起きているか」を集める (issue #948)
//
//  ★ なぜ要るか: 2026-08-15/16 に起きた 3 件は、どれも「**状態が見えないこと自体**」が原因だった。
//      - Emulin が 2 つ動いていることに気付かず、同じ credential を共有して token の回転が
//        衝突し Login expired を 2 度起こした (#943 / #944)
//      - credential が**起動時に一度しか読まれない**ことが見えず往復した
//      - Remote Control が bridge + worker の **2 プロセス**であることが見えなかった
//    診断ログ (EMULIN_TRACE_FILE) は後から読むもので、**いまの状態を一覧する手段が無かった**。
//
//  ★ **収集と表示を分ける**。表示は Swing (LauncherApp) が担当する。ここは値の取得だけ。
//    こうしておけば、将来 Web や CLI から見せたくなっても収集を書き直さずに済む。
//
//  ★ credential は **名前・登録有無・日時だけ**。値は絶対に出さない (#401 の不変条件)。
// --------------------------------------------------------------------
public final class EmulinStatus {

  private EmulinStatus( ) { }

  public static final long PID      = ProcessHandle.current().pid();
  public static final long START_MS = System.currentTimeMillis();

  private static volatile Kernel kernel;
  private static volatile String rootfs = "";

  public static void attach( Kernel k, String rootfsPath ) {
    kernel = k;
    rootfs = ( rootfsPath == null ? "" : rootfsPath );
    writeInstanceFile();
    Runtime.getRuntime().addShutdownHook(
        new Thread( EmulinStatus::removeInstanceFile, "emulin-status-cleanup" ) );
  }

  // ------------------------------------------------------------------
  //  インスタンス台帳 — ★ 今回の事故の本体。
  //    「Emulin が 2 つ動いていて同じ credential を共有している」ことを見えるようにする。
  //
  //  ★ 置き場所が `~/.emulin` 配下なのは意図的: #401 が **guest からのアクセスを遮断**して
  //    いるので、guest はこの台帳を読み書きできない (#949 の ports 台帳と同じ理由)。
  // ------------------------------------------------------------------
  public static final class Instance {
    public long pid; public long startedAt; public String version = "", backend = "", rootfs = "";
    public boolean self;
  }

  private static File instanceDir() {
    return new File( new File( System.getProperty( "user.home", "." ), ".emulin" ), "instances" );
  }

  private static void writeInstanceFile() {
    try {
      File d = instanceDir();
      if( !d.isDirectory() && !d.mkdirs() ) return;
      String backend = "";
      try { backend = CpuBackend.resolve().displayName(); } catch( Throwable ignore ) { }
      String j = "pid=" + PID + "\nstartedAt=" + START_MS + "\nversion=" + Version.get_version()
               + "\nbackend=" + backend + "\nrootfs=" + rootfs + "\n";
      java.nio.file.Files.write( new File( d, PID + ".txt" ).toPath(),
                                 j.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    } catch( Exception ignore ) { }
  }

  private static void removeInstanceFile() {
    try { new File( instanceDir(), PID + ".txt" ).delete(); } catch( Exception ignore ) { }
  }

  /** 生きている Emulin インスタンス (死んだ pid の残骸は掃除する)。 */
  public static java.util.List<Instance> instances() {
    java.util.List<Instance> out = new java.util.ArrayList<>();
    File[] fs = instanceDir().listFiles();
    if( fs == null ) return out;
    for( File f : fs ) {
      try {
        String n = f.getName();
        if( !n.endsWith( ".txt" ) ) continue;
        long pid = Long.parseLong( n.substring( 0, n.length() - 4 ) );
        if( !ProcessHandle.of( pid ).map( ProcessHandle::isAlive ).orElse( false ) ) { f.delete(); continue; }
        Instance in = new Instance();
        in.pid = pid; in.self = ( pid == PID );
        for( String line : new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                                       java.nio.charset.StandardCharsets.UTF_8 ).split( "\n" ) ) {
          int eq = line.indexOf( '=' );
          if( eq <= 0 ) continue;
          String k = line.substring( 0, eq ), v = line.substring( eq + 1 );
          switch( k ) {
            case "startedAt": try { in.startedAt = Long.parseLong( v ); } catch( Exception ignore ) { } break;
            case "version":   in.version = v; break;
            case "backend":   in.backend = v; break;
            case "rootfs":    in.rootfs  = v; break;
            default: break;
          }
        }
        out.add( in );
      } catch( Exception ignore ) { }
    }
    out.sort( ( a, b ) -> Long.compare( a.pid, b.pid ) );
    return out;
  }

  // ------------------------------------------------------------------
  //  guest プロセス
  // ------------------------------------------------------------------
  public static final class GuestProc {
    public int pid, ppid; public String name = "", cwd = "";
  }

  public static java.util.List<GuestProc> guestProcesses() {
    java.util.List<GuestProc> out = new java.util.ArrayList<>();
    try {
      Kernel k = kernel;
      if( k == null ) return out;
      java.util.Vector<?> t = k.ptable;
      for( int i = 0; i < t.size(); i++ ) {
        Object o = t.elementAt( i );
        if( !( o instanceof ProcessInfo ) ) continue;
        ProcessInfo pi = (ProcessInfo) o;
        Process p = pi.process;
        if( p == null ) continue;                       // 終了済み
        GuestProc g = new GuestProc();
        g.pid = p.pid; g.ppid = pi.ppid;
        g.name = ( p.name == null ? "" : p.name );
        try { g.cwd = p.get_curdir(); } catch( Throwable ignore ) { }
        out.add( g );
      }
    } catch( Throwable ignore ) { }
    return out;
  }

  // ------------------------------------------------------------------
  //  credential — ★ 名前・登録有無・日時だけ。値は絶対に出さない。
  // ------------------------------------------------------------------
  public static final class Cred {
    public String name = "", host = "", savedAt = "";
    public boolean registered;
  }

  public static java.util.List<Cred> credentials() {
    java.util.List<Cred> out = new java.util.ArrayList<>();
    try {
      Egress eg = ( kernel == null ) ? null : kernel.egress;
      CredentialStore cs = ( eg == null ) ? null : eg.creds;
      // ★ ランチャー (LauncherApp) は **Emulin とは別プロセス**なので kernel が無い。
      //   そのときは credential ファイルを直接読む。これが無いと、ランチャーでは
      //   credential が常に「未設定」に見える (実装当初その状態で、Windows で動かして
      //   初めて気付いた。Web 版は Emulin の JVM 内で動いていたので問題にならなかった)。
      //   ★ 読むのは **名前と savedAt だけ**。値は取り出さない。
      if( cs == null ) {
        try {
          java.io.File f = Egress.credentialFile();
          if( f != null && f.isFile() ) {
            CredentialStore tmp = new CredentialStore();
            tmp.discoverFromFile( f );
            cs = tmp;
          }
        } catch( Throwable ignore ) { }
      }
      for( String n : CredentialStore.knownNames() ) {
        Cred c = new Cred();
        c.name = n;
        c.host = String.valueOf( CredentialStore.hostFor( n ) );
        c.registered = ( cs != null && cs.names().contains( n ) );
        String sv = ( c.registered ? cs.savedAtOf( n ) : null );
        c.savedAt = ( sv == null ? "" : sv );
        out.add( c );
      }
    } catch( Throwable ignore ) { }
    return out;
  }

  /** ★ 縮退の兆候 (#907 / #935)。「守っているつもりで守れていない」を見えるようにする。 */
  public static String egressSummary() {
    try {
      Egress eg = ( kernel == null ) ? null : kernel.egress;
      if( eg == null ) return "credential サンドボックス: 無効 (credential 未登録)";
      return "intercept=" + eg.policy.mitmDecisions()
           + "  未解決の :443=" + eg.policy.unlearned443()
           + "  token 遮断=" + TlsMitmProxy.tokenRotateBlocked.get();
    } catch( Throwable t ) { return ""; }
  }

  public static String rootfs()  { return rootfs; }
  public static long   uptime()  { return ( System.currentTimeMillis() - START_MS ) / 1000; }
}
