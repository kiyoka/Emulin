package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  GuestLaunch — ランチャー (#948/#963) から guest を **コンソールを出さずに**起動する
//
//  ★ なぜ emulin.bat を経由しないのか:
//    Windows では `cmd.exe` も `java.exe` も**コンソールアプリ**なので、GUI プロセスから
//    起動すると**必ず新しいコンソール (黒い窓) が割り当てられる**。Java には
//    CREATE_NO_WINDOW に相当する API が無く、`start /b` でも親にコンソールが無ければ
//    新規に作られる。→ 子は **javaw.exe** (GUI サブシステム) で起動する。
//    標準出力・標準エラーは ProcessBuilder のパイプで取れるので表示には影響しない。
//
//  ★ #919 の危険を承知でやっている:
//    launcher (emulin.bat / emulin.sh) が持っていた起動条件を Java 側にも持つことになる。
//    「launcher が 2 系統あり出荷側を検証していなかった」を自分で作らないため、
//      (a) 起動条件は**このクラス 1 箇所**に集める
//      (b) tests/scripts/guest-launch-match.sh で **launcher の値と突き合わせる**
//    ★ 特に cwd。rootfs に cd せずに起動すると
//      `current path is out of virtual path area` で即死する (実測)。
// --------------------------------------------------------------------
public final class GuestLaunch {

  private GuestLaunch() { }

  /** launcher と揃える JVM オプション (build-demo-bundle.sh の JVMOPT)。 */
  public static final String[] JVM_OPTS = { "-Xmx8g", "-XX:-DontCompileHugeMethods" };

  /** launcher と揃える guest 側 env の既定値 (未設定のときだけ入れる)。 */
  public static final String[][] ENV_DEFAULTS = {
      { "HOME",                   "/root" },
      { "EMULIN_INHERIT_ENV",     "1"     },
      { "EMULIN_BACKEND",         "auto"  },
      { "EMULIN_NATIVE_POOL_MB",  "2048"  },
      { "LESSCHARSET",            "utf-8" },
  };

  /** 配布ディレクトリ内の JRE の java 実行ファイル。
   *  @param windowless true なら javaw (コンソールを作らない) を優先する */
  public static File javaBin( File home, boolean windowless ) {
    File bundled = new File( new File( home, "jre" ), "bin" );
    File f = pick( bundled, windowless );
    if( f != null ) return f;
    // 同梱 JRE が無い場合 (開発時) は、いま動いている JVM のものを使う
    f = pick( new File( System.getProperty( "java.home", "." ), "bin" ), windowless );
    return ( f != null ) ? f : new File( "java" );
  }

  private static File pick( File bin, boolean windowless ) {
    String[] names = windowless ? new String[]{ "javaw.exe", "java.exe", "java" }
                                : new String[]{ "java.exe", "java" };
    for( String n : names ) {
      File f = new File( bin, n );
      if( f.isFile() ) return f;
    }
    return null;
  }

  /** 配布ディレクトリ内の emulin jar。見つからなければ null。 */
  public static File jar( File home ) {
    File lib = new File( home, "lib" );
    File[] fs = lib.listFiles( ( d, n ) -> n.startsWith( "emulin-" ) && n.endsWith( "-all.jar" ) );
    if( fs == null || fs.length == 0 ) return null;
    Arrays.sort( fs, Comparator.comparing( File::getName ) );
    return fs[ fs.length - 1 ];        // 版が複数あれば新しい方
  }

  public static File rootfs( File home ) { return new File( home, "rootfs" ); }

  /** guest の中でプログラムを 1 本走らせる ProcessBuilder を作る。
   *
   *  @param argv guest 側の argv (例: { "/bin/bash", "-c", cmd })
   *  @param asRoot false なら非 root ユーザー (uid 1000) で走らせる */
  /** apt install 等、**pool を固定しない**で走らせる (EMULIN_NATIVE_POOL_MB を外す)。
   *
   *  ★ 実運用の指示: `apt install` は途中で止まることがあるため、この変数を**削除して**
   *  起動する。host の env に設定されていても外す (ここが要。putIfAbsent の既定値も
   *  入れない)。 */
  public static ProcessBuilder builderNoPool( File home, List<String> argv, boolean asRoot ) {
    return builder( home, argv, asRoot, null );
  }

  /** pool を明示して走らせる。
   *
   *  ★ 実運用の指示: sshd 経由では claude / codex を動かす可能性が高いので **1024** にする。 */
  public static ProcessBuilder builderWithPool( File home, List<String> argv, boolean asRoot, int mb ) {
    return builder( home, argv, asRoot, Integer.valueOf( mb ) );
  }

  public static ProcessBuilder builder( File home, List<String> argv, boolean asRoot ) {
    return builder( home, argv, asRoot, POOL_DEFAULT );
  }

  /** launcher と同じ既定 (ENV_DEFAULTS の値) を使うことを表す番兵。 */
  private static final Integer POOL_DEFAULT = Integer.valueOf( -1 );

  /**
   *  @param poolMb null = EMULIN_NATIVE_POOL_MB を**外す** / -1 = launcher の既定のまま /
   *                その他 = その MB を設定する
   */
  public static ProcessBuilder builder( File home, List<String> argv, boolean asRoot,
                                        Integer poolMb ) {
    File jar = jar( home );
    File rootfs = rootfs( home );
    if( jar == null || !rootfs.isDirectory() ) return null;
    List<String> cmd = new ArrayList<>();
    cmd.add( javaBin( home, true ).getAbsolutePath() );
    cmd.addAll( Arrays.asList( JVM_OPTS ) );
    cmd.add( "-jar" );
    cmd.add( jar.getAbsolutePath() );
    cmd.add( rootfs.getAbsolutePath() );
    cmd.addAll( argv );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    // ★ cwd は **rootfs**。ここを外すと guest が起動できない (実測)。
    pb.directory( rootfs );
    pb.redirectErrorStream( true );
    // ★ stdin を塞ぐ。ランチャーからの実行は**非対話**で、入力を待てる相手がいない。
    //   塞がないと `emulin-adduser` 等が入力待ちで**黙って固まる** (launcher の bat は
    //   `<nul` を付けている)。
    try {
      File nul = new File( System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT )
                             .contains( "win" ) ? "NUL" : "/dev/null" );
      pb.redirectInput( ProcessBuilder.Redirect.from( nul ) );
    } catch( Exception ignore ) { }
    Map<String,String> env = pb.environment();
    for( String[] kv : ENV_DEFAULTS ) env.putIfAbsent( kv[0], kv[1] );
    // ★ pool の扱いは job ごとに違う (実運用の指示):
    //   - apt install 等は**変数ごと外す** (固定すると途中で止まることがある)
    //   - sshd は claude / codex を動かす前提なので 1024 を明示する
    //   host の env に設定されていても、ここで上書き / 削除するのが要。
    if( poolMb == null )                       env.remove( "EMULIN_NATIVE_POOL_MB" );
    else if( poolMb.intValue() >= 0 )          env.put( "EMULIN_NATIVE_POOL_MB",
                                                        String.valueOf( poolMb.intValue() ) );
    // ★ 子 JVM の出力を UTF-8 に揃える (既定はコンソールの encoding = CP932 等)。
    String jto = env.get( "JAVA_TOOL_OPTIONS" );
    env.put( "JAVA_TOOL_OPTIONS",
             ( jto == null || jto.isEmpty() ? "" : jto + " " )
             + "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" );
    if( !asRoot ) {
      String user = guestUser( home );
      if( user != null ) {
        env.put( "EMULIN_UID", "1000" );
        env.put( "EMULIN_GID", "1000" );
        env.put( "EMULIN_THEUSER", user );
      }
    }
    return pb;
  }

  /** rootfs に記録されている非 root ユーザー名 (`/etc/emulin-user`)。 */
  public static String guestUser( File home ) {
    try {
      File f = new File( rootfs( home ), "etc/emulin-user" );
      if( !f.isFile() ) return null;
      String s = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                             java.nio.charset.StandardCharsets.UTF_8 ).trim();
      return s.isEmpty() ? null : s;
    } catch( Exception e ) { return null; }
  }
}
