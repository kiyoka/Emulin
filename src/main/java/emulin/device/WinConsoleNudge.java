// ----------------------------------------
//  Windows console input-buffer nudge (issue #709 緩和)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: Windows Terminal → conpty 段でキーイベントが滞留するハードハング (#709、
//   Ctrl-C も無反応・ウィンドウリサイズで一斉フラッシュして復活) に対する Emulin 側
//   緩和策の注入部。自プロセスのコンソール入力バッファ (CONIN$) へ WriteConsoleInputW
//   で無害な INPUT_RECORD を注入し、conhost の入力配送ループを蹴る (手動リサイズが
//   発生させる WINDOW_BUFFER_SIZE_EVENT の自己版)。watchdog (JLineConsole 側) が
//   「guest が stdin を待っているのに入力が長時間来ない」状態を検出したときのみ呼ぶ。
//
// イベント種別:
//   - FOCUS_EVENT (既定): ドキュメント上「内部使用・アプリは無視すべき」とされる
//     完全に無害なイベント。JLine の pump は focus tracking 無効 (既定) なら捨てる。
//     ReadConsoleInput 待ちを起こす効果だけが残る。
//   - WINDOW_BUFFER_SIZE_EVENT (opt-in): 手動リサイズと同種のイベント。JLine が
//     WINCH signal に変換 → guest に SIGWINCH → TUI が再描画するため「強い」nudge に
//     なるが、定期発火だと再描画チラつきの副作用がある。実機での効果切り分け用。
//
// 設計原則 (WhpBindings と同じ):
//   1. Windows 以外では probe() が os.name チェックで即 false = FFM に一切触れない。
//   2. dll 欠落・console 無し (stdin リダイレクト等)・restricted-method 例外は
//      すべて false に倒し、呼び出し側は watchdog を起動しないだけ。
//   3. probe は 1 度だけ。MethodHandle / CONIN$ handle / INPUT_RECORD buffer は
//      probe 成功時に確保して再利用 (inject は synchronized で直列)。
package emulin.device;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class WinConsoleNudge {

  // Win32 定数
  private static final int GENERIC_READ     = 0x80000000;
  private static final int GENERIC_WRITE    = 0x40000000;
  private static final int FILE_SHARE_READ  = 0x00000001;
  private static final int FILE_SHARE_WRITE = 0x00000002;
  private static final int OPEN_EXISTING    = 3;
  private static final short FOCUS_EVENT              = 0x0010;
  private static final short WINDOW_BUFFER_SIZE_EVENT = 0x0004;

  private static volatile Boolean available;   // null = 未 probe
  private static MethodHandle  mhWriteConsoleInputW;
  private static MemorySegment hConin;         // CONIN$ (GENERIC_READ|WRITE)
  private static MemorySegment rec;            // INPUT_RECORD ×1 (20 bytes、再利用)
  private static MemorySegment writtenOut;     // LPDWORD lpNumberOfEventsWritten

  private WinConsoleNudge() {}

  /** 注入が使えるか。Windows + kernel32 解決 + CONIN$ open 成功で true (1 度だけ probe)。 */
  public static boolean probe() {
    Boolean a = available;
    if( a != null ) return a;
    synchronized( WinConsoleNudge.class ) {
      if( available != null ) return available;
      boolean ok = false;
      try {
        if( System.getProperty( "os.name", "" ).toLowerCase().contains( "win" ) ) {
          Linker       linker = Linker.nativeLinker();
          SymbolLookup k32    = SymbolLookup.libraryLookup( "kernel32", Arena.global() );
          MethodHandle mhCreateFileW = linker.downcallHandle(
              k32.find( "CreateFileW" ).orElseThrow( () -> new UnsatisfiedLinkError( "CreateFileW" ) ),
              FunctionDescriptor.of( ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,   // lpFileName (LPCWSTR)
                  ValueLayout.JAVA_INT,  // dwDesiredAccess
                  ValueLayout.JAVA_INT,  // dwShareMode
                  ValueLayout.ADDRESS,   // lpSecurityAttributes
                  ValueLayout.JAVA_INT,  // dwCreationDisposition
                  ValueLayout.JAVA_INT,  // dwFlagsAndAttributes
                  ValueLayout.ADDRESS ) );  // hTemplateFile
          mhWriteConsoleInputW = linker.downcallHandle(
              k32.find( "WriteConsoleInputW" ).orElseThrow( () -> new UnsatisfiedLinkError( "WriteConsoleInputW" ) ),
              FunctionDescriptor.of( ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,   // hConsoleInput
                  ValueLayout.ADDRESS,   // lpBuffer (INPUT_RECORD*)
                  ValueLayout.JAVA_INT,  // nLength
                  ValueLayout.ADDRESS ) );  // lpNumberOfEventsWritten
          // "CONIN$" (UTF-16LE + NUL)。GetStdHandle(STD_INPUT_HANDLE) は redirect や JLine の
          //   handle 差し替えの影響を受けうるので、常に実 console の入力バッファを名前で開く。
          String conin = "CONIN$";
          MemorySegment name = Arena.global().allocate( ( conin.length() + 1 ) * 2L, 2 );
          for( int i = 0; i < conin.length(); i++ )
            name.set( ValueLayout.JAVA_CHAR, i * 2L, conin.charAt( i ) );
          MemorySegment h = (MemorySegment) mhCreateFileW.invoke( name,
              GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
              MemorySegment.NULL, OPEN_EXISTING, 0, MemorySegment.NULL );
          // INVALID_HANDLE_VALUE = -1、console 無し (サービス/リダイレクト単独) は失敗する
          if( h.address() != 0 && h.address() != -1L ) {
            hConin     = h;
            rec        = Arena.global().allocate( 20, 4 );   // INPUT_RECORD (WORD + pad + union16)
            writtenOut = Arena.global().allocate( 4, 4 );
            ok = true;
          }
        }
      } catch( Throwable t ) {
        ok = false;   // dll 欠落 / --enable-native-access 無し / console 無し は全部「使えない」
      }
      available = ok;
      return ok;
    }
  }

  /** FOCUS_EVENT (bSetFocus=TRUE) を 1 件注入。probe() true が前提。成功で true。 */
  public static synchronized boolean injectFocus() {
    if( available == null || !available ) return false;
    try {
      rec.fill( (byte) 0 );
      rec.set( ValueLayout.JAVA_SHORT, 0, FOCUS_EVENT );
      rec.set( ValueLayout.JAVA_INT,   4, 1 );             // bSetFocus = TRUE
      int r = (int) mhWriteConsoleInputW.invoke( hConin, rec, 1, writtenOut );
      return r != 0 && writtenOut.get( ValueLayout.JAVA_INT, 0 ) == 1;
    } catch( Throwable t ) {
      return false;
    }
  }

  /** WINDOW_BUFFER_SIZE_EVENT (現サイズ) を 1 件注入。SIGWINCH 経由の再描画を伴う強い nudge。 */
  public static synchronized boolean injectWinSize( int cols, int rows ) {
    if( available == null || !available ) return false;
    if( cols <= 0 || rows <= 0 ) { cols = 80; rows = 25; }
    try {
      rec.fill( (byte) 0 );
      rec.set( ValueLayout.JAVA_SHORT, 0, WINDOW_BUFFER_SIZE_EVENT );
      rec.set( ValueLayout.JAVA_SHORT, 4, (short) cols );  // COORD.X
      rec.set( ValueLayout.JAVA_SHORT, 6, (short) rows );  // COORD.Y
      int r = (int) mhWriteConsoleInputW.invoke( hConin, rec, 1, writtenOut );
      return r != 0 && writtenOut.get( ValueLayout.JAVA_INT, 0 ) == 1;
    } catch( Throwable t ) {
      return false;
    }
  }
}
