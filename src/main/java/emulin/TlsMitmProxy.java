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
  /** issue #773 (B): body 内 placeholder を置換する上限。これを超える body は raw 転送。
   *  credential は小さな JSON/form body に載るので、大きな upload を握らないための線引き。 */
  static final int BODY_SWAP_MAX = 256 * 1024;

  /** issue #824: 1 接続ぶんの状態。request 側で分かったことを response 側へ渡す。
   *
   *  ★ response は別スレッドで raw 中継しているので、**トークン応答のときだけ**
   *    解析に切り替える必要がある。全応答を解析すると SSE (claude のストリーミング) を
   *    バッファリングして壊すので、対象を絞ることが安全性の要。 */
  static final class ConnState {
    /** request body で credential を置換した = これは OAuth の token endpoint への要求。 */
    volatile String tokenCredName = null;
    /** 最初の request を処理し終えたことを response スレッドへ知らせる。 */
    final java.util.concurrent.CountDownLatch firstRequestDone =
        new java.util.concurrent.CountDownLatch( 1 );
  }

  private volatile int          port = -1;
  private SSLServerSocket       server;
  /** guest が握手後に最初の 1 byte を送ってくるのを待つ上限 (これを過ぎたら接続を畳む)。 */
  private static final int FIRST_BYTE_WAIT_MS = 300_000;
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
      //   なければ client は ALPN 拡張を送っていない (その場合 negotiated ALPN は空)。
      //   issue #766: http/1.1 を提示しない h2-only client には null を返し no_application_protocol
      //   で明確に中断する ("" を返すと ALPN 無しで握手成立→h2 preface を h1 parser がゴミ解釈して
      //   不透明に失敗した)。h2 downgrade は別途 #433。
      guest.setHandshakeApplicationProtocolSelector( ( s, protos ) -> {
        if( dbg ) System.err.println( "[mitm] client ALPN offer=" + protos );
        return protos.contains( "http/1.1" ) ? "http/1.1" : null;
      } );
      guest.startHandshake();
      String sni = extractSni( guest );
      if( sni == null ) { if( dbg ) System.err.println( "[mitm] no SNI, drop" ); guest.close(); return; }
      if( dbg ) System.err.println( "[mitm] guest TLS ok, SNI=" + sni + " ALPN=" + guest.getApplicationProtocol() );
      final InputStream  gin = new BufferedInputStream( guest.getInputStream() );
      // ★ 上流へは **guest が最初の 1 byte を送ってから** 繋ぐ (lazy connect)。
      //   accept 直後に繋ぐと、guest が握手を終えてから実際に request を送り出すまでの
      //   数秒〜十数秒の間、上流には **1 byte も流れない idle 接続**ができる。上流
      //   (Cloudflare 等) はこれを idle timeout で切るので、その EOF を受けた応答スレッドが
      //   copyRaw を抜けて **guest 側の接続まで閉じて**しまう。guest から見ると TLS 握手の
      //   直後に user_canceled が飛んできた形になり、client は connection-failed になる。
      //   実機の Emacs (url.el) が握手後に NSM 検証/DNS で数秒使うため実際に踏んだ。
      //   curl は握手直後に request を送るので当たらず、原因の切り分けを難しくしていた。
      //   1 byte 読んで戻すだけなので、以後の HTTP parse は従来と同一。
      int soBak = 0;
      try { soBak = guest.getSoTimeout(); } catch( Throwable ignore ) {}
      // 無言のまま放置される接続でスレッドを死蔵しないよう、最初の 1 byte にだけ期限を付ける。
      try { guest.setSoTimeout( FIRST_BYTE_WAIT_MS ); } catch( Throwable ignore ) {}
      gin.mark( 2 );
      int firstByte;
      try { firstByte = gin.read(); }
      catch( java.net.SocketTimeoutException te ) {
        if( dbg ) System.err.println( "[mitm] guest sent nothing within "
            + ( FIRST_BYTE_WAIT_MS / 1000 ) + "s, closing (upstream was never dialed)" );
        return;
      }
      try { guest.setSoTimeout( soBak ); } catch( Throwable ignore ) {}
      if( firstByte < 0 ) {   // guest が何も送らずに閉じた = 上流に繋ぐ必要は無い
        if( dbg ) System.err.println( "[mitm] guest closed before sending a request" );
        return;
      }
      gin.reset();
      // upstream: 実 server へ通常 TLS (実 CA 検証)、SNI/ALPN h1 を合わせる。
      up = (SSLSocket) SSLSocketFactory.getDefault().createSocket( sni, 443 );
      SSLParameters up_p = up.getSSLParameters();
      up_p.setApplicationProtocols( new String[]{ "http/1.1" } );
      up_p.setServerNames( Collections.singletonList( new SNIHostName( sni ) ) );
      up.setSSLParameters( up_p );
      up.startHandshake();
      if( dbg ) System.err.println( "[mitm] upstream TLS ok -> " + sni );
      final OutputStream gout = guest.getOutputStream();
      final InputStream  uin = up.getInputStream();
      final OutputStream uout = up.getOutputStream();
      final SSLSocket upF = up;
      // response (upstream→guest) は原則そのまま中継する。
      //   ★ issue #824: ただし request body で credential を置換した (= OAuth の
      //     token endpoint への要求だった) ときだけ、応答を 1 往復解析して
      //     新しい実トークンを host に取り込み、guest には placeholder を返す。
      //     それ以外を解析すると SSE (claude のストリーミング応答) を壊す。
      final ConnState st = new ConnState();
      Thread resp = new Thread( () -> {
        try {
          // request 側が「token 要求だった」と判定するまで待つ (応答より先に必ず決まる)。
          st.firstRequestDone.await( 30, java.util.concurrent.TimeUnit.SECONDS );
        } catch( InterruptedException ie ) { Thread.currentThread().interrupt(); }
        // token 応答を 1 往復だけ解析し、あとは素通しに戻す。
        //   (keep-alive で 2 本目以降にも token 要求が来る場合は解析しないが、
        //    OAuth の token endpoint は 1 接続 1 往復で使われるので実害は無い。)
        if( st.tokenCredName != null ) pumpTokenResponse( uin, gout, st.tokenCredName );
        copyRaw( uin, gout );
        closeQuiet( guest ); closeQuiet( upF );
      }, "emulin-mitm-resp" );
      resp.setDaemon( true );
      resp.start();
      // request (guest→upstream) は HTTP/1 の header 行と body の placeholder を実キーに swap。
      pumpRequest( gin, uout, st );
    } catch( Exception e ) {
      // ★ JSSE の SSLProtocolException("Unexpected exception") は原因を包み隠すので、
      //   cause 連鎖まで出す (これが無いと「握手が謎に失敗する」で止まる)。
      if( dbg ) {
        StringBuilder cz = new StringBuilder( "[mitm] handle error: " + e );
        for( Throwable t = e.getCause(); t != null && cz.length() < 600; t = t.getCause() )
          cz.append( "\n    caused by: " ).append( t );
        System.err.println( cz );
      }
    } finally {
      closeQuiet( guest );
      closeQuiet( up );
    }
  }

  /** issue #824: OAuth の token 応答を 1 往復だけ解析し、
   *
   *    1. 応答 JSON の access_token / refresh_token / id_token を取り出す
   *    2. **host 側の credential を新しい値に差し替える** (placeholder は据え置き)
   *    3. 応答の該当フィールドを **placeholder に書き換えて** guest へ返す
   *
   *  こうすると guest からは「refresh が成功して新しいトークンを受け取った」ように見え、
   *  実際に伸びた寿命は host 側だけが持つ。guest に実キーは 1 度も落ちない。
   *
   *  @param credName request body で置換した credential 名 (例 CODEX_REFRESH_TOKEN)。
   *                  ここから prefix (CODEX) を取り、応答の各フィールドに対応づける。 */
  void pumpTokenResponse( InputStream in, OutputStream out, String credName ) {
    try {
      java.util.List<String> lines = new java.util.ArrayList<String>();
      long contentLength = -1;
      int  clIndex = -1;
      boolean chunked = false;
      while( true ) {
        String line = readLine( in );
        if( line == null ) return;          // EOF: 何も書かずに raw へ委ねる
        if( line.isEmpty() ) break;         // header 終端
        String low = line.toLowerCase( Locale.ROOT );
        if( low.startsWith( "content-length:" ) ) {
          try { contentLength = Long.parseLong( line.substring( line.indexOf(':')+1 ).trim() ); } catch( Exception ignore ) {}
          clIndex = lines.size();
        } else if( low.startsWith( "transfer-encoding:" ) && low.contains( "chunked" ) ) {
          chunked = true;
        }
        lines.add( line );
      }
      byte[] body = null;
      if( !chunked && contentLength > 0 && contentLength <= BODY_SWAP_MAX )
        body = readN( in, (int)contentLength );

      String rewritten = null;
      if( body != null ) {
        String bs = new String( body, java.nio.charset.StandardCharsets.UTF_8 );
        rewritten = rotateTokensInJson( bs, credName );
      }

      StringBuilder hb = new StringBuilder();
      if( rewritten != null && clIndex >= 0 ) {
        int newLen = rewritten.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
        lines.set( clIndex, "Content-Length: " + newLen );
      }
      for( String l : lines ) hb.append( l ).append( "\r\n" );
      hb.append( "\r\n" );
      out.write( hb.toString().getBytes( "ISO-8859-1" ) );
      if( rewritten != null )      out.write( rewritten.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
      else if( body != null )      out.write( body );
      else if( chunked )           { if( dbg ) System.err.println( "[mitm] token 応答が chunked のため素通し" ); }
      out.flush();
    } catch( Exception e ) {
      if( dbg ) System.err.println( "[mitm] token 応答の解析に失敗 (以後は素通し): " + e );
    }
  }

  /** 応答 JSON の token フィールドを host 側へ取り込み、placeholder に置き換えて返す。
   *  対象が 1 つも無ければ null (呼び元は元の body をそのまま流す)。 */
  private String rotateTokensInJson( String json, String credName ) {
    // credName = "CODEX_REFRESH_TOKEN" → prefix "CODEX"
    int us = ( credName == null ) ? -1 : credName.indexOf( '_' );
    if( us <= 0 ) return null;
    String prefix = credName.substring( 0, us );
    String[][] map = {
      { "access_token",  prefix + "_ACCESS_TOKEN"  },
      { "refresh_token", prefix + "_REFRESH_TOKEN" },
      { "id_token",      prefix + "_ID_TOKEN"      },
    };
    String out = json;
    int rotated = 0;
    for( String[] m : map ) {
      String val = jsonStringField( out, m[0] );
      if( val == null || val.isEmpty() ) continue;
      String ph = creds.placeholderOf( m[1] );
      if( ph == null ) continue;                 // その credential は未設定 = 触らない
      if( val.equals( ph ) ) continue;           // 既に placeholder (置換不要)
      if( creds.rotateReal( m[1], val ) ) rotated++;
      out = out.replace( "\"" + val + "\"", "\"" + ph + "\"" );
    }
    if( rotated == 0 && out.equals( json ) ) return null;
    if( dbg ) System.err.println( "[mitm] token 応答: " + rotated
        + " 件の credential を host 側で更新し、guest には placeholder を返した" );
    return out;
  }

  /** JSON から "name":"値" の値を粗く 1 つ取り出す (escape は考慮しない。token は base64url)。 */
  static String jsonStringField( String json, String name ) {
    String key = "\"" + name + "\"";
    int i = json.indexOf( key );
    if( i < 0 ) return null;
    int c = json.indexOf( ':', i + key.length() );
    if( c < 0 ) return null;
    int q1 = json.indexOf( '"', c );
    if( q1 < 0 ) return null;
    int q2 = json.indexOf( '"', q1 + 1 );
    if( q2 < 0 ) return null;
    return json.substring( q1 + 1, q2 );
  }

  /** n byte をきっちり読む (EOF で足りなければ読めた分だけ返す)。 */

  /** issue #848: `Authorization: Basic <base64(user:pass)>` の **pass 側**が placeholder なら
   *  実トークンに差し替えて re-encode する。置換したら書き換え後の行、しなければ null。
   *
   *  ★ なぜ専用処理が要るか: git の HTTPS 認証は Basic で、トークンが **base64 の中**に
   *    埋まる。header 行を素朴に文字列置換しても placeholder と一致しないため、
   *    そのまま GitHub へ届いて 401 になる (= guest から git push できない)。
   *    gh の API 呼び出しは `Bearer` なので通常の置換で済む。
   *
   *  ★ user 名は触らない。GitHub は Basic の user 部を見ない (任意の文字列でよい) ので、
   *    guest が何を入れていてもそのまま通す。password 側だけを対象にする。
   */
  private String swapBasicAuth( String line ) {
    int c = line.indexOf( ':' );
    if( c < 0 ) return null;
    if( !line.substring( 0, c ).trim().equalsIgnoreCase( "authorization" ) ) return null;
    String val = line.substring( c + 1 ).trim();
    if( val.length() < 6 || !val.regionMatches( true, 0, "Basic ", 0, 6 ) ) return null;
    String b64 = val.substring( 6 ).trim();
    byte[] raw;
    try { raw = java.util.Base64.getDecoder().decode( b64 ); }
    catch( IllegalArgumentException e ) { return null; }      // Basic でない/壊れている
    String userpass = new String( raw, java.nio.charset.StandardCharsets.ISO_8859_1 );
    int sep = userpass.indexOf( ':' );
    if( sep < 0 ) return null;
    String user = userpass.substring( 0, sep );
    String pass = userpass.substring( sep + 1 );
    String real = creds.resolve( pass );                      // 完全一致した placeholder のみ
    if( real == null ) return null;
    String enc = java.util.Base64.getEncoder().encodeToString(
        ( user + ":" + real ).getBytes( java.nio.charset.StandardCharsets.ISO_8859_1 ) );
    return line.substring( 0, c + 1 ) + " Basic " + enc;
  }

  private static byte[] readN( InputStream in, int n ) throws IOException {
    byte[] b = new byte[n];
    int off = 0;
    while( off < n ) {
      int r = in.read( b, off, n - off );
      if( r < 0 ) break;
      off += r;
    }
    if( off == n ) return b;
    byte[] t = new byte[off];
    System.arraycopy( b, 0, t, 0, off );
    return t;
  }

  // guest→upstream: HTTP/1 request を読み、header 行と body の placeholder を実キーに swap して中継。
  //   body は raw 転送 (Content-Length / chunked)。keep-alive で繰り返す。
  //   (package-private: 単体テストから credential swap / HTTP parse を検証する)
  void pumpRequest( InputStream in, OutputStream out ) throws IOException {
    pumpRequest( in, out, new ConnState() );
  }

  void pumpRequest( InputStream in, OutputStream out, ConnState st ) throws IOException {
    // ★ issue #824: どんな抜け方をしても latch は必ず開ける。開けないと response
    //   スレッドが「token 要求かどうか」を待ったまま最大 30 秒止まる
    //   (guest が接続だけして即閉じた場合など、request を 1 本も送らない経路がある)。
    try { pumpRequestInner( in, out, st ); }
    finally { st.firstRequestDone.countDown(); }
  }

  private void pumpRequestInner( InputStream in, OutputStream out, ConnState st ) throws IOException {
    while( true ) {
      // --- header 群を読み rewrite ---
      java.util.List<String> hdrLines = new java.util.ArrayList<String>();
      long contentLength = -1;
      int  clIndex = -1;   // Content-Length 行の位置 (body 置換で長さが変わったら書き換える)
      boolean chunked = false;
      boolean first = true, swapped = false, upgrade = false, bodySwapped = false;
      // issue #773 (B) 診断: 「置換されなかった」ときに理由が分かるようにする。
      //   ★ 値そのものは絶対に出さない (header 名と長さ、先頭が既知 placeholder の
      //     接頭辞と一致するかだけを見る)。
      String credHdrName = null; int credHdrLen = 0; boolean credLooksPh = false;
      while( true ) {
        String line = readLine( in );
        if( dbg && first ) System.err.println( "[mitm] h1 first request line=" + ( line == null ? "<null/EOF>" : line ) );
        if( line == null ) { if( first ) return; break; }  // EOF
        first = false;
        if( line.isEmpty() ) break;   // header 終端 (空行)。書き出しは body 確定後。
        String low = line.toLowerCase( Locale.ROOT );
        if( low.startsWith( "content-length:" ) ) {
          try { contentLength = Long.parseLong( line.substring( line.indexOf(':')+1 ).trim() ); } catch( Exception ignore ) {}
          clIndex = hdrLines.size();
        } else if( low.startsWith( "transfer-encoding:" ) && low.contains( "chunked" ) ) {
          chunked = true;
        } else if( low.startsWith( "upgrade:" ) ) {
          // WebSocket 等。header 通過後はもう HTTP/1 の request framing ではないので、
          //   ここで HTTP parse を止めないと後続の binary frame を request 行と誤読して
          //   stream を壊す。claude の remote control は
          //   wss://api.anthropic.com/v2/session_ingress/mcp/ws/ を使うため実害がある。
          upgrade = true;
        }
        // 診断: credential を載せていそうな header を覚えておく (値は保持しない)。
        if( dbg && credHdrName == null ) {
          int colon = line.indexOf( ':' );
          if( colon > 0 ) {
            String nm  = line.substring( 0, colon ).trim();
            String val = line.substring( colon + 1 ).trim();
            String nml = nm.toLowerCase( Locale.ROOT );
            if( nml.equals( "authorization" ) || nml.equals( "x-api-key" )
                || nml.equals( "api-key" ) || nml.contains( "token" ) ) {
              credHdrName = nm;
              credHdrLen  = val.length();
              for( String ph : creds.placeholders() ) {
                int n = Math.min( 10, ph.length() );
                if( n > 0 && val.contains( ph.substring( 0, n ) ) ) { credLooksPh = true; break; }
              }
            }
          }
        }
        // placeholder swap (Authorization / x-api-key 等、どの header 行でも)
        String rewritten = line;
        for( String ph : creds.placeholders() ) {
          if( rewritten.contains( ph ) ) {
            String real = creds.resolve( ph );
            if( real != null ) { rewritten = rewritten.replace( ph, real ); swapped = true; }
          }
        }
        // ★ issue #848: `Authorization: Basic <base64(user:pass)>` は **placeholder が
        //   base64 の中に埋まる**ので、上の素朴な文字列置換では一致しない。
        //   git の HTTPS push がこの形 (gh の API は Bearer なので上で済む)。
        //   decode → password 側を差し替え → re-encode する。
        //   ※ 非公開テスト #131 の負のコントロールで「marker が base64url の中にあって
        //     置換が効かない」ケースを既に踏んでおり、同型の罠。
        String basicSwapped = swapBasicAuth( rewritten );
        if( basicSwapped != null ) { rewritten = basicSwapped; swapped = true; }
        hdrLines.add( rewritten );
      }

      // ★ issue #773 (B): **body にも placeholder が載る**。
      //   OAuth の refresh (POST /oauth/token) は refresh_token を JSON body に入れる。
      //   header だけ置換する実装では、この経路が placeholder のままサーバへ届き
      //   401 (token_expired) になる = サブスク認証が原理的に成立しない。
      //   Content-Length 付きで十分小さい body に限って読み切り、置換し、
      //   長さが変わったら Content-Length を書き直す。
      //   (大きい body や chunked は従来どおり raw 転送。credential は小さな
      //    JSON/form body に載るので実害は無い。)
      byte[] swappedBody = null;
      if( contentLength > 0 && contentLength <= BODY_SWAP_MAX && !creds.isEmpty() ) {
        byte[] body = readN( in, (int)contentLength );
        String bs = new String( body, "ISO-8859-1" );
        String rewrittenBody = bs;
        for( String ph : creds.placeholders() ) {
          if( rewrittenBody.contains( ph ) ) {
            String real = creds.resolve( ph );
            if( real != null ) {
              rewrittenBody = rewrittenBody.replace( ph, real );
              bodySwapped = true;
              // issue #824: どの credential の要求だったかを response 側へ渡す。
              if( st.tokenCredName == null ) st.tokenCredName = creds.nameOfPlaceholder( ph );
            }
          }
        }
        swappedBody = rewrittenBody.getBytes( "ISO-8859-1" );
        if( bodySwapped && clIndex >= 0 && swappedBody.length != body.length )
          hdrLines.set( clIndex, "Content-Length: " + swappedBody.length );
        contentLength = swappedBody.length;
      }

      ByteArrayOutputStream hdr = new ByteArrayOutputStream();
      for( String l : hdrLines ) {
        hdr.write( l.getBytes( "ISO-8859-1" ) );
        hdr.write( '\r' ); hdr.write( '\n' );
      }
      hdr.write( '\r' ); hdr.write( '\n' );
      out.write( hdr.toByteArray() );
      if( swappedBody != null ) out.write( swappedBody );
      out.flush();
      st.firstRequestDone.countDown();   // issue #824: response 側の分岐を確定させる
      if( dbg ) {
        if( bodySwapped ) System.err.println( "[mitm] credential placeholder swapped in request BODY" );
        if( swapped ) {
          System.err.println( "[mitm] credential placeholder swapped in request header" );
        } else if( bodySwapped ) {
          // body で置換できたなら header に無いのは正常 (OAuth の token endpoint 等)。
        } else if( credHdrName != null ) {
          // ★ ここが出たら「横取りはできているが置換が効いていない」。
          //   placeholder 形なのに一致しない = guest 側が別のトークンに差し替えている
          //   (例: refresh で取り直した)。placeholder ですらない = そもそも別の credential。
          System.err.println( "[mitm] ★ NOT swapped: header=" + credHdrName
              + " len=" + credHdrLen
              + ( credLooksPh ? " (placeholder の接頭辞は一致するが完全一致しない)"
                              : " (既知の placeholder ではない値)" ) );
        } else {
          System.err.println( "[mitm] (credential を載せた header は無し)" );
        }
      }
      if( upgrade ) {
        // upgrade 後は素通し (response 側は元から raw なので双方向で raw になる)。
        if( dbg ) System.err.println( "[mitm] protocol upgrade -> raw passthrough" );
        copyRaw( in, out );
        return;
      }
      // --- body を raw 転送 (上で読み切った分は転送済み) ---
      if( swappedBody != null ) {
        // 既に書き出し済み。何もしない。
      } else if( chunked ) {
        if( dbg && !creds.isEmpty() ) System.err.println( "[mitm] chunked body は置換対象外 (raw 転送)" );
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
