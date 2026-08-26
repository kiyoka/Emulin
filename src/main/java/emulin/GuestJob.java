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
    // ★ 画面用にだけ整える。判定 (full) は**生のまま**残す (OK<n>/NG<n> の照合に使う)。
    String disp = sanitizeForDisplay( line );
    if( disp.isEmpty() ) return;        // 制御シーケンスだけの行 (カーソル操作等)
    if( isBanner( disp ) ) return;      // ★ 画面 (tail) からだけ落とす
    tail.addLast( disp );
    while( tail.size() > TAIL_LINES ) tail.removeFirst();
  }

  private static final java.util.regex.Pattern CSI =
      // ★ パラメータ部は `[0-9;?]` では足りない。**私用パラメータ** 0x3C-0x3F
      //   (`<` `=` `>` `?`) があり、実際 `ESC[>4m` `ESC[<u` が取り残されて画面へ漏れた。
      //   規格どおり パラメータ 0x30-0x3F / 中間 0x20-0x2F / 終端 0x40-0x7E で書く。
      java.util.regex.Pattern.compile( "\u001B\\[[\u0030-\u003F]*[\u0020-\u002F]*[\u0040-\u007E]" );
  private static final java.util.regex.Pattern OSC =
      java.util.regex.Pattern.compile( "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" );
  private static final java.util.regex.Pattern ESC1 =
      // ★ `@-_` だけでは足りない。ESC 7 / ESC 8 (カーソル保存・復元) は 0x37/0x38 で
      //   その範囲の外にあり、実際に取り残されて画面へ漏れた。ESC + 印字可能文字を落とす
      //   (CSI と OSC は先に処理済みなので、ここに来るのは 1 文字終端のものだけ)。
      //   中間バイト付きの形 (`ESC ( B` = G0 に ASCII を割当) もある。これも落とす。
      java.util.regex.Pattern.compile( "\u001B[\u0020-\u002F]*[\u0030-\u007E]" );

  /** 画面に出すために端末制御を取り除く。
   *
   *  ★ 実害 (2026-08-26): Claude の公式インストーラは色とカーソル移動を使うので、
   *  進捗行が `[38;5;174mChecking[10Ginstallation[23Gstatus...[39m` と**化けて見えた**。
   *  guest の出力は「端末に描かれる前提」で、そのまま JTextArea に入れると読めない。
   *
   *  ★ カーソル移動 (`[10G` 等) は**空白 1 つに置き換える**。単に消すと
   *  `Checkinginstallationstatus...` と単語が繋がってしまう
   *  (元は「10 桁目へ移動」= 桁揃えのための移動なので、区切りとしては空白が正しい)。 */
  static String sanitizeForDisplay( String s ) {
    if( s == null ) return "";
    StringBuilder out = new StringBuilder();
    java.util.regex.Matcher m = CSI.matcher( s );
    int last = 0;
    while( m.find() ) {
      out.append( s, last, m.start() );
      char fin = s.charAt( m.end() - 1 );
      if( fin == 'G' || fin == 'C' || fin == 'D' || fin == 'H' || fin == 'f' ) out.append( ' ' );
      last = m.end();
    }
    out.append( s.substring( last ) );
    String t = ESC1.matcher( OSC.matcher( out.toString() ).replaceAll( "" ) ).replaceAll( "" );
    StringBuilder b = new StringBuilder( t.length() );
    for( int i = 0; i < t.length(); i++ ) {
      char c = t.charAt( i );
      if( c == '\t' ) { b.append( ' ' ); continue; }
      if( c < 0x20 || c == 0x7f ) continue;          // BEL / CR / BS 等
      b.append( c );
    }
    int e = b.length();
    while( e > 0 && b.charAt( e - 1 ) == ' ' ) e--;   // 末尾の空白だけ落とす (字下げは残す)
    return b.substring( 0, e );
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

  /** ★ guest へ渡すコマンドを **base64 で運ぶ**。
   *
   *  実害 (2026-08-26): `printf 'sandbox_mode = "danger-full-access"\n' > ~/.codex/config.toml`
   *  を投げたら、guest には**二重引用符が消えた**まま届き、不正な TOML が書かれた。
   *  cmd.exe の `set "RUNCMD=%~1"` は**外側の引用符しか外せず**、中に `"` があると
   *  そこで引用が切れて後続が別扱いになる。`>` `&` `|` `%` も同じ危険がある。
   *
   *  ★ 「二重引用符を使わない」という**約束で回避しない**。約束は破られる (実際に破れた)。
   *  command line に英数字と `+/=` しか載らない形にすれば、この種の事故は原理的に起きない。
   *  Windows (cmd/bat) だけで壊れるため、**Linux のテストでは再現しない**のも危ない。 */
  String encodeForLauncher( String cmd ) {
    String b64 = java.util.Base64.getEncoder().encodeToString(
        cmd.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    return "echo " + b64 + " | base64 -d | /bin/bash";
  }

  List<String> launcherCommand( File home ) {
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
    cmd.add( encodeForLauncher( shellCommand ) );
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
