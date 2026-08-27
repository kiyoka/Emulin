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
    /** どの provider のログインか ("claude" / "codex")。 */
    public String  kind = "";
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
    s.kind  = "claude";
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

  /** issue #968: codex のログイン候補 (`~/.codex/auth.json`) を Windows / WSL 両方から。 */
  public static List<Source> codexSources() { return codexSources( System.currentTimeMillis() ); }

  static List<Source> codexSources( long nowMs ) {
    List<Source> out = new ArrayList<>();
    for( String[] c : SetCred.findCodexLogins() ) out.add( inspectCodex( c[0], new File( c[1] ), nowMs ) );
    return out;
  }

  /** codex の auth.json を見る。★ ここも**中身で**判定する (auth_mode が API キーのことがある)。 */
  static Source inspectCodex( String label, File f, long nowMs ) {
    Source s = new Source();
    s.kind  = "codex";
    s.label = ( label == null ? "" : label );
    s.path  = f.getPath();
    if( !f.isFile() ) { s.reject = "not a file"; return s; }
    Map<String,String> tok = SetCred.readCodexAuth( f );
    if( tok == null ) { s.reject = "cannot parse this file as codex auth.json"; return s; }
    if( tok.get( "access_token" ) == null ) {
      s.reject = "no ChatGPT subscription tokens here (auth_mode=" + tok.get( "auth_mode" ) + ")"
               + " - use the OpenAI API key option instead";
      return s;
    }
    // ★ codex のトークンは JWT なので、**値を出さずに期限だけ**取り出せる。
    s.expiresAtMs = jwtExp( tok.get( "access_token" ) );
    StringBuilder n = new StringBuilder( "ChatGPT subscription" );
    if( s.expiresAtMs <= 0 ) {
      n.append( "  (no expiry in the token)" );
    } else if( s.expiresAtMs > nowMs ) {
      n.append( "  access token valid for " ).append( human( s.expiresAtMs - nowMs ) );
    } else {
      s.expired = true;
      n.append( "  access token expired " ).append( human( nowMs - s.expiresAtMs ) ).append( " ago" );
      n.append( " - Emulin will refresh it on first use" );
    }
    s.note = n.toString();
    return s;
  }

  /** JWT の payload から `exp` を読む (epoch ms)。★ **署名は検証しない**。
   *  ここでやりたいのは期限の表示だけで、認証の判断はしない。読めなければ 0。 */
  static long jwtExp( String jwt ) {
    try {
      if( jwt == null ) return 0;
      String[] parts = jwt.split( "\\." );
      if( parts.length < 2 ) return 0;
      byte[] pay = Base64.getUrlDecoder().decode( parts[1] );
      Object root = MiniJson.parse( new String( pay, java.nio.charset.StandardCharsets.UTF_8 ) );
      if( !( root instanceof Map ) ) return 0;
      Object exp = ((Map<?,?>) root).get( "exp" );
      return ( exp == null ) ? 0 : parseEpoch( String.valueOf( exp ) );
    } catch( Exception e ) { return 0; }
  }

  // ------------------------------------------------------------------
  //  取り込み (host 側だけに保存する)
  //
  //  ★ **UI と CLI が同じここを通る** (#968 の要点)。登録を 2 系統に分けると、
  //    「meta を書くのは片方だけ」「期限を見るのは片方だけ」がそのまま入る。
  //  ★ 返す notes に**値を入れない**。ここは画面にもログにも出る (#401)。
  // ------------------------------------------------------------------
  public static final class Import {
    public boolean ok;
    public int     saved;
    /** そのまま画面／CLI に出せる英語の行。値は含まない。 */
    public final List<String> notes = new ArrayList<>();
    /** 取り込めなかった理由 (ok=false のとき)。 */
    public String  error;
  }

  public static Import importClaudeLogin( File src ) {
    return importClaudeLogin( src, Egress.emulinDir(), Egress.credentialFile(), System.currentTimeMillis() );
  }

  static Import importClaudeLogin( File src, File dir, File cred, long nowMs ) {
    Import r = new Import();
    Source s = inspect( "", src, nowMs );
    if( s.reject != null ) { r.error = s.reject; return r; }
    Map<String,String> tok = SetCred.readClaudeCredentials( src );
    if( tok == null || tok.get( "accessToken" ) == null ) {
      r.error = "does not contain a claudeAiOauth login";      // inspect と二重の保険
      return r;
    }
    r.saved += save( r, dir, cred, "CLAUDE_ACCESS_TOKEN",  tok.get( "accessToken" ) );
    r.saved += save( r, dir, cred, "CLAUDE_REFRESH_TOKEN", tok.get( "refreshToken" ) );
    meta( r, dir, cred, "CLAUDE_SUBSCRIPTION_TYPE", tok.get( "subscriptionType" ) );
    meta( r, dir, cred, "CLAUDE_SCOPES",            tok.get( "scopes" ) );
    meta( r, dir, cred, "CLAUDE_SOURCE",            src.getPath() );
    finish( r, s );
    return r;
  }

  /** ★ **名前や置き場所ではなく中身**で provider を決めて取り込む (#968)。
   *
   *  ファイル選択から呼ぶ入口。`.credentials.json` という名前の別物や、`auth.json` を
   *  claude のつもりで選ぶといった取り違えは実際に起こる (#964 では `.pub` という名前の
   *  秘密鍵に当たった)。どちらとしても読めなければ、**両方の理由を並べて**返す
   *  ("読めません" だけだと、利用者は何を選び直せばよいか分からない)。 */
  public static Import importAny( File src ) {
    return importAny( src, Egress.emulinDir(), Egress.credentialFile(), System.currentTimeMillis() );
  }

  static Import importAny( File src, File dir, File cred, long nowMs ) {
    Import claude = importClaudeLogin( src, dir, cred, nowMs );
    if( claude.ok ) return claude;
    Import codex = importCodexAuth( src, dir, cred, nowMs );
    if( codex.ok ) return codex;
    Import r = new Import();
    r.error = "cannot use this file"
            + "\n  as a Claude login: " + claude.error
            + "\n  as a codex login : " + codex.error;
    return r;
  }

  public static Import importCodexAuth( File src ) {
    return importCodexAuth( src, Egress.emulinDir(), Egress.credentialFile(), System.currentTimeMillis() );
  }

  static Import importCodexAuth( File src, File dir, File cred, long nowMs ) {
    Import r = new Import();
    Source s = inspectCodex( "", src, nowMs );
    if( s.reject != null ) { r.error = s.reject; return r; }
    Map<String,String> tok = SetCred.readCodexAuth( src );
    if( tok == null || tok.get( "access_token" ) == null ) {
      r.error = "no ChatGPT subscription tokens in this file";
      return r;
    }
    r.saved += save( r, dir, cred, "CODEX_ACCESS_TOKEN",  tok.get( "access_token" ) );
    r.saved += save( r, dir, cred, "CODEX_REFRESH_TOKEN", tok.get( "refresh_token" ) );
    r.saved += save( r, dir, cred, "CODEX_ID_TOKEN",      tok.get( "id_token" ) );
    r.saved += save( r, dir, cred, "CODEX_ACCOUNT_ID",    tok.get( "account_id" ) );
    meta( r, dir, cred, "CODEX_SOURCE", src.getPath() );
    finish( r, s );
    return r;
  }

  /** 1 件保存する。★ 失敗しても**黙って 0 件成功にしない** (理由を notes に残す)。 */
  private static int save( Import r, File dir, File cred, String name, String value ) {
    if( value == null || value.isEmpty() ) return 0;
    try { SetCred.saveCredential( dir, cred, name, value ); return 1; }
    catch( Exception e ) { r.notes.add( "could not save " + name + ": " + e ); return 0; }
  }

  private static void meta( Import r, File dir, File cred, String name, String value ) {
    if( value == null || value.isEmpty() ) return;
    try { SetCred.saveMeta( dir, cred, name, value ); }
    catch( Exception e ) { r.notes.add( "could not save " + name + ": " + e ); }
  }

  /** 取り込み後に必ず伝えること。★ ここを省くと #944 の往復がそのまま起きる。 */
  private static void finish( Import r, Source s ) {
    r.ok = r.saved > 0;
    if( !r.ok ) { r.error = "nothing could be saved"; return; }
    r.notes.add( "Saved " + r.saved + " entries, host-side only. The guest only ever gets"
               + " placeholders." );
    if( s.note != null && !s.note.isEmpty() ) r.notes.add( s.note );
    String rn = restartNote();
    r.notes.add( rn != null ? rn
               : "Credentials are read once, at startup: restart Emulin to pick these up." );
  }

  // ------------------------------------------------------------------
  //  貼り付けで登録する (段取り 3)
  //
  //  ★ 検証の順序は CLI と同じ: prefix を見る → 実際に 1 本投げる → 保存。
  //    判定そのもの (prefixMatches / connectivityTest / saveCredential) は SetCred の
  //    ものをそのまま呼ぶ。UI 用に書き直すと、provider が増えたときに片方だけ古くなる。
  // ------------------------------------------------------------------

  /** 貼り付けで登録できる provider。★ ファイルから読む形のもの (Claude のブラウザ認証と
   *  codex) は**貼り付けでは登録できない**ので外す (貼らせても保存できない)。 */
  public static List<SetCred.Provider> pasteProviders() {
    List<SetCred.Provider> out = new ArrayList<>();
    for( SetCred.Provider p : SetCred.SETTABLE )
      if( !p.fromCodexAuthJson && !p.fromClaudeCredentialsJson ) out.add( p );
    return out;
  }

  /** 貼り付ける前の点検。★ **保存はしない**。値も返さない。 */
  public static final class Check {
    /** 期待される prefix で始まっているか。 */
    public boolean prefixOk = true;
    /** 実際に 1 本投げたか。 */
    public boolean verified;
    /** 401/403 で弾かれた = そのトークンは無効。 */
    public boolean rejected;
    /** 画面に出す 1 行 (値は含まない)。 */
    public String  message = "";
    /** 保存に進む前に利用者へ確認を取るべきか。 */
    public boolean needsConfirm() { return !prefixOk || rejected; }
  }

  public static Check checkPasted( SetCred.Provider p, String token, boolean verify ) {
    Check c = new Check();
    if( p == null || token == null || token.isEmpty() ) {
      c.prefixOk = false; c.message = "nothing pasted"; return c;
    }
    c.prefixOk = SetCred.prefixMatches( p.prefix, token );
    StringBuilder m = new StringBuilder();
    if( !c.prefixOk )
      m.append( "does not start with '" ).append( p.prefix.replace( "|", "' or '" ) )
       .append( "' (expected for " ).append( p.label ).append( ")" );
    if( verify && p.probe != null && !p.probe.isEmpty() ) {
      SetCred.Result r = SetCred.connectivityTest( p, token );
      c.verified = true;
      c.rejected = r.invalid;
      if( m.length() > 0 ) m.append( "; " );
      m.append( r.msg );
    }
    c.message = m.toString();
    return c;
  }

  public static Import savePasted( SetCred.Provider p, String token ) {
    return savePasted( p, token, Egress.emulinDir(), Egress.credentialFile() );
  }

  static Import savePasted( SetCred.Provider p, String token, File dir, File cred ) {
    Import r = new Import();
    if( p == null || token == null || token.isEmpty() ) { r.error = "nothing pasted"; return r; }
    r.saved += save( r, dir, cred, p.env, token );
    // ★ 貼り付けも**取り込み元を残す** (#968)。ファイル取り込みだけ記録して貼り付けを
    //   記録しないと、あとで「これはどこから来たのか」が半分しか追えない。
    meta( r, dir, cred, prefixOf( p.env ) + "_SOURCE", "pasted by hand" );
    Source s = new Source();
    s.note = "-> " + p.env + "  (sent to " + CredentialStore.hostFor( p.env ) + ")";
    finish( r, s );
    return r;
  }

  // ------------------------------------------------------------------
  //  削除 (段取り 3)
  // ------------------------------------------------------------------

  /** いま登録されている provider の prefix (CLAUDE / CODEX / GH …)。 */
  public static List<String> registeredProviders() {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for( Entry e : list() ) if( e.registered ) out.add( prefixOf( e.name ) );
    return new ArrayList<>( out );
  }

  public static Import removeProvider( String prefix ) {
    return removeProvider( prefix, Egress.emulinDir(), Egress.credentialFile() );
  }

  /** ★ **provider 単位で消す**。1 件だけ消せるようにしてはいけない。
   *
   *  OAuth は access と refresh の**組**で意味を持つ。片方だけ消すと、guest には
   *  片方の placeholder だけが入り、MITM が解決できない値を上流へ送って 401 になる。
   *  しかも画面には「1 件登録済み」と出るので、**壊れていることが分からない**。
   *  #955 で踏んだ「placeholder は入っているのに MITM が知らない」と同じ形。 */
  static Import removeProvider( String prefix, File dir, File cred ) {
    Import r = new Import();
    if( prefix == null || prefix.isEmpty() ) { r.error = "no provider given"; return r; }
    try {
      Map<String,String[]> m    = SetCred.readCredentials( cred );
      Map<String,String>   meta = SetCred.readMeta( cred );
      String p = prefix + "_";
      int n = 0;
      for( Iterator<String> it = m.keySet().iterator(); it.hasNext(); )
        if( it.next().startsWith( p ) ) { it.remove(); n++; }
      for( Iterator<String> it = meta.keySet().iterator(); it.hasNext(); )
        if( it.next().startsWith( p ) ) it.remove();
      if( n == 0 ) { r.error = "nothing is registered for " + prefix; return r; }
      SetCred.writeCredentialsFile( dir, cred, m, meta );
      r.ok = true;
      r.saved = n;
      r.notes.add( "Removed " + n + " entr" + ( n == 1 ? "y" : "ies" ) + " for " + prefix + "." );
      String rn = restartNote();
      r.notes.add( rn != null ? rn
                 : "Credentials are read once, at startup: restart Emulin for this to take effect." );
    } catch( Exception e ) { r.error = String.valueOf( e ); }
    return r;
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
