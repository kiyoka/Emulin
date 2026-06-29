// ----------------------------------------
//  H2Mitm — issue #401: HTTP/2 MITM (downgrade: guest h2 / upstream h1)。
//
//  guest が ALPN で h2 を選んだとき TlsMitmProxy.handle から呼ばれる。guest 側 h2 を終端し、
//  HEADERS を HPACK decode → credential placeholder を実キーに swap → upstream へ HTTP/1.1(TLS)
//  で中継 → upstream の h1 response を h2(HEADERS+DATA) に変換して逐次返す (SSE streaming 対応)。
//  slow-CPU の h2 runaway は upstream を h1 にすることで回避 (guest↔MITM は localhost で高速)。
//
//  HPACK は JDK 内蔵 jdk.internal.net.http.hpack を流用 (自前実装ゼロ)。compile/run とも
//  --add-exports java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED が要る (pom + launcher)。
//  PoC: /home/kiyoka/emulin-h2poc/H2MitmPoc.java で de-risk 済 (streaming/flow control/swap)。
// ----------------------------------------
package emulin;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;
import jdk.internal.net.http.hpack.Decoder;
import jdk.internal.net.http.hpack.Encoder;

final class H2Mitm {

  static final int DATA=0, HEADERS=1, PRIORITY=2, RST_STREAM=3, SETTINGS=4,
                   PING=6, GOAWAY=7, WINDOW_UPDATE=8, CONTINUATION=9;
  static final int MAX_FRAME = 16384;

  private final SSLSocket guest;
  private final String    sni;     // upstream host
  private final CredentialStore creds;
  private final boolean   dbg;
  private final DataInputStream in;
  private final OutputStream    out;

  // h2 flow control (send 方向)
  private long connWindow = 65535;
  private long initialStreamWin = 65535;
  private final Map<Integer,Long> streamWin = new HashMap<>();
  private final Set<Integer> resetStreams = new HashSet<>();   // RST_STREAM された stream
  private boolean closed = false;
  private final Object writeLk = new Object();

  // per-stream の request 状態 (multi-stream 並行で混ざらないよう stream 毎に保持)
  static final class Stream { final List<String[]> headers = new ArrayList<>(); final ByteArrayOutputStream body = new ByteArrayOutputStream(); }

  H2Mitm( SSLSocket guest, String sni, CredentialStore creds, boolean dbg ) throws IOException {
    this.guest = guest; this.sni = sni; this.creds = creds; this.dbg = dbg;
    this.in  = new DataInputStream( new BufferedInputStream( guest.getInputStream() ) );
    this.out = new BufferedOutputStream( guest.getOutputStream() );
  }

  // reader loop。connection が閉じるまで block (request 完了で bridge thread を起こす)。
  void run() throws Exception {
    byte[] preface = new byte[24]; in.readFully( preface );   // client connection preface
    wf( SETTINGS, 0, 0, new byte[0] );                         // server SETTINGS
    Decoder dec = new Decoder( 4096 );
    Map<Integer,Stream> streams = new HashMap<>();   // ★per-stream state (multi-stream 並行)
    while( true ) {
      int b0 = in.read(); if( b0 < 0 ) { close(); return; }
      int len = (b0<<16)|(in.read()<<8)|in.read();
      int type = in.read(), flags = in.read();
      int sid = in.readInt() & 0x7fffffff;
      byte[] pl = new byte[len]; in.readFully( pl );
      switch( type ) {
        case SETTINGS: if( (flags&1)==0 ) { parseSettings( pl ); wf( SETTINGS, 1, 0, new byte[0] ); } break;
        case PING:     if( (flags&1)==0 ) wf( PING, 1, 0, pl ); break;
        case WINDOW_UPDATE: {
          long inc = ((pl[0]&0x7fL)<<24)|((pl[1]&0xffL)<<16)|((pl[2]&0xffL)<<8)|(pl[3]&0xffL);
          addWindow( sid, inc ); break;
        }
        case PRIORITY: break;
        case HEADERS: {
          initStream( sid );
          Stream st = streams.computeIfAbsent( sid, k -> new Stream() );
          byte[] block = stripHeadersPadding( pl, flags );
          dec.decode( ByteBuffer.wrap( block ), true,
              (n,v) -> st.headers.add( new String[]{ n.toString(), v.toString() } ) );
          if( (flags&0x1)!=0 ) { streams.remove( sid ); startBridge( sid, st.headers, st.body.toByteArray() ); }
          break;
        }
        case DATA: {
          Stream st = streams.get( sid );
          if( st != null ) { st.body.write( pl ); if( (flags&0x1)!=0 ) { streams.remove( sid ); startBridge( sid, st.headers, st.body.toByteArray() ); } }
          break;
        }
        case RST_STREAM: resetStream( sid ); break;   // stream 単位 cancel (conn は閉じない)
        case GOAWAY: close(); return;
        default: break;
      }
    }
  }

  private void startBridge( int sid, List<String[]> reqHeaders, byte[] reqBody ) {
    final List<String[]> hs = new ArrayList<>( reqHeaders );
    Thread t = new Thread( () -> {
      try { bridge( sid, hs, reqBody ); }
      catch( Exception e ) { if( dbg ) System.err.println( "[mitm-h2] bridge err " + e ); close(); }
    }, "emulin-mitm-h2-bridge" );
    t.setDaemon( true );
    t.start();
  }

  // ★streaming downgrade bridge: guest h2 request → upstream h1 → guest へ h2 逐次変換。
  private void bridge( int sid, List<String[]> reqHeaders, byte[] reqBody ) throws Exception {
    String method = "GET", path = "/";
    List<String[]> fwd = new ArrayList<>();
    boolean swapped = false;
    for( String[] h : reqHeaders ) {
      String name = h[0], val = h[1];
      switch( name ) {
        case ":method": method = val; continue;
        case ":path":   path = val; continue;
        case ":scheme": case ":authority": continue;
        default: break;
      }
      if( name.equals("host") || name.equals("connection") || name.equals("keep-alive")
          || name.equals("transfer-encoding") || name.equals("upgrade") ) continue;
      for( String ph : creds.placeholders() ) {
        if( val.contains( ph ) ) {
          String real = creds.resolve( ph );
          if( real != null ) { val = val.replace( ph, real ); swapped = true; }
        }
      }
      fwd.add( new String[]{ name, val } );
    }
    if( dbg ) System.err.println( "[mitm-h2] " + method + " " + path + " -> " + sni + " (h1) swapped=" + swapped );

    // upstream: 実 server へ TLS h1 (実 CA 検証、SNI/ALPN h1)
    SSLSocket up = (SSLSocket) SSLSocketFactory.getDefault().createSocket( sni, 443 );
    SSLParameters up_p = up.getSSLParameters();
    up_p.setApplicationProtocols( new String[]{ "http/1.1" } );
    up_p.setServerNames( Collections.singletonList( new SNIHostName( sni ) ) );
    up.setSSLParameters( up_p );
    up.startHandshake();
    try {
      OutputStream uo = up.getOutputStream();
      StringBuilder req = new StringBuilder();
      req.append( method ).append( ' ' ).append( path ).append( " HTTP/1.1\r\nHost: " ).append( sni ).append( "\r\n" );
      for( String[] h : fwd ) req.append( h[0] ).append( ": " ).append( h[1] ).append( "\r\n" );
      if( reqBody.length > 0 ) req.append( "Content-Length: " ).append( reqBody.length ).append( "\r\n" );
      req.append( "Connection: close\r\n\r\n" );
      uo.write( req.toString().getBytes( StandardCharsets.ISO_8859_1 ) );
      if( reqBody.length > 0 ) uo.write( reqBody );
      uo.flush();

      DataInputStream ui = new DataInputStream( new BufferedInputStream( up.getInputStream() ) );
      String statusLine = readLine( ui );
      int status = Integer.parseInt( statusLine.split( " " )[1] );
      List<String[]> rh = new ArrayList<>();
      rh.add( new String[]{ ":status", Integer.toString( status ) } );
      boolean chunked = false; long clen = -1; String line;
      while( (line = readLine( ui )) != null && !line.isEmpty() ) {
        int ci = line.indexOf( ':' ); if( ci < 0 ) continue;
        String n = line.substring( 0, ci ).trim().toLowerCase( Locale.ROOT ), v = line.substring( ci+1 ).trim();
        if( n.equals("transfer-encoding") && v.toLowerCase().contains("chunked") ) { chunked = true; continue; }
        if( n.equals("content-length") ) { try { clen = Long.parseLong( v ); } catch( Exception ig ){} }
        if( n.equals("connection") || n.equals("keep-alive") ) continue;
        rh.add( new String[]{ n, v } );
      }
      Encoder enc = new Encoder( 4096 );
      wf( HEADERS, 0x4, sid, hpackEncode( enc, rh.toArray( new String[0][] ) ) );   // h2 HEADERS を即送出
      if( dbg ) System.err.println( "[mitm-h2] upstream " + status + " (chunked=" + chunked + " clen=" + clen + ") -> stream" );

      // body を逐次 read → h2 DATA を逐次 emit (全バッファしない、flow control 尊重)
      byte[] buf = new byte[MAX_FRAME];
      if( chunked ) {
        while( true ) {
          String sz = readLine( ui ); if( sz == null ) break;
          int n = Integer.parseInt( sz.trim().split(";")[0], 16 );
          if( n == 0 ) { readLine( ui ); break; }
          int rem = n;
          while( rem > 0 ) { int r = ui.read( buf, 0, Math.min( buf.length, rem ) ); if( r < 0 ) break; sendData( sid, buf, 0, r, false ); rem -= r; }
          readLine( ui );
        }
      } else if( clen >= 0 ) {
        long rem = clen;
        while( rem > 0 ) { int r = ui.read( buf, 0, (int)Math.min( buf.length, rem ) ); if( r < 0 ) break; sendData( sid, buf, 0, r, false ); rem -= r; }
      } else {
        int r; while( (r = ui.read( buf )) >= 0 ) if( r > 0 ) sendData( sid, buf, 0, r, false );
      }
      sendData( sid, new byte[0], 0, 0, true );   // END_STREAM
    } finally { try { up.close(); } catch( Exception ig ){} }
  }

  // ---- h2 framing / flow control ----
  private void wf( int type, int flags, int sid, byte[] pl ) throws IOException {
    synchronized( writeLk ) { writeFrame( out, type, flags, sid, pl ); out.flush(); }
  }
  private synchronized void addWindow( int sid, long inc ) {
    if( sid == 0 ) connWindow += inc; else streamWin.merge( sid, inc, Long::sum );
    notifyAll();
  }
  private synchronized void resetStream( int sid ) { resetStreams.add( sid ); notifyAll(); }   // RST_STREAM: その stream の bridge を中断
  private synchronized void initStream( int sid ) { streamWin.putIfAbsent( sid, initialStreamWin ); }
  private void parseSettings( byte[] pl ) {
    for( int i = 0; i+6 <= pl.length; i += 6 ) {
      int id = ((pl[i]&0xff)<<8)|(pl[i+1]&0xff);
      long v = ((pl[i+2]&0xffL)<<24)|((pl[i+3]&0xffL)<<16)|((pl[i+4]&0xffL)<<8)|(pl[i+5]&0xffL);
      if( id == 4 ) initialStreamWin = v;
    }
  }
  private void sendData( int sid, byte[] data, int off, int len, boolean end ) throws IOException, InterruptedException {
    int sent = 0;
    while( sent < len ) {
      int n;
      synchronized( this ) {
        long avail;
        while( (avail = Math.min( connWindow, streamWin.getOrDefault( sid, 0L ) )) <= 0 && !closed && !resetStreams.contains( sid ) ) wait();
        if( closed || resetStreams.contains( sid ) ) throw new IOException( "stream/conn aborted" );
        n = (int) Math.min( Math.min( avail, MAX_FRAME ), len - sent );
        connWindow -= n; streamWin.merge( sid, (long)-n, Long::sum );
      }
      boolean last = end && (sent + n == len);
      wf( DATA, last?0x1:0, sid, Arrays.copyOfRange( data, off+sent, off+sent+n ) );
      sent += n;
    }
    if( len == 0 && end ) wf( DATA, 0x1, sid, new byte[0] );
  }
  synchronized void close() { closed = true; notifyAll(); try { guest.close(); } catch( Exception e ){} }

  // ---- static helpers ----
  private static byte[] stripHeadersPadding( byte[] payload, int flags ) {
    int off = 0, end = payload.length, pad = 0;
    if( (flags&0x8)!=0 )  { pad = payload[off]&0xff; off += 1; }
    if( (flags&0x20)!=0 ) { off += 5; }
    return Arrays.copyOfRange( payload, off, end - pad );
  }
  private static String readLine( InputStream in ) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(); int c; boolean any = false;
    while( (c = in.read()) >= 0 ) { any = true; if( c=='\n' ) break; if( c!='\r' ) b.write( c ); }
    if( !any && b.size()==0 ) return null;
    return b.toString( "ISO-8859-1" );
  }
  private static byte[] hpackEncode( Encoder enc, String[][] headers ) {
    ByteArrayOutputStream acc = new ByteArrayOutputStream(); ByteBuffer buf = ByteBuffer.allocate( 2048 );
    for( String[] h : headers ) {
      enc.header( h[0], h[1] ); boolean d = false;
      while( !d ) { d = enc.encode( buf ); buf.flip(); byte[] b = new byte[buf.remaining()]; buf.get( b ); acc.write( b, 0, b.length ); buf.clear(); }
    }
    return acc.toByteArray();
  }
  private static void writeFrame( OutputStream out, int type, int flags, int sid, byte[] payload ) throws IOException {
    int len = payload.length;
    out.write( (len>>16)&0xff ); out.write( (len>>8)&0xff ); out.write( len&0xff );
    out.write( type ); out.write( flags );
    out.write( (sid>>24)&0x7f ); out.write( (sid>>16)&0xff ); out.write( (sid>>8)&0xff ); out.write( sid&0xff );
    out.write( payload );
  }
}
