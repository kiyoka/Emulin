// ----------------------------------------
//  Segment Information in Elf
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
  volatile byte buf[];           /* Segment メモリ。Phase 27 step 52: pthread 環境で expand_memory 中の reallocate race 対策で volatile 化 (本来は pre-allocate で realloc させない方針だが念のため) */

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
    if( buf != null ) {
      _segment.buf           =   new byte[ buf.length ];
      System.arraycopy( buf, 0, _segment.buf, 0, buf.length );
    }
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
    // Linux カーネルはセグメントをページ単位でマップする。p_vaddr/p_offset を
    // ページ境界に揃えて確保することで、隣接セグメントがページを共有する場合も
    // 正しくアクセスできる。
    final long PAGE = 0x1000L;
    long page_base    = p_vaddr & ~(PAGE - 1);          // p_vaddr のページ下端
    long page_offset  = p_vaddr - page_base;             // ページ内オフセット
    long file_base    = p_offset - page_offset;          // ファイル上の対応する先頭
    long filesz_ext   = p_filesz + page_offset;
    long memsz_ext    = p_memsz  + page_offset;
    int  alloc_size   = (int)(((memsz_ext + PAGE - 1) / PAGE) * PAGE);
    buf = new byte[alloc_size];
    try {
      in.seek( file_base );
      in.read( buf, 0, (int)filesz_ext );
    }
    catch ( IOException m ) {  process.println( "Seek Failed : offset " + p_offset ); return( false ); }
    p_vaddr  = page_base;      // バッファはページ先頭から始まる
    p_paddr  = page_base;
    p_memsz  = memsz_ext;
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

  // セグメント中データの読みだし (bytes)。
  // Phase 29-A: 旧実装は per-byte ループで JFR で hot spot 1 位 (ossl 31%)。
  // System.arraycopy (JIT intrinsic) で bulk copy するように変更。
  // segment 末尾を超える場合は clamp、address < p_vaddr (= srcStart < 0) なら
  // 0 を返す (旧実装の immediate break と同じ動作)。
  public int peekbs( long address, byte _buf[] ) {
    int srcStart = (int)(address - p_vaddr);
    int blen = buf.length;
    if( srcStart < 0 || srcStart >= blen ) return 0;
    int copyLen = Math.min( _buf.length, blen - srcStart );
    System.arraycopy( buf, srcStart, _buf, 0, copyLen );
    return copyLen;
  }

  // セグメントへのデータ書き込み (byte)
  public void pokeb( long address, byte data ) {
    buf[(int)(address - p_vaddr)] = data;
  }

  // アドレスがセグメント内かどうか調べる (ページ境界アライメント済みバッファ範囲で判定)
  public boolean in( long address ) {
    // load_body をスキップした (= PT_LOAD 以外の) セグメントは buf == null。
    // Memory 走査でこれらを「該当しない」扱いにする (Phase 24 step 1b 関連)。
    if( buf == null ) return false;
    if( ( p_vaddr <= address ) && ( address < (p_vaddr + buf.length)) ) {
      return( true );
    }
    return( false );
  }

  // p_memsz を直接 set (Elf.java の brk pre-allocate 用)
  public void set_memsz( long size ) {
    p_memsz = size - p_vaddr;
  }

  // メモリサイズをaddress のポイントまで拡張する
  // Phase 27 step 52: pthread 安全のため synchronized + buf を volatile 化
  //   (実用上 pre-allocate で realloc は走らないが、保険として race fix)
  public synchronized boolean expand_memory( long address ) {
    boolean ret = true;
    long target_size = address - p_vaddr;
    final long PAGE = 0x1000L;
    long alloc_size = ((target_size + PAGE - 1) / PAGE) * PAGE; // ページ境界に切り上げ
    if( sysinfo.verbose( ) ) {
      process.println( "expanded_memory( ):target_size = " + Util.hexstr( target_size, 8 ));
      process.println( "expanded_memory( ):p_memsz     = " + Util.hexstr( p_memsz, 8 ));
    }
    // 現在のバッファで足りる場合は再確保しない (縮小も行わない)
    if( alloc_size <= buf.length ) {
      p_memsz = target_size;
      return( ret );
    }
    byte tmp_array[] = new byte[(int)alloc_size];
    // 旧バッファのデータをすべてコピーする
    System.arraycopy( buf, 0, tmp_array, 0, buf.length );
    buf = tmp_array;
    p_memsz = target_size;
    print_segment_info( );
    return( ret );
  }
}
