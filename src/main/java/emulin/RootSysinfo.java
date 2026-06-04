// ----------------------------------------
//  System Information
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
  // CONSOLE_NATIVE (1) は Phase 22 step 3e で撤去 (emu_con.c JNI を廃止)。
  // 値は欠番のまま。AWT (2) は実装が空のまま温存。
  public static int CONSOLE_AWT    = 2;
  public static int CONSOLE_JLINE  = 3;
  static long stack_bottom     = 0x70000000L;
  static long stack_bottom_64  = 0x7fff_0000_0000L;  // x86-64 ユーザ空間スタック底
  static int stack_size = 0x100000;  // 1 MiB (busybox 等 glibc 経由は深いスタックを使う)
  static int heap_size  = 0x10000;
  static int block_size = 0x1000;  // Intel Linux のblockサイズ(固定)
  // issue #41 (sshd): emulin の sandbox は単一ユーザ root として動かす方が
  //   sshd / git / chown 等の "owned by root" 系チェックと整合する。
  //   getuid()/geteuid() は 0、stat の st_uid/st_gid もこの値で揃う。
  //   従来の 501/100 では process は uid=501 なのに bash の PS1 は # を出す
  //   不整合や、sshd の strict 検査で abort する経路があった。
  // issue #191 (mozc): EMULIN_UID / EMULIN_GID が設定されていれば初期 process の
  //   uid/gid をその値にする (未設定は従来通り 0 = root)。mozc_server のように
  //   「root では起動拒否」する daemon を非 root で動かすため。getuid()/geteuid()/
  //   stat の st_uid もこの値で揃う。未設定時は 0 なので既存挙動・回帰に影響しない。
  static int default_uid = _env_int( "EMULIN_UID", 0 );
  static int default_gid = _env_int( "EMULIN_GID", 0 );
  private static int _env_int( String name, int dflt ) {
    String v = System.getenv( name );
    if( v == null || v.isEmpty( ) ) return dflt;
    try { return Integer.parseInt( v.trim( ) ); }
    catch( NumberFormatException e ) { return dflt; }
  }
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
  public long get_stack_bottom( )              { return( stack_bottom ); }
  public long get_stack_bottom_64( )          { return( stack_bottom_64 ); }
  public boolean is_console_none( )            { return( CONSOLE_NONE   == console_type ); }
  public boolean is_console_awt( )             { return( CONSOLE_AWT    == console_type ); }
  public boolean is_console_jline( )           { return( CONSOLE_JLINE  == console_type ); }
}
