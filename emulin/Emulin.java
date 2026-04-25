// ----------------------------------------
//  Emulin boot up ( main )
//
//  Copyright (C) 1999-2000  Kiyoka Nishiyama
//
//  $Date: 2000/01/23 11:16:23 $ 
//  $Id: Emulin.java,v 1.26 2000/01/23 11:16:23 kiyoka Exp $
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
    boolean do_setup= false;
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
	if( _args[i].equals( "-S" ))  { // Setup
          do_setup = true;
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
	if( _args[i].equals( "-CN" )) { // Native Console
	  sysinfo.set_console_type( Sysinfo.CONSOLE_NATIVE );
	}
	if( _args[i].equals( "-CW" )) { // AWT Window Console
	  sysinfo.set_console_type( Sysinfo.CONSOLE_AWT );
	}
      }
      else {
	arg_index = i;
	break;
      }
    }

    if( do_setup ) {
      setup( );
      System.exit( 0 );
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
    System.out.println( "  switch : -S  ... setup" );
    System.out.println( "           -D  ... debug" );
    System.out.println( "           -V  ... verbose" );
    System.out.println( "           -CN ... Native Console" );
    System.exit( 1 );
  }

  public static void title( ) {
    System.err.println( "Emulin ver " + Version.get_version( ) + " Copyright (C) 1998-2000 Kiyoka Nishiyama" );
    System.err.println( "(java based EMUlation technology for Linux Ia-32 Native application)" );
  }

  public static void setup( ) {
      String curdir  = System.getProperty( "user.dir" );
      String filesep = System.getProperty( "file.separator" );
      String console_sw = "";
      title( );
      while( true ) {
	  int b = -1;
	  System.err.println( " Please select console type" );
	  System.err.println( "   1. Native console (you can interrupt processes)" );
	  System.err.println( "   2. normal console" );
	  try { b = System.in.read( ); }
	  catch ( IOException m ) {  System.err.println( "Can't read from stdin... " ); }
	  if( b == '1' ) {  console_sw = " -CN "; break; }
	  if( b == '2' ) {  console_sw = " ";    break; }
      }
      
      if( filesep.charAt( 0 ) == '/' ) {
	  // UNIX系 OS とみなす
	  System.out.println( "#!/bin/csh" );
	  System.out.println( "setenv CLASSPATH .:.." );
	  System.out.println( "cd " + curdir + "/root"  );
	  System.out.println( "java emulin.Emulin " + curdir + "/root" + console_sw + " /bin/ash /etc/rc" );
	  System.out.println( "cd .." );
      }
      else {
	  // Windows系 OS とみなす
	  System.out.println( "@echo off" );
	  System.out.println( "set CLASSPATH=.;.." );
	  System.out.println( "cd " + curdir + "\\root"  );
	  System.out.println( "java emulin.Emulin " + curdir + "\\root" + console_sw + " /bin/ash /etc/rc" );
	  System.out.println( "cd .." );
      }
  }

  public static void test( ) {
      //      String _list[];
      //      String vpath = "D:\\emulin\\root\\\\. ";
      //      File file = new File( vpath );
      //      _list = file.list( );
      //      System.out.println( "DEBUG:::  file = [" + file + "] _list = " + _list );
  }
}
