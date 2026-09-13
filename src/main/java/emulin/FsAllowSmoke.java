package emulin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// --------------------------------------------------------------------
//  FsAllowSmoke — issue #1046: ランチャーで設定した host パス allowlist (#732) が
//  **guest を起こすすべての経路に渡る**ことを検査する。
//
//  ★ ここが本題。**起動口は 2 系統ある**:
//      - `GuestLaunch.builder(...)`        … X 端末 / sshd / apt 等のジョブ
//      - `LauncherApp.terminalBuilder(...)` … Open terminal (emulin.bat 経由で
//        GuestLaunch を通らない)
//    **1 つでも渡し忘れると、そこだけ無制限の guest が起きます。** #919 (launcher が
//    2 系統あり片方しか検証していなかった) / #985 (同じ値を 2 箇所に書いた) と同じ形。
//
//  ★ 検査は **実際の builder を呼ぶ**。素の `new ProcessBuilder(...)` を組み立てると、
//    本体が元に戻っても緑のまま通る (terminalBuilder のコメントにある通り)。
//
//  ★ 保存先は `user.home` から導出されるので、この検査は **user.home を temp に差し替えて**
//    走る (本物の ~/.emulin を汚さない)。
//
//  終了コード: 0=PASS / 1=FAIL
// --------------------------------------------------------------------
public final class FsAllowSmoke {

  private static int ng = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) ng++;
  }

  public static void main( String[] args ) throws Exception {
    File tmp = java.nio.file.Files.createTempDirectory( "fsallow" ).toFile();
    System.setProperty( "user.home", tmp.getAbsolutePath() );

    System.out.println( "=== #1046 ランチャーの host パス allowlist ===" );
    store( tmp );
    launchPaths( tmp );
    wording( tmp );

    System.out.println( ng == 0 ? "FsAllow smoke OK" : "FsAllow smoke NG=" + ng );
    System.exit( ng == 0 ? 0 : 1 );
  }

  // ------------------------------------------------------------------
  private static void store( File tmp ) throws Exception {
    check( FsAllow.load().isEmpty(), "既定 (未設定) は空 = 制限なし" );
    check( FsAllow.envValue( FsAllow.load() ) == null, "空なら env 値は null" );

    File work = new File( tmp, "work" );   work.mkdirs();
    File more = new File( tmp, "more" );   more.mkdirs();

    List<String> two = new ArrayList<>();
    two.add( work.getAbsolutePath() );
    two.add( more.getAbsolutePath() );
    two.add( work.getAbsolutePath() );     // 重複
    FsAllow.save( two );

    List<String> back = FsAllow.load();
    check( back.size() == 2, "保存して読み直すと重複が畳まれる: " + back.size() );
    check( back.get( 0 ).equals( work.getAbsolutePath() ), "順序が保たれる" );
    check( FsAllow.configFile().isFile(), "設定ファイルが ~/.emulin に作られる" );

    String v = FsAllow.envValue( back );
    check( v != null && v.indexOf( FsAllow.SEP ) > 0, "env 値は ';' 区切り: " + v );
    check( v != null && v.equals( work.getAbsolutePath() + ";" + more.getAbsolutePath() ),
           "env 値の組み立て" );

    // ★ 区切り文字が入った値は保存させない。通すと env の分解がずれて
    //   **書いたつもりのない場所が許可される**。
    check( FsAllow.invalid( "/a;/b" ), "★ ';' を含む値は弾く" );
    check( FsAllow.invalid( "" ) && FsAllow.invalid( "   " ), "空の値は弾く" );
    check( !FsAllow.invalid( "/mnt/c/dev" ), "普通のパスは通す" );

    // ★ 設定ファイル自身が許可範囲に入ると、guest が次回の制限を書き換えられる。
    List<String> exposing = new ArrayList<>();
    exposing.add( FsAllow.configFile().getParentFile().getAbsolutePath() );
    check( FsAllow.selfExposed( exposing ), "★ 設定ファイルを含む許可を検出する" );
    check( !FsAllow.selfExposed( back ), "普通の設定では検出しない" );

    // ★ 境界一致の罠。判定は FsPolicy.covered 1 本に寄せてあるので、ここでも効く。
    File wsec = new File( tmp, "work-secret" ); wsec.mkdirs();
    List<String> onlyWork = new ArrayList<>();
    onlyWork.add( work.getAbsolutePath() );
    check( !FsPolicy.covered( new File( wsec, "x" ).getAbsolutePath(), onlyWork ),
           "★ /work が /work-secret に一致しない (境界一致)" );
  }

  // ------------------------------------------------------------------
  private static void launchPaths( File tmp ) throws Exception {
    File home = new File( tmp, "dist" );
    new File( home, "lib" ).mkdirs();
    new File( home, "rootfs/etc" ).mkdirs();
    new File( home, "lib/emulin-0.0.0-all.jar" ).createNewFile();

    String want = FsAllow.envValue( FsAllow.load() );
    check( want != null, "前提: 設定が入っている" );

    List<String> argv = new ArrayList<>();
    argv.add( "/bin/true" );

    check( has( GuestLaunch.builder( home, argv, false ), want ),
           "★ GuestLaunch.builder に載る" );
    check( has( GuestLaunch.builderNoPool( home, argv, false ), want ),
           "★ GuestLaunch.builderNoPool (apt 等のジョブ) に載る" );
    check( has( GuestLaunch.builderWithPool( home, argv, false, 1024 ), want ),
           "★ GuestLaunch.builderWithPool (sshd) に載る" );
    check( has( XDisplay.builder( home, 0, false ), want ),
           "★ XDisplay.builder (Open X terminal) に載る" );
    check( has( new SshdService( home ).sshdBuilder( 2222 ), want ),
           "★ SshdService.sshdBuilder に載る" );
    // ★ これが 2 系統目。emulin.bat 経由で GuestLaunch を通らない。
    check( has( LauncherApp.terminalBuilder( home, false, "cmd" ), want ),
           "★ LauncherApp.terminalBuilder (Open terminal) に載る" );

    // ★ 設定が空でも host の env を**消さない**。消すと制限が外れる方向に倒れる。
    FsAllow.save( new ArrayList<String>() );
    Map<String,String> env = new HashMap<>();
    env.put( FsAllow.ENV, "/from/host/env" );
    FsAllow.apply( env );
    check( "/from/host/env".equals( env.get( FsAllow.ENV ) ),
           "★ 設定が空のとき host の env を消さない (fail-closed)" );
  }

  // ------------------------------------------------------------------
  //  ★ **見せ方も検査する。** #968 の実機で出た欠陥 4 件はすべて「判定は効いていたが
  //    見せ方が無かった」だった。空を「制限なし」と言い切れているか、host の env が
  //    効いているときにそれを出せているかを、文字列で確かめる。
  private static void wording( File tmp ) throws Exception {
    List<String> none = new ArrayList<>();
    String t = FsAllowDialog.notesText( none, null );
    check( t.contains( "NO restriction" ),
           "★ 1 件も無いときに「制限なし」と言い切る" );

    t = FsAllowDialog.notesText( none, "/from/host/env" );
    check( t.contains( "/from/host/env" ),
           "★ host の env で効いているときはその値を出す" );

    List<String> some = new ArrayList<>();
    some.add( new File( tmp, "work" ).getAbsolutePath() );
    t = FsAllowDialog.notesText( some, null );
    check( !t.contains( "NO restriction" ) && t.contains( "does not exist" ),
           "制限ありのときは「存在しないものとして扱う」と出す" );
    check( t.contains( "Takes effect for guests started from now on" ),
           "★ 次に起こす guest から効くことを出す (凍結)" );
    check( t.contains( "root filesystem is always allowed" ),
           "rootfs は常に許可であることを出す" );

    List<String> exposing = new ArrayList<>();
    exposing.add( FsAllow.configFile().getParentFile().getAbsolutePath() );
    check( FsAllowDialog.notesText( exposing, null ).contains( "widen its own access" ),
           "★ 設定ファイルを含む許可のとき警告を出す" );
  }

  /** builder が組み立てた env に値が入っているか。null (配布物が無い) は FAIL 扱い。 */
  private static boolean has( ProcessBuilder pb, String want ) {
    if( pb == null || want == null ) return false;
    return want.equals( pb.environment().get( FsAllow.ENV ) );
  }
}
