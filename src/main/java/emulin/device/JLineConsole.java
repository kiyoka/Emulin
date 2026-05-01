// ----------------------------------------
//  JLine-backed Console
//
//  Phase 22 step 3b: NativeConsole (emu_con.c JNI 経由) の置き換え。
//  cooked モード / raw モードの両方を JLine で扱う。
//  非 tty 環境では JLine が dumb terminal にフォールバックするので、
//  既存の cooked パイプテストはそのまま通る。
// ----------------------------------------
package emulin.device;

import java.io.IOException;
import java.io.PrintStream;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import emulin.Signal;
import emulin.Sysinfo;

public class JLineConsole {
  // termios c_lflag (Linux)
  private static final int ICANON = 0x00002;

  private final Sysinfo sysinfo;
  private Terminal terminal;
  private NonBlockingReader reader;
  private Attributes savedAttrs;
  private boolean rawMode = false;
  private volatile int pendingInt = -1;

  public JLineConsole(Sysinfo _sysinfo) { this.sysinfo = _sysinfo; }

  public void init() {
    try {
      terminal = TerminalBuilder.builder()
          .system(true)
          .dumb(true)
          .build();
      reader = terminal.reader();
      // Ctrl-C を SIGINT として記録。実際の配信は呼び出し側 (Process)
      // の check_int 経路から拾う想定 (step 3c で結線)。
      terminal.handle(Terminal.Signal.INT, sig -> { pendingInt = Signal.SIGINT; });
      // JVM 終了時に raw を解除して端末を戻す保険。
      Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    } catch (IOException e) {
      throw new RuntimeException("JLine console init failed: " + e.getMessage(), e);
    }
  }

  // 読み込み: cooked では CR を捨て LF で 1 行打ち切り、raw では 1 文字ずつ返す。
  // Std_read のセマンティクスに合わせる (System.in を JLine の reader に置換した形)。
  public int read(byte[] buf, emulin.Process proc) {
    int i = 0;
    try {
      while (i < buf.length) {
        int b = reader.read();
        if (b < 0) return i;
        if (rawMode) {
          buf[i++] = (byte)b;
          break;
        }
        if (b == 0xD) continue;
        buf[i++] = (byte)b;
        if (b == 0xA) break;
      }
    } catch (IOException e) {
      return i;
    }
    return i;
  }

  public int write(byte[] buf, boolean stderr_flag) {
    PrintStream out = stderr_flag ? System.err : System.out;
    out.write(buf, 0, buf.length);
    out.flush();
    return buf.length;
  }

  public boolean available() {
    try { return reader != null && reader.ready(); }
    catch (IOException e) { return false; }
  }

  public void setParameter(int c_lflag, int c_iflag, int c_oflag, byte[] c_cc) {
    boolean wantRaw = (c_lflag & ICANON) == 0;
    if (wantRaw && !rawMode) {
      try { savedAttrs = terminal.enterRawMode(); rawMode = true; }
      catch (Exception ignore) { /* dumb terminal では no-op */ }
    } else if (!wantRaw && rawMode) {
      try { if (savedAttrs != null) terminal.setAttributes(savedAttrs); }
      catch (Exception ignore) {}
      rawMode = false;
    }
  }

  public boolean checkInt() { return pendingInt != -1; }
  public void cancelInt()   { pendingInt = -1; }
  public void setInt(int sig) { pendingInt = sig; }

  public void close() {
    try {
      if (rawMode && savedAttrs != null && terminal != null) {
        terminal.setAttributes(savedAttrs);
        rawMode = false;
      }
      if (terminal != null) terminal.close();
    } catch (Exception ignore) {}
  }
}
