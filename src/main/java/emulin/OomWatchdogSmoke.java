package emulin;

// --------------------------------------------------------------------
//  OomWatchdogSmoke — issue #1026: **内部 OOM のあと guest が前進しなくなったら落とす**
//  見張りの判定を検査する。
//
//  ★ 実害 (2026-09-12 に手元で再現、CI では 3 日で 4 回):
//    heap を使い切ると busybox の applet 再 exec が OutOfMemoryError → ENOMEM になり、
//    その子が半端に死んだあと **pipe の読み手と wait4 の待ち手が永久に待つ**。
//    検査は 180s の timeout に殺されるだけで、**原因が何も残らなかった**。
//    ★ 停止は「遅い」より悪い。だから **停止したと分かったら大声で落とす**。
//
//  ★ ここで検査するのは **判定** (oomWatchdogVerdict)。System.exit を含む実行側は
//    そのままでは検査できないので、判定を切り出してある。時計を進めたことにして、
//    発火する / しない を決定的に確かめる (guest もネットワークも要らない)。
// --------------------------------------------------------------------
public final class OomWatchdogSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "PASS    oomwd-" : "FAIL    oomwd-" ) + what );
    if( !ok ) failures++;
  }

  public static void main( String[] args ) {
    long now = System.currentTimeMillis();
    int sec = SyscallAmd64.OOM_WATCHDOG_SEC;

    // --- 平常時: OOM が起きていなければ何があっても発火しない ---
    SyscallAmd64.disarmOomWatchdog();
    check( SyscallAmd64.oomWatchdogVerdict( now + 3600_000L ) == null,
           "not-armed (OOM が起きていなければ、いくら待っても発火しない)" );

    // --- OOM 直後: まだ猶予の内なので発火しない (負のコントロール) ---
    SyscallAmd64.armOomWatchdog( 59 );
    check( SyscallAmd64.oomWatchdogVerdict( now ) == null,
           "armed-but-fresh (OOM 直後は発火しない)" );
    check( SyscallAmd64.oomWatchdogVerdict( now + ( sec * 1000L ) - 1 ) == null,
           "armed-just-under (猶予の 1ms 手前でも発火しない)" );

    // --- 猶予を過ぎたら発火し、原因が文面に残る ---
    String v = SyscallAmd64.oomWatchdogVerdict( now + ( sec * 1000L ) + 500 );
    check( v != null, "armed-expired (猶予を過ぎたら発火する)" );
    if( v != null ) {
      check( v.contains( "stopped making progress" ), "verdict に「前進していない」と書く" );
      check( v.contains( "syscall 59" ),              "verdict に **どの syscall で OOM したか** を残す" );
      check( v.contains( "issue #1026" ),             "verdict に issue 番号を残す" );
      check( v.contains( "not slowness" ),            "★「遅いのではなく停止」と言い切る" );
      check( v.contains( "heap used=" ),              "verdict に heap の使用量を残す" );
    }

    // --- 前進したら猶予が延びる (guest が動いている間は落とさない) ---
    SyscallAmd64.OOM_LAST_PROGRESS_MS = now + ( sec * 1000L );
    check( SyscallAmd64.oomWatchdogVerdict( now + ( sec * 1000L ) + 500 ) == null,
           "progress-resets (syscall が通れば猶予が延びる)" );

    SyscallAmd64.disarmOomWatchdog();
    System.out.println( failures == 0 ? "OomWatchdog smoke OK"
                                      : ( "OomWatchdog smoke FAILED (" + failures + ")" ) );
    System.exit( failures == 0 ? 0 : 1 );
  }
}
