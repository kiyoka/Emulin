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

    // launcherCommand が run サブコマンド経由で、生のコマンドを載せていないこと
    List<String> lc = job.launcherCommand( new File( "." ) );
    check( lc.contains( "run" ), "launcher の run サブコマンド経由で渡している" );
    check( lc.stream().noneMatch( a -> a.contains( "danger-full-access" ) ),
           "argv に生のコマンド文字列が載っていない" );

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

    if( failures == 0 ) { System.out.println( "GuestJob smoke OK" ); System.exit( 0 ); }
    System.out.println( "GuestJob smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
