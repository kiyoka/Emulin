package emulin;

import java.io.File;

// --------------------------------------------------------------------
//  PlaceholderStableSmoke — placeholder が rootfs ごとに固定されるか (issue #955)
//
//  ★ 守りたいこと: **同じ rootfs で 2 回起動したら、同じ placeholder になる**こと。
//    ここが崩れると、同時に動く 2 つ目の Emulin が guest のファイルを書き換え、
//    先に動いていたセッションの claude / codex が 401 -> "Login expired" になる。
//    しかも警告が出ないので、利用者は原因に辿り着けない (実運用で繰り返し踏んだ)。
//
//  ★ **違う rootfs では違う値**であることも見る。全部同じにしてしまうと、
//    「固定できているか」の検査は通るのに分離が壊れる。
//
//  終了コード: 0=PASS / 1=FAIL
// --------------------------------------------------------------------
public final class PlaceholderStableSmoke {

  private static int ng = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) ng++;
  }

  /** 1 回の「起動」を模す: seed を引いて CredentialStore を作り、placeholder を返す。 */
  private static java.util.Map<String,String> boot( File emulinDir, String rootfs ) {
    CredentialStore cs = new CredentialStore();
    PlaceholderSeed.Seed s = PlaceholderSeed.forRootfs( emulinDir, rootfs );
    if( s != null ) cs.useStableSeed( s.bytes, s.iat );
    java.util.Map<String,String> hostEnv = new java.util.LinkedHashMap<>();
    hostEnv.put( CredentialStore.HOST_PREFIX + "ANTHROPIC_API_KEY", "sk-ant-REAL-anthropic-key" );
    hostEnv.put( CredentialStore.HOST_PREFIX + "OPENAI_API_KEY",    "sk-REAL-openai-key" );
    hostEnv.put( CredentialStore.HOST_PREFIX + "GEMINI_API_KEY",    "AIzaREALgeminikeyREALgeminikeyREALgem" );
    hostEnv.put( CredentialStore.HOST_PREFIX + "GITHUB_TOKEN",      "ghp_REALgithubtokenREALgithubtoken12" );
    hostEnv.put( CredentialStore.HOST_PREFIX + "CLAUDE_ACCESS_TOKEN",
                 "sk-ant-oat01-REALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREALREA" );
    hostEnv.put( CredentialStore.HOST_PREFIX + "CODEX_ACCESS_TOKEN", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyZWFsIn0.sig" );
    cs.discoverFrom( hostEnv );
    java.util.Map<String,String> out = new java.util.LinkedHashMap<>();
    for( String n : cs.names() ) out.put( n, cs.placeholderOf( n ) );
    return out;
  }

  public static void main( String[] args ) throws Exception {
    File tmp = java.nio.file.Files.createTempDirectory( "emulin-seed" ).toFile();
    File emulinDir = new File( tmp, "dot-emulin" );
    File rootfsA = new File( tmp, "a/rootfs" ); rootfsA.mkdirs();
    File rootfsB = new File( tmp, "b/rootfs" ); rootfsB.mkdirs();

    System.out.println( "=== 同じ rootfs で 2 回「起動」する (#955) ===" );
    java.util.Map<String,String> a1 = boot( emulinDir, rootfsA.getPath() );
    java.util.Map<String,String> a2 = boot( emulinDir, rootfsA.getPath() );
    check( !a1.isEmpty(), "検査の前提: placeholder が作られている (" + a1.size() + " 件)" );
    check( a1.equals( a2 ), "同じ rootfs なら placeholder が一致する (2 つ目の起動が壊さない)" );
    for( String n : a1.keySet() )
      if( !a1.get( n ).equals( a2.get( n ) ) )
        System.out.println( "    差分: " + n );

    System.out.println( "=== 違う rootfs では違う値になる ===" );
    java.util.Map<String,String> b1 = boot( emulinDir, rootfsB.getPath() );
    boolean allDiffer = true;
    for( String n : a1.keySet() )
      if( a1.get( n ).equals( b1.get( n ) ) ) allDiffer = false;
    check( allDiffer, "rootfs が違えば placeholder も違う (分離が保たれる)" );

    System.out.println( "=== 実キーは placeholder に混ざらない (#401 の不変条件) ===" );
    boolean leaked = false;
    for( String v : a1.values() )
      if( v.contains( "REAL" ) ) leaked = true;
    check( !leaked, "placeholder に実キーの断片が入らない" );
    // ★ JWT 形式 (codex) は marker が **base64 の payload の中**にある。素の文字列を
    //   探すだけだと「入っていない」と誤判定するので、JWT は復号してから見る。
    //   (緩めるのではなく、正しく見る)
    boolean marked = true;
    String unmarked = "";
    for( java.util.Map.Entry<String,String> e : a1.entrySet() ) {
      String v = e.getValue();
      String probe = v;
      String[] parts = v.split( "\\." );
      if( parts.length == 3 ) {
        try {
          probe = new String( java.util.Base64.getUrlDecoder().decode( parts[1] ),
                              java.nio.charset.StandardCharsets.UTF_8 );
        } catch( Exception ignore ) { }
      }
      if( !probe.contains( "emph01" ) && !probe.contains( "Emph01" ) ) {
        marked = false; unmarked += " " + e.getKey();
      }
    }
    check( marked, "placeholder に marker (emph01) が入る (漏洩調査で実キーと区別できる)"
                 + ( marked ? "" : " — 無い:" + unmarked ) );

    System.out.println( "=== seed が無い (rootfs 不明) ときは従来どおり毎回変わる ===" );
    java.util.Map<String,String> n1 = boot( emulinDir, null );
    java.util.Map<String,String> n2 = boot( emulinDir, null );
    check( !n1.equals( n2 ), "rootfs を渡さなければ固定されない (seed の効果であることの確認)" );

    System.out.println( ng == 0 ? "Placeholder stable smoke OK" : "Placeholder stable smoke NG=" + ng );
    System.exit( ng == 0 ? 0 : 1 );
  }
}
