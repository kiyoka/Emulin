// ----------------------------------------
//  Console
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:42 $
//  $Id: Console.java,v 1.6 2000/01/13 15:50:42 kiyoka Exp $
//
//  Phase 22 step 3e: NativeConsole (emu_con.c JNI 経由) を撤去し、
//  Std (System.in/out) と JLine の 2 系統のみを扱う。
// ----------------------------------------
package emulin.device;

import emulin.Sysinfo;

public class Console extends StdConsole {

  // -CJ 指定時にのみ生成。Std パスは StdConsole の継承メソッドで処理。
  private JLineConsole jline;

  public Console( Sysinfo _sysinfo ) {
    sysinfo = _sysinfo;
    if( _sysinfo.is_console_jline( )) {
      jline = new JLineConsole( _sysinfo );
      jline.init( );
    }
  }

  public int read( byte buf[], emulin.Process _process ) {
    if( sysinfo.is_console_jline( )) return jline.read( buf, _process );
    return Std_read( buf, _process );
  }

  public int write( byte buf[], boolean stderr_flag ) {
    if( sysinfo.is_console_jline( )) return jline.write( buf, stderr_flag );
    Std_write( buf, stderr_flag );
    return( buf.length );
  }

  public boolean set_parameter( int c_lflag, int c_iflag, int c_oflag, byte c_cc[] ) {
    if( sysinfo.is_console_jline( )) jline.setParameter( c_lflag, c_iflag, c_oflag, c_cc );
    // Std (CONSOLE_NONE) は termios 操作を持たないので no-op
    return( true );
  }

  // 入力バッファのチェック (sys_select 経路から呼ばれる)
  public boolean Available( ) {
    if( sysinfo.is_console_jline( )) return jline.available( );
    return super.Available( );
  }

  // 割り込みのチェック / キャンセル / セット (Ctrl-C 配信用)
  public boolean check_int( ) {
    if( sysinfo.is_console_jline( )) return jline.checkInt( );
    return Std_check_int( );
  }

  public void cancel_int( ) {
    if( sysinfo.is_console_jline( )) { jline.cancelInt( ); return; }
    Std_cancel_int( );
  }

  public void set_int( int sig ) {
    if( sysinfo.is_console_jline( )) { jline.setInt( sig ); return; }
    Std_set_int( sig );
  }

  // Phase 22 step 3c: 端末側で Ctrl-C を捕えたら全プロセスへ SIGINT を送る。
  // Std (CONSOLE_NONE) は check_int が常に false なので no-op。
  public synchronized void check_and_send_int( Sysinfo _sysinfo ) {
    if( check_int( )) {
      cancel_int( );
      _sysinfo.kernel.kill( -1, emulin.Signal.SIGINT );
    }
  }
}
