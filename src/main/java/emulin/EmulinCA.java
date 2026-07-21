// ----------------------------------------
//  EmulinCA — issue #401 Phase 1: emulin 専用 CA + leaf 証明書
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  通信サンドボックス化 (TLS-MITM credential 注入) の証明書基盤。
//  emulin 専用 CA (自己署名, CA:true) と allowlist host を SAN にした leaf cert を
//  生成し、host 側 (~/.emulin/) に永続化して再利用する。
//
//  信頼境界 (issue #401 invariant):
//   - CA 秘密鍵・leaf 秘密鍵は host 側のみ。guest へは「公開 CA cert」だけ
//     (NODE_EXTRA_CA_CERTS / ca-bundle 経由)。
//   - compromise した guest は CA 鍵を持たず証明書を偽造不可。
//   - CA cert は当該 sandbox の trust store のみに入れ、host の system trust には入れない。
//
//  pure Java (sun.security.x509) で生成 (依存追加ゼロ)。launcher が
//   --add-exports java.base/{sun.security.x509,sun.security.util}=ALL-UNNAMED を握る。
//   PoC (CertPoc.java, 2026-06-24) で curl/claude が leaf を受理することを実証済み。
// ----------------------------------------
package emulin;

import sun.security.x509.*;
import sun.security.tools.keytool.CertAndKeyGen;
import sun.security.util.ObjectIdentifier;
import sun.security.util.KnownOIDs;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;

public class EmulinCA {

  // MITM 対象 (leaf SAN に列挙する) allowlist host。claude の API ホスト
  //   (api.anthropic.com / platform.claude.com) を含む。PoC で SAN カバレッジが
  //   pinning でなく問題と確認済み (両方必要)。
  public static final String[] DEFAULT_SAN_HOSTS = {
    "api.anthropic.com", "platform.claude.com", "claude.ai",
    "console.anthropic.com", "statsig.anthropic.com", "localhost",
  };

  private static final String KEYSTORE_FILE = "emulin-ca.p12";
  private static final char[] PW = "emulin".toCharArray();
  private static final long   VALID_SEC = 3650L * 86400;  // 10 年

  private final File    dir;       // host 側保存先 (~/.emulin)
  private final String[] sanHosts;

  private X509Certificate caCert;
  private PrivateKey      caKey;
  private X509Certificate leafCert;
  private PrivateKey      leafKey;

  public EmulinCA( File dir, String[] sanHosts ) {
    this.dir = dir;
    this.sanHosts = (sanHosts != null && sanHosts.length > 0) ? sanHosts : DEFAULT_SAN_HOSTS;
  }

  // 初回に CA + leaf を生成して host 側 keystore に保存。以後はそれを load して再利用
  //   (trust store が毎回変わらない)。冪等。
  public synchronized void ensureGenerated() throws Exception {
    if( caCert != null ) return;  // 既に in-memory にある
    File ks = new File( dir, KEYSTORE_FILE );
    if( ks.isFile() && loadFrom( ks ) ) return;
    generate();
    saveTo( ks );
  }

  // 公開 CA cert を PEM (PKCS#7 でなく単一 CERTIFICATE) で返す。
  //   NODE_EXTRA_CA_CERTS 先 + system ca-bundle に書く。
  public byte[] caPem() throws Exception {
    ensureGenerated();
    return pem( "CERTIFICATE", caCert.getEncoded() ).getBytes( "US-ASCII" );
  }

  // guest 側 SSLEngine に提示する leaf (cert chain + 秘密鍵) を持つ KeyStore。
  //   MitmConnection が KeyManagerFactory に渡す。
  public KeyStore leafKeyStore() throws Exception {
    ensureGenerated();
    KeyStore ks = KeyStore.getInstance( "PKCS12" );
    ks.load( null, null );
    ks.setKeyEntry( "leaf", leafKey, PW, new Certificate[]{ leafCert, caCert } );
    return ks;
  }

  public char[] keyStorePassword() { return PW.clone(); }

  // ---- 内部: 生成 / 永続化 ----

  private void generate() throws Exception {
    Date from = new Date( System.currentTimeMillis() - 60_000L );

    // CA (自己署名, CA:true, keyCertSign)
    //   issue #401: 鍵は EC(P-256)/ECDSA。RSA だと emulin guest の x86-64 エミュレーションで
    //   RSA-PSS 署名検証が "invalid padding" になり (native curl は通るが guest curl/Bun が
    //   TLS1.3 CertificateVerify を拒否)、実 api.anthropic.com が ECDSA cert で guest から
    //   検証できているのと対照的だった。EC に揃えて guest が proxy cert を検証できるようにする。
    CertAndKeyGen caGen = new CertAndKeyGen( "EC", "SHA256withECDSA" );
    caGen.generate( 256 );
    caKey = caGen.getPrivateKey();
    X500Name caName = new X500Name( "CN=emulin local CA, O=emulin" );
    CertificateExtensions caExts = new CertificateExtensions();
    caExts.setExtension( BasicConstraintsExtension.NAME, new BasicConstraintsExtension( Boolean.TRUE, true, -1 ) );
    KeyUsageExtension caKu = new KeyUsageExtension();
    caKu.set( KeyUsageExtension.KEY_CERTSIGN, true );
    caKu.set( KeyUsageExtension.CRL_SIGN, true );
    caExts.setExtension( KeyUsageExtension.NAME, caKu );
    caCert = caGen.getSelfCertificate( caName, from, VALID_SEC, caExts );

    // leaf (CA 鍵で署名, SAN=allowlist, EKU=serverAuth)
    KeyPairGenerator kpg = KeyPairGenerator.getInstance( "EC" );
    kpg.initialize( 256 );   // secp256r1 (P-256)
    KeyPair leafKp = kpg.generateKeyPair();
    leafKey = leafKp.getPrivate();

    X509CertInfo info = new X509CertInfo();
    info.setVersion( new CertificateVersion( CertificateVersion.V3 ) );
    info.setSerialNumber( new CertificateSerialNumber( new BigInteger( 64, new SecureRandom() ) ) );
    info.setSubject( new X500Name( "CN=" + sanHosts[0] ) );
    info.setIssuer( caName );
    info.setValidity( new CertificateValidity( from, new Date( from.getTime() + VALID_SEC * 1000 ) ) );
    info.setKey( new CertificateX509Key( leafKp.getPublic() ) );
    info.setAlgorithmId( new CertificateAlgorithmId( AlgorithmId.get( "SHA256withECDSA" ) ) );
    CertificateExtensions leafExts = new CertificateExtensions();
    leafExts.setExtension( BasicConstraintsExtension.NAME, new BasicConstraintsExtension( false, -1 ) );
    GeneralNames sans = new GeneralNames();
    for( String h : sanHosts ) sans.add( new GeneralName( new DNSName( h ) ) );
    sans.add( new GeneralName( new IPAddressName( "127.0.0.1" ) ) );
    leafExts.setExtension( SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension( sans ) );
    Vector<ObjectIdentifier> eku = new Vector<>();
    eku.add( ObjectIdentifier.of( KnownOIDs.serverAuth ) );
    leafExts.setExtension( ExtendedKeyUsageExtension.NAME, new ExtendedKeyUsageExtension( eku ) );
    info.setExtensions( leafExts );
    leafCert = X509CertImpl.newSigned( info, caKey, "SHA256withECDSA" );
    leafCert.verify( caCert.getPublicKey() );
  }

  private boolean loadFrom( File ks ) {
    try ( InputStream in = new FileInputStream( ks ) ) {
      KeyStore store = KeyStore.getInstance( "PKCS12" );
      store.load( in, PW );
      Certificate[] chain = store.getCertificateChain( "leaf" );
      if( chain == null || chain.length < 2 ) return false;
      leafCert = (X509Certificate)chain[0];
      caCert   = (X509Certificate)chain[1];
      leafKey  = (PrivateKey)store.getKey( "leaf", PW );
      caKey    = (PrivateKey)store.getKey( "ca",   PW );
      if( leafKey == null || caKey == null ) return false;
      // issue #764: EC(P-256) 以外の旧 keystore (RSA) は破棄して再生成する。RSA leaf だと
      //   guest の software backend で RSA-PSS の TLS1.3 CertificateVerify 検証が壊れ、
      //   CERT_SIGNATURE_FAILURE / invalid padding になる (#401 の EC 化はその回避)。この判定が
      //   無いと、旧 RSA 版を一度でも動かしたユーザは upgrade 後も RSA cert のままで直らない。
      if( !"EC".equals( leafKey.getAlgorithm() ) ) return false;
      // SAN allowlist が変わっていたら再生成 (古い leaf は新 host を覆わない)。
      if( !sanCovers( leafCert ) ) return false;
      return true;
    } catch( Exception e ) {
      return false;  // 壊れていたら再生成
    }
  }

  private void saveTo( File ks ) throws Exception {
    if( !dir.isDirectory() ) dir.mkdirs();
    KeyStore store = KeyStore.getInstance( "PKCS12" );
    store.load( null, null );
    store.setKeyEntry( "leaf", leafKey, PW, new Certificate[]{ leafCert, caCert } );
    store.setKeyEntry( "ca",   caKey,   PW, new Certificate[]{ caCert } );
    File tmp = new File( ks.getParentFile(), ks.getName() + ".tmp" );
    try ( OutputStream out = new FileOutputStream( tmp ) ) { store.store( out, PW ); }
    // host 側 secret なので owner-only に絞る (best-effort, POSIX のみ)。
    try { tmp.setReadable( false, false ); tmp.setReadable( true, true );
          tmp.setWritable( false, false ); tmp.setWritable( true, true ); } catch( Exception ignore ) {}
    if( !tmp.renameTo( ks ) ) { ks.delete(); tmp.renameTo( ks ); }
  }

  // leaf の SAN が現在の allowlist を全て覆っているか (load 時の再生成判定用)。
  private boolean sanCovers( X509Certificate leaf ) {
    try {
      Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
      if( sans == null ) return false;
      Set<String> have = new HashSet<>();
      for( List<?> e : sans ) if( ((Integer)e.get(0)) == 2 ) have.add( ((String)e.get(1)).toLowerCase() );
      for( String h : sanHosts ) if( !have.contains( h.toLowerCase() ) ) return false;
      return true;
    } catch( Exception e ) { return false; }
  }

  private static String pem( String type, byte[] der ) {
    String b64 = Base64.getMimeEncoder( 64, new byte[]{'\n'} ).encodeToString( der );
    return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
  }
}
