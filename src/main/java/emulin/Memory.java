// ----------------------------------------
//  Process Memory Management
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import java.util.*;
import emulin.*;

class AllocInfo {
  // mmap prot bits (Linux x86-64)
  static final int PROT_READ  = 0x1;
  static final int PROT_WRITE = 0x2;
  static final int PROT_EXEC  = 0x4;

  boolean use;
  long address;
  int size;
  int fd;
  int map_offset;
  int map_size;
  // Phase 32: mmap の prot flag を保持。fork 時に PROT_WRITE 無しなら buf を
  // share する。default は writable 扱い (= 既存挙動互換、安全側)。
  int prot = PROT_READ | PROT_WRITE;
  // Phase 32: 親子間で buf が share されているか。release_buffers では
  // shared な buf を null しない (= leak だが最大 1 セット分なので実用問題なし)。
  boolean shared;
  byte buf[];

  // huge sparse 領域 (multi-GB の anonymous mmap、JSC gigacage / WASM cage 等)。
  //   Java byte[] は 2GB 上限なので領域全体は確保できないが、JSC はその中の
  //   ごく一部しか touch しない。CHUNK 単位 (1MB) で「触れた所だけ」遅延確保する。
  //   chunks != null ならこの領域は huge sparse、fullSize が真のサイズ (byte)。
  static final int  HUGE_CHUNK_BITS = 20;                 // 1 MB
  static final int  HUGE_CHUNK_SIZE = 1 << HUGE_CHUNK_BITS;
  static final int  HUGE_CHUNK_MASK = HUGE_CHUNK_SIZE - 1;
  long     fullSize;     // huge 領域の真のサイズ。chunks != null のとき有効
  // != null なら huge sparse 領域。chunk[i] は遅延 alloc。複数 JSC スレッド
  //   (GC/JIT/main) が同じ gigacage を同時 access するため、chunk の publication
  //   は AtomicReferenceArray で safe にする (素の byte[][] だと書き手の
  //   chunks[ci]=c が読み手に見えず null→0 を返して JSValue/pointer が壊れ、
  //   非決定的な null 参照 segfault になる)。
  java.util.concurrent.atomic.AtomicReferenceArray<byte[]> chunks;

  // 範囲チェック用の有効サイズ。huge は fullSize (long)、通常は size (int)。
  long regionSize( ) { return ( chunks != null ) ? fullSize : (long)size; }

  // huge 領域の chunk を取得 (volatile read で publication を観測)。範囲外は null。
  byte[] hugeChunk( long off ) {
    int ci = (int)( off >>> HUGE_CHUNK_BITS );
    return ( ci < 0 || ci >= chunks.length() ) ? null : chunks.get( ci );
  }
  // huge 領域の 1 byte load。未 touch chunk は 0 (anonymous mmap は zero-fill)。
  byte hugeLoad8( long off ) {
    byte[] c = hugeChunk( off );
    return ( c == null ) ? 0 : c[ (int)( off & HUGE_CHUNK_MASK ) ];
  }
  // huge 領域の 1 byte store。chunk を遅延 alloc (CAS で安全に publish)。
  void hugeStore8( long off, byte v ) {
    int ci = (int)( off >>> HUGE_CHUNK_BITS );
    if( ci < 0 || ci >= chunks.length() ) return;
    byte[] c = chunks.get( ci );
    if( c == null ) {
      byte[] nc = new byte[ HUGE_CHUNK_SIZE ];
      c = chunks.compareAndSet( ci, null, nc ) ? nc : chunks.get( ci );
    }
    c[ (int)( off & HUGE_CHUNK_MASK ) ] = v;
  }

  AllocInfo( ) {
    init( );
  }
  public void init( ) {
    use     = false;
    address = 0L;
    size    = 0;
  }

  // 自分の複製を返す
  public AllocInfo duplicate( ) {
    AllocInfo _allocinfo = new AllocInfo( );
    _allocinfo.use        = use;
    _allocinfo.address    = address;
    _allocinfo.size       = size;
    _allocinfo.fd         = fd;
    _allocinfo.map_offset = map_offset;
    _allocinfo.map_size   = map_size;
    _allocinfo.prot       = prot;
    if( chunks != null ) {
      // huge sparse 領域: 触れた chunk だけ deep copy (fork は --version では
      //   起きないが整合性のため)。
      _allocinfo.fullSize = fullSize;
      _allocinfo.chunks   = new java.util.concurrent.atomic.AtomicReferenceArray<>( chunks.length() );
      for( int i = 0; i < chunks.length(); i++ ) {
        byte[] c = chunks.get( i );
        if( c != null ) _allocinfo.chunks.set( i, c.clone() );
      }
      return( _allocinfo );
    }
    if( buf != null ) {
      // Phase 32: text mmap (PROT_EXEC + !PROT_WRITE) のみ親子で share。
      // shared library の text 領域 (libc.so / ld.so 等) が対象。
      // 純 PROT_READ mmap (ld.so.cache / locale 等) は後で mprotect で
      // writable 化される可能性があり share しない (sys_mprotect は stub
      // で prot 変更を反映しないため)。
      if( (prot & PROT_EXEC) != 0 && (prot & PROT_WRITE) == 0 ) {
        _allocinfo.buf    = buf;
        _allocinfo.shared = true;
        this.shared       = true;
      } else {
        _allocinfo.buf = new byte[buf.length];
        System.arraycopy( buf, 0, _allocinfo.buf, 0, buf.length );
      }
    }
    return( _allocinfo );
  }
}

public class Memory extends Elf
{
  // Phase 27 step 61: static final にすると JIT が定数として inline でき、
  // division → shift、modulo → and-mask に最適化される (cache_size=2^N 前提)
  static final int cache_size = 32;
  static final int cache_size_mask = cache_size - 1;  // 0x1F (= 32-1)
  static int memory_page_size = 4096;
  // Phase 27 step 53: host (ASLR off) と同じ mmap layout に揃える。
  //   Linux x86-64 ASLR off の典型的 mmap base は 0x7ffff7fbf000。
  //   host の最初の mmap (8 KB ANONYMOUS) はこの値 - 0x2000 = 0x7ffff7fbd000 を返す。
  //   我々は先頭で 2 個の 4 KB を内部 alloc するので memory_top - 8 KB から
  //   開始し、その次の ld.so.cache (60 KB) などが host と一致する。
  static long memory_top = 0x7ffff7fbf000L;
  Syscall syscall;
  long mark_address;
  // Phase 27 step 31: alloclist を sorted map で O(log N) lookup。
  //   Phase 27 step 33: pthread で複数 thread 並列 access するため
  //   ConcurrentSkipListMap に変更。TreeMap は thread-safe でなく、
  //   step 33 の switch dispatch 高速化で race が露見した (pthread_mutex
  //   が 5 回中 2 回 fail)。ConcurrentSkipListMap も同 O(log N)。
  java.util.concurrent.ConcurrentSkipListMap<Long, AllocInfo> alloclist;
  // Phase 27 step 28: pthread (CLONE_VM) で複数 thread が同じ Memory を
  //   read/write するとき、per-byte cache (cache_address + cache[8]) を
  //   共有するとリフィル中に race して `index out of bounds` で crash する。
  //   ThreadLocal にして各 Java thread が独立した cache を持つ。
  // Phase 27 step 51: per-thread cache では cross-thread memory visibility が
  //   壊れる。thread A の store8 は自分の cache のみ invalidate するが、
  //   thread B の cache は古いまま → glibc malloc / mutex 等の atomic op が
  //   壊れる (chunk overlap など)。global version counter (volatile) で
  //   "他 thread が書き込んだ" ことを検知し、cache を再 refill する。
  // Phase 27 step 60: シングルスレッド時 (= ほとんどの実機 binary) は volatile
  //   long ++ がそのまま 5% CPU を食っていた。multiThreadActive (= 1 以上の
  //   pthread worker が生きてる) の時だけ epoch を増分するように変更。
  //   Thread64 が start/finish 時に inc/dec する。
  static volatile long globalStoreEpoch = 0;
  static volatile int multiThreadActive = 0;
  // issue #113: EMULIN_FORCE_SINGLE_THREAD=1 で multiThreadActive を常に 0 に保ち
  //   (Thread64 が増減を skip)、全メモリ操作を単一スレッド fast path に固定する
  //   診断スイッチ。crash が消えれば multi-thread メモリ経路が真因と確定。
  static final boolean FORCE_ST = System.getenv("EMULIN_FORCE_SINGLE_THREAD") != null;
  private static class CacheState {
    long cache_address = -1L;
    long cache_epoch = -1L;
    byte[] cache = new byte[cache_size];
    // step 61: store8 fast path で「直前と同じ segment」判定に使う
    // Phase 34-A8 (issue #4): 2-segment LRU 化 (lastSegmentA = MRU)。
    // SHA256 等の hot loop は「text (insn fetch) ↔ heap data」を頻繁に往復
    // するため、1 slot だと ping-pong で fast path miss 73% という JFR
    // 計測結果になっていた。2 slot で text / data の両方を抱えられるように。
    Segment lastSegment = null;        // = lastSegmentA (MRU)、既存 caller の互換用 alias
    Segment lastSegmentB = null;       // LRU 控え (2nd)
    // Phase 34-mem: alloclist (= mmap 領域) も lastSegment と同じ pattern で
    // fast path 化する。ConcurrentSkipListMap.floorEntry が JFR profile で
    // ~34% を占めていたため。実機 binary の hot path (heap / shared lib data)
    // は同 region への連続 access が大半なので、直前の AllocInfo を覚えて
    // address が range 内なら CSLM lookup を skip する。
    AllocInfo lastAllocInfo = null;
    long lastAllocInfoGen = -1L;  // alloclist 世代の snapshot
  }
  // Phase 34-mem: alloclist が変更されたら incremented。CacheState の
  // lastAllocInfo はこの世代を snapshot しておき、不一致なら invalidate。
  // MAP_FIXED で entry replace されると古い AllocInfo を cache が握り続けて
  // stale data を返す problem の根本対策。
  volatile long alloclistGen = 0;
  private final ThreadLocal<CacheState> tlCache =
      ThreadLocal.withInitial( CacheState::new );

  // 初期化
  Memory( Sysinfo _sysinfo, Syscall _syscall, Process _process ) {
    sysinfo = _sysinfo;
    syscall = _syscall;
    process = _process;
    mark_address = memory_top;
    alloclist = new java.util.concurrent.ConcurrentSkipListMap<>();
  }

  // Phase 31: process exit 時に明示的にメモリを解放する。
  // alloclist の AllocInfo.buf (mmap 領域の byte[]、合計数十 MB ある) と
  // segment[] の Segment.buf (ELF text/data) を null 化することで、
  // Process オブジェクトが何らかの参照で retained されても byte[] は GC
  // 対象になる。fork+exec 連鎖で OOM していた根本対策 (Phase 31)。
  public void release_buffers( ) {
    if( alloclist != null ) {
      for( AllocInfo ai : alloclist.values() ) {
        // Phase 32: shared な buf (parent or child がまだ参照中) は null
        // しない。最後の参照は GC が回収する。
        if( ai != null && !ai.shared ) ai.buf = null;
      }
      alloclist.clear();
    }
    if( segment != null ) {
      for( int i = 0; i < segment.length; i++ ) {
        if( segment[i] != null && !segment[i].shared ) segment[i].buf = null;
      }
    }
  }

  // 自分の複製を返す
  public Memory duplicate( Process _process ) {
    Memory _memory = new Memory( sysinfo, _process.syscall, _process );
    _memory.mark_address = mark_address;
    _memory.alloclist = new java.util.concurrent.ConcurrentSkipListMap<>();
    for( java.util.Map.Entry<Long, AllocInfo> e : alloclist.entrySet() ) {
      AllocInfo ai = e.getValue();
      if( ai != null ) _memory.alloclist.put( e.getKey(), ai.duplicate() );
    }
    _memory.update_info( (Elf)this );
    return( _memory );
  }

  // メモリを確保してファイルにマッピングする。
  // 既存シグネチャ (prot 不明) は writable 扱い (= fork 時に deep copy)。
  public long alloc_and_map( long adrs, int size, int _fd, int offset ) {
    return alloc_and_map( adrs, size, _fd, offset, AllocInfo.PROT_READ | AllocInfo.PROT_WRITE );
  }

  // Phase 32: prot を保持して fork 時の reference share 判定に使う。
  public long alloc_and_map( long adrs, int size, int _fd, int offset, int prot ) {
    long address = alloc( adrs, size );
    AllocInfo allocinfo = alloclist.get( address );
    allocinfo.fd         = _fd;
    allocinfo.map_offset = offset;
    allocinfo.map_size   = size;
    allocinfo.prot       = prot;
    if( sysinfo.verbose( )) {
      process.println( " alloc: fd = " + _fd + " address = " + Util.hexstr( address, 8 ) + " size = " + Util.hexstr( (long)size, 8 ) + " offset = " + Util.hexstr( (long)offset, 8 ) );
    }
    if( _fd > -1 ) {
      int ptr = allocinfo.map_offset;
      syscall.FileSeek( _fd, ptr, FileAccess.SEEK_SET );
      syscall.FileRead( _fd, allocinfo.buf );
      if( sysinfo.debug( )) {
	dump( address, size );
      }
    }
    return( address );
  }

  // メモリ確保する ( 確保したアドレスを返す )
  //   adrs == 0 : mark_address (bump allocator) から確保し、mark_address を進める
  //   adrs != 0 : 指定アドレスに確保 (MAP_FIXED 相当)。
  //   Phase 27 step 31: TreeMap で non-overlap invariant 維持。MAP_FIXED で
  //   既存 entry と range が overlap する場合、古い entries を削除して新しい
  //   ものに置き換える (Linux MAP_FIXED の "replace" semantics)。
  //   Phase 27 step 49: pthread 環境で複数 thread が同時に mmap (alloc) を
  //   呼ぶと、mark_address の read/write race で 2 thread が同じ address を
  //   取得する致命的バグ。`synchronized(alloclist)` で alloc 全体を直列化。
  //   alloclist は ConcurrentSkipListMap だが alloc 内部の "read-modify-write"
  //   全体を atomic にする必要がある (mark_address はただの long field)。
  public long alloc( long adrs, int size ) {
    synchronized( alloclist ) {
      AllocInfo allocinfo = new AllocInfo( );
      // Phase 27 step 54: page 数を round-up で計算 (Linux カーネルの mmap と
      //   挙動を揃える)。旧 `size / page_size` は truncate していて、サイズが
      //   page 境界でない場合に最後の partial page を確保しなかった
      //   (例: 59863 bytes → 14 page = 57344 bytes、本来は 15 page = 61440 bytes 必要)。
      //   その分だけ host と alignment がずれる原因の 1 つ
      int pages = (size + memory_page_size - 1) / memory_page_size;
      if( pages == 0 ) pages++;
      long aligned_size = (long)pages * (long)memory_page_size;
      long address;
      if( adrs != 0 ) {
        // MAP_FIXED 相当: 指定 address に確保 (mark_address は更新しない)。
        //   既存 mapping が新領域と重なる場合は Linux の MAP_FIXED semantics に
        //   合わせて重複 page だけを置換し、非重複の頭/尾 remainder は保存する。
        address = adrs;
        resolve_fixed_overlap( adrs, aligned_size );
      } else {
        // Phase 27 step 53: host Linux の mmap top-down allocation に合わせる。
        //   旧: address = mark_address; mark_address = address + size + guard;
        //        (= bottom-up bump、host と全く違うアドレス)
        //   新: mark_address -= size + guard; address = mark_address;
        //        (= top-down、host の mmap_base から下に伸びる)
        mark_address -= aligned_size;
        address = mark_address;
      }
      allocinfo.use     = true;
      allocinfo.address = address;
      allocinfo.size    = size;
      allocinfo.buf     = new byte[size];

      alloclist.put( address, allocinfo );
      alloclistGen++;  // Phase 34-mem: cache invalidate
      if( sysinfo.verbose( )) {
        process.println( " alloc( ) : address = " + Util.hexstr( address, 8 ) +  " next_address = " + Util.hexstr( mark_address, 8 ) + " pages = " + pages );
      }
      return( address );
    }
  }

  // MAP_FIXED で [new_start, new_end) に既存 mapping が重なるとき、Linux は
  //   重なった page だけを unmap して新 mapping に置き換える。emu の alloclist は
  //   1 AllocInfo = 連続領域なので、重なる既存 AllocInfo を「頭」「尾」の非重複
  //   remainder に分割して再登録し、重複部分だけを呼び出し側の put に明け渡す。
  //   これをしないと、新 fixed mapping が大きな既存領域の先頭 (= 同一 key) に
  //   乗ったとき put() が領域全体を置換して末尾 (thread stack 本体等) が消え、
  //   segfault する (Bun/musl の thread stack guard page 確保で発覚)。
  //   呼び出しは必ず synchronized(alloclist) の中から。
  private void resolve_fixed_overlap( long new_start, long new_size ) {
    long new_end = new_start + new_size;
    java.util.ArrayList<AllocInfo> overlaps = new java.util.ArrayList<>();
    // 非重複 invariant 上、new_start を跨げるのは floorEntry(new_start) だけ。
    java.util.Map.Entry<Long,AllocInfo> fe = alloclist.floorEntry( new_start );
    if( fe != null ) {
      AllocInfo ai = fe.getValue();
      if( ai.buf != null && ai.address + ai.size > new_start ) overlaps.add( ai );
    }
    // new_start より上 ~ new_end 未満で始まる entry は全て (一部 or 全部) 重なる。
    for( java.util.Map.Entry<Long,AllocInfo> en : alloclist.subMap( new_start, false, new_end, false ).entrySet() ) {
      if( en.getValue().buf != null ) overlaps.add( en.getValue() );
    }
    for( AllocInfo ai : overlaps ) {
      long b = ai.address, bend = b + ai.size;
      if( bend <= new_start || b >= new_end ) continue;
      alloclist.remove( b );
      if( b < new_start ) {                 // 頭 remainder [b, new_start)
        int hsize = (int)(new_start - b);
        AllocInfo h = new AllocInfo();
        h.use = true; h.address = b; h.size = hsize;
        h.prot = ai.prot; h.fd = ai.fd; h.map_offset = ai.map_offset; h.map_size = hsize;
        h.buf = new byte[hsize];
        System.arraycopy( ai.buf, 0, h.buf, 0, Math.min(hsize, ai.buf.length) );
        alloclist.put( b, h );
      }
      if( bend > new_end ) {                // 尾 remainder [new_end, bend)
        int tsize  = (int)(bend - new_end);
        int srcoff = (int)(new_end - b);
        AllocInfo tl = new AllocInfo();
        tl.use = true; tl.address = new_end; tl.size = tsize;
        tl.prot = ai.prot; tl.fd = ai.fd; tl.map_offset = ai.map_offset + srcoff; tl.map_size = tsize;
        tl.buf = new byte[tsize];
        if( srcoff < ai.buf.length )
          System.arraycopy( ai.buf, srcoff, tl.buf, 0, Math.min(tsize, ai.buf.length - srcoff) );
        alloclist.put( new_end, tl );
      }
    }
  }

  // /proc/self/maps の動的生成。emu の実メモリ配置 (ELF segment[] + mmap
  //   alloclist + stack segment) を maps 形式で出力する。
  //   重要: glibc の __pthread_getattr_np (main thread) は maps を読んで
  //   「from <= 初期 SP < to」の行を探して stack 境界を決める。stale な静的
  //   maps だと境界を誤算し、JSC が「SP が stack 範囲外」と RELEASE_ASSERT で
  //   abort する (Bun/claude 起動失敗の根本原因)。stack segment 行を必ず含める。
  public String genProcSelfMaps() {
    long stackLow = sysinfo.get_stack_bottom_64() - Sysinfo.stack_size;
    java.util.TreeMap<Long, long[]> regions = new java.util.TreeMap<>();  // start -> {end, isStack, prot}
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      long start = s.p_vaddr, end = s.p_vaddr + s.buf.length;
      if( end > start ) regions.put( start, new long[]{ end, (start==stackLow)?1:0, 7 } );
    }
    for( AllocInfo ai : alloclist.values() ) {
      if( ai.buf == null ) continue;
      long start = ai.address, end = ai.address + ai.size;
      if( end > start ) regions.putIfAbsent( start, new long[]{ end, 0, ai.prot } );
    }
    StringBuilder sb = new StringBuilder();
    for( java.util.Map.Entry<Long,long[]> e : regions.entrySet() ) {
      long start = e.getKey(), end = e.getValue()[0];
      boolean isStack = e.getValue()[1] == 1;
      int prot = (int)e.getValue()[2];
      char r = ((prot & AllocInfo.PROT_READ)  != 0) ? 'r' : '-';
      char w = ((prot & AllocInfo.PROT_WRITE) != 0) ? 'w' : '-';
      char x = ((prot & AllocInfo.PROT_EXEC)  != 0) ? 'x' : '-';
      sb.append( Long.toHexString(start) ).append('-').append( Long.toHexString(end) )
        .append(' ').append(r).append(w).append(x).append("p 00000000 00:00 0 ");
      if( isStack ) sb.append("                         [stack]");
      sb.append('\n');
    }
    return sb.toString();
  }

  // issue #103: [addr, addr+size) が addr 自身以外の live AllocInfo と
  //   range overlap するか判定する (mremap in-place grow の衝突検査に使う)。
  private boolean overlapsOther( long addr, long size ) {
    long end = addr + size;
    java.util.Map.Entry<Long,AllocInfo> fe = alloclist.floorEntry( addr );
    if( fe != null && fe.getKey().longValue() != addr ) {
      AllocInfo a = fe.getValue();
      if( a != null && a.use && Long.compareUnsigned( a.address + a.regionSize(), addr ) > 0 ) return true;
    }
    for( java.util.Map.Entry<Long,AllocInfo> en : alloclist.subMap( addr, false, end, false ).entrySet() ) {
      AllocInfo a = en.getValue();
      if( a != null && a.use ) return true;
    }
    return false;
  }

  public int realloc( long old_address, int size ) {
    synchronized( alloclist ) {
      AllocInfo allocinfo = alloclist.get( old_address );
      if( allocinfo == null ) return -1;
      byte[] old_buf = allocinfo.buf;
      int old_size = allocinfo.size;
      // issue #103: in-place grow が末尾を他の live mapping に食い込ませる場合は
      //   失敗させ、呼び出し側 (amd64_mremap) に relocate (MREMAP_MAYMOVE) させる。
      //   realloc は address を変えず size だけ伸ばすので、伸びた範囲に既存
      //   AllocInfo が残ると address→AllocInfo 解決 (floorEntry) が壊れ、glibc が
      //   mremap で grow した arena と既存の value stack 等が重なって corruption
      //   する (実 Linux の mremap は占有領域への in-place 拡大を拒否する)。
      if( size > old_size && overlapsOther( old_address, (long)size )) return -1;
      allocinfo.size = size;
      allocinfo.buf  = new byte[size];
      System.arraycopy( old_buf, 0, allocinfo.buf, 0, Math.min( old_size, size ) );
      return 0;
    }
  }

  public int free( long address, int size ) {
    AllocInfo allocinfo = alloclist.get( address );
    if( allocinfo == null || allocinfo.size != size ) return -1;
    alloclist.remove( address );
    alloclistGen++;  // Phase 34-mem: cache invalidate
    // Phase 34-mem: free 後も lastAllocInfo cache に AllocInfo の参照が
    // 残るので、buf を null にして cache check の `buf != null` で
    // filter させる (cache 無効化)。
    allocinfo.buf = null;
    if( sysinfo.verbose( )) {
      process.println( " free : address = " + Util.hexstr( address, 8 ) + " size = " + Util.hexstr( (long)size, 8 ));
    }
    return 0;
  }

  // アドレスが有効なメモリ内か調べる
  public boolean in( long address ) {
    for( int i = 0 ; i < segment.length ; i++ ) {
      if( segment[i].in( address )) return true;
    }
    java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( address );
    if( e != null ) {
      AllocInfo ai = e.getValue();
      if( address < ai.address + ai.regionSize() ) return true;
    }
    return false;
  }

  // huge sparse 領域の確保 (multi-GB の anonymous mmap)。byte[] では backing
  //   できないので AllocInfo.chunks (1MB chunk の遅延 alloc) で表現する。
  //   address space は full size 分予約 (mark_address を全長進めて後続 mmap が
  //   領域内に食い込まないよう host layout に合わせる)。
  public long alloc_huge( long addr, long fullAlignedSize, int prot ) {
    synchronized( alloclist ) {
      long address;
      if( addr != 0 ) {
        address = addr;
        resolve_fixed_overlap( addr, fullAlignedSize );
      } else {
        mark_address -= fullAlignedSize;
        address = mark_address;
      }
      AllocInfo ai = new AllocInfo();
      ai.use      = true;
      ai.address  = address;
      ai.size     = 0;                 // buf 未使用 (chunks で backing)
      ai.fullSize = fullAlignedSize;
      ai.prot     = prot;
      int nchunks = (int)( (fullAlignedSize + AllocInfo.HUGE_CHUNK_SIZE - 1) >>> AllocInfo.HUGE_CHUNK_BITS );
      if( nchunks < 1 ) nchunks = 1;
      ai.chunks   = new java.util.concurrent.atomic.AtomicReferenceArray<>( nchunks );
      alloclist.put( address, ai );
      alloclistGen++;
      return address;
    }
  }

  // Phase 34-B1 (issue #3-#1): N byte の bulk load/store を System.arraycopy 経由で。
  // amd64_read / amd64_write が emulator memory ⇔ Java byte[] を per-byte loop で
  // copy していた (4KB / 32KB 単位の HTTPS read で大きな overhead)。
  // segment / mmap 内に収まる連続 range なら arraycopy 1 発で完了。
  // 跨ぎ・miss 時は per-byte fallback (load8/store8)。
  public final void bulkLoadFromMem( long srcAddr, byte[] dst, int dstOff, int len ) {
    if( len <= 0 ) return;
    CacheState cs = tlCache.get();
    // 1) lastSegment fast path
    Segment last = cs.lastSegment;
    if( last != null && last.buf != null ) {
      long lo = last.p_vaddr;
      if( srcAddr >= lo && srcAddr + len <= lo + last.buf.length ) {
        System.arraycopy( last.buf, (int)(srcAddr - lo), dst, dstOff, len );
        return;
      }
    }
    // 2) lastAllocInfo fast path (mmap region, 例えば heap)
    AllocInfo lastAi = cs.lastAllocInfo;
    if( lastAi != null && lastAi.buf != null
        && cs.lastAllocInfoGen == alloclistGen
        && srcAddr >= lastAi.address && srcAddr + len <= lastAi.address + lastAi.size ) {
      System.arraycopy( lastAi.buf, (int)(srcAddr - lastAi.address), dst, dstOff, len );
      return;
    }
    // 3) segment 線形 scan (cold path)
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      long lo = s.p_vaddr;
      if( srcAddr >= lo && srcAddr + len <= lo + s.buf.length ) {
        System.arraycopy( s.buf, (int)(srcAddr - lo), dst, dstOff, len );
        cs.lastSegment = s;
        return;
      }
    }
    // 4) alloclist scan (mmap)
    java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( srcAddr );
    if( e != null ) {
      AllocInfo ai = e.getValue();
      if( ai != null && ai.buf != null
          && srcAddr >= ai.address && srcAddr + len <= ai.address + ai.size ) {
        System.arraycopy( ai.buf, (int)(srcAddr - ai.address), dst, dstOff, len );
        cs.lastAllocInfo = ai;
        cs.lastAllocInfoGen = alloclistGen;
        return;
      }
    }
    // 5) fallback: per-byte (segment 跨ぎ / 無効領域は load8 が segfault 出力)
    for( int i = 0; i < len; i++ ) dst[dstOff + i] = load8( srcAddr + i );
  }

  // Phase 34-B2 (issue #3-#1): emulator memory range の zero fill。
  // 内部で zero-filled byte[] を 1 度だけ alloc して再利用 (max 512 byte) し、
  // bulkStoreToMem で書き込む。signal 配信 (siginfo/ucontext) や FXSAVE 等で
  // 呼ばれる。
  private static final byte[] ZERO_FILL_BUF = new byte[512];
  public final void bulkZero( long dstAddr, int len ) {
    if( len <= 0 ) return;
    if( len <= ZERO_FILL_BUF.length ) {
      bulkStoreToMem( dstAddr, ZERO_FILL_BUF, 0, len );
    } else {
      // > 512 byte: 512 byte chunk を繰り返し書く
      int off = 0;
      while( off < len ) {
        int n = Math.min( ZERO_FILL_BUF.length, len - off );
        bulkStoreToMem( dstAddr + off, ZERO_FILL_BUF, 0, n );
        off += n;
      }
    }
  }

  // amd64_write 側の対称: Java byte[] → emulator memory への bulk store
  public final void bulkStoreToMem( long dstAddr, byte[] src, int srcOff, int len ) {
    if( len <= 0 ) return;
    CacheState cs = tlCache.get();
    cs.cache_address = -1L;  // store なので per-byte cache invalidate
    if( multiThreadActive != 0 ) globalStoreEpoch++;
    Segment last = cs.lastSegment;
    if( last != null && last.buf != null ) {
      long lo = last.p_vaddr;
      if( dstAddr >= lo && dstAddr + len <= lo + last.buf.length ) {
        System.arraycopy( src, srcOff, last.buf, (int)(dstAddr - lo), len );
        return;
      }
    }
    AllocInfo lastAi = cs.lastAllocInfo;
    if( lastAi != null && lastAi.buf != null
        && cs.lastAllocInfoGen == alloclistGen
        && dstAddr >= lastAi.address && dstAddr + len <= lastAi.address + lastAi.size ) {
      System.arraycopy( src, srcOff, lastAi.buf, (int)(dstAddr - lastAi.address), len );
      return;
    }
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      long lo = s.p_vaddr;
      if( dstAddr >= lo && dstAddr + len <= lo + s.buf.length ) {
        System.arraycopy( src, srcOff, s.buf, (int)(dstAddr - lo), len );
        cs.lastSegment = s;
        return;
      }
    }
    java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( dstAddr );
    if( e != null ) {
      AllocInfo ai = e.getValue();
      if( ai != null && ai.buf != null
          && dstAddr >= ai.address && dstAddr + len <= ai.address + ai.size ) {
        System.arraycopy( src, srcOff, ai.buf, (int)(dstAddr - ai.address), len );
        cs.lastAllocInfo = ai;
        cs.lastAllocInfoGen = alloclistGen;
        return;
      }
    }
    // fallback
    for( int i = 0; i < len; i++ ) store8( dstAddr + i, src[srcOff + i] );
  }

  // メモリからの1バイトリード — per-byte 32 byte cache (ThreadLocal)
  // Phase 27 step 61: load8 全体が 698 byte で JIT MaxInlineSize 超え非 inline。
  //   fast/slow path 分離 (slow を load8_slow に)。
  // step 64: load8 を 65 → ~30 byte に再圧縮 (JIT MaxInlineSize=35 より小さく)。
  //   address & ~31 = align. off = address - align で 0..31 が確定。
  //   cache_epoch チェックは load8_slow 側で実施 (multi-thread 限定なので
  //   conditional 分岐が hot path から消える)。
  /**
   * Phase 34-A8 (issue #4): 2-LRU segment 検索。lastSegment (MRU) → lastSegmentB
   * の順で「address..address+len-1 が buf 内に収まるか」を試行。
   * hit したら MRU 側を返し、必要なら B 側との swap も行う。
   * miss なら null (caller は load8 経由の slow path に fallback)。
   *
   * SHA256 等の hot loop で text / data segment を頻繁に往復するケースを
   * 1 slot lastSegment で扱うと毎回 fast path miss していた問題への対策。
   */
  private Segment lookupSegment2( CacheState cs, long address, int len ) {
    Segment a = cs.lastSegment;
    if( a != null && a.buf != null ) {
      long lo = a.p_vaddr;
      if( address >= lo && address + len <= lo + a.buf.length ) return a;
    }
    Segment b = cs.lastSegmentB;
    if( b != null && b.buf != null ) {
      long lo = b.p_vaddr;
      if( address >= lo && address + len <= lo + b.buf.length ) {
        // B hit: B を MRU に格上げ、A を控えに
        cs.lastSegmentB = a;
        cs.lastSegment  = b;
        return b;
      }
    }
    return null;
  }

  // Phase 34-A4-perf (issue #4): N byte の bulk load。Cpu64.refillInsnBuf
  // の 16 回 mem.load8 ループを 1 回の System.arraycopy に置換するための
  // 高速 path。lastSegment fast path で hit すれば arraycopy 1 発で完了。
  // hit しない / range が segment 跨ぎなら per-byte fallback。
  // 戻り値はコピーした byte 数 (常に len、ただし安全側で短絡時もありうる
  // が現在の使用箇所は 16 byte 固定で text/data segment 内に収まる前提)。
  public final void bulkLoad( long address, byte[] buf, int len ) {
    CacheState cs = tlCache.get();
    Segment last = cs.lastSegment;
    if( last != null && last.buf != null ) {
      long lo = last.p_vaddr;
      if( address >= lo && address + len <= lo + last.buf.length ) {
        System.arraycopy( last.buf, (int)(address - lo), buf, 0, len );
        return;
      }
    }
    // 2-LRU: A miss → B を試行、hit なら swap (B が今後の hot に格上げ)
    Segment lastB = cs.lastSegmentB;
    if( lastB != null && lastB.buf != null ) {
      long lo = lastB.p_vaddr;
      if( address >= lo && address + len <= lo + lastB.buf.length ) {
        cs.lastSegmentB = last;
        cs.lastSegment  = lastB;
        System.arraycopy( lastB.buf, (int)(address - lo), buf, 0, len );
        return;
      }
    }
    // fallback: per-byte (segment 線形 scan / mmap region / segfault dump
    // を含めた既存ロジックを再利用)。
    // load8_slow が lastSegment を新値で update するので、次回以降は
    // 2-LRU で hit する見込み。
    for( int i = 0; i < len; i++ ) buf[i] = load8( address + i );
  }

  // Phase 34-A3 step 9: emulin.jit から block compile 中の forward scan で
  // 命令 byte を read するため public 化。
  public final byte load8( long address ) {
    CacheState cs = tlCache.get();
    long off = address - cs.cache_address;
    if( off >= 0L && off < (long)cache_size
        && (multiThreadActive == 0 || cs.cache_epoch == globalStoreEpoch) ) {
      return cs.cache[(int)off];
    }
    return load8_slow( address, cs );
  }

  // load8 の slow path: cache miss / refill / segfault dump 等
  private byte load8_slow( long address, CacheState cs ) {
    int i;
    boolean _in = false;
    long align_address = address & ~(long)cache_size_mask;
    cs.cache_epoch = globalStoreEpoch;
    cs.cache_address = align_address;
    byte[] cache = cs.cache;
    // Phase 29-A2: lastSegment の fast path (store8 と同じ)。
    // cache miss でも segment は変わらない事が多い (e.g., text segment 内の
    // 別命令、heap 内の別 chunk)。線形 scan を省略できる。
    Segment last = cs.lastSegment;
    if( last != null && last.in( address ) ) {
      last.peekbs( align_address, cache );
      _in = true;
    } else {
      for( i = 0 ; i < segment.length ; i++ ) {
        if( segment[i].in( address )) {
          segment[i].peekbs( align_address, cache );
          // Phase 34-A8: 2-LRU の demote 処理。A を B に降格、新 segment を A に。
          // これで次回 ping-pong (旧 segment に戻る) のとき 2-LRU で hit する。
          if( cs.lastSegment != segment[i] ) {
            cs.lastSegmentB = cs.lastSegment;
            cs.lastSegment = segment[i];
          }
          _in = true;
          break;
        }
      }
    }
    if( !_in ) {
      // Phase 34-mem: lastAllocInfo fast path。直前と同じ mmap region なら
      // ConcurrentSkipListMap.floorEntry を skip して O(1) で hit。
      // 世代カウンタ check で MAP_FIXED 等で entry replace されたケースを
      // invalidate (古い AI が握られたまま stale data 返す問題の対策)。
      AllocInfo allocinfo = cs.lastAllocInfo;
      if( allocinfo != null && allocinfo.buf != null
          && cs.lastAllocInfoGen == alloclistGen
          && address >= allocinfo.address && address < allocinfo.address + allocinfo.size ) {
        long adrs       = allocinfo.address;
        int size        = allocinfo.size;
        int align_index = (int)(align_address - adrs);
        for( int j = 0 ; j < cache_size ; j++ ) {
          cache[j] = 0;
          if( align_index+j >= 0 && align_index+j < size ) { cache[j] = allocinfo.buf[ align_index+j ]; }
        }
        _in = true;
      }
    }
    if( !_in ) {
      java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( address );
      if( e != null ) {
        AllocInfo allocinfo = e.getValue();
        long adrs       = allocinfo.address;
        long rsize      = allocinfo.regionSize();
        long align_idx_l= align_address - adrs;
        if( address < adrs + rsize ) {
          if( allocinfo.chunks != null ) {
            // huge sparse: 32 byte cache window を chunk から refill。
            //   align_address は 32-aligned、chunk は 1MB-aligned なので window
            //   は単一 chunk 内に収まる → volatile read は 1 回で済む。
            byte[] c = allocinfo.hugeChunk( align_idx_l );
            int coff = (int)( align_idx_l & AllocInfo.HUGE_CHUNK_MASK );
            for( int j = 0 ; j < cache_size ; j++ ) {
              long o = align_idx_l + j;
              cache[j] = ( c != null && o >= 0 && o < rsize ) ? c[ coff + j ] : 0;
            }
          } else {
            int size        = allocinfo.size;
            int align_index = (int)align_idx_l;
            for( int j = 0 ; j < cache_size ; j++ ) {
              cache[j] = 0;
              if( align_index+j >= 0 && align_index+j < size ) { cache[j] = allocinfo.buf[ align_index+j ]; }
            }
            // Phase 34-mem: text mmap (PROT_EXEC, no PROT_WRITE) のみ cache。
            // data 領域は write されると stale data の risk があるため避ける。
            if( (allocinfo.prot & AllocInfo.PROT_EXEC) != 0
                && (allocinfo.prot & AllocInfo.PROT_WRITE) == 0 ) {
              cs.lastAllocInfo = allocinfo;
              cs.lastAllocInfoGen = alloclistGen;
            }
          }
          _in = true;
        }
      }
      if( ! _in ) {
        process.println( "  Segmentation Fault address(load8) = : " + Util.hexstr( address, 8 ) );
        process.println( "  Segmentation Fault address(load8) :   evals = " + process.evals( ));
        for(int dbg=0;dbg<segment.length;dbg++){if(segment[dbg].buf!=null)process.println("  seg["+dbg+"]: ["+Util.hexstr(segment[dbg].p_vaddr,8)+","+Util.hexstr(segment[dbg].p_vaddr+segment[dbg].buf.length,8)+")");}
        long rip_p = (process.cpu != null) ? process.cpu.get_ip() : 0;
        boolean dump_all = "all".equals(System.getenv("EMULIN_DUMP_MMAPS"));
        int alloc_n = 0;
        for( java.util.Map.Entry<Long, AllocInfo> ent : alloclist.entrySet() ) {
          AllocInfo ai = ent.getValue();
          if( ai == null ) continue;
          long start = ent.getKey(), end = start + ai.size;
          boolean show = dump_all || (ai.size >= 1024*1024) ||
                         (rip_p >= start - 0x10000 && rip_p < end + 0x10000) ||
                         (address >= start - 0x10000 && address < end + 0x10000);
          if( show ) {
            process.println("  mmap: ["+Util.hexstr(start,8)+","+Util.hexstr(end,8)+") size="+ai.size);
            alloc_n++;
          }
          if( alloc_n >= 200 ) { process.println("  ... ("+alloclist.size()+" total mmaps)"); break; }
        }
        if(process.cpu!=null) process.println("  RIP="+Long.toHexString(process.cpu.get_ip()));
        if( process.cpu instanceof Cpu64 ) {
          long[] r = ((Cpu64)process.cpu).r64;
          String[] nm = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
          for( int gi=0; gi<16; gi++ ) process.println("  "+nm[gi]+"="+Long.toHexString(r[gi]));
          process.println("  fs_base="+Long.toHexString(((Cpu64)process.cpu).fs_base));  // issue #113: canary fs:[0x28] fault 診断
          long ripv = ((Cpu64)process.cpu).cur_insn_rip != 0 ? ((Cpu64)process.cpu).cur_insn_rip : process.cpu.get_ip();
          process.println("  TRUE_RIP(cur_insn_rip)="+Long.toHexString(ripv));
          StringBuilder ib = new StringBuilder("  insn@TRUE_RIP=");
          for( int bi=0; bi<16; bi++ ) {
            byte bb = 0; boolean ok=false;
            java.util.Map.Entry<Long,AllocInfo> ie = alloclist.floorEntry( ripv+bi );
            if( ie != null ) { AllocInfo ai=ie.getValue(); int off=(int)((ripv+bi)-ai.address); if(off>=0&&off<ai.size){bb=ai.buf[off];ok=true;} }
            if(!ok){ for(int si=0;si<segment.length;si++){ Segment sg=segment[si]; if(sg.buf!=null&&ripv+bi>=sg.p_vaddr&&ripv+bi<sg.p_vaddr+sg.buf.length){bb=sg.buf[(int)((ripv+bi)-sg.p_vaddr)];ok=true;break;} } }
            ib.append( ok ? String.format("%02x ", bb&0xff) : "?? " );
          }
          process.println( ib.toString() );
          // issue #113: stack backtrace — rsp 近傍を scan し text 範囲の値 (戻りアドレス候補) を vaddr で出す
          long bsp = r[4];
          long pbase = 0x555555554000L;
          StringBuilder bt = new StringBuilder("  backtrace(stack-scan vaddr):");
          int btn = 0;
          for( int o2 = 0; o2 < 0x400 && btn < 24; o2 += 8 ) {
            long sa = bsp + o2;
            if( !in(sa) || !in(sa+7) ) break;
            long v = load64(sa);
            if( v >= pbase && v < pbase + 0x400000L ) { bt.append(" +0x").append(Integer.toHexString(o2)).append("=").append(Long.toHexString(v - pbase)); btn++; }
          }
          process.println( bt.toString() );
        }
        System.exit( 1 );
      }
    }
    return( cs.cache[(int)(address - cs.cache_address)] );
  }


  // メモリに1バイトのデータを書き込む
  // Phase 27 step 61: store8 fast path (~50 byte) を inline 可能に。
  //   slow path (segment loop / segfault dump) を別 method に分離。
  public final boolean store8( long address, int data ) {
    if( WATCH_STORE_ADDR != 0L || WATCH_STORE_VAL != 0L ) {
      store8_watchpoint( address, data );
    }
    CacheState cs = tlCache.get();
    cs.cache_address = -1L;
    if( multiThreadActive != 0 ) globalStoreEpoch++;
    // Fast path: 1 つ前の store/load と同じ segment ならその segment に書く
    Segment lastSeg = cs.lastSegment;
    if( lastSeg != null && lastSeg.in( address ) ) {
      lastSeg.pokeb( address, (byte)data );
      return true;
    }
    // Phase 34-mem: lastAllocInfo fast path (mmap heap/stack 領域用)
    AllocInfo lastAi = cs.lastAllocInfo;
    if( lastAi != null && lastAi.buf != null
        && cs.lastAllocInfoGen == alloclistGen
        && address >= lastAi.address && address < lastAi.address + lastAi.size ) {
      lastAi.buf[ (int)(address - lastAi.address) ] = (byte)data;
      return true;
    }
    return store8_slow( address, data, cs );
  }

  private void store8_watchpoint( long address, int data ) {
    if( WATCH_STORE_ADDR != 0L && address >= WATCH_STORE_ADDR && address < WATCH_STORE_ADDR + 8 ) {
      long rip = current_thread_rip();
      System.err.println("DBG_WA store8 addr=0x"+Long.toHexString(address)
        +" data=0x"+Long.toHexString(data & 0xFFL)
        +" rip=0x"+Long.toHexString(rip));
      System.err.flush();
    }
  }

  private boolean store8_slow( long address, int data, CacheState cs ) {
    int i;
    boolean ret = false;
    for( i = 0 ; i < segment.length ; i++ ) {
      if( segment[i].in( address )) {
        segment[i].pokeb( address, (byte)data );
        cs.lastSegment = segment[i];
        ret = true;
        break;
      }
    }
    if( !ret ) {
      java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( address );
      if( e != null ) {
        AllocInfo allocinfo = e.getValue();
        if( address < allocinfo.address + allocinfo.regionSize() ) {
          if( allocinfo.chunks != null ) {
            allocinfo.hugeStore8( address - allocinfo.address, (byte)data );
          } else if( allocinfo.buf != null ) {
            allocinfo.buf[ (int)(address - allocinfo.address) ] = (byte)data;
          }
          // issue #131 (Part A): allocinfo.buf == null は free 済 region。
          //   fd 等の parallel walker が exit_group で main が抜けた後も worker
          //   pthread が走り、teardown で free された alloc に store して
          //   "allocinfo.buf is null" NPE で thread が死んでいた。free 済への
          //   store は no-op 扱い (ret=true、segfault でも NPE crash でもなく)。
          // Phase 34-mem: text mmap への store は珍しい (self-modifying code)。
          // cache 更新は load 側だけに任せる方が安全。
          ret = true;
        }
      }
      if( !ret ) {
        process.println( "  Segmentation Fault address(store8) = : " + Util.hexstr( address, 8 ) );
        process.println( "  Segmentation Fault address(store8) :   evals = " + process.evals( ));
        for(int dbg=0;dbg<segment.length;dbg++){if(segment[dbg].buf!=null)process.println("  seg["+dbg+"]: ["+Util.hexstr(segment[dbg].p_vaddr,8)+","+Util.hexstr(segment[dbg].p_vaddr+segment[dbg].buf.length,8)+")");}
        if(process.cpu!=null) process.println("  RIP="+Long.toHexString(process.cpu.get_ip()));
        System.exit( 1 );
      }
    }
    if( sysinfo.debug( )) {
      process.println( "  Store8(" + Util.hexstr( address, 8 ) + "," + Util.hexstr( data & 0xFF, 2 ) + ") " );
    }
    return( ret );
  }

  //---------------------------------------- 以下応用関数 ----------------------------------------
  // メモリからのデータリード
  public boolean fetch( long address, byte buf[] ) {
    int i;
    for( i = 0 ; i < buf.length ; i++ ) {
      buf[i] = load8( address + i );
    }
    return( true );
  }

  // メモリからの2バイトリード
  // Phase 34-A6 (issue #4): lastSegment 直接 access の fast path で
  // load8 を 2 回呼ぶオーバーヘッドを排除。multi-thread 時のみ既存 per-byte
  // 経路に fallback (cache + epoch invalidation の整合性が必要なため)。
  public final short load16( long address ) {
    if( multiThreadActive == 0 ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 2 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        return (short)( (b[idx] & 0xFF) | ((b[idx+1] & 0xFF) << 8) );
      }
    }
    return (short)( ((int)load8( address ) & 0xFF) | (((int)load8( address+1 ) & 0xFF) << 8) );
  }

  // メモリからの4バイトリード
  public final int load32( long address ) {
    if( DT_LOAD ) detectTruncLoad( address );  // issue #113
    if( multiThreadActive == 0 ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 4 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        return  (b[idx]   & 0xFF)
             | ((b[idx+1] & 0xFF) <<  8)
             | ((b[idx+2] & 0xFF) << 16)
             | ((b[idx+3] & 0xFF) << 24);
      }
    }
    int ret =
        ((int)load8( address ) & 0xFF) |
        (((int)load8( address+1 ) & 0xFF) << 8 ) |
        (((int)load8( address+2 ) & 0xFF) << 16) |
        (((int)load8( address+3 ) & 0xFF) << 24);
    if( sysinfo.debug( )) {
      process.println( "  Load32(" + Util.hexstr( address, 8 ) + ") = [" + Util.hexstr( ret, 8 ) + "] " );
    }
    return( ret );
  }

  // メモリからの8バイトリード
  public final long load64( long address ) {
    if( multiThreadActive == 0 ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 8 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        return  ((long)(b[idx]   & 0xFF))
             | (((long)(b[idx+1] & 0xFF)) <<  8)
             | (((long)(b[idx+2] & 0xFF)) << 16)
             | (((long)(b[idx+3] & 0xFF)) << 24)
             | (((long)(b[idx+4] & 0xFF)) << 32)
             | (((long)(b[idx+5] & 0xFF)) << 40)
             | (((long)(b[idx+6] & 0xFF)) << 48)
             | (((long)(b[idx+7] & 0xFF)) << 56);
      }
    }
    long ret =
	   (long)
	   ( ((long)load8( address+0 ) & 0xFFL) |
	     (((long)load8( address+1 ) & 0xFFL) << 8 ) |
	     (((long)load8( address+2 ) & 0xFFL) << 16) |
	     (((long)load8( address+3 ) & 0xFFL) << 24) |
	     (((long)load8( address+4 ) & 0xFFL) << 32) |
	     (((long)load8( address+5 ) & 0xFFL) << (32 +  8)) |
	     (((long)load8( address+6 ) & 0xFFL) << (32 + 16)) |
	     (((long)load8( address+7 ) & 0xFFL) << (32 + 24))
	     );
    if( sysinfo.debug( )) {
      process.println( "  Load64(" + Util.hexstr( address, 8 ) + " )  = [ " + Util.hexstr( (int)(ret>>32), 8 ) + Util.hexstr( (int)ret, 8 ) + " ] " );
    }
    return( ret );
  }

  // メモリへの2バイトライト
  // Phase 34-A9 (issue #4): store8 を 2 回呼ぶ代わりに、single-thread 時は
  // 2-LRU lastSegment 検索で segment を直接書く fast path。
  public final void store16( long address, short value ) {
    if( multiThreadActive == 0
        && WATCH_STORE_ADDR == 0L
        && WATCH_STORE_VAL == 0L ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 2 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        b[idx]   = (byte)( value      );
        b[idx+1] = (byte)( value >> 8 );
        cs.cache_address = -1L;
        return;
      }
    }
    store8( address+0,  value        & 0xFF );
    store8( address+1, (value >> 8 ) & 0xFF );
  }

  // メモリへの4バイトライト
  public final void store32( long address, int value ) {
    if( DT_STORE ) detectTruncStore( address, ((long)value) & 0xFFFFFFFFL, 4 );  // issue #113
    if( multiThreadActive == 0
        && WATCH_STORE_ADDR == 0L
        && WATCH_STORE_VAL == 0L ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 4 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        b[idx]   = (byte)( value       );
        b[idx+1] = (byte)( value >>  8 );
        b[idx+2] = (byte)( value >> 16 );
        b[idx+3] = (byte)( value >> 24 );
        cs.cache_address = -1L;
        return;
      }
    }
    store8( address+0,  value        & 0xFF );
    store8( address+1, (value >>  8) & 0xFF );
    store8( address+2, (value >> 16) & 0xFF );
    store8( address+3, (value >> 24) & 0xFF );
  }

  // メモリへの8バイトライト
  public final void store64( long address, long value ) {
    if( DT_STORE ) detectTruncStore( address, value, 8 );  // issue #113
    if( WATCH_STORE_VAL != 0L && value == WATCH_STORE_VAL ) {
      long rip = current_thread_rip();
      System.err.println("DBG_WS addr=0x"+Long.toHexString(address)
        +" val=0x"+Long.toHexString(value)
        +" rip=0x"+Long.toHexString(rip));
      System.err.flush();
    }
    if( WATCH_STORE_ADDR != 0L && address >= WATCH_STORE_ADDR && address < WATCH_STORE_ADDR + 8 ) {
      long rip = current_thread_rip();
      System.err.println("DBG_WA store64 addr=0x"+Long.toHexString(address)
        +" val=0x"+Long.toHexString(value)
        +" rip=0x"+Long.toHexString(rip));
      System.err.flush();
    }
    if( multiThreadActive == 0
        && WATCH_STORE_ADDR == 0L
        && WATCH_STORE_VAL == 0L ) {
      CacheState cs = tlCache.get();
      Segment s = lookupSegment2( cs, address, 8 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        b[idx]   = (byte)( value       );
        b[idx+1] = (byte)( value >>  8 );
        b[idx+2] = (byte)( value >> 16 );
        b[idx+3] = (byte)( value >> 24 );
        b[idx+4] = (byte)( value >> 32 );
        b[idx+5] = (byte)( value >> 40 );
        b[idx+6] = (byte)( value >> 48 );
        b[idx+7] = (byte)( value >> 56 );
        cs.cache_address = -1L;
        return;
      }
    }
    store8( address+0, (int)(value >>  0   ) & 0xFF );
    store8( address+1, (int)(value >>  8   ) & 0xFF );
    store8( address+2, (int)(value >> 16   ) & 0xFF );
    store8( address+3, (int)(value >> 24   ) & 0xFF );
    store8( address+4, (int)(value >> 32+ 0) & 0xFF );
    store8( address+5, (int)(value >> 32+ 8) & 0xFF );
    store8( address+6, (int)(value >> 32+16) & 0xFF );
    store8( address+7, (int)(value >> 32+24) & 0xFF );
  }
  // EMULIN_WATCH_STORE_VAL=<HEX>: store64 が指定値と一致する 64-bit を書く瞬間を
  // 捕捉して rip / addr を dump する。bogus pointer (例: 0xab00000000) の
  // 出所を一発で特定するためのデバッグ用 hook
  // Return the RIP of the *current* Java thread's emulator Cpu, falling back
  // to process.cpu (= main thread) for the main thread or unknown threads.
  // Calls from worker threads must NOT use process.cpu since that's main's rip.
  private long current_thread_rip() {
    Thread cur = Thread.currentThread();
    if( cur instanceof Thread64 ) {
      Cpu64 c = ((Thread64)cur).cpu;
      if( c != null ) return c.get_ip();
    }
    if( process != null && process.cpu != null ) return process.cpu.get_ip();
    return -1L;
  }
  public static final long WATCH_STORE_VAL;
  public static final long WATCH_STORE_ADDR;
  static final boolean DT_STORE;            // issue #113: EMULIN_DETECT_TRUNC で store 側切り詰め検出
  static final boolean DT_LOAD;             // issue #113: EMULIN_DETECT_LOAD で load 側切り詰め検出
  static final long DT_EVAL_LO, DT_EVAL_HI; // EMULIN_WATCH_EVAL_LO/HI で範囲を絞る
  static int dtStoreDumps = 0, dtLoadDumps = 0;
  static {
    long v = 0L;
    String s = System.getenv("EMULIN_WATCH_STORE_VAL");
    if( s != null ) {
      try { v = Long.parseUnsignedLong(s, 16); } catch( NumberFormatException ignored ) {}
    }
    WATCH_STORE_VAL = v;
    long a = 0L;
    String sa = System.getenv("EMULIN_WATCH_STORE_ADDR");
    if( sa != null ) {
      try { a = Long.parseUnsignedLong(sa, 16); } catch( NumberFormatException ignored ) {}
    }
    WATCH_STORE_ADDR = a;
    DT_STORE = System.getenv("EMULIN_DETECT_TRUNC") != null;
    DT_LOAD  = System.getenv("EMULIN_DETECT_LOAD") != null;
    long dlo = 0L, dhi = Long.MAX_VALUE;
    try { String x = System.getenv("EMULIN_WATCH_EVAL_LO"); if( x != null ) dlo = Long.parseLong(x); } catch( NumberFormatException ignored ){}
    try { String x = System.getenv("EMULIN_WATCH_EVAL_HI"); if( x != null ) dhi = Long.parseLong(x); } catch( NumberFormatException ignored ){}
    DT_EVAL_LO = dlo; DT_EVAL_HI = dhi;
  }

  // issue #113: store が「pointer slot」(PIE/stack 範囲の 64bit 値) を 32bit 切り詰め
  //   値で上書きする瞬間を検出する。真の RIP は cur_insn_rip (EMULIN_TRACK_INSN_RIP=1)。
  private long current_insn_rip() {
    Thread cur = Thread.currentThread();
    if( cur instanceof Thread64 ) { Cpu64 c = ((Thread64)cur).cpu; if( c != null ) return c.cur_insn_rip; }
    if( process != null && process.cpu instanceof Cpu64 ) return ((Cpu64)process.cpu).cur_insn_rip;
    return -1L;
  }
  private void detectTruncStore( long address, long value, int size ) {
    if( dtStoreDumps >= 60 ) return;
    long ev = (process != null) ? process.evals() : 0L;
    if( ev < DT_EVAL_LO || ev > DT_EVAL_HI ) return;
    long old = load64( address );
    boolean oldPtr = (old >= 0x555555554000L && old < 0x556000000000L)
                  || (old >= 0x7f0000000000L && old < 0x800000000000L);
    if( !oldPtr ) return;
    // store64: 低位32が old と一致し上位32が変わる = pointer の in-place 切り詰め。
    // store32: pointer slot に 32bit store = 64bit store の downsize 疑い。
    // 真の in-place 切り詰め: スロットが保持していたポインタ old の低位32が、
    //   zero/sign 拡張で書き戻される (= old の上位ポインタビットが落ちる)。
    //   タグ操作 (上位が tag bit) もスロット再利用 (低位32が別値) も除外される。
    boolean trunc = ( size == 8 )
        ? ( ((value >>> 32) == 0L || (value >>> 32) == 0xFFFFFFFFL)
            && (value & 0xFFFFFFFFL) == (old & 0xFFFFFFFFL) )
        : true;
    if( trunc ) {
      System.err.println("DBG_TRUNC_ST"+(size*8)+" rip=0x"+Long.toHexString(current_insn_rip())
        +" addr=0x"+Long.toHexString(address)+" old=0x"+Long.toHexString(old)
        +" new=0x"+Long.toHexString(value)+" eval="+ev);
      System.err.flush();
      dtStoreDumps++;
    }
  }
  // issue #113: REX.W (本来 64bit operand) の命令が load32 でオペランドを読む
  //   = 64bit ロードのつもりが 32bit (高位消失) を検出。movsxd(0x63) は正規で除外。
  //   ロード元の 8byte がポインタ (高位ビットあり) のときだけ報告。
  private void detectTruncLoad( long address ) {
    if( dtLoadDumps >= 60 ) return;
    long ev = (process != null) ? process.evals() : 0L;
    if( ev < DT_EVAL_LO || ev > DT_EVAL_HI ) return;
    long full = load64( address );
    boolean fullPtr = (full >= 0x555555554000L && full < 0x556000000000L)
                   || (full >= 0x7f0000000000L && full < 0x800000000000L);
    if( !fullPtr ) return;
    long rip = current_insn_rip();
    boolean rexw = false; int op = 0;
    for( int k = 0; k < 8; k++ ) {
      int by = (int)load8( rip + k ) & 0xFF;
      if( by==0x66||by==0x67||by==0xf0||by==0xf2||by==0xf3||by==0x2e||by==0x36||by==0x3e||by==0x26||by==0x64||by==0x65 ) continue;
      if( (by & 0xF0) == 0x40 ) { rexw = (by & 0x08) != 0; continue; }
      op = by; break;
    }
    if( !rexw || op == 0x63 ) return;  // 非 REX.W or movsxd は正規
    System.err.println("DBG_TRUNC_LD32 rip=0x"+Long.toHexString(rip)+" op=0x"+Integer.toHexString(op)
      +" addr=0x"+Long.toHexString(address)+" mem64=0x"+Long.toHexString(full)+" eval="+ev);
    System.err.flush();
    dtLoadDumps++;
  }

  // 文字列を格納する
  public long storeString( long address, String str ) {
    // Phase 27 step 42: 旧実装は (byte)char で Latin-1 キャスト。
    //   非 ASCII (例: Hungarian の ő = U+0151) で `(byte)0x151` = 0x51 = 'Q'
    //   と化けて、getdents64 経由でファイル名が壊れていた。UTF-8 で encode する。
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
    byte[] bytes = str.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    bulkStoreToMem( address, bytes, 0, bytes.length );
    address += bytes.length;
    store8( address, 0 );
    address++;
    return( address );
  }

  // 文字列を読み出す
  public String loadString( long address ) {
    // Phase 27 step 42: 旧実装は (char)load8 で byte → char 直キャスト (Latin-1)
    //   していた。UTF-8 multi-byte のファイル名 (例: NetLock の Hungarian の
    //   ő/ú/í/á を含む cert) で Java File API が code unit ずれを起こし、
    //   存在するファイルを open できず gnutls の CA load が失敗していた。
    //   バイト列を集めて UTF-8 として decode する。
    int len;
    for( len = 0; len < 10000; len++ ) {
      if( 0 == load8( address + len ) ) break;
    }
    byte[] bytes = new byte[len];
    for( int i = 0; i < len; i++ ) {
      bytes[i] = (byte) load8( address + i );
    }
    return new String( bytes, java.nio.charset.StandardCharsets.UTF_8 );
  }

  // DUMPをとる
  public void dump( long address, int len ) {
    int i, j, index = 0;
    String str;

    if( false ) {
      return;
    }
    else {
      if( len < 16 ) { len = 16; }
      for( i = 0 ; i < len/16 ; i++ ) {
	str = " " + Util.hexstr( address + index, 8 ) + ": ";
	for( j = 0 ; j < 16 ; j++ ) {
	  if( in( address + index )) {
	    str += " " + Util.hexstr( 0xFF & (int)load8( address + index ), 2 );
	    index ++;
	  }
	}
	process.println( str );
      }
    }
  }
}

