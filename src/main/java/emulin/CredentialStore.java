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
// ----------------------------------------
package emulin;

import java.security.SecureRandom;
import java.util.*;

public class CredentialStore {

  public static final String HOST_PREFIX = "EMULIN_CRED_";

  // guest env 変数名 → placeholder
  private final Map<String,String> envToPlaceholder = new LinkedHashMap<>();
  // placeholder → 実キー (host 側のみ。guest には絶対渡さない)
  private final Map<String,String> placeholderToReal = new HashMap<>();

  // host env から `EMULIN_CRED_<NAME>=<realkey>` を auto-discover する。
  public void discoverFromHostEnv() {
    discoverFrom( System.getenv() );
  }

  // テスト/明示注入用。
  public void discoverFrom( Map<String,String> hostEnv ) {
    SecureRandom rng = new SecureRandom();
    for( Map.Entry<String,String> e : hostEnv.entrySet() ) {
      String k = e.getKey();
      if( k == null || !k.startsWith( HOST_PREFIX ) ) continue;
      String name = k.substring( HOST_PREFIX.length() );
      String real = e.getValue();
      if( name.isEmpty() || real == null || real.isEmpty() ) continue;
      String ph = makePlaceholder( rng );
      envToPlaceholder.put( name, ph );
      placeholderToReal.put( ph, real );
    }
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
