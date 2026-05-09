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
import org.jline.utils.Signals;

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
  private volatile boolean pendingWinch = false;
  // Phase 30 follow-up7: bash readline は Enter 押下時に line 全体を
  // batch redisplay する。emulator の auto-echo (per-char) と重複して
  // 同じ行が 2 回表示される。auto-echo した bytes を覚えておき、bash の
  // 直後の write がそれと一致したら suppress する。
  private final java.io.ByteArrayOutputStream echoedBuf = new java.io.ByteArrayOutputStream();
  private boolean suppressNextBatch = false;

  public JLineConsole(Sysinfo _sysinfo) { this.sysinfo = _sysinfo; }

  public void init() {
    try {
      // EMULIN_FORCE_NATIVE_TTY=1 で dumb fallback を禁止し、native
      // (jni-jansi-jna) のみ許可する。Windows で raw mode が効かない
      // 場合に「dumb fallback したのか」「native が壊れているのか」を
      // 切り分けるための診断フラグ。
      boolean force_native = System.getenv("EMULIN_FORCE_NATIVE_TTY") != null;
      TerminalBuilder b = TerminalBuilder.builder().system(true);
      if (!force_native) b = b.dumb(true);
      terminal = b.build();
      reader = terminal.reader();
      // EMULIN_DEBUG_TTY=1 で JLine が選んだ terminal type を表示。
      // Windows cmd.exe で raw mode が効かない場合の診断用。
      // jline-terminal-jni が classpath に無いと DumbTerminal が選ばれて
      // 1 文字単位の入力ができない。
      if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
        String type = terminal.getClass().getName();
        boolean native_term = !type.contains("Dumb");
        System.err.println("DBG_TTY terminal=" + type
          + " native=" + native_term
          + " size=" + terminal.getSize().getColumns() + "x" + terminal.getSize().getRows());
      }
      // Ctrl-C を SIGINT として記録。実際の配信は呼び出し側 (Process)
      // の check_int 経路から拾う (step 3c で結線済)。
      terminal.handle(Terminal.Signal.INT,   sig -> { pendingInt = Signal.SIGINT; });
      // 端末リサイズで SIGWINCH を全プロセスに送る (step 3d)。
      terminal.handle(Terminal.Signal.WINCH, sig -> { pendingWinch = true; });
      // Windows cmd.exe では terminal.handle だけでは Ctrl-C 時に JVM
      // 既定ハンドラが先に走って落ちるケースがあるので、JVM レベルでも
      // 直接 SIGINT を握って pendingInt にだけ落とす保険を入れる。
      Signals.register("INT", () -> { pendingInt = Signal.SIGINT; });
      // JVM 終了時に raw を解除して端末を戻す保険。
      Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    } catch (IOException e) {
      throw new RuntimeException("JLine console init failed: " + e.getMessage(), e);
    }
  }

  // 読み込み: cooked では CR を捨て LF で 1 行打ち切り、raw では 1 文字ずつ返す。
  // Std_read のセマンティクスに合わせる (System.in を JLine の reader に置換した形)。
  public int read(byte[] buf, emulin.Process proc) {
    boolean debug = System.getenv("EMULIN_DEBUG_TTY") != null;
    // Phase 30 follow-up6: bash readline の per-char redisplay (echo)
    // が Windows 上で動かない (なぜか write を呼ばない、調査で原因不明)
    // ため、emulator 側で raw mode 時に printable ASCII を即時 echo する。
    // bash readline が echo しない場合でも user が入力中のキーが見える。
    // bash が ECHO bit を on にしている cooked mode では JLine の raw
    // path を通らないので影響なし。control 系 (Tab/Esc/arrow/BS) は
    // bash が独自に処理するので emulator は echo しない。
    boolean disable_echo = System.getenv("EMULIN_NO_RAW_ECHO") != null;
    int i = 0;
    try {
      while (i < buf.length) {
        int b = reader.read();
        if (debug) {
          System.err.println("DBG_RD got=0x" + Integer.toHexString(b & 0xff)
            + " rawMode=" + rawMode + " buflen=" + buf.length);
          System.err.flush();
        }
        if (b < 0) return i;
        if (rawMode) {
          if (!disable_echo && b >= 0x20 && b < 0x7f && terminal != null) {
            try {
              java.io.OutputStream os = terminal.output();
              os.write(b);
              os.flush();
              echoedBuf.write(b);
            } catch (IOException ignore) {}
          }
          // CR (Enter) を読んだら、次の bash batch redisplay を suppress
          // 候補に。bash が echo した line content が直前の auto-echo と
          // 一致すれば skip する。
          if (b == 0x0D || b == 0x0A) {
            suppressNextBatch = echoedBuf.size() > 0;
          }
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
    boolean debug = System.getenv("EMULIN_DEBUG_TTY") != null;
    if (debug) {
      StringBuilder sb = new StringBuilder("DBG_WR ");
      sb.append(stderr_flag ? "err" : "out").append(" len=").append(buf.length);
      sb.append(" rawMode=").append(rawMode);
      sb.append(" hex=");
      for (int i = 0; i < Math.min(buf.length, 32); i++) {
        sb.append(String.format("%02x", buf[i] & 0xff));
      }
      System.err.println(sb.toString());
      System.err.flush();
    }
    // Phase 30 follow-up3+4: native terminal (jline-terminal-jni) で raw
    // mode 中は JLine が console handle を握っており Java の System.out/err
    // 経由で書いても画面に届かない。bash readline は fd 2 (stderr) に echo
    // するため raw mode 中は stderr も terminal.output() に流す。
    // cooked mode (回帰テストの dumb terminal 含む) は従来 System.out/err
    // で動作させて grep/sed の stderr 期待値を壊さないようにする。
    if (terminal != null && rawMode) {
      // Phase 30 follow-up7: auto-echo と bash の batch redisplay の
      // 重複防止。CR/LF 直後に bash が auto-echo と一致する line を
      // 書いてきたら skip。
      int off = 0;
      int len = buf.length;
      if (suppressNextBatch && stderr_flag) {
        byte[] expected = echoedBuf.toByteArray();
        if (len >= expected.length) {
          boolean match = true;
          for (int k = 0; k < expected.length; k++) {
            if (buf[k] != expected[k]) { match = false; break; }
          }
          if (match) {
            off = expected.length;
            len -= expected.length;
            if (debug) System.err.println("DBG_WR suppressed " + expected.length + " bytes (batch echo)");
          }
        }
        suppressNextBatch = false;
        echoedBuf.reset();
      }
      try {
        java.io.OutputStream os = terminal.output();
        if (len > 0) {
          os.write(buf, off, len);
          os.flush();
        }
        return buf.length;
      } catch (java.io.IOException e) {
        if (debug) System.err.println("DBG_WR terminal.output FAILED: " + e);
      }
    }
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
    boolean debug = System.getenv("EMULIN_DEBUG_TTY") != null;
    if (wantRaw && !rawMode) {
      try {
        savedAttrs = terminal.enterRawMode();
        rawMode = true;
        if (debug) System.err.println("DBG_TTY enterRawMode SUCCESS, savedAttrs=" + (savedAttrs != null));
      } catch (Exception e) {
        if (debug) System.err.println("DBG_TTY enterRawMode FAILED: " + e.getClass().getName() + " " + e.getMessage());
      }
    } else if (!wantRaw && rawMode) {
      try { if (savedAttrs != null) terminal.setAttributes(savedAttrs); }
      catch (Exception ignore) {}
      rawMode = false;
      if (debug) System.err.println("DBG_TTY exitRawMode");
    }
  }

  public boolean checkInt() { return pendingInt != -1; }
  public void cancelInt()   { pendingInt = -1; }
  public void setInt(int sig) { pendingInt = sig; }

  public boolean checkWinch() { return pendingWinch; }
  public void cancelWinch()   { pendingWinch = false; }
  public void setWinch()      { pendingWinch = true; }

  // 端末サイズ取得。dumb terminal や非 tty では 0 を返すので、
  // 呼び出し側でフォールバック (25x80 等) する想定。
  public int getColumns() {
    try { return terminal != null ? terminal.getSize().getColumns() : 0; }
    catch (Exception e) { return 0; }
  }
  public int getRows() {
    try { return terminal != null ? terminal.getSize().getRows() : 0; }
    catch (Exception e) { return 0; }
  }

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
