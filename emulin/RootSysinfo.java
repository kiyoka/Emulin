// ----------------------------------------
//  System Information
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:28 $ 
//  $Id: RootSysinfo.java,v 1.33 2000/01/13 15:50:28 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;
import emulin.device.*;

public class RootSysinfo {
  int     _verbose_level;
  boolean _debug;
  public static int CONSOLE_NONE   = 0;
  public static int CONSOLE_NATIVE = 1;
  public static int CONSOLE_AWT    = 2;
  static int stack_bottom = 0x70000000;
  static int stack_size = 0x10000;
  static int heap_size  = 0x10000;
  static int block_size = 0x1000;  // Intel Linux のblockサイズ(固定)
  static int default_uid = 501;
  static int default_gid = 100;
  int console_type; // コンソールの実現方法 ( CONSOLE_XXX  )
  public int console_buf;  // コンソールバッファ
  public Kernel kernel;    // カーネルへ参照
  boolean cache_flag;   // キャッシュを使用するかどうか

  RootSysinfo( ) { console_buf = 0; cache_flag = true; }
  public boolean verbose( )                    { if( _verbose_level > 0 ) { return( true ); }  return( false ); }
  public void verbose_set( int level )         { _verbose_level = level; }
  public int verbose_level( )                  { return( _verbose_level ); }
  public boolean debug( )                      { return( _debug ); }
  public void debug_on( )                      { _debug = true; }
  public int get_block_size( )                 { return( block_size ); }
  public int file_uid( )                       { return( default_uid ); }
  public int file_gid( )                       { return( default_gid ); }
  public int get_default_uid( )                { return( default_uid ); }
  public int get_default_gid( )                { return( default_gid ); }
  public void set_console_type( int type_no )  { console_type = type_no; }
  public int get_console_type( )               { return( console_type ); }
  public void cache_set( boolean _cache_flag ) { cache_flag = _cache_flag; }
  public boolean cache( )                      { return( cache_flag ); }
  public int get_stack_bottom( )               { return( stack_bottom ); }
  public boolean is_console_none( )            { return( CONSOLE_NONE   == console_type ); }
  public boolean is_console_native( )          { return( CONSOLE_NATIVE == console_type ); }
  public boolean is_console_awt( )             { return( CONSOLE_AWT    == console_type ); }
}
