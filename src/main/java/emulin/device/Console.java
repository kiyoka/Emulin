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

  // issue #432: TTY/console の read(drain) 世代。read で >=1 byte 取得する度に +1。
  //   crossterm(codex) は stdin を EPOLLET (edge-triggered) で登録し「1 打鍵 = 1 edge」を
  //   期待する。amd64_epoll_wait の EPOLLET 判定で「前回報告以降に read(drain) が
  //   あったか」をこの世代で見て edge を再 arm する (#428 の eventfd 世代と同型)。
  //   旧 level 判定 (readable が継続している間 latch を 1 に保持) は JLine の
  //   availablePeek 遅延等で latch が張り付くと 2 文字目以降の edge を出せず、
  //   連続打鍵が 1 文字で固まる問題があった。
  public volatile long readGen = 0;

  public int read( byte buf[], emulin.Process _process ) {
    int n = sysinfo.is_console_jline( ) ? jline.read( buf, _process ) : Std_read( buf, _process );
    if( n > 0 ) readGen++;
    return n;
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

  // issue #131 (layer 17): TTY input の能動 probe (peek)。Available() が false の
  //   ときの fallback (forked tmux server の入力経路で reader.ready() が
  //   buffer 空のまま false 固定になる問題)。JLineConsole.availablePeek() 参照。
  public boolean availablePeek( ) {
    if( sysinfo.is_console_jline( )) return jline.availablePeek( );
    return super.Available( );
  }

  // issue #206: poll/pselect の待機を「TTY 入力到着で即復帰する blocking peek」に
  //   する。reader.peek(ms) は最大 ms ブロックして stream を probe し入力到着で
  //   即返る。busy-sleep のポーリング遅延を排除する。非 JLine console は peek
  //   不可なので短い sleep + Available() で代替。
  public boolean peekWait( int ms ) {
    if( sysinfo.is_console_jline( )) return jline.peekWait( ms );
    try { Thread.sleep( Math.max( 1, Math.min( ms, 10 ) ) ); } catch( InterruptedException e ) {}
    return super.Available( );
  }

  // issue #413: console→pty-master bridge 稼働中は bridge thread が reader を排他所有する。
  //   true の間 available/availablePeek/peekWait は reader 非接触になり、エスケープ列の競合分断を防ぐ。
  public void setBridgeMode( boolean b ) {
    if( sysinfo.is_console_jline( ) && jline != null ) jline.bridgeMode = b;
  }

  // issue #413: bridge thread 専用 read (エスケープ列 coalesce)。非 JLine は通常 read。
  public int bridgeRead( byte[] buf ) {
    if( sysinfo.is_console_jline( ) ) return jline.bridgeRead( buf );
    return Std_read( buf, null );
  }

  // issue #72: emulin 終了直前に console 出力を drain する。Windows native
  // terminal で最後の write がレンダリング前に JVM 終了して消えるのを防ぐ。
  public void flush( ) {
    if( sysinfo.is_console_jline( )) { jline.flush( ); return; }
    try { System.out.flush(); } catch( Exception ignore ) {}
    try { System.err.flush(); } catch( Exception ignore ) {}
  }
  public void close( ) {
    if( sysinfo.is_console_jline( )) jline.close( );
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

  // Phase 22 step 3c: 端末側で Ctrl-C を捕えたら SIGINT を送る。
  // Std (CONSOLE_NONE) は check_int が常に false なので no-op。
  // Phase 33-17 → issue #3-#2 → issue #3-#3 で再修正:
  //   旧 (33-17): SIGINT 配信を完全停止し byte 0x03 経路のみに委ねていた。
  //     → bash + git clone (network read で blocking) で Ctrl-C 中断不可 (#3-#2)。
  //   issue #3-#2: bash の child = non-shell (git/curl) なら SIGINT 配信。
  //   issue #3-#3 追加: emacs / vim 等 raw mode interactive editor は
  //     Ctrl-C を「バイト 0x03 として受信して処理」する設計 (C-x C-c で quit、
  //     keyboard-quit 等)。raw mode 中は SIGINT 配信せず byte 0x03 経路に
  //     委ねる必要がある。
  //   分岐:
  //     1) raw mode (= isRaw): 全て byte 0x03 経路 → SIGINT 配信 skip
  //        (bash readline / vim insert / emacs はこれで動作)
  //     2) cooked mode + foreground = shell: byte 0x03 経路 (33-17 と同じ)
  //     3) cooked mode + foreground = non-shell: SIGINT 配信 (#3-#2)
  public synchronized void check_and_send_int( Sysinfo _sysinfo ) {
    if( check_int( )) {
      cancel_int( );
      // raw mode 中は emacs/vim/bash readline 等が byte 0x03 を直接処理。
      // SIGINT 配信は skip (emacs C-x C-c の C-c byte を奪わない)。
      if( jline != null && jline.isRaw() ) return;
      int target = _sysinfo.kernel.find_foreground_child_pid();
      if( target > 0 ) {
        _sysinfo.kernel.kill( target, emulin.Signal.SIGINT );
      }
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
