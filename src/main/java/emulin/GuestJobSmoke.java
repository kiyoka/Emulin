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
