package emulin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// --------------------------------------------------------------------
//  FsAllowSmoke — issue #1046: ランチャーが保存した「guest に見せる host パス」が
//  **guest 側 (FsPolicy) にそのまま届く**ことを検査する。
//
//  ★ **env は使わない。** ランチャーが env で渡す形は、`Open terminal` が `wt.exe` 経由で
//    別プロセス文脈から起動し直されると落ちて、**そこだけ無制限の guest が起きる**。
//    guest 側が起動時にファイルを読む形にしてあるので、ここでは
//    **別 JVM を -Duser.home で起こして FsPolicy が何を読んだか**を確かめる
//    (FsPolicy は static 初期化で 1 回だけ読むため、同一 JVM では切り替えられない)。
//
//  ★ **見せ方も検査する。** #968 で実機から出た欠陥 4 件はすべて「判定は効いていたが
//    見せ方が無かった」だった。空を「制限なし」と言い切れているかを文字列で見る。
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
    if( args.length > 0 && args[0].equals( "child" ) ) {
      // 子 JVM: FsPolicy が何を読んだかを 1 行で返す。
      String d = FsPolicy.describe();
      System.out.println( "R:" + ( d == null ? "(none)" : d ) );
      return;
    }

    File tmp = java.nio.file.Files.createTempDirectory( "fsallow" ).toFile();
    System.setProperty( "user.home", tmp.getAbsolutePath() );

    System.out.println( "=== #1046 ランチャーの host パス allowlist ===" );
    store( tmp );
    reaches( tmp );
    wording( tmp );

    System.out.println( ng == 0 ? "FsAllow smoke OK" : "FsAllow smoke NG=" + ng );
    System.exit( ng == 0 ? 0 : 1 );
  }

  // ------------------------------------------------------------------
  private static void store( File tmp ) throws Exception {
    check( FsAllow.load().isEmpty(), "既定 (未設定) は空 = 制限なし" );

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

    check( FsAllow.invalid( "" ) && FsAllow.invalid( "   " ), "空の値は弾く" );
    check( FsAllow.invalid( "/a\nb" ), "★ 改行を含む値は弾く (1 行 1 パスが崩れる)" );
    check( !FsAllow.invalid( "/mnt/c/dev" ), "普通のパスは通す" );
    // ★ env をやめたので `;` を含む path も普通に扱える (env 時代は分解がずれた)。
    check( !FsAllow.invalid( "/mnt/c/a;b" ), "';' を含むパスも保存できる (env 依存の制約が消えた)" );

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
  //  ★ ここが本題: **保存した値が guest 側 (FsPolicy) に届く**こと。
  //    env を一切渡さずに届かなければ、この設計は成立していない。
  private static void reaches( File tmp ) throws Exception {
    String want = new File( tmp, "work" ).getAbsolutePath();
    String r = ask( tmp );
    check( r.contains( want ), "★ 保存した値を FsPolicy が読む (env を渡していない): " + r );
    check( r.contains( FsAllow.configFile().getPath() ),
           "★ どこを直せばよいか (設定ファイルの場所) が出る" );

    // 設定を消したら制限も消えること (消し忘れで塞がったままにならない)。
    FsAllow.save( new ArrayList<String>() );
    r = ask( tmp );
    check( r.equals( "(none)" ), "設定を空にすると制限なしに戻る: " + r );

    // 戻す (以降の検査のため)
    List<String> back = new ArrayList<>();
    back.add( want );
    FsAllow.save( back );
  }

  /** 別 JVM を -Duser.home 付きで起こし、FsPolicy が読んだ内容を返す。 */
  private static String ask( File home ) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add( new File( new File( System.getProperty( "java.home" ), "bin" ), "java" ).getPath() );
    cmd.add( "-Duser.home=" + home.getAbsolutePath() );
    cmd.add( "-cp" ); cmd.add( System.getProperty( "java.class.path" ) );
    cmd.add( "emulin.FsAllowSmoke" ); cmd.add( "child" );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.redirectErrorStream( true );
    java.lang.Process p = pb.start();
    String out = new String( p.getInputStream().readAllBytes(), "UTF-8" );
    p.waitFor();
    for( String line : out.split( "\\R" ) ) if( line.startsWith( "R:" ) ) return line.substring( 2 );
    return "?" + out;
  }

  // ------------------------------------------------------------------
  private static void wording( File tmp ) throws Exception {
    List<String> none = new ArrayList<>();
    String t = FsAllowDialog.notesText( none );
    check( t.contains( "NO restriction" ),
           "★ 1 件も無いときに「制限なし」と言い切る" );

    List<String> some = new ArrayList<>();
    some.add( new File( tmp, "work" ).getAbsolutePath() );
    t = FsAllowDialog.notesText( some );
    check( !t.contains( "NO restriction" ) && t.contains( "does not exist" ),
           "制限ありのときは「存在しないものとして扱う」と出す" );
    check( t.contains( "Takes effect for guests started from now on" ),
           "★ 次に起こす guest から効くことを出す (凍結)" );
    check( t.contains( "root filesystem is always allowed" ),
           "rootfs は常に許可であることを出す" );
    check( t.contains( "from a command prompt" ),
           "★ ランチャー以外から起こした guest にも効くことを出す" );

    List<String> exposing = new ArrayList<>();
    exposing.add( FsAllow.configFile().getParentFile().getAbsolutePath() );
    check( FsAllowDialog.notesText( exposing ).contains( "widen its own access" ),
           "★ 設定ファイルを含む許可のとき警告を出す" );
  }
}
