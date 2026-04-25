// ----------------------------------------
//  Native Console ( using JNI )
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:47 $ 
//  $Id: NativeConsole.java,v 1.10 2000/01/13 15:50:47 kiyoka Exp $
// ----------------------------------------
package emulin.device;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;
import emulin.RootSysinfo.*;
import emulin.Sysinfo.*;
import emulin.Signal.*;

public class NativeConsole extends StdConsole {
  static int IGNBRK	= 0x0000001;
  static int BRKINT	= 0x0000002;
  static int IGNPAR	= 0x0000004;
  static int PARMRK	= 0x0000010;
  static int INPCK	= 0x0000020;
  static int ISTRIP	= 0x0000040;
  static int INLCR	= 0x0000100;
  static int IGNCR	= 0x0000200;
  static int ICRNL	= 0x0000400;
  static int IUCLC	= 0x0001000;
  static int IXON	= 0x0002000;
  static int IXANY	= 0x0004000;
  static int IXOFF	= 0x0010000;
  static int IMAXBEL	= 0x0020000;

  static int VMIN  = 6;
  static int VTIME = 5;
  int vmin;
  int vtime;
  int byte_buf;
  int iflag;
  int oflag;
  boolean int_flag;

  public void Native_init( Sysinfo _sysinfo ) {
    int_flag = false;
    vmin  = 0;
    vtime = 0;
    byte_buf = 0;
    iflag = ICRNL | INLCR;
    oflag = 0;
    if( _sysinfo.CONSOLE_NATIVE == _sysinfo.get_console_type( )) {
      System.out.println( "Info:native console library installed..." );
      System.loadLibrary("emu_con");
      native_init( );
    }
  }

  // Native
  public native int native_init( );
  public static native int native_read( );
  public native int native_set_parameter( int c_lflag, byte c_cc );
  public native int native_israw( );
  public native int native_check_int( );
  public native int native_cancel_int( );
  public native int native_set_int( int sig );

  // コンソールからの読み込み
  public int Native_read( byte buf[], emulin.Process _process ) {
    int i;
    int b = -1;
    int raw = native_israw( );
    if( false ) {
      System.out.println(  "native_israw = " + raw );
      System.out.println(  "vmin  = " + vmin );
      System.out.println(  "vtime = " + vtime );
    }
    for( i = 0 ; i < buf.length ; ) {
      int sig;
      //      System.out.println( " iflag = " + Util.hexstr( iflag, 8 ));
      do {
	try { Thread.sleep( 20L ); }
	catch( InterruptedException m ) { };
	Thread.yield( );

	_int_check_and_send( _process.sysinfo );

	// signal を受けた場合はいったん中断する。
	sig = _process.psig( );
	// System.out.println( "deb(1) sig=(" + sig + ")" );
	if( -1 != sig ) { return( i ); }
	// System.out.println( "deb(2) sig=(" + sig + ")" );

      } while( sysinfo.console_buf == 0 );

      b = sysinfo.console_buf;  if( 0xA == b ) { b = 0xD; } // 変換する
      sysinfo.console_buf = 0;

      // System.out.println( "[" + Util.hexstr( b, 8 ) + "]" );
      if( -1 == b ) { return( i ); }
      else {

        if( 0x4 == b ) {
	}
	else {
	  if( 0 != (iflag & IGNCR) ) {  if( 0xA == b ) { b = -1;  }}
	  if( 0 != (iflag & ICRNL) ) {  if( 0xA == b ) { b = 0xD; }}
	  if( 0 != (iflag & INLCR) ) {  if( 0xD == b ) { b = 0xA; }}
	  if( b >= 0 ) {
	    buf[i] = (byte)b; i++;
	  }
	}
      }
      if( b >= 0 ) {
	if( 0 != raw ) { break; }
	if( 0xA == b ) { break; }  // 改行で処理を終りにする。
	if( 0x4 == b ) { break; }  // EOFで処理を終りにする。
      }
    }
    return( i );
  }

  // 入力がたまっているかどうか調べる。
  public boolean Available( ) {
    return( sysinfo.console_buf > 0 );
  }

  // 入力があれば,返す
  public int _byte_read( Sysinfo _sysinfo ) {
      if( _sysinfo.console_buf == 0 ) {
	  _sysinfo.console_buf = native_read( );
      }
      return( _sysinfo.console_buf );
  }

  // 割り込みをチェックして,シグナルを入れる
  public synchronized void _int_check_and_send( Sysinfo _sysinfo ) {
      if( Native_check_int( )) { // STDINを持つプロセスにSIGINTを送る
	  Native_cancel_int( );
	  _sysinfo.kernel.kill( -1, Signal.SIGINT );
      }
      if( _sysinfo.verbose( )) {
	  _sysinfo.kernel.println( "[init process]:check_int( ) " );
      }
  }

  // パラメータの設定
  public void Native_set_parameter( int c_lflag, int c_iflag, int c_oflag, byte c_cc[] ) {
    vmin  = c_cc[VMIN];
    vtime = c_cc[VTIME];
    iflag = c_iflag;
    oflag = c_oflag;
    native_set_parameter( c_lflag, c_cc[VMIN] );
  }

  // 割り込みのチェック
  public boolean Native_check_int( ) {
      if( 0 < native_check_int( )) {
	  return( true );
      }
      return( false );
  }

  // 割り込みのキャンセル
  public void Native_cancel_int( ) {
      native_cancel_int( );
  }

  // 割り込みが 1回入ったことにする。
  public void Native_set_int( int sig ) {
      // System.out.println( "Native_set_int( )" );
      native_set_int( sig );
  }
}
