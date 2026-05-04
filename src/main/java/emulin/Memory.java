// ----------------------------------------
//  Process Memory Management
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/23 11:16:42 $ 
//  $Id: Memory.java,v 1.37 2000/01/23 11:16:42 kiyoka Exp $
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
  static int cache_size = 8;
  static int memory_page_size = 4096;
  static long memory_top = 0x40000000L;
  Syscall syscall;
  long mark_address;
  Vector alloclist;
  // Phase 27 step 28: pthread (CLONE_VM) で複数 thread が同じ Memory を
  //   read/write するとき、per-byte cache (cache_address + cache[8]) を
  //   共有するとリフィル中に race して `index out of bounds` で crash する。
  //   ThreadLocal にして各 Java thread が独立した cache を持つ。
  private static class CacheState {
    long cache_address = -1L;
    byte[] cache = new byte[cache_size];
  }
  private final ThreadLocal<CacheState> tlCache =
      ThreadLocal.withInitial( CacheState::new );

  // 初期化
  Memory( Sysinfo _sysinfo, Syscall _syscall, Process _process ) {
    int i;
    sysinfo = _sysinfo;
    syscall = _syscall;
    process = _process;
    mark_address = memory_top;
    alloclist = new Vector( );
  }

  // 自分の複製を返す
  public Memory duplicate( Process _process ) {
    int i;
    Memory _memory = new Memory( sysinfo, _process.syscall, _process );
    _memory.mark_address = mark_address;
    _memory.alloclist = new Vector( );
    // 全てのエレメントを複製する。
    for( i = 0 ; i < alloclist.size( ) ; i++ ) { 
      AllocInfo allocinfo = (AllocInfo)alloclist.elementAt( i );
      if( null == allocinfo ) {
	_memory.alloclist.addElement( (Object)null );
      }
      else {
	_memory.alloclist.addElement( (Object)allocinfo.duplicate( ));
      }
    }
    _memory.update_info( (Elf)this );
    return( _memory );
  }

  // メモリを確保してファイルにマッピングする。
  public long alloc_and_map( long adrs, int size, int _fd, int offset ) {
    long address = alloc( adrs, size );
    AllocInfo allocinfo;
    allocinfo = (AllocInfo)alloclist.lastElement( );
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
  //   adrs != 0 : 指定アドレスに確保 (MAP_FIXED 相当)。mark_address は
  //               「確保末尾より大きい場合のみ」更新する。低位の MAP_FIXED で
  //               mark_address を後退させると、次の mmap(0) で既存領域と
  //               衝突してしまう (ld.so が複数ライブラリをロードする際に
  //               libcom_err 等が静かに上書きされて失敗する原因)。
  public long alloc( long adrs, int size ) {
    AllocInfo allocinfo = new AllocInfo( );
    long address = mark_address;
    int pages = 0;
    if( adrs != 0 ) {
      address = adrs;
    }
    allocinfo.use     = true;
    allocinfo.address = address;
    allocinfo.size    = size;
    allocinfo.buf     = new byte[size];
    alloclist.addElement( (Object)allocinfo );
    pages = size / memory_page_size;
    if( pages == 0 ) pages++;
    long end = address + (long)pages * (long)memory_page_size;
    if( end > mark_address ) mark_address = end;
    if( sysinfo.verbose( )) {
      process.println( " alloc( ) : address = " + Util.hexstr( address, 8 ) +  " next_address = " + Util.hexstr( mark_address, 8 ) + " pages = " + pages );
    }
    return( address );
  }

  public int realloc( long old_address, int size ) {
    int i;
    byte old_buf[];
    int  old_size;
    AllocInfo allocinfo;

    // 既に確保されているメモリを探し、メモリサイズを変更する
    for( i = 0 ; i < alloclist.size( ) ; i++ ) {
      int j;
      allocinfo = (AllocInfo)alloclist.elementAt( i );
      if( old_address == allocinfo.address ) {
	// 新しいメモリバッファを確保する。
	old_buf  = allocinfo.buf;
	old_size = allocinfo.size;
	allocinfo.size    = size;
	allocinfo.buf     = new byte[size];
	// 古いデータをコピーする
	for( j = 0 ; j < old_size ; j++ ) {
	  allocinfo.buf[j] = old_buf[j];
	}
	return( 0 );
      }
    }
    return( -1 );
  }

  public int free( long address, int size ) {
    int i;
    AllocInfo allocinfo;
    for( i = 0 ; i < alloclist.size( ) ; i++ ) {
      allocinfo = (AllocInfo)alloclist.elementAt( i );
      if( (address == allocinfo.address)&&(size == allocinfo.size) ) {
	alloclist.removeElementAt( i );
	if( sysinfo.verbose( )) {
	  process.println( " free : address = " + Util.hexstr( address, 8 ) + " size = " + Util.hexstr( (long)size, 8 ));
	}
	return( 0 );
      }
    }
    return( -1 );
  }

  // アドレスが有効なメモリ内か調べる
  public boolean in( long address ) {
    int i;
    boolean ret = false;
    for( i = 0 ; i < segment.length ; i++ ) {
      if( segment[i].in( address )) {
	ret = true;
	break;
      }
    }
    // メモリ確保アドレスからの調査
    if( !alloclist.isEmpty( )) {
      AllocInfo start_allocinfo = (AllocInfo)alloclist.firstElement( );
      AllocInfo end_allocinfo   = (AllocInfo)alloclist.lastElement( );
      if( (start_allocinfo.address <= address) &&
	  ( address < (end_allocinfo.address + end_allocinfo.size))) {
	ret = true;
      }
    }
    return( ret );
  }

  // メモリからの1バイトリード — per-byte 8 byte cache (ThreadLocal)
  byte load8( long address ) {
    int i;
    boolean _in = false;
    CacheState cs = tlCache.get();
    long align_address = (address / cache_size) * cache_size;
    if( cs.cache_address != align_address ) {
      cs.cache_address = align_address;
      byte[] cache = cs.cache;
      for( i = 0 ; i < segment.length ; i++ ) {
	if( segment[i].in( address )) {
	  segment[i].peekbs( align_address, cache );
	  _in = true;
	  break;
	}
      }
      if( !_in ) {
	for( i = 0 ; i < alloclist.size( ) ; i++ ) {
	  AllocInfo allocinfo = (AllocInfo)alloclist.elementAt( i );
	  long adrs           = allocinfo.address;
	  int size            = allocinfo.size;
	  int align_index     = (int)(align_address - adrs);
	  if( ( adrs <= address            ) && ( address            < (adrs + size))) {
	    int j;
	    for( j = 0 ; j < cache_size ; j++ ) {
	      cache[j] = 0;
	      if( align_index+j < size ) { cache[j] = allocinfo.buf[ align_index+j ]; }
	    }
	    _in = true;
	  }
	}
	if( ! _in ) {
	  process.println( "  Segmentation Fault address(load8) = : " + Util.hexstr( address, 8 ) );
	  process.println( "  Segmentation Fault address(load8) :   evals = " + process.evals( ));
	  for(int dbg=0;dbg<segment.length;dbg++){if(segment[dbg].buf!=null)process.println("  seg["+dbg+"]: ["+Util.hexstr(segment[dbg].p_vaddr,8)+","+Util.hexstr(segment[dbg].p_vaddr+segment[dbg].buf.length,8)+")");}
	  if(process.cpu!=null) process.println("  RIP="+Long.toHexString(process.cpu.get_ip()));
	  System.exit( 1 );
	}
      }
    }
    return( cs.cache[(int)(address - cs.cache_address)] );
  }


  // メモリに1バイトのデータを書き込む
  public boolean store8( long address, int data ) {
    int i;
    boolean ret   = false;
    tlCache.get().cache_address = -1L; // キャッシュの破棄 (current thread のみ)
    for( i = 0 ; i < segment.length ; i++ ) {
      if( segment[i].in( address )) {
	segment[i].pokeb( address, (byte)data );
	ret = true;
	break;
      }
    }
    if( !ret ) {
      for( i = 0 ; i < alloclist.size( ) ; i++ ) {
	AllocInfo allocinfo = (AllocInfo)alloclist.elementAt( i );
	long adrs = allocinfo.address;
	int size  = allocinfo.size;
	int fd    = allocinfo.fd;
	if( ( adrs <= address) &&
	    ( address < (adrs + size))) {
	  if( -1 == fd ) {
	    // ファイルがマップされていないただのメモリの場合
	    allocinfo.buf[ (int)(address - adrs) ] = (byte)data;
	    ret = true;
	  }
	  else {
	    // ファイルがマップされている場合
	    allocinfo.buf[ (int)(address - adrs) ] = (byte)data;
	    if( false ) {
	      process.println( "  Warning : Emulin    memory mapped file store is Unsupport... fd = " +
				  fd + "  address = " + Util.hexstr( address, 8 ) );
	    }
	    ret = true;
	  }
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
    int ret;
    ret =
	   (int)
	   ( ((int)load8( address ) & 0xFF) |
	     (((int)load8( address+1 ) & 0xFF) << 8 ) |
	     (((int)load8( address+2 ) & 0xFF) << 16) |
	     (((int)load8( address+3 ) & 0xFF) << 24)
	     );
    if( sysinfo.debug( )) {
      process.println( "  Load32(" + Util.hexstr( address, 8 ) + ") = [" + Util.hexstr( ret, 8 ) + "] " );
    }
    return( ret );
  }

  // メモリからの8バイトリード
  public long load64( long address ) {
    long ret;
    ret =  
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
    store8( address+0, (int)(value >>  0   ) & 0xFF );
    store8( address+1, (int)(value >>  8   ) & 0xFF );
    store8( address+2, (int)(value >> 16   ) & 0xFF );
    store8( address+3, (int)(value >> 24   ) & 0xFF );
    store8( address+4, (int)(value >> 32+ 0) & 0xFF );
    store8( address+5, (int)(value >> 32+ 8) & 0xFF );
    store8( address+6, (int)(value >> 32+16) & 0xFF );
    store8( address+7, (int)(value >> 32+24) & 0xFF );
  }

  // 文字列を格納する
  public long storeString( long address, String str ) {
    int i;
    for( i = 0 ; i < str.length( ) ; i++ ) {
      store8( address, (byte)str.charAt( i ));
      address ++;
    }
    store8( address, 0 );
    address ++;
    return( address );
  }

  // 文字列を読み出す
  public String loadString( long address ) {
    int len, i;
    char buf[];
    String ret = "";
    for( i = 0 ; i < 10000 ; i++ ) {
      if(0 == (char)load8( address+i )) { break; }
    }
    len = i;
    buf = new char[len];
    for( i = 0 ; i < len ; i++ ) {
      buf[i] = (char)load8( address+i );
    }
    return( ret.copyValueOf( buf ));
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

