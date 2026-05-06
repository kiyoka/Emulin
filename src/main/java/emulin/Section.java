// ----------------------------------------
//  Section Information in Segment
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  $Date: 1999/04/06 17:09:52 $ 
//  $Id: Section.java,v 1.7 1999/04/06 17:09:52 kiyoka Exp $
// ----------------------------------------
package emulin;

//
// ---- Section Header ---
//typedef struct
//{
//  Elf32_Word	sh_name;		/* Section name (string tbl index) */
//  Elf32_Word	sh_type;		/* Section type */
//  Elf32_Word	sh_flags;		/* Section flags */
//  Elf32_Addr	sh_addr;		/* Section virtual addr at execution */
//  Elf32_Off	sh_offset;		/* Section file offset */
//  Elf32_Word	sh_size;		/* Section size in bytes */
//  Elf32_Word	sh_link;		/* Link to another section */
//  Elf32_Word	sh_info;		/* Additional section information */
//  Elf32_Word	sh_addralign;		/* Section alignment */
//  Elf32_Word	sh_entsize;		/* Entry size if section holds table */
//} Elf32_Shdr;
//

import java.lang.*;
import java.io.*;
import emulin.*;

/* セクション情報 */
public class Section {
  static int S_NOBITS = 8;
  String typename[];
  int sh_name       ;
  int sh_type       ;
  long sh_flags     ;
  long sh_addr      ;
  long sh_offset    ;
  long sh_size      ;
  int sh_link       ;
  int sh_info       ;
  long sh_addralign ;
  long sh_entsize   ;
  Sysinfo sysinfo;   /* Processシステム情報 */
  Process process;      /* Process 情報 */

  Section( Sysinfo _sysinfo, Process _process ) {
    typename = new String[13];
    typename[0] = "NULL";
    typename[1] = "PROGBITS";
    typename[2] = "SYMTAB";
    typename[3] = "STRTAB";
    typename[4] = "RELA";
    typename[5] = "HASH";
    typename[6] = "DYNAMIC";
    typename[7] = "NOTE";
    typename[8] = "NOBITS";
    typename[9] = "REL";
    typename[10] = "SHLIB";
    typename[11] = "DYNSYM";
    typename[12] = "NUM";
    sysinfo = _sysinfo;
    process = _process;
  }

  // 自分の複製を返す
  public Section duplicate( ) {
    Section _section       = new Section( sysinfo, process );
    _section.sh_name       = sh_name       ;
    _section.sh_type       = sh_type       ;
    _section.sh_flags      = sh_flags      ;
    _section.sh_addr       = sh_addr       ;
    _section.sh_offset     = sh_offset     ;
    _section.sh_size       = sh_size       ;
    _section.sh_link       = sh_link       ;
    _section.sh_info       = sh_info       ;
    _section.sh_addralign  = sh_addralign  ;
    _section.sh_entsize    = sh_entsize    ;
    return( _section );
  }

  // ELF32 セクションヘッダのロード
  boolean load( RandomAccessFile in ) {
    sh_name       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_type       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_flags      = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    sh_addr       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    sh_offset     = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    sh_size       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    sh_link       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_info       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_addralign  = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    sh_entsize    = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    print_section_info( );
    return( true );
  }

  // ELF64 セクションヘッダのロード (sh_flags/sh_addr/sh_offset/sh_size が 8 バイト)
  boolean load64( RandomAccessFile in ) {
    sh_name       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_type       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_flags      =        LoadUtil.little64( in, sysinfo.kernel );
    sh_addr       =        LoadUtil.little64( in, sysinfo.kernel );
    sh_offset     =        LoadUtil.little64( in, sysinfo.kernel );
    sh_size       =        LoadUtil.little64( in, sysinfo.kernel );
    sh_link       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_info       =        LoadUtil.little32( in, sysinfo.kernel );
    sh_addralign  =        LoadUtil.little64( in, sysinfo.kernel );
    sh_entsize    =        LoadUtil.little64( in, sysinfo.kernel );
    print_section_info( );
    return( true );
  }

  void print_section_info( ) {
    if( sysinfo.debug( )) {
      process.println( "  ----- Section Header -----" );
      process.println( "  sh_name       : " + Integer.toString( sh_name,       16));
      process.println( "  sh_type       : " + Integer.toString( sh_type,       16));
      process.println( "  sh_flags      : " + Long.toString(    sh_flags,      16));
      process.println( "  sh_addr       : " + Long.toString(    sh_addr,       16));
      process.println( "  sh_offset     : " + Long.toString(    sh_offset,     16));
      process.println( "  sh_size       : " + Long.toString(    sh_size,       16));
      process.println( "  sh_link       : " + Integer.toString( sh_link,       16));
      process.println( "  sh_info       : " + Integer.toString( sh_info,       16));
      process.println( "  sh_addralign  : " + Long.toString(    sh_addralign,  16));
      process.println( "  sh_entsize    : " + Long.toString(    sh_entsize,    16));
    }
  }

  boolean isbss( ) {
    return( sh_type == S_NOBITS );
  }

  long get_brk( ) {
    return( sh_addr + sh_size );
  }
}
