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
    policy = new EgressPolicy( dns, EmulinCA.DEFAULT_SAN_HOSTS );
    proxy  = new TlsMitmProxy( ca, creds );
  }

  // host 側の設定 dir (~/.emulin)。keystore / credential file の置き場で、
  //   Mount がここを guest から遮断する (#767) 基準でもあるので導出は 1 箇所に集める。
  public static File emulinDir() {
    return new File( System.getProperty( "user.home", "." ), ".emulin" );
  }

  // host 側 credential file (~/.emulin/credentials)。emulin.{bat,sh} setcred が書き、
  //   launcher の MITM 自動有効化判定もこの path を見る (両者で同じ場所を指すこと)。
  public static File credentialFile() {
    return new File( emulinDir(), "credentials" );
  }

  public static boolean enabled() {
    String v = System.getenv( "EMULIN_EGRESS_MITM" );
    return v != null && !v.isEmpty() && !"0".equals( v );
  }

  // credential file はあるのに MITM が無効なときに理由を 1 行出す。
  //   launcher (emulin.{bat,sh}) は credential file があれば EMULIN_EGRESS_MITM=1 を
  //   自動で立てるが、`java -jar` 直起動や古い launcher ではそれが効かない。その場合
  //   guest に placeholder が入らないまま claude 等が「/login せよ」と言い出し、原因が
  //   まったく見えないので、ここで明示する。
  public static void warnIfCredentialsUnused() {
    if( enabled() ) return;
    File f = credentialFile();
    if( !f.isFile() ) return;
    System.err.println( "[egress] note: " + f + " exists but EMULIN_EGRESS_MITM is unset;"
      + " no credential is injected into the guest (set EMULIN_EGRESS_MITM=1 to enable)" );
  }

  // 起動時: guest の trust store + env を準備する。
  //   - 公開 CA cert を rootfs /etc/ssl/emulin-ca.pem に配置 (秘密鍵は出さない)
  //   - guest env に NODE_EXTRA_CA_CERTS (Bun/Node 用) と system ca-bundle append (curl 用)
  //   - CredentialStore の placeholder を guest env に注入 (実キーは入れない)
  public void prepareGuest( Sysinfo sysinfo, List<String> envList ) {
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
      if( System.getenv( "EMULIN_TRACE_MITM" ) != null )
        System.err.println( "[egress] prepared: CA -> " + GUEST_CA_PATH + ", placeholders=" + creds.placeholders().size() );
    } catch( Exception e ) {
      System.err.println( "[egress] prepareGuest failed: " + e );
    }
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
