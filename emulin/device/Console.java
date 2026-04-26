// ----------------------------------------
//  Console
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:42 $ 
//  $Id: Console.java,v 1.6 2000/01/13 15:50:42 kiyoka Exp $
// ----------------------------------------
package emulin.device;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;

public class Console extends NativeConsole {

  public Console( Sysinfo _sysinfo ) {
    sysinfo = _sysinfo;
    Native_init( _sysinfo );
  }

  // コンソールからのリード
  public int read( byte buf[], emulin.Process _process ) {
    int len = 0;
    if( sysinfo.is_console_none( ))    {  len = Std_read(    buf, _process ); }
    if( sysinfo.is_console_native( ))  {  len = Native_read( buf, _process ); }
    return( len );
  }

  // コンソールへのライト
  public int write( byte buf[], boolean stderr_flag ) {
    if( sysinfo.is_console_none( ))    {  Std_write( buf, stderr_flag ); }
    if( sysinfo.is_console_native( ))  {  Std_write( buf, stderr_flag ); }
    return( buf.length );
  }

  // パラメータの変更
  public boolean set_parameter( int c_lflag, int c_iflag, int c_oflag, byte c_cc[] ) {
    if( sysinfo.is_console_native( ))  {  Native_set_parameter( c_lflag, c_iflag, c_oflag, c_cc ); }
    return( true );
  }

  // 割り込みのチェック
  public boolean check_int( ) {
      boolean ret = false;
      if( sysinfo.is_console_none( ))    {  ret = Std_check_int( ); }
      if( sysinfo.is_console_native( ))  {  ret = Native_check_int( ); }
      return( ret );
  }

  // 割り込みのキャンセル
  public void cancel_int( ) {
      if( sysinfo.is_console_none( ))    {  Std_cancel_int( ); }
      if( sysinfo.is_console_native( ))  {  Native_cancel_int( ); }
  }

  // 割り込みが 1回入ったことにする。
  public void set_int( int sig ) {
      if( sysinfo.is_console_none( ))    {  Std_set_int( sig ); }
      if( sysinfo.is_console_native( ))  {  Native_set_int( sig ); }
  }
}
