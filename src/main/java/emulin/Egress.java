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
    File dir = new File( System.getProperty( "user.home", "." ), ".emulin" );
    ca     = new EmulinCA( dir, null );
    creds  = new CredentialStore();
    creds.discoverFromHostEnv();
    dns    = new DnsSnoop();
    policy = new EgressPolicy( dns, EmulinCA.DEFAULT_SAN_HOSTS );
    proxy  = new TlsMitmProxy( ca, creds );
  }

  public static boolean enabled() {
    String v = System.getenv( "EMULIN_EGRESS_MITM" );
    return v != null && !v.isEmpty() && !"0".equals( v );
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
      // 既に append 済みなら skip (起動毎の重複を防ぐ)。
      try ( BufferedReader r = new BufferedReader( new InputStreamReader( new FileInputStream( bundle ), "US-ASCII" ) ) ) {
        String line;
        while( (line = r.readLine()) != null ) if( line.equals( marker ) ) return;
      }
      try ( OutputStream o = new FileOutputStream( bundle, true ) ) {
        o.write( ("\n" + marker + "\n").getBytes( "US-ASCII" ) );
        o.write( pem );
      }
    } catch( Exception ignore ) {}
  }
}
