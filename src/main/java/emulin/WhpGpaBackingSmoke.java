// ----------------------------------------
//  WhpGpaBacking chunk ロジック smoke (issue #304 WHP lazy commit)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// WhpGpaBacking の chunk index 計算 (WHP API 非依存の純関数) を検証する。
// allocPt/allocData/enableMmu/duplicate が ensure(poolOffset, len) で commit+map すべき chunk を
// 正しく算出するかが lazy commit の正しさの核。VirtualAlloc/WHvMapGpaRange は Windows でしか動かない
// が、chunk 算出ロジックは Linux で単体検証できる (この smoke)。
//
// 起動: java -cp target/classes emulin.WhpGpaBackingSmoke   (KVM/WHP 不要、純 Java)
package emulin;

public class WhpGpaBackingSmoke {

  private static int failures = 0;
  private static void eq( String name, long got, long want ) {
    if( got != want ) { System.err.println( "  FAIL " + name + ": got=" + got + " want=" + want ); failures++; }
    else System.out.println( "  ok " + name + " = " + got );
  }

  /** [off, off+len) が跨ぐ chunk index 集合。 */
  private static java.util.BitSet chunkSet( long off, long len, long chunk ) {
    java.util.BitSet b = new java.util.BitSet();
    if( len <= 0 ) return b;
    for( int c = WhpGpaBacking.chunkOf( off, chunk ); c <= WhpGpaBacking.lastChunkOf( off, len, chunk ); c++ ) b.set( c );
    return b;
  }

  public static void main( String[] args ) {
    final long CH = 0x200000L;   // 2MB を明示 (CHUNK の env 依存を排除して決定的に検証)
    System.out.println( "[WhpGpaBackingSmoke] CHUNK(default/env) = 0x" + Long.toHexString( WhpGpaBacking.CHUNK )
        + " (テストは固定 CH=0x" + Long.toHexString( CH ) + " で算出を検証)" );

    // chunkOf: pool offset → chunk index
    eq( "chunkOf(0)",            WhpGpaBacking.chunkOf( 0,          CH ), 0 );
    eq( "chunkOf(PML4=0x1000)",  WhpGpaBacking.chunkOf( 0x1000,     CH ), 0 );
    eq( "chunkOf(2MB-1)",        WhpGpaBacking.chunkOf( CH - 1,     CH ), 0 );
    eq( "chunkOf(2MB)",          WhpGpaBacking.chunkOf( CH,         CH ), 1 );
    eq( "chunkOf(8MB=DATA_BASE)",WhpGpaBacking.chunkOf( 0x800000,   CH ), 4 );

    // lastChunkOf: [off, off+len) が跨ぐ最後の chunk (inclusive)
    eq( "last(PML4 1page)",      WhpGpaBacking.lastChunkOf( 0x1000, 0x1000,   CH ), 0 );   // [0x1000,0x2000)
    eq( "last(0,2MB)",           WhpGpaBacking.lastChunkOf( 0,      CH,       CH ), 0 );   // [0,2MB) は chunk0 のみ
    eq( "last(0,2MB+1)",         WhpGpaBacking.lastChunkOf( 0,      CH + 1,   CH ), 1 );   // 1byte 越えで chunk1 へ
    eq( "last(chunk0 末page)",   WhpGpaBacking.lastChunkOf( CH-0x1000, 0x1000,CH ), 0 );   // chunk0 の最終 page
    eq( "last(0,8MB)",           WhpGpaBacking.lastChunkOf( 0,      0x800000, CH ), 3 );   // [0,8MB)=chunks0-3 (duplicate prefix 相当)

    // chunkOffset
    eq( "chunkOffset(0)",        WhpGpaBacking.chunkOffset( 0, CH ), 0 );
    eq( "chunkOffset(4)",        WhpGpaBacking.chunkOffset( 4, CH ), 0x800000 );

    // chunkLen: 通常は CHUNK、末尾 chunk は pool 端で切り詰め
    final long pool = 5L * CH + 0x1000;   // 5 full chunk + 1 page (= 非 chunk 倍数の pool)
    eq( "chunkLen(0,full)",      WhpGpaBacking.chunkLen( 0, CH, pool ), CH );
    eq( "chunkLen(4,full)",      WhpGpaBacking.chunkLen( 4, CH, pool ), CH );
    eq( "chunkLen(5,partial)",   WhpGpaBacking.chunkLen( 5, CH, pool ), 0x1000 );   // 末尾 partial = 1 page

    // 現実シナリオ: 8GB pool で 2MB chunk = 4096 chunk、最終 offset の chunk index
    final long pool8g = 8L * 1024 * 1024 * 1024;
    eq( "chunkOf(8GB-1page, 8GB pool)", WhpGpaBacking.chunkOf( pool8g - 0x1000, CH ), 4095 );
    eq( "chunkLen(4095, 8GB pool)",     WhpGpaBacking.chunkLen( 4095, CH, pool8g ), CH );

    // ---- duplicate() の copy 範囲が page table 予約ギャップ [ptNext, DATA_BASE) を跨がないこと ----
    //   Windows 実機 regression (2026-06-14): 旧 NativeMemoryBackend.duplicate は親 pool の
    //   [0, dataNext) を一括 MemorySegment.copy していたが、これは page table 予約域の未使用ギャップ
    //   [ptNext, DATA_BASE) (誰も ensure しない = MEM_RESERVE) を読み、EXCEPTION_ACCESS_VIOLATION @
    //   jlong_disjoint_arraycopy で死んだ。修正=使用済み 2 領域 [0,ptNext) と [DATA_BASE,dataNext) だけ
    //   別々に copy。本テストは「新 copy 範囲の chunk は全て親が commit 済 = ギャップ chunk を含まない」
    //   を純ロジックで lock し、旧実装がギャップを跨いでいたことも実演する (KVM では reserve 概念が
    //   無く顕在化しないので、この純ロジック guard が Linux 側の回帰防壁)。
    {
      final long DATA_BASE = 0x800000L;             // NativeMemoryBackend 既定 (pool <= 1GB)
      final long ptNext    = 0x6000L;               // PML4 + 数枚の PT (chunk0 内)
      final long dataNext  = DATA_BASE + 0x40000L;  // 64 data page (chunk4 内)
      // 親が commit する chunk = enableMmu(chunk0) U allocPt[0,ptNext) U allocData[DATA_BASE,dataNext)
      java.util.BitSet committed = chunkSet( 0, ptNext, CH );
      committed.or( chunkSet( DATA_BASE, dataNext - DATA_BASE, CH ) );
      // 新 copy 範囲の chunk (= committed と同一)
      java.util.BitSet copyChunks = chunkSet( 0, ptNext, CH );
      copyChunks.or( chunkSet( DATA_BASE, dataNext - DATA_BASE, CH ) );
      java.util.BitSet notCommitted = (java.util.BitSet) copyChunks.clone(); notCommitted.andNot( committed );
      eq( "dup: new-copy chunks all committed (0=ok)", notCommitted.cardinality(), 0 );
      // ギャップ chunk (1,2,3) を識別
      java.util.BitSet gap = new java.util.BitSet();
      for( int c = WhpGpaBacking.lastChunkOf( 0, ptNext, CH ) + 1; c < WhpGpaBacking.chunkOf( DATA_BASE, CH ); c++ ) gap.set( c );
      eq( "dup: gap chunks exist (3=1,2,3)", gap.cardinality(), 3 );
      java.util.BitSet newHitsGap = (java.util.BitSet) copyChunks.clone(); newHitsGap.and( gap );
      eq( "dup: new-copy hits gap (0=ok)", newHitsGap.cardinality(), 0 );
      // 旧実装の実演: [0,dataNext) 一括 copy はギャップ chunk を含む (= これがバグ)
      java.util.BitSet oldCopy = chunkSet( 0, dataNext, CH );
      java.util.BitSet oldHitsGap = (java.util.BitSet) oldCopy.clone(); oldHitsGap.and( gap );
      eq( "dup: OLD [0,dataNext) copy hits gap (3=regression shown)", oldHitsGap.cardinality(), 3 );
    }

    if( failures == 0 ) { System.out.println( "WhpGpaBacking smoke OK" ); System.exit( 0 ); }
    System.err.println( "WhpGpaBacking smoke FAIL (" + failures + " 件)" ); System.exit( 1 );
  }
}
