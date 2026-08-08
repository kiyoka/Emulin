// ----------------------------------------
//  CredentialStore — issue #401 Phase 1: 実キーの host 側管理 + placeholder 注入
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  #401 invariant: 実 API キーは host 側 (sandbox 外) のみに保持し、guest env には
//   placeholder だけを注入する。MITM (TlsMitmProxy) が wire 上の placeholder を
//   実キーに swap するので、guest (compromise しても) は実キーを得られない。
//
//  discovery: host env `EMULIN_CRED_<NAME>=<realkey>` を走査し、guest env `<NAME>` に
//   placeholder を入れる。例: host `EMULIN_CRED_ANTHROPIC_API_KEY=sk-ant-...`
//   → guest `ANTHROPIC_API_KEY=sk-ant-emph01-<hex>` (placeholder)。
//   併せて host 側 credential ファイル `~/.emulin/credentials` (NAME=value 行) からも
//   読み込む。env に実キーを置きたくない (process listing / shell 履歴に乗る) ユーザ向け。
//   このファイルは Mount 層で guest から遮断される (Windows drive mount 越しの読取防止)。
// ----------------------------------------
package emulin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;

public class CredentialStore {

  public static final String HOST_PREFIX = "EMULIN_CRED_";

  // credential 名 → その credential を送る相手 (MITM 対象 host)。
  //   MITM は「placeholder を実キーに戻す」ためだけに張るので、credential が設定されて
  //   いない相手を横取りする理由が無い。ここから allowlist を作ることで、credential を
  //   1 つも設定していなければ TLS 終端は一切起こらない (既存挙動と完全に同じ) し、
  //   claude.ai の OAuth や statsig のテレメトリも素通しのままになる。
  private static final String[][] NAME_HOSTS = {
    { "ANTHROPIC_API_KEY",       "api.anthropic.com" },
    { "CLAUDE_CODE_OAUTH_TOKEN", "api.anthropic.com" },
    { "OPENAI_API_KEY",          "api.openai.com"    },
    // issue #773: Gemini。credential 名は gemini-cli / google-genai SDK が最初に見る
    //   GEMINI_API_KEY を主にする。
    { "GEMINI_API_KEY",          "generativelanguage.googleapis.com" },
    // issue #773 (B): OpenAI Codex の ChatGPT サブスクリプション認証。
    //   ★ Claude の「長期トークン 1 個」と違い、**JWT 3 種 + account_id** の組で、
    //     しかも短命 (host 側で refresh する)。credential 名は codex の auth.json の
    //     フィールド名に合わせる (guest の auth.json を生成するときに 1:1 で対応させる)。
    //   ★ 1 つの credential が複数ホストへ行くので、行は可変長 (2 列目以降が全て host)。
    { "CODEX_ACCESS_TOKEN",      "api.openai.com", "chatgpt.com" },
    { "CODEX_REFRESH_TOKEN",     "auth.openai.com" },
    { "CODEX_ID_TOKEN",          "api.openai.com", "auth.openai.com", "chatgpt.com" },
    { "CODEX_ACCOUNT_ID",        "api.openai.com", "chatgpt.com" },
    // issue #848: GitHub。gh (API) と git push (HTTPS) の両方を placeholder で通す。
    //   ★ git の HTTPS 認証は **Basic** = トークンが base64 の中に埋まるので、
    //     素朴な文字列置換では一致しない (TlsMitmProxy 側で decode して差し替える)。
    //   uploads.github.com は release asset のアップロード先。
    { "GH_TOKEN",                "api.github.com", "github.com", "uploads.github.com" },
  };

  // 別名 (alias): 同じ鍵を別の環境変数名でも読む client がいるので **MITM 先の解決だけ**する。
  //   ★ ユーザに提示する一覧 (knownNames) には出さない。出すと「これは何を設定するもの?」と
  //     迷わせ、本来設定すべき主名の設定まで躊躇させてしまう (実機のフィードバックより)。
  //   GOOGLE_API_KEY は google-genai / gemini-cli が GEMINI_API_KEY の次に見る名前だが、
  //   他の Google Cloud client も読む汎用名なので、こちらから設定を勧めることはしない。
  private static final String[][] NAME_HOST_ALIASES = {
    { "GOOGLE_API_KEY",          "generativelanguage.googleapis.com" },
    // issue #848: gh は GH_TOKEN → GITHUB_TOKEN の順に見る。actions 系や多くの CI
    //   ツールが読むのは後者なので、設定されていれば同じ扱いにする。
    { "GITHUB_TOKEN",            "api.github.com", "github.com", "uploads.github.com" },
  };

  // 未知の名前は null (= MITM 先が分からない)。呼び側が警告する。
  //   複数ホストを持つ credential は代表 (1 つ目) を返す (表示用)。
  public static String hostFor( String name ) {
    java.util.List<String> hs = hostsFor( name );
    return hs.isEmpty() ? null : hs.get( 0 );
  }

  // issue #773 (B): 1 つの credential が複数ホストへ行くことがある
  //   (Codex の access token は api.openai.com と chatgpt.com の両方で使われる)。
  public static java.util.List<String> hostsFor( String name ) {
    java.util.List<String> out = new java.util.ArrayList<>();
    if( name == null ) return out;
    for( String[] e : NAME_HOSTS )
      if( e[0].equals( name ) ) { for( int i = 1; i < e.length; i++ ) out.add( e[i] ); return out; }
    for( String[] e : NAME_HOST_ALIASES )
      if( e[0].equals( name ) ) { for( int i = 1; i < e.length; i++ ) out.add( e[i] ); return out; }
    return out;
  }

  // 既知の credential 名 (NAME_HOSTS の distinct、登録順)。起動時の保存状況表示に使う。
  public static java.util.List<String> knownNames() {
    java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
    for( String[] e : NAME_HOSTS ) s.add( e[0] );
    return new java.util.ArrayList<>( s );
  }

  // guest env 変数名 → placeholder
  private final Map<String,String> envToPlaceholder = new LinkedHashMap<>();
  // placeholder → 実キー (host 側のみ。guest には絶対渡さない)
  private final Map<String,String> placeholderToReal = new HashMap<>();
  // 変数名 → 登録日時 (ISO 8601、credentials.json の savedAt。env 由来は null)
  private final Map<String,String> savedAt = new LinkedHashMap<>();
  private final SecureRandom       rng = new SecureRandom();

  // host env から `EMULIN_CRED_<NAME>=<realkey>` を auto-discover する。
  public void discoverFromHostEnv() {
    discoverFrom( System.getenv() );
  }

  // テスト/明示注入用。
  public void discoverFrom( Map<String,String> hostEnv ) {
    for( Map.Entry<String,String> e : hostEnv.entrySet() ) {
      String k = e.getKey();
      if( k == null || !k.startsWith( HOST_PREFIX ) ) continue;
      add( k.substring( HOST_PREFIX.length() ), e.getValue() );
    }
  }

  // host 側 credential ファイル (`~/.emulin/credentials.json`) を読み込む (issue #774)。
  //   env と同じく guest env `<NAME>` に placeholder を入れ、実値と登録日時 (savedAt) は
  //   host 側のみ保持する。同名が env にもあれば env が override する
  //   (Egress が file → env の順に呼ぶ)。schema:
  //     { "version":1, "credentials": { "NAME": {"value":"...","savedAt":"ISO8601"} } }
  public void discoverFromFile( File f ) {
    if( f == null || !f.isFile() ) return;
    warnIfGroupOrWorldReadable( f );
    srcFile = f;   // issue #824: rotateReal の保存先
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      Object root = MiniJson.parse( text );
      Object creds = ( root instanceof Map ) ? ((Map<?,?>)root).get( "credentials" ) : null;
      if( !( creds instanceof Map ) ) return;
      for( Map.Entry<?,?> e : ((Map<?,?>)creds).entrySet() ) {
        Object entry = e.getValue();
        if( !( entry instanceof Map ) ) continue;
        Object val = ((Map<?,?>)entry).get( "value" );
        Object sv  = ((Map<?,?>)entry).get( "savedAt" );
        if( val == null ) continue;
        String name = String.valueOf( e.getKey() );
        add( name, String.valueOf( val ) );
        if( sv != null ) savedAt.put( name, String.valueOf( sv ) );
      }
    } catch( Exception e ) {
      System.err.println( "[cred] credential file read failed: " + e );
    }
  }

  // 登録日時 (ISO 8601)。未登録 / env 由来 / 旧データは null。
  public String savedAtOf( String name ) { return savedAt.get( name ); }

  // name→real を 1 件登録し placeholder を割り当てる。同名の再登録は placeholder を
  //   維持したまま real だけ更新する (env が file を override するため)。
  private void add( String name, String real ) {
    if( name == null || name.isEmpty() || real == null || real.isEmpty() ) return;
    // issue #861: placeholder を作る**前**に、実トークンから codex がローカルで読む
    //   非機密の claim (プラン種別など) を拾っておく。
    sniffCodexClaims( name, real );
    String ph = envToPlaceholder.get( name );
    if( ph == null ) {
      ph = makePlaceholder( rng, name, real );
      envToPlaceholder.put( name, ph );
    }
    placeholderToReal.put( ph, real );
    // issue #109: name ↔ placeholder ↔ real は 1:1:1。ずれると
    //   「placeholder は配ったが実キーに解決できない」= wire にそのまま出る。
    assert placeholderToReal.size() == envToPlaceholder.size()
      : Invariant.mark( "name/placeholder/real の対応が 1:1:1",
                        "ph2real=" + placeholderToReal.size()
                        + " env2ph=" + envToPlaceholder.size() );
  }

  // POSIX で group/other 読取可なら警告する (実キー平文なので 0600 推奨)。
  //   Windows (POSIX view 無し) では user profile の ACL に委ねる。
  private static void warnIfGroupOrWorldReadable( File f ) {
    try {
      Set<PosixFilePermission> perms =
        java.nio.file.Files.getPosixFilePermissions( f.toPath() );
      if( perms.contains( PosixFilePermission.GROUP_READ )
          || perms.contains( PosixFilePermission.OTHERS_READ ) ) {
        System.err.println( "[cred] warning: " + f
          + " is group/other readable; chmod 600 recommended (holds real key)" );
      }
    } catch( UnsupportedOperationException ignore ) {
      // Windows 等 POSIX view 無し
    } catch( Exception ignore ) {}
  }

  // guest env (envList) に placeholder のみ追加する。実キーは入れない。
  public void injectPlaceholders( List<String> guestEnv ) {
    for( Map.Entry<String,String> e : envToPlaceholder.entrySet() ) {
      if( isFileOnly( e.getKey() ) ) continue;      // issue #773 (B): env に出してはいけない
      guestEnv.add( e.getKey() + "=" + e.getValue() );
    }
  }

  /** issue #773 (B): **guest env に出してはいけない** credential か。
   *
   *  Codex の credential は `~/.codex/auth.json` (ファイル) 経由でのみ渡す。
   *  ★ `CODEX_ACCESS_TOKEN` は **codex 自身が別用途で読む実在の環境変数** ("agent identity"
   *    トークン) で、しかも auth.json より優先される。placeholder を env に置くと codex が
   *    それを agent identity として解釈し
   *      "Error checking login status: agent identity JWT payload is not valid JSON"
   *    で認証そのものが成立しなくなる (実機で踏んだ)。
   *  MITM の swap は placeholder 文字列の完全一致で行うので、env に出さなくても
   *  wire 上の置換は従来どおり効く。 */
  static boolean isFileOnly( String name ) {
    return name != null && name.startsWith( "CODEX_" );
  }

  // MITM が wire 上の placeholder を実キーに swap する。未知なら null。
  public String resolve( String placeholder ) { return placeholderToReal.get( placeholder ); }

  // issue #824: placeholder → credential 名 (逆引き)。
  //   MITM が「どの credential を含む request だったか」を知り、その応答で返ってきた
  //   新しいトークンを正しい名前に紐づけるために使う。
  public String nameOfPlaceholder( String placeholder ) {
    if( placeholder == null ) return null;
    for( Map.Entry<String,String> e : envToPlaceholder.entrySet() )
      if( placeholder.equals( e.getValue() ) ) return e.getKey();
    return null;
  }

  /** issue #824: トークンのローテーション。
   *
   *  OAuth の refresh が成功すると server は**新しい実トークン**を返す。これをそのまま
   *  guest へ流すと実キーがサンドボックス内に落ちてしまう (#401 の前提が崩れる)。
   *  そこで **placeholder は変えずに、指す先の実キーだけ差し替える**。
   *  guest が既に持っている placeholder はそのまま有効なので、guest 側の設定ファイル
   *  (codex の auth.json 等) を書き換える必要が無い。
   *
   *  @return 差し替えたら true (その credential が未設定なら false) */
  public boolean rotateReal( String name, String newReal ) {
    if( name == null || newReal == null || newReal.isEmpty() ) return false;
    String ph = envToPlaceholder.get( name );
    if( ph == null ) return false;
    if( newReal.equals( placeholderToReal.get( ph ) ) ) return false;   // 変化なし
    placeholderToReal.put( ph, newReal );
    persist( name, newReal );
    return true;
  }

  // 読み込み元の credential ファイル (rotateReal の永続化に使う)。env 由来なら null。
  private File srcFile;

  private void persist( String name, String value ) {
    if( srcFile == null ) return;   // env 由来 = 永続化先が無い (プロセス内だけ更新)
    try {
      SetCred.saveCredential( srcFile.getParentFile(), srcFile, name, value );
    } catch( Exception e ) {
      // ★ 値は絶対に出さない
      System.err.println( "[cred] rotate の保存に失敗: " + name + ": " + e );
    }
  }

  // issue #773 (B): credential 名 → placeholder。guest 側の設定ファイル (codex の auth.json)
  //   を **placeholder だけで**組み立てるのに使う。未設定なら null。
  public String placeholderOf( String name ) { return envToPlaceholder.get( name ); }

  // MITM が request (header/body) を scan する対象の placeholder 集合。
  public Set<String> placeholders() { return Collections.unmodifiableSet( placeholderToReal.keySet() ); }

  public boolean isEmpty() { return placeholderToReal.isEmpty(); }

  // 設定済み credential の名前 (登録順)。
  public Set<String> names() { return Collections.unmodifiableSet( envToPlaceholder.keySet() ); }

  // 設定済み credential から MITM すべき host を導く。credential が無ければ空 = MITM 無し。
  public Set<String> mitmHosts() {
    Set<String> s = new LinkedHashSet<>();
    for( String n : envToPlaceholder.keySet() ) s.addAll( hostsFor( n ) );   // issue #773 (B): 複数ホスト
    return s;
  }

  // host が分からない credential 名 (placeholder が swap されず実 server に届いてしまう)。
  public Set<String> unmappedNames() {
    Set<String> s = new LinkedHashSet<>();
    for( String n : envToPlaceholder.keySet() ) if( hostFor( n ) == null ) s.add( n );
    return s;
  }

  // placeholder: **provider ごとに実キーの「形」を模す** (issue #773)。
  //   swap は placeholder 文字列の完全一致で行うので形は本来自由だが、guest 側の client が
  //   送信前に **key の format を検証する**ことがあるため、そこで弾かれると
  //   サンドボックス越しに一切通信できなくなる:
  //     Anthropic … "sk-ant-" 始まりを見る実装がある
  //     OpenAI    … "sk-" 始まりを見る実装がある
  //     Gemini    … "AIza" 始まり・39 文字の Google API key 形式を見る実装がある
  //   いずれも "emph01" を marker として埋め込むので、漏洩調査時に placeholder と実キーを
  //   目視で区別できる (実キーに emph01 は入らない)。
  private static String placeholderPrefixFor( String name ) {
    if( name == null ) return "sk-ant-emph01-";
    if( name.startsWith( "OPENAI_" ) ) return "sk-emph01-";
    // issue #848: GitHub のトークンは prefix で種別が決まる。実トークンの種別が分からない
    //   文脈 (この関数は name しか見ない) では classic PAT を既定にし、
    //   実トークンがある場合は githubPlaceholder() が種別ごとに上書きする。
    if( name.equals( "GH_TOKEN" ) || name.equals( "GITHUB_TOKEN" ) ) return "ghp_emph01";
    if( name.startsWith( "GEMINI_" ) || name.startsWith( "GOOGLE_" ) ) return "AIzaEmph01";
    return "sk-ant-emph01-";     // Anthropic 系 (既定)
  }

  // issue #773 (B) / #861: Codex の credential は **形まで模さないと client 側で弾かれる**。
  //   codex は auth.json の JWT を**ローカルで parse する** (3 パート・payload が有効な JSON)。
  //   署名は検証しない (サーバ署名なので当然)。
  //   ★ issue #861: 「3 パート + exp が遠い未来」だけでは足りなかった。codex は payload の
  //     **claim を読む**: バイナリに chatgpt_account_id / chatgpt_plan_type /
  //     "https://api.openai.com/auth" の参照がある。これが無いと codex は認証が壊れていると
  //     判断して refresh に走り、"Your access token could not be refreshed" で止まる (実機)。
  //     → 本物と同じ claim 構造を持たせる。値のうち **識別情報は placeholder** にし、
  //       秘密でないプラン種別だけ実トークンから引き写す。
  //   ★ exp は遠い未来にする: 近いと codex 自身が refresh を試み、guest に実トークンが
  //     書き戻されてしまう (#401 の不変条件が壊れる)。更新は host 側だけで行う。
  private String makeJwtPlaceholder( SecureRandom rng, String marker ) {
    long now = System.currentTimeMillis() / 1000L;
    long exp = now + 10L * 365 * 24 * 3600;   // 10 年後
    String id   = "emph01-" + hex( rng, 12 );
    String acct = codexAccountUuid( rng );
    String plan = ( codexPlanType != null ) ? codexPlanType : "pro";
    // 本物の access_token / id_token と同じ形。識別情報は placeholder。
    StringBuilder b = new StringBuilder();
    b.append( "{\"sub\":\"" ).append( id ).append( "\"" )
     .append( ",\"iat\":" ).append( now )
     .append( ",\"exp\":" ).append( exp )
     .append( ",\"iss\":\"https://auth.openai.com\"" )
     .append( ",\"client_id\":\"app_EMULIN_PLACEHOLDER\"" )
     .append( ",\"session_id\":\"" ).append( id ).append( "\"" )
     .append( ",\"emulin\":\"" ).append( marker ).append( "\"" )
     .append( ",\"https://api.openai.com/auth\":{" )
     .append(   "\"chatgpt_account_id\":\"" ).append( acct ).append( "\"" )
     .append(   ",\"chatgpt_plan_type\":\"" ).append( plan ).append( "\"" )
     .append(   ",\"chatgpt_user_id\":\"" ).append( id ).append( "\"" )
     .append(   ",\"user_id\":\"" ).append( id ).append( "\"" )
     .append(   ",\"chatgpt_subscription_active_until\":\"" ).append( farIso( exp ) ).append( "\"" )
     .append( "}" )
     .append( ",\"https://api.openai.com/profile\":{\"email\":\"emulin-placeholder@invalid\","
            + "\"email_verified\":true,\"name\":\"Emulin Placeholder\"}" )
     .append( "}" );
    String head = b64u( "{\"alg\":\"RS256\",\"typ\":\"JWT\"}" );
    String sig  = b64u( "emulin-placeholder-signature-" + id );
    return head + "." + b64u( b.toString() ) + "." + sig;
  }

  /** issue #861: refresh token は **JWT ではない不透明文字列** (本物がそう)。形を合わせる。 */
  private static String makeOpaquePlaceholder( SecureRandom rng ) {
    return "emph01-" + hex( rng, 32 );
  }

  private static String hex( SecureRandom rng, int nbytes ) {
    byte[] r = new byte[nbytes];
    rng.nextBytes( r );
    StringBuilder sb = new StringBuilder();
    for( byte b : r ) sb.append( Character.forDigit( (b >> 4) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
    return sb.toString();
  }

  private static String farIso( long epochSec ) {
    return java.time.format.DateTimeFormatter.ISO_INSTANT.format(
             java.time.Instant.ofEpochSecond( epochSec ) );
  }

  // ---- issue #861: codex がローカルで読む非機密 claim ----
  //   ★ 実トークンの payload は base64 を解けば誰でも読めるもので秘密ではないが、
  //     guest に個人情報 (email / user id) を落とす理由も無い。**プラン種別だけ**引き写し、
  //     残りは placeholder にする。account_id は guest に配る UUID placeholder と揃える
  //     (codex は auth.json の tokens.account_id と JWT の claim の両方を見るため、
  //      食い違うと別の壊れ方をする)。
  private String codexPlanType;      // "pro" / "plus" / "team" 等 (実トークン由来)
  private String codexAcctUuid;      // CODEX_ACCOUNT_ID の placeholder と共有する UUID

  private String codexAccountUuid( SecureRandom rng ) {
    if( codexAcctUuid == null ) codexAcctUuid = makeUuidPlaceholder( rng );
    return codexAcctUuid;
  }

  private void sniffCodexClaims( String name, String real ) {
    if( name == null || !name.startsWith( "CODEX_" ) || real == null ) return;
    if( codexPlanType != null ) return;                 // 一度取れれば十分
    int d1 = real.indexOf( '.' ), d2 = real.lastIndexOf( '.' );
    if( d1 <= 0 || d2 <= d1 ) return;                   // JWT でない (refresh token 等)
    try {
      String json = new String( java.util.Base64.getUrlDecoder().decode( real.substring( d1 + 1, d2 ) ),
                                StandardCharsets.UTF_8 );
      Object root = MiniJson.parse( json );
      if( !( root instanceof java.util.Map ) ) return;
      Object auth = ((java.util.Map<?,?>) root).get( "https://api.openai.com/auth" );
      if( auth instanceof java.util.Map ) {
        Object plan = ((java.util.Map<?,?>) auth).get( "chatgpt_plan_type" );
        if( plan instanceof String && !((String) plan).isEmpty() ) codexPlanType = (String) plan;
      }
    } catch( Throwable ignore ) { }   // 解けなくても placeholder は既定値で作れる
  }

  private static String b64u( String s ) {
    return java.util.Base64.getUrlEncoder().withoutPadding()
             .encodeToString( s.getBytes( StandardCharsets.UTF_8 ) );
  }

  // account_id は秘密ではない (認証できない) が、guest に実値を置く理由も無いので
  //   UUID 形の placeholder にする。wire に出たら MITM が実値へ戻す。
  private static String makeUuidPlaceholder( SecureRandom rng ) {
    byte[] r = new byte[16];
    rng.nextBytes( r );
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < 16; i++ ) {
      if( i == 4 || i == 6 || i == 8 || i == 10 ) sb.append( '-' );
      sb.append( Character.forDigit( (r[i] >> 4) & 0xF, 16 ) ).append( Character.forDigit( r[i] & 0xF, 16 ) );
    }
    return sb.toString();
  }

  // ★ 人間が一目で「これは実キーではない」と分かる語を placeholder の中に入れる。
  //   guest の env や client のログに出たとき、乱数だけだと本物と見分けが付かず
  //   「漏れているのでは」と毎回調べる羽目になる (実機のフィードバックより)。
  //   形 (prefix / 総長 / 文字種) は client 側の format 検証を通すために変えられないので、
  //   変えられるのは中身だけ。ここに READABLE を置き、残りを乱数で埋める。
  private static final String READABLE = "EMULIN-PLACEHOLDER-";
  //   ★ 乱数部は最低これだけ残す。placeholder は **起動ごとに違う**ことが前提で、
  //     「設定ファイルに残った古い placeholder を見分けて書き直す」判定 (#824) の土台になる。
  private static final int    RAND_MIN = 6;

  /** prefix から始まり total 文字ちょうどの placeholder を作る (収まるなら READABLE を挟む)。 */
  private static String fillPlaceholder( SecureRandom rng, String prefix, int total, String alphabet ) {
    return fillPlaceholder( rng, prefix, total, alphabet, READABLE );
  }
  /** issue #848: READABLE を差し替えられる版。
   *  GitHub の PAT は **英数字だけ** ([A-Za-z0-9]) なので、既定の READABLE に含まれる
   *  `-` を入れると実物の形から外れ、client 側の検証で弾かれうる (#861 の JWT と同型の罠)。
   *  そこで区切り無しの marker を渡せるようにする。 */
  private static String fillPlaceholder( SecureRandom rng, String prefix, int total,
                                         String alphabet, String readable ) {
    StringBuilder sb = new StringBuilder( prefix );
    if( prefix.length() + readable.length() + RAND_MIN <= total ) sb.append( readable );
    while( sb.length() < total ) sb.append( alphabet.charAt( rng.nextInt( alphabet.length() ) ) );
    return sb.length() > total ? sb.substring( 0, total ) : sb.toString();
  }

  // issue #848: GitHub のトークンは **種別ごとに prefix も長さも違う**。
  //   classic PAT       "ghp_"        + 36  = 40
  //   OAuth (gh auth login) "gho_"    + 36  = 40
  //   user/server/refresh   "ghu_/ghs_/ghr_" + 36 = 40
  //   fine-grained PAT  "github_pat_" + 82  = 93
  //   ★ 種別を決め打ちすると実トークンと形がずれる。実際 `gh auth login` 済みの実機で
  //     登録されたのは **fine-grained PAT (93 文字)** だったのに placeholder は
  //     classic 形 (40 文字) だった。swap は完全一致なので機能はするが、
  //     #861 (codex の JWT を claim まで見られた) と同型の「client 側の format 検証で
  //     弾かれる」risk をわざわざ残すことになる。
  //   → **実トークンから prefix と総長を引き写す**。中身は placeholder のまま。
  //   文字種は英数字だけにする: classic は [A-Za-z0-9]、fine-grained は [A-Za-z0-9_] なので
  //   英数字は**両方の部分集合**であり、どちらの形式検査も通る。
  private static final String[] GH_PREFIXES =
    { "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_" };

  private static String githubPlaceholder( SecureRandom rng, String real ) {
    final String AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    String kind = null;
    if( real != null ) {
      for( String p : GH_PREFIXES ) { if( real.startsWith( p ) ) { kind = p; break; } }
    }
    // prefix 無し (旧来の 40 桁 hex トークン等) は classic 形を既定にする。
    if( kind == null ) return fillPlaceholder( rng, "ghp_emph01", 40, AL, "EMULINPLACEHOLDER" );
    String prefix = kind + "emph01";
    // 実物と同じ総長にする。ただし marker と乱数が入らないほど短い入力は信用せず既定長へ。
    int total = ( real != null ) ? real.length() : 0;
    if( total < prefix.length() + RAND_MIN ) total = kind.length() + 36;
    return fillPlaceholder( rng, prefix, total, AL, "EMULINPLACEHOLDER" );
  }

  private String makePlaceholder( SecureRandom rng, String name, String real ) {
    // issue #773 (B): Codex は JWT / UUID の形を要求する
    if( name != null && name.startsWith( "CODEX_" ) ) {
      if( name.endsWith( "_ACCOUNT_ID" ) ) return codexAccountUuid( rng );   // issue #861: JWT の claim と同一
      if( name.endsWith( "_REFRESH_TOKEN" ) ) return makeOpaquePlaceholder( rng );  // issue #861: 本物は JWT でない
      return makeJwtPlaceholder( rng, name );
    }
    String prefix = placeholderPrefixFor( name );
    // issue #848: GitHub は種別ごとに形が違うので実トークンから引き写す (上記参照)。
    if( prefix.startsWith( "ghp_" ) ) return githubPlaceholder( rng, real );
    if( prefix.startsWith( "AIza" ) ) {
      // Google API key は "AIza" + 35 文字 (合計 39) の [A-Za-z0-9_-]。長さも形も合わせる。
      final String AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
      return fillPlaceholder( rng, prefix, 39, AL );
    }
    // Anthropic / OpenAI 系: prefix + 40 文字。実キーは大小英数と -/_ を含むので
    //   READABLE を入れても形は崩れない (残りは 16 進で埋める)。
    return fillPlaceholder( rng, prefix, prefix.length() + 40, "0123456789abcdef" );
  }
}
