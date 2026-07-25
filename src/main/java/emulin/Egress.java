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
      }
      creds.injectPlaceholders( envList );
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
    // NAME_HOSTS に無い名前 (MITM 先不明) は placeholder が実 server に届いてしまうので警告。
    for( String n : creds.unmappedNames() )
      System.err.println( "[egress] warning: no MITM host is known for " + n
        + "; its placeholder would reach the real server as-is" );
  }

  // curl 等 non-Node client 用に system ca-bundle へ append (重複は marker で防ぐ)。
  private void appendToCaBundle( Sysinfo sysinfo, byte[] pem ) {
    try {
      String bundlePath = sysinfo.get_native_path( "/etc/ssl/certs/ca-certificates.crt" );
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
