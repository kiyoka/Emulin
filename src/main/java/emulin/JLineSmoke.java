// Phase 22 step 3a/3c: JLine 依存導入のスモークテスト。
// (3a) Terminal を作って raw mode の往復ができるか
// (3c) JLineConsole の setInt/checkInt/cancelInt の契約が壊れていないか
// を確認するミニ CLI。非 tty 環境では JLine が dumb terminal にフォールバック。
package emulin;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import emulin.device.JLineConsole;

public final class JLineSmoke {
  public static void main(String[] args) throws Exception {
    try (Terminal t = TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .build()) {
      String ver = Terminal.class.getPackage().getImplementationVersion();
      System.out.println("jline-version=" + (ver == null ? "unknown" : ver));
      System.out.println("type=" + t.getType());
      Size size = t.getSize();
      System.out.println("size=" + size.getColumns() + "x" + size.getRows());
      Attributes saved = t.enterRawMode();
      t.setAttributes(saved);
      System.out.println("raw-mode-ok");
    }

    // step 3c: SIGINT API の契約スモーク
    Sysinfo si = new Sysinfo(0, false);
    JLineConsole jc = new JLineConsole(si);
    jc.init();
    boolean before = jc.checkInt();
    jc.setInt(Signal.SIGINT);
    boolean afterSet = jc.checkInt();
    jc.cancelInt();
    boolean afterCancel = jc.checkInt();
    System.out.println("signal-before=" + before
        + " set=" + afterSet + " cancel=" + afterCancel);
    if (!before && afterSet && !afterCancel) {
      System.out.println("signal-api-ok");
    }

    // step 3d: SIGWINCH API + size 取得の契約スモーク
    boolean wBefore = jc.checkWinch();
    jc.setWinch();
    boolean wSet = jc.checkWinch();
    jc.cancelWinch();
    boolean wCancel = jc.checkWinch();
    int cols = jc.getColumns();
    int rows = jc.getRows();
    System.out.println("winch-before=" + wBefore
        + " set=" + wSet + " cancel=" + wCancel);
    System.out.println("winch-size=" + cols + "x" + rows);
    if (!wBefore && wSet && !wCancel && cols >= 0 && rows >= 0) {
      System.out.println("winch-api-ok");
    }
    jc.close();
  }
}
