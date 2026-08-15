// ----------------------------------------
//  SetCred — issue #763: credential 設定 CLI ウィザード
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  Pro/Max サブスクリプションユーザ向けに、TLS-MITM (issue #401) の credential ファイル
//   `~/.emulin/credentials.json` を対話でセットアップする。emulin.bat/emulin.sh の `setcred`
//   サブコマンドから起動:
//     保存済み一覧表示 → provider 選択 → その provider 固有の取り方手順 → トークン貼付 →
//     疎通テスト (host 側で api に 1 本投げ 401 か否かで有効性判定・claude 実行不要) → atomic 保存。
//
//  provider は PROVIDERS 表 (保存済み一覧用) と SETTABLE 表 (今 setup できるもの) で定義。
//   MITM 先の host は CredentialStore.NAME_HOSTS が持つ (credential 名 → 送り先)。
//   issue #773: Claude / OpenAI / Gemini の API キーを設定可能にした。疎通テストは
//   provider ごとに host / endpoint / 認証ヘッダが違うので、Provider 表が probe 定義も持つ。
//
//  bundle JRE は java.base + java.logging のみ (Swing=java.desktop / java.net.http 無し) なので、
//   GUI/HttpClient を使わず SSLSocket(javax.net.ssl=java.base) + System.in/out で実装する。
//   実キーは host 側 (~/.emulin) のみに保存され、guest には placeholder だけ渡る (#401 の不変条件)。
//   ※ ユーザ向けメッセージは英語。コメントは日本語。
// ----------------------------------------
package emulin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.*;

public class SetCred {

  // 既定の表示用ホスト。実際の疎通先は Provider.host (issue #773 で provider ごとに分離)。
  static final String API_HOST = "api.anthropic.com";

  // 保存済み一覧に載せる provider。{ env 変数名, ラベル, 補足 }。
  static final String[][] PROVIDERS = {
    // ★ provider ごとにまとめ、各 provider 内は「定額サブスク → 従量 API キー」の順にする
    //   (どちらを選ぶべきか迷わせないため)。
    // issue #935: Claude のサブスクリプション認証は **ブラウザ認証 (OAuth) に一本化**した。
    { "CLAUDE_ACCESS_TOKEN",     "Claude (Pro/Max subscription)",        "" },
    // ★ setup-token は 0.8.3 で**廃止** (ウィザードからは選べない)。既に登録済みの人が
    //   「消えた」と誤解しないよう、保存済み一覧にだけ deprecated として残す。
    { "CLAUDE_CODE_OAUTH_TOKEN", "Claude (setup-token, DEPRECATED)",     "" },
    { "ANTHROPIC_API_KEY",       "Claude (Console API key)",            "" },
    { "CODEX_ACCESS_TOKEN",      "OpenAI Codex (ChatGPT subscription)", "" },
    { "OPENAI_API_KEY",          "OpenAI (API key)",                    "" },
    { "GEMINI_API_KEY",          "Gemini (API key)",                    "" },
    // issue #848: GitHub。SETTABLE に追加したらこちらにも足す。
    //   ★ 片方だけだと「setup できるのに保存済み一覧に出ない」= 登録できたのか
    //     確認できない状態になる (実際そうなっていた)。
    { "GH_TOKEN",                "GitHub (personal access token)",      "" },
  };

  // Anthropic の疎通テスト body (最小の messages リクエスト。max_tokens=1)。
  static final String ANTHROPIC_PROBE_BODY =
      "{\"model\":\"claude-3-5-haiku-20241022\",\"max_tokens\":1,"
    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";

  // 今 setup できる provider。それぞれ固有の「取り方」手順 + 期待 prefix + 疎通テストの
  //   叩き先 (host / method+path / 認証ヘッダ / 追加ヘッダ / body) を持つ (issue #773)。
  //   ★ 疎通テストは「認証が通るか」だけを見る。200 以外でも 401/403 系でなければ
  //     「トークンは有効 (test request の形が違うだけ)」と判定する。
  static final Provider[] SETTABLE = {
    // issue #935: Claude のサブスクリプション認証は **ブラウザ認証 (OAuth) に一本化**。
    //   ★ 旧 `claude setup-token` の長期トークンは 0.8.3 で廃止した。理由は 2 つ:
    //     - **inference 限定**で Remote Control 等が使えない (claude 自身が拒否する)
    //     - 選択肢が 2 つあると「どちらを選ぶべきか」で迷わせる。片方が劣化版なら残す意味が無い
    new Provider( "CLAUDE_ACCESS_TOKEN", "Claude (Pro/Max subscription)", "sk-ant-oat01-",
                  "api.anthropic.com", "", "", new String[]{}, null, new String[]{
      "How to set up (uses your claude.ai Pro/Max subscription, no metered charge):",
      "  1. In another terminal ON THIS HOST, with a DEDICATED config dir:",
      "       CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login",
      "  2. Approve in the browser (claude.ai account)",
      "  3. Come back here; this wizard reads .credentials.json for you",
      "  * Use a DEDICATED config dir. OAuth refresh tokens rotate, so sharing one",
      "    login with another Claude Code session logs that session out.",
      "  * Do NOT run 'claude auth login' inside the guest -- the real token would end",
      "    up inside the sandbox, which is what this feature avoids.",
      "  * This replaces the old 'claude setup-token' flow (removed in 0.8.3): those",
      "    long-lived tokens are inference-only and cannot do Remote Control.",
    } ).claudeCredentialsJson(),
    new Provider( "ANTHROPIC_API_KEY", "Claude (Console API key)", "sk-ant-api03-",
                  "api.anthropic.com", "POST /v1/messages?beta=true", "x-api-key: ",
                  new String[]{ "anthropic-version: 2023-06-01" }, ANTHROPIC_PROBE_BODY, new String[]{
      "How to get a Console API key (pay-per-use; separate from a Pro/Max subscription):",
      "  1. Open  https://platform.claude.com/settings/keys   (Anthropic Console)",
      "  2. Create Key, then copy it (sk-ant-api03-...)",
      "  Note: billed per use, NOT included in a Pro/Max subscription.",
    } ),
    // issue #773 (B): OpenAI Codex の ChatGPT サブスクリプション (定額)。
    //   ★ 従量課金の API キーより**前**に置く: サブスク契約者が API キー (別課金) を
    //     誤って選ばないようにする。
    new Provider( "CODEX_ACCESS_TOKEN", "OpenAI Codex (ChatGPT subscription)", "",
                  "api.openai.com", "", "", new String[]{}, null, new String[]{
      "How to set up (uses your ChatGPT Plus/Pro subscription, no metered charge):",
      "  1. In another terminal ON THIS HOST:  codex login",
      "       (headless / no browser here?  codex login --device-auth  shows a code)",
      "  2. Approve in the browser (ChatGPT account)",
      "  3. Come back here; this wizard reads ~/.codex/auth.json for you",
      "  Note: do NOT run 'codex login' inside the guest -- that would put the real",
      "        token inside the sandbox, which is what this whole feature avoids.",
      "  Note: this is the subscription. The 'OpenAI (API key)' option below is billed per use.",
    } ).codexAuthJson(),
    // issue #773: OpenAI。sk-proj-... (project key) と sk-... (legacy) の両方を受けるため
    //   prefix は "sk-" にする。疎通は GET /v1/models (body 不要・課金されない)。
    new Provider( "OPENAI_API_KEY", "OpenAI (API key)", "sk-",
                  "api.openai.com", "GET /v1/models", "Authorization: Bearer ",
                  new String[]{}, null, new String[]{
      "How to get an OpenAI API key (pay-per-use):",
      "  1. Open  https://platform.openai.com/api-keys",
      "  2. Create new secret key, then copy it (sk-proj-... or sk-...)",
      "  Note: billed per use. A ChatGPT Plus subscription does NOT include API usage.",
      "  Note: Codex CLI can also sign in with a ChatGPT account instead of an API key;",
      "        that OAuth flow is not covered by this wizard yet.",
    } ),
    // issue #773: Gemini。gemini-cli / google-genai SDK は GEMINI_API_KEY を見る。
    //   疎通は GET /v1beta/models。認証ヘッダは x-goog-api-key (Bearer ではない)。
    new Provider( "GEMINI_API_KEY", "Gemini (API key)", "AIza",
                  "generativelanguage.googleapis.com", "GET /v1beta/models", "x-goog-api-key: ",
                  new String[]{}, null, new String[]{
      "How to get a Gemini API key:",
      "  1. Open  https://aistudio.google.com/apikey   (Google AI Studio)",
      "  2. Create API key, then copy it (AIza...)",
      "  Note: a free tier is available; paid tiers are billed per use.",
      "  Note: the same key is also read from GOOGLE_API_KEY by some SDKs.",
    } ),
    // issue #848: GitHub。gh の API (Bearer) と git push (Basic) の両方をこの 1 個で通す。
    //   ★ 疎通は GET /user。token が有効なら 200 + 自分のアカウント JSON が返る。
    //   User-Agent が無いと GitHub は 403 を返すので必ず付ける。
    //   ★ prefix は種別ごとに違うので**候補を全部**受ける。1 つに決め打ちすると、
    //     `gh auth login` 済みホストで普通に使われている fine-grained PAT
    //     (github_pat_...) を貼っただけで偽の警告が出る。
    new Provider( "GH_TOKEN", "GitHub (personal access token)",
                  "ghp_|github_pat_|gho_|ghu_|ghs_|ghr_",
                  "api.github.com", "GET /user", "Authorization: Bearer ",
                  new String[]{ "User-Agent: emulin-setcred" }, null, new String[]{
      "How to get a GitHub token:",
      "  1. Open  https://github.com/settings/tokens",
      "  2. 'Generate new token (classic)' and copy it (ghp_...)",
      "     Scopes: 'repo' for git push / private repos, 'read:org' for org listing.",
      "     (fine-grained tokens 'github_pat_...' also work)",
      "  Note: this single token covers both 'gh' (API) and 'git push' over HTTPS.",
      "  Note: in the guest, run 'gh auth setup-git' once so git uses gh for HTTPS auth.",
    } ),
  };


  static final class Provider {
    final String env, label, prefix; final String[] howto;
    // issue #773 (B): Codex の ChatGPT サブスクだけは「1 個の文字列を貼る」形ではなく、
    //   host の ~/.codex/auth.json から JWT 3 種 + account_id を読み取る。
    //   メニューを provider 順に並べるため、特別扱いせず同じ表に載せて種別で分岐する。
    boolean fromCodexAuthJson = false;
    Provider codexAuthJson() { this.fromCodexAuthJson = true; return this; }
    // issue #935: Claude のブラウザ認証は `~/.claude/.credentials.json` を読む。
    boolean fromClaudeCredentialsJson = false;
    Provider claudeCredentialsJson() { this.fromClaudeCredentialsJson = true; return this; }
    // issue #773: 疎通テストの叩き先。provider ごとに host も endpoint も認証ヘッダも違う。
    final String   host;          // MITM 先と同じホスト (CredentialStore.NAME_HOSTS と一致させる)
    final String   probe;         // "GET /v1/models" のような method + path
    final String   authHeader;    // "Authorization: Bearer " / "x-api-key: " / "x-goog-api-key: "
    final String[] extraHeaders;  // provider 固有の必須ヘッダ (anthropic-version 等)
    final String   body;          // null なら body 無し (GET)
    Provider( String env, String label, String prefix,
              String host, String probe, String authHeader, String[] extraHeaders, String body,
              String[] howto ) {
      this.env = env; this.label = label; this.prefix = prefix;
      this.host = host; this.probe = probe; this.authHeader = authHeader;
      this.extraHeaders = extraHeaders; this.body = body; this.howto = howto;
    }
  }

  public static void main( String[] args ) {
    BufferedReader in = new BufferedReader( new InputStreamReader( System.in, StandardCharsets.UTF_8 ) );
    PrintStream o = System.out;
    try {
      File dir  = new File( System.getProperty( "user.home", "." ), ".emulin" );
      File cred = new File( dir, "credentials.json" );

      o.println();
      o.println( "==== Emulin credential setup (issue #401 network sandbox) ====" );
      o.println();
      o.println( "Your real API token is stored host-side only (" + cred.getPath() + ")." );
      o.println( "The guest (emulin) receives a placeholder only and cannot read the real token." );
      o.println();

      // 保存済み一覧 (登録日時つき、issue #774)。
      Map<String,String[]> existing = readCredentials( cred );
      o.println( "Currently saved credentials:" );
      for( String[] p : PROVIDERS ) {
        String[] v = existing.get( p[0] );
        boolean saved = ( v != null && v[0] != null && !v[0].isEmpty() );
        String mark   = saved ? "[x]" : "[ ]";
        String status = saved
            ? ( "saved (" + prefix( v[0] ) + "...)" + ( v[1] != null ? " on " + v[1] : "" ) )
            : "not set";
        o.println( String.format( "  %s %-28s %-26s %s %s", mark, p[1], p[0], status, p[2] ) );
      }
      o.println();

      // provider 選択メニュー。
      o.println( "Which credential do you want to set up?" );
      for( int i = 0; i < SETTABLE.length; i++ )
        o.println( "  [" + ( i + 1 ) + "] " + SETTABLE[i].label
                   + ( SETTABLE[i].fromCodexAuthJson ? "  -- reads ~/.codex/auth.json" : "" )
                   + ( SETTABLE[i].fromClaudeCredentialsJson ? "  -- reads ~/.claude/.credentials.json" : "" ) );
      o.print( "Choose [1-" + SETTABLE.length + ", empty to cancel]: " );
      o.flush();
      String c = in.readLine();
      if( c == null || c.trim().isEmpty() ) { o.println( "Cancelled." ); return; }
      int idx = -1;
      try { idx = Integer.parseInt( c.trim() ) - 1; } catch( Exception ignore ) {}
      if( idx < 0 || idx >= SETTABLE.length ) { o.println( "Invalid choice. Cancelled." ); return; }
      Provider sel = SETTABLE[idx];
      if( sel.fromCodexAuthJson ) { setupCodexSubscription( in, o, dir, cred ); return; }
      if( sel.fromClaudeCredentialsJson ) { setupClaudeBrowserLogin( in, o, dir, cred ); return; }

      // 選択した provider 固有の取り方手順。
      o.println();
      o.println( "--- " + sel.label + " ---" );
      for( String line : sel.howto ) o.println( line );
      o.println();
      o.print( "Paste the token and press Enter (empty to cancel): " );
      o.flush();
      String token = in.readLine();
      if( token == null || token.trim().isEmpty() ) { o.println( "Cancelled." ); return; }
      token = token.trim();

      // prefix 検証 (選択 provider の期待 prefix と違えば警告)。
      //   issue #848: prefix は '|' 区切りで複数書ける (GitHub は種別ごとに違う)。
      if( !prefixMatches( sel.prefix, token ) ) {
        o.println( "Warning: token does not start with '"
                   + sel.prefix.replace( "|", "' or '" ) + "' (expected for " + sel.label + ")." );
        o.print( "Save it to " + sel.env + " anyway? [y/N]: " );
        o.flush();
        String a = in.readLine();
        if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "Cancelled." ); return; }
      }
      boolean already = existing.containsKey( sel.env );
      o.println( "-> guest env variable: " + sel.env + "  (token prefix " + prefix( token ) + "...)"
                 + ( already ? "  [will overwrite the existing entry]" : "" ) );
      o.println();

      // 疎通テスト (任意)。api.anthropic.com への Bearer 認証で 401 か否か。
      o.print( "Verify this token now (send one request to " + sel.host + ")? [Y/n]: " );
      o.flush();
      String t = in.readLine();
      if( t == null || !t.trim().toLowerCase().startsWith( "n" ) ) {
        o.print( "  Testing ... " );
        o.flush();
        Result r = connectivityTest( sel, token );
        o.println( r.msg );
        if( r.invalid ) {
          o.print( "The token was rejected. Save it anyway? [y/N]: " );
          o.flush();
          String a = in.readLine();
          if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "Cancelled." ); return; }
        }
      }
      o.println();

      // 保存。
      o.print( "Save to " + cred.getPath() + " ? [Y/n]: " );
      o.flush();
      String s = in.readLine();
      if( s != null && s.trim().toLowerCase().startsWith( "n" ) ) { o.println( "Not saved. Exiting." ); return; }
      saveCredential( dir, cred, sel.env, token );

      o.println();
      o.println( "Saved: " + cred.getPath() + "  (" + sel.env + ")" );
      o.println( "  - The real token stays host-side only; the guest gets a placeholder (swapped on the wire)." );
      o.println( "  - Nothing else to set up: the credential sandbox turns itself on for "
                 + CredentialStore.hostFor( sel.env ) + " at the next start." );
      o.println( "      emulin.bat sshd" );
    } catch( Exception e ) {
      o.println( "Error: " + e );
    }
  }

  static String prefix( String t ) { return t.length() > 16 ? t.substring( 0, 16 ) : t; }

  /** issue #848: 期待 prefix は '|' 区切りで複数指定できる。1 つでも前方一致すれば OK。 */
  static boolean prefixMatches( String expected, String token ) {
    if( expected == null || expected.isEmpty() ) return true;
    for( String p : expected.split( "\\|" ) )
      if( !p.isEmpty() && token.startsWith( p ) ) return true;
    return false;
  }

  // ~/.emulin/credentials.json を NAME -> {value, savedAt} に読む (issue #774)。無ければ空。
  //   schema: { "version":1, "credentials": { "NAME": {"value":"...","savedAt":"ISO8601"} } }
  static Map<String,String[]> readCredentials( File cred ) {
    Map<String,String[]> m = new LinkedHashMap<>();
    if( cred == null || !cred.isFile() ) return m;
    try {
      Object root = MiniJson.parse( new String( Files.readAllBytes( cred.toPath() ), StandardCharsets.UTF_8 ) );
      Object creds = ( root instanceof Map ) ? ((Map<?,?>)root).get( "credentials" ) : null;
      if( creds instanceof Map ) {
        for( Map.Entry<?,?> e : ((Map<?,?>)creds).entrySet() ) {
          Object entry = e.getValue();
          if( !( entry instanceof Map ) ) continue;
          Object v  = ((Map<?,?>)entry).get( "value" );
          Object sv = ((Map<?,?>)entry).get( "savedAt" );
          if( v != null )
            m.put( String.valueOf( e.getKey() ),
                   new String[]{ String.valueOf( v ), sv == null ? null : String.valueOf( sv ) } );
        }
      }
    } catch( Exception ignore ) {}
    return m;
  }

  /** issue #935: credentials.json の `meta` を読む (秘密でない付随情報)。 */
  static Map<String,String> readMeta( File cred ) {
    Map<String,String> m = new LinkedHashMap<>();
    if( cred == null || !cred.isFile() ) return m;
    try {
      Object root = MiniJson.parse( new String( Files.readAllBytes( cred.toPath() ), StandardCharsets.UTF_8 ) );
      Object meta = ( root instanceof Map ) ? ((Map<?,?>)root).get( "meta" ) : null;
      if( meta instanceof Map )
        for( Map.Entry<?,?> e : ((Map<?,?>)meta).entrySet() )
          if( e.getValue() != null ) m.put( String.valueOf( e.getKey() ), String.valueOf( e.getValue() ) );
    } catch( Exception ignore ) {}
    return m;
  }

  /** issue #935: meta を 1 件書く (credentials はそのまま保つ)。 */
  static void saveMeta( File dir, File cred, String name, String value ) throws Exception {
    if( value == null || value.isEmpty() ) return;
    if( !dir.isDirectory() ) dir.mkdirs();
    Map<String,String[]> m = readCredentials( cred );
    Map<String,String>   meta = readMeta( cred );
    meta.put( name, value );
    writeCredentialsFile( dir, cred, m, meta );
  }

  static final class Result { final boolean invalid; final String msg; Result( boolean i, String m ){ invalid=i; msg=m; } }

  // host 側 SSLSocket で api.anthropic.com に最小の POST /v1/messages を 1 本投げる。
  //   401/403 = token rejected (invalid)。それ以外 (200/400 等) = 認証は通った (=valid。
  //   ヘッダ/model 名の細部がズレても、API は auth を先に検証するので 401 か否かで判定できる)。
  //   接続不可/タイムアウト = ネットワーク不通として区別。claude 実行不要。
  // issue #773: provider ごとに host / endpoint / 認証ヘッダ / body が違うので、
  //   Provider の probe 定義から HTTP/1.1 リクエストを組み立てる。
  //   ★ 見るのは「認証が通ったか」だけ。200 以外でも 401/403 系でなければトークンは有効
  //     (test request の形が違うだけ) と判定する。
  static Result connectivityTest( Provider prov, String token ) {
    final String host = prov.host;
    SSLSocket sock = null;
    try {
      sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
      sock.connect( new InetSocketAddress( host, 443 ), 10000 );
      sock.setSoTimeout( 15000 );
      SSLParameters p = sock.getSSLParameters();
      p.setApplicationProtocols( new String[]{ "http/1.1" } );
      p.setServerNames( Collections.singletonList( new SNIHostName( host ) ) );
      sock.setSSLParameters( p );
      sock.startHandshake();
      byte[] body = ( prov.body == null ) ? null : prov.body.getBytes( StandardCharsets.UTF_8 );
      StringBuilder req = new StringBuilder();
      req.append( prov.probe ).append( " HTTP/1.1\r\n" );
      req.append( "Host: " ).append( host ).append( "\r\n" );
      req.append( prov.authHeader ).append( token ).append( "\r\n" );
      for( String h : prov.extraHeaders ) req.append( h ).append( "\r\n" );
      if( body != null ) {
        req.append( "content-type: application/json\r\n" );
        req.append( "content-length: " ).append( body.length ).append( "\r\n" );
      }
      req.append( "connection: close\r\n\r\n" );
      OutputStream os = sock.getOutputStream();
      os.write( req.toString().getBytes( StandardCharsets.ISO_8859_1 ) );
      if( body != null ) os.write( body );
      os.flush();
      BufferedReader r = new BufferedReader( new InputStreamReader( sock.getInputStream(), StandardCharsets.ISO_8859_1 ) );
      String status = r.readLine();
      if( status == null ) return new Result( false, "? no response (cannot determine)" );
      int code = -1;
      String[] parts = status.split( " " );
      if( parts.length >= 2 ) try { code = Integer.parseInt( parts[1] ); } catch( Exception ignore ) {}
      // レスポンス (header + body) を少し読んで error type/message のヒントを得る (connection: close
      //   なので EOF まで、上限行数で cap)。API は auth を最初に検証し、無効トークンは 401 +
      //   authentication_error を返す。200 以外 (404/400 等) でも 401/403 でなければ「認証は通った
      //   =トークン有効」を意味する (test request の endpoint/形が違うだけ)。
      StringBuilder rest = new StringBuilder();
      try { String ln; int n = 0; while( ( ln = r.readLine() ) != null && n++ < 80 ) rest.append( ln ).append( '\n' ); }
      catch( Exception ignore ) {}
      String low  = rest.toString().toLowerCase();
      String hint = extractMessage( rest.toString() );
      // issue #773: 認証エラーの表し方は provider ごとに違う。
      //   Anthropic … 401 + authentication_error
      //   OpenAI    … 401 + invalid_api_key
      //   Gemini    … **400** + API_KEY_INVALID (401 ではない) / 403 + PERMISSION_DENIED
      //   なので status code だけでなく本文のキーワードも見る。
      boolean authErr = code == 401 || code == 403
                     || low.contains( "authentication_error" )
                     || low.contains( "invalid bearer" ) || low.contains( "invalid x-api-key" )
                     || low.contains( "invalid_api_key" ) || low.contains( "api_key_invalid" )
                     || low.contains( "api key not valid" ) || low.contains( "unauthenticated" );
      if( authErr )
        return new Result( true, "REJECTED: the token was NOT accepted -- invalid or expired (" + status.trim() + ")"
                                 + ( hint.isEmpty() ? "" : " -- " + hint ) );
      if( code == 200 )
        return new Result( false, "OK: the token is valid (HTTP 200)" );
      if( code > 0 )
        return new Result( false, "OK: the token is valid -- it authenticated with the API. "
                                 + "(The minimal test request itself returned " + status.trim()
                                 + ", which is not an authentication error"
                                 + ( hint.isEmpty() ? "" : "; " + hint ) + ".)" );
      return new Result( false, "? cannot determine (" + status.trim() + ")" );
    } catch( Exception e ) {
      return new Result( false, "? network/connection error (" + e + ") -- could not verify the token" );
    } finally {
      if( sock != null ) try { sock.close(); } catch( Exception ignore ) {}
    }
  }

  // issue #773 (B): OpenAI Codex の ChatGPT サブスクリプション認証を取り込む。
  //   ★ Claude の「長期トークン 1 個を貼る」とは違い、codex の credential は
  //     JWT 3 種 (id/access/refresh) + account_id の**組**で、しかも短命。
  //     3 つの JWT を手で貼らせるのは非現実的なので、**host 側の ~/.codex/auth.json を読む**。
  //   guest には placeholder だけの auth.json が置かれ (Egress)、wire 上で MITM が
  //   実トークンへ swap する。実トークンは host 側 (~/.emulin/credentials.json) にのみ残る。
  // ------------------------------------------------------------------
  //  issue #935: Claude の**ブラウザ認証** (`claude auth login`) を取り込む。
  //
  //  `claude setup-token` の長期トークンは **inference 限定**で、Remote Control 等は
  //  claude 自身が拒否する ("Long-lived tokens ... are limited to inference-only")。
  //  full-scope は access/refresh の 2 本組で、access は数時間で切れるため、guest では
  //  MITM が refresh を回す (#824 の機構。CLAUDE_ 接頭辞でそのまま効く)。
  // ------------------------------------------------------------------
  static void setupClaudeBrowserLogin( BufferedReader in, PrintStream o,
                                       File dir, File cred ) throws IOException {
    o.println();
    o.println( "--- Claude (Pro/Max subscription) ---" );
    o.println( "How to prepare:" );
    o.println( "  1. In another terminal ON THIS HOST, with a DEDICATED config dir:" );
    o.println( "       CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login" );
    o.println( "  2. Approve in the browser (claude.ai Pro/Max account)" );
    o.println( "  3. Come back here; this wizard reads .credentials.json for you" );
    o.println();
    o.println( "  * Use a DEDICATED config dir. OAuth refresh tokens ROTATE: if the guest" );
    o.println( "    shares one login with another Claude Code session, whichever refreshes" );
    o.println( "    first keeps working and the other is logged out. Separate logins are fine" );
    o.println( "    (two machines on one account work today)." );
    o.println( "  * Do NOT run 'claude auth login' inside the guest: the real token would be" );
    o.println( "    written inside the sandbox, which defeats the purpose." );
    // ★ codex と同じ罠: WSL2 でログインすると WSL2 のホームに置かれ Windows 側からは見えない。
    o.println( "  * Logged in from WSL2?  .credentials.json lands in the WSL2 home, not this one." );
    o.println( "    Type the UNC path below, e.g. \\\\wsl$\\<distro>\\home\\<user>\\.claude-emulin\\.credentials.json" );
    o.println( "  * macOS stores these in the Keychain (no file); this wizard cannot read that." );
    o.println();

    String cfg = System.getenv( "CLAUDE_CONFIG_DIR" );
    String defPath = ( cfg != null && !cfg.isEmpty() )
        ? new File( cfg, ".credentials.json" ).getPath()
        : new File( System.getProperty( "user.home", "." ), ".claude/.credentials.json" ).getPath();
    // ★ `claude auth login` を **Windows でやったか WSL2 でやったか**で置き場所が違う。
    //   WSL2 のホームは Windows のホームと**別物**なので、既定パスを見せるだけだと
    //   「そこに無い」で詰まる (codex でも同じ罠を踏んだ)。UNC パスを手打ちさせるのは
    //   設計が弱いので、**見つかった候補から選ばせる**。
    java.util.List<String[]> cands = findClaudeLogins( cfg );
    String pathIn;
    if( !cands.isEmpty() ) {
      o.println( "Found these Claude logins on this machine:" );
      for( int i = 0; i < cands.size(); i++ )
        o.println( "  [" + ( i + 1 ) + "] " + cands.get( i )[0] + "  " + cands.get( i )[1] );
      o.println( "  [0] type a path myself" );
      o.print( "Choose [1-" + cands.size() + ", default 1]: " );
      o.flush();
      String c = in.readLine();
      int pick = 1;
      if( c != null && !c.trim().isEmpty() ) {
        try { pick = Integer.parseInt( c.trim() ); } catch( Exception ignore ) { pick = 1; }
      }
      if( pick >= 1 && pick <= cands.size() ) {
        pathIn = cands.get( pick - 1 )[1];
        o.println( "-> " + pathIn );
      } else {
        o.print( "Path to .credentials.json [" + defPath + "]: " );
        o.flush();
        pathIn = in.readLine();
      }
    } else {
      o.println( "(no .credentials.json found in the usual places)" );
      o.print( "Path to .credentials.json [" + defPath + "]: " );
      o.flush();
      pathIn = in.readLine();
    }
    File src = new File( ( pathIn == null || pathIn.trim().isEmpty() ) ? defPath : pathIn.trim() );
    if( !src.isFile() ) {
      o.println( "Not found: " + src.getPath() );
      o.println( "  Run 'claude auth login' on THIS host first (see above). Cancelled." );
      return;
    }

    Map<String,String> tok = readClaudeCredentials( src );
    if( tok == null || tok.get( "accessToken" ) == null ) {
      o.println( "Could not read claudeAiOauth from " + src.getPath() + " (unexpected format)." );
      o.println( "  (A 'setup-token' does not create this file. Use the other Claude option.)" );
      return;
    }
    String scopes = tok.get( "scopes" );
    o.println( "Found a browser login." );
    o.println( "  subscription: " + tok.getOrDefault( "subscriptionType", "(unknown)" ) );
    o.println( "  scopes      : " + ( scopes == null ? "(none)" : scopes ) );
    // ★ full scope の実体は user:sessions:claude_code とみられる。無ければ Remote Control は
    //   使えないので、黙って保存せずここで知らせる (後で「なぜか使えない」と悩まないため)。
    if( scopes == null || !scopes.contains( "user:sessions:claude_code" ) )
      o.println( "  WARNING: 'user:sessions:claude_code' is missing -- Remote Control likely won't work." );
    o.println( "  accessToken / refreshToken will be stored host-side only." );
    o.println( "  The guest gets a placeholder .credentials.json, regenerated on every launch." );
    o.println();
    o.print( "Save these to " + cred.getPath() + " ? [Y/n]: " );
    o.flush();
    String yn = in.readLine();
    if( yn != null && yn.trim().toLowerCase().startsWith( "n" ) ) { o.println( "Cancelled." ); return; }

    int saved = 0;
    for( String[] kv : new String[][]{
           { "CLAUDE_ACCESS_TOKEN",  "accessToken"  },
           { "CLAUDE_REFRESH_TOKEN", "refreshToken" } } ) {
      String v = tok.get( kv[1] );
      if( v == null || v.isEmpty() ) continue;
      try { saveCredential( dir, cred, kv[0], v ); saved++; }
      catch( Exception e ) { o.println( "  failed to save " + kv[0] + ": " + e ); }
    }
    // ★ プラン種別と scope は**秘密ではない**が、placeholder ファイルに正しく書けないと
    //   claude が full-scope と認識しなかったり、契約と違うプラン前提の挙動になる。
    //   credentials ではなく meta に置く (placeholder を割り当てて wire で swap しないため)。
    try {
      saveMeta( dir, cred, "CLAUDE_SUBSCRIPTION_TYPE", tok.get( "subscriptionType" ) );
      saveMeta( dir, cred, "CLAUDE_SCOPES", scopes );
    } catch( Exception e ) { o.println( "  failed to save plan metadata: " + e ); }
    o.println( "Saved " + saved + " entries. (host-side only: " + cred.getPath() + ")" );
    o.println();
    o.println( "Note: the access token is short-lived (hours). Emulin refreshes it on the wire" );
    o.println( "      and keeps the new tokens host-side, so you should not need to redo this" );
    o.println( "      until the refresh token itself expires (about a week)." );
  }

  /** issue #935: この機械にある Claude のログインを列挙する。返すのは {ラベル, パス}。
   *
   *  探す場所 (存在するものだけ返す):
   *    - `$CLAUDE_CONFIG_DIR` (指定されていれば)
   *    - Windows のホーム    … `%USERPROFILE%\{.claude-emulin,.claude}\.credentials.json`
   *    - **WSL2 の各ホーム** … `\\wsl.localhost\<distro>\home\<user>\{.claude-emulin,.claude}\...`
   *
   *  ★ サンドボックス専用 (`.claude-emulin`) を**先**に並べる。README がそれを勧めており、
   *    普段使いの `.claude` を共有すると refresh の回転で相手をログアウトさせるため。
   *  ★ `\\wsl.localhost` の直下は listFiles() で列挙できないので、distro 名は
   *    `wsl.exe -l -q` から取る (出力は **UTF-16LE**。ここを間違えると 1 つも見つからない)。 */
  static java.util.List<String[]> findClaudeLogins( String cfg ) {
    java.util.List<String[]> out = new java.util.ArrayList<>();
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    if( cfg != null && !cfg.isEmpty() ) addIfFile( out, seen, "CLAUDE_CONFIG_DIR",
        new File( cfg, ".credentials.json" ) );
    String home = System.getProperty( "user.home", "." );
    for( String d : new String[]{ ".claude-emulin", ".claude" } )
      addIfFile( out, seen, "this host (" + d + ")", new File( new File( home, d ), ".credentials.json" ) );
    if( System.getProperty( "os.name", "" ).toLowerCase().startsWith( "windows" ) ) {
      for( String distro : wslDistros() ) {
        File users = new File( "\\\\wsl.localhost\\" + distro + "\\home" );
        File[] us = users.listFiles();
        if( us == null ) continue;
        for( File u : us )
          for( String d : new String[]{ ".claude-emulin", ".claude" } )
            addIfFile( out, seen, "WSL2 " + distro + " / " + u.getName() + " (" + d + ")",
                       new File( new File( u, d ), ".credentials.json" ) );
      }
    }
    return out;
  }

  private static void addIfFile( java.util.List<String[]> out, java.util.Set<String> seen,
                                 String label, File f ) {
    try {
      if( !f.isFile() ) return;
      String p = f.getPath();
      if( !seen.add( p ) ) return;
      out.add( new String[]{ label, p } );
    } catch( Exception ignore ) { }
  }

  /** `wsl.exe -l -q` で distro 名を得る。★ 出力は UTF-16LE (ここを誤ると空になる)。 */
  static java.util.List<String> wslDistros() {
    java.util.List<String> r = new java.util.ArrayList<>();
    try {
      // ★ このパッケージには emulin.Process があるので java.lang.Process を明示する。
      java.lang.Process p =
          new ProcessBuilder( "wsl.exe", "-l", "-q" ).redirectErrorStream( true ).start();
      byte[] b = p.getInputStream().readAllBytes();
      p.waitFor();
      String s = new String( b, java.nio.charset.StandardCharsets.UTF_16LE );
      for( String line : s.split( "\r?\n" ) ) {
        String t = line.trim().replace( "\u0000", "" );
        if( !t.isEmpty() ) r.add( t );
      }
    } catch( Exception ignore ) { }
    return r;
  }

  /** issue #935: `.credentials.json` の claudeAiOauth を読む (MiniJson = java.base のみ)。 */
  static Map<String,String> readClaudeCredentials( File f ) {
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      Object root = MiniJson.parse( text );
      if( !( root instanceof Map ) ) return null;
      Object oauth = ((Map<?,?>) root).get( "claudeAiOauth" );
      if( !( oauth instanceof Map ) ) return null;
      Map<?,?> m = (Map<?,?>) oauth;
      Map<String,String> out = new LinkedHashMap<>();
      for( String k : new String[]{ "accessToken", "refreshToken", "subscriptionType" } ) {
        Object v = m.get( k );
        if( v != null ) out.put( k, String.valueOf( v ) );
      }
      Object sc = m.get( "scopes" );
      if( sc instanceof java.util.List ) {
        StringBuilder b = new StringBuilder();
        for( Object x : (java.util.List<?>) sc ) {
          if( b.length() > 0 ) b.append( " " );
          b.append( String.valueOf( x ) );
        }
        out.put( "scopes", b.toString() );
      }
      return out;
    } catch( Exception e ) {
      return null;
    }
  }

  static void setupCodexSubscription( BufferedReader in, PrintStream o,
                                      File dir, File cred ) throws IOException {
    o.println();
    o.println( "--- OpenAI Codex (ChatGPT subscription) ---" );
    o.println( "How to prepare:" );
    o.println( "  1. In another terminal ON THIS HOST:  codex login" );
    o.println( "       (no browser on this machine?  codex login --device-auth" );
    o.println( "        prints a code to type on another device)" );
    o.println( "  2. Approve in the browser (ChatGPT Plus/Pro account)" );
    o.println( "  3. Come back here; this wizard reads ~/.codex/auth.json for you" );
    o.println();
    o.println( "  * Do NOT run 'codex login' inside the guest: the real token would be" );
    o.println( "    written inside the sandbox, which defeats the purpose. The guest gets" );
    o.println( "    a placeholder auth.json regenerated on every launch." );
    // ★ 実機で踏んだ罠: WSL2 でログインすると WSL2 のホームに置かれ、この Windows 側の
    //   ウィザードからは見えない (~ が別物)。下の path プロンプトで UNC を渡せる。
    o.println( "  * Logged in from WSL2?  auth.json lands in the WSL2 home, not this one." );
    o.println( "    Either copy it:  cp ~/.codex/auth.json /mnt/c/Users/<user>/.codex/auth.json" );
    o.println( "    or type the UNC path below, e.g. \\\\wsl$\\<distro>\\home\\<user>\\.codex\\auth.json" );
    o.println( "  Note: this is the subscription. The 'OpenAI (API key)' option is billed per use." );
    o.println();

    String defPath = new File( System.getProperty( "user.home", "." ), ".codex/auth.json" ).getPath();
    o.print( "Path to codex auth.json [" + defPath + "]: " );
    o.flush();
    String pathIn = in.readLine();
    File src = new File( ( pathIn == null || pathIn.trim().isEmpty() ) ? defPath : pathIn.trim() );
    if( !src.isFile() ) {
      o.println( "Not found: " + src.getPath() );
      o.println( "  Run 'codex login' on THIS host first (or, if you logged in from WSL2," );
      o.println( "  copy the file over / give the \\\\wsl$\\... path above). Cancelled." );
      return;
    }

    Map<String,String> tok = readCodexAuth( src );
    if( tok == null ) { o.println( "Could not parse " + src.getPath() + " (unexpected format). Cancelled." ); return; }
    if( tok.get( "access_token" ) == null ) {
      o.println( "No ChatGPT tokens in " + src.getPath() + "." );
      o.println( "  (auth_mode=" + tok.get( "auth_mode" ) + ". If you logged in with an API key," );
      o.println( "   use the 'OpenAI (API key)' option instead.)" );
      return;
    }
    o.println( "Found ChatGPT subscription tokens (auth_mode=" + tok.get( "auth_mode" ) + ")." );
    o.println( "  id_token / access_token / refresh_token / account_id will be stored host-side." );
    o.println( "  The guest gets placeholder JWTs only; the real tokens never enter the sandbox." );
    o.println();
    o.print( "Save these to " + cred.getPath() + " ? [Y/n]: " );
    o.flush();
    String yn = in.readLine();
    if( yn != null && yn.trim().toLowerCase().startsWith( "n" ) ) { o.println( "Cancelled." ); return; }

    // 既存の saveCredential を 4 回呼ぶ (1 件ずつ atomic に書く。順序は表示順)。
    int saved = 0;
    for( String[] kv : new String[][]{
           { "CODEX_ACCESS_TOKEN",  "access_token"  },
           { "CODEX_REFRESH_TOKEN", "refresh_token" },
           { "CODEX_ID_TOKEN",      "id_token"      },
           { "CODEX_ACCOUNT_ID",    "account_id"    } } ) {
      String v = tok.get( kv[1] );
      if( v == null || v.isEmpty() ) continue;
      try { saveCredential( dir, cred, kv[0], v ); saved++; }
      catch( Exception e ) { o.println( "  failed to save " + kv[0] + ": " + e ); }
    }
    o.println( "Saved " + saved + " entries. (host-side only: " + cred.getPath() + ")" );
    o.println();
    o.println( "Note: these tokens are short-lived. If codex stops working in the guest," );
    o.println( "      run 'codex login' on the host again and re-run this wizard." );
  }

  // codex の auth.json から必要な値を取り出す (MiniJson は java.base のみで動く自前 parser)。
  static Map<String,String> readCodexAuth( File f ) {
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      Object root = MiniJson.parse( text );
      if( !( root instanceof Map ) ) return null;
      Map<?,?> m = (Map<?,?>) root;
      Map<String,String> out = new LinkedHashMap<>();
      Object am = m.get( "auth_mode" );
      out.put( "auth_mode", am == null ? "(none)" : String.valueOf( am ) );
      Object t = m.get( "tokens" );
      if( t instanceof Map ) {
        Map<?,?> tm = (Map<?,?>) t;
        for( String k : new String[]{ "id_token", "access_token", "refresh_token", "account_id" } ) {
          Object v = tm.get( k );
          if( v != null ) out.put( k, String.valueOf( v ) );
        }
      }
      return out;
    } catch( Exception e ) {
      return null;
    }
  }

  // JSON body から "message":"..." を粗く 1 つ抜き出す (診断ヒント用。escape は無視・切詰め)。
  static String extractMessage( String body ) {
    int i = body.indexOf( "\"message\"" );
    if( i < 0 ) return "";
    int c = body.indexOf( ':', i );       if( c  < 0 ) return "";
    int q1 = body.indexOf( '"', c + 1 );  if( q1 < 0 ) return "";
    int q2 = body.indexOf( '"', q1 + 1 ); if( q2 < 0 ) return "";
    String m = body.substring( q1 + 1, q2 ).trim();
    return m.length() > 140 ? m.substring( 0, 140 ) + "..." : m;
  }

  // ~/.emulin/credentials.json の該当 NAME を更新/追加 (savedAt=現在時刻) し、他 credential は保持。
  //   atomic (tmp + Files.move) 書き込み。best-effort で owner-only 権限 (POSIX)。issue #774。
  static void saveCredential( File dir, File cred, String name, String token ) throws Exception {
    if( !dir.isDirectory() ) dir.mkdirs();
    Map<String,String[]> m = readCredentials( cred );
    String now = java.time.Instant.now().truncatedTo( java.time.temporal.ChronoUnit.SECONDS ).toString();
    m.put( name, new String[]{ token, now } );
    writeCredentialsFile( dir, cred, m, readMeta( cred ) );   // issue #935: meta を消さない
  }

  /** credentials.json を atomic に書く (issue #774 の手順。issue #935 で meta も一緒に)。 */
  static void writeCredentialsFile( File dir, File cred,
                                    Map<String,String[]> m, Map<String,String> meta ) throws Exception {
    File tmp = new File( dir, "credentials.json.emulin-tmp" );
    Files.write( tmp.toPath(), renderCredentials( m, meta ).getBytes( StandardCharsets.UTF_8 ) );
    try { tmp.setReadable( false, false ); tmp.setReadable( true, true );
          tmp.setWritable( false, false ); tmp.setWritable( true, true ); } catch( Exception ignore ) {}
    try {
      Files.move( tmp.toPath(), cred.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
    } catch( java.nio.file.AtomicMoveNotSupportedException e ) {
      Files.move( tmp.toPath(), cred.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
  }

  // credentials.json をレンダリングする (issue #774)。手動編集しやすいよう整形 pretty-print。
  static String renderCredentials( Map<String,String[]> m ) {
    return renderCredentials( m, new LinkedHashMap<String,String>() );
  }

  /** issue #935: `meta` (秘密でない付随情報) を保ったまま書き出す。
   *  ★ ここを忘れると、次に別の credential を保存したときに meta が**黙って消える**
   *    (render は毎回ファイル全体を作り直すため)。 */
  static String renderCredentials( Map<String,String[]> m, Map<String,String> meta ) {
    StringBuilder b = new StringBuilder();
    b.append( "{\n  \"version\": 1,\n" );
    if( meta != null && !meta.isEmpty() ) {
      b.append( "  \"meta\": {\n" );
      int mi = 0, mn = meta.size();
      for( Map.Entry<String,String> e : meta.entrySet() )
        b.append( "    " ).append( MiniJson.quote( e.getKey() ) ).append( ": " )
         .append( MiniJson.quote( e.getValue() ) ).append( ++mi < mn ? ",\n" : "\n" );
      b.append( "  },\n" );
    }
    b.append( "  \"credentials\": {\n" );
    int idx = 0, n = m.size();
    for( Map.Entry<String,String[]> e : m.entrySet() ) {
      String value = e.getValue()[0];
      String sv    = e.getValue().length > 1 ? e.getValue()[1] : null;
      b.append( "    " ).append( MiniJson.quote( e.getKey() ) )
       .append( ": { \"value\": " ).append( MiniJson.quote( value ) )
       .append( ", \"savedAt\": " ).append( sv == null ? "null" : MiniJson.quote( sv ) )
       .append( " }" ).append( ++idx < n ? ",\n" : "\n" );
    }
    b.append( "  }\n}\n" );
    return b.toString();
  }
}
