// ----------------------------------------
//  TlsMitmProxy — issue #401 Phase 1: TLS-MITM プロキシ (credential placeholder swap)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  allowlist API host (:443) への connect を横取りし、TLS を終端して HTTP/1 の
//   credential placeholder を実キーに swap してから実 server へ中継する。
//
//  方式: host 側 127.0.0.1 の SSLServerSocket を 1 つ立て、emulin の amd64_connect が
//   MITM 対象 connect を「実 server でなくこの local proxy」へ繋ぎ替える。guest は
//   emulin CA 署名 leaf を提示されて TLS handshake (NODE_EXTRA_CA_CERTS で CA を信頼)、
//   proxy が ClientHello の SNI から upstream host を決めて実 TLS で中継する。
//   設計(#408) の in-emulin SSLEngine 双方向 pump の代わりに、実 SSLSocket を使う
//   local-proxy 方式 (堅牢・同一セキュリティモデル: 実キーは host 側 proxy のみ)。
//
//  invariant: 実キーは host 側 (CredentialStore) のみ。guest は placeholder だけ持ち、
//   placeholder→実キー の swap は wire 上 (この proxy 内) でのみ起こる。
// ----------------------------------------
package emulin;

import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;
import javax.net.ssl.*;

public class TlsMitmProxy {

  private final EmulinCA        ca;
  private final CredentialStore creds;
  private final boolean         dbg = System.getenv("EMULIN_TRACE_MITM") != null;

  private volatile int          port = -1;
  private SSLServerSocket       server;
  private SSLContext            guestCtx;   // leaf を提示する server 側 context

  public TlsMitmProxy( EmulinCA ca, CredentialStore creds ) {
    this.ca = ca;
    this.creds = creds;
  }

  // local proxy を起動 (冪等)、待受 port を返す。amd64_connect が繋ぎ替え先に使う。
  public synchronized int ensureStarted() throws Exception {
    if( port > 0 ) return port;
    KeyStore leaf = ca.leafKeyStore();
    KeyManagerFactory kmf = KeyManagerFactory.getInstance( KeyManagerFactory.getDefaultAlgorithm() );
    kmf.init( leaf, ca.keyStorePassword() );
    guestCtx = SSLContext.getInstance( "TLS" );
    guestCtx.init( kmf.getKeyManagers(), null, null );
    SSLServerSocket ss = (SSLServerSocket) guestCtx.getServerSocketFactory()
        .createServerSocket( 0, 64, InetAddress.getByName( "127.0.0.1" ) );
    // guest と http/1.1 を ALPN 合意 (h1 で credential swap する)。実 client (curl/Bun/claude)
    //   は h1 で問題なく通る。h2 対応 (guest h2 / upstream h1 downgrade) は別途 #433。
    SSLParameters p = ss.getSSLParameters();
    p.setApplicationProtocols( new String[]{ "http/1.1" } );
    ss.setSSLParameters( p );
    server = ss;
    port = ss.getLocalPort();
    Thread t = new Thread( this::acceptLoop, "emulin-mitm-accept" );
    t.setDaemon( true );
    t.start();
    if( dbg ) System.err.println( "[mitm] proxy listening on 127.0.0.1:" + port );
    return port;
  }

  private void acceptLoop() {
    while( true ) {
      final SSLSocket guest;
      try { guest = (SSLSocket) server.accept(); }
      catch( IOException e ) { return; }
      Thread h = new Thread( () -> handle( guest ), "emulin-mitm-conn" );
      h.setDaemon( true );
      h.start();
    }
  }

  private void handle( SSLSocket guest ) {
    SSLSocket up = null;
    try {
      // client が offer した ALPN list をログ (診断) しつつ http/1.1 を選ぶ。selector が呼ばれ
      //   なければ client は ALPN 拡張を送っていない (その場合 negotiated ALPN は空)。h2 downgrade は #433。
      guest.setHandshakeApplicationProtocolSelector( ( s, protos ) -> {
        if( dbg ) System.err.println( "[mitm] client ALPN offer=" + protos );
        return protos.contains( "http/1.1" ) ? "http/1.1" : "";
      } );
      guest.startHandshake();
      String sni = extractSni( guest );
      if( sni == null ) { if( dbg ) System.err.println( "[mitm] no SNI, drop" ); guest.close(); return; }
      if( dbg ) System.err.println( "[mitm] guest TLS ok, SNI=" + sni + " ALPN=" + guest.getApplicationProtocol() );
      // upstream: 実 server へ通常 TLS (実 CA 検証)、SNI/ALPN h1 を合わせる。
      up = (SSLSocket) SSLSocketFactory.getDefault().createSocket( sni, 443 );
      SSLParameters up_p = up.getSSLParameters();
      up_p.setApplicationProtocols( new String[]{ "http/1.1" } );
      up_p.setServerNames( Collections.singletonList( new SNIHostName( sni ) ) );
      up.setSSLParameters( up_p );
      up.startHandshake();
      if( dbg ) System.err.println( "[mitm] upstream TLS ok -> " + sni );
      final InputStream  gin = new BufferedInputStream( guest.getInputStream() );
      final OutputStream gout = guest.getOutputStream();
      final InputStream  uin = up.getInputStream();
      final OutputStream uout = up.getOutputStream();
      final SSLSocket upF = up;
      // response (upstream→guest) は無加工で中継。
      Thread resp = new Thread( () -> { copyRaw( uin, gout ); closeQuiet( guest ); closeQuiet( upF ); }, "emulin-mitm-resp" );
      resp.setDaemon( true );
      resp.start();
      // request (guest→upstream) は HTTP/1 header の placeholder を実キーに swap。
      pumpRequest( gin, uout );
    } catch( Exception e ) {
      if( dbg ) System.err.println( "[mitm] handle error: " + e );
    } finally {
      closeQuiet( guest );
      closeQuiet( up );
    }
  }

  // guest→upstream: HTTP/1 request を読み、header 行の placeholder を実キーに swap して中継。
  //   body は raw 転送 (Content-Length / chunked)。keep-alive で繰り返す。
  //   (package-private: 単体テストから credential swap / HTTP parse を検証する)
  void pumpRequest( InputStream in, OutputStream out ) throws IOException {
    while( true ) {
      // --- header 群を読み rewrite ---
      ByteArrayOutputStream hdr = new ByteArrayOutputStream();
      long contentLength = -1;
      boolean chunked = false;
      boolean first = true, swapped = false;
      while( true ) {
        String line = readLine( in );
        if( dbg && first ) System.err.println( "[mitm] h1 first request line=" + ( line == null ? "<null/EOF>" : line ) );
        if( line == null ) { if( first ) return; break; }  // EOF
        first = false;
        if( line.isEmpty() ) {  // header 終端 (空行)
          hdr.write( '\r' ); hdr.write( '\n' );
          break;
        }
        String low = line.toLowerCase( Locale.ROOT );
        if( low.startsWith( "content-length:" ) ) {
          try { contentLength = Long.parseLong( line.substring( line.indexOf(':')+1 ).trim() ); } catch( Exception ignore ) {}
        } else if( low.startsWith( "transfer-encoding:" ) && low.contains( "chunked" ) ) {
          chunked = true;
        }
        // placeholder swap (Authorization / x-api-key 等、どの header 行でも)
        String rewritten = line;
        for( String ph : creds.placeholders() ) {
          if( rewritten.contains( ph ) ) {
            String real = creds.resolve( ph );
            if( real != null ) { rewritten = rewritten.replace( ph, real ); swapped = true; }
          }
        }
        hdr.write( rewritten.getBytes( "ISO-8859-1" ) );
        hdr.write( '\r' ); hdr.write( '\n' );
      }
      out.write( hdr.toByteArray() );
      out.flush();
      if( dbg && swapped ) System.err.println( "[mitm] credential placeholder swapped in request header" );
      // --- body を raw 転送 ---
      if( chunked ) {
        copyChunked( in, out );
      } else if( contentLength > 0 ) {
        copyN( in, out, contentLength );
      }
      out.flush();
    }
  }

  // ---- helpers ----

  private static String extractSni( SSLSocket s ) {
    try {
      SSLSession sess = s.getSession();
      if( sess instanceof ExtendedSSLSession ) {
        for( SNIServerName n : ((ExtendedSSLSession)sess).getRequestedServerNames() )
          if( n instanceof SNIHostName ) return ((SNIHostName)n).getAsciiName();
      }
    } catch( Exception ignore ) {}
    return null;
  }

  // CRLF 終端の 1 行を読む (header 用、ISO-8859-1)。EOF で null。
  private static String readLine( InputStream in ) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    boolean any = false;
    while( (c = in.read()) != -1 ) {
      any = true;
      if( c == '\n' ) break;
      if( c != '\r' ) b.write( c );
    }
    if( !any && b.size() == 0 ) return null;
    return new String( b.toByteArray(), "ISO-8859-1" );
  }

  private static void copyN( InputStream in, OutputStream out, long n ) throws IOException {
    byte[] buf = new byte[8192];
    long left = n;
    while( left > 0 ) {
      int r = in.read( buf, 0, (int)Math.min( buf.length, left ) );
      if( r < 0 ) break;
      out.write( buf, 0, r );
      left -= r;
    }
  }

  // chunked transfer-encoding を最後の 0-chunk まで raw 転送。
  private static void copyChunked( InputStream in, OutputStream out ) throws IOException {
    while( true ) {
      String sizeLine = readLine( in );
      if( sizeLine == null ) return;
      out.write( sizeLine.getBytes( "ISO-8859-1" ) ); out.write( '\r' ); out.write( '\n' );
      int semi = sizeLine.indexOf( ';' );
      String hex = (semi >= 0 ? sizeLine.substring( 0, semi ) : sizeLine).trim();
      long size;
      try { size = Long.parseLong( hex, 16 ); } catch( Exception e ) { return; }
      if( size == 0 ) {  // 末尾 (trailer + 空行) を転送
        String t;
        while( (t = readLine( in )) != null ) { out.write( t.getBytes("ISO-8859-1") ); out.write('\r'); out.write('\n'); if( t.isEmpty() ) break; }
        out.flush();
        return;
      }
      copyN( in, out, size );
      String crlf = readLine( in );  // chunk 末尾の CRLF
      out.write( '\r' ); out.write( '\n' );
    }
  }

  private static void copyRaw( InputStream in, OutputStream out ) {
    byte[] buf = new byte[16384];
    try {
      int r;
      while( (r = in.read( buf )) != -1 ) { out.write( buf, 0, r ); out.flush(); }
    } catch( IOException ignore ) {}
  }

  private static void closeQuiet( Closeable c ) { if( c != null ) try { c.close(); } catch( Exception ignore ) {} }
}
