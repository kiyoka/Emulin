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
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

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
  // issue #413: console→pty-master bridge が稼働中は bridge thread が reader を排他所有する。
  //   main thread の available/availablePeek/peekWait が reader.ready()/peek() で並行アクセス
  //   すると ESC[A 等のエスケープ列が分断・脱落する (claude(Bun) で矢印が ESC だけになる)。
  //   bridgeMode=true の間は main thread 側を reader 非接触にして bridge thread に一本化する。
  public volatile boolean bridgeMode = false;
  private volatile int pendingInt = -1;
  private volatile boolean pendingWinch = false;
  private volatile boolean lastAvail = false;  // available() 直近の戻り値 (transition log 用)
  // issue #588: JLine の PosixSysTerminal 上で reader.peek(short_ms) は、
  //   underlying pty (AbstractPty$PtyInputStream) が VMIN=0/VTIME=1 (POSIX
  //   termios: 1 decisecond = 100ms 固定粒度) に設定されるため、「これ以上データが
  //   無い」ことを確認する際に要求 timeout (1ms) を無視して実測 ~100ms かかる
  //   (JLine 自体の特性、Emulin 側の問題ではない。native/software 両 backend・
  //   独立 wait(1) では再現しないことを確認済)。crossterm(codex 等)の CPR
  //   (cursor position report) 応答がこの ~100ms 遅延で背景 event reader との
  //   race に負け "cursor position could not be read" になる。
  //   PtyInputStream 内部の生 InputStream (FilterInputStream 経由で実 fd の
  //   available() = ioctl(FIONREAD) 相当に委譲) を reflection で直接掴めば、
  //   「既に buffer 済か」を待たず正確に判定できる (peek の代替、待ち意味論は
  //   変えない = 既存の「buffer 済の続きだけ probe」という意図のまま高速化)。
  //   reflection 失敗時 (将来の JLine version 変更等) は null のままとし、
  //   呼び出し側は必ず reader.peek(1) への fallback を保持する。
  private InputStream fastAvailIn;
  private boolean fastAvailInResolved = false;  // reflection 試行済みか (成否問わず一度だけ試す)
  // ---- issue #709 緩和 watchdog の観測点 ----
  //   WT→conpty 段のキーイベント滞留 (打鍵が届かないハードハング、手動ウィンドウリサイズの
  //   WINDOW_BUFFER_SIZE_EVENT で一斉フラッシュして復活する) を Emulin 側から自己 nudge する
  //   ための状態。lastPollNs = guest が stdin を見た最終時刻 (available/peekWait/read 等の呼出)、
  //   lastInputNs = 実際に入力を観測した最終時刻、stdinWaiters = blocking read/peek 中の thread 数
  //   (claude 等の bridge thread は bridgeRead() で永久 block するため「poll し続けている」の
  //   代わりにこれで待機中を検出する)。watchdog は startNudgeWatchdog() 参照。
  private volatile long lastPollNs  = System.nanoTime();
  private volatile long lastInputNs = System.nanoTime();
  private final java.util.concurrent.atomic.AtomicInteger stdinWaiters
      = new java.util.concurrent.atomic.AtomicInteger();

  public JLineConsole(Sysinfo _sysinfo) { this.sysinfo = _sysinfo; }

  // issue #588: fastAvailIn の遅延解決。terminal.input() を init() 直後
  //   (最初の reader.read() 成功より前) に呼ぶと reader 側の内部状態と競合して
  //   read() が hang する現象を実機確認した (isolated JLine test で再現)。
  //   このため呼び出しは「最初の rawMode drain (= 直前に reader.read() が
  //   最低 1 回成功済) 到達時」まで遅延する。reflection 失敗時は恒久的に
  //   null のままとし、以後は呼び出し側の fallback (reader.peek) に任せる。
  private InputStream resolveFastAvailIn() {
    if (fastAvailInResolved) return fastAvailIn;
    fastAvailInResolved = true;
    try {
      InputStream rawIn = terminal.input();
      Field inField = rawIn.getClass().getDeclaredField("in");
      inField.setAccessible(true);
      Object inner = inField.get(rawIn);
      if (inner instanceof InputStream) fastAvailIn = (InputStream) inner;
    } catch (Throwable t) {
      fastAvailIn = null;  // 将来の JLine version 変更等で構造が変わった場合の保険
    }
    return fastAvailIn;
  }

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
      // issue #692: Windows console (JLine jni) は物理 Backspace を uChar=0x08 で届ける
      //   (conpty の INPUT_RECORD 合成。jline は uChar 素通しで VK_BACK を特別扱いしない)。
      //   Linux の端末 (xterm 系 kbs=^?) は Backspace で DEL (0x7f) を送るため、guest の
      //   VERASE 既定 ^? (tmux pane / stty) と不一致になり、tmux 内 sh (dash) 等 canonical
      //   シェルで Backspace が erase にならない (#690 の行編集が VERASE 不一致で発動しない)。
      //   Windows では 0x08 → 0x7f に変換して Linux 端末の既定に揃える。console レベルで
      //   物理 Backspace と Ctrl+H は区別不能のため Ctrl+H も DEL になる (emacs の C-h help は
      //   F1 で代替)。EMULIN_KEY_BS=bs で従来動作 (0x08 のまま) に戻せる。
      boolean isWindows = System.getProperty( "os.name", "" ).toLowerCase().contains( "win" );
      if( isWindows && !"bs".equals( System.getenv( "EMULIN_KEY_BS" ) ) ) {
        final org.jline.utils.NonBlockingReader d = reader;
        reader = new org.jline.utils.NonBlockingReader() {
          private int tr( int c ) { return c == 0x08 ? 0x7f : c; }
          @Override protected int read( long timeout, boolean isPeek ) throws IOException {
            return tr( isPeek ? d.peek( timeout ) : d.read( timeout ) );
          }
          @Override public int readBuffered( char[] bf, int off, int len, long timeout ) throws IOException {
            int n = d.readBuffered( bf, off, len, timeout );
            for( int i = 0; i < n; i++ ) if( bf[off+i] == 0x08 ) bf[off+i] = 0x7f;
            return n;
          }
          @Override public int     available()          { return d.available(); }
          @Override public boolean ready() throws IOException { return d.ready(); }
          @Override public void    shutdown()           { d.shutdown(); }
          @Override public void    close() throws IOException { d.close(); }
        };
      }
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
      // issue #709 緩和: WT→conpty 段のキーイベント滞留に対する self-nudge watchdog。
      //   「guest が stdin を待っている (blocking read/peek 中 or 直近 poll) のに入力が長時間
      //   来ない」間、コンソール入力バッファへ無害な FOCUS_EVENT を注入して conhost の入力配送を
      //   蹴る (手動リサイズの WINDOW_BUFFER_SIZE_EVENT 相当の自己版。WinConsoleNudge 参照)。
      //   実機検証前のため既定 OFF。EMULIN_INPUT_NUDGE=1 で有効化、間隔=EMULIN_INPUT_NUDGE_MS
      //   (既定 2000ms)、EMULIN_INPUT_NUDGE_EVENT=winsize で WINDOW_BUFFER_SIZE_EVENT (現サイズ、
      //   SIGWINCH 再描画を伴う強い nudge) に切替、EMULIN_TRACE_NUDGE=1 で発火を stderr に出す。
      if (isWindows && "1".equals(System.getenv("EMULIN_INPUT_NUDGE"))
          && isNative() && WinConsoleNudge.probe()) {
        startNudgeWatchdog();
      }
    } catch (IOException e) {
      throw new RuntimeException("JLine console init failed: " + e.getMessage(), e);
    }
  }

  // issue #709 緩和 watchdog 本体。daemon thread が idle 間隔の 1/4 周期で観測点を見て、
  //   ① stdin 待ちが居る (stdinWaiters>0 または直近 2×idle 以内に poll あり)
  //   ② 入力が idle 間隔以上来ていない
  //   ③ 前回 nudge から idle 間隔以上経過
  //   の 3 条件が揃ったときだけ注入する。通常のアイドル (ユーザが考え中) でも発火するが、
  //   FOCUS_EVENT は JLine の pump が捨てる完全無害イベントなので副作用は無い。滞留が
  //   起きていた場合は最大 idle 間隔 (既定 2 秒) で自己回復する、が狙い。
  private void startNudgeWatchdog() {
    long ms = 2000;
    try {
      String e = System.getenv("EMULIN_INPUT_NUDGE_MS");
      if (e != null) ms = Math.max(200, Long.parseLong(e.trim()));
    } catch (NumberFormatException ignore) {}
    final long idleNs = ms * 1_000_000L;
    final long periodMs = Math.max(100, ms / 4);
    final boolean winsize = "winsize".equals(System.getenv("EMULIN_INPUT_NUDGE_EVENT"));
    final boolean trace = System.getenv("EMULIN_TRACE_NUDGE") != null;
    Thread t = new Thread(() -> {
      long lastNudgeNs = System.nanoTime();
      while (true) {
        try { Thread.sleep(periodMs); } catch (InterruptedException ie) { return; }
        long now = System.nanoTime();
        boolean waiting = stdinWaiters.get() > 0 || (now - lastPollNs) <= 2 * idleNs;
        if (!waiting) continue;                       // guest が stdin を見ていない (batch 等)
        if (now - lastInputNs < idleNs) continue;     // 入力が最近来ている = 健全
        if (now - lastNudgeNs < idleNs) continue;     // nudge 間隔の下限
        boolean ok = winsize ? WinConsoleNudge.injectWinSize(getColumns(), getRows())
                             : WinConsoleNudge.injectFocus();
        lastNudgeNs = now;
        if (trace) System.err.println("[nudge] " + (winsize ? "winsize" : "focus")
            + " ok=" + ok
            + " idleMs=" + ((now - lastInputNs) / 1_000_000)
            + " waiters=" + stdinWaiters.get());
      }
    }, "emulin-input-nudge");
    t.setDaemon(true);
    t.start();
    if (trace) System.err.println("[nudge] watchdog started: idleMs=" + ms
        + " event=" + (winsize ? "winsize" : "focus"));
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
    lastPollNs = System.nanoTime();                    // issue #709: guest が stdin を見た
    stdinWaiters.incrementAndGet();                    // issue #709: blocking read 中
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
          // issue #131 (layer 17): raw mode でも immediately-available な分は
          //   drain して 1 read で返す。1 byte ずつだと multi-byte ESC sequence
          //   (端末の DA1/DA2 等 query 応答 \033[?...c や矢印キー \033[A) が遅い
          //   poll 跨ぎで分断され、tmux の tty_keys ESC timeout で個別キーに
          //   誤 parse されて pane に garbage が流れる (forked server が peek 経由で
          //   1 byte ずつ読む経路で顕在化)。
          //   JLine の peek(timeout): timeout=0 は「無限ブロック」なので使えない。
          //   bounded な peek(1) (1ms) で buffer 済の続きだけを probe する (burst で
          //   届く ESC seq は OS buffer に揃っているので 1ms 以内に取れ、続きが
          //   無ければ 1ms で READ_EXPIRED(<0) → break)。
          //   issue #588: 「続きが無い」の確認自体が JLine の PosixSysTerminal では
          //   実測 ~100ms かかる (VMIN=0/VTIME=1 の POSIX termios 粒度、クラスコメント
          //   参照)。fastAvailIn (生 fd の available()) が使えればそちらを優先し、
          //   reflection 失敗時のみ従来の peek(1) に fallback する。
          try {
            InputStream fa = resolveFastAvailIn();
            while (i < buf.length) {
              boolean more = (fa != null) ? fa.available() > 0 : reader.peek(1) >= 0;
              if (!more) break;
              int nb = reader.read();
              if (nb < 0) break;
              buf[i++] = (byte)nb;
            }
          } catch (IOException e) { /* drain 中の I/O 例外は無視して取得分を返す */ }
          break;
        }
        // issue #55: ICRNL 相当 (Linux TTY の input mode default)。
        //   cmd.exe + JLine cooked mode は Enter で \r 単体を送ることがあり
        //   (\n 続かない)、旧実装の \"if (b == 0xD) continue;\" だと次の
        //   reader.read() で永久 block (ssh の passphrase 入力 → Enter で hang)。
        //   \r\n が pump に並んでいる場合: CR を skip して次 iter で LF を返す。
        //   \r 単体: LF に変換して line を終わらせる。
        if (b == 0xD) {
          if (reader.ready()) continue;   // \n が直後に follow → CR skip
          b = 0xA;                         // \r 単体 → LF にマッピング
        }
        buf[i++] = (byte)b;
        if (b == 0xA) break;
      }
    } catch (IOException e) {
      return i;
    } finally {
      stdinWaiters.decrementAndGet();
      if (i > 0) lastInputNs = System.nanoTime();      // issue #709: 実入力を観測
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
    if (bridgeMode) return false;   // issue #413: bridge thread が reader を排他所有
    lastPollNs = System.nanoTime();                    // issue #709: guest が stdin を見た
    try {
      boolean r = reader != null && reader.ready();
      if (r) lastInputNs = System.nanoTime();          // issue #709: 入力が居るのを観測
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

  // issue #131 (layer 17): available() = reader.ready() は JLine の内部 buffer
  //   しか見ず underlying stream を probe しないため、tmux server (fork 後の
  //   別プロセスが passed tty fd を poll するケース) で「buffer 空のまま
  //   ready()=false 固定」になり、打鍵が server まで届かない (poll が POLLIN を
  //   立てず read もされない)。peek(timeout) は stream を能動的に probe するので
  //   ready() が見逃す pending 入力を検出できる。peek は consume しないので
  //   後続の read()/reader.read() が同じ char を取得する。poll の TTY 経路で
  //   available() が false のときの fallback として使う (emacs 等 available()=true
  //   のケースは短絡され非干渉)。
  public boolean availablePeek() {
    if (bridgeMode) return false;   // issue #413: bridge thread が reader を排他所有
    lastPollNs = System.nanoTime();                    // issue #709: guest が stdin を見た
    try {
      if (reader == null) return false;
      boolean r = reader.peek(3) >= 0;   // 最大 3ms 待って underlying stream を probe
      if (r) lastInputNs = System.nanoTime();          // issue #709: 入力が居るのを観測
      return r;
    } catch (IOException e) {
      return false;
    }
  }

  // issue #206: poll/pselect の busy-sleep を排除する blocking peek。
  //   reader.peek(ms) は underlying stream を最大 ms ブロックして probe し、入力が
  //   到着した瞬間に返る (timeout で READ_EXPIRED)。peek は consume しないので
  //   後続の read()/Available() が同じ char を取得する。これを待機に使うと
  //   Thread.sleep(10) のポーリング遅延が消え、TTY 入力到着で即復帰できる
  //   (CPU spin は無し: stream の blocking read に委ねる)。戻り値は入力有無。
  public boolean peekWait(int ms) {
    if (bridgeMode) {              // issue #413: reader は bridge thread 専有 → ここでは触らず待つだけ
      try { Thread.sleep(Math.max(1, Math.min(ms, 20))); } catch (InterruptedException e) {}
      return false;
    }
    lastPollNs = System.nanoTime();                    // issue #709: guest が stdin を見た
    stdinWaiters.incrementAndGet();                    // issue #709: blocking peek 中
    try {
      if (reader == null) return false;
      if (ms < 1) ms = 1;
      boolean r = reader.peek(ms) >= 0;
      if (r) lastInputNs = System.nanoTime();          // issue #709: 入力が居るのを観測
      return r;
    } catch (IOException e) {
      return false;
    } finally {
      stdinWaiters.decrementAndGet();
    }
  }

  // issue #413: console→pty-master bridge thread 専用 read。ESC(0x1b) を読んだら
  //   continuation (CSI ESC[…final / SS3 ESC O X) を短い peek 待ちで coalesce し、
  //   async 先読みでエスケープ列が ESC 単独に分断されるのを防ぐ (claude(Bun) の矢印)。
  //   非 ESC は available 分をまとめて返す。戻り値 = 読んだ byte 数 (EOF で -1)。
  public int bridgeRead(byte[] buf) {
    lastPollNs = System.nanoTime();                    // issue #709: guest が stdin を見た
    stdinWaiters.incrementAndGet();                    // issue #709: bridge thread は永久 blocking read
    try {
      if (reader == null) return -1;
      int b = reader.read();           // blocking で最初の 1 byte
      if (b < 0) return -1;
      lastInputNs = System.nanoTime();                 // issue #709: 実入力を観測
      int i = 0;
      buf[i++] = (byte)b;
      if (b == 0x1b) {                 // ESC → continuation を待って一括化
        if (i < buf.length && reader.peek(40) >= 0) {
          int c = reader.read();
          if (c >= 0) {
            buf[i++] = (byte)c;
            if (c == 0x5b || c == 0x4f) {   // CSI '[' / SS3 'O' → final byte (0x40-0x7e) まで
              while (i < buf.length && reader.peek(40) >= 0) {
                int p = reader.read();
                if (p < 0) break;
                buf[i++] = (byte)p;
                if (p >= 0x40 && p <= 0x7e) break;
              }
            }
          }
        }
      } else {                         // 非 ESC: available 分をまとめる (paste / UTF-8)
        while (i < buf.length && reader.ready()) {
          int nb = reader.read();
          if (nb < 0) break;
          buf[i++] = (byte)nb;
        }
      }
      if (System.getenv("EMULIN_DEBUG_TTY") != null) {
        StringBuilder sb = new StringBuilder("DBG_BRIDGE_READ n=" + i + " hex=");
        for (int k = 0; k < i; k++) sb.append(String.format("%02x", buf[k] & 0xff));
        System.err.println(sb.toString());
      }
      return i;
    } catch (IOException e) {
      return -1;
    } finally {
      stdinWaiters.decrementAndGet();
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

  // issue #72: console 出力を確実に drain する。Windows native terminal
  //   (NativeWinSysTerminal) では System.out / terminal.output() への write +
  //   flush 後も、OS console host へのレンダリングが非同期で、emulin が直後に
  //   System.exit するとレンダリング前に JVM が終了し最後の出力が画面に出ない
  //   (`ls -l` 等の単発出力が消える)。exit 前に flush を呼んで drain させる。
  public void flush() {
    try { System.out.flush(); } catch (Exception ignore) {}
    try { System.err.flush(); } catch (Exception ignore) {}
    try { if (terminal != null) terminal.flush(); } catch (Exception ignore) {}
  }

  public void close() {
    try {
      flush();   // issue #72: close 前に必ず drain
      if (rawMode && savedAttrs != null && terminal != null) {
        terminal.setAttributes(savedAttrs);
        rawMode = false;
      }
      if (terminal != null) terminal.close();
    } catch (Exception ignore) {}
  }
}
