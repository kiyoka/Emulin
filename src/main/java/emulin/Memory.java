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
  boolean use;
  long address;
  int size;
  int fd;
  int map_offset;
  int map_size;
  byte buf[];

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
    if( buf != null ) {
      _allocinfo.buf = new byte[buf.length];
      System.arraycopy( buf, 0, _allocinfo.buf, 0, buf.length );
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
  private static class CacheState {
    long cache_address = -1L;
    long cache_epoch = -1L;
    byte[] cache = new byte[cache_size];
    // step 61: store8 fast path で「直前と同じ segment」判定に使う
    Segment lastSegment = null;
  }
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
  public long alloc_and_map( long adrs, int size, int _fd, int offset ) {
    long address = alloc( adrs, size );
    AllocInfo allocinfo = alloclist.get( address );
    allocinfo.fd         = _fd;
    allocinfo.map_offset = offset;
    allocinfo.map_size   = size;
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
        // MAP_FIXED 相当: 指定 address に確保 (mark_address は更新しない)
        address = adrs;
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
      if( sysinfo.verbose( )) {
        process.println( " alloc( ) : address = " + Util.hexstr( address, 8 ) +  " next_address = " + Util.hexstr( mark_address, 8 ) + " pages = " + pages );
      }
      return( address );
    }
  }

  public int realloc( long old_address, int size ) {
    AllocInfo allocinfo = alloclist.get( old_address );
    if( allocinfo == null ) return -1;
    byte[] old_buf = allocinfo.buf;
    int old_size = allocinfo.size;
    allocinfo.size = size;
    allocinfo.buf  = new byte[size];
    System.arraycopy( old_buf, 0, allocinfo.buf, 0, Math.min( old_size, size ) );
    return 0;
  }

  public int free( long address, int size ) {
    AllocInfo allocinfo = alloclist.get( address );
    if( allocinfo == null || allocinfo.size != size ) return -1;
    alloclist.remove( address );
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
      if( address < ai.address + ai.size ) return true;
    }
    return false;
  }

  // メモリからの1バイトリード — per-byte 32 byte cache (ThreadLocal)
  // Phase 27 step 61: load8 全体が 698 byte で JIT MaxInlineSize 超え非 inline。
  //   fast/slow path 分離 (slow を load8_slow に)。
  // step 64: load8 を 65 → ~30 byte に再圧縮 (JIT MaxInlineSize=35 より小さく)。
  //   address & ~31 = align. off = address - align で 0..31 が確定。
  //   cache_epoch チェックは load8_slow 側で実施 (multi-thread 限定なので
  //   conditional 分岐が hot path から消える)。
  byte load8( long address ) {
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
    for( i = 0 ; i < segment.length ; i++ ) {
      if( segment[i].in( address )) {
        segment[i].peekbs( align_address, cache );
        cs.lastSegment = segment[i];
        _in = true;
        break;
      }
    }
    if( !_in ) {
      java.util.Map.Entry<Long, AllocInfo> e = alloclist.floorEntry( address );
      if( e != null ) {
        AllocInfo allocinfo = e.getValue();
        long adrs       = allocinfo.address;
        int size        = allocinfo.size;
        int align_index = (int)(align_address - adrs);
        if( address < adrs + size ) {
          for( int j = 0 ; j < cache_size ; j++ ) {
            cache[j] = 0;
            if( align_index+j >= 0 && align_index+j < size ) { cache[j] = allocinfo.buf[ align_index+j ]; }
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
        System.exit( 1 );
      }
    }
    return( cs.cache[(int)(address - cs.cache_address)] );
  }


  // メモリに1バイトのデータを書き込む
  // Phase 27 step 61: store8 fast path (~50 byte) を inline 可能に。
  //   slow path (segment loop / segfault dump) を別 method に分離。
  public boolean store8( long address, int data ) {
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
        if( address < allocinfo.address + allocinfo.size ) {
          allocinfo.buf[ (int)(address - allocinfo.address) ] = (byte)data;
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
  public short load16( long address ) {
    short ret;
    ret = (short) ( ((int)load8( address ) & 0xFF) | (((int)load8( address+1 ) & 0xFF) << 8));
    //    if( sysinfo.debug( )) {
    //      process.println( "  Load16(" + Util.hexstr( address, 8 ) + ") = [" + Util.hexstr( ret & 0xFFFF, 4 ) + "] " );
    //    }
    return( ret );
  }

  // メモリからの4バイトリード
  public int load32( long address ) {
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
  public long load64( long address ) {
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
  public void store16( long address, short value ) {
    store8( address+0,  value        & 0xFF );
    store8( address+1, (value >> 8 ) & 0xFF );
    //    if( sysinfo.debug( )) {
    //      process.println( "  Store16(" + Util.hexstr( address, 8 ) + "," + Util.hexstr( value & 0xFFFF, 4 ) + ") " );
    //    }
  }

  // メモリへの4バイトライト
  public void store32( long address, int value ) {
    store8( address+0,  value        & 0xFF );
    store8( address+1, (value >>  8) & 0xFF );
    store8( address+2, (value >> 16) & 0xFF );
    store8( address+3, (value >> 24) & 0xFF );
    //    if( sysinfo.debug( )) {
    //      process.println( "  Store32(" + Util.hexstr( address, 8 ) + "," + Util.hexstr( value, 8 ) + ") " );
    //    }
  }

  // メモリへの8バイトライト
  public void store64( long address, long value ) {
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
  }

  // 文字列を格納する
  public long storeString( long address, String str ) {
    // Phase 27 step 42: 旧実装は (byte)char で Latin-1 キャスト。
    //   非 ASCII (例: Hungarian の ő = U+0151) で `(byte)0x151` = 0x51 = 'Q'
    //   と化けて、getdents64 経由でファイル名が壊れていた。UTF-8 で encode する。
    byte[] bytes = str.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    for( int i = 0; i < bytes.length; i++ ) {
      store8( address, bytes[i] );
      address++;
    }
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

