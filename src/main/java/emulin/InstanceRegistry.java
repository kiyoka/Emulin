package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  InstanceRegistry — 稼働中の Emulin インスタンスを host 側に登録する (issue #955)
//
//  ★ 何のためにあるか:
//    **稼働中の rootfs に対してもう 1 つ Emulin を起動すると、先に動いていた側の
//    claude / codex の認証が黙って切れる。**
//
//    `Egress` は起動のたびに guest の `~/.claude/.credentials.json` を placeholder で
//    書き直す (#824: placeholder は起動ごとに作り直されるので、古いファイルを残すと
//    guest が古い placeholder を送り続けて 401 になるため、意図的にそうしている)。
//    placeholder は SecureRandom 由来で**起動ごとに別値**なので、稼働中インスタンスの
//    claude があとから起動した側の placeholder を掴むと、その値は**自分の MITM が
//    知らない**ので置換されずに上流へ届き、401 → claude は credential を捨てる。
//    利用者からは「何もしていないのに Login expired」に見え、原因は画面に何も出ない。
//
//    ★ 「書き直さない」では直らない: そうすると**新しく起動した側**が古い placeholder を
//    掴んで同じ壊れ方をする。根治は placeholder を rootfs 単位で共有すること (#955 案 1)。
//    ここでは**まず気付けるようにする** (#955 案 2)。
//
//  ファイルは `~/.emulin/instances/<pid>.txt`。死んだ pid の残骸は読むときに掃除する。
// --------------------------------------------------------------------
public final class InstanceRegistry {

  private InstanceRegistry() { }

  public static final class Instance {
    public long    pid;
    public long    startedAt;
    public String  version = "", backend = "", rootfs = "";
    /** この Emulin が何のために起きているか ("sshd" / "job" / 不明なら空)。issue #963。 */
    public String  role = "";
    /** role が port を持つとき (sshd) のその port。0 = 無し。 */
    public int     port;
    public boolean self;

    /** 画面や警告に出す 1 語。★ **分からないときは何も名乗らない**。
     *  ここで「shell」等と決め打ちすると、bat から起こした sshd を取り違えて表示する。 */
    public String label() {
      if( role.isEmpty() ) return "";
      return ( port > 0 ) ? role + ":" + port : role;
    }
  }

  /** 役割を子プロセスへ伝える env の名前。★ 名前はここだけで定義する
   *  (書く側 = GuestLaunch.withRole / 読む側 = register が別々に綴ると片方だけ直る)。 */
  static final String ENV_ROLE      = "EMULIN_ROLE";
  static final String ENV_ROLE_PORT = "EMULIN_ROLE_PORT";

  static final long PID      = ProcessHandle.current().pid();
  static final long START_MS = System.currentTimeMillis();

  /** テスト専用: 登録先を差し替える。★ 利用者の `~/.emulin/instances` を触らないため。 */
  static volatile File dirOverride = null;

  static File dir() {
    File o = dirOverride;
    if( o != null ) return o;
    return new File( new File( System.getProperty( "user.home", "." ), ".emulin" ), "instances" );
  }

  /** rootfs のパスを比較可能な形にする。
   *
   *  ★ **canonical にすることが要**。今回の実害は symlink / junction 越しに同じ rootfs を
   *  掴んだ形だった (`/tmp/x/rootfs -> .../work/.../rootfs`)。生の文字列で比べると
   *  **同じ rootfs なのに別物と判定して警告を出し損ねる**。 */
  static String canon( String path ) {
    if( path == null ) return "";
    try { return new File( path ).getCanonicalPath(); }
    catch( Exception e ) { return new File( path ).getAbsolutePath(); }
  }

  private static volatile boolean registered = false;
  /** shutdown hook は 1 回だけ積む。★ registered とは別に持つ — 登録を解除したあと
   *  もう一度登録できるようにしたいが、hook まで二重に積みたくはない。 */
  private static volatile boolean hookAdded = false;

  /** 自分を登録し、終了時に消す。
   *
   *  ★ **冪等**にしてある。呼び元が増えたときに (a) shutdown hook が二重に積まれる
   *  (b) あとの呼び出しが別の値で**上書きしてしまう** のを防ぐ。
   *  実際 #948 のダッシュボードは cwd を渡していて、rootfs を上書きしかけた。 */
  public static synchronized void register( String rootfsPath ) {
    int p = 0;
    try { p = Integer.parseInt( String.valueOf( System.getenv( ENV_ROLE_PORT ) ).trim() ); }
    catch( Exception ignore ) { }
    String r = System.getenv( ENV_ROLE );
    register( rootfsPath, ( r == null ? "" : r.trim() ), p );
  }

  /** @param role 役割 ("sshd" / "job" / 不明なら空)。@param port role が持つ port (0 = 無し)。
   *
   *  ★ env から読む版と分けてあるのは検査のため。JVM 自身の env は後から変えられないので、
   *    値を渡せる口が無いと「台帳に role が載る」ことを試せない。 */
  static synchronized void register( String rootfsPath, String role, int port ) {
    if( registered ) return;
    registered = true;
    try {
      File d = dir();
      if( !d.isDirectory() && !d.mkdirs() ) return;
      String backend = "";
      try { backend = CpuBackend.resolve().displayName(); } catch( Throwable ignore ) { }
      StringBuilder j = new StringBuilder();
      j.append( "pid=" ).append( PID ).append( '\n' )
       .append( "startedAt=" ).append( START_MS ).append( '\n' )
       .append( "version=" ).append( Version.get_version() ).append( '\n' )
       .append( "backend=" ).append( backend ).append( '\n' )
       .append( "rootfs=" ).append( canon( rootfsPath ) ).append( '\n' );
      // ★ issue #963: 「どの Emulin を止めればよいか」を台帳だけで言えるようにする。
      //   pid だけだと、sshd なのか端末なのか判別できない (実機で実際に困った)。
      if( role != null && !role.isEmpty() ) j.append( "role=" ).append( role ).append( '\n' );
      if( port > 0 )                        j.append( "port=" ).append( port ).append( '\n' );
      java.nio.file.Files.write( new File( d, PID + ".txt" ).toPath(),
          j.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
      if( !hookAdded ) {
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook( new Thread( InstanceRegistry::unregister,
                                                          "emulin-instance-cleanup" ) );
      }
    } catch( Exception ignore ) { }
  }

  /** 台帳から自分を消す。
   *
   *  ★ **registered も戻す**。戻さないと「解除したのに二度と登録できない」状態になり、
   *  実際 role/port の検査 (InstanceWarnSmoke) がそれで落ちた。台帳に自分が居ない以上、
   *  次の register は通るのが筋。 */
  static synchronized void unregister() {
    try { new File( dir(), PID + ".txt" ).delete(); } catch( Exception ignore ) { }
    registered = false;
  }

  /** 生きているインスタンス (死んだ pid の残骸は掃除する)。 */
  public static List<Instance> live() {
    List<Instance> out = new ArrayList<>();
    File[] fs = dir().listFiles();
    if( fs == null ) return out;
    for( File f : fs ) {
      try {
        String n = f.getName();
        if( !n.endsWith( ".txt" ) ) continue;
        long pid = Long.parseLong( n.substring( 0, n.length() - 4 ) );
        if( !ProcessHandle.of( pid ).map( ProcessHandle::isAlive ).orElse( false ) ) {
          f.delete();                      // 異常終了で残った残骸
          continue;
        }
        Instance in = new Instance();
        in.pid = pid;
        in.self = ( pid == PID );
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
            case "role":      in.role    = v; break;
            case "port":      try { in.port = Integer.parseInt( v ); } catch( Exception ignore ) { } break;
            default: break;
          }
        }
        out.add( in );
      } catch( Exception ignore ) { }
    }
    out.sort( ( a, b ) -> Long.compare( a.pid, b.pid ) );
    return out;
  }

  /** 同じ rootfs を使っている**自分以外**の生きたインスタンス。 */
  public static List<Instance> othersOnSameRootfs( String rootfsPath ) {
    String want = canon( rootfsPath );
    List<Instance> out = new ArrayList<>();
    if( want.isEmpty() ) return out;
    for( Instance in : live() )
      if( !in.self && want.equals( in.rootfs ) ) out.add( in );
    return out;
  }

  /** 警告の文面。該当が無ければ null。 */
  public static String conflictWarning( List<Instance> others, String rootfsPath ) {
    if( others == null || others.isEmpty() ) return null;
    StringBuilder m = new StringBuilder();
    m.append( "[egress] ★★ 別の Emulin が同じ rootfs を使っています: " )
     .append( canon( rootfsPath ) ).append( '\n' );
    for( Instance in : others ) {
      m.append( "[egress]      pid " ).append( in.pid );
      if( in.startedAt > 0 )
        m.append( " (起動 " )
         .append( new java.text.SimpleDateFormat( "HH:mm:ss" ).format( new java.util.Date( in.startedAt ) ) )
         .append( ")" );
      if( !in.version.isEmpty() ) m.append( "  " ).append( in.version );
      // ★ 役割が分かるなら出す。「どれを止めればよいか」がこの 1 語で決まる (#963)。
      if( !in.label().isEmpty() ) m.append( "  [" ).append( in.label() ).append( "]" );
      m.append( '\n' );
    }
    m.append( "[egress]    そちらで動いている claude / codex は、この起動で" )
     .append( "**認証が切れます** (#955)。\n" )
     .append( "[egress]    guest の credential ファイルは起動ごとに作り直され、" )
     .append( "中の placeholder が毎回変わるためです。\n" )
     .append( "[egress]    → 片方を終了するか、rootfs (zip の展開先) を分けてください。\n" )
     .append( "[egress]    切れてしまった場合は、全部終了してから 1 つだけ起動し直せば" )
     .append( "作り直されます。" );
    return m.toString();
  }
}
