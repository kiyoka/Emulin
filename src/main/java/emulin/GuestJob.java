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


  /** いま画面に描かれている 1 行 (再描画を合成するため保持する)。 */
  private final StringBuilder screen = new StringBuilder();
  private final int[]  screenCol = { 0 };
  private boolean lastWasScreenLine = false;

  private synchronized void addTail( String line ) {
    if( full.length() < 1024 * 1024 ) full.append( line ).append( '\n' );   // 暴走防止に上限
    // ★ 画面用にだけ整える。判定 (full) は**生のまま**残す (OK<n>/NG<n> の照合に使う)。
    // ★ 再描画は「**直前に表示された行**」に重ねる。
    //   単純に「再描画でない行が来たらリセット」にすると、間に挟まる
    //   `ESC[?25l` (カーソル非表示) のような**何も表示しない行**でバッファが消え、
    //   合成できずに文字が欠ける (実測: `Installing Cl ude C de n ive ...`)。
    //   何も表示しない行は画面を変えないので、保持している行も変えない。
    boolean redraw = isRedraw( line );
    String disp;
    if( redraw ) {
      screenCol[0] = 0;                    // 直前の \r\n で桁は 0 に戻っている
      disp = renderOnto( screen, screenCol, line );
    } else {
      StringBuilder tmp = new StringBuilder();
      int[] c = { 0 };
      disp = renderOnto( tmp, c, line );
      if( !disp.isEmpty() ) {
        screen.setLength( 0 );
        screen.append( tmp );
        screenCol[0] = c[0];
      }
    }
    // ★ 順序が要る: 空行の判定を先にすると `[emulin tip]` ブロックが**終端しない**。
    //   tip は空行で終わる複数行ブロックなので、その空行を isBanner に渡す必要がある。
    //   逆にすると以降の出力が全部バナー扱いで消え、**画面に 1 行も出なくなる** (実測)。
    if( isBanner( disp ) ) return;      // ★ 画面 (tail) からだけ落とす
    // ★ 判定用の内部マーカー (OK0 / NG2 …) は画面に出さない。
    //   これは AgentInstall.detect が結果を機械的に読むための印で、利用者向けではない。
    //   実機で「Checking what is already installed... の次に NG2 と出て、エラーが起きたように
    //   見える」と指摘された。判定に使う全文 (full) には残すので、読み取りには影響しない。
    if( PROBE_MARKER.matcher( disp ).matches() ) return;
    if( disp.isEmpty() ) return;        // 制御シーケンスだけの行 (カーソル操作等)
    // ★ 再描画は**行を増やさず置き換える**。同じ画面行を上書きしているだけなので、
    //   足していくと要約 15 行が同じ行の途中経過で埋まる。
    if( redraw && lastWasScreenLine && !tail.isEmpty() ) tail.removeLast();
    tail.addLast( disp );
    lastWasScreenLine = true;
    while( tail.size() > TAIL_LINES ) tail.removeFirst();
  }

  /** 判定用マーカー (`OK0` / `NG12` など、その 1 行だけ)。 */
  private static final java.util.regex.Pattern PROBE_MARKER =
      java.util.regex.Pattern.compile( "(OK|NG)\\d+" );

  private boolean inTipBlock = false;

  /** launcher / JVM / Emulin の**起動バナー**か。
   *
   *  ★ なぜ要るか (実測): わざと失敗させた job の末尾 15 行のうち **13 行が
   *  `[egress] credential ...` 等のバナー**で、肝心の失敗理由 2 行がぎりぎり残るだけだった。
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

  /** 画面に出すために端末制御を解釈する (1 行分の**画面バッファ**を持つ)。
   *
   *  ★ なぜ「取り除く」では足りないか (実測 2026-08-26):
   *  Claude の公式インストーラは `ESC[1A` で 1 行上へ戻り、**変化した桁だけを書き直す**。
   *
   *    ESC[1A ESC[38;5;174m "Installing Cl" ESC[15G "ude C" ESC[21G "de n" ESC[27G "ive build latest..."
   *
   *  制御を消すだけだと `Installing Cl ude C de n ive build latest...` になり、
   *  **文字が欠けて読めない** (14/20/25-26 桁の "a" "o" "at" は前のフレームが描いたもの)。
   *  桁位置に書き込む形で合成すると `Installing Claude Code native build latest...` に戻る。
   *
   *  完全な端末エミュレーションはしない。**1 行**だけ、カーソル桁移動と消去に対応する。 */
  static String renderOnto( StringBuilder screen, int[] col, String s ) {
    if( s == null ) s = "";
    int i = 0, n = s.length();
    while( i < n ) {
      char c = s.charAt( i );
      if( c == 0x1B && i + 1 < n ) {
        char c1 = s.charAt( i + 1 );
        if( c1 == '[' ) {                          // CSI
          int j = i + 2;
          while( j < n && s.charAt( j ) >= 0x30 && s.charAt( j ) <= 0x3F ) j++;
          int ps = i + 2, pe = j;
          while( j < n && s.charAt( j ) >= 0x20 && s.charAt( j ) <= 0x2F ) j++;
          if( j >= n ) { i = n; break; }
          char fin = s.charAt( j );
          // ★ 省略時の既定値は機能ごとに違う。移動 (A/B/C/D/G) は 1、**消去 (K/J) は 0**。
          //   一律 1 にすると `ESC[K` が `ESC[1K` (行頭からカーソルまで消去) になり、
          //   **書いたばかりの行が空白で潰れる** (実測: 44 桁の空白と "." だけが残った)。
          String p = s.substring( ps, pe ).replaceAll( "[^0-9]", "" );
          int num = p.isEmpty() ? ( ( fin == 'K' || fin == 'J' ) ? 0 : 1 ) : 1;
          if( !p.isEmpty() ) { try { num = Integer.parseInt( p ); } catch( Exception ignore ) { num = 1; } }
          switch( fin ) {
            case 'G': col[0] = Math.max( 0, num - 1 ); break;
            case 'C': col[0] += num; break;
            case 'D': col[0] = Math.max( 0, col[0] - num ); break;
            case 'K':                               // 行内消去
              if( num == 2 ) { screen.setLength( 0 ); col[0] = 0; }
              else if( num == 1 ) { for( int k = 0; k < col[0] && k < screen.length(); k++ ) screen.setCharAt( k, ' ' ); }
              else if( col[0] < screen.length() ) screen.setLength( col[0] );
              break;
            default: break;                         // 色 (m) / カーソル上下 等は無視
          }
          i = j + 1;
          continue;
        }
        if( c1 == ']' ) {                           // OSC — BEL か ST まで
          int j = i + 2;
          while( j < n && s.charAt( j ) != 0x07 && s.charAt( j ) != 0x1B ) j++;
          i = ( j < n && s.charAt( j ) == 0x07 ) ? j + 1 : j;
          continue;
        }
        int j = i + 1;                              // ESC + 中間バイト + 終端 (ESC ( B 等)
        while( j < n && s.charAt( j ) >= 0x20 && s.charAt( j ) <= 0x2F ) j++;
        i = ( j < n ) ? j + 1 : n;
        continue;
      }
      if( c == '\r' ) { col[0] = 0; i++; continue; }
      if( c == '\b' ) { col[0] = Math.max( 0, col[0] - 1 ); i++; continue; }
      if( c == '\t' ) { col[0] = ( col[0] / 8 + 1 ) * 8; i++; continue; }
      if( c < 0x20 || c == 0x7F ) { i++; continue; }
      while( screen.length() < col[0] ) screen.append( ' ' );
      if( col[0] < screen.length() ) screen.setCharAt( col[0], c );
      else                           screen.append( c );
      col[0]++;
      i++;
    }
    int e = screen.length();
    while( e > 0 && screen.charAt( e - 1 ) == ' ' ) e--;
    return screen.substring( 0, e );
  }

  /** 1 行だけを整える (状態を持たない版)。 */
  static String sanitizeForDisplay( String s ) {
    return renderOnto( new StringBuilder(), new int[]{ 0 }, s );
  }

  /** その行が「直前の画面行を描き直している」か (`ESC[<n>A` = カーソル上)。 */
  static boolean isRedraw( String s ) {
    return s != null && java.util.regex.Pattern.compile( "\u001B\\[[0-9;]*A" ).matcher( s ).find();
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
      // ★ issue #963: **emulin.bat を経由しない**。cmd.exe / java.exe はコンソールアプリで、
      //   GUI から起動すると必ず黒い窓が出る。javaw で直接起動する (GuestLaunch)。
      //   ★ コマンドは **base64 で運ぶ**。cmd.exe を外しても、**Java の ProcessBuilder 自身が
      //   Windows で埋め込みの `"` を正しくエスケープしない** (実測: 引用符が消えて
      //   不正な TOML が書かれた)。argv に英数字と +/= しか載らない形にすれば起きない。
      //   ★ pool は**外して**走らせる。`apt install` が途中で止まることがあるため
      //   (実運用の指示)。host の env に EMULIN_NATIVE_POOL_MB があっても外れる。
      ProcessBuilder pb = GuestLaunch.builderNoPool( home,
          java.util.Arrays.asList( "/bin/bash", "-c", encodeForLauncher( shellCommand ) ), asRoot );
      if( pb == null ) {
        state = State.FAILED;
        addTail( "distribution not found (lib/emulin-*-all.jar and rootfs): " + home );
        if( onChange != null ) onChange.accept( this );
        return;
      }
      // (env / cwd / UTF-8 の設定は GuestLaunch に集約してある)
      java.lang.Process p = pb.start();
      try ( BufferedReader r = new BufferedReader( new InputStreamReader( p.getInputStream(),
                                   java.nio.charset.StandardCharsets.UTF_8 ) );
            PrintWriter w = new PrintWriter( new OutputStreamWriter( new FileOutputStream( logFile ),
                                   java.nio.charset.StandardCharsets.UTF_8 ) ) ) {
        w.println( "$ " + shellCommand + "   (" + ( asRoot ? "root" : "non-root" ) + ")" );
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
      addTail( "failed to launch: " + e );
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
