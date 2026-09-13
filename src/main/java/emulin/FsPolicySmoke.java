package emulin;

import java.io.File;

// --------------------------------------------------------------------
//  FsPolicySmoke — issue #732: host パスの allowlist が**抜けられない**ことを検査する。
//
//  ★ セキュリティ境界の検査で一番大事なのは「許可が効くか」ではなく
//    **「抜けられないか」**。許可の検査だけ緑にしても、抜け道があれば意味が無い
//    (#497 が「安全対策として頼ると偽の安心になる」と書かれてクローズされたのと同じ話)。
//
//  ★ env で駆動する設計なので、**別 JVM で env を与えて**検査する。
//    FsPolicy は static 初期化で env を読むため、同一 JVM 内では切り替えられない。
//
//  終了コード: 0=PASS / 1=FAIL
// --------------------------------------------------------------------
public final class FsPolicySmoke {

  private static int ng = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) ng++;
  }

  public static void main( String[] args ) throws Exception {
    String mode = ( args.length > 0 ) ? args[0] : "parent";
    if( mode.equals( "child" ) ) { child(); return; }

    File tmp = java.nio.file.Files.createTempDirectory( "fspol" ).toFile();
    File rootfs = new File( tmp, "rootfs" );  new File( rootfs, "etc" ).mkdirs();
    File work   = new File( tmp, "work" );    work.mkdirs();
    File secret = new File( tmp, "secret" );  secret.mkdirs();
    File workish= new File( tmp, "work-secret" ); workish.mkdirs();   // 境界一致の罠
    java.nio.file.Files.write( new File( secret, "key.txt" ).toPath(), "S".getBytes() );
    java.nio.file.Files.write( new File( work,   "ok.txt"  ).toPath(), "W".getBytes() );

    // ★ 許可ゾーンの中から**外を指す symlink**。これを踏めるなら allowlist は無意味。
    boolean linked = true;
    try {
      java.nio.file.Files.createSymbolicLink( new File( work, "escape" ).toPath(), secret.toPath() );
    } catch( Exception e ) { linked = false; System.out.println( "  (symlink を作れない: " + e + ")" ); }

    System.out.println( "=== #732 host パス allowlist ===" );
    run( tmp, rootfs, work, secret, workish, linked );

    System.out.println( ng == 0 ? "FsPolicy smoke OK" : "FsPolicy smoke NG=" + ng );
    System.exit( ng == 0 ? 0 : 1 );
  }

  /** 子 JVM: env に応じて mount 表を組んで freeze し、path の判定列を返す。
   *    T_ROOTFS      … rootfs の host パス
   *    T_MOUNT       … "<guest path>=<host path>" (freeze **前**に足す)
   *    T_MOUNT_AFTER … "<guest path>=<host path>" (freeze **後**に載せ替える = 抜け道の試み)
   *    T_PATHS       … 判定したい host パス (';' 区切り) */
  private static void child() {
    Mount m = new Mount();
    m.set_root( System.getenv( "T_ROOTFS" ) );
    String pre = System.getenv( "T_MOUNT" );
    if( pre != null && !pre.isEmpty() ) {
      String[] kv = pre.split( "=", 2 );
      m.add_mountpoint( kv[0], kv[1] );
    }
    FsPolicy.freeze( m );
    // ★ 凍結後に guest が mount(2) で許可名を別の host dir に載せ替える攻撃。
    String post = System.getenv( "T_MOUNT_AFTER" );
    if( post != null && !post.isEmpty() ) {
      String[] kv = post.split( "=", 2 );
      m.remove_mountpoint( kv[0] );
      m.add_mountpoint( kv[0], kv[1] );
    }
    StringBuilder b = new StringBuilder();
    for( String p : System.getenv( "T_PATHS" ).split( ";" ) )
      b.append( FsPolicy.allowed( p ) ? '1' : '0' );
    System.out.println( "R:" + b + ":" + FsPolicy.ENABLED );
  }

  /** 子 JVM を env つきで起こして判定列を得る。 */
  private static String ask( String allow, String rootfs, String mount, String mountAfter,
                             String... paths ) throws Exception {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add( new File( new File( System.getProperty( "java.home" ), "bin" ), "java" ).getPath() );
    cmd.add( "-cp" ); cmd.add( System.getProperty( "java.class.path" ) );
    cmd.add( "emulin.FsPolicySmoke" ); cmd.add( "child" );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.environment().remove( "EMULIN_FS_ALLOW" );
    pb.environment().remove( "EMULIN_FS_DENY" );   // 廃止済み。残っていても効かないこと
    if( allow != null ) pb.environment().put( "EMULIN_FS_ALLOW", allow );
    pb.environment().put( "T_ROOTFS", rootfs );
    pb.environment().put( "T_MOUNT", mount == null ? "" : mount );
    pb.environment().put( "T_MOUNT_AFTER", mountAfter == null ? "" : mountAfter );
    pb.environment().put( "T_PATHS", String.join( ";", paths ) );
    pb.redirectErrorStream( true );
    java.lang.Process p = pb.start();
    String out = new String( p.getInputStream().readAllBytes(), "UTF-8" );
    p.waitFor();
    for( String line : out.split( "\\R" ) ) if( line.startsWith( "R:" ) ) return line.split( ":" )[1];
    return "?" + out;
  }

  private static void run( File tmp, File rootfs, File work, File secret, File workish,
                           boolean linked ) throws Exception {
    String inRoot = new File( rootfs, "etc/passwd" ).getPath();
    String inWork = new File( work, "ok.txt" ).getPath();
    String inSec  = new File( secret, "key.txt" ).getPath();
    String inWorkish = new File( workish, "x.txt" ).getPath();
    String viaLink   = new File( work, "escape/key.txt" ).getPath();
    String dotdot    = new File( work, "../secret/key.txt" ).getPath();

    // (1) 既定 (env 未設定) は無制限 — 既存利用者の挙動を変えない
    String r = ask( null, rootfs.getPath(), null, null, inRoot, inWork, inSec );
    check( "111".equals( r ), "既定は無制限 (env 未設定): " + r );

    // (2) allowlist モード (host 表記で書いた場合)
    r = ask( work.getPath(), rootfs.getPath(), null, null, inRoot, inWork, inSec, inWorkish );
    check( r.length() == 4 && r.charAt(0) == '1', "rootfs 配下は常に許可" );
    check( r.length() == 4 && r.charAt(1) == '1', "許可した path は通る" );
    check( r.length() == 4 && r.charAt(2) == '0', "★ 許可外は塞ぐ" );
    check( r.length() == 4 && r.charAt(3) == '0',
           "★ prefix が境界で一致する (/work が /work-secret に一致しない): " + r );

    // (3) ★ 脱出経路
    if( linked ) {
      r = ask( work.getPath(), rootfs.getPath(), null, null, viaLink );
      check( "0".equals( r ), "★ 許可ゾーン内から外を指す symlink を踏めない: " + r );
    }
    r = ask( work.getPath(), rootfs.getPath(), null, null, dotdot );
    check( "0".equals( r ), "★ .. で外へ出られない: " + r );

    // (4) **guest から見える名前**で書ける (mount 表で host パスへ変換される)。
    //     Windows でも WSL でも同じ文字列を書けるようにするための経路。
    r = ask( "/mnt/x", rootfs.getPath(), "/mnt/x=" + work.getPath(), null, inWork, inSec );
    check( "10".equals( r ), "guest 表記 (/mnt/x) が mount 表で host パスに変換される: " + r );

    // (5) ★ **凍結**: freeze 後に guest が mount(2) で許可名を別の host dir に載せ替えても
    //     allowlist は広がらない。ここを検査時解決にすると guest が自分で穴を開けられる。
    r = ask( "/mnt/x", rootfs.getPath(), "/mnt/x=" + work.getPath(),
             "/mnt/x=" + secret.getPath(), inWork, inSec );
    check( "10".equals( r ),
           "★ 起動後の mount(2) で許可集合が広がらない (凍結): " + r );

    // (6) 廃止した EMULIN_FS_DENY が残っていても、allow 未設定なら無制限のまま
    //     (deny を頼りにしていた設定が「効いているつもり」にならないよう ENABLED で見せる)。
    r = ask( null, rootfs.getPath(), null, null, inSec );
    check( "1".equals( r ), "EMULIN_FS_DENY は廃止 (allow 未設定なら無制限): " + r );
  }
}
