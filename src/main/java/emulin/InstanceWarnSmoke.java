package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  InstanceWarnSmoke — issue #955: 「同じ rootfs を使う別インスタンス」の検出を検証する。
//
//  ★ 守る実害 (2026-08-25 に実機で踏んだ):
//    稼働中の rootfs にもう 1 つ Emulin を起動すると、guest の credential ファイルが
//    別の placeholder で書き直され、**先に動いていた claude が黙って認証切れになる**。
//    原因は画面に何も出ないので、利用者からは「何もしていないのに Login expired」。
//
//  ★ この検査で一番大事なのは **canonical 比較** の行。実害は symlink / junction 越しに
//    同じ rootfs を掴んだ形だった。生の文字列で比べる実装は「別物」と判断して
//    **警告を出し損ねる** = 検出したい唯一の場面で黙る。
//
//  ★ 「違う rootfs では警告しない」も必ず見る。これが無いと**常に警告する実装**でも
//    緑になり、正常な使い方を毎回脅かす方向に壊れる。
//
//  guest もネットワークも要らない (純 Java)。
// --------------------------------------------------------------------
public final class InstanceWarnSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  /** 自分ではない**生きた** pid。親プロセスを使い、無ければ子を 1 つ起こす。 */
  private static long otherLivePid( List<java.lang.Process> spawned ) throws Exception {
    Optional<ProcessHandle> parent = ProcessHandle.current().parent();
    if( parent.isPresent() && parent.get().isAlive() ) return parent.get().pid();
    java.lang.Process p = new ProcessBuilder( "sleep", "30" ).start();
    spawned.add( p );
    return p.pid();
  }

  /** 死んでいる pid (子を起こして待ってから使う)。 */
  private static long deadPid() throws Exception {
    java.lang.Process p = new ProcessBuilder( "true" ).start();
    long pid = p.pid();
    p.waitFor();
    for( int i = 0; i < 50 && ProcessHandle.of( pid ).map( ProcessHandle::isAlive ).orElse( false ); i++ )
      Thread.sleep( 20 );
    return pid;
  }

  private static void writeEntry( File dir, long pid, String rootfs ) throws Exception {
    String j = "pid=" + pid + "\nstartedAt=" + System.currentTimeMillis()
             + "\nversion=test\nbackend=test\nrootfs=" + rootfs + "\n";
    java.nio.file.Files.write( new File( dir, pid + ".txt" ).toPath(),
        j.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
  }

  public static void main( String[] args ) throws Exception {
    List<java.lang.Process> spawned = new ArrayList<>();
    File tmp = java.nio.file.Files.createTempDirectory( "emulin-inst" ).toFile();
    File reg = new File( tmp, "instances" );
    reg.mkdirs();
    // ★ 利用者の ~/.emulin/instances は絶対に触らない
    InstanceRegistry.dirOverride = reg;

    File rootfsA = new File( tmp, "rootfsA" ); rootfsA.mkdirs();
    File rootfsB = new File( tmp, "rootfsB" ); rootfsB.mkdirs();

    System.out.println( "=== #955 同じ rootfs を使う別インスタンスの検出 ===" );

    // (0) 誰も居なければ警告しない
    check( InstanceRegistry.conflictWarning(
               InstanceRegistry.othersOnSameRootfs( rootfsA.getPath() ), rootfsA.getPath() ) == null,
           "誰も居なければ警告しない" );

    long other = otherLivePid( spawned );
    long dead  = deadPid();

    // (1) 同じ rootfs の生きた別インスタンス → 警告する
    writeEntry( reg, other, rootfsA.getCanonicalPath() );
    String w = InstanceRegistry.conflictWarning(
                   InstanceRegistry.othersOnSameRootfs( rootfsA.getPath() ), rootfsA.getPath() );
    check( w != null && w.contains( "pid " + other ) && w.contains( "#955" ),
           "同じ rootfs の生きた別インスタンスを警告する (pid が文面に出る)" );

    // (2) ★ 負のコントロール: 違う rootfs では警告しない
    check( InstanceRegistry.conflictWarning(
               InstanceRegistry.othersOnSameRootfs( rootfsB.getPath() ), rootfsB.getPath() ) == null,
           "違う rootfs なら警告しない (常に警告する実装を通さない)" );

    // (3) ★ symlink 越しでも同じ rootfs と判定する (実害はこの形だった)
    File link = new File( tmp, "linkToA" );
    boolean linked = true;
    try { java.nio.file.Files.createSymbolicLink( link.toPath(), rootfsA.toPath() ); }
    catch( Exception e ) { linked = false; System.out.println( "  (symlink を作れない: " + e + ")" ); }
    if( linked ) {
      check( !InstanceRegistry.othersOnSameRootfs( link.getPath() ).isEmpty(),
             "symlink 越しでも同じ rootfs と判定する (canonical 比較)" );
    }

    // (4) 死んだ pid の残骸は掃除され、警告しない
    new File( reg, other + ".txt" ).delete();
    writeEntry( reg, dead, rootfsA.getCanonicalPath() );
    boolean warned = InstanceRegistry.conflictWarning(
                         InstanceRegistry.othersOnSameRootfs( rootfsA.getPath() ), rootfsA.getPath() ) != null;
    check( !warned && !new File( reg, dead + ".txt" ).exists(),
           "死んだ pid の残骸は掃除され、警告しない" );

    // (5) register した自分自身では警告しない
    InstanceRegistry.register( rootfsA.getPath() );
    check( InstanceRegistry.conflictWarning(
               InstanceRegistry.othersOnSameRootfs( rootfsA.getPath() ), rootfsA.getPath() ) == null,
           "自分自身は警告の対象にしない" );
    InstanceRegistry.unregister();

    for( java.lang.Process p : spawned ) p.destroy();

    if( failures == 0 ) { System.out.println( "InstanceWarn smoke OK" ); System.exit( 0 ); }
    System.out.println( "InstanceWarn smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
