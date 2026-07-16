// ----------------------------------------
//  Emulin PollKick (issue #709, 案C)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

// issue #709: sleep ベースのポーリング型 blocking syscall (poll / epoll_wait / wait4) を
//   event-driven に近づける「kick」機構。
//
//   背景: Emulin の poll/epoll/wait4 は「readiness を再導出 → Thread.sleep(数 ms) → 再チェック」の
//   ポーリング。fd の状態を変える側 (eventfd write / pipe write / 子プロセス exit) から待機側を
//   直接起こす経路が無く、最大で sleep 間隔ぶんの遅延と、その窓での起こしすれ違いが起きる。
//   node/libuv (uv_async=eventfd) や Rust/tokio (mio::Waker=eventfd) の「ワーカー→リアクタ起こし」が
//   ここに乗るため、AI エージェントのイベントループが間欠的に固まる一因になる (#709)。
//
//   設計: 全プロセス/スレッド共有の単一 waker。poller は Thread.sleep の代わりに await(ms) で待ち、
//   状態変更側は kick() で待機中の全 poller を再チェックさせる。await の ms timeout は backstop として
//   残るので、kick を取りこぼしても最悪 ms 後には再チェックし、恒久 hang にはならない (二重の保険)。
//   注意: これは「起こしの遅延と窓」を潰す最適化であって、readiness の再導出そのもの (socket peek 等)
//   の正しさは別途保証する必要がある。futex は元々 event-driven (Object.wait/notify) なので対象外。
final class PollKick {
  private PollKick() {}

  private static final Object WAKER = new Object();

  // 診断/A-B 用のキルスイッチ。EMULIN_POLLKICK=0 で案C を無効化し、await は素の
  //   Thread.sleep(ms)、kick は no-op に落とす (= 案C 導入前と同一のポーリング挙動)。
  //   同一 jar で「案Cあり(既定)/なし」を切替比較できるので、#709 が本当に案Cで
  //   解消するかを実機で判定するのに使う。既定は有効。
  private static final boolean ENABLED =
    !"0".equals( System.getenv( "EMULIN_POLLKICK" ) );

  // fd の状態を変えた側 (eventfd write / pipe write / 子 exit 等) が呼ぶ。
  //   待機中の全 poller を起こして readiness を即再チェックさせる。呼び出しは軽量 (待機者ゼロなら
  //   notifyAll は no-op)。ホットパスから呼んでも問題ない粒度。
  static void kick() {
    if( !ENABLED ) return;
    synchronized( WAKER ) { WAKER.notifyAll(); }
  }

  // poller が polling ループ内の Thread.sleep(ms) の代わりに使う。kick() か ms 経過で戻る。
  //   InterruptedException は割込フラグを立て直して戻る (呼び出し側のループが再チェックする)。
  static void await( long ms ) {
    if( ms <= 0 ) return;
    if( !ENABLED ) {  // 案C 無効時は素の sleep (導入前と同一挙動)
      try { Thread.sleep( ms ); }
      catch( InterruptedException ie ) { Thread.currentThread().interrupt(); }
      return;
    }
    synchronized( WAKER ) {
      try { WAKER.wait( ms ); }
      catch( InterruptedException ie ) { Thread.currentThread().interrupt(); }
    }
  }
}
