// ----------------------------------------
//  Standard Console ( using OS stdin,out )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
    // host stdin が非 tty (redirected file / pipe) なら raw に bulk read する。
    //   下の canonical 経路 (CR 破棄 + 0x0A で打ち切り) は tty 前提の行編集で、
    //   非 tty に適用すると binary stdin の CR(0x0d) を落とし (md5sum/base64/od < file が
    //   1 byte 欠落)、行単位でしか読まないため read(2) セマンティクスも壊す。実 Linux は
    //   redirected/pipe を非 tty として raw に渡すので、それに合わせる。
    if( !sysinfo.host_std_is_tty( 0 ) ) {
      int n = -1;
      try { n = System.in.read( buf, 0, buf.length ); }
      catch ( IOException m ) { return( 0 ); }
      return( n < 0 ? 0 : n );   // EOF は read(2) の 0
    }
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
