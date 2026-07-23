// ----------------------------------------
//  SetCred — issue #763: credential 設定 CLI ウィザード
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  Pro/Max サブスクリプションユーザ向けに、TLS-MITM (issue #401) の credential ファイル
//   `~/.emulin/credentials` を対話でセットアップする。emulin.bat/emulin.sh の `setcred`
//   サブコマンドから起動:
//     保存済み一覧表示 → provider 選択 → その provider 固有の取り方手順 → トークン貼付 →
//     疎通テスト (host 側で api に 1 本投げ 401 か否かで有効性判定・claude 実行不要) → atomic 保存。
//
//  provider は PROVIDERS 表 (保存済み一覧用) と SETTABLE 表 (今 setup できるもの) で定義。
//   MITM 先の host は CredentialStore.NAME_HOSTS が持つ (credential 名 → 送り先)。
//   OpenAI Codex は実機 MITM 未検証なので今は coming soon (一覧には出すが選択肢にしない)。
//
//  bundle JRE は java.base + java.logging のみ (Swing=java.desktop / java.net.http 無し) なので、
//   GUI/HttpClient を使わず SSLSocket(javax.net.ssl=java.base) + System.in/out で実装する。
//   実キーは host 側 (~/.emulin) のみに保存され、guest には placeholder だけ渡る (#401 の不変条件)。
//   ※ ユーザ向けメッセージは英語。コメントは日本語。
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

  // 保存済み一覧に載せる provider。{ env 変数名, ラベル, 補足 }。
  static final String[][] PROVIDERS = {
    { "CLAUDE_CODE_OAUTH_TOKEN", "Claude (Pro/Max subscription)", ""              },
    { "ANTHROPIC_API_KEY",       "Claude (Console API key)",      ""              },
    { "OPENAI_API_KEY",          "OpenAI Codex",                  "(coming soon)" },
  };

  // 今 setup できる provider。それぞれ固有の「取り方」手順 + 期待 prefix を持つ。
  //   ※ OpenAI Codex は MITM allowlist 未対応なのでここには入れない (issue #763)。
  static final Provider[] SETTABLE = {
    new Provider( "CLAUDE_CODE_OAUTH_TOKEN", "Claude (Pro/Max subscription)", "sk-ant-oat01-", new String[]{
      "How to get a Pro/Max long-lived token:",
      "  1. In another terminal:  claude setup-token",
      "  2. Approve in the browser; a long-lived token (sk-ant-oat01-...) is printed",
      "  3. Copy that token   (counts within your Max/Pro flat rate; no metered charge)",
      "  Note: 'claude /login' does NOT print a token. Use 'claude setup-token'.",
      "  Docs: https://code.claude.com/docs/en/authentication  (\"Generate a long-lived token\")",
    } ),
    new Provider( "ANTHROPIC_API_KEY", "Claude (Console API key)", "sk-ant-api03-", new String[]{
      "How to get a Console API key (pay-per-use; separate from a Pro/Max subscription):",
      "  1. Open  https://platform.claude.com/settings/keys   (Anthropic Console)",
      "  2. Create Key, then copy it (sk-ant-api03-...)",
      "  Note: billed per use, NOT included in a Pro/Max subscription.",
    } ),
  };

  static final class Provider {
    final String env, label, prefix; final String[] howto;
    Provider( String env, String label, String prefix, String[] howto ) {
      this.env = env; this.label = label; this.prefix = prefix; this.howto = howto;
    }
  }

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
        o.println( String.format( "  %s %-30s %-26s %s %s", mark, p[1], p[0], status, p[2] ) );
      }
      o.println();

      // provider 選択メニュー。
      o.println( "Which credential do you want to set up?" );
      for( int i = 0; i < SETTABLE.length; i++ )
        o.println( "  [" + ( i + 1 ) + "] " + SETTABLE[i].label );
      o.println( "  (OpenAI Codex: coming soon -- needs MITM support for api.openai.com)" );
      o.print( "Choose [1-" + SETTABLE.length + ", empty to cancel]: " );
      o.flush();
      String c = in.readLine();
      if( c == null || c.trim().isEmpty() ) { o.println( "Cancelled." ); return; }
      int idx = -1;
      try { idx = Integer.parseInt( c.trim() ) - 1; } catch( Exception ignore ) {}
      if( idx < 0 || idx >= SETTABLE.length ) { o.println( "Invalid choice. Cancelled." ); return; }
      Provider sel = SETTABLE[idx];

      // 選択した provider 固有の取り方手順。
      o.println();
      o.println( "--- " + sel.label + " ---" );
      for( String line : sel.howto ) o.println( line );
      o.println();
      o.print( "Paste the token and press Enter (empty to cancel): " );
      o.flush();
      String token = in.readLine();
      if( token == null || token.trim().isEmpty() ) { o.println( "Cancelled." ); return; }
      token = token.trim();

      // prefix 検証 (選択 provider の期待 prefix と違えば警告)。
      if( !token.startsWith( sel.prefix ) ) {
        o.println( "Warning: token does not start with '" + sel.prefix + "' (expected for " + sel.label + ")." );
        o.print( "Save it to " + sel.env + " anyway? [y/N]: " );
        o.flush();
        String a = in.readLine();
        if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "Cancelled." ); return; }
      }
      boolean already = existing.containsKey( sel.env );
      o.println( "-> guest env variable: " + sel.env + "  (token prefix " + prefix( token ) + "...)"
                 + ( already ? "  [will overwrite the existing entry]" : "" ) );
      o.println();

      // 疎通テスト (任意)。api.anthropic.com への Bearer 認証で 401 か否か。
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
      saveCredential( dir, cred, sel.env, token );

      o.println();
      o.println( "Saved: " + cred.getPath() + "  (" + sel.env + ")" );
      o.println( "  - The real token stays host-side only; the guest gets a placeholder (swapped on the wire)." );
      o.println( "  - Nothing else to set up: the credential sandbox turns itself on for "
                 + CredentialStore.hostFor( sel.env ) + " at the next start." );
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
      // レスポンス (header + body) を少し読んで error type/message のヒントを得る (connection: close
      //   なので EOF まで、上限行数で cap)。API は auth を最初に検証し、無効トークンは 401 +
      //   authentication_error を返す。200 以外 (404/400 等) でも 401/403 でなければ「認証は通った
      //   =トークン有効」を意味する (test request の endpoint/形が違うだけ)。
      StringBuilder rest = new StringBuilder();
      try { String ln; int n = 0; while( ( ln = r.readLine() ) != null && n++ < 80 ) rest.append( ln ).append( '\n' ); }
      catch( Exception ignore ) {}
      String low  = rest.toString().toLowerCase();
      String hint = extractMessage( rest.toString() );
      boolean authErr = code == 401 || code == 403
                     || low.contains( "authentication_error" )
                     || low.contains( "invalid bearer" ) || low.contains( "invalid x-api-key" );
      if( authErr )
        return new Result( true, "REJECTED: the token was NOT accepted -- invalid or expired (" + status.trim() + ")"
                                 + ( hint.isEmpty() ? "" : " -- " + hint ) );
      if( code == 200 )
        return new Result( false, "OK: the token is valid (HTTP 200)" );
      if( code > 0 )
        return new Result( false, "OK: the token is valid -- it authenticated with the API. "
                                 + "(The minimal test request itself returned " + status.trim()
                                 + ", which is not an authentication error"
                                 + ( hint.isEmpty() ? "" : "; " + hint ) + ".)" );
      return new Result( false, "? cannot determine (" + status.trim() + ")" );
    } catch( Exception e ) {
      return new Result( false, "? network/connection error (" + e + ") -- could not verify the token" );
    } finally {
      if( sock != null ) try { sock.close(); } catch( Exception ignore ) {}
    }
  }

  // JSON body から "message":"..." を粗く 1 つ抜き出す (診断ヒント用。escape は無視・切詰め)。
  static String extractMessage( String body ) {
    int i = body.indexOf( "\"message\"" );
    if( i < 0 ) return "";
    int c = body.indexOf( ':', i );       if( c  < 0 ) return "";
    int q1 = body.indexOf( '"', c + 1 );  if( q1 < 0 ) return "";
    int q2 = body.indexOf( '"', q1 + 1 ); if( q2 < 0 ) return "";
    String m = body.substring( q1 + 1, q2 ).trim();
    return m.length() > 140 ? m.substring( 0, 140 ) + "..." : m;
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
