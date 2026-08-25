package emulin;

import java.io.*;
import java.util.*;

// --------------------------------------------------------------------
//  GuestJob — guest の中でコマンドを 1 本走らせる (issue #948 インストーラ UI の土台)
//
//  ★ なぜ「どのユーザーで実行するか」を持つのか:
//    Codex CLI と Claude Code は**手順がほぼ正反対**で、そこが一番間違えやすい。
//
//      Codex CLI    : apt-get / npm -g は **root**、config.toml は **非 root のホーム**、
//                     起動も非 root。root のホームに config を置いても効かない
//      Claude Code  : 公式インストーラは **非 root** (~/.local/bin)。
//                     root で入れると /root/.local/bin に入り、セッションから見えない
//
//    README にはどちらも注記があるが、**人間が読んで守る**前提になっている。
//    ここを UI が肩代わりするのが #948 の価値の中心なので、job に uid を持たせる。
//
//  ★ ログ: 画面には**要約 (末尾 15 行)**、全文はファイル。
//    根拠: #932 の実害は apt install の**末尾 7 行** (`Errors were encountered while
//    processing:` 以下) に出ていた。途中の 500 行は読む価値がない。
// --------------------------------------------------------------------
public final class GuestJob {

  /** 画面に出す末尾の行数。#932 の実害は末尾 7 行に出ていたので 15 行あれば足りる。 */
  public static final int TAIL_LINES = 15;

  public enum State { READY, RUNNING, DONE, FAILED }

  public final String  title;
  public final String  shellCommand;
  public final boolean asRoot;
  public volatile State state = State.READY;
  public volatile int   exitCode = -1;
  public volatile File  logFile;

  private final Deque<String> tail = new ArrayDeque<>();
  /** ★ 判定 (AgentInstall.detect) は**全行**が要る。末尾 15 行から拾うと、
   *  判定項目が増えた瞬間に古い行が押し出されて**黙って「未」に化ける**。
   *  画面表示 (tail) と判定 (full) を分けておく。 */
  private final StringBuilder full = new StringBuilder();

  public synchronized String fullOutput() { return full.toString(); }

  public GuestJob( String title, String shellCommand, boolean asRoot ) {
    this.title = title; this.shellCommand = shellCommand; this.asRoot = asRoot;
  }

  /** 画面に出す末尾 N 行。 */
  public synchronized List<String> tailLines() { return new ArrayList<>( tail ); }

  private boolean inTipBlock = false;

  private synchronized void addTail( String line ) {
    if( full.length() < 1024 * 1024 ) full.append( line ).append( '\n' );   // 暴走防止に上限
    if( isBanner( line ) ) return;      // ★ 画面 (tail) からだけ落とす。判定 (full) には残す
    if( tail.isEmpty() && line.trim().isEmpty() ) return;   // 要約の先頭が空行になるのを避ける
    tail.addLast( line );
    while( tail.size() > TAIL_LINES ) tail.removeFirst();
  }

  /** launcher / JVM / Emulin の**起動バナー**か。
   *
   *  ★ なぜ要るか (実測): わざと失敗させた job の末尾 15 行のうち **13 行が
   *  `[egress] credential ...` 等のバナー**で、肝心の失敗理由 2 行がぎりぎり残るだけだった。
   *  バナーは guest を 1 回起動するたびに必ず 20 行以上出るので、放置すると
   *  **失敗理由が黙って画面から押し出される** (#932 の実害は末尾 7 行に出ていた)。
   *  全文はログファイルに残るので、ここで落として困ることはない。 */
  private boolean isBanner( String line ) {
    String t = line.trim();
    if( inTipBlock ) {                       // tip は複数行ブロック。空行まで捨てる
      if( t.isEmpty() ) inTipBlock = false;
      return true;
    }
    if( t.startsWith( "[emulin tip]" ) ) { inTipBlock = true; return true; }
    return t.startsWith( "[egress] credential " )
        || t.startsWith( "[cred] " )
        || t.startsWith( "[backend=" )
        || t.startsWith( "[mitm]" )
        || t.startsWith( "Picked up JAVA_TOOL_OPTIONS" )
        || t.startsWith( "WARNING: " )
        || t.startsWith( "Emulin ver " )
        || t.startsWith( "(java based EMUlation" );
  }

  // ------------------------------------------------------------------
  //  実行 — ★ ここでも emulin.bat / emulin.sh に委ねる。
  //    guest の起動条件 (JVM オプション・rootfs・-CJ・非 root の HOME 等) は
  //    launcher が持っており、**同じロジックを 2 箇所に書かない** (#919 の教訓)。
  // ------------------------------------------------------------------
  public void run( File home, java.util.function.Consumer<GuestJob> onChange ) {
    state = State.RUNNING;
    if( onChange != null ) onChange.accept( this );
    try {
      logFile = new File( logDir(), "emulin-install-"
          + new java.text.SimpleDateFormat( "yyyyMMdd-HHmmss" ).format( new java.util.Date() ) + ".log" );
      List<String> cmd = launcherCommand( home );
      ProcessBuilder pb = new ProcessBuilder( cmd );
      pb.directory( home );
      pb.redirectErrorStream( true );
      // ★ 非 root で走らせるときは launcher と同じ env を与える
      //   (EMULIN_UID / EMULIN_THEUSER / HOME を揃えないと、導入先が /root になる)。
      if( !asRoot ) {
        String user = guestUser( home );
        if( user != null ) {
          pb.environment().put( "EMULIN_UID", "1000" );
          pb.environment().put( "EMULIN_GID", "1000" );
          pb.environment().put( "EMULIN_THEUSER", user );
        }
      }
      java.lang.Process p = pb.start();
      try ( BufferedReader r = new BufferedReader( new InputStreamReader( p.getInputStream(),
                                   java.nio.charset.StandardCharsets.UTF_8 ) );
            PrintWriter w = new PrintWriter( new OutputStreamWriter( new FileOutputStream( logFile ),
                                   java.nio.charset.StandardCharsets.UTF_8 ) ) ) {
        w.println( "$ " + shellCommand + "   (" + ( asRoot ? "root" : "非 root" ) + ")" );
        String line;
        while( ( line = r.readLine() ) != null ) {
          w.println( line );
          addTail( line );
          if( onChange != null ) onChange.accept( this );
        }
      }
      exitCode = p.waitFor();
      state = ( exitCode == 0 ) ? State.DONE : State.FAILED;
    } catch( Exception e ) {
      addTail( "起動に失敗しました: " + e );
      state = State.FAILED;
    }
    if( onChange != null ) onChange.accept( this );
  }

  private List<String> launcherCommand( File home ) {
    List<String> cmd = new ArrayList<>();
    File bat = new File( home, "emulin.bat" );
    if( bat.isFile() ) { cmd.add( "cmd" ); cmd.add( "/c" ); cmd.add( bat.getAbsolutePath() ); }
    // ★ emulin.sh は **bash script** (配列 JVM_OPTS を使う)。`/bin/sh` で起動すると
    //   Debian 系の dash では `Syntax error: "(" unexpected` で即死する。
    //   Windows は cmd /c emulin.bat なのでこの経路を通らず、**Linux/macOS だけで壊れる**。
    else {
      File bash = new File( "/bin/bash" );
      cmd.add( bash.canExecute() ? "/bin/bash" : "/bin/sh" );
      cmd.add( new File( home, "emulin.sh" ).getAbsolutePath() );
    }
    // ★ `run` は**非対話実行の口** (#948)。通常経路は -CJ (JLine) が付き、
    //   **出力がリダイレクト先に届かない** (実測: -CJ ありでパイプに 0 行)。
    cmd.add( "run" );
    cmd.add( shellCommand );
    return cmd;
  }

  /** rootfs に記録されている非 root ユーザー名 (`/etc/emulin-user`)。 */
  static String guestUser( File home ) {
    try {
      File f = new File( new File( home, "rootfs" ), "etc/emulin-user" );
      if( !f.isFile() ) return null;
      String s = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                             java.nio.charset.StandardCharsets.UTF_8 ).trim();
      return s.isEmpty() ? null : s;
    } catch( Exception e ) { return null; }
  }

  private static File logDir() {
    File d = new File( System.getProperty( "os.name", "" ).toLowerCase().startsWith( "windows" )
                       ? "C:\\temp" : System.getProperty( "java.io.tmpdir", "/tmp" ) );
    if( !d.isDirectory() ) d.mkdirs();
    return d;
  }
}
