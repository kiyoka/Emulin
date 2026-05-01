// Phase 22 step 3a: JLine 依存導入のスモークテスト。
// Terminal を作って raw mode の往復ができるか確認するだけのミニ CLI。
// 非 tty 環境では JLine が dumb terminal にフォールバックする。
package emulin;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

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
  }
}
