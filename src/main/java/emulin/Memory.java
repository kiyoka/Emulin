// ----------------------------------------
//  Process Memory Management
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
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
  long map_offset;   // issue #336: file offset は off_t 64-bit (>2GB file-backed mmap)
  int map_size;
  // Phase 32: mmap の prot flag を保持。fork 時に PROT_WRITE 無しなら buf を
  // share する。default は writable 扱い (= 既存挙動互換、安全側)。
  int prot = PROT_READ | PROT_WRITE;
  // Phase 32: 親子間で buf が share されているか。release_buffers では
  // shared な buf を null しない (= leak だが最大 1 セット分なので実用問題なし)。
  boolean shared;
  // issue #517: mmap MAP_SHARED か (msync の file 書き戻し対象判定)。
  //   上の shared (fork 時 buf 共有) とは別物。
  boolean map_shared;
  String map_path;   // issue #113: file-backed mmap の元 file path (segfault dump で library 名特定用)
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
    _allocinfo.map_path   = map_path;   // issue #113: library 名を子にも引き継ぐ
    _allocinfo.map_shared = map_shared; // issue #560: MAP_SHARED フラグを子に伝播
    if( chunks != null ) {
      _allocinfo.fullSize = fullSize;
      if( map_shared ) {
        // issue #560: MAP_SHARED huge 領域 (JSC gigacage の共有等) は chunks を
        //   参照共有し、fork 後も親子で同一物理ページを見せる。
        _allocinfo.chunks = chunks;
        _allocinfo.shared = true;
        this.shared       = true;
        return( _allocinfo );
      }
      // huge sparse 領域: 触れた chunk だけ deep copy (fork は --version では
      //   起きないが整合性のため)。
      _allocinfo.chunks   = new java.util.concurrent.atomic.AtomicReferenceArray<>( chunks.length() );
      for( int i = 0; i < chunks.length(); i++ ) {
        byte[] c = chunks.get( i );
        if( c != null ) _allocinfo.chunks.set( i, c.clone() );
      }
      return( _allocinfo );
    }
    if( buf != null ) {
      if( map_shared ) {
        // issue #560: MAP_SHARED anon/file は fork 後も親子で同一物理ページを共有する。
        //   buf を deep copy せず参照共有し、子の store が親に (逆も) 即座に見えるように
        //   する (Python multiprocessing / POSIX 共有メモリ / プロセス間カウンタ)。
        _allocinfo.buf    = buf;
        _allocinfo.shared = true;
        this.shared       = true;
      }
      // Phase 32: text mmap (PROT_EXEC + !PROT_WRITE) のみ親子で share。
      // shared library の text 領域 (libc.so / ld.so 等) が対象。
      // 純 PROT_READ mmap (ld.so.cache / locale 等) は後で mprotect で
      // writable 化される可能性があり share しない (sys_mprotect は stub
      // で prot 変更を反映しないため)。
      else if( (prot & PROT_EXEC) != 0 && (prot & PROT_WRITE) == 0 ) {
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

// issue #232 (Step 2/3 of #221 refactor): MemoryBackend interface を実装する
//   ことで、将来 NativeMemoryBackend (WHP/KVM、#221 Phase 0+) が同 interface で
//   plug-in できるようにする。Memory class 自身の body は変更しないため、
//   per-byte hot path (load8 / store8、claude 起動時間の ~30%) の性能 regression
//   リスクはゼロ。JIT は Memory が唯一の impl のうちは monomorphic 解決して
//   inline するので interface 経由でも従来と同一機械語が出る (実測で確認済)。
public class Memory extends Elf implements MemoryBackend
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
  // issue #113: global 実行ロック (big interpreter lock)。worker pthread は spawn するが
  //   guest 命令の実行を 1 thread ずつに直列化し、全メモリ race クラス (torn/ordering/
  //   SIMD16byte/fetch coherence 等) を一括解消する。FORCE_ST が単一スレッド実行の
  //   正しさを実証済なのでこの方向が確実。Cpu64.eval が命令ループ開始で lock、
  //   exec_syscall の blocking 中と 1024 命令毎の yield で release する (deadlock 回避)。
  //   lock は Memory instance 単位 = アドレス空間単位 (CLONE_VM worker は共有、fork 子は
  //   duplicate で別 Memory=別 lock なので真の並列のまま)。EMULIN_NO_GLOBAL_LOCK=1 で無効化 (A/B)。
  static final boolean GLOBAL_LOCK = System.getenv("EMULIN_NO_GLOBAL_LOCK") == null;
  final java.util.concurrent.locks.ReentrantLock execLock = new java.util.concurrent.locks.ReentrantLock();
  // issue #113 ROOT CAUSE fix: 実 x86-64 では aligned な 2/4/8-byte store/load は atomic だが、
  //   emulin の store16/32/64 / load16/32/64 は byte[] への per-byte 分解で実装されているため、
  //   worker 並走時 (multiThreadActive!=0) に別スレッドが half-written な値 (torn) を観測しうる
  //   (#113: Lisp_Object の高 dword=新タグ0x40000000 / 低 dword=stale)。aligned access を
  //   VarHandle の plain get/set (byteArrayView) で 1 命令アクセスし torn write/read を断つ。
  //   host (x86-64/arm64) では aligned な put/get long/int/short は単一 mov = bitwise atomic
  //   なので tearing しない。volatile mode は byteArrayView だと対象 JRE で
  //   UnsupportedOperationException になるため plain を使用 (cross-thread ordering は後続の
  //   volatile globalStoreEpoch++ + host TSO が担保)。multiThreadActive==0
  //   (= 大多数の実機 binary) は従来 fast path のまま無影響。EMULIN_NO_ATOMIC_WIDE=1 で無効化 (A/B 用)。
  static final boolean ATOMIC_WIDE = System.getenv("EMULIN_NO_ATOMIC_WIDE") == null;
  // issue #113 (H3): LOCK prefix 付き命令の RMW を synchronized(mem) で atomic 化する
  //   (Cpu64.decode_and_exec)。EMULIN_NO_LOCK_ATOMIC=1 で無効化して A/B 検証する用。
  static final boolean NO_LOCK_ATOMIC = System.getenv("EMULIN_NO_LOCK_ATOMIC") != null;
  private static final VarHandle VH_LONG  = MethodHandles.byteArrayViewVarHandle( long[].class,  ByteOrder.LITTLE_ENDIAN );
  private static final VarHandle VH_INT   = MethodHandles.byteArrayViewVarHandle( int[].class,   ByteOrder.LITTLE_ENDIAN );
  private static final VarHandle VH_SHORT = MethodHandles.byteArrayViewVarHandle( short[].class, ByteOrder.LITTLE_ENDIAN );
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
    int atomIdx = 0;              // issue #113: flatBacking が解決した byte[] index (atomic wide access 用)
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

  // issue #113: segfault (out-of-bounds load8/store8) を JVM 全体の System.exit
  //   ではなく「その process だけを SIGSEGV で終了」させるための controlled
  //   exception。Memory は process に 1:1 bind されるので this.process が faulting
  //   process 本人 (fork 子なら子)。Process.run / Thread64.run が catch して
  //   set_exit_flag (親へ SIGCHLD) → JVM と親は継続 (real Linux 挙動)。
  static final class SegfaultException extends RuntimeException {
    final long faultAddr;   // issue #548: fault したアドレス (SIGSEGV siginfo の si_addr 用)。0=不明
    SegfaultException( )          { this( 0 ); }
    SegfaultException( long addr ) { super( "SIGSEGV" ); faultAddr = addr; }
  }
  // issue #441: syscall がユーザ空間ポインタ引数の不正アクセスで fault したときは、
  //   guest に SIGSEGV を配信する代わりに syscall を -EFAULT で返させる (POSIX:
  //   bad user pointer to a syscall は EFAULT であって SIGSEGV ではない)。
  //   SyscallAmd64 が dispatch の間だけこの flag を立て、その間の fault は crash
  //   dump / term_sig を出さずに SegfaultException だけ投げる → dispatch が catch
  //   して EFAULT に変換する。命令実行中の fault (flag=false) は従来どおり SIGSEGV。
  public static final ThreadLocal<Boolean> FAULT_AS_EFAULT =
      ThreadLocal.withInitial( () -> Boolean.FALSE );
  // crash dump 出力後に呼ぶ: term_sig を立てて SegfaultException を throw。
  private void raiseSegv( long addr ) {
    if( process != null ) process.term_sig = Signal.SIGSEGV;   // = 11
    throw new SegfaultException( addr );                        // issue #548: si_addr 用に fault 番地を伝える
  }

  // issue #113: file-backed mmap の元 file path を記録する (segfault dump で
  //   faulting RIP がどの library かを特定するため)。amd64_mmap が fd>=0 のとき呼ぶ。
  public void set_map_path( long addr, String path ) {
    AllocInfo ai = alloclist.get( addr );
    if( ai != null ) ai.map_path = path;
  }

  // issue #403: file-backed VA 範囲の追跡 (madvise が file 内容を zero 化しないため)。
  private final FileBackedRanges fileBacked = new FileBackedRanges();
  @Override public void    registerFileBacked  ( long addr, long len ) { fileBacked.add( addr, len ); }
  @Override public boolean isFileBacked        ( long addr )           { return fileBacked.contains( addr ); }
  @Override public void    unregisterFileBacked( long addr, long len ) { fileBacked.remove( addr, len ); }

  // issue #517: msync/mlock の ENOMEM 判定。[addr, addr+len) の全域が
  //   ELF segment / brk heap (segment[]) か alloclist の mapping に覆われていれば
  //   true。gpg は brk heap を mlock するので segment[] を含めるのが必須。
  @Override public boolean isRangeMapped( long addr, long len ) {
    long cur = addr & ~0xFFFL;
    long end = addr + len;
    if( end < cur ) return false;   // overflow
    while( cur < end ) {
      long next = -1;
      for( int i = 0; i < segment.length; i++ ) {
        Segment s = segment[i];
        if( s == null || s.buf == null ) continue;
        if( cur >= s.p_vaddr && cur < s.p_vaddr + s.buf.length ) {
          next = s.p_vaddr + s.buf.length;
          break;
        }
      }
      if( next < 0 ) {
        java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( cur );
        if( e != null && e.getValue( ) != null ) {
          AllocInfo ai = e.getValue( );
          long asize = ( ai.chunks != null ) ? ai.fullSize : (long)ai.size;
          if( cur < ai.address + asize ) next = ai.address + asize;
        }
      }
      if( next < 0 ) return false;
      cur = next;
    }
    return true;
  }

  // issue #517: msync — 範囲と重なる file-backed MAP_SHARED mapping の
  //   buf (guest write が直接載っている) を backing file へ書き戻す。Linux は内部で
  //   file を保持するが emulin は mmap 時の fd 経由の近似 (close/reuse 済なら
  //   FileSeek が失敗して skip)。file 終端を超える page 埋め分は書かない
  //   (apt pkgcache が page 単位に膨らむのを防ぐ)。
  @Override public void msyncFlush( long addr, long len ) {
    long end = addr + len;
    for( java.util.Map.Entry<Long, AllocInfo> e : alloclist.headMap( end, false ).entrySet( ) ) {
      AllocInfo ai = e.getValue( );
      if( ai == null || ai.fd < 0 || !ai.map_shared || ai.buf == null ) continue;
      long astart = ai.address, aend = ai.address + ai.map_size;
      long lo = Math.max( addr, astart ), hi = Math.min( end, aend );
      if( lo >= hi ) continue;
      try {
        long saved = syscall.FileSeek( ai.fd, 0, FileAccess.SEEK_CUR );
        if( saved < 0 ) continue;                                    // fd close 済等
        long flen = syscall.FileSeek( ai.fd, 0, FileAccess.SEEK_END );
        long fend = astart + ( flen - ai.map_offset );               // file 終端の VA
        long hi2 = Math.min( hi, fend );
        if( hi2 > lo ) {
          syscall.FileSeek( ai.fd, ai.map_offset + ( lo - astart ), FileAccess.SEEK_SET );
          byte[] out = java.util.Arrays.copyOfRange( ai.buf, (int)( lo - astart ), (int)( hi2 - astart ) );
          syscall.FileWrite( ai.fd, out );
        }
        syscall.FileSeek( ai.fd, saved, FileAccess.SEEK_SET );
      } catch( Exception ignored ) { }
    }
  }

  // issue #113: addr がどの region (ELF segment / mmap / unmapped) にあるかのラベル。
  //   segfault dump で fault address / RIP の所在を即座に分かるようにする。
  String regionLabel( long addr ) {
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s != null && s.buf != null && addr >= s.p_vaddr && addr < s.p_vaddr + s.buf.length )
        return "seg[" + i + "]+0x" + Long.toHexString( addr - s.p_vaddr );
    }
    java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( addr );
    if( e != null ) {
      AllocInfo ai = e.getValue();
      if( ai != null && (ai.buf != null || ai.chunks != null) && addr >= ai.address && addr < ai.address + ai.regionSize() )
        return "mmap[0x" + Long.toHexString( ai.address ) + "]+0x" + Long.toHexString( addr - ai.address )
             + ( ai.map_path != null ? " " + ai.map_path : "" );
    }
    return "UNMAPPED";
  }

  // issue #113: rip から 16 byte の命令バイト列を読む (segment / mmap 両対応)。
  String insnBytesAt( long rip ) {
    StringBuilder ib = new StringBuilder();
    for( int bi = 0; bi < 16; bi++ ) {
      byte bb = 0; boolean ok = false;
      java.util.Map.Entry<Long, AllocInfo> ie = alloclist.floorEntry( rip + bi );
      if( ie != null ) { AllocInfo ai = ie.getValue(); long off = (rip + bi) - ai.address; if( off >= 0 && off < ai.regionSize() ) { if( ai.buf != null && off < ai.size ) { bb = ai.buf[(int)off]; ok = true; } else if( ai.chunks != null ) { byte[] c = ai.hugeChunk( off ); if( c != null ) { bb = c[(int)(off & AllocInfo.HUGE_CHUNK_MASK)]; ok = true; } } } }
      if( !ok ) { for( int si = 0; si < segment.length; si++ ) { Segment sg = segment[si]; if( sg.buf != null && rip + bi >= sg.p_vaddr && rip + bi < sg.p_vaddr + sg.buf.length ) { bb = sg.buf[(int)((rip + bi) - sg.p_vaddr)]; ok = true; break; } } }
      ib.append( ok ? String.format( "%02x ", bb & 0xff ) : "?? " );
    }
    return ib.toString();
  }

  // Phase 31: process exit 時に明示的にメモリを解放する。
  // alloclist の AllocInfo.buf (mmap 領域の byte[]、合計数十 MB ある) と
  // segment[] の Segment.buf (ELF text/data) を null 化することで、
  // Process オブジェクトが何らかの参照で retained されても byte[] は GC
  // 対象になる。fork+exec 連鎖で OOM していた根本対策 (Phase 31)。
  public void release_buffers( ) {
    // issue #113: worker 並走中に共有 alloclist を clear すると、まだ生きている
    //   sibling worker の load/store が空マップに当たって二次 segfault する
    //   (実 Linux は fatal signal で thread group 全体を atomic に終了させるが、
    //   emulin の Java thread は個別なので main process の teardown が sibling を
    //   巻き込む)。worker が 1 つでも live (multiThreadActive!=0) の間は teardown を
    //   skip し、buf は全 thread 終了後に GC へ委ねる。単一スレッド process
    //   (大多数・fork+exec OOM 対策の対象) は multiThreadActive==0 なので従来どおり即解放。
    if( multiThreadActive != 0 ) return;
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
  public long alloc_and_map( long adrs, int size, int _fd, long offset ) {
    return alloc_and_map( adrs, size, _fd, offset, AllocInfo.PROT_READ | AllocInfo.PROT_WRITE );
  }

  // issue (ddskk): シグナルハンドラの戻り先に使う rt_sigreturn トランポリン。
  //   旧実装は未マップの sentinel (Cpu64.SIGRETURN_TRAMPOLINE = 0xFFFFFFFFFFFEDEAD)
  //   を戻りアドレスに push し、ハンドラの ret 着地を eval ループで検出して
  //   sigreturn していた。だが libgcc のアンワインダ (emacs の SIGABRT backtrace や
  //   C++ 例外が使う x86_64_fallback_frame_state) は signal frame を識別するため
  //   戻りアドレスのバイト列 (`48 c7 c0 0f 00 00 00 0f 05` = mov $0xf,%rax; syscall)
  //   を読む。未マップ sentinel を読むと load8 SEGV する (ddskk 入力時のクラッシュ)。
  //   実機 Linux と同様に、実ページにトランポリンバイトを置き、その実番地を戻り
  //   アドレスに使う。eval ループの sigreturn 検出もこの実番地で行う (emulin が
  //   ret 着地を intercept するのでバイト自体は実行されないが、アンワインダの
  //   パターン照合は通り SEGV しなくなる)。1 プロセス 1 ページ、lazy 確保。
  public long sigtrampAddr = 0;
  public long ensureSigtramp() {
    if( sigtrampAddr != 0 ) return sigtrampAddr;
    synchronized( alloclist ) {
      if( sigtrampAddr == 0 ) {
        long p = alloc_and_map( 0, 4096, -1, 0, AllocInfo.PROT_READ | AllocInfo.PROT_EXEC );
        if( p > 0 ) {
          int[] tr = { 0x48, 0xc7, 0xc0, 0x0f, 0x00, 0x00, 0x00, 0x0f, 0x05, 0xc3 };
          for( int i = 0; i < tr.length; i++ ) store8( p + i, tr[i] );
          sigtrampAddr = p;
        }
      }
    }
    return sigtrampAddr;
  }

  // issue #221 step 3d-2c-34: MAP_FIXED 無しの addr は hint — Linux は範囲が塞がっていると
  //   kernel が別の場所を選ぶ。旧実装は hint を無条件 MAP_FIXED 扱いしており、hint が brk heap
  //   segment と重なると alloclist と segment[] が同一仮想を alias して silent corruption に
  //   なる (native backend で #281 として修正したのと同型の software 版。resolve_fixed_overlap
  //   は alloclist 同士しか見ず、ELF segment との重なりは検出できない)。amd64_mmap が flags を
  //   渡してくる本 overload で hint 判定し、塞がっていれば kernel-chooses (adrs=0) に relocate。
  @Override
  public long alloc_and_map( long adrs, int size, int _fd, long offset, int prot, long flags ) {
    if( adrs != 0 && ( flags & 0x10 ) == 0 ) {   // 0x10 = MAP_FIXED (無し = adrs は hint)
      long len = ( (long)size + 0xFFFL ) & ~0xFFFL;
      if( !rangeIsFreeForHint( adrs & ~0xFFFL, len ) ) adrs = 0;
    }
    long address = alloc_and_map( adrs, size, _fd, offset, prot );
    if( address > 0 ) {
      // issue #517: MAP_SHARED を記録 (msync の書き戻し対象)。
      AllocInfo ai = alloclist.get( address );
      if( ai != null ) ai.map_shared = ( flags & 0x1L ) != 0;
    }
    return address;
  }
  /** [lo, lo+len) が ELF segment / alloclist のどの mapping とも重ならないか (hint 判定用)。 */
  private boolean rangeIsFreeForHint( long lo, long len ) {
    long hi = lo + len;
    if( hi <= lo ) return false;                 // overflow / 0 長は relocate へ
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      if( lo < s.p_vaddr + s.buf.length && s.p_vaddr < hi ) return false;
    }
    synchronized( alloclist ) {
      Long k = alloclist.ceilingKey( lo );
      if( k != null && k < hi ) return false;    // 区間内に始まる mapping
      java.util.Map.Entry<Long, AllocInfo> f = alloclist.floorEntry( lo );
      if( f != null && f.getValue() != null && f.getKey() + f.getValue().size > lo ) return false;  // 跨ぐ mapping
    }
    return true;
  }
  // issue #221 step 3d-2c-34: brk 成長先に mmap mapping が居たら Linux 同様 fail させる。
  //   V8/node は brk より上の free 域に 512MB を hint mmap し、その後 glibc の brk 成長が
  //   その mmap 領域を貫通すると、brk Segment (expand_memory) と AllocInfo が同一仮想を
  //   alias して「store した値が別 backing に行き load で 0 が見える」silent corruption に
  //   なっていた (node の InstructionStream.code field が 0 になり RelocIterator で SEGV)。
  //   Linux は brk が既存 mapping に当たると失敗し、glibc malloc は mmap arena に fallback
  //   する。検査は新規に backing が増えるページ範囲 [現 buf 終端, page-ceil(_brk)) のみ
  //   (shrink 後の再成長は buf 内なので検査しない = native の brkHigh と同じ考え方)。
  @Override
  public boolean set_curbrk( long _brk ) {
    Segment bs = segment[ brk_segment_no ];
    if( bs != null && bs.buf != null ) {
      long mappedEnd = bs.p_vaddr + bs.buf.length;
      long newEnd = ( _brk + 0xFFFL ) & ~0xFFFL;
      if( newEnd > mappedEnd ) {
        synchronized( alloclist ) {
          Long k = alloclist.ceilingKey( mappedEnd );
          if( k != null && k < newEnd ) return false;
          java.util.Map.Entry<Long, AllocInfo> f = alloclist.floorEntry( mappedEnd );
          if( f != null && f.getValue() != null && f.getKey() + f.getValue().size > mappedEnd ) return false;
        }
      }
    }
    return super.set_curbrk( _brk );
  }

  // Phase 32: prot を保持して fork 時の reference share 判定に使う。
  public long alloc_and_map( long adrs, int size, int _fd, long offset, int prot ) {
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
      long ptr = allocinfo.map_offset;   // issue #336: long (旧 int = >2GB file offset 切り詰め)
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
    long stackLow = sysinfo.get_stack_bottom_64() - Sysinfo.stack_size64;
    java.util.TreeMap<Long, long[]> regions = new java.util.TreeMap<>();  // start -> {end, isStack, prot}
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      long start = s.p_vaddr, end = s.p_vaddr + s.buf.length;
      if( end <= start ) continue;
      boolean isStk = (start == stackLow);
      // issue: 旧実装は ELF segment の prot を一律 7 (rwx) でハードコードしていた。
      //   実 Linux では text=r-x / rodata=r-- / data=rw- であり、特に read-only な
      //   .rodata が rw 付きで報告されると glibc の "%n in writable segment" 保護
      //   (__readonly_area が /proc/self/maps を読んで format 文字列の segment が
      //   writable か判定) が誤発火し、合法な %n を使う printf で abort する
      //   (ddskk 入力時の emacs クラッシュ等)。ELF p_flags (PF_X=1/PF_W=2/PF_R=4)
      //   を PROT_* (READ=1/WRITE=2/EXEC=4) に変換して実 permission を反映する。
      //   合成 stack と p_flags 未設定 segment は rw- にフォールバック。
      int prot;
      if( isStk || s.p_flags == 0 ) {
        prot = AllocInfo.PROT_READ | AllocInfo.PROT_WRITE;
      } else {
        prot = 0;
        if( (s.p_flags & Segment.PF_R) != 0 ) prot |= AllocInfo.PROT_READ;
        if( (s.p_flags & Segment.PF_W) != 0 ) prot |= AllocInfo.PROT_WRITE;
        if( (s.p_flags & Segment.PF_X) != 0 ) prot |= AllocInfo.PROT_EXEC;
      }
      regions.put( start, new long[]{ end, isStk?1:0, prot } );
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

  // issue #113: free も alloclist を変更するのに synchronized(alloclist) が無く
  //   lock-free だった (alloc/realloc/alloc_huge/resolve_fixed_overlap は全て保護済)。
  //   GIL は syscall (mmap/munmap) 前後で release される (Cpu64.exec_syscall) ため、
  //   worker 並走時に free の alloclist.remove + alloclistGen++ が alloc/resolve と
  //   race しうる。兄弟 mutator と同じ monitor で囲み get→remove→gen++→buf=null を
  //   atomic 化する (intrinsic lock は reentrant なので alloclist 保持経路から呼ばれても安全)。
  public int free( long address, long size ) {   // issue #392 review #1: size を long 化 (≥2GB 切り詰め防止)
    synchronized( alloclist ) {
      AllocInfo allocinfo = alloclist.get( address );
      if( allocinfo == null || allocinfo.size != size ) return -1;
      alloclist.remove( address );
      alloclistGen++;  // Phase 34-mem: cache invalidate
      // Phase 34-mem: free 後も lastAllocInfo cache に AllocInfo の参照が
      // 残るので、buf を null にして cache check の `buf != null` で filter させる。
      allocinfo.buf = null;
      if( sysinfo.verbose( )) {
        process.println( " free : address = " + Util.hexstr( address, 8 ) + " size = " + Util.hexstr( (long)size, 8 ));
      }
      return 0;
    }
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
  public long alloc_huge( long addr, long fullAlignedSize, int prot, boolean fixed ) {  // fixed: software は addr!=0 を常に honor するため未使用
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
    if( WATCH_ACTIVE ) watchBulk( dstAddr, src, srcOff, len );  // issue #113: memcpy/SSE/socket-read 盲点を塞ぐ
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

  // issue #113: [address, address+size) が単一の flat byte[] (Segment.buf または
  //   AllocInfo.buf。huge chunks=buf null は除外) に収まるなら、その buf を返し
  //   解決した index を cs.atomIdx に格納する。収まらない/未マップ/chunks なら null
  //   (caller は per-byte fallback へ)。bulk*ToMem と同じ 4 段 resolution。
  //   atomic wide store/load (VarHandle) が backing array を 1 発で得るための helper。
  private byte[] flatBacking( long address, int size, CacheState cs ) {
    Segment last = cs.lastSegment;
    if( last != null && last.buf != null ) {
      long lo = last.p_vaddr;
      if( address >= lo && address + size <= lo + last.buf.length ) { cs.atomIdx = (int)(address - lo); return last.buf; }
    }
    AllocInfo lastAi = cs.lastAllocInfo;
    if( lastAi != null && lastAi.buf != null && cs.lastAllocInfoGen == alloclistGen
        && address >= lastAi.address && address + size <= lastAi.address + lastAi.size ) {
      cs.atomIdx = (int)(address - lastAi.address); return lastAi.buf;
    }
    for( int i = 0; i < segment.length; i++ ) {
      Segment s = segment[i];
      if( s == null || s.buf == null ) continue;
      long lo = s.p_vaddr;
      if( address >= lo && address + size <= lo + s.buf.length ) { cs.lastSegment = s; cs.atomIdx = (int)(address - lo); return s.buf; }
    }
    java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( address );
    if( e != null ) {
      AllocInfo ai = e.getValue();
      if( ai != null && ai.buf != null && address >= ai.address && address + size <= ai.address + ai.size ) {
        cs.lastAllocInfo = ai; cs.lastAllocInfoGen = alloclistGen; cs.atomIdx = (int)(address - ai.address); return ai.buf;
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
      if( multiThreadActive == 0 && address >= lo && address + len <= lo + last.buf.length ) {
        // issue #113 (H4): instruction fetch fast path は epoch を検証しないため、
        //   worker 並走時 (multiThreadActive!=0) は per-byte load8 (epoch coherent) に
        //   落とす。さもないと別 thread の code 書込 (.eln native-comp 等) が見えず
        //   stale/部分書込の命令を arraycopy して wild jump (RIP=ゼロ埋め域) で crash。
        System.arraycopy( last.buf, (int)(address - lo), buf, 0, len );
        return;
      }
    }
    // 2-LRU: A miss → B を試行、hit なら swap (B が今後の hot に格上げ)
    Segment lastB = cs.lastSegmentB;
    if( lastB != null && lastB.buf != null ) {
      long lo = lastB.p_vaddr;
      if( multiThreadActive == 0 && address >= lo && address + len <= lo + lastB.buf.length ) {
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
        if( FAULT_AS_EFAULT.get() ) throw new SegfaultException( );  // issue #441: syscall arg fault → EFAULT
        dumpFaultDiag( "load8", address );  // issue #113 review #3: load8/store8 共通 dump helper に集約
        raiseSegv( address );  // issue #113: System.exit せず、その process だけ SIGSEGV 終了 (issue #548: fault 番地)
      }
    }
    return( cs.cache[(int)(address - cs.cache_address)] );
  }


  // メモリに1バイトのデータを書き込む
  // Phase 27 step 61: store8 fast path (~50 byte) を inline 可能に。
  //   slow path (segment loop / segfault dump) を別 method に分離。
  public final boolean store8( long address, int data ) {
    if( WATCH_ACTIVE ) {
      watchStore( address, data & 0xFFL, 1, "s8" );  // issue #113
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
        if( FAULT_AS_EFAULT.get() ) throw new SegfaultException( );  // issue #441: syscall arg fault → EFAULT
        dumpFaultDiag( "store8", address );  // issue #113 review #3: load8/store8 共通 dump helper に集約
        raiseSegv( address );  // issue #113: System.exit せず、その process だけ SIGSEGV 終了 (issue #548: fault 番地)
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
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
      Segment s = lookupSegment2( cs, address, 2 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        return (short)( (b[idx] & 0xFF) | ((b[idx+1] & 0xFF) << 8) );
      }
    } else if( ATOMIC_WIDE && (address & 1) == 0 ) {   // issue #113: aligned 2B を atomic load (torn read 防止)
      byte[] b = flatBacking( address, 2, cs );
      if( b != null && (cs.atomIdx & 1) == 0 ) return (short) VH_SHORT.get( b, cs.atomIdx );
    }
    return (short)( ((int)load8( address ) & 0xFF) | (((int)load8( address+1 ) & 0xFF) << 8) );
  }

  // メモリからの4バイトリード
  public final int load32( long address ) {
    if( DT_LOAD ) detectTruncLoad( address );  // issue #113
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
      Segment s = lookupSegment2( cs, address, 4 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        return  (b[idx]   & 0xFF)
             | ((b[idx+1] & 0xFF) <<  8)
             | ((b[idx+2] & 0xFF) << 16)
             | ((b[idx+3] & 0xFF) << 24);
      }
    } else if( ATOMIC_WIDE && (address & 3) == 0 ) {   // issue #113: aligned 4B を atomic load (torn read 防止)
      byte[] b = flatBacking( address, 4, cs );
      if( b != null && (cs.atomIdx & 3) == 0 ) return (int) VH_INT.get( b, cs.atomIdx );
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
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
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
    } else if( ATOMIC_WIDE && (address & 7) == 0 ) {   // issue #113: aligned 8B を atomic load (torn read 防止)
      byte[] b = flatBacking( address, 8, cs );
      if( b != null && (cs.atomIdx & 7) == 0 ) return (long) VH_LONG.get( b, cs.atomIdx );
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
    if( WATCH_ACTIVE ) watchStore( address, ((long)value) & 0xFFFFL, 2, "s16" );  // issue #113
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
      Segment s = lookupSegment2( cs, address, 2 );
      if( s != null ) {
        int idx = (int)(address - s.p_vaddr);
        byte[] b = s.buf;
        b[idx]   = (byte)( value      );
        b[idx+1] = (byte)( value >> 8 );
        cs.cache_address = -1L;
        return;
      }
    } else if( ATOMIC_WIDE && (address & 1) == 0 ) {   // issue #113: aligned 2B を atomic store (torn write 防止)
      byte[] b = flatBacking( address, 2, cs );
      if( b != null && (cs.atomIdx & 1) == 0 ) {
        cs.cache_address = -1L;
        globalStoreEpoch++;
        VH_SHORT.set( b, cs.atomIdx, value );
        return;
      }
    }
    store8( address+0,  value        & 0xFF );
    store8( address+1, (value >> 8 ) & 0xFF );
  }

  // メモリへの4バイトライト
  public final void store32( long address, int value ) {
    if( DT_STORE ) detectTruncStore( address, ((long)value) & 0xFFFFFFFFL, 4 );  // issue #113
    if( WATCH_ACTIVE ) watchStore( address, ((long)value) & 0xFFFFFFFFL, 4, "s32" );  // issue #113
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
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
    } else if( ATOMIC_WIDE && (address & 3) == 0 ) {   // issue #113: aligned 4B を atomic store (torn write 防止)
      byte[] b = flatBacking( address, 4, cs );
      if( b != null && (cs.atomIdx & 3) == 0 ) {
        cs.cache_address = -1L;
        globalStoreEpoch++;
        VH_INT.set( b, cs.atomIdx, value );
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
    if( WATCH_ACTIVE ) watchStore( address, value, 8, "s64" );  // issue #113
    CacheState cs = tlCache.get();
    if( multiThreadActive == 0 ) {
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
    } else if( ATOMIC_WIDE && (address & 7) == 0 ) {   // issue #113: aligned 8B を atomic store (torn write 防止)
      byte[] b = flatBacking( address, 8, cs );
      if( b != null && (cs.atomIdx & 7) == 0 ) {
        cs.cache_address = -1L;
        globalStoreEpoch++;
        VH_LONG.set( b, cs.atomIdx, value );
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
  // issue #113: fault を起こした「実 thread」の Cpu64 を返す。worker (Thread64) なら
  //   その cpu、それ以外 (main) なら process.cpu。crash dump を faulting thread に
  //   正しく帰属させる (旧 dump は常に process.cpu=main を使い worker fault を誤報告)。
  private Cpu64 faultingCpu() {
    Thread cur = Thread.currentThread();
    if( cur instanceof Thread64 ) { Cpu64 c = ((Thread64)cur).cpu; if( c != null ) return c; }
    return ( process != null && process.cpu instanceof Cpu64 ) ? (Cpu64)process.cpu : null;
  }

  // issue #113 (review #3): load8 / store8 の segfault 診断 dump を集約。faulting
  //   thread の Cpu64 (worker は Thread64.cpu) に基づき seg/mmap/register/RIP/region/
  //   backtrace を stderr へ出す。i386 (Cpu64 でない) でも RIP 行だけは
  //   current_thread_rip() で必ず出す (review #1)。出力後に呼び元が raiseSegv() する。
  private void dumpFaultDiag( String tag, long address ) {
    Cpu64 fc = faultingCpu();
    // review #1: i386 (fc==null) でも RIP を出すため current_thread_rip() に fallback。
    long rip_p = ( fc != null ) ? fc.get_ip() : current_thread_rip();
    java.io.PrintStream es = System.err;  // dump は stderr へ。2> で capture でき interactive stdout を汚さない
    es.println( "EMULIN_SEGV ===== " + tag + " fault thread=" + Thread.currentThread().getName() + " =====" );
    es.println( "  Segmentation Fault address(" + tag + ") = : " + Util.hexstr( address, 8 ) );
    es.println( "  evals = " + process.evals( ) );
    for( int dbg = 0; dbg < segment.length; dbg++ ) {
      if( segment[dbg].buf != null )
        es.println( "  seg[" + dbg + "]: [" + Util.hexstr( segment[dbg].p_vaddr, 8 ) + "," + Util.hexstr( segment[dbg].p_vaddr + segment[dbg].buf.length, 8 ) + ")" );
    }
    es.println( "  RIP=" + Long.toHexString( rip_p ) );  // review #1: i386 含め全 CPU で RIP を出す
    boolean dump_all = "all".equals( System.getenv( "EMULIN_DUMP_MMAPS" ) );
    int alloc_n = 0;
    for( java.util.Map.Entry<Long, AllocInfo> ent : alloclist.entrySet() ) {
      AllocInfo ai = ent.getValue();
      if( ai == null ) continue;
      long start = ent.getKey(), end = start + ai.size;
      boolean show = dump_all || ( ai.size >= 1024*1024 ) ||
                     ( rip_p >= start - 0x10000 && rip_p < end + 0x10000 ) ||
                     ( address >= start - 0x10000 && address < end + 0x10000 );
      if( show ) { es.println( "  mmap: [" + Util.hexstr( start, 8 ) + "," + Util.hexstr( end, 8 ) + ") size=" + ai.size + ( ai.map_path != null ? " " + ai.map_path : "" ) ); alloc_n++; }
      if( alloc_n >= 200 ) { es.println( "  ... (" + alloclist.size() + " total mmaps)" ); break; }
    }
    if( fc != null ) {
      long[] r = fc.r64;
      String[] nm = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
      for( int gi = 0; gi < 16; gi++ ) es.println( "  " + nm[gi] + "=" + Long.toHexString( r[gi] ) );
      es.println( "  fs_base=" + Long.toHexString( fc.fs_base ) );
      long ripv = fc.cur_insn_rip != 0 ? fc.cur_insn_rip : fc.get_ip();
      long ripg = fc.get_ip();
      es.println( "  TRUE_RIP(cur_insn_rip)=" + Long.toHexString( ripv ) );
      es.println( "  insn@TRUE_RIP=" + insnBytesAt( ripv ) );
      if( ripg != ripv ) es.println( "  insn@RIP(get_ip=" + Long.toHexString( ripg ) + ")=" + insnBytesAt( ripg ) );
      es.println( "  region: fault=" + regionLabel( address ) + " | cur_insn_rip=" + regionLabel( ripv ) + " | get_ip=" + regionLabel( ripg ) );
      long bsp = r[4];
      long pbase = 0x555555554000L;
      StringBuilder bt = new StringBuilder( "  backtrace(stack-scan vaddr):" );
      int btn = 0;
      for( int o2 = 0; o2 < 0x400 && btn < 24; o2 += 8 ) {
        long sa = bsp + o2;
        if( !in( sa ) || !in( sa + 7 ) ) break;
        long v = load64( sa );
        if( v >= pbase && v < pbase + 0x400000L ) { bt.append( " +0x" ).append( Integer.toHexString( o2 ) ).append( "=" ).append( Long.toHexString( v - pbase ) ); btn++; }
        // 3d-2c-34: ET_EXEC (非 PIE、node 等の固定 0x400000 帯) の return address も拾う。
        //   旧 filter は PIE base 帯のみで node (121MB ET_EXEC) の backtrace が空だった。
        else if( v >= 0x400000L && v < 0x8000000L ) { bt.append( " +0x" ).append( Integer.toHexString( o2 ) ).append( "=X:" ).append( Long.toHexString( v ) ); btn++; }
      }
      es.println( bt.toString() );
      // issue #113: EMULIN_TRACE_RING=1 のとき、fault 直前に実行した RIP 列を新しい順に
      //   region+命令バイト付きで出す。worker が壊れた pointer (例 0x58) へ wild jump した
      //   「発生元の正常命令」を objdump 逆引き可能な形で特定する。
      if( Cpu64.TRACE_RING && fc.ripRing != null ) {
        es.println( "  RIP-RING (newest first, last " + Cpu64.RIPRING_SIZE + " executed):" );
        for( int k = 1; k <= Cpu64.RIPRING_SIZE; k++ ) {
          long rr = fc.ripRing[ (fc.ripRingPos - k) & (Cpu64.RIPRING_SIZE - 1) ];
          if( rr == 0 ) continue;
          es.println( "    [" + (k-1) + "] 0x" + Long.toHexString( rr ) + " " + regionLabel( rr ) + " insn=" + insnBytesAt( rr ) );
        }
      }
    }
    es.flush();
  }
  private long current_thread_rip() {
    Thread cur = Thread.currentThread();
    if( cur instanceof Thread64 ) {
      Cpu64 c = ((Thread64)cur).cpu;
      if( c != null ) return c.get_ip();
    }
    if( process != null && process.cpu != null ) return process.cpu.get_ip();
    return -1L;
  }

  // issue #113: 統一 store watchpoint。範囲アドレス監視 (WATCH_STORE_ADDR..+LEN) と
  //   (マスク付き) 値一致 (WATCH_STORE_VAL & MASK) のいずれかにヒットしたら
  //   真の RIP (cur_insn_rip 優先) + addr + value + region label + eval を dump。
  //   破壊値 0x40000000_xxxxxxxx は run 毎に低位が変わるので、
  //     EMULIN_WATCH_STORE_VAL=4000000000000000
  //     EMULIN_WATCH_STORE_VAL_MASK=ffffffff00000000
  //   で高位 32bit だけ照合すれば低位に関わらず確実に捕捉できる。
  //   lispsym 配列全体を狙うなら EMULIN_WATCH_STORE_ADDR=<base> EMULIN_WATCH_STORE_LEN=20000。
  //   flood 防止に dump は上限 (wsDumps)。EMULIN_TRACK_INSN_RIP=1 併用で真の RIP。
  static volatile int wsDumps = 0;  // volatile: multi-thread emacs でも cap が確実に見える (race で ±数件は許容、flood は防ぐ)
  static final int WS_MAX_DUMPS = 2000;  // flood 防止上限 (EMULIN_WATCH_EVAL_LO/HI で crash window に絞れば十分)
  private boolean valHitMasked( long value, int size ) {
    long mask = ( WATCH_STORE_VAL_MASK != 0L ) ? WATCH_STORE_VAL_MASK : -1L;
    long want = WATCH_STORE_VAL & mask;
    if( size >= 8 ) return ( value & mask ) == want;
    if( size == 4 ) {
      // 32bit store は slot の hi/lo どちらの dword にもタグ値を置きうる
      int v = (int)value;
      int mLo = (int)mask, mHi = (int)( mask >>> 32 );
      return ( mLo != 0 && ( v & mLo ) == (int)want )
          || ( mHi != 0 && ( v & mHi ) == (int)( want >>> 32 ) );
    }
    return false;  // 1/2 byte store はタグ値照合の対象外 (範囲監視でのみ拾う)
  }
  private boolean addrHit( long address, int size ) {
    long lo = WATCH_STORE_ADDR;
    long hi = lo + ( WATCH_STORE_LEN != 0L ? WATCH_STORE_LEN : 8L );
    return address < hi && address + size > lo;
  }
  private void watchStore( long address, long value, int size, String how ) {
    if( wsDumps >= WS_MAX_DUMPS ) return;
    // EMULIN_WATCH_EVAL_LO/HI が設定されていれば crash window 外の store は無視 (cap 枯渇回避)
    if( ( DT_EVAL_LO != 0L || DT_EVAL_HI != Long.MAX_VALUE ) && process != null ) {
      long ev0 = process.evals();
      if( ev0 < DT_EVAL_LO || ev0 > DT_EVAL_HI ) return;
    }
    final boolean haveAddr = WATCH_STORE_ADDR != 0L;
    final boolean haveVal  = WATCH_STORE_VAL  != 0L;
    boolean aHit = haveAddr && addrHit( address, size );
    boolean vHit = haveVal  && valHitMasked( value, size );
    // 両方指定時は AND (= 監視範囲内かつ高タグ破壊値 → #113 を最小ノイズで特定)。
    // 片方のみ指定時はそれ単独で照合。
    boolean hit = ( haveAddr && haveVal ) ? ( aHit && vHit ) : ( aHit || vHit );
    if( !hit ) return;
    wsDumps++;
    String why = ( aHit ? "addr" : "" ) + ( vHit ? "val" : "" );
    long rip = current_insn_rip();
    if( rip <= 0L ) rip = current_thread_rip();
    long ev = ( process != null ) ? process.evals() : 0L;
    System.err.println( "DBG_WSTORE[" + how + "/" + why + "] addr=0x" + Long.toHexString( address )
      + " size=" + size + " val=0x" + Long.toHexString( value )
      + " rip=0x" + Long.toHexString( rip ) + " eval=" + ev
      + " | region=" + regionLabel( address ) + " | rip_region=" + regionLabel( rip ) );
    System.err.flush();
  }

  // issue #113: bulk (memcpy/SSE/socket-read) 経路の watchpoint。範囲オーバーラップ or
  //   マスク値一致 (8-byte little-endian window 走査)。両方指定時は「範囲内に重なる
  //   8-byte window がタグ値と一致」を報告 (= memcpy が lispsym へ破壊値を運ぶ瞬間)。
  //   値走査は debug-only。
  private void watchBulk( long dstAddr, byte[] src, int srcOff, int len ) {
    if( wsDumps >= WS_MAX_DUMPS ) return;
    if( ( DT_EVAL_LO != 0L || DT_EVAL_HI != Long.MAX_VALUE ) && process != null ) {
      long ev0 = process.evals();
      if( ev0 < DT_EVAL_LO || ev0 > DT_EVAL_HI ) return;
    }
    final boolean haveAddr = WATCH_STORE_ADDR != 0L;
    final boolean haveVal  = WATCH_STORE_VAL  != 0L;
    boolean overlap = haveAddr && ( dstAddr + len > WATCH_STORE_ADDR )
                   && ( dstAddr < WATCH_STORE_ADDR + ( WATCH_STORE_LEN != 0L ? WATCH_STORE_LEN : 8L ) );
    // addr-only: 範囲に bulk が重なれば即報告 (symbol table への memcpy 自体が異常)
    if( haveAddr && !haveVal ) {
      if( overlap ) {
        wsDumps++;
        long rip = current_insn_rip(); if( rip <= 0L ) rip = current_thread_rip();
        long ev = ( process != null ) ? process.evals() : 0L;
        System.err.println( "DBG_WSTORE[bulk/addr] dst=0x" + Long.toHexString( dstAddr )
          + " len=" + len + " rip=0x" + Long.toHexString( rip ) + " eval=" + ev
          + " | region=" + regionLabel( dstAddr ) + " | rip_region=" + regionLabel( rip ) );
        System.err.flush();
      }
      return;
    }
    // val (単独) or AND (範囲に重なる場合のみ): 8-byte little-endian window 走査
    if( haveVal && len >= 8 && ( !haveAddr || overlap ) ) {
      long mask = ( WATCH_STORE_VAL_MASK != 0L ) ? WATCH_STORE_VAL_MASK : -1L;
      long want = WATCH_STORE_VAL & mask;
      int end = len - 8;
      for( int i = 0; i <= end; i++ ) {
        long a = dstAddr + i;
        if( haveAddr && !addrHit( a, 8 ) ) continue;  // AND: 監視範囲に重なる window だけ
        long v = ( src[srcOff+i]   & 0xFFL )
               | ( ( src[srcOff+i+1] & 0xFFL ) << 8 )
               | ( ( src[srcOff+i+2] & 0xFFL ) << 16 )
               | ( ( src[srcOff+i+3] & 0xFFL ) << 24 )
               | ( ( src[srcOff+i+4] & 0xFFL ) << 32 )
               | ( ( src[srcOff+i+5] & 0xFFL ) << 40 )
               | ( ( src[srcOff+i+6] & 0xFFL ) << 48 )
               | ( ( src[srcOff+i+7] & 0xFFL ) << 56 );
        if( ( v & mask ) == want ) {
          wsDumps++;
          long rip = current_insn_rip(); if( rip <= 0L ) rip = current_thread_rip();
          long ev = ( process != null ) ? process.evals() : 0L;
          System.err.println( "DBG_WSTORE[bulk/" + ( haveAddr ? "addrval" : "val" ) + "] dst=0x" + Long.toHexString( a )
            + " val=0x" + Long.toHexString( v ) + " rip=0x" + Long.toHexString( rip ) + " eval=" + ev
            + " | region=" + regionLabel( a ) + " | rip_region=" + regionLabel( rip ) );
          System.err.flush();
          break;
        }
      }
    }
  }

  public static final long WATCH_STORE_VAL;
  public static final long WATCH_STORE_VAL_MASK; // issue #113: 0 => 完全一致。set => (val & MASK)==(WATCH_STORE_VAL & MASK) で照合 (低位変動する 0x40000000_ 高タグ破壊値を捕捉)
  public static final long WATCH_STORE_ADDR;
  public static final long WATCH_STORE_LEN;      // issue #113: 0 => 8 byte 窓 (旧互換)。set => [ADDR, ADDR+LEN) の範囲監視 (lispsym 配列全体など)
  static final boolean WATCH_ACTIVE;             // issue #113: ADDR or VAL のいずれかが設定されている
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
    long m = 0L;
    String sm = System.getenv("EMULIN_WATCH_STORE_VAL_MASK");
    if( sm != null ) { try { m = Long.parseUnsignedLong(sm, 16); } catch( NumberFormatException ignored ) {} }
    WATCH_STORE_VAL_MASK = m;
    long ln = 0L;
    String sl = System.getenv("EMULIN_WATCH_STORE_LEN");
    if( sl != null ) {
      try { ln = sl.startsWith("0x") ? Long.parseUnsignedLong(sl.substring(2),16) : Long.parseLong(sl); }
      catch( NumberFormatException ignored ) {}
    }
    WATCH_STORE_LEN = ln;
    WATCH_ACTIVE = ( WATCH_STORE_ADDR != 0L ) || ( WATCH_STORE_VAL != 0L );
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

