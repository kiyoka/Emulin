// ----------------------------------------
//  CredentialStore — issue #401 Phase 1: 実キーの host 側管理 + placeholder 注入
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  #401 invariant: 実 API キーは host 側 (sandbox 外) のみに保持し、guest env には
//   placeholder だけを注入する。MITM (TlsMitmProxy) が wire 上の placeholder を
//   実キーに swap するので、guest (compromise しても) は実キーを得られない。
//
//  discovery: host env `EMULIN_CRED_<NAME>=<realkey>` を走査し、guest env `<NAME>` に
//   placeholder を入れる。例: host `EMULIN_CRED_ANTHROPIC_API_KEY=sk-ant-...`
//   → guest `ANTHROPIC_API_KEY=sk-ant-emph01-<hex>` (placeholder)。
//   併せて host 側 credential ファイル `~/.emulin/credentials` (NAME=value 行) からも
//   読み込む。env に実キーを置きたくない (process listing / shell 履歴に乗る) ユーザ向け。
//   このファイルは Mount 層で guest から遮断される (Windows drive mount 越しの読取防止)。
// ----------------------------------------
package emulin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;

public class CredentialStore {

  public static final String HOST_PREFIX = "EMULIN_CRED_";

  // credential 名 → その credential を送る相手 (MITM 対象 host)。
  //   MITM は「placeholder を実キーに戻す」ためだけに張るので、credential が設定されて
  //   いない相手を横取りする理由が無い。ここから allowlist を作ることで、credential を
  //   1 つも設定していなければ TLS 終端は一切起こらない (既存挙動と完全に同じ) し、
  //   claude.ai の OAuth や statsig のテレメトリも素通しのままになる。
  private static final String[][] NAME_HOSTS = {
    { "ANTHROPIC_API_KEY",       "api.anthropic.com" },
    { "CLAUDE_CODE_OAUTH_TOKEN", "api.anthropic.com" },
    { "OPENAI_API_KEY",          "api.openai.com"    },
  };

  // 未知の名前は null (= MITM 先が分からない)。呼び側が警告する。
  public static String hostFor( String name ) {
    if( name == null ) return null;
    for( String[] e : NAME_HOSTS ) if( e[0].equals( name ) ) return e[1];
    return null;
  }

  // 既知の credential 名 (NAME_HOSTS の distinct、登録順)。起動時の保存状況表示に使う。
  public static java.util.List<String> knownNames() {
    java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
    for( String[] e : NAME_HOSTS ) s.add( e[0] );
    return new java.util.ArrayList<>( s );
  }

  // guest env 変数名 → placeholder
  private final Map<String,String> envToPlaceholder = new LinkedHashMap<>();
  // placeholder → 実キー (host 側のみ。guest には絶対渡さない)
  private final Map<String,String> placeholderToReal = new HashMap<>();
  // 変数名 → 登録日時 (ISO 8601、credentials.json の savedAt。env 由来は null)
  private final Map<String,String> savedAt = new LinkedHashMap<>();
  private final SecureRandom       rng = new SecureRandom();

  // host env から `EMULIN_CRED_<NAME>=<realkey>` を auto-discover する。
  public void discoverFromHostEnv() {
    discoverFrom( System.getenv() );
  }

  // テスト/明示注入用。
  public void discoverFrom( Map<String,String> hostEnv ) {
    for( Map.Entry<String,String> e : hostEnv.entrySet() ) {
      String k = e.getKey();
      if( k == null || !k.startsWith( HOST_PREFIX ) ) continue;
      add( k.substring( HOST_PREFIX.length() ), e.getValue() );
    }
  }

  // host 側 credential ファイル (`~/.emulin/credentials.json`) を読み込む (issue #774)。
  //   env と同じく guest env `<NAME>` に placeholder を入れ、実値と登録日時 (savedAt) は
  //   host 側のみ保持する。同名が env にもあれば env が override する
  //   (Egress が file → env の順に呼ぶ)。schema:
  //     { "version":1, "credentials": { "NAME": {"value":"...","savedAt":"ISO8601"} } }
  public void discoverFromFile( File f ) {
    if( f == null || !f.isFile() ) return;
    warnIfGroupOrWorldReadable( f );
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      Object root = MiniJson.parse( text );
      Object creds = ( root instanceof Map ) ? ((Map<?,?>)root).get( "credentials" ) : null;
      if( !( creds instanceof Map ) ) return;
      for( Map.Entry<?,?> e : ((Map<?,?>)creds).entrySet() ) {
        Object entry = e.getValue();
        if( !( entry instanceof Map ) ) continue;
        Object val = ((Map<?,?>)entry).get( "value" );
        Object sv  = ((Map<?,?>)entry).get( "savedAt" );
        if( val == null ) continue;
        String name = String.valueOf( e.getKey() );
        add( name, String.valueOf( val ) );
        if( sv != null ) savedAt.put( name, String.valueOf( sv ) );
      }
    } catch( Exception e ) {
      System.err.println( "[cred] credential file read failed: " + e );
    }
  }

  // 登録日時 (ISO 8601)。未登録 / env 由来 / 旧データは null。
  public String savedAtOf( String name ) { return savedAt.get( name ); }

  // name→real を 1 件登録し placeholder を割り当てる。同名の再登録は placeholder を
  //   維持したまま real だけ更新する (env が file を override するため)。
  private void add( String name, String real ) {
    if( name == null || name.isEmpty() || real == null || real.isEmpty() ) return;
    String ph = envToPlaceholder.get( name );
    if( ph == null ) {
      ph = makePlaceholder( rng );
      envToPlaceholder.put( name, ph );
    }
    placeholderToReal.put( ph, real );
  }

  // POSIX で group/other 読取可なら警告する (実キー平文なので 0600 推奨)。
  //   Windows (POSIX view 無し) では user profile の ACL に委ねる。
  private static void warnIfGroupOrWorldReadable( File f ) {
    try {
      Set<PosixFilePermission> perms =
        java.nio.file.Files.getPosixFilePermissions( f.toPath() );
      if( perms.contains( PosixFilePermission.GROUP_READ )
          || perms.contains( PosixFilePermission.OTHERS_READ ) ) {
        System.err.println( "[cred] warning: " + f
          + " is group/other readable; chmod 600 recommended (holds real key)" );
      }
    } catch( UnsupportedOperationException ignore ) {
      // Windows 等 POSIX view 無し
    } catch( Exception ignore ) {}
  }

  // guest env (envList) に placeholder のみ追加する。実キーは入れない。
  public void injectPlaceholders( List<String> guestEnv ) {
    for( Map.Entry<String,String> e : envToPlaceholder.entrySet() )
      guestEnv.add( e.getKey() + "=" + e.getValue() );
  }

  // MITM が wire 上の placeholder を実キーに swap する。未知なら null。
  public String resolve( String placeholder ) { return placeholderToReal.get( placeholder ); }

  // MITM が request (header/body) を scan する対象の placeholder 集合。
  public Set<String> placeholders() { return Collections.unmodifiableSet( placeholderToReal.keySet() ); }

  public boolean isEmpty() { return placeholderToReal.isEmpty(); }

  // 設定済み credential の名前 (登録順)。
  public Set<String> names() { return Collections.unmodifiableSet( envToPlaceholder.keySet() ); }

  // 設定済み credential から MITM すべき host を導く。credential が無ければ空 = MITM 無し。
  public Set<String> mitmHosts() {
    Set<String> s = new LinkedHashSet<>();
    for( String n : envToPlaceholder.keySet() ) {
      String h = hostFor( n );
      if( h != null ) s.add( h );
    }
    return s;
  }

  // host が分からない credential 名 (placeholder が swap されず実 server に届いてしまう)。
  public Set<String> unmappedNames() {
    Set<String> s = new LinkedHashSet<>();
    for( String n : envToPlaceholder.keySet() ) if( hostFor( n ) == null ) s.add( n );
    return s;
  }

  // placeholder: "sk-ant-emph01-<hex>" — 緩い format validation (sk-ant- 始まり) を通しつつ
  //   一意で MITM が認識できる marker。実キー format は模さない。
  private static String makePlaceholder( SecureRandom rng ) {
    byte[] r = new byte[20];
    rng.nextBytes( r );
    StringBuilder sb = new StringBuilder( "sk-ant-emph01-" );
    for( byte b : r ) sb.append( Character.forDigit( (b >> 4) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
    return sb.toString();
  }
}
