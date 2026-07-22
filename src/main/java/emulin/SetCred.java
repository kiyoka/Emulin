// ----------------------------------------
//  SetCred — issue #763: credential 設定 CLI ウィザード
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  Pro/Max サブスクリプションユーザ向けに、TLS-MITM (issue #401) の credential ファイル
//   `~/.emulin/credentials` を対話でセットアップする。emulin.bat/emulin.sh の `setcred`
//   サブコマンドから起動:
//     手順表示 (claude setup-token) → トークン貼付 → 疎通テスト (host 側で api に 1 本投げて
//     401 か否かで有効性判定・claude 実行不要) → 正しいパスへ atomic 保存。
//
//  bundle JRE は java.base + java.logging のみ (Swing=java.desktop / java.net.http 無し) なので、
//   GUI/HttpClient を使わず、SSLSocket(javax.net.ssl=java.base) + System.in/out で実装する。
//   実キーは host 側 (~/.emulin) のみに保存され、guest には placeholder だけ渡る (#401 の不変条件)。
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

  public static void main( String[] args ) {
    BufferedReader in = new BufferedReader( new InputStreamReader( System.in, StandardCharsets.UTF_8 ) );
    PrintStream o = System.out;
    try {
      o.println();
      o.println( "==== Emulin credential setup (issue #401 通信サンドボックス) ====" );
      o.println();
      o.println( "Pro/Max サブスクリプションの長期トークンを保存します。" );
      o.println( "実トークンは host 側 (~/.emulin/credentials) だけに保存され、" );
      o.println( "guest(emulin) には placeholder だけが渡ります (guest からは読めません)。" );
      o.println();
      o.println( "[トークンの取り方]" );
      o.println( "  1. 別のターミナルで:   claude setup-token" );
      o.println( "  2. ブラウザで承認すると、長期トークン (sk-ant-oat01-...) が画面に表示されます" );
      o.println( "  3. そのトークンをコピー   (Max/Pro の定額内。従量課金は発生しません)" );
      o.println( "  ※ 'claude /login' では画面にトークンが出ません。必ず setup-token を使ってください。" );
      o.println( "  ドキュメント: " + DOC_URL );
      o.println();
      o.print( "トークンを貼り付けて Enter (空 Enter で中止): " );
      o.flush();
      String token = in.readLine();
      if( token == null || token.trim().isEmpty() ) { o.println( "中止しました。" ); return; }
      token = token.trim();

      // credential NAME を判定 (OAuth=CLAUDE_CODE_OAUTH_TOKEN / Console API key=ANTHROPIC_API_KEY)。
      String name;
      if( token.startsWith( "sk-ant-oat01-" ) )      name = "CLAUDE_CODE_OAUTH_TOKEN";
      else if( token.startsWith( "sk-ant-api03-" ) ) name = "ANTHROPIC_API_KEY";
      else {
        o.println( "警告: 'sk-ant-oat01-'(サブスク) でも 'sk-ant-api03-'(Console API key) でもありません。" );
        o.println( "      setup-token の出力を貼り間違えていないか確認してください。" );
        o.print( "それでも CLAUDE_CODE_OAUTH_TOKEN として続行しますか? [y/N]: " );
        o.flush();
        String a = in.readLine();
        if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "中止しました。" ); return; }
        name = "CLAUDE_CODE_OAUTH_TOKEN";
      }
      o.println( "→ guest env 変数名: " + name + "  (トークン先頭 " + prefix( token ) + "...)" );
      o.println();

      // 疎通テスト (任意)。
      o.print( "疎通テスト (host から " + API_HOST + " に 1 本投げて有効性を確認) しますか? [Y/n]: " );
      o.flush();
      String t = in.readLine();
      if( t == null || !t.trim().toLowerCase().startsWith( "n" ) ) {
        o.print( "  テスト中 ... " );
        o.flush();
        Result r = connectivityTest( token );
        o.println( r.msg );
        if( r.invalid ) {
          o.print( "トークンが拒否されました。それでも保存しますか? [y/N]: " );
          o.flush();
          String a = in.readLine();
          if( a == null || !a.trim().toLowerCase().startsWith( "y" ) ) { o.println( "中止しました。" ); return; }
        }
      }
      o.println();

      // 保存。
      File dir  = new File( System.getProperty( "user.home", "." ), ".emulin" );
      File cred = new File( dir, "credentials" );
      o.print( "保存先: " + cred.getPath() + "\n  ここに保存しますか? [Y/n]: " );
      o.flush();
      String s = in.readLine();
      if( s != null && s.trim().toLowerCase().startsWith( "n" ) ) { o.println( "保存せず終了。" ); return; }
      saveCredential( dir, cred, name, token );

      o.println();
      o.println( "保存しました: " + cred.getPath() );
      o.println( "  - 実トークンは host 側だけ。guest には placeholder のみ (wire 上でだけ swap)。" );
      o.println( "  - 次のように起動すると claude が透過動作します:" );
      o.println( "      set EMULIN_EGRESS_MITM=1" );
      o.println( "      set EMULIN_NATIVE_POOL_MB=1024" );
      o.println( "      emulin.bat sshd" );
    } catch( Exception e ) {
      o.println( "エラー: " + e );
    }
  }

  static String prefix( String t ) { return t.length() > 16 ? t.substring( 0, 16 ) : t; }

  static final class Result { final boolean invalid; final String msg; Result( boolean i, String m ){ invalid=i; msg=m; } }

  // host 側 SSLSocket で api.anthropic.com に最小の POST /v1/messages を 1 本投げる。
  //   401/403 = トークン拒否 (無効)。それ以外 (200/400 等) = 認証は通った (=トークン有効。
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
      if( status == null ) return new Result( false, "? 応答がありません (判定不能)" );
      int code = -1;
      String[] parts = status.split( " " );
      if( parts.length >= 2 ) try { code = Integer.parseInt( parts[1] ); } catch( Exception ignore ) {}
      if( code == 401 || code == 403 )
        return new Result( true, "NG: トークンが拒否されました (" + status.trim() + ")" );
      if( code > 0 )
        return new Result( false, "OK: トークンは有効です (" + status.trim() + ")" );
      return new Result( false, "? 判定不能 (" + status.trim() + ")" );
    } catch( Exception e ) {
      return new Result( false, "? ネットワーク/接続エラー (" + e + ") — トークンの有効性は判定できませんでした" );
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
      if( lines.isEmpty() ) lines.add( "# Emulin credential store (host only; guest からは遮断される)" );
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
