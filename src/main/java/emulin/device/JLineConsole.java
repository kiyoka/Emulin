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
  private volatile boolean lastAvail = false;  // available() 直近の戻り値 (transition log 用)

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
  //
  // issue #55: POSIX read(fd, buf, N) は「kernel buffer の available 分だけ即返す」
  //   semantics。1 byte 以上取れたらすぐ return。line discipline (Enter まで
  //   bufferring) は kernel 側の仕事で、user-space loop で LF まで spin して
  //   待ってはいけない。旧実装は cooked mode で LF まで reader.read() を loop
  //   していたため、ssh の \`read(0, buf, 8192)\` で 1 char 取得後の次の
  //   reader.read() が永久 block (post-auth client_loop で hang)。
  //   修正: 「最初の 1 byte は wait OK、以後は ready() の間だけ fetch、ready
  //   でなくなったら break」。raw mode は従来通り 1 char で break。
  public int read(byte[] buf, emulin.Process proc) {
    boolean debug = System.getenv("EMULIN_DEBUG_TTY") != null;
    int i = 0;
    try {
      while (i < buf.length) {
        // 2 文字目以降は available なときだけ取得 (POSIX 準拠)。
        if (i > 0 && !reader.ready()) break;
        int b = reader.read();
        if (debug) {
          System.err.println("DBG_RD got=0x" + Integer.toHexString(b & 0xff)
            + " rawMode=" + rawMode + " buflen=" + buf.length);
          System.err.flush();
        }
        if (b < 0) return i;
        if (rawMode) {
          // Phase 30 follow-up5 (termios 共有) で bash readline が正常に
          // per-char redisplay (echo) を行うようになったので、emulator 側
          // で auto-echo する必要はなくなった (auto-echo を残すと bash の
          // echo と重複表示される)。
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
      try {
        java.io.OutputStream os = terminal.output();
        os.write(buf, 0, buf.length);
        os.flush();
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
    try {
      boolean r = reader != null && reader.ready();
      if (r != lastAvail) {
        lastAvail = r;
        if (System.getenv("EMULIN_DEBUG_TTY") != null)
          System.err.println("DBG_AVAIL " + r);
      }
      return r;
    } catch (IOException e) {
      if (System.getenv("EMULIN_DEBUG_TTY") != null)
        System.err.println("DBG_AVAIL ioex " + e);
      return false;
    }
  }

  public boolean isRaw() { return rawMode; }

  // Phase 30 follow-up8: terminal が native (= 実 TTY、dumb fallback ではない)
  // か。poll/select で TTY input availability を厳密に確認するかの判定に
  // 使う。dumb terminal (pipe stdin の test 等) では「常に ready」を維持。
  public boolean isNative() {
    return terminal != null && !terminal.getClass().getName().contains("Dumb");
  }

  public void setParameter(int c_lflag, int c_iflag, int c_oflag, byte[] c_cc) {
    boolean wantRaw = (c_lflag & ICANON) == 0;
    boolean debug = System.getenv("EMULIN_DEBUG_TTY") != null;
    if (wantRaw && !rawMode) {
      try {
        savedAttrs = terminal.enterRawMode();
        // issue #3-#5 (e): JLine の default enterRawMode は ISIG を touch しない。
        // ISIG=on のままだと VINTR (0x03 = Ctrl-C) / VQUIT (0x1C) / VSUSP (0x1A)
        // を processInputChar が signal に変換し、byte 0x03 を reader に届けない。
        // emacs C-x C-c 等で C-c が emacs に届かない原因。ISIG=off に明示的に
        // 切り替える (savedAttrs は不変なので exit 時に自動復帰)。
        Attributes a = terminal.getAttributes();
        a.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(a);
        rawMode = true;
        if (debug) System.err.println("DBG_TTY enterRawMode SUCCESS, savedAttrs=" + (savedAttrs != null) + " ISIG=off");
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
