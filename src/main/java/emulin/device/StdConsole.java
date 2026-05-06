// ----------------------------------------
//  Standard Console ( using OS stdin,out )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:53 $ 
//  $Id: StdConsole.java,v 1.6 2000/01/13 15:50:53 kiyoka Exp $
// ----------------------------------------
package emulin.device;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;

public class StdConsole {
  Sysinfo sysinfo;

  // OSの stdinからの読み込み
  int Std_read( byte buf[], emulin.Process _process ) {
    int i;
    int b = -1;
    for( i = 0 ; i < buf.length ; ) {
      try { b = System.in.read( ); }
      catch ( IOException m ) {  System.out.println( "Can't read from stdin... " ); }
      if( -1 == b ) { return( i ); }
      else {
	if( 0xD != b ) {
	  buf[i] = (byte)b; i++;
	}
      }
      if( 0xA == b ) { break; }  // 改行で処理を終りにする。
    }
    return( i );
  }

  // 入力がたまっているかどうか調べる。
  public boolean Available( ) {
    int len = 0;
    try { len = System.in.available( ); }
    catch( IOException m ) { len = 0; }
    return( len > 0 );
  }

  // OSのstdoutへの書き込み
  int Std_write( byte buf[], boolean stderr_flag ) {
    int i;
    if( sysinfo.verbose( )) {
      if( stderr_flag ) {  System.out.print( "STDERR:" ); }
      else              {  System.out.print( "STDOUT:" ); }
    }
    for( i = 0 ; i < buf.length ; i++ ) {
      if( stderr_flag ) { System.err.write( (int)buf[i] & 0xFF ); }
      else              { System.out.write( (int)buf[i] & 0xFF ); }
    }
    if( stderr_flag)    { System.err.flush( ); }
    else                { System.out.flush( ); }
    return( buf.length );
  }

  // 割り込みのチェック
  boolean Std_check_int( ) {
      return( false );
  }

  // 割り込みのキャンセル
  public void Std_cancel_int( ) {
  }

  // 割り込みが 1回入ったことにする。
  public void Std_set_int( int sig ) {
  }
}
