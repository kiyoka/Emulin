package emulin;

import java.io.File;
import java.util.List;

// --------------------------------------------------------------------
//  GuestJobSmoke — guest へ渡すコマンドが **引用符で壊れない**ことを検査する。
//
//  ★ 実害 (2026-08-26): インストーラが
//      printf 'sandbox_mode = "danger-full-access"\n' > ~/.codex/config.toml
//    を投げたところ、guest には**二重引用符が消えて**届き、不正な TOML が書かれた。
//    codex は config を解釈できず、sandbox_mode が効かない。
//    しかも当時の判定は `grep -q danger-full-access` だったので、**壊れたファイルを
//    「導入済み」と判定**し、二度と直らなかった。
//
//  ★ この事故は **Windows (cmd.exe / .bat) でしか起きない**。
//    `set "RUNCMD=%~1"` は外側の引用符しか外せず、中に `"` があると引用が切れる。
//    Linux の bash 経路では再現しないので、**guest を起動するテストでは捕まらない**。
//    だから「command line に何が載るか」という**構造**を検査する。
//
//  guest もネットワークも要らない (純 Java)。
// --------------------------------------------------------------------
public final class GuestJobSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  public static void main( String[] args ) throws Exception {
    // 実際に壊れたコマンドをそのまま使う (引用符 / リダイレクト / パイプ / % を含む)
    String cmd = "mkdir -p ~/.codex && printf 'sandbox_mode = \"danger-full-access\"\\n'"
               + " >> ~/.codex/config.toml && echo 100% | cat";
    GuestJob job = new GuestJob( "t", cmd, false );

    String wire = job.encodeForLauncher( cmd );
    System.out.println( "=== guest へ渡す文字列 ===" );
    System.out.println( "  " + wire );

    // ★ 危険な文字が command line に 1 つも載らないこと
    String payload = wire.replace( "echo ", "" ).replace( " | base64 -d | /bin/bash", "" );
    check( !payload.matches( ".*[\"'><&|%].*" ),
           "base64 部分に引用符・リダイレクト・パイプ・% が含まれない" );
    check( payload.matches( "[A-Za-z0-9+/=]+" ),
           "base64 部分は英数字と +/= だけ" );

    // ★ 復号すると元のコマンドと **1 byte も違わない** こと
    String back = new String( java.util.Base64.getDecoder().decode( payload ),
                              java.nio.charset.StandardCharsets.UTF_8 );
    check( back.equals( cmd ), "復号すると元のコマンドに完全一致する" );
    if( !back.equals( cmd ) ) {
      System.out.println( "    元 : " + cmd );
      System.out.println( "    復 : " + back );
    }

    // 非 ASCII (#932 の系統) も壊れないこと
    String ja = "echo 'テスト 日本語 \"引用\"' > /tmp/日本語.txt";
    String back2 = new String( java.util.Base64.getDecoder().decode(
        new GuestJob( "t", ja, false ).encodeForLauncher( ja )
            .replace( "echo ", "" ).replace( " | base64 -d | /bin/bash", "" ) ),
        java.nio.charset.StandardCharsets.UTF_8 );
    check( back2.equals( ja ), "非 ASCII を含むコマンドも完全一致する" );

    // ★ issue #963: cmd.exe を経由しなくなったので、guest へは argv で直接渡す。
    //   コンソール (黒い窓) を出さないため javaw を使うこと。
    ProcessBuilder pb = GuestLaunch.builder( new File( "." ),
        java.util.Arrays.asList( "/bin/bash", "-c", job.encodeForLauncher( cmd ) ), true );
    if( pb != null ) {
      check( !pb.command().get( 0 ).endsWith( "java.exe" ),
             "子は javaw で起動する (java.exe だとコンソールが出る)" );
      check( pb.command().stream().noneMatch( x -> x.contains( "\"" ) ),
             "argv に二重引用符が 1 つも載らない (ProcessBuilder が Windows で壊すため)" );
    }

    // ★ 端末制御が画面へ漏れないこと。実際に Claude 公式インストーラが出したバイト列
    //   (2026-08-26 のログから採取)。進捗行が化けて読めなかった。
    String raw = "\u001B[38;5;174mChecking\u001B[10Ginstallation\u001B[23Gstatus...\u001B[39m";
    String got = GuestJob.sanitizeForDisplay( raw );
    System.out.println( "=== 端末制御の除去 ===" );
    System.out.println( "  -> [" + got + "]" );
    check( got.equals( "Checking installation status..." ),
           "色指定とカーソル移動を取り除き、単語が繋がらない" );
    check( got.indexOf( 0x1B ) < 0 && !got.contains( "[38;5" ) && !got.contains( "[10G" ),
           "ESC も残骸 ([38;5;174m 等) も残っていない" );
    check( GuestJob.sanitizeForDisplay( "\u001B7\u001B[r\u001B8\u001B[?25h" ).isEmpty(),
           "制御シーケンスだけの行は空になる (画面に出さない)" );
    check( GuestJob.sanitizeForDisplay( "  Unpacking node-levn (0.4.1) ..." )
             .equals( "  Unpacking node-levn (0.4.1) ..." ),
           "ふつうの行は字下げも含めてそのまま" );

    // ★ 実インストーラが終了時に出す「端末を元に戻す」一連 (2026-08-26 のログから採取)。
    //   私用パラメータ (ESC[>4m / ESC[<u) と中間バイト付き (ESC ( B) を含み、
    //   最初の実装ではここが `(B>4m<u` として画面に漏れた。
    String tail = "\u001B[?25h\u001B[?1006l\u001B[?1003l\u001B(B\u000F\u001B[>4m\u001B[<u"
                + "\u001B[?2004l\u001B7\u001B[r\u001B8\u001B[?25h";
    check( GuestJob.sanitizeForDisplay( tail ).isEmpty(),
           "終了時の端末復帰シーケンス一式が 1 文字も残らない" );

    // ★ アニメーション再描画の合成 (実インストーラのバイト列そのもの)。
    //   `ESC[1A` で 1 行上へ戻り、**変化した桁だけ**を書き直す形。制御を消すだけだと
    //   `Installing Cl ude C de n ive build latest...` と文字が欠ける。
    {
      StringBuilder screen = new StringBuilder();
      int[] col = { 0 };
      GuestJob.renderOnto( screen, col,
          "\u001B[38;5;174mChecking\u001B[10Ginstallation\u001B[23Gstatus...\u001B[39m" );
      col[0] = 0;
      String r = GuestJob.renderOnto( screen, col,
          "\u001B[1A\u001B[38;5;174mInstalling Cl\u001B[15Gude C\u001B[21Gde n"
          + "\u001B[27Give build latest...\u001B[39m" );
      System.out.println( "  -> [" + r + "]" );
      check( r.equals( "Installing Claude Code native build latest..." ),
             "再描画フレームを合成すると欠けた文字が戻る" );
    }
    // ★ CSI の省略時既定値は機能ごとに違う。消去 (K/J) は 0、移動は 1。
    //   一律 1 にすると `ESC[K` が `ESC[1K` になり、書いたばかりの行が空白で潰れる。
    {
      StringBuilder screen = new StringBuilder();
      int[] col = { 0 };
      String r = GuestJob.renderOnto( screen, col, "abcdef\u001B[4G\u001B[K" );
      check( r.equals( "abc" ), "ESC[K は既定 0 (カーソルから行末まで消去): [" + r + "]" );
    }

    // ★ issue #963: listen port が既に使われていることを**押す前に**検知する。
    //   起動してから "Address already in use" で死ぬ形にしない。
    {
      try ( java.net.ServerSocket hold = new java.net.ServerSocket() ) {
        hold.setReuseAddress( false );
        hold.bind( new java.net.InetSocketAddress(
            java.net.InetAddress.getByName( "127.0.0.1" ), 0 ) );
        int busyPort = hold.getLocalPort();
        String msg = SshdService.portInUse( busyPort );
        System.out.println( "=== port 使用中の検知 ===" );
        System.out.println( "  -> " + msg );
        check( msg != null && msg.contains( String.valueOf( busyPort ) ),
               "使用中の port を検知し、port 番号を文面に出す" );
        // ★ 負のコントロール: 空いている port では null を返すこと
        //   (常に「使用中」と言う実装を通さない)
        int freePort;
        try ( java.net.ServerSocket probe = new java.net.ServerSocket( 0 ) ) {
          freePort = probe.getLocalPort();
        }
        check( SshdService.portInUse( freePort ) == null,
               "空いている port は使用中と言わない (port " + freePort + ")" );
      }
    }

    // ★ native pool の扱いは job ごとに違う (実運用の指示):
    //   - apt install 等は **EMULIN_NATIVE_POOL_MB を外す** (固定すると途中で止まる)
    //   - sshd は claude / codex を動かす前提なので 1024 を明示する
    //   ★ 肝は **host の env に設定されていても外れる / 上書きされる**こと。
    //     この検査は EMULIN_NATIVE_POOL_MB を設定した状態で走らせて初めて意味がある
    //     (tests/scripts/guestjob-quoting-smoke.sh がそうしている)。
    {
      // ★ builder は配布物 (lib/emulin-*-all.jar と rootfs) が無いと null を返す。
      //   カレントで呼ぶと null になり、**検査が 1 つも走らないまま緑になる** (実際そうなった)。
      //   偽の配布物を作ってから呼ぶ。
      File fake = java.nio.file.Files.createTempDirectory( "emulin-fakedist" ).toFile();
      new File( fake, "lib" ).mkdirs();
      new File( fake, "rootfs" ).mkdirs();
      new File( fake, "lib/emulin-0.0.0-all.jar" ).createNewFile();
      // ★ 非 root ユーザーが居る配布物にする。これが無いと guestUser() が null になり、
      //   下の HOME/uid の検査が**一度も走らないまま緑になる** (#996 を見逃した形)。
      new File( fake, "rootfs/etc" ).mkdirs();
      try ( java.io.PrintWriter w = new java.io.PrintWriter( new File( fake, "rootfs/etc/emulin-user" ) ) ) {
        w.println( "smokeuser" );
      }
      java.util.List<String> argv = java.util.Arrays.asList( "/bin/true" );
      ProcessBuilder ins = GuestLaunch.builderNoPool( fake, argv, true );
      // ★ sshd は **SshdService の呼び出し口をそのまま通す**。ここで
      //   GuestLaunch.builderWithPool(..., SSHD_POOL_MB) を検査側が組み立てると、
      //   SshdService が GuestLaunch.builder(...) (launcher 既定 2048) に書き換わっても
      //   検査は緑のまま通る = 守りたいものを守れない。
      ProcessBuilder ssh = new SshdService( fake ).sshdBuilder( 2222 );
      check( ins != null && ssh != null,
             "検査の前提: 偽の配布物で ProcessBuilder が作れる (null だと何も確かめられない)" );
      String hostVal = System.getenv( "EMULIN_NATIVE_POOL_MB" );
      System.out.println( "=== native pool の扱い (host の env = "
                          + ( hostVal == null ? "未設定" : hostVal ) + ") ===" );
      if( ins != null ) {
        String v = ins.environment().get( "EMULIN_NATIVE_POOL_MB" );
        System.out.println( "  install job -> " + ( v == null ? "(外れている)" : v ) );
        check( v == null, "install / 判定では EMULIN_NATIVE_POOL_MB が外れる"
                        + ( hostVal != null ? " (host に " + hostVal + " があっても)" : "" ) );
      }
      if( ssh != null ) {
        String v = ssh.environment().get( "EMULIN_NATIVE_POOL_MB" );
        System.out.println( "  sshd        -> " + v );
        check( "1024".equals( v ), "sshd では 1024 に固定される"
                        + ( hostVal != null ? " (host に " + hostVal + " があっても)" : "" ) );
        // ★ 起こすものが sshd であること自体も見る。argv が変わって別物を起こしていたら、
        //   pool だけ合っていても意味がない。
        String sshCmd = String.join( " ", ssh.command() );
        check( sshCmd.contains( "/usr/sbin/sshd" ) && sshCmd.contains( "-p 2222" ),
               "sshd の argv (port 込み) がそのまま渡る" );
        // ★ 台帳に「sshd:<port>」と名乗らせる (#963)。無いと、稼働中の Emulin が sshd か
        //   端末か pid からは分からず、「どれを止めればよいか」が決められない。
        check( "sshd".equals( ssh.environment().get( "EMULIN_ROLE" ) )
               && "2222".equals( ssh.environment().get( "EMULIN_ROLE_PORT" ) ),
               "sshd は役割と port を名乗る (台帳に載る)" );
      }
      // ★ issue #985: Open terminal も **sshd と同じ 1024** にする。以前はここだけ
      //   何もしておらず、`emulin.bat` の既定 2048 のまま動いていた。
      //   ★ ただし **host が明示した値は尊重する**ので、期待値は env の状態で変わる。
      //     **設定あり / 未設定の両方で走らせて初めて意味がある** —
      //     tests/scripts/guestjob-quoting-smoke.sh が 2 回走らせている。
      //   ★ ここも **LauncherApp の呼び出し口をそのまま通す** (sshd と同じ理由。
      //     検査側で ProcessBuilder を組み立てると、製品側が素の ProcessBuilder に
      //     戻っても緑のまま通ってしまう)。
      {
        ProcessBuilder term = LauncherApp.terminalBuilder( fake, false, "wt.exe", "--", "cmd" );
        String v = term.environment().get( "EMULIN_NATIVE_POOL_MB" );
        System.out.println( "  open terminal -> " + ( v == null ? "(外れている)" : v ) );
        if( hostVal == null || hostVal.trim().isEmpty() )
          check( String.valueOf( GuestLaunch.AGENT_POOL_MB ).equals( v ),
                 "Open terminal は host 未設定なら " + GuestLaunch.AGENT_POOL_MB + " にする" );
        else
          check( hostVal.equals( v ),
                 "Open terminal は host が明示した値 (" + hostVal + ") を尊重する" );
        // ★ 起こすものが端末であること自体も見る (pool だけ合っていても意味がない)。
        check( String.join( " ", term.command() ).contains( "wt.exe" ),
               "Open terminal の argv がそのまま渡る" );
        // ★ issue #996: 端末は非 root で開く。既定 (root) のまま開くと、
        //   エージェントは非 root のホームに入っているので command not found になる。
        System.out.println( "  open terminal -> EMULIN_LOGIN=" + term.environment().get( "EMULIN_LOGIN" ) );
        check( "user".equals( term.environment().get( "EMULIN_LOGIN" ) ),
               "Open terminal は非 root ユーザーで開く (#996)" );
        // ★ root のボタンもある。guest に sudo が無いので apt には root が要る。
        //   **押した先が env で決まる**ことを両方見る (片方だけだと決め打ちを見逃す)。
        ProcessBuilder rootTerm = LauncherApp.terminalBuilder( fake, true, "wt.exe" );
        System.out.println( "  open terminal (root) -> EMULIN_LOGIN="
                            + rootTerm.environment().get( "EMULIN_LOGIN" ) );
        check( "root".equals( rootTerm.environment().get( "EMULIN_LOGIN" ) ),
               "Open terminal as root は root で開く (#996: sudo が無いので apt に要る)" );
      }
      // ★ issue #996: 初回のユーザー名の候補と、guest の shell へ渡すときの引用。
      //   名前は `emulin-adduser <name>` として shell に渡るので、引用が崩れると
      //   別のコマンドが走りうる (#948 で引用符が消えて壊れた前科がある)。
      {
        String s = LauncherApp.suggestUserName();
        System.out.println( "  suggestUserName() -> " + s );
        boolean okName = !s.isEmpty() && !Character.isDigit( s.charAt( 0 ) );
        for( char c : s.toCharArray() )
          if( !( ( c >= 'a' && c <= 'z' ) || ( c >= '0' && c <= '9' ) || c == '_' || c == '-' ) )
            okName = false;
        check( okName, "ユーザー名の候補が guest で通る形 (英小文字/数字/_/- で数字始まりでない)" );
        check( LauncherApp.suggestUserName().length() <= 31, "ユーザー名の候補が 31 文字以内" );
        String q = LauncherApp.shellQuote( "ab'cd" );
        System.out.println( "  shellQuote(ab'cd) -> " + q );
        check( q.startsWith( "'" ) && q.endsWith( "'" ) && !q.equals( "'ab'cd'" ),
               "ユーザー名の引用符が閉じる (単引用符を含む名前でも壊れない)" );
      }

      // ★ issue #996: 非 root で走らせる job は **uid/gid と HOME が揃っている**こと。
      //   HOME だけ /root のままだと、uid 1000 で走るのに書き込み先が root のホームに
      //   なり、Install Claude Code が /root/.local/bin に入って README どおりの
      //   非 root セッションから見えなくなる (実機で踏んだ)。
      {
        ProcessBuilder nonroot = GuestLaunch.builder( fake, argv, false );
        ProcessBuilder asroot  = GuestLaunch.builder( fake, argv, true );
        check( nonroot != null && asroot != null, "検査の前提: 両方の ProcessBuilder が作れる" );
        if( nonroot != null && asroot != null ) {
          java.util.Map<String,String> n = nonroot.environment(), r = asroot.environment();
          System.out.println( "  非 root -> uid=" + n.get( "EMULIN_UID" ) + " HOME=" + n.get( "HOME" ) );
          System.out.println( "  root    -> uid=" + r.get( "EMULIN_UID" ) + " HOME=" + r.get( "HOME" ) );
          check( "1000".equals( n.get( "EMULIN_UID" ) ) && "1000".equals( n.get( "EMULIN_GID" ) )
                 && "/home/smokeuser".equals( n.get( "HOME" ) ),
                 "非 root job は uid/gid/HOME が揃う (HOME=/home/<user>)" );
          check( r.get( "EMULIN_UID" ) == null && "/root".equals( r.get( "HOME" ) ),
                 "root job は uid を付けず HOME=/root のまま" );
          check( "smokeuser".equals( n.get( "EMULIN_THEUSER" ) )
                 && "smokeuser".equals( r.get( "EMULIN_THEUSER" ) ),
                 "EMULIN_THEUSER は root/非 root の両方で渡る (#963)" );
        }
      }
    }

    // ★ 判定用マーカーは画面に出さないが、**判定に使う全文には残す**。
    //   実機で「Checking... の次に NG2 と出てエラーに見える」と指摘された (2026-08-27)。
    {
      GuestJob j = new GuestJob( "t", "x", false );
      java.lang.reflect.Method m = GuestJob.class.getDeclaredMethod( "addTail", String.class );
      m.setAccessible( true );
      for( String l : new String[]{ "OK0", "NG2", "Reading package lists...", "OK12" } )
        m.invoke( j, l );
      java.util.List<String> t = j.tailLines();
      System.out.println( "=== 判定マーカーの扱い ===" );
      System.out.println( "  画面: " + t );
      check( t.size() == 1 && t.get( 0 ).equals( "Reading package lists..." ),
             "OK<n> / NG<n> は画面に出さない" );
      check( j.fullOutput().contains( "OK0" ) && j.fullOutput().contains( "NG2" ),
             "判定に使う全文には残る (これが消えると導入判定が壊れる)" );
    }

    // ★ EMULIN_THEUSER は **root で走らせるときも渡す** (#963 の取りこぼし)。
    //   Egress は placeholder を書く先を /root と /home/$EMULIN_THEUSER で決めるので、
    //   これが無いと **非 root ユーザーの credential が更新されない**。
    //   実害 (2026-08-27): sshd を root で起動 → /home/<user> に**とうに終了した
    //   導入ジョブの placeholder** が残り、動いている MITM が知らず素通し → 401 →
    //   claude が credential を捨てて "Login expired"。
    {
      File fake = java.nio.file.Files.createTempDirectory( "emulin-theuser" ).toFile();
      new File( fake, "lib" ).mkdirs();
      new File( fake, "rootfs/etc" ).mkdirs();
      new File( fake, "lib/emulin-0.0.0-all.jar" ).createNewFile();
      java.nio.file.Files.write( new File( fake, "rootfs/etc/emulin-user" ).toPath(),
                                 "kiyoka\n".getBytes( "UTF-8" ) );
      java.util.List<String> argv = java.util.Arrays.asList( "/bin/true" );
      ProcessBuilder asRoot    = GuestLaunch.builderWithPool( fake, argv, true, 1024 );
      ProcessBuilder asNonRoot = GuestLaunch.builderNoPool( fake, argv, false );
      System.out.println( "=== EMULIN_THEUSER の扱い ===" );
      if( asRoot != null && asNonRoot != null ) {
        System.out.println( "  root     -> THEUSER=" + asRoot.environment().get( "EMULIN_THEUSER" )
                            + "  UID=" + asRoot.environment().get( "EMULIN_UID" ) );
        System.out.println( "  non-root -> THEUSER=" + asNonRoot.environment().get( "EMULIN_THEUSER" )
                            + "  UID=" + asNonRoot.environment().get( "EMULIN_UID" ) );
        check( "kiyoka".equals( asRoot.environment().get( "EMULIN_THEUSER" ) ),
               "root で走らせても EMULIN_THEUSER を渡す (sshd が非 root ユーザーに使わせるため)" );
        check( asRoot.environment().get( "EMULIN_UID" ) == null,
               "root のときは UID/GID を設定しない (誰として走るかは別の話)" );
        check( "1000".equals( asNonRoot.environment().get( "EMULIN_UID" ) )
               && "kiyoka".equals( asNonRoot.environment().get( "EMULIN_THEUSER" ) ),
               "非 root では UID/GID と THEUSER の両方を渡す" );
      } else {
        check( false, "検査の前提: 偽の配布物で ProcessBuilder が作れる" );
      }
    }

    // ★ 別のランチャーが起動した sshd を認識できること。ただし
    //   **port を掴んでいるだけの無関係なプロセスを自分のものと見なさない**こと。
    //   実害 (2026-08-27): ランチャーを開き直すと、動いている sshd があるのに
    //   ボタンが Start のままだった (自分が起動した Process しか見ていなかった)。
    {
      File fake2 = java.nio.file.Files.createTempDirectory( "emulin-sshdstate" ).toFile();
      new File( fake2, "lib" ).mkdirs();
      new File( fake2, "rootfs" ).mkdirs();
      new File( fake2, "lib/emulin-0.0.0-all.jar" ).createNewFile();
      SshdService sv = new SshdService( fake2 );
      System.out.println( "=== 別窓が起動した sshd の判定 ===" );
      // ★ 負のコントロール: 台帳に無い port は「自分のもの」と見なさない
      check( sv.externalPid( 65000 ) == 0,
             "台帳に無い port は自分の sshd と見なさない" );
      // ★ port は埋まっているが Emulin ではない場合も、見なさない
      try ( java.net.ServerSocket hold = new java.net.ServerSocket() ) {
        hold.setReuseAddress( false );
        hold.bind( new java.net.InetSocketAddress(
            java.net.InetAddress.getByName( "127.0.0.1" ), 0 ) );
        int busy = hold.getLocalPort();
        check( sv.externalPid( busy ) == 0,
               "Emulin 以外が掴んでいる port を自分の sshd と見なさない (port " + busy + ")" );
        check( SshdService.portInUse( busy ) != null,
               "  ただし『使用中』としては検知する" );
      }
    }

    if( failures == 0 ) { System.out.println( "GuestJob smoke OK" ); System.exit( 0 ); }
    System.out.println( "GuestJob smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
