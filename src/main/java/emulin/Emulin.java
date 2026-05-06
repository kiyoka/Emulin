// ----------------------------------------
//  Emulin boot up ( main )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;

class Emulin {
  public static void main(String _args[]) {
    Kernel kernel;
    int i, ip;
    byte buf[] = new byte[16];
    boolean disasm  = false;
    boolean dump    = false;
    boolean execute = true;
    int address = 0;
    int size = 0;
    int arg_index = -1;
    Sysinfo sysinfo = new Sysinfo( 0, false );
    
    test( ); // Javaのテスト用

    // スイッチの解析
    for( i = 1 ; i < _args.length ; i++ ) {
      if( '-' == _args[i].charAt( 0 )) {
	if( 'l' == _args[i].charAt( 1 )) {
	  address = Integer.parseInt( _args[i+1], 16 );
	  size    = Integer.parseInt( _args[i+2], 16 );
	  i+=2;
	  disasm  = true;
	  execute = false;
	}
	if( 'd' == _args[i].charAt( 1 )) {
	  address = Integer.parseInt( _args[i+1], 16 );
	  size    = Integer.parseInt( _args[i+2], 16 );
	  i+=2;
	  dump = true;
	  execute = false;
	}
	if( _args[i].equals( "-V" ))  { // Verbose Level 1
	  sysinfo.verbose_set( 1 );
	}
	if( _args[i].equals( "-V2" )) { // Verbose Level 2
	  sysinfo.verbose_set( 2 );
	}
	if( _args[i].equals( "-V3" )) { // Verbose Level 3
	  sysinfo.verbose_set( 3 );
	}
	if( _args[i].equals( "-D" ))  { // Debug mode
	  sysinfo.debug_on( );
	}
	if( _args[i].equals( "-CW" )) { // AWT Window Console
	  sysinfo.set_console_type( Sysinfo.CONSOLE_AWT );
	}
	if( _args[i].equals( "-CJ" )) { // JLine Console (Phase 22 step 3b)
	  sysinfo.set_console_type( Sysinfo.CONSOLE_JLINE );
	}
      }
      else {
	arg_index = i;
	break;
      }
    }

    if( _args.length < 1 ) {
      usage( );
    }

    // カーネルの生成
    kernel = new Kernel( sysinfo );
    sysinfo.kernel = kernel;

    // ルートパスを設定する。
    sysinfo.set_root( _args[0] );

    // emulin.cnf をロードする。
    if( sysinfo.verbose( )) { System.out.println( "load : emulin.cnf" ); }
    sysinfo.load_config( "/etc/emulin.cnf" );

    if( arg_index < 0 ) {
      usage( );
    }
    
    // 引数リストの生成
    int len       = _args.length - arg_index;
    String args[] = new String[ len ];
    for( i = 0 ; i < len ; i++ ) {
      args[i] = _args[i + arg_index];
    }

    // カーネルの実行
    title( );
    kernel.boot( args, System.getProperty( "user.dir" ));
    kernel.start( );
  }
  
  public static void usage( ) {
    title( );
    System.out.println( "  usage  : emulin.Emulin <rootpath> [switch] <elfbin>" );
    System.out.println( "  switch : -D  ... debug" );
    System.out.println( "           -V  ... verbose" );
    System.out.println( "           -CJ ... JLine Console (raw / line editing)" );
    System.exit( 1 );
  }

  public static void title( ) {
    System.err.println( "Emulin ver " + Version.get_version( ) + " Copyright (C) 1998-2026 Kiyoka Nishiyama" );
    System.err.println( "(java based EMUlation technology for Linux IA-32 / x86-64 Native application)" );
  }

  public static void test( ) {
      //      String _list[];
      //      String vpath = "D:\\emulin\\root\\\\. ";
      //      File file = new File( vpath );
      //      _list = file.list( );
      //      System.out.println( "DEBUG:::  file = [" + file + "] _list = " + _list );
  }
}
