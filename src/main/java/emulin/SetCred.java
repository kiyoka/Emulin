// ----------------------------------------
//  SetCred — issue #763: credential 設定 CLI ウィザード
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  Pro/Max サブスクリプションユーザ向けに、TLS-MITM (issue #401) の credential ファイル
//   `~/.emulin/credentials` を対話でセットアップする。emulin.bat/emulin.sh の `setcred`
//   サブコマンドから起動:
//     保存済み一覧表示 → 手順表示 (claude setup-token) → トークン貼付 → 疎通テスト
//     (host 側で api に 1 本投げ 401 か否かで有効性判定・claude 実行不要) → 正しいパスへ atomic 保存。
//
//  provider は PROVIDERS 表 (env 変数名 + ラベル) で定義し、そのうち OpenAI Codex 等も
//   同じ枠組みで保存済み一覧に載せられるようにしてある (issue #763)。
//
//  bundle JRE は java.base + java.logging のみ (Swing=java.desktop / java.net.http 無し) なので、
//   GUI/HttpClient を使わず SSLSocket(javax.net.ssl=java.base) + System.in/out で実装する。
//   実キーは host 側 (~/.emulin) のみに保存され、guest には placeholder だけ渡る (#401 の不変条件)。
//   ※ ユーザ向けメッセージは英語 (setcred 実機は英語環境も多いため)。コメントは日本語。
// ----------------------------------------
package emulin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.*;

public class SetCred {

  static final String API_HOST = "api.anthropic.com";
  static final String DOC_URL  =
    "https://code.claude.com/docs/en/authentication  (\"Generate a long-lived token\" / claude setup-token)";

  // 保存済み一覧に載せる provider。{ env 変数名, ラベル, 補足 }。
  //   ※ OpenAI Codex は将来対応 (issue #763)。一覧には出すが setup フローは Claude のみ。
  static final String[][] PROVIDERS = {
    { "CLAUDE_CODE_OAUTH_TOKEN", "Claude (Pro/Max subscription)", ""              },
    { "ANTHROPIC_API_KEY",       "Claude (Console API key)",      ""              },
    { "OPENAI_API_KEY",          "OpenAI Codex",                  "(coming soon)" },
  };

  public static void main( String[] args ) {
    BufferedReader in = new BufferedReader( new InputStreamReader( System.in, StandardCharsets.UTF_8 ) );
    PrintStream o = System.out;
    try {
      File dir  = new File( System.getProperty( "user.home", "." ), ".emulin" );
      File cred = new File( dir, "credentials" );

      o.println();
      o.println( "==== Emulin credential setup (issue #401 network sandbox) ====" );
      o.println();
      o.println( "Your real API token is stored host-side only (" + cred.getPath() + ")." );
      o.println( "The guest (emulin) receives a placeholder only and cannot read the real token." );
      o.println();

      // 保存済み一覧。
      Map<String,String> existing = readCredentials( cred );
      o.println( "Currently saved credentials:" );
      for( String[] p : PROVIDERS ) {
        String v = existing.get( p[0] );
        boolean saved = ( v != null && !v.isEmpty() );
        String mark   = saved ? "[x]" : "[ ]";
        String status = saved ? ( "saved (" + prefix( v ) + "...)" ) : "not set";
        o.println( String.format( "  %s %-30s %-26s %s %s",
                                  mark, p[1], p[0], status, p[2] ) );
      }
      o.println();
      o.println( "--- Add / update a Claude token ---" );
      o.println( "How to get a Pro/Max long-lived token:" );
      o.println( "  1. In another terminal:  claude setup-token" );
      o.println( "  2. Approve in the browser; a long-lived token (sk-ant-oat01-...) is printed" );
      o.println( "  3. Copy that token   (counts within your Max/Pro flat rate; no metered charge)" );
      o.println( "  Note: 'claude /login' does NOT print a token. Use 'claude setup-token'." );
      o.println( "  Docs: " + DOC_URL );
      o.println();
      o.print( "Paste the token and press Enter (empty to cancel): " );
      o.flush();
      String token = in.readLine();
      if( token == null || token.trim().isEmpty() ) { o.println( "Cancelled." ); return; }
      token = token.trim();

      // credential NAME を判定 (OAuth=CLAUDE_CODE_OAUTH_TOKEN / Console API key=ANTHROPIC_API_KEY)。
      String name;
      if( token.startsWith( "sk-ant-oat01-" ) )      name = "CLAUDE_CODE_OAUTH_TOKEN";
      else if( token.startsWith( "sk-ant-api03-" ) ) name = "ANTHROPIC_API_KEY";
      else {
        o.println( "Warning: token is neither 'sk-ant-oat01-' (subscription) nor 'sk-ant-api03-' (Console API key)." );
        o.println( "         Make sure you pasted the output of 'claude setup-token'." );
        o.print( "Continue anyway and save as CLAUDE_CODE_OAUTH_TOKEN? [y/N]: " );
        o.flush();
        String a = in.readLine();
        if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "Cancelled." ); return; }
        name = "CLAUDE_CODE_OAUTH_TOKEN";
      }
      boolean already = existing.containsKey( name );
      o.println( "-> guest env variable: " + name + "  (token prefix " + prefix( token ) + "...)"
                 + ( already ? "  [will overwrite the existing entry]" : "" ) );
      o.println();

      // 疎通テスト (任意)。
      o.print( "Verify this token now (send one request to " + API_HOST + ")? [Y/n]: " );
      o.flush();
      String t = in.readLine();
      if( t == null || !t.trim().toLowerCase().startsWith( "n" ) ) {
        o.print( "  Testing ... " );
        o.flush();
        Result r = connectivityTest( token );
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
      saveCredential( dir, cred, name, token );

      o.println();
      o.println( "Saved: " + cred.getPath() );
      o.println( "  - The real token stays host-side only; the guest gets a placeholder (swapped on the wire)." );
      o.println( "  - Start Emulin like this so claude works transparently:" );
      o.println( "      set EMULIN_EGRESS_MITM=1" );
      o.println( "      set EMULIN_NATIVE_POOL_MB=1024" );
      o.println( "      emulin.bat sshd" );
    } catch( Exception e ) {
      o.println( "Error: " + e );
    }
  }

  static String prefix( String t ) { return t.length() > 16 ? t.substring( 0, 16 ) : t; }

  // ~/.emulin/credentials を NAME->value に読む (# コメント/空行は無視)。無ければ空。
  static Map<String,String> readCredentials( File cred ) {
    Map<String,String> m = new LinkedHashMap<>();
    if( cred == null || !cred.isFile() ) return m;
    try {
      for( String line : Files.readAllLines( cred.toPath(), StandardCharsets.UTF_8 ) ) {
        String s = line.trim();
        if( s.isEmpty() || s.charAt( 0 ) == '#' ) continue;
        int eq = s.indexOf( '=' );
        if( eq <= 0 ) continue;
        m.put( s.substring( 0, eq ).trim(), s.substring( eq + 1 ).trim() );
      }
    } catch( Exception ignore ) {}
    return m;
  }

  static final class Result { final boolean invalid; final String msg; Result( boolean i, String m ){ invalid=i; msg=m; } }

  // host 側 SSLSocket で api.anthropic.com に最小の POST /v1/messages を 1 本投げる。
  //   401/403 = token rejected (invalid)。それ以外 (200/400 等) = 認証は通った (=valid。
  //   ヘッダ/model 名の細部がズレても、API は auth を先に検証するので 401 か否かで判定できる)。
  //   接続不可/タイムアウト = ネットワーク不通として区別。claude 実行不要。
  static Result connectivityTest( String token ) {
    SSLSocket sock = null;
    try {
      sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
      sock.connect( new InetSocketAddress( API_HOST, 443 ), 10000 );
      sock.setSoTimeout( 15000 );
      SSLParameters p = sock.getSSLParameters();
      p.setApplicationProtocols( new String[]{ "http/1.1" } );
      p.setServerNames( Collections.singletonList( new SNIHostName( API_HOST ) ) );
      sock.setSSLParameters( p );
      sock.startHandshake();
      byte[] body = ( "{\"model\":\"claude-3-5-haiku-20241022\",\"max_tokens\":1,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}" )
                    .getBytes( StandardCharsets.UTF_8 );
      StringBuilder req = new StringBuilder();
      req.append( "POST /v1/messages?beta=true HTTP/1.1\r\n" );
      req.append( "Host: " ).append( API_HOST ).append( "\r\n" );
      req.append( "Authorization: Bearer " ).append( token ).append( "\r\n" );
      req.append( "anthropic-version: 2023-06-01\r\n" );
      req.append( "content-type: application/json\r\n" );
      req.append( "content-length: " ).append( body.length ).append( "\r\n" );
      req.append( "connection: close\r\n\r\n" );
      OutputStream os = sock.getOutputStream();
      os.write( req.toString().getBytes( StandardCharsets.ISO_8859_1 ) );
      os.write( body );
      os.flush();
      BufferedReader r = new BufferedReader( new InputStreamReader( sock.getInputStream(), StandardCharsets.ISO_8859_1 ) );
      String status = r.readLine();
      if( status == null ) return new Result( false, "? no response (cannot determine)" );
      int code = -1;
      String[] parts = status.split( " " );
      if( parts.length >= 2 ) try { code = Integer.parseInt( parts[1] ); } catch( Exception ignore ) {}
      if( code == 401 || code == 403 )
        return new Result( true, "REJECTED: token was not accepted (" + status.trim() + ")" );
      if( code > 0 )
        return new Result( false, "OK: token is valid (" + status.trim() + ")" );
      return new Result( false, "? cannot determine (" + status.trim() + ")" );
    } catch( Exception e ) {
      return new Result( false, "? network/connection error (" + e + ") -- could not verify the token" );
    } finally {
      if( sock != null ) try { sock.close(); } catch( Exception ignore ) {}
    }
  }

  // ~/.emulin/credentials の該当 NAME= 行を更新/追加し、他の行 (他 credential・コメント) は保持。
  //   atomic (tmp + Files.move) 書き込み。best-effort で owner-only 権限 (POSIX)。
  static void saveCredential( File dir, File cred, String name, String token ) throws Exception {
    if( !dir.isDirectory() ) dir.mkdirs();
    List<String> lines = new ArrayList<>();
    boolean replaced = false;
    if( cred.isFile() ) {
      for( String line : Files.readAllLines( cred.toPath(), StandardCharsets.UTF_8 ) ) {
        String s = line.trim();
        if( !s.isEmpty() && s.charAt( 0 ) != '#' ) {
          int eq = s.indexOf( '=' );
          if( eq > 0 && s.substring( 0, eq ).trim().equals( name ) ) {
            lines.add( name + "=" + token ); replaced = true; continue;
          }
        }
        lines.add( line );
      }
    }
    if( !replaced ) {
      if( lines.isEmpty() ) lines.add( "# Emulin credential store (host only; blocked from the guest)" );
      lines.add( name + "=" + token );
    }
    StringBuilder sb = new StringBuilder();
    for( String l : lines ) sb.append( l ).append( '\n' );
    File tmp = new File( dir, "credentials.emulin-tmp" );
    Files.write( tmp.toPath(), sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    try { tmp.setReadable( false, false ); tmp.setReadable( true, true );
          tmp.setWritable( false, false ); tmp.setWritable( true, true ); } catch( Exception ignore ) {}
    try {
      Files.move( tmp.toPath(), cred.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
    } catch( java.nio.file.AtomicMoveNotSupportedException e ) {
      Files.move( tmp.toPath(), cred.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
  }
}
