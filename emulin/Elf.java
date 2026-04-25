// ----------------------------------------
//  Elf ( support ELF format (32bit for i[34]86))
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/11/30 17:23:08 $ 
//  $Id: Elf.java,v 1.24 1999/11/30 17:23:08 kiyoka Exp $
// ----------------------------------------
package emulin;

//
// ELFヘッダ
//
// typedef struct elf32_hdr{
//   EI_NIDENT = 16;
//   char	e_ident[EI_NIDENT];      :  e_ident[0...3] = '\x7f' "ELF" であること
//   Elf32_Half	e_type;                  :  ET_EXEC であること
//   Elf32_Half	e_machine;               :  EM_386 であること (将来 EM_486サポートする)
//   Elf32_Word	e_version;
//   Elf32_Addr	e_entry;  /* Entry point */
//   Elf32_Off	e_phoff;
//   Elf32_Off	e_shoff;
//   Elf32_Word	e_flags;
//   Elf32_Half	e_ehsize;
//   Elf32_Half	e_phentsize;
//   Elf32_Half	e_phnum;
//   Elf32_Half	e_shentsize;
//   Elf32_Half	e_shnum;
//   Elf32_Half	e_shstrndx;
// } Elf32_Ehdr;

// Emulin のセグメントの扱い
//  1. stack
//     アドレス ~0 から 適当なメモリを確保する。
//     ELFのなかに stackセグメントは無いので,Elfクラスで追加生成する。
//  2. それ以外のセグメント
//     セグメントのWRX属性にしたがって,アクセス制限を行う。
//     当然 entry ポイントは X属性のセグメントでなければエラーとする。

import java.lang.*;
import java.io.*;
import emulin.*;

public class Elf
{
  Process process;

  static short ET_NONE = 0;		/* No file type */
  static short ET_REL  = 1;		/* Relocatable file */
  static short ET_EXEC = 2;		/* Executable file */
  static short ET_DYN  = 3;		/* Shared object file */
  static short ET_CORE = 4;		/* Core file */
  static short ET_NUM  = 5;		/* Number of defined types.  */

  /* These constants define the various ELF target machines */
  static short EM_386  = 3;
  static short EM_486  = 6;   /* Perhaps disused */

  byte  e_ident[] = new byte[16];           //   :  e_ident[0...3] = '\x7f' "ELF" であること
  short	e_type;                       
  short	e_machine;
  int e_version;
  int e_entry;              // Entry point
  int e_phoff;
  int e_shoff;
  int e_flags;
  short	e_ehsize;
  short	e_phentsize;
  short	e_phnum;
  short e_shentsize;
  short e_shnum;
  short e_shstrndx;
  String symbol[];
  int symadrs[];
  int symbols;
  Segment[] segment;       // セグメント
  int segments;            // 総セグメント数
  Section[] section;       // セクション
  int sections;            // 総セクション数
  int brk;                 // 現在の brk アドレス
  int brk_segment_no;      // brkの存在する セグメント番号
  Sysinfo sysinfo;


  // 指定インスタンスの情報で自分をアップデートする。
  public void update_info( Elf _elf ) {
    int i;
    System.arraycopy( _elf.e_ident, 0, e_ident, 0, _elf.e_ident.length );
    e_type       = _elf.e_type       ;
    e_machine    = _elf.e_machine    ;
    e_version    = _elf.e_version    ;
    e_entry      = _elf.e_entry      ;
    e_phoff      = _elf.e_phoff      ;
    e_shoff      = _elf.e_shoff      ;
    e_flags      = _elf.e_flags      ;
    e_ehsize     = _elf.e_ehsize     ;
    e_phentsize  = _elf.e_phentsize  ;
    e_phnum      = _elf.e_phnum      ;
    e_shentsize  = _elf.e_shentsize  ;
    e_shnum      = _elf.e_shnum      ;
    e_shstrndx   = _elf.e_shstrndx   ;
    // シンボルのコピー
    symbols      = _elf.symbols      ;
    symbol       = new String[ _elf.symbols ];
    symadrs      = new int[ _elf.symbols ];
    if( sysinfo.debug( )) {
      process.println( "  Elf.update_info : symbols = [ " + symbols + " ] " );
    }
    for( i = 0 ; i < symbols ; i++ ) {
      if( sysinfo.debug( )) {
	process.println( "  Elf.update_info : symbol[ " + i + " ]  = " + _elf.symbol[i] + " " );
      }
      if( null != _elf.symbol[i] ) {
	symbol[i]  = new String( _elf.symbol[i] );
	symadrs[i] = _elf.symadrs[i];
      }
    }
    if( sysinfo.verbose( )) {
      process.println( "  Elf.update_info : Symbol copyied 1" );
    }
    // セグメント
    segments = _elf.segments;
    segment  = new Segment[ segments ];
    for( i = 0 ; i < segments ; i++ ) {
      segment[i] = _elf.segment[i].duplicate( );
    }
    // セクション
    sections = _elf.sections;
    section  = new Section[ sections ];
    for( i = 0 ; i < segments ; i++ ) {
      section[i] = _elf.section[i].duplicate( );
    }
    brk            = _elf.brk;               // 現在の brk アドレス
    brk_segment_no = _elf.brk_segment_no;    // brkの存在する セグメント番号
  }

  public boolean load_symbol( String filename ) {
    String buf = "";
    String adrs_str = "";
    int i;
    RandomAccessFile in;
    try { in = new RandomAccessFile( sysinfo.get_native_path( filename ), "r" ); }
    catch ( IOException m ) {
      if( sysinfo.debug( )) {
	process.println( "Can't file open :" + filename );
      }
      symbols = 0;
      return( false );   
    }
    // 行数の確認
    for( i = 0 ; null != buf ; i++ ) {
      try { buf = in.readLine( ); }
      catch ( IOException m ) {  process.println( "File read error" ); return( false ); }
    }
    symbol  = new String[i];
    symadrs = new int[i];
    // 読み込み
    try{ in.seek( 0 ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }    

    buf = "";
    for( i = 0 ; null != buf ; i++ ) {
      try { buf = in.readLine( ); }
      catch ( IOException m ) {  process.println( "File read error" ); return( false ); }
      if( buf != null ) {
	adrs_str = buf.substring( 0, 8 );
	if( ! adrs_str.equals( "        " )) {
	  symadrs[ i ] = Integer.parseInt( adrs_str, 16);
	  symbol[ i ] = buf.substring( 11 );
	  if( sysinfo.debug( )) {
	    //	    process.println( "adrs = " + Util.hexstr( symadrs[i], 8 ) + "  symbol = " + symbol[i] );
	  }
	}
      }
    }
    symbols = i;
    return( true );
  }

  public String get_symbol( int address ) {
    int i;
    String ret = null;
    for( i = 0 ; i < symbols ; i++ ) {
      if( address == symadrs[i] ) {
	ret = symbol[i];
      }
    }
    return( ret );
  }

  // エントリーアドレスを返す
  public int get_entry( ) {
    return( e_entry );
  }

  // brk値(データセグメントの最後のアドレス)を返す
  public int get_curbrk( ) {
    return( brk );
  }

  // brk値を更新する
  public boolean set_curbrk( int _brk ) {
    if( segment[ brk_segment_no ].expand_memory( _brk )) {
      brk = _brk;
    }
    return( true );
  }

  // ロードする
  public boolean load( String filename ) {
    RandomAccessFile in;
    int i;
    try { in = new RandomAccessFile( sysinfo.get_native_path( filename ), "r" ); }
    catch ( IOException m ) {  process.println( "Can't file open :" + filename );  return( false ); }

    // ヘッダ情報を全てロードする
    LoadUtil.bytes( in, e_ident, sysinfo.kernel );
    e_type        =   LoadUtil.little16( in, sysinfo.kernel );
    e_machine     =   LoadUtil.little16( in, sysinfo.kernel );
    e_version     =   LoadUtil.little32( in, sysinfo.kernel );
    e_entry       =   LoadUtil.little32( in, sysinfo.kernel );
    e_phoff       =   LoadUtil.little32( in, sysinfo.kernel );
    e_shoff       =   LoadUtil.little32( in, sysinfo.kernel );
    e_flags       =   LoadUtil.little32( in, sysinfo.kernel );
    e_ehsize      =   LoadUtil.little16( in, sysinfo.kernel );
    e_phentsize   =   LoadUtil.little16( in, sysinfo.kernel );
    e_phnum       =   LoadUtil.little16( in, sysinfo.kernel );
    e_shentsize   =   LoadUtil.little16( in, sysinfo.kernel );
    e_shnum       =   LoadUtil.little16( in, sysinfo.kernel );
    e_shstrndx    =   LoadUtil.little16( in, sysinfo.kernel );

    if( sysinfo.debug( )) {
      process.println( "File [" + filename + "]" );
      process.println( "----- Elf Header -----" );
      process.println( "e_type        : " + Integer.toString( e_type,         16));
      process.println( "e_machine     : " + Integer.toString( e_machine,      16));
      process.println( "e_version     : " + Integer.toString( e_version,      16));
      process.println( "e_entry       : " + Integer.toString( e_entry,        16));
      process.println( "e_phentsize   : " + Integer.toString( e_phentsize,    16));
      process.println( "e_phnum       : " + Integer.toString( e_phnum,        16));
      process.println( "e_phoff       : " + Integer.toString( e_phoff,        16));
      process.println( "e_shnum       : " + Integer.toString( e_shnum,        16));
      process.println( "e_shoff       : " + Integer.toString( e_shoff,        16));
    }

    // ELFフォーマットかどうか確認する
    if( !
       ((e_ident[0] == 0x7F ) &&
       (e_ident[1] == 'E' ) &&
       (e_ident[2] == 'L' ) &&
       (e_ident[3] == 'F' ))) {
      process.println( "Not Elf Format :" + filename ); return( false );
    }

    if( (e_type != ET_EXEC) ) {
      process.println( "Not Executable Format :" + filename ); return( false );
    }
    if( e_machine != EM_386 ) {
      process.println( "Not Match CPU Type :" + filename ); return( false );
    }

    // プログラムヘッダを読み込む
    try{ in.seek( e_phoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }    

    segments = e_phnum + 1;
    segment = new Segment[ segments ];
    for( i = 0 ; i < segments ; i++ ) {
      segment[i] = new Segment( sysinfo, process );
      if( i < e_phnum ) {
	segment[i].load_ph( in );
      }
      else {
	segment[i].stack( Sysinfo.stack_size );
      }
    }
    // ボディーのロード
    for( i = 0 ; i < e_phnum ; i++ ) {
      segment[i].load_body( in );
    }

    // セクションヘッダを読み込む
    try{ in.seek( e_shoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }    
    sections = e_shnum;
    section = new Section[ sections ];
    for( i = 0 ; i < sections ; i++ ) {
      section[i] = new Section( sysinfo, process );
      section[i].load( in );
      if( section[i].isbss( )) {
	brk = section[i].get_brk( );
      }
    }

    // brkアドレスが含まれるセグメントを探す
    for( i = 0 ; i < segments ; i++ ) {
      if( brk == segment[i].segment_end( )) {
	brk_segment_no = i;
      }
    }

    if( sysinfo.debug( )) {
      process.println( " ----- BRK ----- " );
      process.println( "   brk adrs       = " + Util.hexstr( brk, 8 ));
      process.println( "   brk segment no = " + Util.hexstr( brk_segment_no, 8 ));
    }
    return( true );
  }
}
