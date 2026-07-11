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
    this.guestRam  = guestRam;
    this.size      = guestRam.byteSize();
    this.DATA_BASE = Math.max( 0x800000L, ( size / 128 ) & ~(PAGE - 1) );  // PT 領域を pool に比例 (既定 8MB)
    this.dataNext  = DATA_BASE;
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
  // ★ DATA_BASE = page table 領域 [PT_BASE, DATA_BASE) と data 領域 [DATA_BASE, size) の境界。
  //   pool が大きいほど多くの vaddr を map し leaf PT が増えるので pool サイズに比例させる
  //   (size/128: 512MB→8MB[既定据置]、8GB→64MB)。data 1 page あたり leaf PT は ~1/512 page なので
  //   size/128 は leaf+中間+疎マッピング分を十分カバーする。constructor で確定 (instance final)。
  private final long DATA_BASE;
  private long ptNext   = PT_BASE + PAGE;            // 次に割り当てる page table ページ
  private long dataNext;                             // 次に割り当てる data ページ (= DATA_BASE、ctor で init)
  private boolean mmuActive = false;                 // MMU 有効化フラグ (false 中は raw offset)
  // ★ multi-vCPU (issue #221 pthread): guestMem は全 vCPU thread が共有する。syscall 層
  //   (call_amd64 の futex/read/write 等) は複数 worker thread から並行に load/store するので、
  //   単一エントリ TLB (instance field) は data race になる → 持たず毎回 page walk する。walk は
  //   8-byte aligned な physGet64 数回で安価、native は guest 実行が実 CPU 側なので hot path でない。
  //   page table を変更する経路 (mapPage/mapRange/mmap/brk) は mmuLock で直列化し、read 経路
  //   (virt2phys/xlat/load/store) は lock-free (aligned PTE は atomic、leaf を最後に publish)。
  private final Object mmuLock = new Object();

  /** MMU を有効化 (PML4 を zero 初期化済とみなす)。NativeCpuBackend が連携初期化時に呼ぶ。 */
  public void enableMmu() { ensure( PML4_PHYS, PAGE ); mmuActive = true; }   // chunk0(PML4) を先に backing

  // ---- GPA base (issue #221 step 3e-whp-7、fork-on-WHP) ----
  //   WHP は 1 process につき 1 partition しか guest memory を map できない (§4.4rr) ため、JVM 全体で
  //   単一 partition を共有し process ごとに別 GPA slot へ pool を map する。vCPU の hardware walker は
  //   GPA で page table を辿るので、page table entry には「gpaBase + pool offset」を格納し、Java 側の
  //   walk (virt2phys 等) は entry から gpaBase を引いて pool offset に戻す。KVM は per-process VM
  //   (gpa 0 に map) のままなので常に 0 = 全演算が従来と同値 (byte-identical)。
  private long gpaBase = 0;
  /** この pool が partition 内で map される GPA base。最初の mapPage より前に設定すること。 */
  public void setGpaBase( long base ) {
    if( mmuActive ) throw new IllegalStateException( "setGpaBase は enableMmu 前に呼ぶこと" );
    gpaBase = base;
  }
  public long gpaBase() { return gpaBase; }

  /** issue #379: 使用済み pool offset の上端 (= data bump top)。fork 子の pool を
   *  縮小 retry する際の floor に使う (子 pool は親の [0, dataNext) を複製するので
   *  この値以上のサイズが要る)。data は DATA_BASE から上方、page table は [PT_BASE,
   *  DATA_BASE) なので dataNext が常に最大の使用 offset。 */
  public long usedTop() { return dataNext; }

  /** CR3 用 PML4 物理アドレス (GPA。WHP の fork/exec 子は slot base が乗る)。 */
  public long pml4Phys() { return gpaBase + PML4_PHYS; }

  // ---- GPA backing hook (issue #304: WHP lazy commit) ----
  //   allocPt/allocData/enableMmu/duplicate が「pool のこの offset 範囲を今から使う」瞬間に呼ぶ。
  //   WHP backend (WhpGpaBacking) はその chunk を MEM_COMMIT + WHvMapGpaRange し、commit charge を
  //   guest の実使用量に比例させる (pool 全体の eager commit を廃し、fork 連鎖時の system commit
  //   limit 圧迫を緩和)。物理 RAM は Windows の demand-zero なので元々遅延、本 hook は commit charge
  //   (pagefile 予約) の遅延が目的。
  //   ★ KVM は backing を attach しない (null = no-op) ので allocPt/allocData の返値・PTE 内容・
  //     bump pointer (ptNext/dataNext) は一切変わらず byte-identical を保つ。mmap は元から demand-paged。
  //   ★ ensure は allocPt/allocData 経由 (= mmuLock 下) または boot 中 single-thread からのみ呼ばれる
  //     ので backend 内では直列。実際の commit/map の thread-safety は実装側 (WhpGpaBacking) が担う。
  public interface GpaBacking {
    /** pool offset [poolOffset, poolOffset+len) を使う前に backing (commit + map) を保証する。 */
    void ensure( long poolOffset, long len );
  }
  private GpaBacking backing = null;          // 既定 null = KVM / 従来経路は完全 no-op
  /** WHP backend で commit-on-map hook を接続する (KVM は呼ばない)。enableMmu / 最初の alloc より前に。 */
  public void setGpaBacking( GpaBacking b ) { this.backing = b; }
  private void ensure( long off, long len ) { GpaBacking b = backing; if( b != null ) b.ensure( off, len ); }

  // ===== fork: 親アドレス空間を子の新プールに複製 (issue #221 step 3d-2c-20) =====
  //   page table は pool-relative な物理 offset を格納する (CR3=PML4_PHYS=0x1000、data ページは
  //   DATA_BASE=0x800000 から) ので、使用済み prefix [0, dataNext) を子プールへ verbatim copy すれば、
  //   子は独立した同一アドレス空間を持つ: 子プールでも CR3=0x1000 が有効で、copy された page table の
  //   物理 offset がそのまま子プール内の copy された data ページを指す (絶対 host アドレスを含まないので
  //   別プールでも valid)。MMU の bump pointer (ptNext/dataNext) / mmap top / brk も複製する。これ以後
  //   親と子は別プールで独立に成長する (= fork のアドレス空間分離)。
  //
  //   呼び出し前提: 親 vCPU は fork trap で停止中 (NativeCpuBackend.eval の call_amd64 内、親の eval
  //   thread が本メソッドを呼ぶ) なので親プールは quiescent。並行 worker (pthread) が brk/mmap で
  //   page table を変更しうるので、scalar 読取り + prefix copy を mmuLock 下で atomic にして一貫
  //   snapshot を取る (read 経路 load/store は lock-free のまま影響しない)。
  /** @param childPool 子の新規物理プール (byteSize >= 親 size、KvmBindings.mmap で確保済)
   *  @param childGpaBase 子 pool の GPA base。KVM = 0 (per-process VM、従来通り verbatim copy のみ)。
   *    WHP (step 3e-whp-7) = 子の GPA slot。親と異なる場合、copy 後に全 page table entry の GPA を
   *    delta (childGpaBase - 親 gpaBase) だけ rebase する ([PT_BASE, ptNext) は bump 割当の page table
   *    専用領域なので、present entry を機械的に書き換えれば良い)。 */
  public NativeMemoryBackend duplicate( MemorySegment childPool, long childGpaBase ) {
    return duplicate( childPool, childGpaBase, null );
  }
  /** @param childBacking 子 pool の commit-on-map hook (WHP、issue #304)。KVM は null (従来通り)。
   *    copy 前に子 pool の [0, dataNext) を commit するために、duplicate の中で child に配線する。 */
  public NativeMemoryBackend duplicate( MemorySegment childPool, long childGpaBase, GpaBacking childBacking ) {
    NativeMemoryBackend child = new NativeMemoryBackend( childPool );
    child.backing = childBacking;             // WHP: 子 pool の commit-on-map hook (KVM=null=no-op)
    synchronized( mmuLock ) {
      child.gpaBase   = childGpaBase;
      child.ptNext    = this.ptNext;
      child.dataNext  = this.dataNext;
      child.mmapTop   = this.mmapTop;
      child.curbrk    = this.curbrk;
      child.brkHigh   = this.brkHigh;
      child.mmuActive = this.mmuActive;
      child.stackBottomVaddr = this.stackBottomVaddr;   // fork 子も [stack] を正しく報告できるよう継承
      child.mmapRegions.putAll( this.mmapRegions );      // issue #304: mmap 領域追跡を子へ複製 (realloc 整合)
      child.fileBacked.copyFrom( this.fileBacked );       // issue #403: file-backed 範囲も継承 (子 madvise が PT_LOAD/.so を zero 化しないため)
      // issue #527: file huge 領域も継承 (present ページは下で copy されるが、未 fault の reserve 部分は
      //   子の faultIn が file を読む)。channel は host path から子用に開き直す (親の munmap/close と独立
      //   させるため)。開き直せない場合 (file が消えた等) は親の channel を owned=false で共有 —
      //   親が先に閉じたら子の fault は zero-fill + 警告に落ちる (fileHugeFill 参照)。
      {
        java.util.HashMap<java.nio.channels.FileChannel,java.nio.channels.FileChannel> reopened = new java.util.HashMap<>();
        for( java.util.Map.Entry<Long,FileHugeRegion> e : this.fileHugeRegions.entrySet() ) {
          FileHugeRegion r = e.getValue();
          java.nio.channels.FileChannel nch = reopened.get( r.ch );
          boolean owned = true;
          if( nch == null ) {
            try { nch = java.nio.channels.FileChannel.open( java.nio.file.Paths.get( r.hostPath ),
                                                            java.nio.file.StandardOpenOption.READ ); }
            catch( Exception ex ) { nch = r.ch; owned = false; }
            reopened.put( r.ch, nch );
          } else if( nch == r.ch ) owned = false;   // 前の piece で再 open に失敗し共有になった channel
          child.fileHugeRegions.put( e.getKey(), new FileHugeRegion( r.base, r.len, r.fileOff, nch, r.hostPath, owned ) );
        }
      }
      child.maxReserveLen = this.maxReserveLen;           // review #6/#7: 下方走査 bound も継承 (子の faultIn/munmap 整合)
      child.freePages.addAll( this.freePages );          // issue #334: free-list を子へ複製 (子も同 prefix を copy 済)
      // WHP lazy commit (issue #304): 親 pool は使用済み chunk しか commit していない。旧実装は
      //   [0, dataNext) を一括 copy していたが、これは page table 予約域の未使用ギャップ
      //   [ptNext, DATA_BASE) (誰も ensure しない = reserve-only) を読み、Windows 実機で git clone の
      //   最初の fork が EXCEPTION_ACCESS_VIOLATION @ jlong_disjoint_arraycopy (= MemorySegment.copy の
      //   8byte intrinsic) で即死した (KVM は mmap demand-paged で reserve 概念が無く顕在化しなかった)。
      //   そこで使用済みの 2 領域だけを別々に copy する:
      //     [0, ptNext)          = page 0(未使用) + PML4 + 全 page table
      //     [DATA_BASE, dataNext) = 割当済 data ページ
      //   間のギャップは常にゼロ (誰も書かない) かつ子 pool も新規ゼロなので、飛ばしても子の内容は
      //   同一 = KVM byte-identical を保つ (旧コードの「ゼロ→ゼロ copy」と等価)。各 copy 先を先に
      //   commit (reserve-only だと host write が access violation。KVM childBacking=null は no-op)。
      child.ensure( 0L, this.ptNext );
      MemorySegment.copy( this.guestRam, 0L, childPool, 0L, this.ptNext );
      long dataLen = this.dataNext - DATA_BASE;
      if( dataLen > 0 ) {
        child.ensure( DATA_BASE, dataLen );
        MemorySegment.copy( this.guestRam, DATA_BASE, childPool, DATA_BASE, dataLen );
      }
      // GPA rebase (WHP): page table entry は「GPA = gpaBase + pool offset」を格納するので、親と子で
      //   gpaBase が違えば全 present entry を delta だけずらす。delta=0 (KVM / 同一 slot) は no-op。
      long delta = childGpaBase - this.gpaBase;
      if( delta != 0 ) {
        for( long p = PT_BASE; p < this.ptNext; p += 8 ) {
          long e = childPool.get( ValueLayout.JAVA_LONG_UNALIGNED, p );
          if( (e & PTE_P) == 0 ) continue;
          long gpa = (e & PHYS_MASK) + delta;
          childPool.set( ValueLayout.JAVA_LONG_UNALIGNED, p, (gpa & PHYS_MASK) | (e & ~PHYS_MASK) );
        }
      }
      // issue #675: MAP_SHARED ページを子 pool に alias し直す (copy でなく同一 memfd ページの共有)。
      //   PTE は verbatim copy 済みで同じ pool offset を指すので、子 pool の同 offset に同じ arena
      //   offset を MAP_FIXED すれば親子が同一物理ページを見る。prefix copy が書いた bytes は alias で
      //   破棄される (内容は arena と同一なので無損失)。alias 失敗ページは copy のまま (共有されない
      //   だけで内容は正しい)。sharedFileMaps も継承し、子の msync / write(2) 伝播 (#616) を生かす。
      if( !this.sharedPages.isEmpty() && SharedArena.enabled() ) {
        long childBase = childPool.address();
        for( java.util.Map.Entry<Long,long[]> e : this.sharedPages.entrySet() ) {
          long[] sp = e.getValue();
          try {
            MemorySegment r = KvmBindings.mmap( MemorySegment.ofAddress( childBase + sp[1] ), PAGE,
                KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
                KvmBindings.MAP_SHARED | KvmBindings.MAP_FIXED, SharedArena.fd(), sp[0] );
            if( r.address() == childBase + sp[1] ) {
              SharedArena.ref( sp[0] );
              child.sharedPages.put( e.getKey(), new long[]{ sp[0], sp[1] } );
            }
          } catch( Throwable ignore ) {}
        }
      }
      child.sharedFileMaps.putAll( this.sharedFileMaps );
      child.mayHaveSharedFileMaps616 = this.mayHaveSharedFileMaps616;
    }
    return child;
  }

  // raw 物理アクセス (page table 操作用、変換しない)
  private long physGet64( long phys ) { return guestRam.get( ValueLayout.JAVA_LONG_UNALIGNED, phys ); }
  private void physSet64( long phys, long v ) { guestRam.set( ValueLayout.JAVA_LONG_UNALIGNED, phys, v ); }

  // native pool / page-table 枯渇を表す例外。amd64_mmap / amd64_mremap が catch して -ENOMEM を
  //   ゲストに返す (JVM スレッドを巻き込んで落とさず、Linux 同様にゲストへ OOM を委ねる。claude/V8 等が
  //   巨大 mmap でプールを使い切ったとき crash でなく ENOMEM になる)。IllegalStateException を継承する
  //   ので、これを catch しない既存経路の挙動 (= 従来通り上位へ伝播) は変わらない。
  public static final class NativeOom extends IllegalStateException {
    NativeOom( String m ) { super( m ); }
  }

  private long allocPt() {                     // zeroed page table ページ (Arena 0 初期化済)
    long p = ptNext; ptNext += PAGE;
    if( ptNext > DATA_BASE ) throw new NativeOom( "native MMU: page table 領域枯渇" );
    ensure( p, PAGE );                         // WHP: この PT page の chunk を commit+map (KVM no-op)
    return p;
  }
  private long allocData() {                   // zeroed data ページ
    if( !freePages.isEmpty() ) {               // issue #334: 回収済み物理を再利用 (mmap は zero-fill 契約)
      long r = freePages.pop();                //   chunk は初回確保時に ensure 済なので再 ensure 不要
      guestRam.asSlice( r, PAGE ).fill( (byte) 0 );   // 前 use の stale を消してゼロ化
      return r;
    }
    long p = dataNext; dataNext += PAGE;
    if( dataNext > size ) throw new NativeOom( "native MMU: 物理プール枯渇 (size=0x" + Long.toHexString(size) + ")" );
    ensure( p, PAGE );                         // WHP: この data page の chunk を commit+map (KVM no-op)
    return p;
  }

  /** vaddr の 4KB ページを物理 phys に map (中間 table は US=1、leaf は user 引数で US 制御)。
   *  mmuLock 下で呼ぶこと (mapRange/mapSupervisor/anonMmap 経由は取得済)。leaf PTE を最後に
   *  physSet64 で publish するので、lock-free な reader は「未 map か完全 map か」のみ観測する。 */
  public void mapPage( long vaddr, long phys, boolean user ) {
    synchronized( mmuLock ) {
      long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF;
      long i2 = (vaddr >>> 21) & 0x1FF, i1 = (vaddr >>> 12) & 0x1FF;
      long link = PTE_P | PTE_RW | PTE_US;     // 中間 entry は常に US=1 (US は全 level の AND)
      long pdpt = nextTable( PML4_PHYS + i4 * 8, link );
      long pd   = nextTable( pdpt + i3 * 8, link );
      long pt   = nextTable( pd + i2 * 8, link );
      // entry は GPA (= gpaBase + pool offset) を格納 (vCPU の hardware walker が辿るため)
      physSet64( pt + i1 * 8, ((phys + gpaBase) & PHYS_MASK) | PTE_P | PTE_RW | (user ? PTE_US : 0) );
    }
  }
  // entry が present ならその table の pool offset を、無ければ新規 PT を割当てて link する。
  //   引数 entryPhys は pool offset、entry の中身は GPA (gpaBase を足し引きして変換)。
  private long nextTable( long entryPhys, long link ) {
    long e = physGet64( entryPhys );
    if( (e & PTE_P) != 0 ) return (e & PHYS_MASK) - gpaBase;
    long t = allocPt();
    physSet64( entryPhys, ((t + gpaBase) & PHYS_MASK) | link );
    return t;
  }

  /** vaddr の leaf PTE を clear (unmap) し、マップされていた pool offset を返す (未 map は -1)。
   *  issue #334: free() が物理を回収するために使う。中間 table は据置 (他ページが使う)。
   *  mmuLock 下で呼ぶこと。 */
  private long unmapPage( long vaddr ) {
    long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF;
    long i2 = (vaddr >>> 21) & 0x1FF, i1 = (vaddr >>> 12) & 0x1FF;
    long e = physGet64( PML4_PHYS + i4 * 8 );           if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i3 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i2 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    long leafAddr = (e & PHYS_MASK) - gpaBase + i1 * 8;
    long leaf = physGet64( leafAddr );                  if( (leaf & PTE_P) == 0 ) return -1;
    physSet64( leafAddr, 0 );                           // PTE を clear = unmap (leaf を 1 回で publish)
    return (leaf & PHYS_MASK) - gpaBase;                // マップされていた pool offset
  }

  /** [vaddr, vaddr+bytes) の各ページに物理を割当てて map (既 map ページは据置)。user 領域。
   *  check (virt2phys) と map (mapPage) を mmuLock 下で atomic にし、並行 mmap で同一ページを
   *  二重 map しない (二重だと後者の allocData ページで前者のデータが消える)。 */
  public void mapRange( long vaddr, long bytes, boolean user ) {
    long v0 = vaddr & ~(PAGE - 1);
    long v1 = (vaddr + bytes + PAGE - 1) & ~(PAGE - 1);
    synchronized( mmuLock ) {
      for( long v = v0; v < v1; v += PAGE ) {
        if( virt2phys( v ) < 0 ) mapPage( v, allocData(), user );
      }
    }
  }
  /** [vaddr, vaddr+bytes) を supervisor (US=0) で map (LSTAR stub 等、ring-0 専用ページ)。 */
  public void mapSupervisor( long vaddr, long bytes ) {
    long v0 = vaddr & ~(PAGE - 1);
    long v1 = (vaddr + bytes + PAGE - 1) & ~(PAGE - 1);
    synchronized( mmuLock ) {
      for( long v = v0; v < v1; v += PAGE ) {
        if( virt2phys( v ) < 0 ) mapPage( v, allocData(), false );
      }
    }
  }

  /** guest 仮想 → 物理 (page table walk)。未 map は -1。lock-free: 各 PTE は 8-byte aligned で
   *  physGet64 は atomic read、mapPage は leaf を最後に publish するので reader は「未 map か完全
   *  map か」のみ観測する。TLB は持たない (multi-vCPU で shared instance field が race するため)。 */
  private long virt2phys( long vaddr ) {
    if( !mmuActive ) return vaddr;            // MMU 無効中は identity (初期化前/互換)
    long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF;
    long i2 = (vaddr >>> 21) & 0x1FF, i1 = (vaddr >>> 12) & 0x1FF;
    // entry の中身は GPA (= gpaBase + pool offset) なので、pool への physGet64 前に gpaBase を引く
    long e = physGet64( PML4_PHYS + i4 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i3 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i2 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i1 * 8 ); if( (e & PTE_P) == 0 ) return -1;
    return (e & PHYS_MASK) - gpaBase + (vaddr & (PAGE - 1));
  }
  // issue #392 review #13: vaddr の walk で最初に absent な level の stride を返す
  //   (PML4E absent→512GB / PDPTE absent→1GB / PDE absent→2MB / leaf 到達→PAGE)。free() が
  //   reserve-only な大領域の absent stride をまとめて skip するのに使う (mmuLock 下で呼ぶ)。
  private long absentStride( long vaddr ) {
    if( !mmuActive ) return PAGE;
    long i4 = (vaddr >>> 39) & 0x1FF, i3 = (vaddr >>> 30) & 0x1FF, i2 = (vaddr >>> 21) & 0x1FF;
    long e = physGet64( PML4_PHYS + i4 * 8 );            if( (e & PTE_P) == 0 ) return 1L << 39;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i3 * 8 ); if( (e & PTE_P) == 0 ) return 1L << 30;
    e = physGet64( (e & PHYS_MASK) - gpaBase + i2 * 8 ); if( (e & PTE_P) == 0 ) return 1L << 21;
    return PAGE;
  }
  // 物理アドレスへ翻訳 (未 map は SegfaultException = kernel-side の bad user pointer)。
  private long xlat( long vaddr ) {
    long p = virt2phys( vaddr );
    if( p < 0 ) {
      // 戦略B: kernel-side fault (syscall 層が reserve ページに touch = copy_to_user 相当)。faultIn を試す。
      if( NATIVE_PF && faultIn( vaddr, true ) ) { p = virt2phys( vaddr ); if( p >= 0 ) return p; }
      // issue #682: 旧実装は IllegalStateException を投げ、NativeCpuBackend の generic catch で
      //   「eval failed」= guest thread crash になっていた。software と同じ Memory.SegfaultException を
      //   投げれば、syscall dispatch (FAULT_AS_EFAULT) が -EFAULT に変換する (bad user pointer は
      //   EFAULT が POSIX 正)。SEGV_MAPERR(1)。
      throw new Memory.SegfaultException( vaddr, 1 );
    }
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
  // hint を honor してよい仮想の上限。MMAP_BASE から下方 bump する kernel-chooses 帯は pool size
  //   (≤64GB 想定) 分しか降りないので、その帯に hint mapping が居座って後続 bump と同一ページを
  //   alias しないよう、十分下 (2TB 下) で切る。これ以上の hint は kernel-chooses に relocate
  //   (MAP_FIXED 無し hint の relocate は Linux 契約上常に合法)。
  private static final long HINT_VA_MAX = 0x7E0000000000L;
  // issue #392 review #9: kernel-chooses bump-down の下限。PIE text/heap 帯 (ET_DYN base 0x555555554000
  //   ≈ 93.7TB) の上に置き、≥2GB cage を何度も予約しても mmapTop が heap/text に侵入 or underflow して
  //   live mapping を alias するのを防ぐ (越えたら NativeOom=ENOMEM)。floor〜MMAP_BASE で ~35TB 確保。
  private static final long MMAP_FLOOR = 0x600000000000L;
  private long mmapTop = MMAP_BASE;
  // issue #304 (#221 step 3d-2): mmap 領域の base -> (page-aligned) byte size 追跡。realloc(mremap) が
  //   old_size を知り「末尾が空なら同一 VA で in-place 拡大、塞がっていれば relocate」を software
  //   (Memory.alloclist) と同じ意味論で判断するため。全 access は mmuLock 下 (load/store は触れない)
  //   なので plain TreeMap で可。fork (duplicate) で子へ複製する。
  // issue #392 (戦略B #PF demand paging): anon mmap / alloc_huge を reserve-only (PTE not-present) 化し、
  //   guest/kernel が触れた時の #PF / xlat miss で faultIn が demand 割当する。
  //   ★2026-06-23 案B: default ON (KVM+WHP の hermetic 56/56 + 実 binary claude --version 完走で gate 全クリア)。
  //   eager 強制の escape hatch = EMULIN_NO_NATIVE_PF=1 (または EMULIN_NATIVE_PF=0)。NativeCpuBackend.NATIVE_PF と一致必須。
  static final boolean NATIVE_PF = System.getenv( "EMULIN_NO_NATIVE_PF" ) == null
                                   && !"0".equals( System.getenv( "EMULIN_NATIVE_PF" ) );
  // issue #392 review #15: debug 用 env flag は static final で cache (CLAUDE.md 規約)。
  static final boolean TRACE_MMAP = System.getenv( "EMULIN_TRACE_MMAP" ) != null;
  private final java.util.TreeMap<Long,Long> mmapRegions = new java.util.TreeMap<>();
  // issue #559-native: mprotect/mmap で PROT_NONE / PROT_READ にされたページを追跡 (page整列 addr → prot 下位3bit)。
  //   mmapRegions は範囲だけで prot を持たないため別管理する。faultIn がこれを見て「読/書禁止ページは
  //   demand map せず #PF を SIGSEGV(SEGV_ACCERR) にする」判定に使う。RW 完全なページは登録しない (= 通常)。
  private final java.util.TreeMap<Long,Integer> protectedPages = new java.util.TreeMap<>();
  // issue #559-native: mprotect/mmap の prot を per-page に反映。RW 完全なら解除、非 RW は登録。
  @Override public void setProtection( long addr, long len, int prot ) {
    long start = addr & ~(PAGE - 1);
    long end   = ( addr + len + (PAGE - 1) ) & ~(PAGE - 1);
    synchronized( mmuLock ) {
      for( long p = start; Long.compareUnsigned( p, end ) < 0; p += PAGE ) {
        if( ( prot & 3 ) == 3 ) protectedPages.remove( p );   // PROT_READ|PROT_WRITE = 通常 → 解除
        else                    protectedPages.put( p, prot & 7 );
      }
    }
  }
  // issue #559-native: addr が保護ページ (非 RW) に属するか。行 765 (#PF wild) が MAPERR/ACCERR の
  //   出し分けに使う。null なら未保護、非 null は prot (下位3bit)。
  public Integer protOf( long addr ) {
    synchronized( mmuLock ) { return protectedPages.get( addr & ~(PAGE - 1) ); }
  }

  // issue #617: file-backed mapping の EOF を越えるページ (file 長を超える map 範囲)。not-present に
  //   保ち、guest がアクセスすると #PF → faultIn が false → #PF 経路が SIGBUS(BUS_ADRERR) を配送する。
  private final java.util.TreeSet<Long> beyondEofPages = new java.util.TreeSet<>();
  public boolean isBeyondEof( long addr ) {
    synchronized( mmuLock ) { return beyondEofPages.contains( addr & ~(PAGE - 1) ); }
  }
  // issue #392 review #6/#7: mmapRegions は overlapping/nested entry を許す (cage 内の MAP_FIXED guard /
  //   merge-max 等)。floorEntry 単体や固定回数 scan では深い nesting で包含 region を取りこぼすので、
  //   「最大 region 長」を保持し、それを使って下方走査を bound する (base < vaddr-maxReserveLen の entry は
  //   len≤maxReserveLen ゆえ vaddr に届かない=安全に打ち切れる)。insert 毎に更新、duplicate で子へ複製。
  private long maxReserveLen = 0;
  // issue #527: file-backed ≥2GiB mmap (git index-pack の巨大 pack 等) の demand paging 用領域追跡。
  //   alloc_huge (anon) と同じ reserve-only 予約に加え、faultIn 時にどの file のどの offset を読むかを
  //   持つ。ch は guest fd と独立に host path から開いた read 専用 channel — guest が mmap 後に fd を
  //   close/seek しても読める (POSIX: mapping は fd close 後も有効) し、positional read (read(buf,pos))
  //   なので他 guest thread の同 fd read と seek 位置を奪い合わない。
  //   entries は pairwise disjoint (挿入時と munmap/上書き時に removeFileHugeRange で分割・除去) —
  //   containingFileHuge は floorEntry 1 発で判定できる。全 access は mmuLock 下。fork は duplicate で
  //   子へ複製 (channel は host path から開き直し)。
  private static final class FileHugeRegion {
    final long base, len, fileOff;                       // guest VA / byte 長 / base に対応する file offset
    final java.nio.channels.FileChannel ch;
    final String hostPath;                               // fork 子の再 open 用
    final boolean owned;                                 // false = fork で親と共有 (再 open 失敗時) → close しない
    FileHugeRegion( long base, long len, long fileOff, java.nio.channels.FileChannel ch, String hostPath, boolean owned ) {
      this.base = base; this.len = len; this.fileOff = fileOff; this.ch = ch; this.hostPath = hostPath; this.owned = owned;
    }
  }
  private final java.util.TreeMap<Long,FileHugeRegion> fileHugeRegions = new java.util.TreeMap<>();

  // issue #616: MAP_SHARED file mapping (通常サイズ、eager copy-in) の追跡。file への write(2) を
  //   同一 file の map guest ページに反映する (write→map coherence)。key = guest VA。
  private static final class ShFileMap {
    final long va, fileOff; final int size, fd; final String hostPath;
    ShFileMap( long va, int size, long fileOff, int fd, String hostPath ) {
      this.va = va; this.size = size; this.fileOff = fileOff; this.fd = fd; this.hostPath = hostPath;
    }
  }
  private final java.util.TreeMap<Long,ShFileMap> sharedFileMaps = new java.util.TreeMap<>();
  private volatile boolean mayHaveSharedFileMaps616 = false;
  @Override public boolean mayHaveSharedFileMaps( ) { return mayHaveSharedFileMaps616; }

  // ===== issue #675: fork 跨ぎ MAP_SHARED 共有 (KVM/Linux 専用) =====
  //   fork は「子は別 pool + 使用済み prefix の verbatim copy」なので、そのままでは MAP_SHARED
  //   領域もコピー分離されてしまう。共有ページの物理 backing を per-process pool の anon メモリ
  //   でなく全 process 共通の memfd (SharedArena) に置き、pool 内の該当 offset へ
  //   mmap(MAP_SHARED|MAP_FIXED) で alias する:
  //   - PTE / virt2phys / load-store / faultIn は無変更 (pool offset の裏が memfd ページになるだけ)。
  //   - fork: 子 pool の同じ pool offset に同じ memfd offset を alias し直す → 親子の KVM memslot
  //     (HVA は別 pool) が同一 page cache 物理ページに解決され、真の共有になる。KVM は host 側の
  //     再マップに MMU notifier で追随する (balloon/postcopy と同じ標準機構)。
  //   - WHP は partition slot への host aliasing に Windows section object が要るため未対応
  //     (SharedArena.enabled()=false で全経路が従来の copy 動作)。
  static final class SharedArena {
    private static final long SYS_ftruncate = 77, SYS_memfd_create = 319;
    private static int  fd = -2;          // -2=未試行, -1=不可, >=0=memfd
    private static long limit = 0;        // ftruncate 済みサイズ
    private static long bump = 0;         // 次の未使用 arena offset
    private static final java.util.ArrayDeque<Long> freeList = new java.util.ArrayDeque<>();
    private static final java.util.HashMap<Long,Integer> refs = new java.util.HashMap<>();
    static synchronized boolean enabled() {
      if( fd == -2 ) {
        fd = -1;
        try {
          if( KvmBindings.probe() ) {                       // KVM (Linux) のみ。WHP は object() 不可
            MemorySegment name = java.lang.foreign.Arena.global().allocate( 16 );   // zero 初期化 = NUL 終端
            byte[] nm = { 'e','m','u','l','i','n','-','s','h','m' };
            MemorySegment.copy( nm, 0, name, ValueLayout.JAVA_BYTE, 0, nm.length );
            long r = KvmBindings.syscall3( SYS_memfd_create, name.address(), 1 /*MFD_CLOEXEC*/, 0 );
            if( r >= 0 ) fd = (int) r;
          }
        } catch( Throwable ignore ) { fd = -1; }
      }
      return fd >= 0;
    }
    static synchronized int fd() { return fd; }
    /** 1 ページ確保して参照数 1 で返す。失敗 = -1 (呼出側は従来の非共有動作に落とす)。 */
    static synchronized long allocPage() {
      if( fd < 0 ) return -1;
      Long f = freeList.poll();
      if( f != null ) { refs.put( f, 1 ); return f; }
      long off = bump;
      if( off + 0x1000L > limit ) {
        long nl = Math.max( limit * 2, 1L << 24 );          // 16MB から倍々 (sparse、実 RAM は触った分だけ)
        try { if( KvmBindings.syscall3( SYS_ftruncate, fd, nl, 0 ) != 0 ) return -1; }
        catch( Throwable t ) { return -1; }
        limit = nl;
      }
      bump = off + 0x1000L;
      refs.put( off, 1 );
      return off;
    }
    static synchronized void ref( long off )   { refs.merge( off, 1, Integer::sum ); }
    static synchronized void deref( long off ) {
      Integer r = refs.get( off );
      if( r == null ) return;
      if( r <= 1 ) { refs.remove( off ); freeList.push( off ); }
      else refs.put( off, r - 1 );
    }
  }
  /** この process の共有ページ台帳: vaPage → {arenaOff, poolOff}。全 access は mmuLock 下。 */
  private final java.util.TreeMap<Long,long[]> sharedPages = new java.util.TreeMap<>();
  private long poolBase() { return guestRam.address(); }

  // [va, va+len) の present ページを arena ページで alias する (mmuLock 下、eager 割当済み前提)。
  //   alias 直後に zero fill (anon MAP_SHARED の zero-fill 契約。file の copy-in は後で上書きする)。
  //   arena 確保や mmap が失敗したページは従来の非共有 anon のまま (内容は正しく、共有だけされない)。
  private void aliasSharedRange( long va, long len ) {
    long end = ( va + len + PAGE - 1 ) & ~(PAGE - 1);
    for( long v = va & ~(PAGE - 1); Long.compareUnsigned( v, end ) < 0; v += PAGE ) {
      long poolOff = virt2phys( v );
      if( poolOff < 0 ) continue;                                    // 防御 (eager のはず)
      long aOff = SharedArena.allocPage();
      if( aOff < 0 ) return;
      try {
        MemorySegment r = KvmBindings.mmap( MemorySegment.ofAddress( poolBase() + poolOff ), PAGE,
            KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
            KvmBindings.MAP_SHARED | KvmBindings.MAP_FIXED, SharedArena.fd(), aOff );
        if( r.address() != poolBase() + poolOff ) { SharedArena.deref( aOff ); return; }
      } catch( Throwable t ) { SharedArena.deref( aOff ); return; }
      guestRam.asSlice( poolOff, PAGE ).fill( (byte) 0 );
      sharedPages.put( v, new long[]{ aOff, poolOff } );
    }
  }
  // [start,end) の共有 alias を解除し pool offset を anon backing に戻す (mmuLock 下)。
  //   munmap / MAP_FIXED 上書き (Linux の暗黙 munmap 相当) から呼ぶ。★anon に戻してからでないと、
  //   (a) anonMmap の FIXED-overlap 分岐は PTE 据置で bulkZero するため memfd ページを zero して
  //   共有相手 (fork 親/子) のデータを壊す、(b) freePages 再利用時に別用途の write が memfd に届く。
  private void clearSharedRange( long start, long end ) {
    if( sharedPages.isEmpty() ) return;
    java.util.Iterator<java.util.Map.Entry<Long,long[]>> it =
        sharedPages.subMap( start, end ).entrySet().iterator();
    while( it.hasNext() ) {
      long[] sp = it.next().getValue();
      try {
        KvmBindings.mmap( MemorySegment.ofAddress( poolBase() + sp[1] ), PAGE,
            KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
            KvmBindings.MAP_PRIVATE | KvmBindings.MAP_ANONYMOUS | KvmBindings.MAP_FIXED, -1, 0 );
      } catch( Throwable ignore ) {}
      SharedArena.deref( sp[0] );
      it.remove();
    }
  }
  /** issue #675: process 終了時に共有 arena 参照を返却する (pool 自体は呼出側が解放する)。 */
  public void releaseSharedPages() {
    synchronized( mmuLock ) {
      for( long[] sp : sharedPages.values() ) SharedArena.deref( sp[0] );
      sharedPages.clear();
    }
  }
  // issue #675: madvise(DONTNEED) の zero 化ガード。共有ページの zero 化は共有相手のデータを壊す
  //   (Linux の DONTNEED は shared mapping では内容を保持し、refault で共有オブジェクトを読み直す)。
  @Override public boolean isSharedMapped( long addr ) {
    synchronized( mmuLock ) { return sharedPages.containsKey( addr & ~(PAGE - 1) ); }
  }

  // issue #616: file への write(2)/pwrite 後、同一 host file を MAP_SHARED で map している
  //   guest ページを書込み内容で更新する (実 Linux の page cache 共有相当)。native は file mmap を
  //   eager copy-in するため、この反映が無いと write→map coherence が失われる。copyIn は present
  //   ページを上書き、未 fault ページは faultIn(更新後の file を読む)後に上書きする。
  @Override public void propagateWriteToSharedMaps( String hostPath, long fileOff, byte[] data, int len ) {
    if( !mayHaveSharedFileMaps616 || hostPath == null || len <= 0 ) return;
    java.util.ArrayList<ShFileMap> targets = new java.util.ArrayList<>();
    synchronized( mmuLock ) {
      for( ShFileMap m : sharedFileMaps.values() )
        if( hostPath.equals( m.hostPath ) ) targets.add( m );
    }
    // copyIn は自前で virt2phys/faultIn の lock を取るので mmuLock 外で呼ぶ (deadlock 回避)。
    for( ShFileMap m : targets ) {
      long mStart = m.fileOff, mEnd = mStart + (long)m.size;
      long oStart = Math.max( mStart, fileOff ), oEnd = Math.min( mEnd, fileOff + (long)len );
      if( oStart >= oEnd ) continue;
      int bufOff  = (int)( oStart - mStart );   // map VA 内 offset
      int dataOff = (int)( oStart - fileOff );
      int n       = (int)( oEnd - oStart );
      if( dataOff < 0 || dataOff + n > data.length ) continue;
      copyIn( m.va + bufOff, data, dataOff, n );
    }
  }

  // issue #617: ftruncate で file 長が変わったとき、その file を MAP_SHARED で map している領域の
  //   EOF 越え境界 (beyondEofPages) を更新する。縮小で新たに EOF を越えたページは unmap して
  //   #PF→SIGBUS にする。拡大は beyond から外す (再アクセスで demand-zero。grow-after-mmap は稀)。
  @Override public void updateFileMapEof( String hostPath, long newFileSize ) {
    if( hostPath == null ) return;
    synchronized( mmuLock ) {
      for( ShFileMap m : sharedFileMaps.values() ) {
        if( !hostPath.equals( m.hostPath ) ) continue;
        long avail = newFileSize - m.fileOff;
        if( avail < 0 ) avail = 0;
        long validBytes = ( avail + (PAGE - 1) ) & ~(PAGE - 1);
        for( long v = m.va + validBytes; v < m.va + (long)m.size; v += PAGE ) {
          if( beyondEofPages.add( v ) ) unmapPage( v );   // 新規に beyond → present なら not-present
        }
        if( validBytes > 0 ) {
          java.util.List<Long> rm = new java.util.ArrayList<>( beyondEofPages.subSet( m.va, m.va + validBytes ) );
          beyondEofPages.removeAll( rm );                 // 拡大: EOF 内に戻ったページ
        }
      }
    }
  }

  // issue #616: msync — MAP_SHARED file map の guest ページ (guest store が載っている) を backing
  //   file へ書き戻す (map→file 方向。apt の pkgcache.bin 等が使う)。native は msyncFlush が従来
  //   default no-op で、store→msync→read(2) の逆方向 coherence が失われていた。software Memory.
  //   msyncFlush と同じく file 終端を超える page 埋め分は書かない。fd close 済なら skip。
  @Override public void msyncFlush( long addr, long len ) {
    if( sharedFileMaps.isEmpty() ) return;
    long end = addr + len;
    java.util.ArrayList<ShFileMap> targets = new java.util.ArrayList<>();
    synchronized( mmuLock ) {
      for( ShFileMap m : sharedFileMaps.values() )
        if( m.va + (long)m.size > addr && m.va < end ) targets.add( m );
    }
    for( ShFileMap m : targets ) {
      long astart = m.va, aend = m.va + (long)m.size;
      long lo = Math.max( addr, astart ), hi = Math.min( end, aend );
      if( lo >= hi ) continue;
      try {
        long saved = syscall.FileSeek( m.fd, 0, FileAccess.SEEK_CUR );
        if( saved < 0 ) continue;                                   // fd close 済等
        long flen = syscall.FileSeek( m.fd, 0, FileAccess.SEEK_END );
        long fend = astart + ( flen - m.fileOff );                 // file 終端の VA
        long hi2  = Math.min( hi, fend );
        if( hi2 > lo ) {
          byte[] out = new byte[ (int)( hi2 - lo ) ];
          copyOut( lo, out, 0, out.length );                       // guest ページ読み取り
          syscall.FileSeek( m.fd, m.fileOff + ( lo - astart ), FileAccess.SEEK_SET );
          syscall.FileWrite( m.fd, out );
        }
        syscall.FileSeek( m.fd, saved, FileAccess.SEEK_SET );
      } catch( Exception ignored ) { }
    }
  }
  // issue #392 review #3: NATIVE_PF の exception 配送基盤 (GDT/IDT/PF_STUB/per-vCPU TSS/kstack) は guest の
  //   低位 user VA に eager 配置される。guest が runtime に MAP_FIXED でそこへ mmap すると anonMmap の fixed
  //   経路が present な supervisor ページを bulkZero して TSS.RSP0/IDT を破壊 → 次の #PF で triple fault (DoS)。
  //   NativeCpuBackend が tables 構築時に保護範囲を登録し、fixed mmap が踏んだら relocate する。
  private long resvBandLo = 0, resvBandHi = 0;   // [lo,hi) 保護帯 (空=未設定)
  public void setReservedBand( long lo, long hi ) { resvBandLo = lo; resvBandHi = hi; }
  private boolean hitsReservedBand( long va, long len ) {
    return Long.compareUnsigned( resvBandLo, resvBandHi ) < 0
        && Long.compareUnsigned( va, resvBandHi ) < 0 && Long.compareUnsigned( resvBandLo, va + len ) < 0;
  }
  // issue #334: free した物理 data ページ (pool offset) を再利用する free-list。mremap-grow の
  //   relocate (新領域+copy+旧 free) や munmap で解放された物理を回収し、apt 等の mremap 多用で
  //   物理プールが leak 枯渇するのを防ぐ。VA は bump-down で再利用しない (新 mmap は常に新 VA) ので
  //   recycle した物理を新 VA に張っても旧 VA の stale TLB は無害 = TLB shootdown 不要。全 access は
  //   mmuLock 下 (allocData は mapRange/anonMmap/realloc 経由で取得済、free/duplicate も取得)。
  private final java.util.ArrayDeque<Long> freePages = new java.util.ArrayDeque<>();
  // issue #392 review #9: kernel-chooses (addr=0 / hint 不可) の VA を MMAP_BASE から下方 bump する。
  //   underflow / MMAP_FLOOR 割れを検出して NativeOom=ENOMEM にし、heap/text 帯への侵入を防ぐ。mmuLock 下。
  private long bumpDown( long len ) {
    if( Long.compareUnsigned( mmapTop, len ) < 0 || Long.compareUnsigned( mmapTop - len, MMAP_FLOOR ) < 0 )
      throw new NativeOom( "native MMU: mmap VA 空間枯渇 (mmapTop=0x" + Long.toHexString( mmapTop )
          + " len=0x" + Long.toHexString( len ) + ")" );
    mmapTop -= len;
    // ★ issue #435: kernel-chooses mmap は MMAP_BASE(0x7ffff0000000) から下方へ bump するが、これは
    //   guest stack bottom(0x7fff00000000) より「上」にある(Linux とは逆で、Linux の mmap_base は
    //   stack より下)。累積割当で mmapTop が stack 保護帯 [stackBottom-STACK_PROT, stackBottom) に
    //   降りてくると、free() は inStackRegion で守るのに割当側は無防備で、その帯を map+zero-fill して
    //   main thread の parked stack(pthread_join 待ち)を破壊していた。復帰時に return address=0 →
    //   SIGSEGV(userRip=0)で codex が /quit 中に多数の worker を join する局面で非決定的に死んでいた。
    //   帯に掛かる割当は帯の「下」へジャンプして帯を丸ごと避ける(VA は bump-down で再利用しないので
    //   帯を捨てても安全。stack auto-grow の上限も同じ STACK_PROT なので headroom は不変)。
    if( stackBottomVaddr != 0 ) {
      long bandHi = stackBottomVaddr, bandLo = stackBottomVaddr - STACK_PROT;
      if( Long.compareUnsigned( mmapTop, bandHi ) < 0
          && Long.compareUnsigned( bandLo, mmapTop + len ) < 0 ) {
        if( Long.compareUnsigned( bandLo, len ) < 0 || Long.compareUnsigned( bandLo - len, MMAP_FLOOR ) < 0 )
          throw new NativeOom( "native MMU: mmap VA 空間枯渇 (stack 帯回避後 bandLo=0x"
              + Long.toHexString( bandLo ) + " len=0x" + Long.toHexString( len ) + ")" );
        mmapTop = bandLo - len;
      }
    }
    return mmapTop;
  }
  // issue #392 review #6/#7: vaddr を含む reserve region を返す (無ければ null)。overlapping/nested entry に
  //   対応するため floorEntry から下方走査するが、base < vaddr-maxReserveLen で打ち切る (それより下の region は
  //   len≤maxReserveLen ゆえ vaddr に届かない)。mmuLock 下で呼ぶこと。
  private java.util.Map.Entry<Long,Long> containingReserve( long vaddr ) {
    long bound = Long.compareUnsigned( vaddr, maxReserveLen ) >= 0 ? vaddr - maxReserveLen : 0;
    for( java.util.Map.Entry<Long,Long> e = mmapRegions.floorEntry( vaddr );
         e != null && Long.compareUnsigned( e.getKey(), bound ) >= 0; e = mmapRegions.lowerEntry( e.getKey() ) ) {
      if( Long.compareUnsigned( vaddr, e.getKey() + e.getValue() ) < 0 ) return e;   // base <= vaddr < base+len
    }
    return null;
  }
  private long anonMmap( long adrs, int sz, boolean fixed ) { return anonMmap( adrs, (long) sz, fixed, false ); }
  private long anonMmap( long adrs, int sz, boolean fixed, boolean reserveOnly ) { return anonMmap( adrs, (long) sz, fixed, reserveOnly ); }
  private long anonMmap( long adrs, long sz, boolean fixed, boolean reserveOnly ) {
    long len = ( sz + (PAGE - 1) ) & ~(PAGE - 1);
    if( len <= 0 ) len = PAGE;
    // mmapTop の bump + page table 構築を mmuLock 下で atomic に (並行 mmap で領域が重なったり
    //   同一ページを二重割当しないため)。
    synchronized( mmuLock ) {
      long va;
      if( adrs != 0 && fixed ) {                              // MAP_FIXED: その仮想に必ず map
        va = adrs & ~(PAGE - 1);
        if( NATIVE_PF && hitsReservedBand( va, len ) ) {      // review #3: 予約帯 (TSS/GDT/IDT) clobber 回避 → relocate
          if( TRACE_MMAP ) System.err.println( "[native] MAP_FIXED が予約帯を踏むため relocate: va=0x" + Long.toHexString( va ) );
          va = bumpDown( len );
        }
      }
      else if( adrs != 0 ) {
        // ★MAP_FIXED 無しの addr は hint (issue #221 step 3d-2c-32)。Linux は hint 範囲が空いて
        //   いればそこを使い、塞がっていれば kernel が別の場所を選ぶ。旧実装は hint を無条件
        //   MAP_FIXED 扱いして既 map ページを zero していたため、V8 (node) が brk heap 近傍の
        //   hint (heap top の 512KB 切下げ) で mmap した時に live な malloc top chunk を zero
        //   して glibc sysmalloc assertion (malloc.c:2599) で死んでいた。software backend は
        //   アドレス配置が違い hint が heap に当たらないため顕在化しなかった parity bug。
        va = adrs & ~(PAGE - 1);
        // issue #545: hint 上限を TASK_SIZE(2^47) に緩和する。従来の HINT_VA_MAX(126TB) は MMAP_BASE
        //   (128TB) より低く、munmap 済みの mmap 帯番地 (bumpDown で MMAP_BASE 付近に配置された領域を
        //   解放した後) への hint が上限超過で尊重されず bump に落ちていた。bumpDown 帯との衝突は下の
        //   virt2phys / overlapsReserve チェック (present / reserve-only なら free=false) で防ぐ。
        boolean free = ( va >= 0x10000 && va + len > va && va + len <= 0x800000000000L );
        if( free ) for( long v = va; v < va + len; v += PAGE ) {
          if( virt2phys( v ) >= 0 ) { free = false; break; }   // 既存 mapping (brk heap 含む) と衝突
        }
        // 戦略B: reserve-only 領域 (not-present) との衝突も判定。さもないと hint が reserve を踏んで重複 entry を
        //   作り、faultIn の floorEntry が誤領域を拾って miss する (eager では present 判定で relocate するのに揃える)。
        if( free && NATIVE_PF && overlapsReserve( va, len ) ) free = false;
        if( !free ) { va = bumpDown( len ); }                  // 塞がっている → kernel-chooses に fallback (review #9: floor guard)
      }
      else { va = bumpDown( len ); }                    // addr=0 kernel-chooses: 高位から下方 bump (review #9)
      // issue #675: この範囲が共有 alias を含むなら先に解除して anon に戻す (MAP_FIXED の
      //   暗黙 munmap 相当)。解除しないと下の bulkZero (PTE 据置) が memfd ページを zero し、
      //   共有相手 (fork 親/子) のデータを壊す。
      clearSharedRange( va, va + len );
      // anonymous mmap は zero-fill page を返す (kernel semantics)。
      //   未 map ページ      → allocData (Arena 0 初期化済) で fresh zero ページを map。
      //   既 map ページ      → MAP_FIXED が既存 mapping に被さるケース。stale 内容を zero クリア。
      //     ★ld.so は libc 全体を file-backed で予約 mmap した後、.bss を MAP_ANON|MAP_FIXED で
      //       上書きして zero 化する。この時 mapRange は既 map ページを skip するので、予約で
      //       読んだ file byte が残り _IO_stdfile_1_lock 等が非ゼロ → futex(WAIT) で永久 hang した。
      for( long v = va; v < va + len; v += PAGE ) {
        if( virt2phys( v ) < 0 ) {
          if( !reserveOnly ) mapPage( v, allocData(), true );        // fresh page (allocData が zero)
          // reserveOnly (戦略B): not-present のまま残す。guest が触れた時の #PF で faultIn が demand 割当。
        } else {
          bulkZero( v, (int) PAGE );                                  // 既 map page の stale を zero
        }
      }
      // issue #304: realloc(mremap) 用 + 戦略B reserve 領域追跡。NATIVE_PF では MAP_FIXED サブ領域 (同一 base
      //   の PROT_NONE guard page 等) が大領域 entry を put で上書き縮小して faultIn を取りこぼすので、大きい
      //   len を保持する (claude/V8 は 64MB 領域の先頭に 1-page guard を MAP_FIXED する)。
      if( NATIVE_PF ) { mmapRegions.merge( va, len, ( a, b ) -> a > b ? a : b ); if( len > maxReserveLen ) maxReserveLen = len; }
      else            mmapRegions.put( va, len );
      // issue #527: この mapping が file huge 領域に被さったら追跡を punch する (被さった範囲の fault が
      //   新 mapping の意味論=zero/eager 内容でなく file 内容で fill されるのを防ぐ)。通常は map が空で no-op。
      if( !fileHugeRegions.isEmpty() ) removeFileHugeRange( va, va + len );
      return va;
    }
  }
  // 旧シグネチャ経路 (mremap の addr=0 / i386 等、flags 情報が無い caller) は従来挙動 (addr!=0 を
  //   MAP_FIXED 扱い) を維持する。flags を知る amd64_mmap は 6 引数 alloc_and_map 経由で hint を渡す。
  @Override public long    alloc( long adrs, int size ) { return anonMmap( adrs, size, true ); }
  // issue #581: prot 省略の 4 引数版は software Memory.alloc_and_map と同じく PROT_READ|PROT_WRITE を
  //   既定にする。旧実装は prot=0 (PROT_NONE) を渡しており、6 引数版が anon 非 RW 判定 (prot&3 != 3) で
  //   その領域を setProtection(PROT_NONE) 追跡していた。すると reserve-only ページへの後続 store が
  //   faultIn で「PROT_NONE=読めない」と誤判定され demand map されず xlat が unmapped 例外を投げていた
  //   (aider/CPython の realloc→mremap relocate の copy ループ、io_uring ring 等で発火)。RW 既定なら
  //   6 引数版の setProtection 分岐に入らず通常の reserve 領域として faultIn される。
  @Override public long    alloc_and_map( long adrs, int size, int fd, long offset ) {
    return alloc_and_map( adrs, size, fd, offset, AllocInfo.PROT_READ | AllocInfo.PROT_WRITE );
  }
  @Override public long    alloc_and_map( long adrs, int size, int fd, long offset, int prot ) {
    return alloc_and_map( adrs, size, fd, offset, prot, 0x10 /* MAP_FIXED 相当 = 従来挙動 */ );
  }
  @Override public long    alloc_and_map( long adrs, int size, int fd, long offset, int prot, long flags ) {
    // 戦略B: 純 anonymous (fd<0) は reserve-only (#PF で fault-in)。file-backed (fd>=0) は copy-in のため eager。
    // issue #675: MAP_SHARED (flags&1) は fork 跨ぎ共有のため eager 割当 + arena alias する
    //   (reserve-only だと fault-in が per-process anon ページを割ってしまい共有にならない)。
    boolean shared675 = ( flags & 0x1L ) != 0 && SharedArena.enabled();
    boolean reserveOnly = NATIVE_PF && fd < 0 && !shared675;
    long va = anonMmap( adrs, size, ( flags & 0x10 ) != 0, reserveOnly );   // 0x10 = MAP_FIXED (無し = adrs は hint)
    if( shared675 && size > 0 ) synchronized( mmuLock ) { aliasSharedRange( va, size ); }   // file の copy-in (下) より前に
    // issue #617 regression fix: この [va, va+size) を再マップするので、以前この範囲を覆っていた
    //   file mapping が残した beyondEofPages エントリを消す。残ると、ld.so が library の file map
    //   の一部を MAP_FIXED anon RW (.bss) で置換した後、その anon ページへの write を faultIn が
    //   「EOF 越え」と誤判定して拒否 → SIGSEGV になり、全動的リンクバイナリが起動直後に落ちる
    //   (native backend 総崩れ)。file 再マップなら下の fd>=0 経路が正しい EOF 越えページを再登録する。
    if( size > 0 ) synchronized( mmuLock ) {
      if( !beyondEofPages.isEmpty() ) beyondEofPages.subSet( va, va + (long)size ).clear();
    }
    // issue #559-native: anon の非 RW mmap (PROT_NONE / PROT_READ の guard page) を保護追跡。
    //   faultIn がこれを見て demand map せず #PF を SIGSEGV(ACCERR) にする。
    // issue #592: 常に setProtection を呼ぶ (RW でも)。jemalloc 等は「大領域を PROT_NONE で予約
    //   → 一部を MAP_FIXED で RW に再マップ」を多用する (chunk 予約 + 段階的 commit)。旧実装は
    //   prot が RW のとき setProtection を呼ばずスキップしていたため、以前の PROT_NONE 予約が
    //   protectedPages に残ったままになり、MAP_FIXED で RW に再マップした領域への write も
    //   faultIn が古い PROT_NONE を見て SIGSEGV(ACCERR) にしていた (tmux が起動直後に確実に
    //   SIGSEGV する原因、libjemalloc のアリーナ chunk 初期化で再現)。setProtection 自身が
    //   RW (prot&3==3) のとき protectedPages.remove で正しく解除するので、常に呼んで良い。
    if( fd < 0 ) setProtection( va, size, prot );
    if( fd >= 0 ) {
      // file-backed mmap (ld.so が libc.so 等を map): file の [offset, offset+size) を guest に読む。
      //   MAP_FIXED が既 map ページに被さる場合も内容は読み込む (replace 内容を上書き)。file が
      //   size より短ければ残りは 0 のまま (mmap の zero-fill = BSS)。software Memory.alloc_and_map
      //   と同じ FileSeek+FileRead 経路。
      byte[] tmp = new byte[ size ];
      syscall.FileSeek( fd, offset, FileAccess.SEEK_SET );
      int n = syscall.FileRead( fd, tmp );
      if( n > 0 ) copyIn( va, tmp, 0, n );
      // issue #617: file が map 全域を覆わない (n < size) なら、file 内容が届くページより後は
      //   EOF 越え。not-present に戻し (eager map で present になっている) beyondEofPages に登録し、
      //   guest アクセスで #PF → SIGBUS になるようにする。EOF を含む最後の partial page は
      //   zero-pad でアクセス可 (valid = ceil(n/PAGE)*PAGE)。
      if( n < 0 ) n = 0;
      long validBytes = ( (long)n + (PAGE - 1) ) & ~(PAGE - 1);
      if( validBytes < (long)size ) {
        synchronized( mmuLock ) {
          for( long v = va + validBytes; v < va + (long)size; v += PAGE ) {
            unmapPage( v );              // present → not-present (#PF on access)
            beyondEofPages.add( v );
          }
        }
      }
      // issue #616: MAP_SHARED (flags&1) file map を追跡し、後続の write(2) を map に反映する。
      if( ( flags & 0x1L ) != 0 ) {
        String hp = null;
        try { Fileinfo fi = syscall.get_finfo( fd ); if( fi != null ) hp = fi.get_name(); } catch( Exception ig ) {}
        if( hp != null ) {
          synchronized( mmuLock ) { sharedFileMaps.put( va, new ShFileMap( va, size, offset, fd, hp ) ); }
          mayHaveSharedFileMaps616 = true;
        }
      }
    }
    return va;
  }
  @Override public long    alloc_huge( long addr, long fullAlignedSize, int prot, boolean fixed ) {
    if( !NATIVE_PF ) return -12L;   // 従来: ≥2GB anonymous mmap は物理プールに入らず ENOMEM
    // 戦略B: ≥2GB の reserve (V8/Bun の pointer-compression cage、実測 128GB) を reserve-only で受ける。
    //   PTE not-present、#PF で fault-in。anonMmap と同じ mmapRegions.merge(max) で領域追跡し、partial
    //   munmap (V8 の alignment trim) で大領域 entry を失わないようにする。
    //   ★以前 reserve-only 化で near-null(0x3f01)に至ったのは、当時 4e の region 追跡修正 (MAP_FIXED の
    //   merge-max=d87a2ab / partial munmap split=removeReserveRange=1c81c5f) が未投入で cage 内の guard
    //   page / alignment trim が大領域 entry を縮小・消去し faultIn が取りこぼしていたため。それらが入った
    //   今は解消済で、claude --version が 128GB cage を抱えたまま default 512MB pool で完走する (demand
    //   paging で触れたページだけ commit)。回帰 sys_pf_huge64 (native-pf-oracle.sh の 2-way)。
    synchronized( mmuLock ) {
      long len = ( fullAlignedSize + (PAGE - 1) ) & ~(PAGE - 1);
      if( len <= 0 ) len = PAGE;
      long va;
      // review #8: addr!=0 は hint。reserve 領域と衝突しなければ honor、塞がっていれば kernel-chooses に
      //   fallback (≥2GB ゆえ anonMmap の per-page present 走査は省略し overlapsReserve の領域照合で代替)。
      // review #16: anonMmap への delegate は不可 — anonMmap の per-page collision/map ループが 128GB cage で
      //   33M 回 iterate して hang するため。reserve-only の huge は per-page 作業を持たない別経路が必須。
      // ★ MAP_FIXED は要求アドレスへ必ず map する (overlap しても relocate しない)。V8/JSC(Bun) は巨大 cage
      //   を予約後、その内側に MAP_FIXED で sub-map する。relocate すると cage 相対ポインタが崩れ JSC が
      //   assertion crash (0xbbadbeef) / claude も対話不能になる。fixed のとき overlapsReserve を skip。
      if( addr != 0 && ( fixed || !overlapsReserve( addr & ~(PAGE - 1), len ) ) ) va = addr & ~(PAGE - 1);
      else va = bumpDown( len );                    // review #9: floor/underflow guard 付き bump
      mmapRegions.merge( va, len, ( a, b ) -> a > b ? a : b );
      if( len > maxReserveLen ) maxReserveLen = len;
      if( TRACE_MMAP )
        System.err.println( "[native][huge] reserve va=0x" + Long.toHexString( va ) + " len=0x" + Long.toHexString( len ) );
      return va;
    }
  }
  // issue #527: file-backed ≥2GiB mmap。alloc_huge と同じ reserve-only 予約 + fileHugeRegions に
  //   file backing (独立 channel + offset) を登録し、faultIn が demand で file 内容を読む。
  //   ★既知の限界 (alloc_huge と同じ): MAP_FIXED が既 present ページに被さった場合、そのページは
  //   再フォールトしないので stale 内容が残る (git index-pack 等は kernel-chooses の新規 VA なので
  //   実際には踏まない)。MAP_SHARED の write-back は非対応 (native の eager file mmap と同等)。
  @Override public long alloc_huge_file( long addr, long fullAlignedSize, int fd, long offset, int prot,
                                         boolean fixed, String hostPath ) {
    if( !NATIVE_PF ) return -12L;   // demand paging 無しでは backing できない (alloc_huge と同じ制約)
    java.nio.channels.FileChannel ch;
    try {
      // guest fd と独立の read channel。NIO FileChannel.open は Windows で FILE_SHARE_DELETE 付き
      //   (ShareDeleteFile と同じ) なので、mapping 保持中も guest の unlink/rename を妨げない。
      ch = java.nio.channels.FileChannel.open( java.nio.file.Paths.get( hostPath ),
                                               java.nio.file.StandardOpenOption.READ );
    } catch( Exception e ) {
      if( TRACE_MMAP ) System.err.println( "[native][hugefile] host open 失敗 path=" + hostPath + " : " + e );
      return -12L;
    }
    synchronized( mmuLock ) {
      long len = ( fullAlignedSize + (PAGE - 1) ) & ~(PAGE - 1);
      if( len <= 0 ) { try { ch.close(); } catch( Exception ignore ) {} return -12L; }
      long va;
      if( addr != 0 && ( fixed || !overlapsReserve( addr & ~(PAGE - 1), len ) ) ) va = addr & ~(PAGE - 1);
      else va = bumpDown( len );
      mmapRegions.merge( va, len, ( a, b ) -> a > b ? a : b );
      if( len > maxReserveLen ) maxReserveLen = len;
      removeFileHugeRange( va, va + len );   // MAP_FIXED 置換: 旧 file 領域と重ねない (disjoint 不変条件)
      fileHugeRegions.put( va, new FileHugeRegion( va, len, offset, ch, hostPath, true ) );
      if( TRACE_MMAP )
        System.err.println( "[native][hugefile] reserve va=0x" + Long.toHexString( va )
            + " len=0x" + Long.toHexString( len ) + " off=0x" + Long.toHexString( offset ) + " path=" + hostPath );
      return va;
    }
  }
  // issue #527: vaddr を含む file huge 領域 (無ければ null)。entries は disjoint なので floorEntry 1 発。
  //   mmuLock 下で呼ぶこと。file map は低位 user VA のみなので signed 比較で良い。
  private FileHugeRegion containingFileHuge( long vaddr ) {
    java.util.Map.Entry<Long,FileHugeRegion> e = fileHugeRegions.floorEntry( vaddr );
    if( e != null && vaddr < e.getValue().base + e.getValue().len ) return e.getValue();
    return null;
  }
  // issue #527: [start,end) を file huge 追跡から除去 (munmap / MAP_FIXED 上書き)。重なる entry を
  //   head/tail に分割して disjoint を維持し、fileOff も分割位置に合わせて進める。除去後にどの entry
  //   からも参照されなくなった channel は close する (分割片は同じ channel を共有するため参照走査)。
  //   mmuLock 下で呼ぶこと。
  private void removeFileHugeRange( long start, long end ) {
    if( fileHugeRegions.isEmpty() ) return;
    java.util.ArrayList<Long> kill = new java.util.ArrayList<>();
    java.util.Map.Entry<Long,FileHugeRegion> lo = fileHugeRegions.lowerEntry( start );
    if( lo != null && lo.getValue().base + lo.getValue().len > start ) kill.add( lo.getKey() );
    for( java.util.Map.Entry<Long,FileHugeRegion> e = fileHugeRegions.ceilingEntry( start );
         e != null && e.getKey() < end; e = fileHugeRegions.higherEntry( e.getKey() ) )
      kill.add( e.getKey() );
    if( kill.isEmpty() ) return;
    java.util.ArrayList<FileHugeRegion> dead = new java.util.ArrayList<>();
    for( long k : kill ) {
      FileHugeRegion r = fileHugeRegions.remove( k );
      dead.add( r );
      if( r.base < start )
        fileHugeRegions.put( r.base, new FileHugeRegion( r.base, start - r.base, r.fileOff, r.ch, r.hostPath, r.owned ) );
      long re = r.base + r.len;
      if( end < re )
        fileHugeRegions.put( end, new FileHugeRegion( end, re - end, r.fileOff + ( end - r.base ), r.ch, r.hostPath, r.owned ) );
    }
    for( FileHugeRegion r : dead ) {
      if( !r.owned ) continue;                       // fork 共有 channel は親の所有 → 子は閉じない
      boolean live = false;
      for( FileHugeRegion s : fileHugeRegions.values() ) if( s.ch == r.ch ) { live = true; break; }
      if( !live ) { try { r.ch.close(); } catch( Exception ignore ) {} }
    }
  }
  // issue #527: file huge 領域の demand fill。faulted page を含む最大 1MB block を file から読み、
  //   まだ absent なページだけ map + copy する (1 fault = 1 page だと 3GB pack で 78 万回 fault する
  //   ので block 単位で先読みする)。file read は mmuLock の外 (disk I/O で全 vCPU の fault/mmap を
  //   止めない)。lock を離す窓で munmap/MAP_FIXED 上書きされた可能性があるので、map 前に page 単位で
  //   再確認する。EOF 以降・read 失敗は 0 のまま (mmap の zero-fill 契約 / silent 破壊はしない)。
  private static final long FILE_FILL_CHUNK = 0x100000L;   // 1MB
  private boolean fileHugeFill( FileHugeRegion fr, long page ) {
    long blockStart = Math.max( fr.base, page & ~( FILE_FILL_CHUNK - 1 ) );
    long blockEnd   = Math.min( fr.base + fr.len, blockStart + FILE_FILL_CHUNK );
    int  blen = (int)( blockEnd - blockStart );
    byte[] buf = new byte[ blen ];
    try {
      long fileOff = fr.fileOff + ( blockStart - fr.base );
      java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap( buf );
      while( bb.hasRemaining() ) {
        int n = fr.ch.read( bb, fileOff + bb.position() );   // positional read: seek 位置に影響しない
        if( n < 0 ) break;                                    // EOF: 残りは 0 のまま
      }
    } catch( Exception e ) {
      // channel close (fork 共有の親が munmap した等) / IO error。zero-fill に落とすが必ず警告は出す。
      System.err.println( "[native][hugefile] read 失敗 va=0x" + Long.toHexString( page )
          + " path=" + fr.hostPath + " : " + e );
    }
    synchronized( mmuLock ) {
      for( long v = blockStart; v < blockEnd; v += PAGE ) {
        if( virt2phys( v ) >= 0 ) continue;                  // 既 present (他 vCPU / dirty) は触らない
        FileHugeRegion cur = containingFileHuge( v );
        if( cur == null || cur.ch != fr.ch ) continue;       // lock 外の窓で punch された部分は埋めない
        mapPage( v, allocData(), true );
        int off = (int)( v - blockStart );
        copyIn( v, buf, off, (int)Math.min( PAGE, blen - off ) );
      }
      // faulted page の最終保証: 上の loop で埋まらなかった (窓で file 領域から外れた) 場合、
      //   通常の reserve/wild 判定に戻す。
      if( virt2phys( page ) >= 0 ) return true;
      if( containingReserve( page ) != null || inStackRegion( page ) ) { mapPage( page, allocData(), true ); return true; }
      return false;
    }
  }
  // 戦略B: [va, va+len) が既存 reserve mmap 領域 (mmapRegions) と重なるか。hint placement が reserve-only
  //   領域 (not-present ゆえ virt2phys では空きに見える) を踏んで重複 entry を作るのを防ぐ判定。
  // issue #545: hint (非 FIXED) が使用中か判定する (amd64_mmap が isRangeMapped で hint を無視/採用、
  //   #544 の MAP_32BIT 低位探索もこれを使う)。MemoryBackend の default は常に true (寛容側) で、
  //   native では munmap 後の番地も「使用中」に見え hint が尊重されず bump に落ちていた。present page /
  //   reserve-only 予約 / stack 帯を mapped とし、完全に unmap された範囲は unmapped (= hint に使える) と返す。
  @Override public boolean isRangeMapped( long addr, long len ) {
    synchronized( mmuLock ) {
      long start = addr & ~(PAGE - 1);
      long end   = ( addr + len + (PAGE - 1) ) & ~(PAGE - 1);
      for( long p = start; Long.compareUnsigned( p, end ) < 0; p += PAGE ) {
        if( virt2phys( p ) < 0 && containingReserve( p ) == null && !inStackRegion( p ) ) return false;
      }
      return true;
    }
  }
  private boolean overlapsReserve( long va, long len ) {
    // review #7: va を含む region は containingReserve で nesting 対応の下方走査 (floorEntry 単体では深い
    //   nesting で取りこぼす)。加えて [va,va+len) 内に別 region の base があるかを ceiling で確認。
    if( containingReserve( va ) != null ) return true;                                              // va が既存 region 内
    java.util.Map.Entry<Long,Long> c = mmapRegions.ceilingEntry( va );
    if( c != null && Long.compareUnsigned( c.getKey(), va + len ) < 0 ) return true;                // [va,va+len) 内に region base
    return false;
  }
  // issue #392 (戦略B): #PF / kernel-side fault で reserve ページを demand 割当する。vaddr が reserve mmap
  //   領域 (mmapRegions) に属し未 map なら zero ページを割当して true、それ以外 (wild access) は false。
  //   ★既知の residual (review #10/#11/#C5): mmapRegions は領域の「範囲」だけを追跡し prot を持たない。よって
  //   (a) reservation 内の PROT_NONE guard ページへのアクセスも demand-map されて SIGSEGV にならない (#C5)、
  //   (b) merge(max) で同一 base に縮小 MAP_FIXED-replace された場合 stale-large 範囲を over-cover し得る (#11)、
  //   (c) その範囲を指す不正 syscall ポインタも xlat→faultIn で zero serve され EFAULT 化しない (#10)。
  //   範囲精度 (overlap/nesting/munmap) は review #6/#7 で正したが、prot 精度には prot-aware な VMA 追跡
  //   (大きめの別 refactor) が要るため本 PR scope 外。NATIVE_PF opt-in なので既定挙動には無影響。
  public boolean faultIn( long vaddr, boolean write ) {
    long page = vaddr & ~(PAGE - 1);
    FileHugeRegion fr;
    synchronized( mmuLock ) {
      if( virt2phys( page ) >= 0 ) return true;     // 他 vCPU が先に fill した race を吸収
      // issue #559-native: 保護ページ (PROT_NONE / PROT_READ への write) は demand map せず
      //   #PF を SIGSEGV(SEGV_ACCERR) にする (呼出側 = NativeCpuBackend の #PF 経路が protOf で ACCERR 判定)。
      //   PROT_READ の read は許すが、現状 read-only PTE を作らないので write も通ってしまう点は後段対応。
      Integer pr = protectedPages.get( page );
      if( pr != null ) {
        if( ( pr & 1 ) == 0 ) return false;                // PROT_NONE (読めない) → SIGSEGV
        if( ( pr & 2 ) == 0 && write ) return false;       // PROT_READ への write → SIGSEGV (ACCERR)
      }
      // issue #617: file map の EOF 越えページは demand map せず #PF を SIGBUS にする
      //   (呼出側 = NativeCpuBackend の #PF 経路が isBeyondEof で SIGBUS 判定)。
      if( beyondEofPages.contains( page ) ) return false;
      // review #6: vaddr を含む reserve region を maxReserveLen-bounded 下方走査で探す (固定 64 回 cap だと
      //   深い nesting=cage 内に 64+ sub-entry がある場合に包含 region を取りこぼし spurious SIGSEGV した)。
      // review #1 fix: stack 帯なら demand grow (Linux の stack auto-grow 相当。mmap 帯と重なる cage の munmap で
      //   stack が unmap されても再 grow できる + 初回 stack push の growth も賄う)。
      if( containingReserve( vaddr ) != null || inStackRegion( vaddr ) ) {
        // issue #435 追補: 「過剰 free された生きたページへの再フォールトをゼロページで隠蔽していないか」の
        //   診断。free() の FREE trace と突き合わせ、解放済み範囲への demand fault を検出する。
        if( SyscallAmd64.TRACE_WAKE )
          SyscallAmd64._wakeTrace( "FAULTIN va=0x" + Long.toHexString( page )
              + ( inStackRegion( vaddr ) ? " stack" : " reserve" ) + " w=" + write
              + " as=" + Integer.toHexString( System.identityHashCode( this ) ) );   // as= アドレス空間識別 (fork 子と区別)
        // issue #527: file huge 領域なら zero でなく file 内容を demand で読む (lock 外 I/O のため後段へ)。
        fr = containingFileHuge( page );
        if( fr == null ) {
          mapPage( page, allocData(), true );        // demand-zero ページ (mmap zero-fill 契約)
          return true;
        }
      } else {
        if( SyscallAmd64.TRACE_WAKE )
          SyscallAmd64._wakeTrace( "FAULTIN va=0x" + Long.toHexString( page ) + " WILD (SIGSEGV)" );
        return false;                                // どの reserve region にも属さない = wild access (→ SIGSEGV)
      }
    }
    return fileHugeFill( fr, page );                 // file read は mmuLock の外で行う
  }
  // issue #304 (#221 step 3d-2): mremap 用 realloc。amd64_mremap から呼ばれ、software Memory.realloc と
  //   同じ契約 (in-place 成功=0 で呼出側が同一 addr を維持、失敗=非 0 で呼出側が MREMAP_MAYMOVE に
  //   relocate=alloc_and_map+copy) を native bump-allocator + page-table 上で再現する。
  //     ・領域不明 (mmapRegions に無い)  → -1  (software の alloclist 不在と同じ。呼出側で relocate)
  //     ・縮小 / 現状以下                → 0   (page は据置。glibc は新 size 以上を触らない)
  //     ・拡大: 末尾 [oldEnd,newEnd) が全て未 map なら fresh zero ページを同一 VA に map して 0、
  //            1 ページでも他 mapping が居れば -1 (relocate)。他領域への食い込み corruption を回避。
  //   戻り値が software と整合するので native-oracle の stdout byte 一致を保つ。leak も無し (in-place)。
  @Override public int realloc( long old_address, int size ) {
    long newAligned = ( (long) size + (PAGE - 1) ) & ~(PAGE - 1);
    if( newAligned <= 0 ) newAligned = PAGE;
    synchronized( mmuLock ) {
      Long oldLen = mmapRegions.get( old_address );
      if( oldLen == null ) return -1;                  // 未知領域 → 呼出側で relocate
      if( newAligned <= oldLen ) return 0;             // 縮小 / 同一 → in-place (据置)
      long oldEnd = old_address + oldLen, newEnd = old_address + newAligned;
      // review #4: 末尾 [oldEnd,newEnd) が reserve-only (not-present) な隣接 anon 領域に食い込むのを検出。
      //   virt2phys は present ページしか見ないので、reserve-only 隣接を見落として上書き拡張し、重複 entry /
      //   物理共有 corruption を起こしていた。mmapRegions 照合で reserve 衝突も relocate にする。
      if( NATIVE_PF && overlapsReserve( oldEnd, newAligned - oldLen ) ) return -1;
      for( long v = oldEnd; v < newEnd; v += PAGE )
        if( virt2phys( v ) >= 0 ) return -1;           // 末尾が他 mapping と衝突 → relocate
      for( long v = oldEnd; v < newEnd; v += PAGE )
        mapPage( v, allocData(), true );               // fresh zero ページを同一 VA に in-place 追加
      mmapRegions.put( old_address, newAligned );
      return 0;
    }
  }
  // 戦略B: [start,end) の munmap を mmapRegions に反映する。重なる entry を分割/縮小し、部分 munmap で
  //   大領域 entry を丸ごと失わないようにする (V8 は領域を over-allocate→alignment 用に head/tail を munmap
  //   するので、旧 mmapRegions.remove(address) だと残った aligned 部分の faultIn が取りこぼし SIGSEGV した)。
  private void removeReserveRange( long start, long end ) {
    java.util.ArrayList<long[]> readd = new java.util.ArrayList<>();
    // review #7: base < start で [start,end) と重なる entry を「全て」split (旧実装は lowerEntry 1 個だけで、
    //   nested/overlap した深い enclosing region を取りこぼし、hole が faultable のまま残った)。maxReserveLen
    //   -bounded で下方走査し、各 overlapper を head [base,start) + tail [end,re) に分割する。
    long bound = Long.compareUnsigned( start, maxReserveLen ) >= 0 ? start - maxReserveLen : 0;
    java.util.ArrayList<Long> lowBases = new java.util.ArrayList<>();
    for( java.util.Map.Entry<Long,Long> e = mmapRegions.lowerEntry( start );
         e != null && Long.compareUnsigned( e.getKey(), bound ) >= 0; e = mmapRegions.lowerEntry( e.getKey() ) ) {
      if( Long.compareUnsigned( start, e.getKey() + e.getValue() ) < 0 ) lowBases.add( e.getKey() );   // [start,end) と重なる
    }
    for( long rb : lowBases ) {
      long re = rb + mmapRegions.remove( rb );
      readd.add( new long[]{ rb, start - rb } );                                          // head [rb,start)
      if( Long.compareUnsigned( end, re ) < 0 ) readd.add( new long[]{ end, re - end } ); // tail [end,re)
    }
    // base が [start,end) にある entry → remove して tail [end,re) を残す。
    java.util.ArrayList<Long> bases = new java.util.ArrayList<>();
    for( java.util.Map.Entry<Long,Long> e = mmapRegions.ceilingEntry( start );
         e != null && Long.compareUnsigned( e.getKey(), end ) < 0; e = mmapRegions.higherEntry( e.getKey() ) )
      bases.add( e.getKey() );
    for( long rb : bases ) {
      long re = rb + mmapRegions.remove( rb );
      if( Long.compareUnsigned( end, re ) < 0 ) readd.add( new long[]{ end, re - end } );
    }
    for( long[] a : readd ) if( a[1] > 0 ) mmapRegions.merge( a[0], a[1], ( x, y ) -> x > y ? x : y );
  }
  // issue #334: munmap / mremap-relocate の旧領域解放。物理 data ページを unmap して free-list に
  //   戻し、後続 alloc が再利用する (これで mremap-grow を繰り返す apt 等の物理 leak 枯渇を防ぐ)。
  //   VA は再利用しないので旧 VA の stale TLB は無害 (TLB shootdown 不要)。
  @Override public int free( long address, long size ) {   // issue #392 review #1: size を long 化
    if( size <= 0 ) return 0;
    synchronized( mmuLock ) {
      long start = address & ~(PAGE - 1);
      long end   = ( address + size + PAGE - 1 ) & ~(PAGE - 1);
      // issue #675: 共有 alias を解除して anon backing に戻す。下の unmapPage → freePages 再利用の
      //   前にやらないと、再利用先の write が memfd ページ (= fork 相手の共有データ) に届いてしまう。
      clearSharedRange( start, end );
      // issue #392 review #13: reserve-only な ≥2GB 領域 (V8 cage) は大半が not-present なので、
      //   全ページを 1 つずつ probe すると O(region/PAGE) (128GB=33M 回) を mmuLock 下で回し全 vCPU を
      //   stall させる。page-table の上位 level が absent な stride (PML4=512GB/PDPT=1GB/PD=2MB) は
      //   まとめて skip し、present な leaf だけ unmap する。
      long freed = 0;   // issue #435 追補: 診断 trace 用 (実際に unmap した present ページ数)
      for( long v = start; v < end; ) {
        long stride = absentStride( v );          // 上位 level が absent なら大きな stride、present 候補なら PAGE
        if( stride > PAGE ) { v = Math.min( end, ( v + stride ) & ~(stride - 1) ); continue; }
        if( NATIVE_PF && inStackRegion( v ) ) { v += PAGE; continue; }   // review #1 fix: stack 帯は munmap しない (保護)
        long phys = unmapPage( v );
        if( phys >= DATA_BASE ) { freePages.push( phys ); freed++; }   // data ページのみ回収 (PT/低位は対象外)
        v += PAGE;
      }
      // issue #435 追補: FAULTIN trace との突き合わせで「解放済み範囲への再フォールト」を検出する診断。
      if( SyscallAmd64.TRACE_WAKE && freed > 0 )
        SyscallAmd64._wakeTrace( "FREE va=0x" + Long.toHexString( start ) + "-0x" + Long.toHexString( end )
            + " pages=" + freed + " as=" + Integer.toHexString( System.identityHashCode( this ) ) );   // as= アドレス空間識別
      if( NATIVE_PF ) {
        removeReserveRange( start, end );   // 部分 munmap で大領域 entry を消さない (split)
        removeFileHugeRange( start, end );  // issue #527: file huge 追跡も同期 (残すと再 mmap 域が file 内容で fill される)
      }
      else            mmapRegions.remove( address );
      // issue #616: 解放範囲の MAP_SHARED file map 追跡を除去 (stale VA への誤 propagate 防止)。
      if( !sharedFileMaps.isEmpty() ) sharedFileMaps.subMap( start, end ).clear();
      // issue #617: 解放範囲の EOF 越えページ追跡も除去 (同 VA を再 mmap した際の誤 SIGBUS 防止)。
      if( !beyondEofPages.isEmpty() ) beyondEofPages.subSet( start, end ).clear();
    }
    return 0;
  }

  // ===== brk (非 identity MMU 版) =====
  //   curbrk は guest 仮想アドレス。初期 curbrk は connect_devices が ELF 由来 brk で seedBrk
  //   する (その時点では map しない)。brk が成長したら新ページを mapRange で割当てる。
  private volatile long curbrk = 0;   // volatile: 複数 thread が brk する場合の安全な publish
  private long brkHigh = 0;           // heap が過去に到達した最高 brk (shrink 後も page は保持、mmuLock 下で更新)
  /** 初期 brk を map せずに設定 (connect_devices から)。 */
  private long startBrk = 0;   // issue #547-native: 初期 brk (heap 領域の起点)。genProcSelfMaps の [heap] 判定用。
  public void seedBrk( long brk ) { curbrk = brk; brkHigh = brk; startBrk = brk; }
  @Override public long    get_curbrk() { return curbrk; }
  @Override public boolean set_curbrk( long _brk ) {
    if( _brk < 0 ) return false;
    // 並行 brk (複数 thread) の check-grow-update を mmuLock 下で atomic に。
    synchronized( mmuLock ) {
      long retainedTop = brkHigh;   // issue #605: この呼び出し前の high-water (再露出範囲の上限)
      if( _brk > brkHigh ) {
        // issue #546-native: TASK_SIZE(2^47) 超の巨大 brk 要求は即頭打ち (成長走査 [brkHigh,_brk) が
        //   数百兆ページに及んで hang するのを防ぐ。Linux も TASK_SIZE 超 brk は現 break を返す)。
        if( Long.compareUnsigned( _brk, 0x800000000000L ) > 0 ) return false;
        // ★成長先に他の mapping (hint mmap が heap 直上に置いた領域等) が居たら Linux 同様 brk を
        //   失敗させる (issue #221 step 3d-2c-32)。黙って mapRange すると既 map ページが skip され
        //   heap と mmap 領域が同じページを alias して silent corruption になる。glibc malloc は
        //   brk 失敗時 mmap arena に fallback するので失敗は正しい挙動。
        //   検査開始 = heap 未所有の最初のページ (brkHigh を含むページは heap 自身が map 済みうる)。
        for( long v = ( (brkHigh - 1) & ~(PAGE - 1) ) + PAGE; v < _brk; v += PAGE ) {
          if( virt2phys( v ) >= 0 ) return false;
          // issue #546-native: reserve-only (not-present) な mmap 領域も vma として頭打ちする。anon mmap は
          //   demand paging で not-present なので virt2phys だけでは素通りし、brk が mmap 域を突き抜けていた。
          if( NATIVE_PF && containingReserve( v ) != null ) return false;
        }
        mapRange( brkHigh, _brk - brkHigh, true );  // grow: 新ページを物理割当 + map
        brkHigh = _brk;
      }
      // issue #605: 実 Linux は成長した brk 領域を常にゼロで見せる。native は shrink で page を
      //   保持・再利用するため ([curbrk,brkHigh) 保持)、curbrk を再成長で上げると保持ページの旧データが
      //   再露出する。再露出する retained 範囲 [curbrk, min(_brk, retainedTop)) だけを zero-fill する
      //   (retainedTop=この呼び出し前の brkHigh。それ超の新規ページは allocData が zero 済みなので対象外)。
      if( _brk > curbrk ) {
        long zeroEnd = Math.min( _brk, retainedTop );
        if( zeroEnd > curbrk ) bulkZero( curbrk, (int)( zeroEnd - curbrk ) );
      }
      curbrk = _brk;   // shrink は unmap せず curbrk だけ下げる ([curbrk,brkHigh) の page は保持・再利用)
    }
    return true;
  }
  @Override public long    ensureSigtramp() { throw todo( "ensureSigtramp" ); }
  @Override public void    set_map_path( long addr, String path ) { /* 診断用 (segfault dump の lib 特定)。native では no-op */ }

  // issue #403: file-backed VA 範囲の追跡。madvise(MADV_DONTNEED) が file 内容を持つ page
  //   (fd>=0 mmap / ELF PT_LOAD) を zero 化しないようにする。NativeCpuBackend.connect_devices
  //   が PT_LOAD copy 後に登録し、amd64_mmap が fd>=0 mmap を登録する。
  private final FileBackedRanges fileBacked = new FileBackedRanges();
  @Override public void    registerFileBacked  ( long addr, long len ) { fileBacked.add( addr, len ); }
  @Override public boolean isFileBacked        ( long addr )           { return fileBacked.contains( addr ); }
  @Override public void    unregisterFileBacked( long addr, long len ) { fileBacked.remove( addr, len ); }

  // ===== /proc/self/maps の動的生成 (issue #221 step 3d-2c-23) =====
  //   ★ grep/gawk 等が pcre2/glibc 経由で /proc/self/maps を読む。旧 stub は throw して native を
  //   busy-hang させていた (sed/tr/sort 等は読まないので無事だった)。software (Memory.genProcSelfMaps) が
  //   segment[]/alloclist から生成するのに対し、native は **page table を walk** して実 mapped user range を
  //   列挙する (page table が「何が map 済か」の権威)。
  //   prot: native の leaf PTE は一律 RW で r-x/rw を区別できないので user 領域は "rwxp"、stack は
  //   "[stack]" 付きで報告する。region 内容は grep 等の stdout に出ない (init 用に内部で読むだけ) ので、
  //   output の native==software parity は保たれる。glibc の stack 境界検出 (__pthread_getattr_np) が
  //   [stack] 行を要求するので stack region を識別して付す。
  //   ※ prot を一律 rwxp にするので rodata も writable 報告になり __readonly_area の %n 保護は native で
  //   緩くなる (software は p_flags で正確)。coverage 対象 binary は %n-into-rodata を使わないので影響なし
  //   (ddskk/emacs 系の %n 厳密判定が要るケースは software backend 側で担保)。
  private long stackBottomVaddr = 0;   // [stack] 識別用 (NativeCpuBackend.setup_initial_stack が seed)
  /** 初期 stack の bottom (= 最高位アドレス) を設定。genProcSelfMaps が該当 region を [stack] と報告する。 */
  public void seedStack( long stackBottom ) { stackBottomVaddr = stackBottom; }
  // issue #392 review #1 fix: guest stack 領域 [stackBottomVaddr-STACK_PROT, stackBottomVaddr) を保護する。
  //   emulin native は stack を高位 VA (0x7fff00000000 から下方成長) に置くが、mmap 帯も近傍を下方 bump する
  //   ため V8 cage が stack と VA 重なりする。#1 で munmap が (long 化で) 実際に効くようになった結果、cage の
  //   trim munmap が stack ページを巻き込んで unmap し stack を破壊していた (pre-fix は munmap no-op で露見せず)。
  //   → free() はこの帯を unmap せず、faultIn は demand grow する (Linux の stack auto-grow + guard 相当)。
  private static final long STACK_PROT = 0x800000L;   // 8MB (RLIMIT_STACK 既定相当)
  private boolean inStackRegion( long va ) {
    return stackBottomVaddr != 0 && Long.compareUnsigned( va, stackBottomVaddr ) < 0
        && Long.compareUnsigned( stackBottomVaddr - va, STACK_PROT ) <= 0;
  }
  @Override public String genProcSelfMaps() {
    if( !mmuActive ) return "";   // MMU 未有効 (初期化前) は空 maps。実際には guest 実行中 (=有効) でしか呼ばれない防御
    StringBuilder sb = new StringBuilder();
    synchronized( mmuLock ) {
      long curStart = -1, curEnd = -1;       // coalesce 中の連続 mapped range
      // 4-level walk: present かつ US=1 (user) の entry だけ降りる。i4..i1 昇順なので vaddr も昇順。
      for( long i4 = 0; i4 < 512; i4++ ) {
        long e4 = physGet64( PML4_PHYS + i4 * 8 );
        if( (e4 & (PTE_P | PTE_US)) != (PTE_P | PTE_US) ) continue;
        long pdpt = (e4 & PHYS_MASK) - gpaBase;   // entry は GPA → pool offset に戻す
        for( long i3 = 0; i3 < 512; i3++ ) {
          long e3 = physGet64( pdpt + i3 * 8 );
          if( (e3 & (PTE_P | PTE_US)) != (PTE_P | PTE_US) ) continue;
          long pd = (e3 & PHYS_MASK) - gpaBase;
          for( long i2 = 0; i2 < 512; i2++ ) {
            long e2 = physGet64( pd + i2 * 8 );
            if( (e2 & (PTE_P | PTE_US)) != (PTE_P | PTE_US) ) continue;
            long pt = (e2 & PHYS_MASK) - gpaBase;
            for( long i1 = 0; i1 < 512; i1++ ) {
              long e1 = physGet64( pt + i1 * 8 );
              if( (e1 & (PTE_P | PTE_US)) != (PTE_P | PTE_US) ) continue;
              long v = (i4 << 39) | (i3 << 30) | (i2 << 21) | (i1 << 12);
              if( v == curEnd ) { curEnd += PAGE; }     // 連続 → 伸ばす
              else {
                if( curStart >= 0 ) emitMapsLine( sb, curStart, curEnd );
                curStart = v; curEnd = v + PAGE;
              }
            }
          }
        }
      }
      if( curStart >= 0 ) emitMapsLine( sb, curStart, curEnd );
    }
    return sb.toString();
  }
  private void emitMapsLine( StringBuilder sb, long start, long end ) {
    // stack region = 初期 stack top 直下のバイト (stackBottomVaddr-1) を含む range。half-open [start,end)
    //   で end==stackBottomVaddr の stack range を正しく拾い、上隣接 region (start==stackBottomVaddr) を
    //   誤判定しない (review: 境界の off-by-one / coalesce 端を明確化)。
    boolean isStack = stackBottomVaddr != 0 && start <= stackBottomVaddr - 1 && stackBottomVaddr - 1 < end;
    // issue #547-native: brk heap 帯 [startBrk, curbrk) と重なる range は [heap] と報告する
    //   (jemalloc/GC/pthread が [heap] 行を探す)。native は present ページを coalesce するので data
    //   segment と heap が 1 行になりうるが、テスト (「[heap] が brk を含む」) の要件は満たせる。
    boolean isHeap = !isStack && startBrk != 0 && Long.compareUnsigned( curbrk, startBrk ) > 0
                  && Long.compareUnsigned( start, curbrk ) < 0 && Long.compareUnsigned( startBrk, end ) < 0;
    sb.append( Long.toHexString( start ) ).append( '-' ).append( Long.toHexString( end ) )
      .append( isStack ? " rw-p 00000000 00:00 0 " : " rwxp 00000000 00:00 0 " );
    if( isStack )     sb.append( "                         [stack]" );
    else if( isHeap ) sb.append( "                         [heap]" );
    sb.append( '\n' );
  }
}
