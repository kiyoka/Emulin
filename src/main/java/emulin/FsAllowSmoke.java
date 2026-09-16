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
      // ★ **freeze() を通してから聞く。** 実機の表示は freeze 後に出るので、凍結前の
      //   describe() を見ても本番と別の分岐を見ることになる (実際、最初この 1 行が
      //   無かったせいで「内部表現を出す」不具合を検査が素通しした)。
      FsPolicy.freeze( null );
      String d = FsPolicy.describe();
      System.out.println( "R:" + ( d == null ? "(none)" : d ) );
      // ★ **表示だけでなく判定も返す。** 「読めないと言った」と「実際に閉じている」は別。
      //   args[1] があれば、その host パスに触れてよいかを 1/0 で返す。
      if( args.length > 1 ) System.out.println( "P:" + ( FsPolicy.allowed( args[1] ) ? 1 : 0 ) );
      return;
    }

    File tmp = java.nio.file.Files.createTempDirectory( "fsallow" ).toFile();
    System.setProperty( "user.home", tmp.getAbsolutePath() );

    System.out.println( "=== #1046 ランチャーの host パス allowlist ===" );
    store( tmp );
    reaches( tmp );
    wording( tmp );
    failClosed( tmp );

    System.out.println( ng == 0 ? "FsAllow smoke OK" : "FsAllow smoke NG=" + ng );
    System.exit( ng == 0 ? 0 : 1 );
  }

  // ------------------------------------------------------------------
  private static void store( File tmp ) throws Exception {
    check( FsAllow.read().entries.isEmpty() && !FsAllow.read().unreadable(),
           "既定 (未設定) は空 = 制限なし" );

    File work = new File( tmp, "work" );   work.mkdirs();
    File more = new File( tmp, "more" );   more.mkdirs();

    List<String> two = new ArrayList<>();
    two.add( work.getAbsolutePath() );
    two.add( more.getAbsolutePath() );
    two.add( work.getAbsolutePath() );     // 重複
    FsAllow.save( two );

    List<String> back = FsAllow.read().entries;
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

    // ★ **書いたとおりに出ること。** 内部表現 (canonical 化・Windows の小文字畳み込み・
    //   区切りの '/' 統一) を出すと、選んだフォルダだと読めない (2026-09-13 実機で発覚:
    //   `C:\dev\zenn-content` を選んだのに表示は `c:/dev/zenn-content` だった)。
    //   末尾 '/' と大文字を含む値を書いて、**そのまま**出ることを見る。
    List<String> verbatim = new ArrayList<>();
    String odd = new File( tmp, "Mixed/Case/" ).getPath() + File.separator;
    verbatim.add( odd );
    FsAllow.save( verbatim );
    r = ask( tmp );
    check( r.contains( odd ), "★ 書いたとおりに表示する (内部表現を出さない): " + r );

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
  private static String ask( File home ) throws Exception { return ask( home, null ); }

  /** 同上。probe を渡すと "R:<起動行>" に続けて "P:<0|1>" (その host パスに触れてよいか)
   *  も返る。★ 表示と判定は別物なので、両方を同じ子 JVM から取る。 */
  private static String ask( File home, String probe ) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add( new File( new File( System.getProperty( "java.home" ), "bin" ), "java" ).getPath() );
    cmd.add( "-Duser.home=" + home.getAbsolutePath() );
    cmd.add( "-cp" ); cmd.add( System.getProperty( "java.class.path" ) );
    cmd.add( "emulin.FsAllowSmoke" ); cmd.add( "child" );
    if( probe != null ) cmd.add( probe );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.redirectErrorStream( true );
    java.lang.Process p = pb.start();
    String out = new String( p.getInputStream().readAllBytes(), "UTF-8" );
    p.waitFor();
    StringBuilder r = new StringBuilder();
    for( String line : out.split( "\\R" ) ) {
      if( line.startsWith( "R:" ) ) r.insert( 0, line.substring( 2 ) );
      if( line.startsWith( "P:" ) ) r.append( "  [allowed=" ).append( line.substring( 2 ) ).append( "]" );
    }
    return ( r.length() > 0 ) ? r.toString() : ( "?" + out );
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

  // ------------------------------------------------------------------
  //  ★ **設定ファイルが「在るのに読めない」ときに制限が外れないこと** (fail-closed)。
  //
  //  以前はここが素通りだった: 読み取り例外を握って空リストにしていたので、**設定が
  //  壊れただけで無制限の guest が起き、起動行も 1 行も出なかった**。この機能の前提は
  //  「渡し忘れたら外れる形にしてはいけない」なので、緩む方向の失敗を残してはいけない。
  //
  //  ★ 再現経路は作り話ではない。実機 (Windows) で確認した 2 つをそのまま条項にする:
  //     - cp932 のエディタで保存された日本語パス (UTF-8 として不正)
  //       → **同じファイルに書いてある正常な行まで全部落ちる**
  //     - 設定ファイルの場所が dir
  //
  //  ★ 表示と判定を**両方**見る。「読めないと言った」だけを見ると、言ったのに開いている、
  //    を見逃す。子 JVM から allowed() の結果も取る。
  private static void failClosed( File tmp ) throws Exception {
    File cfg   = FsAllow.configFile();
    File work  = new File( tmp, "work" );
    String out = new File( tmp, "secret" ).getAbsolutePath();   // 許可していない host パス

    // (1) 不正バイト列。正常な行も 1 行入れておく (ファイルごと落ちることを見る)。
    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
    b.write( ( "# test\n" + work.getAbsolutePath() + "\n" ).getBytes( "UTF-8" ) );
    b.write( new byte[]{ (byte)0xBA, (byte)0xEC, (byte)0xB6, (byte)0xC6, '\n' } );  // cp932
    java.nio.file.Files.write( cfg.toPath(), b.toByteArray() );

    String r = ask( tmp, out );
    check( !r.startsWith( "(none)" ), "★ 読めない設定が「制限なし」にならない: " + r );
    check( r.contains( "CANNOT READ" ) && r.contains( cfg.getPath() ),
           "★ 読めないことと直す場所を起動行で言う: " + r );
    check( r.contains( "[allowed=0]" ),
           "★ 実際に閉じている (許可外の host パスに触れない): " + r );

    // (2) 読めない設定を黙って上書きしない。一覧が空に見えるので、ここで 1 件足すと
    //     「残っていた許可を全部捨てて 1 件だけ」になり、利用者は設定を失う。
    boolean refused = false;
    try {
      List<String> one = new ArrayList<>();
      one.add( work.getAbsolutePath() );
      FsAllow.save( one );
    } catch( java.io.IOException e ) {
      refused = String.valueOf( e.getMessage() ).contains( "cannot be read" );
    }
    check( refused, "★ 読めない設定を黙って上書きしない (足すと残りを失う)" );

    // (3) 画面の文面も「制限なし」にならない。
    FsAllow.Config broken = FsAllow.read();
    check( broken.unreadable(), "read() が「在るのに読めない」を返す" );
    String t = FsAllowDialog.notesText( broken );
    check( !t.contains( "NO restriction" ) && t.contains( "cannot be read" ),
           "★ 画面の文面が「制限なし」にならない" );

    // (4) 設定ファイルの場所が dir でも同じ。
    check( cfg.delete(), "壊した設定を消せる" );
    check( cfg.mkdirs(), "設定ファイルの場所に dir を作る" );
    r = ask( tmp, out );
    check( r.contains( "CANNOT READ" ) && r.contains( "[allowed=0]" ),
           "★ 設定ファイルの場所が dir でも閉じる: " + r );
    check( cfg.delete(), "dir を片付ける" );

    // (5) ★ **対照**: 正しい設定に戻せば通常の allow= に戻り、許可外だけが閉じる。
    //     これが無いと「常に CANNOT READ を出しているだけ」と区別できない。
    List<String> ok = new ArrayList<>();
    ok.add( work.getAbsolutePath() );
    FsAllow.save( ok );
    r = ask( tmp, out );
    check( !r.contains( "CANNOT READ" ) && r.contains( work.getAbsolutePath() ),
           "対照: 正しい設定に戻すと通常の表示に戻る: " + r );
    check( r.contains( "[allowed=0]" ), "対照: 正しい設定でも許可外は閉じている" );
    r = ask( tmp, work.getAbsolutePath() );
    check( r.contains( "[allowed=1]" ), "対照: 許可した場所は開いている" );
  }
}
