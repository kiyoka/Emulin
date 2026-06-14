// ----------------------------------------
//  WHP guest RAM lazy commit backing (issue #304)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// NativeMemoryBackend.GpaBacking の WHP 実装。allocPt/allocData/enableMmu/duplicate が「pool の
// この offset 範囲を今から使う」瞬間に ensure() を呼ぶので、その chunk だけを
// VirtualAlloc(MEM_COMMIT) + WHvMapGpaRange する。pool 全体の eager commit (HvVm.allocGuestRam の
// MEM_COMMIT) を廃し、commit charge (pagefile / system commit limit の予約) を guest 実使用量に
// 比例させる。これで fork 連鎖 (N × POOL_SIZE) の commit charge 圧迫を緩和する。
//
//   ★ 物理 RAM は Windows の MEM_COMMIT でも demand-zero で元から遅延。本 class が削るのは
//     commit charge (予約) であって物理 RAM ではない。
//   ★ host (JVM の page table 書込 / ELF copy / syscall copyin-out) も guest (vCPU access) も、
//     emulin が PTE を張る瞬間 (= allocPt/allocData) に既に commit+map 済の chunk を触るので、
//     CPU fault-on-access (WHvRunVpExitReasonMemoryAccess) を一切扱わずに lazy commit が成立する。
//   ★ Linux では instantiate されない (WHP 経路専用)。KVM backend は GpaBacking を attach しないので
//     完全 no-op = byte-identical。chunk index 計算は static 純関数に分離し Linux 単体テスト可能。
//   ★ ensure は backend の mmuLock 下 (allocPt/allocData) / boot single-thread (enableMmu) /
//     親 mmuLock 下 (duplicate) からのみ呼ばれるが、念のため synchronized で commit/map/BitSet を直列化。
package emulin;

import java.lang.foreign.MemorySegment;
import java.util.BitSet;

final class WhpGpaBacking implements NativeMemoryBackend.GpaBacking {

  // commit/map の粒度。小さいと VirtualAlloc/WHvMapGpaRange 回数増、大きいと commit over-provision。
  //   WHvRunVirtualProcessor round-trip ~5.8µs を踏まえ既定 2MB。EMULIN_WHP_COMMIT_CHUNK_MB で可変。
  static final long CHUNK = parseChunk();
  private static long parseChunk() {
    long mb = 2;
    try {
      String s = System.getenv( "EMULIN_WHP_COMMIT_CHUNK_MB" );
      if( s != null ) mb = Math.max( 1L, Long.parseLong( s.trim() ) );
    } catch( Throwable ignore ) {}
    return mb * 1024L * 1024L;
  }

  private final long   hostBase;   // 予約済 pool の host VA 先頭 (= poolSeg.address())
  private final long   gpaBase;    // この pool が partition 内で map される GPA slot base
  private final long   poolSize;
  private final BitSet committed = new BitSet();   // chunk index → MEM_COMMIT 済
  private final BitSet mapped    = new BitSet();   // chunk index → WHvMapGpaRange 済
  private WhpVm        vm;          // bindVm まで null (boot 中の commit は flushMaps で後追い map)

  WhpGpaBacking( long hostBase, long gpaBase, long poolSize ) {
    this.hostBase = hostBase;
    this.gpaBase  = gpaBase;
    this.poolSize = poolSize;
  }

  // ---- 純ロジック (WHP API 非依存、Linux 単体テスト可能) ----
  /** pool offset を含む chunk index。 */
  static int chunkOf( long poolOffset, long chunk ) { return (int) ( poolOffset / chunk ); }
  /** [off, off+len) が跨ぐ最後の chunk index (inclusive)。 */
  static int lastChunkOf( long poolOffset, long len, long chunk ) {
    return (int) ( ( poolOffset + len - 1 ) / chunk );
  }
  /** chunk c の pool offset。 */
  static long chunkOffset( int c, long chunk ) { return (long) c * chunk; }
  /** chunk c の長さ (末尾 chunk は pool 端で切り詰め、pool 外なら 0 以下)。 */
  static long chunkLen( int c, long chunk, long poolSize ) {
    long off = (long) c * chunk;
    return Math.min( chunk, poolSize - off );
  }

  @Override
  public synchronized void ensure( long poolOffset, long len ) {
    if( len <= 0 ) return;
    int c0 = chunkOf( poolOffset, CHUNK );
    int c1 = lastChunkOf( poolOffset, len, CHUNK );
    for( int c = c0; c <= c1; c++ ) {
      long coff = chunkOffset( c, CHUNK );
      long clen = chunkLen( c, CHUNK, poolSize );
      if( clen <= 0 ) continue;                 // pool 端を越える chunk (defensive)
      if( !committed.get( c ) ) { commitChunk( coff, clen ); committed.set( c ); }
      if( vm != null && !mapped.get( c ) )      { mapChunk( coff, clen );    mapped.set( c ); }
    }
  }

  /** vm を接続し、bindVm 前に commit 済だった chunk を全て map する。
   *  ★ NativeCpuBackend は setupVcpu (CR3 設定) より前に呼ぶこと (PML4/PT/PT_LOAD chunk が
   *    map 済でないと初回 fetch が triple fault になる)。 */
  synchronized void bindVm( WhpVm v ) {
    this.vm = v;
    for( int c = committed.nextSetBit( 0 ); c >= 0; c = committed.nextSetBit( c + 1 ) ) {
      if( !mapped.get( c ) ) {
        mapChunk( chunkOffset( c, CHUNK ), chunkLen( c, CHUNK, poolSize ) );
        mapped.set( c );
      }
    }
  }

  /** teardown: map 済 chunk を 1 つずつ unmap (slot 全域 1 回 unmap は未 map gap で失敗しうるため chunk 単位)。 */
  synchronized void unmapAll() {
    if( vm == null ) return;
    for( int c = mapped.nextSetBit( 0 ); c >= 0; c = mapped.nextSetBit( c + 1 ) ) {
      try { vm.unmapGuestRam( gpaBase + chunkOffset( c, CHUNK ), chunkLen( c, CHUNK, poolSize ) ); }
      catch( Throwable t ) {
        // unmap 失敗 chunk は slot 再利用時の map 失敗として遅れて表面化する。本 class は Windows
        //   未検証なので、どの chunk が失敗したか log して実機で観測可能にする (issue #304 review)。
        System.err.println( "[WhpGpaBacking] WHvUnmapGpaRange 失敗 chunk=" + c + " gpa=0x"
            + Long.toHexString( gpaBase + chunkOffset( c, CHUNK ) ) + ": " + t );
      }
    }
    mapped.clear();
  }

  private void commitChunk( long coff, long clen ) {
    try {
      MemorySegment r = (MemorySegment) WhpBindings.virtualAlloc().invoke(
          MemorySegment.ofAddress( hostBase + coff ), clen,
          (int) WhpBindings.MEM_COMMIT, (int) WhpBindings.PAGE_READWRITE );
      if( r == null || r.address() == 0L )
        throw new IllegalStateException( "WhpGpaBacking: VirtualAlloc(MEM_COMMIT) failed at pool off=0x"
            + Long.toHexString( coff ) + " len=0x" + Long.toHexString( clen ) );
    } catch( Throwable t ) {
      throw new RuntimeException( "WhpGpaBacking.commitChunk off=0x" + Long.toHexString( coff ), t );
    }
  }

  private void mapChunk( long coff, long clen ) {
    try { vm.mapGuestRam( gpaBase + coff, hostBase + coff, clen ); }
    catch( Throwable t ) {
      throw new RuntimeException( "WhpGpaBacking.mapChunk gpa=0x" + Long.toHexString( gpaBase + coff )
          + " host=0x" + Long.toHexString( hostBase + coff ), t );
    }
  }
}
