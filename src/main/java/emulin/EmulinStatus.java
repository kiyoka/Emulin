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

  /** issue #948: 収集を有効にする。
   *
   *  ★ 台帳 (`~/.emulin/instances/`) の読み書きは **InstanceRegistry に一本化**した (#955)。
   *  ここで別途書くと、あとから呼ばれた方が rootfs を**上書きして**同居検出を黙って壊す
   *  (実際この class は cwd を rootfs として書いていた)。 */
  public static void attach( Kernel k ) {
    kernel = k;
  }

  /** 生きている Emulin インスタンス。収集の実体は InstanceRegistry (#955)。 */
  public static java.util.List<InstanceRegistry.Instance> instances() {
    return InstanceRegistry.live();
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
      if( eg == null ) return "credential sandbox: off (no credential registered)";
      return "intercept=" + eg.policy.mitmDecisions()
           + "  unresolved :443=" + eg.policy.unlearned443()
           + "  token blocked=" + TlsMitmProxy.tokenRotateBlocked.get();
    } catch( Throwable t ) { return ""; }
  }

  /** この プロセスの rootfs (台帳に登録した値)。未登録なら空。 */
  public static String rootfs() {
    for( InstanceRegistry.Instance in : InstanceRegistry.live() )
      if( in.self ) return in.rootfs;
    return "";
  }
  public static long   uptime()  { return ( System.currentTimeMillis() - START_MS ) / 1000; }
}
