// ----------------------------------------
//  Egress — issue #401 Phase 1: 通信サンドボックス化 (TLS-MITM) の facade
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  EmulinCA / CredentialStore / DnsSnoop / EgressPolicy / TlsMitmProxy を束ね、
//   起動時の guest trust 注入 (CA cert + placeholder) と connect 時の MITM 判定を
//   1 箇所に集約する。EMULIN_EGRESS_MITM=1 のときだけ有効 (default off、既存挙動不変)。
//
//  invariant (#401): CA 秘密鍵・leaf 秘密鍵・実 API キーは host 側のみ。guest へは
//   公開 CA cert と placeholder だけ。
// ----------------------------------------
package emulin;

import java.io.*;
import java.util.List;
import java.util.Set;

public class Egress {

  public final EmulinCA        ca;
  public final CredentialStore creds;
  public final DnsSnoop        dns;
  public final EgressPolicy    policy;
  public final TlsMitmProxy    proxy;

  // guest が NODE_EXTRA_CA_CERTS で指す CA cert path (rootfs 内)。
  public static final String GUEST_CA_PATH = "/etc/ssl/emulin-ca.pem";

  public Egress() {
    File dir = emulinDir();
    ca     = new EmulinCA( dir, null );
    creds  = new CredentialStore();
    creds.discoverFromFile( credentialFile() );  // #401: host-only, Mount で guest 遮断
    creds.discoverFromHostEnv();                 // env は file を override
    dns    = new DnsSnoop();
    // MITM 対象は「設定済み credential の送り先」だけ (cert の SAN 一覧ではない)。
    //   credential が無ければ空 = どこも横取りしない。
    Set<String> hosts = creds.mitmHosts();
    policy = new EgressPolicy( dns, hosts.toArray( new String[0] ) );
    proxy  = new TlsMitmProxy( ca, creds );
  }

  // host 側の設定 dir (~/.emulin)。keystore / credential file の置き場で、
  //   Mount がここを guest から遮断する (#767) 基準でもあるので導出は 1 箇所に集める。
  public static File emulinDir() {
    return new File( System.getProperty( "user.home", "." ), ".emulin" );
  }

  // host 側 credential file (~/.emulin/credentials.json)。emulin.{bat,sh} setcred が書く (issue #774)。
  public static File credentialFile() {
    return new File( emulinDir(), "credentials.json" );
  }

  // credential が 1 つでも設定されているか (file または EMULIN_CRED_* env)。
  //   これが false なら守る秘密が無いので、Kernel は egress を作らず Mount の deny guard も
  //   no-op にする (= credential 未設定のユーザには #401 以前と完全に同じ挙動・同じ負荷)。
  //   状態を持たず TOCTOU も無い (env と file の存在だけを見る)。
  public static boolean hasCredentials() {
    if( credentialFile().isFile() ) return true;
    for( String k : System.getenv().keySet() )
      if( k != null && k.startsWith( CredentialStore.HOST_PREFIX ) ) return true;
    return false;
  }

  // 既定で有効。EMULIN_EGRESS_MITM=0 (false/off/no) で明示的に切れる。
  //   「有効」は「credential があれば守る」という意味で、credential が 1 つも無ければ
  //   Kernel 側で Egress ごと skip されるので TLS 終端も CA 生成も起こらない
  //   (= credential 未設定のユーザには従来と完全に同じ挙動)。
  public static boolean enabled() {
    String v = System.getenv( "EMULIN_EGRESS_MITM" );
    if( v == null || v.isEmpty() ) return true;
    String s = v.trim().toLowerCase();
    return !( s.equals( "0" ) || s.equals( "false" ) || s.equals( "off" ) || s.equals( "no" ) );
  }

  // credential file はあるのに MITM を明示的に切っているときに 1 行知らせる。
  //   guest に placeholder が入らないまま claude 等が「/login せよ」と言い出したとき、
  //   原因がまったく見えないのを防ぐ (実際に踏んだ事故)。
  public static void warnIfCredentialsUnused() {
    if( enabled() ) return;
    File f = credentialFile();
    if( !f.isFile() ) return;
    System.err.println( "[egress] note: " + f + " exists but EMULIN_EGRESS_MITM is off;"
      + " no credential is injected into the guest" );
  }

  // issue #774: 旧形式 ~/.emulin/credentials (NAME=value) は読まなくなった。新 credentials.json が
  //   無いのに旧ファイルだけある場合、黙って credential 無し扱いになると原因が見えないので 1 行案内する
  //   (旧ファイルは parse しない = 後方互換なし。setcred での作り直しを促すだけ)。
  public static void warnLegacyCredential() {
    File json = credentialFile();
    File legacy = new File( emulinDir(), "credentials" );
    if( !json.isFile() && legacy.isFile() )
      System.err.println( "[egress] note: found legacy " + legacy + " (pre-#774 format, no longer read);"
        + " run 'emulin.bat setcred' once to create " + json.getName() );
  }

  // 起動時: guest の trust store + env を準備する。
  //   - 公開 CA cert を rootfs /etc/ssl/emulin-ca.pem に配置 (秘密鍵は出さない)
  //   - guest env に NODE_EXTRA_CA_CERTS (Bun/Node 用) と system ca-bundle append (curl 用)
  //   - CredentialStore の placeholder を guest env に注入 (実キーは入れない)
  //   準備できたら true。false のときは MITM を張れないので caller は egress を持たない
  //   (中途半端に横取りだけ有効になって通信が壊れるのを防ぐ)。
  public boolean prepareGuest( Sysinfo sysinfo, List<String> envList ) {
    try {
      ca.ensureGenerated();
      byte[] pem = ca.caPem();
      String hostCaPath = sysinfo.get_native_path( GUEST_CA_PATH );
      if( hostCaPath != null ) {
        File f = new File( hostCaPath );
        if( f.getParentFile() != null ) f.getParentFile().mkdirs();
        try ( OutputStream o = new FileOutputStream( f ) ) { o.write( pem ); }
        envList.add( "NODE_EXTRA_CA_CERTS=" + GUEST_CA_PATH );
        appendToCaBundle( sysinfo, pem );
        // ★ issue #865: **rustls (Rust 製 client) は system の ca-bundle を自分で探さない**。
        //   openssl-probe 経由で SSL_CERT_FILE / SSL_CERT_DIR を見るため、未設定だと
        //   EmulinCA を知らず MITM の leaf を `unknown_ca` で拒否する
        //   (実機の codex がこれ。curl は ca-bundle を直接読むので通っていた =
        //    「curl は動くのに codex だけ落ちる」非対称の正体)。
        //   ★ 指すのは **CA 単体ではなく ca-bundle** (直前で EmulinCA を追記済み)。
        //     単体を指すと通常のサイトの検証が全部落ちる。
        if( caBundlePath( sysinfo ) != null ) {
          envList.add( "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt" );
          envList.add( "SSL_CERT_DIR=/etc/ssl/certs" );
        }
      }
      creds.injectPlaceholders( envList );
      writeCodexAuth( sysinfo );        // issue #773 (B)
      writeClaudeOnboarding( sysinfo ); // issue #876
      report();
      if( System.getenv( "EMULIN_TRACE_MITM" ) != null )
        System.err.println( "[egress] prepared: CA -> " + GUEST_CA_PATH + ", placeholders=" + creds.placeholders().size() );
      return true;
    } catch( Throwable t ) {
      // Exception ではなく Throwable: launcher の --add-exports が無いと sun.security.x509
      //   への linkage が IllegalAccessError (Error) になり、catch(Exception) を素通りして
      //   boot ごと落ちる。ここで握って理由を出し、MITM 無しで起動を続ける。
      System.err.println( "[egress] credential sandbox disabled: " + t );
      if( t instanceof IllegalAccessError )
        System.err.println( "[egress]   (launch via emulin.bat / emulin.sh; the CA generator"
          + " needs --add-exports java.base/sun.security.x509=ALL-UNNAMED)" );
      return false;
    }
  }

  // issue #773 (B): OpenAI Codex は **env でなくファイル** (`~/.codex/auth.json`) から
  //   認証情報を読む。実測で分かったこと:
  //     - CODEX_ACCESS_TOKEN / CODEX_AUTH は別用途 (agent identity / secret 名) で使えない
  //     - auth.json の JWT は**ローカルで parse される**だけで署名は検証されない
  //       → **形さえ合っていれば placeholder で「ログイン済み」と認識される**
  //   そこで guest には placeholder だけの auth.json を置き、実トークンは host 側に留める。
  //   wire 上の placeholder は MITM が実トークンへ swap するので、guest は一度も実物を見ない。
  //   ★ 既にファイルがある場合は上書きしない (ユーザが guest 内で codex login した結果を
  //     勝手に壊さない)。credential 未設定なら何もしない。
  /** issue #824: 既存の codex auth.json が「Emulin が書いた placeholder 版」かどうか。
   *
   *  placeholder には `emph01` という marker が入っている (CredentialStore が生成)。
   *  これが入っていれば Emulin が書いたものなので、現在の placeholder で上書きしてよい。
   *  実トークンが入っている場合は利用者が guest 内で login したとみなして触らない。 */
  private static boolean isEmulinPlaceholderAuth( File f ) {
    try {
      String t = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                             java.nio.charset.StandardCharsets.UTF_8 );
      if( t.contains( "emph01" ) ) return true;    // UUID 形 (CODEX_ACCOUNT_ID) は素で入る
      // ★ JWT 形の placeholder は marker を **base64url の中**に持つ (payload の "emulin" claim)。
      //   素の文字列検索では見つからないので、base64url らしき断片を decode して調べる。
      for( String part : t.split( "[^A-Za-z0-9_-]+" ) ) {
        if( part.length() < 16 ) continue;
        try {
          String d = new String( java.util.Base64.getUrlDecoder().decode( part ),
                                 java.nio.charset.StandardCharsets.UTF_8 );
          if( d.contains( "emph01" ) ) return true;
        } catch( Exception ignore ) { }
      }
      return false;
    } catch( Exception e ) {
      return false;   // 読めないなら触らない
    }
  }

  private void writeCodexAuth( Sysinfo sysinfo ) {
    String at = creds.placeholderOf( "CODEX_ACCESS_TOKEN" );
    if( at == null ) return;                       // Codex サブスクの credential 未設定
    String rt = creds.placeholderOf( "CODEX_REFRESH_TOKEN" );
    String it = creds.placeholderOf( "CODEX_ID_TOKEN" );
    String ai = creds.placeholderOf( "CODEX_ACCOUNT_ID" );
    for( String home : new String[]{ "/root", "/home/" + System.getenv( "EMULIN_THEUSER" ) } ) {
      if( home.endsWith( "null" ) ) continue;
      try {
        String nat = sysinfo.get_native_path( home + "/.codex" );
        if( nat == null ) continue;
        File dir = new File( nat );
        File f   = new File( dir, "auth.json" );
        // ★ issue #824: placeholder は**起動ごとに作り直される**ので、既存ファイルを
        //   そのまま残すと guest は**古い placeholder**を送り続ける。MITM 側は新しい
        //   placeholder しか知らないので置換が起きず、placeholder がそのまま OpenAI に
        //   届いて 401 (Could not parse your authentication token) になる。
        //   → 中身が Emulin の placeholder なら**毎回書き直す**。
        //   guest 内で `codex login` して本物のトークンが入っている場合だけは尊重する
        //   (サンドボックスの趣旨には反するが、利用者の明示的な操作を壊さない)。
        if( f.exists() && !isEmulinPlaceholderAuth( f ) ) {
          System.err.println( "[egress] " + home + "/.codex/auth.json は Emulin の placeholder では"
              + "ないため触りません (guest 内で codex login した場合はそのまま使われます)" );
          continue;
        }
        if( !dir.isDirectory() && !dir.mkdirs() ) continue;
        StringBuilder j = new StringBuilder();
        j.append( "{\n  \"auth_mode\": \"chatgpt\",\n" );
        j.append( "  \"OPENAI_API_KEY\": null,\n" );
        j.append( "  \"tokens\": {\n" );
        j.append( "    \"id_token\": \"" ).append( it != null ? it : at ).append( "\",\n" );
        j.append( "    \"access_token\": \"" ).append( at ).append( "\",\n" );
        j.append( "    \"refresh_token\": \"" ).append( rt != null ? rt : at ).append( "\",\n" );
        j.append( "    \"account_id\": \"" ).append( ai != null ? ai : "00000000-0000-0000-0000-000000000000" ).append( "\"\n" );
        j.append( "  },\n" );
        j.append( "  \"last_refresh\": \"" )
         .append( java.time.format.DateTimeFormatter.ofPattern( "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" )
                    .format( java.time.ZonedDateTime.now( java.time.ZoneOffset.UTC ) ) )
         .append( "\"\n}\n" );
        try ( OutputStream o = new FileOutputStream( f ) ) {
          o.write( j.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        }
        try { f.setReadable( false, false ); f.setReadable( true, true ); f.setWritable( true, true ); }
        catch( Exception ignore ) {}
        if( System.getenv( "EMULIN_TRACE_MITM" ) != null )
          System.err.println( "[egress] wrote placeholder codex auth.json -> " + home + "/.codex/auth.json" );
      } catch( Exception e ) {
        System.err.println( "[egress] codex auth.json の配置に失敗 (" + home + "): " + e );
      }
    }
  }

  /** issue #876: Claude Code の初回 onboarding を済み扱いにする (`~/.claude.json`)。
   *
   *  claude は `CLAUDE_CODE_OAUTH_TOKEN` があっても**初回の対話起動では onboarding を
   *  出す** (テーマ選択 → ログイン方式の選択)。トークン自体は効いている
   *  (`claude -p` は onboarding 無しでそのトークンを使って送信する) のに、UI だけが
   *  先に立ちはだかる。
   *
   *  ★ これは単なる不便ではなく**危険**: 利用者はそこで「1. Claude account with
   *    subscription」を選んでしまい、**guest の中で OAuth が走って本物のトークンが
   *    sandbox 内に書き込まれる**。#401 が防ごうとしているものを利用者自身の手で
   *    無効化することになる (codex で同じ罠を踏んだ)。
   *
   *  `hasCompletedOnboarding` が 1 つあれば onboarding は出ない (theme は不要)。
   *
   *  ★ codex の auth.json と違い **`~/.claude.json` は上書きしてはいけない**。
   *    projects / userID / 履歴など利用者の状態を持つファイルなので、
   *    **キーが無いときだけ挿入する**マージにする。 */
  private void writeClaudeOnboarding( Sysinfo sysinfo ) {
    if( creds.placeholderOf( "CLAUDE_CODE_OAUTH_TOKEN" ) == null
     && creds.placeholderOf( "ANTHROPIC_API_KEY" ) == null ) return;   // Claude の credential 未設定
    for( String home : new String[]{ "/root", "/home/" + System.getenv( "EMULIN_THEUSER" ) } ) {
      if( home.endsWith( "null" ) ) continue;
      try {
        String nat = sysinfo.get_native_path( home + "/.claude.json" );
        if( nat == null ) continue;
        File f = new File( nat );
        if( !f.getParentFile().isDirectory() ) continue;   // home 自体が無い = 触らない
        String cur = null;
        if( f.isFile() ) {
          cur = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                            java.nio.charset.StandardCharsets.UTF_8 );
          // 既に済み (claude が書いた / 前回 seed した / 利用者が明示設定した) なら何もしない。
          if( cur.contains( "\"hasCompletedOnboarding\"" ) ) continue;
        }
        String out = withOnboardingFlag( cur );
        if( out == null ) continue;                        // JSON object に見えない = 触らない
        boolean created = !f.isFile();
        try ( OutputStream o = new FileOutputStream( f ) ) {
          o.write( out.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        }
        // 新規作成したときだけ所有者のみに絞る (既存ファイルの権限は変えない)。
        if( created ) {
          try { f.setReadable( false, false ); f.setReadable( true, true ); f.setWritable( true, true ); }
          catch( Exception ignore ) {}
        }
        if( System.getenv( "EMULIN_TRACE_MITM" ) != null )
          System.err.println( "[egress] claude onboarding を済み扱いに -> " + home + "/.claude.json"
              + ( created ? " (新規作成)" : " (既存にキーを追加)" ) );
      } catch( Exception e ) {
        System.err.println( "[egress] claude.json の更新に失敗 (" + home + "): " + e );
      }
    }
  }

  /** JSON object の先頭に `"hasCompletedOnboarding": true` を挿入した文字列を返す。
   *  object に見えなければ null (壊さないため何もしない)。 */
  static String withOnboardingFlag( String cur ) {
    final String KV = "\"hasCompletedOnboarding\": true";
    if( cur == null || cur.trim().isEmpty() ) return "{\n  " + KV + "\n}\n";
    int b = cur.indexOf( '{' );
    if( b < 0 ) return null;
    int i = b + 1;
    while( i < cur.length() && Character.isWhitespace( cur.charAt( i ) ) ) i++;
    if( i >= cur.length() ) return null;                   // 閉じていない = 壊れている
    // 空 object のときに "," を足すと trailing comma で JSON が壊れる。
    String sep = ( cur.charAt( i ) == '}' ) ? "" : ",";
    return cur.substring( 0, b + 1 ) + "\n  " + KV + sep + cur.substring( b + 1 );
  }

  // 何を守っているかを 1 行で示す。これが出ない = credential が guest に渡っていない、と
  //   一目で分かるようにする (無言で守られていないのが #401 で一番危ない状態だった)。
  private void report() {
    // issue #774: 既知 provider ごとに「保存済み(登録日時) / 未設定」と MITM 先を 1 行で示す。
    //   設定済みなら savedAt (credentials.json)、env 由来で日時不明なら (source: env) と出す。
    for( String n : CredentialStore.knownNames() ) {
      String host = CredentialStore.hostFor( n );
      if( host == null ) host = "(no MITM host)";
      if( creds.names().contains( n ) ) {
        String sv = creds.savedAtOf( n );
        String when = ( sv != null ) ? "saved " + sv : "saved (source: env)";
        System.err.println( "[egress] credential " + n + " = " + when + " -> " + host );
      } else {
        System.err.println( "[egress] credential " + n + " = not set (-> " + host + ")" );
      }
    }
    // issue #773: 別名 (GOOGLE_API_KEY 等) は一覧には出さないが、**実際に設定されていれば**
    //   何がどこへ行くかを示す (黙って MITM するのが一番危ない)。
    for( String n : creds.names() ) {
      if( CredentialStore.knownNames().contains( n ) ) continue;   // 主名は上のループで出力済み
      String host = CredentialStore.hostFor( n );
      if( host == null ) continue;                                  // MITM 先不明は下で警告する
      String sv = creds.savedAtOf( n );
      System.err.println( "[egress] credential " + n + " = "
        + ( ( sv != null ) ? "saved " + sv : "saved (source: env)" ) + " -> " + host + " (alias)" );
    }
    // NAME_HOSTS に無い名前 (MITM 先不明) は placeholder が実 server に届いてしまうので警告。
    for( String n : creds.unmappedNames() )
      System.err.println( "[egress] warning: no MITM host is known for " + n
        + "; its placeholder would reach the real server as-is" );
  }

  // curl 等 non-Node client 用に system ca-bundle へ append (重複は marker で防ぐ)。
  /** issue #865: guest の ca-bundle が実在すればその host path、無ければ null。 */
  private String caBundlePath( Sysinfo sysinfo ) {
    try {
      String p = sysinfo.get_native_path( "/etc/ssl/certs/ca-certificates.crt" );
      return ( p != null && new File( p ).isFile() ) ? p : null;
    } catch( Throwable t ) { return null; }
  }

  // guest 側で「実際に使われている」CA バンドル。**実在するものすべて**に追記する。
  //   ★ ca-certificates.crt だけを更新していると、別のバンドルを指している client が
  //     MITM の leaf を検証できず失敗する。実際 git は build-sandbox.sh が書く
  //     /etc/gitconfig の `sslCAInfo = /etc/ssl/certs/emulin-roots.pem` を見ており、
  //     こちらには emulin CA が一度も追記されていなかった:
  //       fatal: unable to access '...': server verification failed:
  //              certificate signer not trusted. (CAfile: /etc/ssl/certs/emulin-roots.pem)
  //     #401 の MITM 対象に github.com が入って初めて表面化した (#848)。
  //   ★ #898 と同型の失敗: 「更新すべき場所が N 個あるのに 1 個しか更新していない」。
  private static final String[] GUEST_CA_BUNDLES = {
    "/etc/ssl/certs/ca-certificates.crt",   // Debian 系の標準 (curl / OpenSSL)
    "/etc/ssl/certs/emulin-roots.pem",      // build-sandbox.sh が git に指定するもの
  };

  private void appendToCaBundle( Sysinfo sysinfo, byte[] pem ) {
    for( String p : GUEST_CA_BUNDLES ) appendToOneCaBundle( sysinfo, pem, p );
  }

  private void appendToOneCaBundle( Sysinfo sysinfo, byte[] pem, String guestPath ) {
    try {
      String bundlePath = sysinfo.get_native_path( guestPath );
      if( bundlePath == null ) return;
      File bundle = new File( bundlePath );
      if( !bundle.isFile() ) return;
      final String marker = "# emulin local CA (issue #401)";
      // issue #765: ISO-8859-1 は byte↔char 1:1 で decode が throw しない。旧 US_ASCII 版は
      //   CodingErrorAction.REPORT なので bundle に非 ASCII バイトが 1 つでもあると
      //   MalformedInputException で全処理が中断し、emulin CA が無言で未追記になっていた
      //   (curl 等が MITM leaf を検証できず、しかも旧 CA block も除去されない)。
      java.nio.charset.Charset cs = java.nio.charset.StandardCharsets.ISO_8859_1;
      byte[] cur = java.nio.file.Files.readAllBytes( bundle.toPath() );
      // 既存の emulin CA block を「すべて」除去してから現行 CA を 1 つだけ足す。
      //   ※ CERT_SIGNATURE_FAILURE 対策: subject 同一 (CN=emulin local CA) の旧 CA が残ると、
      //     chain builder が issuer 名一致で旧鍵を選び新 leaf の署名検証に (非決定的に) 失敗する。
      StringBuilder out = new StringBuilder();
      boolean skip = false;
      try ( BufferedReader r = new BufferedReader( new StringReader( new String( cur, cs ) ) ) ) {
        String line;
        while( ( line = r.readLine() ) != null ) {
          if( line.equals( marker ) ) { skip = true; continue; }        // emulin block 開始
          if( skip ) { if( line.contains( "END CERTIFICATE" ) ) skip = false; continue; }
          out.append( line ).append( '\n' );
        }
      }
      out.append( marker ).append( '\n' ).append( new String( pem, cs ) );
      if( out.charAt( out.length() - 1 ) != '\n' ) out.append( '\n' );
      byte[] next = out.toString().getBytes( cs );
      // issue #765: 内容が変わらなければ書かない (CA は p12 永続で不変。毎 boot の全 rewrite と
      //   下の truncate 書き込みの破損窓を避ける)。
      if( java.util.Arrays.equals( next, cur ) ) return;
      // issue #765: atomic 置換 (tmp に書いて move)。旧実装は truncate-then-write で、途中失敗
      //   (DrvFs/ディスク満杯) すると system trust store 全体が破損し guest の全 HTTPS が壊れ得た。
      //   CLAUDE.md「Windows は NIO Files.move に切替」に従う。
      File tmp = new File( bundle.getParentFile(), bundle.getName() + ".emulin-tmp" );
      java.nio.file.Files.write( tmp.toPath(), next );
      try {
        java.nio.file.Files.move( tmp.toPath(), bundle.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE );
      } catch( java.nio.file.AtomicMoveNotSupportedException amns ) {
        java.nio.file.Files.move( tmp.toPath(), bundle.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      }
    } catch( Exception ignore ) {}
  }
}
