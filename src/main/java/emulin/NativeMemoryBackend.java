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
  private Syscall             syscall;   // file-backed mmap で .so を読むための file 層 (connect_devices で設定)

  /** file-backed mmap (ld.so の .so map) 用に syscall 層を接続する。 */
  public void setSyscall( Syscall s ) { this.syscall = s; }

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

  // ============================================================================
  //  非 identity MMU (issue #221 step 3d-2c-8): 疎な guest 仮想 → compact な物理プール
  // ----------------------------------------------------------------------------
  //   4-level 4KB page table。page table 自身は物理プールの予約域 [PT_BASE, DATA_BASE) に
  //   置き guest 仮想にはマップしない (CPU の walker は CR3+物理で読むので vaddr 不要)。
  //   data ページは DATA_BASE から bump 割当。load/store/bulk は virt2phys で変換してから
  //   物理プールに触る。これで 0x400000 / 0x7ffff7... / 高位 stack が compact な物理に乗る。
  // ============================================================================
  private static final long PAGE      = 0x1000L;
  private static final long PHYS_MASK = 0x000FFFFFFFFFF000L;  // PTE の物理アドレス部
  private static final long PTE_P  = 1L << 0;
  private static final long PTE_RW = 1L << 1;
  private static final long PTE_US = 1L << 2;
  private static final long PML4_PHYS = 0x1000L;     // PML4 の物理アドレス (= CR3)
  private static final long PT_BASE   = 0x1000L;     // page table 領域の先頭 (PML4 含む)
  private static final long DATA_BASE = 0x800000L;   // 8MB: data ページ割当の先頭 (下は PT 領域)
  private long ptNext   = PT_BASE + PAGE;            // 次に割り当てる page table ページ
  private long dataNext = DATA_BASE;                 // 次に割り当てる data ページ
  private long cachedVpage = -1, cachedPpage = -1;   // 単一エントリ TLB (sequential アクセス用)
  private boolean mmuActive = false;                 // MMU 有効化フラグ (false 中は raw offset)

  /** MMU を有効化 (PML4 を zero 初期化済とみなす)。NativeCpuBackend が連携初期化時に呼ぶ。 */
  public void enableMmu() { mmuActive = true; cachedVpage = -1; }
  /** CR3 用 PML4 物理アドレス */
  public long pml4Phys() { return PML4_PHYS; }

  // raw 物理アクセス (page table 操作用、変換しない)
  private long physGet64( long phys ) { return guestRam.get( ValueLayout.JAVA_LONG_UNALIGNED, phys ); }
  private void physSet64( long phys, long v ) { guestRam.set( ValueLayout.JAVA_LONG_UNALIGNED, phys, v ); }

  private long allocPt() {                     // zeroed page table ページ (Arena 0 初期化済)
    long p = ptNext; ptNext += PAGE;
    if( ptNext > DATA_BASE ) throw new IllegalStateException( "native MMU: page table 領域枯渇" );
    return p;
  }
  private long allocData() {                   // zeroed data ページ
    long p = dataNext; dataNext += PAGE;
    if( dataNext > size ) throw new IllegalStateException( "native MMU: 物理プール枯渇 (size=0x" + Long.toHexString(size) + ")" );
    return p;
  }

  /** vaddr の 4KB ページを物理 phys に map (中間 table は US=1、leaf は user 引数で US 制御)。 */
  public void mapPage( long vaddr, long phys, boolean user ) {
    long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF;
    long i2 = (vaddr >>> 21) & 0x1FF, i1 = (vaddr >>> 12) & 0x1FF;
    long link = PTE_P | PTE_RW | PTE_US;     // 中間 entry は常に US=1 (US は全 level の AND)
    long pdpt = nextTable( PML4_PHYS + i4 * 8, link );
    long pd   = nextTable( pdpt + i3 * 8, link );
    long pt   = nextTable( pd + i2 * 8, link );
    physSet64( pt + i1 * 8, (phys & PHYS_MASK) | PTE_P | PTE_RW | (user ? PTE_US : 0) );
    cachedVpage = -1;                         // TLB 無効化
  }
  // entry が present ならその物理 table を、無ければ新規 PT を割当てて link する。
  private long nextTable( long entryPhys, long link ) {
    long e = physGet64( entryPhys );
    if( (e & PTE_P) != 0 ) return e & PHYS_MASK;
    long t = allocPt();
    physSet64( entryPhys, (t & PHYS_MASK) | link );
    return t;
  }

  /** [vaddr, vaddr+bytes) の各ページに物理を割当てて map (既 map ページは据置)。user 領域。 */
  public void mapRange( long vaddr, long bytes, boolean user ) {
    long v0 = vaddr & ~(PAGE - 1);
    long v1 = (vaddr + bytes + PAGE - 1) & ~(PAGE - 1);
    for( long v = v0; v < v1; v += PAGE ) {
      if( virt2phys( v ) < 0 ) mapPage( v, allocData(), user );
    }
  }
  /** [vaddr, vaddr+bytes) を supervisor (US=0) で map (LSTAR stub 等、ring-0 専用ページ)。 */
  public void mapSupervisor( long vaddr, long bytes ) {
    long v0 = vaddr & ~(PAGE - 1);
    long v1 = (vaddr + bytes + PAGE - 1) & ~(PAGE - 1);
    for( long v = v0; v < v1; v += PAGE ) {
      if( virt2phys( v ) < 0 ) mapPage( v, allocData(), false );
    }
  }

  /** guest 仮想 → 物理 (page table walk + TLB)。未 map は -1。 */
  private long virt2phys( long vaddr ) {
    if( !mmuActive ) return vaddr;            // MMU 無効中は identity (初期化前/互換)
    long vpage = vaddr & ~(PAGE - 1);
    if( vpage == cachedVpage ) return cachedPpage + (vaddr & (PAGE - 1));
    long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF;
    long i2 = (vaddr >>> 21) & 0x1FF, i1 = (vaddr >>> 12) & 0x1FF;
    long e = physGet64( PML4_PHYS + i4 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) + i3 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) + i2 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) + i1 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    long ppage = e & PHYS_MASK;
    cachedVpage = vpage; cachedPpage = ppage;
    return ppage + (vaddr & (PAGE - 1));
  }
  // 物理アドレスへ翻訳 (未 map は IllegalStateException = guest の wild access)。
  private long xlat( long vaddr ) {
    long p = virt2phys( vaddr );
    if( p < 0 ) throw new IllegalStateException( "native MMU: unmapped guest vaddr 0x" + Long.toHexString( vaddr ) );
    return p;
  }

  // ===== linear memory (虚 → 物 変換、LE) =====

  //   多 byte アクセスが page を跨ぐと物理ページが非連続なので byte 分解する。
  //   同一 page (大多数) は xlat 1 回 + 直接アクセスの fast path。
  private static boolean samePage( long a, int n ) { return (a & ~(PAGE - 1)) == ((a + n - 1) & ~(PAGE - 1)); }

  @Override public byte load8 ( long a ) { return guestRam.get( ValueLayout.JAVA_BYTE, xlat( a ) ); }
  @Override public short load16( long a ) {
    if( samePage( a, 2 ) ) return guestRam.get( ValueLayout.JAVA_SHORT_UNALIGNED, xlat( a ) );
    return (short) ( (load8( a ) & 0xFF) | ((load8( a + 1 ) & 0xFF) << 8) );
  }
  @Override public int load32( long a ) {
    if( samePage( a, 4 ) ) return guestRam.get( ValueLayout.JAVA_INT_UNALIGNED, xlat( a ) );
    int v = 0; for( int i = 0; i < 4; i++ ) v |= (load8( a + i ) & 0xFF) << (8 * i); return v;
  }
  @Override public long load64( long a ) {
    if( samePage( a, 8 ) ) return guestRam.get( ValueLayout.JAVA_LONG_UNALIGNED, xlat( a ) );
    long v = 0; for( int i = 0; i < 8; i++ ) v |= (long) (load8( a + i ) & 0xFF) << (8 * i); return v;
  }

  @Override public boolean store8 ( long a, int data ) {
    guestRam.set( ValueLayout.JAVA_BYTE, xlat( a ), (byte) data );
    return true;
  }
  @Override public void store16( long a, short v ) {
    if( samePage( a, 2 ) ) guestRam.set( ValueLayout.JAVA_SHORT_UNALIGNED, xlat( a ), v );
    else { store8( a, v & 0xFF ); store8( a + 1, (v >> 8) & 0xFF ); }
  }
  @Override public void store32( long a, int v ) {
    if( samePage( a, 4 ) ) guestRam.set( ValueLayout.JAVA_INT_UNALIGNED, xlat( a ), v );
    else for( int i = 0; i < 4; i++ ) store8( a + i, (v >> (8 * i)) & 0xFF );
  }
  @Override public void store64( long a, long v ) {
    if( samePage( a, 8 ) ) guestRam.set( ValueLayout.JAVA_LONG_UNALIGNED, xlat( a ), v );
    else for( int i = 0; i < 8; i++ ) store8( a + i, (int) ((v >> (8 * i)) & 0xFF) );
  }

  // ===== bulk transfer (MemorySegment.copy = arraycopy 相当の intrinsic) =====

  @Override public void bulkLoad( long address, byte[] buf, int len ) { copyOut( address, buf, 0, len ); }
  @Override public void bulkLoadFromMem( long srcAddr, byte[] dst, int dstOff, int len ) { copyOut( srcAddr, dst, dstOff, len ); }
  @Override public void bulkStoreToMem( long dstAddr, byte[] src, int srcOff, int len ) { copyIn( dstAddr, src, srcOff, len ); }
  @Override public boolean fetch( long address, byte[] buf ) { copyOut( address, buf, 0, buf.length ); return true; }
  @Override public void bulkZero( long dstAddr, int len ) {
    int pos = 0;
    while( pos < len ) {
      long v = dstAddr + pos;
      int off = (int) (v & (PAGE - 1)), chunk = Math.min( len - pos, (int) (PAGE - off) );
      guestRam.asSlice( xlat( v ), chunk ).fill( (byte) 0 );
      pos += chunk;
    }
  }
  // guest(vaddr) → dst[dstOff..] を len byte。page 境界で virt→phys 変換しつつ copy。
  private void copyOut( long vaddr, byte[] dst, int dstOff, int len ) {
    int pos = 0;
    while( pos < len ) {
      long v = vaddr + pos;
      int off = (int) (v & (PAGE - 1)), chunk = Math.min( len - pos, (int) (PAGE - off) );
      MemorySegment.copy( guestRam, ValueLayout.JAVA_BYTE, xlat( v ), dst, dstOff + pos, chunk );
      pos += chunk;
    }
  }
  // src[srcOff..] → guest(vaddr) を len byte。
  private void copyIn( long vaddr, byte[] src, int srcOff, int len ) {
    int pos = 0;
    while( pos < len ) {
      long v = vaddr + pos;
      int off = (int) (v & (PAGE - 1)), chunk = Math.min( len - pos, (int) (PAGE - off) );
      MemorySegment.copy( src, srcOff + pos, guestRam, ValueLayout.JAVA_BYTE, xlat( v ), chunk );
      pos += chunk;
    }
  }

  // ===== range check / debug =====

  @Override public boolean in( long address ) {
    if( !mmuActive ) return address >= 0 && address < size;
    return virt2phys( address ) >= 0;
  }

  @Override public void dump( long address, int len ) {
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < len; i++ ) {
      sb.append( String.format( "%02x ", load8( address + i ) & 0xFF ) );
    }
    System.err.println( "[native-mem] dump 0x" + Long.toHexString( address ) + ": " + sb );
  }

  // ===== string helper =====

  // ★ Memory.storeString と完全一致させる (semantic parity)。戻り値は「書いた
  //   byte 数」ではなく **NUL の次アドレス** (address + len + 1)。chained-write の
  //   契約 (次の文字列をその戻り値に続けて書く) が Memory 呼び出し側に存在する。
  @Override public long storeString( long address, String str ) {
    byte[] bytes = str.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    copyIn( address, bytes, 0, bytes.length );
    store8( address + bytes.length, 0 );                       // NUL 終端
    return address + bytes.length + 1;                         // NUL の次アドレス
  }
  // ★ Memory.loadString と完全一致 (Phase 27 step 42)。byte 列を集めて UTF-8 で
  //   decode する。per-byte の (char) キャスト (Latin-1) は非 ASCII の guest path
  //   (例: Hungarian ő/ú を含む cert ファイル名) を化けさせる既知バグなので不可。
  //   10000 byte で打ち切り (Memory と同じ防御上限)。
  @Override public String loadString( long address ) {
    int len;
    for( len = 0; len < 10000; len++ ) {
      if( 0 == load8( address + len ) ) break;
    }
    byte[] bytes = new byte[ len ];
    copyOut( address, bytes, 0, len );
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

  // ===== anonymous mmap (issue #221 step 3d-2c-8: 非 identity MMU 版) =====
  //   仮想と物理を分離したので、mmap は高位仮想帯 (MMAP_BASE から下方) に仮想アドレスを取り、
  //   各ページに物理を割当てて map し、その仮想アドレスを返す。heap (curbrk から上方の低位) とは
  //   仮想空間が完全に分離するので衝突しない (物理ページは共通 allocData から distinct に取る)。
  //   addr 指定 (MAP_FIXED) はその仮想に map。prot は一律 RW で無視。fd>=0 (file-backed mmap=
  //   ld.so の .so map) は動的リンク step で実装。munmap (free) は no-op success。
  private static final long MMAP_BASE = 0x7ffff0000000L;  // mmap 領域上端 (Linux mmap_base 帯近く)
  private long mmapTop = MMAP_BASE;
  private long anonMmap( long adrs, int sz ) {
    long len = ( (long) sz + (PAGE - 1) ) & ~(PAGE - 1);
    if( len <= 0 ) len = PAGE;
    long va;
    if( adrs != 0 ) { va = adrs & ~(PAGE - 1); }      // MAP_FIXED 相当
    else { mmapTop -= len; va = mmapTop; }            // kernel-chooses: 高位から下方 bump
    // anonymous mmap は zero-fill page を返す (kernel semantics)。
    //   未 map ページ      → allocData (Arena 0 初期化済) で fresh zero ページを map。
    //   既 map ページ      → MAP_FIXED が既存 mapping に被さるケース。stale 内容を zero クリア。
    //     ★ld.so は libc 全体を file-backed で予約 mmap した後、.bss を MAP_ANON|MAP_FIXED で
    //       上書きして zero 化する。この時 mapRange は既 map ページを skip するので、予約で
    //       読んだ file byte が残り _IO_stdfile_1_lock 等が非ゼロ → futex(WAIT) で永久 hang した。
    for( long v = va; v < va + len; v += PAGE ) {
      if( virt2phys( v ) < 0 ) mapPage( v, allocData(), true );  // fresh page (allocData が zero)
      else                     bulkZero( v, (int) PAGE );         // 既 map page の stale を zero
    }
    return va;
  }
  @Override public long    alloc( long adrs, int size ) { return anonMmap( adrs, size ); }
  @Override public long    alloc_and_map( long adrs, int size, int fd, int offset ) { return alloc_and_map( adrs, size, fd, offset, 0 ); }
  @Override public long    alloc_and_map( long adrs, int size, int fd, int offset, int prot ) {
    long va = anonMmap( adrs, size );   // 仮想割当 + page table 構築 (addr=0 は高位帯、!=0 は MAP_FIXED)
    if( fd >= 0 ) {
      // file-backed mmap (ld.so が libc.so 等を map): file の [offset, offset+size) を guest に読む。
      //   MAP_FIXED が既 map ページに被さる場合も内容は読み込む (replace 内容を上書き)。file が
      //   size より短ければ残りは 0 のまま (mmap の zero-fill = BSS)。software Memory.alloc_and_map
      //   と同じ FileSeek+FileRead 経路。
      byte[] tmp = new byte[ size ];
      syscall.FileSeek( fd, offset, FileAccess.SEEK_SET );
      int n = syscall.FileRead( fd, tmp );
      if( n > 0 ) copyIn( va, tmp, 0, n );
    }
    return va;
  }
  @Override public long    alloc_huge( long addr, long fullAlignedSize, int prot ) {
    return -12L;   // multi-GB anonymous mmap (JSC gigacage 等) は物理プールに入らず ENOMEM
  }
  @Override public int     realloc( long old_address, int size ) { throw todo( "realloc" ); }
  @Override public int     free( long address, int size ) { return 0; }   // munmap: bump-alloc なので no-op success

  // ===== brk (非 identity MMU 版) =====
  //   curbrk は guest 仮想アドレス。初期 curbrk は connect_devices が ELF 由来 brk で seedBrk
  //   する (その時点では map しない)。brk が成長したら新ページを mapRange で割当てる。
  private long curbrk = 0;
  /** 初期 brk を map せずに設定 (connect_devices から)。 */
  public void seedBrk( long brk ) { curbrk = brk; }
  @Override public long    get_curbrk() { return curbrk; }
  @Override public boolean set_curbrk( long _brk ) {
    if( _brk < 0 ) return false;
    if( _brk > curbrk ) mapRange( curbrk, _brk - curbrk, true );  // grow: 新ページを物理割当 + map
    curbrk = _brk;                                                // shrink は unmap せず curbrk だけ下げる
    return true;
  }
  @Override public long    ensureSigtramp() { throw todo( "ensureSigtramp" ); }
  @Override public void    set_map_path( long addr, String path ) { /* 診断用 (segfault dump の lib 特定)。native では no-op */ }
  @Override public String  genProcSelfMaps() { throw todo( "genProcSelfMaps" ); }
}
