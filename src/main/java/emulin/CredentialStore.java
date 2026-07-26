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
  };

  // 別名 (alias): 同じ鍵を別の環境変数名でも読む client がいるので **MITM 先の解決だけ**する。
  //   ★ ユーザに提示する一覧 (knownNames) には出さない。出すと「これは何を設定するもの?」と
  //     迷わせ、本来設定すべき主名の設定まで躊躇させてしまう (実機のフィードバックより)。
  //   GOOGLE_API_KEY は google-genai / gemini-cli が GEMINI_API_KEY の次に見る名前だが、
  //   他の Google Cloud client も読む汎用名なので、こちらから設定を勧めることはしない。
  private static final String[][] NAME_HOST_ALIASES = {
    { "GOOGLE_API_KEY",          "generativelanguage.googleapis.com" },
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
    String ph = envToPlaceholder.get( name );
    if( ph == null ) {
      ph = makePlaceholder( rng, name );
      envToPlaceholder.put( name, ph );
    }
    placeholderToReal.put( ph, real );
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
    if( name.startsWith( "GEMINI_" ) || name.startsWith( "GOOGLE_" ) ) return "AIzaEmph01";
    return "sk-ant-emph01-";     // Anthropic 系 (既定)
  }

  // issue #773 (B): Codex の credential は **形まで模さないと client 側で弾かれる**。
  //   codex は auth.json の JWT を**ローカルで parse する** (3 パート・payload が有効な JSON)。
  //   署名は検証しない (サーバ署名なので当然) ので、形さえ合っていれば「ログイン済み」と認識する。
  //   ★ exp は遠い未来にする: 近いと codex 自身が refresh を試み、guest に実トークンが
  //     書き戻されてしまう (#401 の不変条件が壊れる)。更新は host 側だけで行う。
  private static String makeJwtPlaceholder( SecureRandom rng, String marker ) {
    long exp = System.currentTimeMillis() / 1000L + 10L * 365 * 24 * 3600;   // 10 年後
    byte[] r = new byte[12];
    rng.nextBytes( r );
    StringBuilder id = new StringBuilder( "emph01-" );
    for( byte b : r ) id.append( Character.forDigit( (b >> 4) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
    String head = b64u( "{\"alg\":\"RS256\",\"typ\":\"JWT\"}" );
    String body = b64u( "{\"sub\":\"" + id + "\",\"exp\":" + exp + ",\"emulin\":\"" + marker + "\"}" );
    String sig  = b64u( "emulin-placeholder-signature-" + id );
    return head + "." + body + "." + sig;
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

  private static String makePlaceholder( SecureRandom rng, String name ) {
    // issue #773 (B): Codex は JWT / UUID の形を要求する
    if( name != null && name.startsWith( "CODEX_" ) ) {
      if( name.endsWith( "_ACCOUNT_ID" ) ) return makeUuidPlaceholder( rng );
      return makeJwtPlaceholder( rng, name );
    }
    String prefix = placeholderPrefixFor( name );
    if( prefix.startsWith( "AIza" ) ) {
      // Google API key は "AIza" + 35 文字 (合計 39) の [A-Za-z0-9_-]。長さも形も合わせる。
      final String AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
      StringBuilder sb = new StringBuilder( prefix );
      while( sb.length() < 39 ) sb.append( AL.charAt( rng.nextInt( AL.length() ) ) );
      return sb.toString();
    }
    byte[] r = new byte[20];
    rng.nextBytes( r );
    StringBuilder sb = new StringBuilder( prefix );
    for( byte b : r ) sb.append( Character.forDigit( (b >> 4) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
    return sb.toString();
  }
}
