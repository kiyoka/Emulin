package emulin;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// --------------------------------------------------------------------
//  TokenRotateSmoke — issue #954: OAuth refresh の **in-flight 直列化**を検証する。
//
//  ★ 何を守るテストか:
//    guest 内で複数の client (ssh 2 本の claude / Remote Control の bridge と worker) が
//    **同時に** refresh を投げると、両方が**同じ refresh token** を上流へ提示する。
//    OAuth の回転は 1 回しか通らないので後着は invalid_grant で弾かれ、受け取った client は
//    「ログインが切れた」と判断して credential を捨てる (#944 の実害)。
//
//  ★ #943 の cooldown では足りない理由もここで示す (負のコントロール):
//    cooldown は `msSinceLastRotate()` を見る **事後**の窓で、回転が書き戻されるまで
//    更新されない。したがって同時に飛んだ 2 本は両方とも通り抜ける。
//    直列化を切った状態 (= #943 までの実装と同じ) で FORWARD が 2 本以上出ることを
//    **実際に測って**から、直列化ありで 1 本になることを確認する。
//
//  ネットワークも guest も要らない (純 Java)。
// --------------------------------------------------------------------
public final class TokenRotateSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  /** 実キーは持たせない。placeholder が割り当てられれば十分。 */
  private static CredentialStore store() {
    Map<String,String> env = new HashMap<>();
    env.put( "EMULIN_CRED_CLAUDE_ACCESS_TOKEN",  "sk-ant-oat01-TEST-ACCESS-0000000000" );
    env.put( "EMULIN_CRED_CLAUDE_REFRESH_TOKEN", "sk-ant-ort01-TEST-REFRESH-000000000" );
    CredentialStore cs = new CredentialStore();
    cs.discoverFrom( env );      // ★ srcFile が null なので rotateReal はファイルを触らない
    return cs;
  }

  /** N 本を同時に投げ、FORWARD (= 上流へ行く) が何本になるかを数える。 */
  private static int[] race( boolean serialize, int n ) throws Exception {
    CredentialStore cs = store();
    TlsMitmProxy mitm = new TlsMitmProxy( null, cs, serialize, 20_000L );
    CountDownLatch start = new CountDownLatch( 1 );
    CountDownLatch done  = new CountDownLatch( n );
    AtomicInteger forward = new AtomicInteger(), local = new AtomicInteger();
    AtomicLong releasedAt = new AtomicLong( Long.MAX_VALUE );
    AtomicLong firstLocalAt = new AtomicLong( Long.MAX_VALUE );

    for( int i = 0; i < n; i++ ) {
      new Thread( () -> {
        try {
          start.await();
          TlsMitmProxy.ConnState st = new TlsMitmProxy.ConnState();
          TlsMitmProxy.RotateDecision d = mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", st );
          if( d == TlsMitmProxy.RotateDecision.FORWARD ) {
            forward.incrementAndGet();
            if( st.rotateGate != null ) {          // 自分が回転する側
              Thread.sleep( 300 );                 // 上流との往復を模す
              cs.rotateReal( "CLAUDE_ACCESS_TOKEN", "sk-ant-oat01-TEST-ACCESS-ROTATED-1" );
              releasedAt.set( System.nanoTime() );
              TlsMitmProxy.releaseRotateGate( st );
            }
          } else {
            local.incrementAndGet();
            firstLocalAt.accumulateAndGet( System.nanoTime(), Math::min );
          }
        } catch( Exception e ) {
          System.out.println( "  (thread error: " + e + ")" );
        } finally { done.countDown(); }
      }, "race-" + i ).start();
    }
    start.countDown();
    if( !done.await( 60, TimeUnit.SECONDS ) ) System.out.println( "  (タイムアウト)" );
    boolean ordered = firstLocalAt.get() >= releasedAt.get();
    return new int[]{ forward.get(), local.get(), ordered ? 1 : 0 };
  }

  /** placeholder を body に載せた OAuth token 要求を組み立てる。 */
  private static byte[] tokenRequest( String placeholder ) {
    String body = "{\"grant_type\":\"refresh_token\",\"refresh_token\":\"" + placeholder + "\"}";
    String req = "POST /v1/oauth/token HTTP/1.1\r\n"
               + "Host: platform.claude.com\r\n"
               + "Content-Type: application/json\r\n"
               + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    return req.getBytes( java.nio.charset.StandardCharsets.ISO_8859_1 );
  }

  /** pumpRequest (実際の経路) を通して、直列化が効いているかを見る。 */
  private static void checkWiring() throws Exception {
    CredentialStore cs = store();
    String ph = cs.placeholderOf( "CLAUDE_REFRESH_TOKEN" );
    if( ph == null ) { check( false, "placeholder が割り当てられていない (テストの前提が壊れている)" ); return; }

    // (a) 誰も回転していない → 通常どおり上流へ行く (= 直列化が普通の refresh を止めない)
    {
      TlsMitmProxy mitm = new TlsMitmProxy( null, cs, true, 20_000L );
      TlsMitmProxy.ConnState st = new TlsMitmProxy.ConnState();
      java.io.ByteArrayOutputStream up = new java.io.ByteArrayOutputStream();
      mitm.pumpRequest( new java.io.ByteArrayInputStream( tokenRequest( ph ) ), up, st );
      String sent = up.toString( "ISO-8859-1" );
      check( up.size() > 0 && st.localAnswer == null && !sent.contains( ph ),
             "先着がいなければ上流へ行き、placeholder は実キーに置換されている ("
             + up.size() + " byte)" );
      TlsMitmProxy.releaseRotateGate( st );
    }

    // (b) 先着が回転中 → 上流へ 1 byte も出さず、現在のトークンで答える
    {
      TlsMitmProxy mitm = new TlsMitmProxy( null, cs, true, 20_000L );
      TlsMitmProxy.ConnState leader = new TlsMitmProxy.ConnState();
      mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", leader );       // 握る
      TlsMitmProxy.ConnState st = new TlsMitmProxy.ConnState();
      java.io.ByteArrayOutputStream up = new java.io.ByteArrayOutputStream();
      Thread t = new Thread( () -> {
        try { mitm.pumpRequest( new java.io.ByteArrayInputStream( tokenRequest( ph ) ), up, st ); }
        catch( Exception e ) { System.out.println( "  (wiring error: " + e + ")" ); }
      } );
      t.start();
      Thread.sleep( 200 );
      boolean blockedWhileLeaderHolds = ( up.size() == 0 );
      TlsMitmProxy.releaseRotateGate( leader );                  // 先着の回転が終わった
      t.join( 30_000 );
      check( blockedWhileLeaderHolds && up.size() == 0 && st.localAnswer != null,
             "先着が回転中の refresh は**上流へ 1 byte も出さず**、現在のトークンで応答する"
             + " (上流へ出た byte=" + up.size() + ")" );
      String ans = ( st.localAnswer == null ) ? "" 
                 : new String( st.localAnswer, java.nio.charset.StandardCharsets.ISO_8859_1 );
      check( ans.contains( "200 OK" ) && ans.contains( ph ),
             "応答は 200 で、guest には placeholder を返している (実キーは渡さない)" );
    }
  }

  public static void main( String[] args ) throws Exception {
    final int N = 8;
    System.out.println( "=== #954 refresh の直列化 ===" );

    // ★ 負のコントロール: 直列化なし = #943 までの実装。ここが「1 本」だと
    //   テストが**壊れた実装を通してしまう**ので、まず 2 本以上出ることを測る。
    int[] off = race( false, N );
    System.out.println( "  直列化なし: 上流へ " + off[0] + " 本 / ローカル応答 " + off[1] + " 本" );
    check( off[0] >= 2, "負のコントロール: 直列化なしだと同じ refresh token が複数回 上流へ行く"
                      + " (= 後着が invalid_grant で弾かれる状態)" );

    int[] on = race( true, N );
    System.out.println( "  直列化あり: 上流へ " + on[0] + " 本 / ローカル応答 " + on[1] + " 本" );
    check( on[0] == 1,      "同時 " + N + " 本のうち上流へ行くのは 1 本だけ" );
    check( on[1] == N - 1,  "残り " + ( N - 1 ) + " 本は現在のトークンで応答する" );
    check( on[2] == 1,      "後着の決定は先着の回転が完了した**あと**に起きている"
                          + " (窓ではなく順序で保証されている)" );

    // ★ 直列化が**新しい停止要因**にならないこと。先着が返ってこないときは諦めて上流へ。
    {
      CredentialStore cs = store();
      TlsMitmProxy mitm = new TlsMitmProxy( null, cs, true, 300L );
      TlsMitmProxy.ConnState leader = new TlsMitmProxy.ConnState();
      mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", leader );      // 握ったまま解放しない
      long t0 = System.nanoTime();
      TlsMitmProxy.RotateDecision d =
          mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", new TlsMitmProxy.ConnState() );
      long ms = ( System.nanoTime() - t0 ) / 1_000_000;
      check( d == TlsMitmProxy.RotateDecision.FORWARD && ms >= 250,
             "先着が固まっても " + ms + " ms で諦めて上流へ投げる (fail open)" );
      TlsMitmProxy.releaseRotateGate( leader );
    }

    // keep-alive で同じ接続から 2 本目の token 要求が来ても、二重にゲートを取らない。
    {
      CredentialStore cs = store();
      TlsMitmProxy mitm = new TlsMitmProxy( null, cs, true, 300L );
      TlsMitmProxy.ConnState st = new TlsMitmProxy.ConnState();
      mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", st );
      java.util.concurrent.Semaphore g = st.rotateGate;
      TlsMitmProxy.RotateDecision d2 = mitm.decideRotate( "CLAUDE_REFRESH_TOKEN", st );
      TlsMitmProxy.releaseRotateGate( st );
      check( d2 == TlsMitmProxy.RotateDecision.FORWARD && g != null && g.availablePermits() == 1,
             "同じ接続の 2 本目はゲートを取り直さない (解放後に許可が 1 に戻る)" );
    }

    // ★ ここまでは decideRotate 単体。**実際の request 経路に繋がっているか**を測る。
    //   繋ぎ忘れは「テストは緑なのに実機で直っていない」という一番痛い形になる。
    checkWiring();

    if( failures == 0 ) { System.out.println( "TokenRotate smoke OK" ); System.exit( 0 ); }
    System.out.println( "TokenRotate smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
