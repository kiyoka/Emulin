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

//  ★ issue #934: 診断は **SyscallAmd64.TRACE_OUT** へ出す (System.err 直書きにしない)。
//    EMULIN_TRACE_FILE は「TUI を壊さずに診断を採る」ために 0.8.2 で入れた仕組みだが、
//    credential サンドボックス側だけ System.err のままで、**画面に出てファイルに落ちない**
//    状態だった (実機で claude remote-control の TUI が [mitm] で埋まった)。
//    「同じ仕組みが N 箇所にあり 1 箇所だけ直っていない」型。
public class TlsMitmProxy {

  private final EmulinCA        ca;
  private final CredentialStore creds;
  private final boolean         dbg = System.getenv("EMULIN_TRACE_MITM") != null;
  /** issue #773 (B): body 内 placeholder を置換する上限。これを超える body は raw 転送。
   *  credential は小さな JSON/form body に載るので、大きな upload を握らないための線引き。 */
  static final int BODY_SWAP_MAX = 256 * 1024;

  /** issue #935: token 応答を回転できず**遮断した**回数。0 以外なら credential の
   *  再登録が要る (終了時サマリで知らせる)。 */
  static final java.util.concurrent.atomic.AtomicLong tokenRotateBlocked =
      new java.util.concurrent.atomic.AtomicLong();

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
    if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] proxy listening on 127.0.0.1:" + port );
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
        if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] client ALPN offer=" + protos );
        return protos.contains( "http/1.1" ) ? "http/1.1" : null;
      } );
      guest.startHandshake();
      String sni = extractSni( guest );
      if( sni == null ) { if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] no SNI, drop" ); guest.close(); return; }
      if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] guest TLS ok, SNI=" + sni + " ALPN=" + guest.getApplicationProtocol() );
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
        if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] guest sent nothing within "
            + ( FIRST_BYTE_WAIT_MS / 1000 ) + "s, closing (upstream was never dialed)" );
        return;
      }
      try { guest.setSoTimeout( soBak ); } catch( Throwable ignore ) {}
      if( firstByte < 0 ) {   // guest が何も送らずに閉じた = 上流に繋ぐ必要は無い
        if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] guest closed before sending a request" );
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
      if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] upstream TLS ok -> " + sni );
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
        SyscallAmd64.TRACE_OUT.println( cz );
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
      boolean chunked = false, encoded = false;
      while( true ) {
        String line = readLine( in );
        if( line == null ) return;          // EOF: 何も書かずに raw へ委ねる
        if( line.isEmpty() ) break;         // header 終端
        String low = line.toLowerCase( Locale.ROOT );
        if( low.startsWith( "content-length:" ) ) {
          try { contentLength = Long.parseLong( line.substring( line.indexOf(':')+1 ).trim() ); } catch( Exception ignore ) {}
        } else if( low.startsWith( "transfer-encoding:" ) && low.contains( "chunked" ) ) {
          chunked = true;
        } else if( low.startsWith( "content-encoding:" ) && !low.contains( "identity" ) ) {
          encoded = true;                   // gzip 等。文字列置換が効かない
        }
        lines.add( line );
      }

      byte[] body = null;
      String why = null;
      if( encoded ) {
        why = "Content-Encoding つき (圧縮) の応答";
      } else if( chunked ) {
        body = readChunkedBody( in, BODY_SWAP_MAX );
        if( body == null ) why = "chunked body を読み切れない (" + BODY_SWAP_MAX + " byte 超過か形式不正)";
      } else if( contentLength > BODY_SWAP_MAX ) {
        why = "body が大きすぎる (" + contentLength + " byte)";
      } else if( contentLength >= 0 ) {
        body = readN( in, (int)contentLength );
      } else {
        why = "Content-Length も chunked も無い応答";
      }

      if( body == null ) {
        // ★★ issue #935: ここを素通しすると **回転後の実トークンがそのまま guest に届き、
        //   guest のファイルに保存される** = #401 の不変条件 (実キーは host 側のみ) が破れる。
        //   実際 0.8.2 では chunked の token 応答を素通ししており、guest の
        //   ~/.claude/.credentials.json に実物の access/refresh が書き込まれていた
        //   (しかも guest 側は「動いている」ので誰も気付けない)。
        //   → **fail closed**。漏らして動くより、止めて気付ける方を選ぶ。
        tokenRotateBlocked.incrementAndGet();
        SyscallAmd64.TRACE_OUT.println( "[mitm] ★ token 応答を回転できません (" + why + ")。"
            + "実トークンを guest に渡さないため、この応答を遮断しました。" );
        SyscallAmd64.TRACE_OUT.println( "[mitm]   → upstream 側で token は既に回転済みの可能性が高く、"
            + "host の credential は無効になっています。再ログインして setcred をやり直してください。" );
        writeGatewayError( out );
        return;
      }

      String bs = new String( body, java.nio.charset.StandardCharsets.UTF_8 );
      String rewritten = rotateTokensInJson( bs, credName );
      byte[] outBody = ( rewritten != null ? rewritten : bs )
                         .getBytes( java.nio.charset.StandardCharsets.UTF_8 );
      // ★ chunked を読み切って結合したので、返すときは Content-Length に付け替える
      //   (Transfer-Encoding を残したまま平文 body を返すと framing が壊れる)。
      StringBuilder hb = new StringBuilder();
      for( String l : lines ) {
        String low = l.toLowerCase( Locale.ROOT );
        if( low.startsWith( "transfer-encoding:" ) || low.startsWith( "content-length:" ) ) continue;
        hb.append( l ).append( "\r\n" );
      }
      hb.append( "Content-Length: " ).append( outBody.length ).append( "\r\n\r\n" );
      out.write( hb.toString().getBytes( "ISO-8859-1" ) );
      out.write( outBody );
      out.flush();
    } catch( Exception e ) {
      if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] token 応答の解析に失敗 (以後は素通し): " + e );
    }
  }

  /** issue #935: chunked body を読み切って結合する。上限超過・形式不正なら null。 */
  private byte[] readChunkedBody( InputStream in, int max ) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    while( true ) {
      String sizeLine = readLine( in );
      if( sizeLine == null ) return null;
      String t = sizeLine.trim();
      int semi = t.indexOf( ';' );          // chunk extension は捨てる
      if( semi >= 0 ) t = t.substring( 0, semi ).trim();
      int n;
      try { n = Integer.parseInt( t, 16 ); } catch( Exception e ) { return null; }
      if( n == 0 ) {
        while( true ) { String tr = readLine( in ); if( tr == null || tr.isEmpty() ) break; }  // trailer
        return buf.toByteArray();
      }
      if( buf.size() + n > max ) return null;
      byte[] c = readN( in, n );
      if( c == null || c.length != n ) return null;
      buf.write( c );
      readLine( in );                        // chunk 後の CRLF
    }
  }

  /** issue #935: 回転できない token 応答の代わりに返すエラー。guest 側には認証失敗として
   *  見えるが、**実トークンは渡さない**。上の警告と対で読むこと。 */
  private void writeGatewayError( OutputStream out ) throws IOException {
    String body = "{\"error\":\"emulin_credential_sandbox\","
        + "\"error_description\":\"Emulin blocked this token response because it could not"
        + " swap the real credential out. See the [mitm] warning on the host console.\"}";
    byte[] b = body.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    StringBuilder h = new StringBuilder();
    h.append( "HTTP/1.1 502 Bad Gateway\r\n" )
     .append( "Content-Type: application/json\r\n" )
     .append( "Content-Length: " ).append( b.length ).append( "\r\n" )
     .append( "Connection: close\r\n\r\n" );
    out.write( h.toString().getBytes( "ISO-8859-1" ) );
    out.write( b );
    out.flush();
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
    if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] token 応答: " + rotated
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
      boolean credHdrHasMarker = false;   // issue #934
      String  credRealName = null;        // issue #934: 実キーが載っていたらその名前
      // issue #773 (B) 診断: 「置換されなかった」ときに理由が分かるようにする。
      //   ★ 値そのものは絶対に出さない (header 名と長さ、先頭が既知 placeholder の
      //     接頭辞と一致するかだけを見る)。
      String credHdrName = null; int credHdrLen = 0; boolean credLooksPh = false;
      while( true ) {
        String line = readLine( in );
        if( dbg && first ) SyscallAmd64.TRACE_OUT.println( "[mitm] h1 first request line=" + ( line == null ? "<null/EOF>" : line ) );
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
        //   issue #934: marker (emph01) の有無だけは覚える。実トークンか placeholder かの
        //   区別がこれで付く (値は保持しない)。
        if( dbg && credHdrName == null ) {
          int colon = line.indexOf( ':' );
          if( colon > 0 ) {
            String nm  = line.substring( 0, colon ).trim();
            String val = line.substring( colon + 1 ).trim();
            if( val.contains( "emph01" ) ) credHdrHasMarker = true;
            // issue #934 (診断): 実キーそのものが載っていないか (値は出さない)。
            if( credRealName == null ) credRealName = creds.realCredentialInside( val );
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
        if( basicSwapped != null ) {
          rewritten = basicSwapped; swapped = true;
          // ★ Basic は Bearer と**別経路**なので、トレースも分けておく。
          //   共通の "swapped in request header" だけだと、git push が 401 のとき
          //   「Basic の decode/再 encode が動いたのか」が切り分けられない。
          if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] credential placeholder swapped in Basic auth (git HTTPS)" );
        }
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

      // ★ issue #935: body に credential が載った要求 (= OAuth の token endpoint) は、
      //   応答を**必ず平文で受け取る**必要がある。gzip で返されると JSON の文字列置換が
      //   効かず、回転できないまま実トークンが guest に届く (遮断すると guest が壊れる)。
      //   Accept-Encoding を落として identity で返させる。要求は 1 往復・小さいので損は無い。
      if( bodySwapped ) {
        for( java.util.Iterator<String> it = hdrLines.iterator(); it.hasNext(); ) {
          if( it.next().toLowerCase( Locale.ROOT ).startsWith( "accept-encoding:" ) ) it.remove();
        }
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
        if( bodySwapped ) SyscallAmd64.TRACE_OUT.println( "[mitm] credential placeholder swapped in request BODY" );
        if( swapped ) {
          SyscallAmd64.TRACE_OUT.println( "[mitm] credential placeholder swapped in request header" );
        } else if( bodySwapped ) {
          // body で置換できたなら header に無いのは正常 (OAuth の token endpoint 等)。
        } else if( credHdrName != null ) {
          // ★ ここが出たら「横取りはできているが置換が効いていない」。
          //   placeholder 形なのに一致しない = guest 側が別のトークンに差し替えている
          //   (例: refresh で取り直した)。placeholder ですらない = そもそも別の credential。
          // ★ issue #934: 「接頭辞が一致」だけでは **実トークンと placeholder を区別できない**
          //   (実物も placeholder も sk-ant-oat01- で始まる)。Emulin の marker (emph01) の
          //   有無を出す。marker 無し = **guest が実トークンを持っている** = 別経路で入手した
          //   ことを意味し、サンドボックスの穴を探す手掛かりになる。値そのものは出さない。
          SyscallAmd64.TRACE_OUT.println( "[mitm] ★ NOT swapped: header=" + credHdrName
              + " len=" + credHdrLen
              + " marker=" + ( credHdrHasMarker ? "emph01 あり (Emulin の placeholder 系)"
                                                : "**無し = 実トークンの可能性**" )
              + ( credRealName != null ? " ★★ host の実キー (" + credRealName + ") そのものが guest から送られている"
                                       : " (host の既知実キーとは別の値)" )
              + ( credLooksPh ? " (既知 placeholder と接頭辞のみ一致)"
                              : " (既知の placeholder ではない値)" ) );
        } else {
          SyscallAmd64.TRACE_OUT.println( "[mitm] (credential を載せた header は無し)" );
        }
      }
      if( upgrade ) {
        // upgrade 後は素通し (response 側は元から raw なので双方向で raw になる)。
        if( dbg ) SyscallAmd64.TRACE_OUT.println( "[mitm] protocol upgrade -> raw passthrough" );
        copyRaw( in, out );
        return;
      }
      // --- body を raw 転送 (上で読み切った分は転送済み) ---
      if( swappedBody != null ) {
        // 既に書き出し済み。何もしない。
      } else if( chunked ) {
        if( dbg && !creds.isEmpty() ) SyscallAmd64.TRACE_OUT.println( "[mitm] chunked body は置換対象外 (raw 転送)" );
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
