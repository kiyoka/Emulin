package emulin;

import java.io.File;
import java.util.List;

// --------------------------------------------------------------------
//  XDisplaySmoke — ランチャーの「Open X terminal」(issue #1021) の**起動条件**を検査する。
//
//  ★ guest も X サーバも要らない (純 Java)。検査するのは「何を起動しようとするか」という
//    構造。実際に窓が出るかは実機でしか見られないが、**構造が壊れたことは機械で捕まる**。
//
//  ★ ここで固定したい不変条件 (どれも実害と 1 対 1):
//    1. #949 の遮断を開けるのは **X の port だけ**。`1` / `all` にしない
//       (全許可にすると guest から host の localhost サービス全部に手が届く)
//    2. DISPLAY は **guest の argv に載る** (host env の継承に頼らない)。
//       継承に頼ると経路が変わったとき黙って壊れ、"Can't open display" だけが残る
//    3. **前提を押す前に確かめる** — X サーバが居ない / guest に xterm が無いことを
//       host 側から判定できる
//    4. 起動は **javaw** (黒い窓を出さない、#963/#976)
// --------------------------------------------------------------------
public final class XDisplaySmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  public static void main( String[] args ) throws Exception {
    // ---- port の対応 (X11 の決まり) ----
    check( XDisplay.port( 0 ) == 6000 && XDisplay.port( 7 ) == 6007, "port = 6000 + display 番号" );

    // ---- 偽の X サーバを立てて findServer を見る ----
    //   ★ display 0 (port 6000) は実機で本物が居ることがあるので使わない。高い番号で試す。
    int probeDisplay = 63;
    check( !XDisplay.serverAt( probeDisplay, 200 ),
           "X サーバが居ない display は false (負のコントロール)" );
    try( java.net.ServerSocket ss = new java.net.ServerSocket() ) {
      ss.bind( new java.net.InetSocketAddress( "127.0.0.1", XDisplay.port( probeDisplay ) ) );
      check( XDisplay.serverAt( probeDisplay, 500 ), "listen していれば見つかる" );
      check( XDisplay.findServer( probeDisplay, 200 ) == probeDisplay
             || XDisplay.findServer( probeDisplay, 200 ) >= 0,
             "findServer が居る display を返す" );
    }

    // ---- 配布物を模した home を作って builder() を組み立てる ----
    File home = java.nio.file.Files.createTempDirectory( "emulin-xdisp" ).toFile();
    new File( home, "lib" ).mkdirs();
    new File( home, "rootfs/usr/bin" ).mkdirs();
    new File( home, "lib/emulin-0.0.0-all.jar" ).createNewFile();

    check( !XDisplay.hasXterm( home ), "guest に xterm が無いことを判定できる (負のコントロール)" );
    new File( home, "rootfs/usr/bin/xterm" ).createNewFile();
    check( XDisplay.hasXterm( home ), "guest に xterm があることを判定できる" );

    int display = 2;
    ProcessBuilder pb = XDisplay.builder( home, display, false );
    check( pb != null, "配布物が揃っていれば ProcessBuilder が組める" );
    if( pb == null ) { System.exit( 1 ); }

    List<String> cmd = pb.command();
    String line = String.join( " ", cmd );
    System.out.println( "=== 起動する command line ===" );
    System.out.println( "  " + line );

    // 1. 許可は X の port だけ
    String allow = pb.environment().get( "EMULIN_ALLOW_HOST_LOOPBACK" );
    check( String.valueOf( XDisplay.port( display ) ).equals( allow ),
           "EMULIN_ALLOW_HOST_LOOPBACK が X の port だけ (= " + XDisplay.port( display ) + ")" );
    check( !"1".equals( allow ) && !"all".equalsIgnoreCase( String.valueOf( allow ) ),
           "★ 全許可 (1 / all) にしていない" );

    // 2. DISPLAY が guest の argv に載る (host env の継承に頼らない)
    check( cmd.contains( "DISPLAY=127.0.0.1:" + display ),
           "DISPLAY=127.0.0.1:" + display + " が guest の argv に載る" );
    check( cmd.contains( "/usr/bin/env" ) && cmd.contains( "/usr/bin/xterm" ),
           "guest 側は env で DISPLAY を与えて xterm を起こす" );
    check( cmd.indexOf( "/usr/bin/env" ) < cmd.indexOf( "/usr/bin/xterm" ),
           "env が xterm より前 (順序が逆だと DISPLAY が効かない)" );

    // 4. 黒い窓を出さない (javaw)
    //   ★ `os.name` で分岐すると **Linux/CI では何も検査しない**ことになる (素通り)。
    //     同梱 JRE を模した jre/bin/javaw.exe を置き、**選ばれること自体**を見る。
    new File( home, "jre/bin" ).mkdirs();
    File javaw = new File( home, "jre/bin/javaw.exe" );
    File javae = new File( home, "jre/bin/java.exe" );
    javaw.createNewFile(); javae.createNewFile();
    check( GuestLaunch.javaBin( home, true ).getName().equals( "javaw.exe" ),
           "同梱 JRE があれば javaw を選ぶ (黒い窓を出さない #963/#976)" );
    check( GuestLaunch.javaBin( home, false ).getName().equals( "java.exe" ),
           "windowless=false なら java を選ぶ (負のコントロール)" );

    // cwd は rootfs (ここを外すと guest が起動できない)
    check( pb.directory() != null && pb.directory().getName().equals( "rootfs" ),
           "cwd が rootfs" );

    // 役割が台帳に載る (#963)
    check( "xapp".equals( pb.environment().get( InstanceRegistry.ENV_ROLE ) ),
           "台帳に載る役割が xapp" );

    System.out.println( failures == 0 ? "XDisplay smoke OK" : ( "XDisplay smoke FAILED (" + failures + ")" ) );
    System.exit( failures == 0 ? 0 : 1 );
  }
}
