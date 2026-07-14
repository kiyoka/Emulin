// ----------------------------------------
//  fork 子 pool 縮小時の物理レイアウト継承 smoke (issue #723)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 32GB 窓ひっ迫 (issue #379) で fork 子の pool が親より小さく確保されると、
// NativeMemoryBackend.DATA_BASE (= page table 領域と data 領域の境界、pool サイズから算出) が
// 親と食い違う。子は親の物理レイアウト (page table / data ページの物理 offset) を verbatim copy
// するので、DATA_BASE は「その pool のレイアウト」の一部であり、必ず親から継承しなければならない。
//
// 食い違うと何が壊れるか (WHP lazy commit #304 が前提):
//   子 pool の commit 済 chunk は duplicate() が張った [0, ptNext) と [親 DATA_BASE, dataNext) のみで、
//   その間 [ptNext, 親 DATA_BASE) は **reserve-only (未 commit)**。子が孫を fork すると duplicate() は
//   「自分の」DATA_BASE (小さい方) から data 領域を copy しようとするため、この未 commit ギャップを
//   MemorySegment.copy が読み、Windows で EXCEPTION_ACCESS_VIOLATION になる (#304 で一度直した
//   クラスの再発)。allocPt() の fixed 枠判定 (ptNext + PAGE > DATA_BASE) も同時に狂う。
//
// 本 smoke は WHP/KVM 非依存の純 Java で、(a) 縮小 pool への duplicate() が DATA_BASE を継承すること、
// (b) その結果、孫 fork の data copy 範囲が「commit 済の [DATA_BASE, dataNext)」に収まり未 commit
// ギャップを読まないこと、を検証する (ギャップは commit 追跡を模した GpaBacking で検出する)。
//
// 起動: java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.PoolShrinkSmoke
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class PoolShrinkSmoke {

  private static int failures = 0;
  private static void eq( String name, long got, long want ) {
    if( got != want ) { System.err.println( "  FAIL " + name + ": got=" + got + " want=" + want ); failures++; }
    else System.out.println( "  ok " + name + " = 0x" + Long.toHexString( got ) );
  }
  private static void ok( String name, boolean cond, String detail ) {
    if( !cond ) { System.err.println( "  FAIL " + name + ": " + detail ); failures++; }
    else System.out.println( "  ok " + name );
  }

  /** commit 済 chunk を追跡する GpaBacking (WHP の lazy commit を模す)。ensure されていない
   *  offset を「読んだ」ら Windows では access violation になる = ここでは記録して検出する。 */
  private static final class TrackBacking implements NativeMemoryBackend.GpaBacking {
    static final long CHUNK = 0x200000L;                  // 2MB (WhpGpaBacking と同じ粒度)
    final java.util.BitSet committed = new java.util.BitSet();
    @Override public void ensure( long off, long len ) {
      if( len <= 0 ) return;
      for( long c = off / CHUNK; c <= ( off + len - 1 ) / CHUNK; c++ ) committed.set( (int) c );
    }
    boolean rangeCommitted( long off, long len ) {
      if( len <= 0 ) return true;
      for( long c = off / CHUNK; c <= ( off + len - 1 ) / CHUNK; c++ ) if( !committed.get( (int) c ) ) return false;
      return true;
    }
  }

  public static void main( String[] args ) {
    System.out.println( "  [PoolShrinkSmoke] fork 子 pool 縮小時の DATA_BASE 継承 (issue #723)" );
    try( Arena arena = Arena.ofConfined() ) {
      // 親 = 2048MB pool (DATA_BASE = 2048MB/128 = 16MB)、子 = 256MB に縮小 (単体だと 8MB になる)。
      //   MemorySegment は lazy な仮想確保なので、実 RAM は触った分しか使わない。
      final long PARENT = 2048L * 1024 * 1024;
      final long CHILD  =  256L * 1024 * 1024;
      MemorySegment parentPool = arena.allocate( PARENT, 0x1000 );
      MemorySegment childPool  = arena.allocate( CHILD,  0x1000 );

      NativeMemoryBackend parent = new NativeMemoryBackend( parentPool );
      TrackBacking parentBacking = new TrackBacking();
      parent.setGpaBacking( parentBacking );
      parent.enableMmu();

      eq( "parent DATA_BASE (2048MB pool)", parent.dataBase(), 16L * 1024 * 1024 );
      eq( "単独 256MB pool の DATA_BASE",   new NativeMemoryBackend( childPool ).dataBase(), 8L * 1024 * 1024 );

      // guest ページを少し map して page table と data を実際に確保する。
      for( long v = 0x400000L; v < 0x400000L + 16 * 0x1000L; v += 0x1000L ) parent.mapRange( v, 0x1000L, true );
      long ptNext = parent.ptNextForTest();
      ok( "親の PT 使用あり", ptNext > 0x2000L, "ptNext=0x" + Long.toHexString( ptNext ) );

      // ---- 縮小 pool へ fork ----
      TrackBacking childBacking = new TrackBacking();
      NativeMemoryBackend child = parent.duplicate( childPool, 0L, childBacking );

      // (a) DATA_BASE を親から継承していること (旧実装は子 pool サイズから 8MB に再計算していた)
      eq( "子 DATA_BASE = 親 DATA_BASE", child.dataBase(), parent.dataBase() );

      // (b) 子 pool の未 commit ギャップ [ptNext, DATA_BASE) が実在すること (前提条件の確認)
      ok( "子 pool に reserve-only ギャップが在る",
          !childBacking.rangeCommitted( ptNext, parent.dataBase() - ptNext ),
          "ギャップが commit 済に見える (前提が崩れている)" );

      // (c) 孫 fork: 子の duplicate() が copy する data 範囲 [child.DATA_BASE, dataNext) が
      //     すべて commit 済であること (= 未 commit ギャップを読まない)。旧実装は子の DATA_BASE が
      //     8MB に化けるため [8MB, 16MB) の未 commit 域を読み、Windows で AV crash していた。
      long copyOff = child.dataBase();
      long copyLen = child.usedTop() - copyOff;
      ok( "孫 fork の data copy 範囲が commit 済 (未 commit ギャップを読まない)",
          childBacking.rangeCommitted( copyOff, copyLen ),
          "copy [0x" + Long.toHexString( copyOff ) + ", 0x" + Long.toHexString( copyOff + copyLen )
              + ") が未 commit 域を含む = Windows で EXCEPTION_ACCESS_VIOLATION" );

      // 実際に孫を fork しても壊れないこと (KVM 相当: backing=null でも整合)
      MemorySegment grandPool = arena.allocate( CHILD, 0x1000 );
      TrackBacking grandBacking = new TrackBacking();
      NativeMemoryBackend grand = child.duplicate( grandPool, 0L, grandBacking );
      eq( "孫 DATA_BASE も継承", grand.dataBase(), parent.dataBase() );
    } catch( Throwable t ) {
      System.err.println( "  FAIL 例外: " + t );
      t.printStackTrace();
      failures++;
    }

    if( failures == 0 ) System.out.println( "  PoolShrink smoke OK" );
    else                System.err.println( "  PoolShrink smoke FAILED (" + failures + ")" );
    System.exit( failures == 0 ? 0 : 1 );
  }
}
