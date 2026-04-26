// ----------------------------------------
//  Segment Information in Elf
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/03 12:52:38 $ 
//  $Id: Segment.java,v 1.22 2000/02/03 12:52:38 kiyoka Exp $
// ----------------------------------------
package emulin;

//
//typedef struct
//{
//  Elf32_Word	p_type;			/* Segment type */
//  Elf32_Off	p_offset;		/* Segment file offset */
//  Elf32_Addr	p_vaddr;		/* Segment virtual address */
//  Elf32_Addr	p_paddr;		/* Segment physical address */
//  Elf32_Word	p_filesz;		/* Segment size in file */
//  Elf32_Word	p_memsz;		/* Segment size in memory */
//  Elf32_Word	p_flags;		/* Segment flags */
//  Elf32_Word	p_align;		/* Segment alignment */
//} Elf32_Phdr;
//

import java.lang.*;
import java.io.*;
import emulin.*;

public class Segment
{
  static int PF_X	= (1 << 0);	/* Segment is executable */
  static int PF_W	= (1 << 1);	/* Segment is writable */
  static int PF_R	= (1 << 2);	/* Segment is readable */

  int p_type;		/* Segment type */
  long p_offset;	/* Segment file offset */
  long p_vaddr;		/* Segment virtual address */
  long p_paddr;		/* Segment physical address */
  long p_filesz;	/* Segment size in file */
  long p_memsz;		/* Segment size in memory */
  int p_flags;		/* Segment flags */
  long p_align;		/* Segment alignment */
  byte buf[];           /* Segment メモリ */

  boolean bss;          /* BSSを含むセグメントか? */

  Sysinfo sysinfo;      /* Process システム情報 */
  Process process;      /* Process 情報 */

  public Segment( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
  }

  // 自分の複製を返す
  public Segment duplicate( ) {
    Segment _segment       =   new Segment( sysinfo, process );
    _segment.p_type        =   p_type;
    _segment.p_offset      =   p_offset;
    _segment.p_vaddr       =   p_vaddr;
    _segment.p_paddr       =   p_paddr;
    _segment.p_filesz      =   p_filesz;
    _segment.p_memsz       =   p_memsz;
    _segment.p_flags       =   p_flags;
    _segment.p_align       =   p_align;
    _segment.buf           =   new byte[ buf.length ];
    System.arraycopy( buf, 0, _segment.buf, 0, buf.length );
    _segment.bss           =   bss;
    return( _segment );
  }

  // ELF32 プログラムヘッダのロード
  public boolean load_ph( RandomAccessFile in ) {
    p_type        =        LoadUtil.little32( in, sysinfo.kernel );
    p_offset      = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    p_vaddr       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    p_paddr       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    p_filesz      = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    p_memsz       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    p_flags       =        LoadUtil.little32( in, sysinfo.kernel );
    p_align       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    print_segment_info( );
    return( true );
  }

  // ELF64 プログラムヘッダのロード (p_flags の位置が ELF32 と異なる)
  public boolean load_ph64( RandomAccessFile in ) {
    p_type        =        LoadUtil.little32( in, sysinfo.kernel );
    p_flags       =        LoadUtil.little32( in, sysinfo.kernel );  // ELF64: p_flags は p_offset の前
    p_offset      =        LoadUtil.little64( in, sysinfo.kernel );
    p_vaddr       =        LoadUtil.little64( in, sysinfo.kernel );
    p_paddr       =        LoadUtil.little64( in, sysinfo.kernel );
    p_filesz      =        LoadUtil.little64( in, sysinfo.kernel );
    p_memsz       =        LoadUtil.little64( in, sysinfo.kernel );
    p_align       =        LoadUtil.little64( in, sysinfo.kernel );
    print_segment_info( );
    return( true );
  }

  void print_segment_info( ) {
    if( sysinfo.debug( )) {
      // タイプの解析
      String t = "";
      if( 0 != (PF_X & p_type)) {  t += "X";  }
      if( 0 != (PF_W & p_type)) {  t += "W";  }
      if( 0 != (PF_R & p_type)) {  t += "R";  }
      process.println( "  ----- Program Segment -----" );
      process.println( "  p_type        : " + Integer.toString( p_type,       16) + "(" + t + ")" );
      process.println( "  p_offset      : " + Long.toString(    p_offset,        16));
      process.println( "  p_vaddr       : " + Long.toString(    p_vaddr,         16));
      process.println( "  p_paddr       : " + Long.toString(    p_paddr,         16));
      process.println( "  p_filesz      : " + Long.toString(    p_filesz,        16));
      process.println( "  p_memsz       : " + Long.toString(    p_memsz,         16));
      process.println( "  p_flags       : " + Integer.toString( p_flags,         16));
      process.println( "  p_align       : " + Long.toString(    p_align,         16));
    }
  }

  // セグメントの最終アドレスを返す
  public long segment_end( ) {
    return( p_vaddr + p_memsz );
  }

  public boolean load_body( RandomAccessFile in ) {
    // ファイルからデータをロードする
    // Linux カーネルはセグメントをページ単位でマップするため、p_memsz をページ境界に
    // 切り上げたサイズで確保する。これにより fetch() の 15 バイト先読みが
    // セグメント末尾を越えてもゼロパディング領域を参照でき Segfault を防ぐ。
    final long PAGE = 0x1000L;
    int alloc_size = (int)(((p_memsz + PAGE - 1) / PAGE) * PAGE);
    buf = new byte[alloc_size];   // ページ境界アライメント確保
    try {
      in.seek( p_offset );
      in.read( buf, 0, (int)p_filesz );
    }
    catch ( IOException m ) {  process.println( "Seek Failed : offset " + p_offset ); return( false ); }
    if( sysinfo.debug( )) {
      process.println( "  ----- Segment body loading  address = " + Util.hexstr( p_paddr, 8 ) + " -----" );
    }
    return( true );
  }

  // スタックセグメントとして初期化する (bottom: スタック底アドレス)
  public void stack( long bottom, int stack_size ) {
    String t = "";
    buf = new byte[stack_size];   // メモリ確保
    p_type  = PF_W | PF_R; // R/W 可能
    p_vaddr = bottom - stack_size;
    p_paddr = bottom - stack_size;
    p_memsz = (long)stack_size;

    if( sysinfo.debug( )) {
      // タイプの解析
      if( 0 != (PF_X & p_type)) {  t += "X";  }
      if( 0 != (PF_W & p_type)) {  t += "W";  }
      if( 0 != (PF_R & p_type)) {  t += "R";  }
      process.println( "  ----- Stack Segment Inited  size = " + Util.hexstr( stack_size, 6 ) + " -----" );
      process.println( "  ----- Segment (stack) -----" );
      process.println( "  p_type        : " + t );
      process.println( "  p_vaddr       : " + Long.toString(    p_vaddr,         16));
      process.println( "  p_paddr       : " + Long.toString(    p_paddr,         16));
      process.println( "  p_memsz       : " + Long.toString(    p_memsz,         16));
    }
  }

  // セグメント中データの読みだし  (byte)
  public byte peekb( long address ) {
    return( buf[(int)(address - p_vaddr)] );
  }

  // セグメント中データの読みだし  (bytes)
  public int peekbs( long address, byte _buf[] ) {
    int i;
    int index = (int)(address - p_vaddr);
    for( i = 0 ; i < _buf.length ; i++, index++ ) {
      if(( index >= 0)&&(index < buf.length )) { _buf[i] = buf[index]; }
      else{ break; }
    }
    return( i );
  }

  // セグメントへのデータ書き込み (byte)
  public void pokeb( long address, byte data ) {
    buf[(int)(address - p_vaddr)] = data;
  }

  // アドレスがセグメント内かどうか調べる (ページ境界アライメント済みバッファ範囲で判定)
  public boolean in( long address ) {
    if( ( p_vaddr <= address ) && ( address < (p_vaddr + buf.length)) ) {
      return( true );
    }
    return( false );
  }

  // メモリサイズをaddress のポイントまで拡張する
  public boolean expand_memory( long address ) {
    boolean ret = true;
    long target_size = address - p_vaddr;
    byte tmp_array[] = new byte[(int)target_size]; // 新バッファ確保
    int i;
    if( sysinfo.verbose( ) ) {
      process.println( "expanded_memory( ):target_size = " + Util.hexstr( target_size, 8 ));
      process.println( "expanded_memory( ):p_memsz     = " + Util.hexstr( p_memsz, 8 ));
    }
    if( p_memsz > target_size ) { // サイズの縮小
      p_memsz = target_size;
    }
    for( i = 0 ; i < p_memsz ; i++ ) {  // 新バッファへのコピー
      tmp_array[i] = buf[i];
    }
    buf = tmp_array;  // 新バッファに置き換える
    p_memsz = target_size;
    print_segment_info( );
    return( ret );
  }
}
