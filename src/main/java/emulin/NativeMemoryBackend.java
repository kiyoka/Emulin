// ----------------------------------------
//  Native guest-RAM MemoryBackend (issue #221 Phase 0 step 3d-1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: HW 仮想化 backend (KVM/WHP) の guest 物理 RAM を表す `MemoryBackend`
//   実装。emulin の syscall 層 (`SyscallAmd64.amd64_write` 等) は guest pointer を
//   `mem.bulkLoadFromMem` / `load8` 等で読む。native backend ではその `mem` が
//   本 class で、**off-heap な単一 `MemorySegment`** (= KVM が
//   `KVM_SET_USER_MEMORY_REGION` でマップする guest 物理 RAM そのもの) を直
//   index する。
//
// なぜ off-heap か (step 3a/3b review の申し送り): emulin の通常 `Memory` は
//   Java heap `byte[]` chunks。Java heap array は GC で移動し安定した native
//   アドレスを持たないため、`KVM_SET_USER_MEMORY_REGION.userspace_addr` には
//   使えない。`Arena.allocate` の off-heap segment は固定 native アドレスを持つ。
//
// メモリモデル (step 3d-1): guest 物理 RAM を guest 物理 0 から identity map し、
//   **guest 仮想 = guest 物理 = segment 内 offset** とする (page table も
//   identity)。よって `load8(addr)` = `guestRam.get(JAVA_BYTE, addr)`。PIE/mmap/
//   brk 等で guest 仮想 ≠ 物理 になる本格対応は step 3d-2 以降。
//
// little-endian: x86-64 guest と JVM (x86 host) は共に LE。`ValueLayout` の
//   `*_UNALIGNED` (native byte order) を使うので guest の load/store と一致し、
//   かつ guest pointer が非整列でも正しく読める。
//
// 本 step (3d-1) の範囲: linear memory (load/store 8-64) + bulk transfer +
//   string helper + in/dump を実装。alloc/mmap/brk/sigtramp/proc-maps 等の
//   VM 管理・ELF 系 method は step 3d-2 (NativeCpuBackend 統合 + ELF loader) で
//   実装するため `UnsupportedOperationException` の stub。
//
// ★OOB の扱い (step 3d-2 申し送り、code review): 範囲外アドレスへの load/store は
//   `MemorySegment` の bounds-check で `IndexOutOfBoundsException` を投げる (host
//   memory への wild access は無いので安全)。ただし software `Memory` は OOB を
//   guest SIGSEGV / EFAULT (SegfaultException 経由) に変換する。3d-2 で
//   SyscallAmd64 に繋ぐ際は、load/store を try/catch で囲み IndexOutOfBounds を
//   emulin の segfault path にマップする (= 同じ fault 契約にする) 必要がある。
//
// ★ELF/stack の扱い (step 3d-2 申し送り、code review): 通常の ELF 実行では
//   PT_LOAD は `Elf.load_body` が Memory の segment[].buf[] に直接書き (MemoryBackend
//   経由でない)、stack/auxv は guest 仮想 ~0x7fff_0000_0000 に書かれる。本 backend は
//   guest 仮想=物理=offset の identity map かつ有限 size なので、それらは届かない /
//   OOB になる。3d-2 では (a) PT_LOAD を bulkStoreToMem で guest 物理に配置し
//   page table を build する native ELF loader、(b) guest 仮想→物理 変換 (PIE/mmap/
//   high stack 対応) が要る (alloc 系 stub を実装するだけでは不十分)。
package emulin;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class NativeMemoryBackend implements MemoryBackend {

  // KVM がマップする guest 物理 RAM。off-heap、固定 native アドレス。
  private final MemorySegment guestRam;
  private final long          size;

  /**
   * @param guestRam off-heap MemorySegment (page-aligned、KVM_SET_USER_MEMORY_REGION
   *                 でマップ済 or マップ予定の guest 物理 RAM)
   */
  public NativeMemoryBackend( MemorySegment guestRam ) {
    this.guestRam = guestRam;
    this.size     = guestRam.byteSize();
  }

  /** KVM_SET_USER_MEMORY_REGION.userspace_addr 用の native アドレス */
  public long address() { return guestRam.address(); }
  /** guest 物理 RAM サイズ (= KVM_SET_USER_MEMORY_REGION.memory_size) */
  public long sizeBytes() { return size; }
  /** 下層 segment (KVM bindings へ渡す用) */
  public MemorySegment segment() { return guestRam; }

  // ===== linear memory (guest 仮想=物理=offset、LE) =====

  @Override public byte load8 ( long address ) { return guestRam.get( ValueLayout.JAVA_BYTE, address ); }
  @Override public short load16( long address ) { return guestRam.get( ValueLayout.JAVA_SHORT_UNALIGNED, address ); }
  @Override public int   load32( long address ) { return guestRam.get( ValueLayout.JAVA_INT_UNALIGNED, address ); }
  @Override public long  load64( long address ) { return guestRam.get( ValueLayout.JAVA_LONG_UNALIGNED, address ); }

  @Override public boolean store8 ( long address, int data ) {
    guestRam.set( ValueLayout.JAVA_BYTE, address, (byte) data );
    return true;
  }
  @Override public void store16( long address, short value ) { guestRam.set( ValueLayout.JAVA_SHORT_UNALIGNED, address, value ); }
  @Override public void store32( long address, int   value ) { guestRam.set( ValueLayout.JAVA_INT_UNALIGNED,   address, value ); }
  @Override public void store64( long address, long  value ) { guestRam.set( ValueLayout.JAVA_LONG_UNALIGNED,  address, value ); }

  // ===== bulk transfer (MemorySegment.copy = arraycopy 相当の intrinsic) =====

  @Override public void bulkLoad( long address, byte[] buf, int len ) {
    MemorySegment.copy( guestRam, ValueLayout.JAVA_BYTE, address, buf, 0, len );
  }
  @Override public void bulkLoadFromMem( long srcAddr, byte[] dst, int dstOff, int len ) {
    MemorySegment.copy( guestRam, ValueLayout.JAVA_BYTE, srcAddr, dst, dstOff, len );
  }
  @Override public void bulkStoreToMem( long dstAddr, byte[] src, int srcOff, int len ) {
    MemorySegment.copy( src, srcOff, guestRam, ValueLayout.JAVA_BYTE, dstAddr, len );
  }
  @Override public void bulkZero( long dstAddr, int len ) {
    guestRam.asSlice( dstAddr, len ).fill( (byte) 0 );
  }
  @Override public boolean fetch( long address, byte[] buf ) {
    MemorySegment.copy( guestRam, ValueLayout.JAVA_BYTE, address, buf, 0, buf.length );
    return true;
  }

  // ===== range check / debug =====

  @Override public boolean in( long address ) { return address >= 0 && address < size; }

  @Override public void dump( long address, int len ) {
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < len; i++ ) {
      sb.append( String.format( "%02x ", guestRam.get( ValueLayout.JAVA_BYTE, address + i ) & 0xFF ) );
    }
    System.err.println( "[native-mem] dump 0x" + Long.toHexString( address ) + ": " + sb );
  }

  // ===== string helper =====

  // ★ Memory.storeString と完全一致させる (semantic parity)。戻り値は「書いた
  //   byte 数」ではなく **NUL の次アドレス** (address + len + 1)。chained-write の
  //   契約 (次の文字列をその戻り値に続けて書く) が Memory 呼び出し側に存在する。
  @Override public long storeString( long address, String str ) {
    byte[] bytes = str.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    MemorySegment.copy( bytes, 0, guestRam, ValueLayout.JAVA_BYTE, address, bytes.length );
    address += bytes.length;
    guestRam.set( ValueLayout.JAVA_BYTE, address, (byte) 0 );  // NUL 終端
    address++;
    return address;
  }
  // ★ Memory.loadString と完全一致 (Phase 27 step 42)。byte 列を集めて UTF-8 で
  //   decode する。per-byte の (char) キャスト (Latin-1) は非 ASCII の guest path
  //   (例: Hungarian ő/ú を含む cert ファイル名) を化けさせる既知バグなので不可。
  //   10000 byte で打ち切り (Memory と同じ防御上限)。
  @Override public String loadString( long address ) {
    int len;
    for( len = 0; len < 10000; len++ ) {
      if( 0 == guestRam.get( ValueLayout.JAVA_BYTE, address + len ) ) break;
    }
    byte[] bytes = new byte[ len ];
    MemorySegment.copy( guestRam, ValueLayout.JAVA_BYTE, address, bytes, 0, len );
    return new String( bytes, java.nio.charset.StandardCharsets.UTF_8 );
  }

  // ===== 無害な lifecycle / debug (no-op / null) =====

  @Override public void release_buffers() { /* Arena が segment を所有。明示 free 不要 */ }
  @Override public String get_symbol( long address ) { return null; }  // ELF symbol 情報なし

  // ===== VM 管理 / ELF (step 3d-2 で実装、現状 stub) =====

  private static UnsupportedOperationException todo( String m ) {
    return new UnsupportedOperationException(
        "NativeMemoryBackend." + m + " not implemented yet (issue #221 step 3d-2: "
        + "PIE/mmap/brk/page-table 管理 + ELF loader)" );
  }

  @Override public long    alloc( long adrs, int size ) { throw todo( "alloc" ); }
  @Override public long    alloc_and_map( long adrs, int size, int fd, int offset ) { throw todo( "alloc_and_map" ); }
  @Override public long    alloc_and_map( long adrs, int size, int fd, int offset, int prot ) { throw todo( "alloc_and_map" ); }
  @Override public long    alloc_huge( long addr, long fullAlignedSize, int prot ) { throw todo( "alloc_huge" ); }
  @Override public int     realloc( long old_address, int size ) { throw todo( "realloc" ); }
  @Override public int     free( long address, int size ) { throw todo( "free" ); }
  @Override public long    get_curbrk() { throw todo( "get_curbrk" ); }
  @Override public boolean set_curbrk( long _brk ) { throw todo( "set_curbrk" ); }
  @Override public long    ensureSigtramp() { throw todo( "ensureSigtramp" ); }
  @Override public void    set_map_path( long addr, String path ) { throw todo( "set_map_path" ); }
  @Override public String  genProcSelfMaps() { throw todo( "genProcSelfMaps" ); }
}
