package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  CredAdmin — credential の登録状況・取り込み元・期限を集める (issue #968)
//
//  ★ **収集と表示を分ける**。表示は Swing (LauncherApp) が、対話は SetCred (CLI) が担当し、
//    ここは値の取得だけを持つ。EmulinStatus (#948) と同じ分け方。
//
//  ★ **登録ロジックを 2 系統にしない** (#968 の要点)。取り込み元の探索と読み取りは
//    SetCred が既に持っているので、ここからそれを呼ぶ。UI 用にコピーを作ると、
//    「片方だけ直っていない」型のバグがそのまま入る (#898 / #903 / #932 で 3 回踏んだ)。
//
//  ★ **値は絶対に出さない** (#401 の不変条件)。ここが返すのは名前・送り先・日時・期限・
//    どこから取り込んだか だけ。先頭数文字も返さない。
//
//  ★ 期限を出す理由: 2026-08-25 に、**10 日前に期限切れになっていたファイルを取り込めて
//    しまい**、それに気付けずに往復した。取り込み元の `expiresAt` は最初から書かれていて、
//    ただ誰も見ていなかった。
// --------------------------------------------------------------------
public final class CredAdmin {

  private CredAdmin() { }

  // ------------------------------------------------------------------
  //  登録済み credential の一覧 (値は持たない)
  // ------------------------------------------------------------------
  public static final class Entry {
    public String  name = "", host = "", savedAt = "";
    public boolean registered;
    /** どこから取り込んだか (meta の `<PREFIX>_SOURCE`)。記録が無ければ空。 */
    public String  origin = "";
    /** 画面に出す 1 行 (英語)。分からなければ空。 */
    public String  note = "";
    /** 利用者の対処が要る見込みなら true (画面で色を変えるため)。 */
    public boolean warn;
  }

  /** OAuth の refresh token のおおよその寿命。★ 正確な値は上流にしか無いので、
   *  「これを過ぎたら再ログインが要るかもしれない」という**目安**としてだけ使う。 */
  static final long REFRESH_LIFETIME_MS = 7L * 24 * 3600 * 1000;

  public static List<Entry> list() { return list( System.currentTimeMillis() ); }

  /** @param nowMs 「いま」。テストから固定時刻を渡せるようにしている。 */
  static List<Entry> list( long nowMs ) {
    List<Entry> out = new ArrayList<>();
    CredentialStore cs = readStore();
    for( EmulinStatus.Cred c : EmulinStatus.credentials() ) {
      Entry e = new Entry();
      e.name = c.name; e.host = c.host; e.savedAt = c.savedAt; e.registered = c.registered;
      if( e.registered && cs != null ) {
        String src = cs.metaOf( prefixOf( c.name ) + "_SOURCE" );
        if( src != null ) e.origin = src;
      }
      describe( e, nowMs );
      out.add( e );
    }
    return out;
  }

  /** 1 件ぶんの「いまどういう状態か」を英語 1 行にする。
   *
   *  ★ access token の期限は**書けない**: guest の refresh は wire 上で回っていて、
   *    新しい期限は store に書き戻らない (#824 の設計)。したがってここで言えるのは
   *    **登録してからどれだけ経ったか**と、refresh token の寿命 (約 1 週間) との関係だけ。
   *    分からないものを「あと N 時間」と書くと、それ自体が誤診の材料になる。 */
  static void describe( Entry e, long nowMs ) {
    if( !e.registered ) return;
    long saved = parseIso( e.savedAt );
    if( saved <= 0 ) return;
    long age = nowMs - saved;
    e.note = "last updated " + human( age ) + " ago";
    if( !isOauth( e.name ) ) return;                 // API キーは期限が無い
    if( age >= REFRESH_LIFETIME_MS ) {
      e.warn = true;
      e.note += " - the refresh token may have expired (they last about a week);"
              + " log in again and re-import if the guest reports 401";
    }
  }

  /** OAuth (回転する) 系か。API キー (期限なし) と分けて扱う。 */
  static boolean isOauth( String name ) {
    return name != null && ( name.endsWith( "_ACCESS_TOKEN" ) || name.endsWith( "_REFRESH_TOKEN" )
                          || name.endsWith( "_ID_TOKEN" ) );
  }

  static String prefixOf( String name ) {
    int us = ( name == null ) ? -1 : name.indexOf( '_' );
    return ( us > 0 ) ? name.substring( 0, us ) : String.valueOf( name );
  }

  // ------------------------------------------------------------------
  //  取り込み元の候補 (host にある Claude のログイン)
  // ------------------------------------------------------------------
  public static final class Source {
    public String  label = "", path = "";
    /** 取り込めない理由 (null なら取り込める)。★ **中身を見て**決める。 */
    public String  reject;
    public String  subscription = "", scopes = "";
    /** ファイルに書かれている access token の期限 (epoch ms)。0 = 不明。 */
    public long    expiresAtMs;
    public boolean expired;
    /** 普段使いの `.claude` = **他のセッションと共有**しているログイン。 */
    public boolean sharedLogin;
    /** 画面に出す 1 行 (英語)。 */
    public String  note = "";
    public boolean warn;
  }

  public static List<Source> claudeSources() {
    return claudeSources( System.getenv( "CLAUDE_CONFIG_DIR" ), System.currentTimeMillis() );
  }

  static List<Source> claudeSources( String cfg, long nowMs ) {
    List<Source> out = new ArrayList<>();
    for( String[] c : SetCred.findClaudeLogins( cfg ) ) out.add( inspect( c[0], new File( c[1] ), nowMs ) );
    return out;
  }

  /** 候補 1 件を読んで、取り込めるか・期限・共有ログインかを決める。
   *
   *  ★ **名前ではなく中身で判定する** (#964 で `.pub` という名前の秘密鍵に当たった)。
   *    `.credentials.json` という名前でも中身が別物のことはある。 */
  static Source inspect( String label, File f, long nowMs ) {
    Source s = new Source();
    s.label = ( label == null ? "" : label );
    s.path  = f.getPath();
    s.sharedLogin = isSharedLogin( f );
    if( !f.isFile() ) { s.reject = "not a file"; return s; }
    Map<String,String> tok = SetCred.readClaudeCredentials( f );
    if( tok == null || tok.get( "accessToken" ) == null ) {
      s.reject = "does not contain a claudeAiOauth login"
               + " (a 'setup-token' does not create this file)";
      return s;
    }
    s.subscription = tok.getOrDefault( "subscriptionType", "" );
    s.scopes       = tok.getOrDefault( "scopes", "" );
    s.expiresAtMs  = parseEpoch( tok.get( "expiresAt" ) );

    StringBuilder n = new StringBuilder();
    if( !s.subscription.isEmpty() ) n.append( s.subscription ).append( "  " );
    if( s.expiresAtMs <= 0 ) {
      n.append( "no expiry recorded" );
    } else if( s.expiresAtMs > nowMs ) {
      n.append( "access token valid for " ).append( human( s.expiresAtMs - nowMs ) );
    } else {
      s.expired = true;
      long since = nowMs - s.expiresAtMs;
      n.append( "access token expired " ).append( human( since ) ).append( " ago" );
      // ★ 期限切れ = 使えない、ではない。Emulin は wire 上で refresh を回すので、
      //   refresh token が生きていれば取り込んで問題ない。**両者を混同して
      //   「使えません」と書くと、正しい取り込み元まで避けさせてしまう**。
      if( since >= REFRESH_LIFETIME_MS ) {
        s.warn = true;
        n.append( " - the refresh token has probably expired too; log in again first" );
      } else {
        n.append( " - Emulin will refresh it on first use" );
      }
    }
    // ★ full scope が無いと Remote Control は動かない (#935)。取り込む前に見せる。
    if( !s.scopes.contains( "user:sessions:claude_code" ) ) {
      s.warn = true;
      n.append( "  [no user:sessions:claude_code - Remote Control will not work]" );
    }
    if( s.sharedLogin ) {
      s.warn = true;
      // ★ #954 / #970 で実際に踏んだ形。refresh token は回転するので、普段使いの
      //   ログインを共有すると**先に refresh した方だけが生き残る**。
      n.append( "  [everyday login: OAuth refresh tokens rotate, so sharing this with"
              + " another Claude Code session logs one of them out - prefer a dedicated"
              + " config dir such as ~/.claude-emulin]" );
    }
    s.note = n.toString();
    return s;
  }

  /** 普段使いの `.claude` から取り込もうとしていないか (親ディレクトリ名で判定)。 */
  static boolean isSharedLogin( File f ) {
    File dir = ( f == null ) ? null : f.getParentFile();
    return dir != null && ".claude".equals( dir.getName() );
  }

  // ------------------------------------------------------------------
  //  稼働中インスタンスへの反映 (#944 で実際に詰まった)
  // ------------------------------------------------------------------
  /** credential を書き換えても反映されない、いま動いている Emulin の数。 */
  public static int runningInstances() {
    try { return InstanceRegistry.live().size(); } catch( Throwable t ) { return 0; }
  }

  /** 稼働中インスタンスがあるときだけ返す注意書き (無ければ null)。
   *
   *  ★ credential は **Emulin の起動時に一度だけ**読まれる。これが見えないせいで
   *    「store は直したのに guest が直らない」と往復した (#944)。 */
  public static String restartNote() {
    int n = runningInstances();
    if( n <= 0 ) return null;
    return "Credentials are read once, at startup: " + n + " running instance"
         + ( n == 1 ? "" : "s" ) + " will keep using the old ones until restarted.";
  }

  // ------------------------------------------------------------------
  //  小物
  // ------------------------------------------------------------------
  /** ★ store をここで読むのは **meta を見るため**だけ (値は取り出さない)。
   *  ランチャーは Emulin とは別プロセスなので kernel が無く、ファイルから読む
   *  (EmulinStatus.credentials() が同じ理由で同じことをしている)。 */
  static CredentialStore readStore() {
    try {
      File f = Egress.credentialFile();
      if( f == null || !f.isFile() ) return null;
      CredentialStore cs = new CredentialStore();
      cs.discoverFromFile( f );
      return cs;
    } catch( Throwable t ) { return null; }
  }

  /** epoch の秒/ミリ秒どちらで書かれていても受ける。0 = 読めない。
   *  ★ 秒とミリ秒を取り違えると「1970 年に期限切れ」や「55000 年まで有効」になり、
   *    画面としては成立してしまうので気付けない。桁で判定する。 */
  static long parseEpoch( String v ) {
    if( v == null ) return 0;
    String t = v.trim();
    int dot = t.indexOf( '.' );
    if( dot > 0 ) t = t.substring( 0, dot );        // 1756... .0 のような書かれ方
    try {
      long n = Long.parseLong( t );
      if( n <= 0 ) return 0;
      return ( n < 100_000_000_000L ) ? n * 1000 : n;   // 10 桁台までは秒とみなす
    } catch( NumberFormatException e ) { return 0; }
  }

  /** ISO8601 (savedAt) を epoch ms に。読めなければ 0。 */
  static long parseIso( String s ) {
    if( s == null || s.isEmpty() ) return 0;
    try { return java.time.Instant.parse( s.trim() ).toEpochMilli(); }
    catch( Exception e ) { }
    try { return java.time.OffsetDateTime.parse( s.trim() ).toInstant().toEpochMilli(); }
    catch( Exception e ) { return 0; }
  }

  /** 期間を人が読める形に (英語)。 */
  static String human( long ms ) {
    long sec = Math.max( 0, ms / 1000 );
    if( sec < 90 )            return sec + " s";
    long min = sec / 60;
    if( min < 90 )            return min + " min";
    long hour = min / 60;
    if( hour < 48 )           return hour + " h";
    long day = hour / 24;
    return day + " day" + ( day == 1 ? "" : "s" );
  }
}
