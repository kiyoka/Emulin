package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  AgentInstall — Codex CLI / Claude Code の導入手順 (issue #948)
//
//  ★ この 2 つは**手順がほぼ正反対**で、そこが一番間違えやすい。
//    README にはどちらも注記があるが「人間が読んで守る」前提になっている。
//    **その使い分けを UI が肩代わりする**のが、この画面の価値の中心。
//
//    | | Codex CLI | Claude Code |
//    |---|---|---|
//    | 導入      | apt + npm -g          | curl \| bash          |
//    | 実行ユーザー | **root**            | **非 root (uid 1000)** |
//    | 導入先    | /usr/lib/node_modules | ~/.local/bin          |
//    | 起動      | 非 root               | 非 root               |
//    | 追加設定  | ★ config.toml を**非 root のホーム**に (root に置いても効かない) | ~/.bashrc に PATH |
//
//  ★ 現状を判定してから出す。「nodejs は入っているが codex は未導入」はよくあり、
//    判定しないと **8 分の apt install を無駄に繰り返す** (実測: 559 パッケージ・約 8 分・2GB)。
// --------------------------------------------------------------------
public final class AgentInstall {

  private AgentInstall( ) { }

  /** 1 工程。判定コマンドが 0 を返せば「済」。 */
  public static final class Step {
    public final String  title;
    public final String  command;      // guest で実行する内容
    public final String  checkCommand; // 済かどうかの判定 (0 = 済)。null なら常に実行対象
    public final boolean asRoot;
    public volatile Boolean done;      // null = 未判定

    Step( String title, String command, String checkCommand, boolean asRoot ) {
      this.title = title; this.command = command; this.checkCommand = checkCommand; this.asRoot = asRoot;
    }
    public String userLabel() { return asRoot ? "root" : "非 root"; }
    public GuestJob toJob() { return new GuestJob( title, command, asRoot ); }
  }

  public static final class Agent {
    public final String name;
    public final List<Step> steps;
    Agent( String name, List<Step> steps ) { this.name = name; this.steps = steps; }
  }

  /** Codex CLI — ★ root で入れて、非 root で使い、設定は非 root のホームに置く。 */
  public static Agent codex() {
    List<Step> s = new ArrayList<>();
    s.add( new Step( "nodejs / npm を入れる  (約 8 分・2GB)",
                     "apt-get update && apt-get install -y nodejs npm </dev/null",
                     "dpkg -s nodejs npm >/dev/null 2>&1", true ) );
    s.add( new Step( "codex を入れる",
                     "npm install -g @openai/codex",
                     "command -v codex >/dev/null 2>&1 || test -e /usr/local/lib/node_modules/@openai/codex", true ) );
    // ★ ここが最頻の落とし穴: root のホームに置いても効かない。**非 root で**作る。
    s.add( new Step( "~/.codex/config.toml を作る  (これが無いと codex は panic する)",
                     "mkdir -p ~/.codex && printf 'sandbox_mode = \"danger-full-access\"\\n' >> ~/.codex/config.toml",
                     "grep -q danger-full-access ~/.codex/config.toml 2>/dev/null", false ) );
    return new Agent( "Codex CLI", s );
  }

  /** Claude Code — ★ 非 root で入れる。root で入れると /root/.local/bin に入り見えなくなる。 */
  public static Agent claude() {
    List<Step> s = new ArrayList<>();
    s.add( new Step( "Claude Code を入れる  (公式インストーラ)",
                     "curl -fsSL https://claude.ai/install.sh | bash",
                     "test -e ~/.local/bin/claude", false ) );
    // ★ Emulin は bash を -i (非ログイン) で起動するので ~/.profile が読まれない。
    s.add( new Step( "~/.bashrc に PATH を通す  (~/.local/bin)",
                     "grep -q 'local/bin' ~/.bashrc || printf '\\nif [ -d \"$HOME/.local/bin\" ] ; then\\n    PATH=\"$HOME/.local/bin:$PATH\"\\nfi\\n' >> ~/.bashrc",
                     "grep -q 'local/bin' ~/.bashrc 2>/dev/null", false ) );
    return new Agent( "Claude Code", s );
  }

  public static List<Agent> all() { return Arrays.asList( codex(), claude() ); }

  /**
   *  現状を判定する。★ 1 回の guest 起動でまとめて調べる (起動が重いので工程ごとに
   *  上げると数十秒かかる)。判定結果は Step.done に入れる。
   */
  public static void detect( File home, List<Agent> agents ) {
    List<Step> steps = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for( Agent a : agents )
      for( Step st : a.steps ) {
        if( st.checkCommand == null ) continue;
        steps.add( st );
        // 各判定を "OK<n>" / "NG<n>" として 1 回の shell でまとめて出す
        sb.append( "if " ).append( st.checkCommand )
          .append( "; then echo OK" ).append( steps.size() - 1 )
          .append( "; else echo NG" ).append( steps.size() - 1 ).append( "; fi; " );
      }
    if( steps.isEmpty() ) return;
    // ★ 非 root のホームを見る判定があるので、判定自体も非 root で走らせる
    //   (root で走らせると ~/.codex が /root/.codex になり、誤って「未」と出る)。
    GuestJob probe = new GuestJob( "現状を確認しています", sb.toString(), false );
    probe.run( home, null );
    // ★ 末尾 15 行ではなく**全出力**で判定する (項目が増えると押し出されて誤判定になる)
    String out = probe.fullOutput();
    for( int i = 0; i < steps.size(); i++ )
      steps.get( i ).done = out.contains( "OK" + i );
  }
}
