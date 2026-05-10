// ----------------------------------------
//  Console
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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

  // raw mode (ICANON off) かどうか。Phase 30 follow-up8 で poll/select
  // の TTY input availability check の分岐に使う。
  public boolean is_raw( ) {
    if( sysinfo.is_console_jline( )) return jline.isRaw( );
    return false;
  }

  // native terminal (= 実 TTY 経由、dumb fallback ではない) か。
  public boolean is_native_tty( ) {
    if( sysinfo.is_console_jline( )) return jline.isNative( );
    return false;
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

  // Phase 22 step 3d: 端末リサイズで全プロセスに SIGWINCH を配信する。
  // Std パスは WINCH を生成しないので何もしない。
  public synchronized void check_and_send_winch( Sysinfo _sysinfo ) {
    if( jline != null && jline.checkWinch( )) {
      jline.cancelWinch( );
      _sysinfo.kernel.kill( -1, emulin.Signal.SIGWINCH );
    }
  }

  // 端末サイズ。JLine が使えるならそこから取得。0 なら呼び出し側で
  // 25x80 等にフォールバックする想定。
  public int getColumns( ) { return jline != null ? jline.getColumns( ) : 0; }
  public int getRows( )    { return jline != null ? jline.getRows( )    : 0; }
}
