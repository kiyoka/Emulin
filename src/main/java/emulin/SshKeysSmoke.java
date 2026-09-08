package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  SshKeysSmoke — 公開鍵の登録が **秘密鍵を通さない**ことを検査する (issue #964)
//
//  ★ ここが最重要。秘密鍵を guest の authorized_keys に書き込むと、
//    #401 の不変条件 (実 credential は host 側にのみ置く) が真っ向から破れる。
//    しかも「動いてしまう」ので気付けない。
//
//  ★ 拡張子で判定しないことも検査する (.pub という名前の秘密鍵を作って当てる)。
// --------------------------------------------------------------------
public final class SshKeysSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  private static final String PUB =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ8Zx5Q0Nq0iH6h7oPWQ0m3n5H1cQ9y1YvC0dW8kK2pL kiyoka@example";
  private static final String PRIV =
      "-----BEGIN OPENSSH PRIVATE KEY-----\n"
      + "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n"
      + "-----END OPENSSH PRIVATE KEY-----\n";

  public static void main( String[] args ) throws Exception {
    File tmp = java.nio.file.Files.createTempDirectory( "emulin-keys" ).toFile();
    File home = new File( tmp, "dist" );
    new File( home, "rootfs/root/.ssh" ).mkdirs();

    System.out.println( "=== #964 公開鍵の登録 ===" );

    // (1) ★ 秘密鍵は拒否する
    check( SshKeys.rejectReason( PRIV ) != null, "秘密鍵を拒否する" );
    System.out.println( "       理由: " + SshKeys.rejectReason( PRIV ) );

    // (2) ★ 拡張子で判定しない — .pub という名前の秘密鍵
    File fake = new File( tmp, "id_ed25519.pub" );
    java.nio.file.Files.write( fake.toPath(), PRIV.getBytes( "UTF-8" ) );
    check( SshKeys.parse( fake, "test" ) == null,
           ".pub という名前でも中身が秘密鍵なら読み込まない" );

    // (3) 正しい公開鍵は通る
    File good = new File( tmp, "good.pub" );
    java.nio.file.Files.write( good.toPath(), ( PUB + "\n" ).getBytes( "UTF-8" ) );
    SshKeys.PubKey k = SshKeys.parse( good, "test" );
    check( k != null, "公開鍵は読み込める" );
    if( k == null ) { System.out.println( "SshKeys smoke FAILED" ); System.exit( 1 ); }
    check( k.type.equals( "ssh-ed25519" ) && k.comment.equals( "kiyoka@example" ),
           "種別とコメントを取り出す: " + k.type + " / " + k.comment );
    check( k.fingerprint.startsWith( "SHA256:" ) && k.fingerprint.length() > 20,
           "fingerprint を出す: " + k.fingerprint );

    // (4) 登録できる / ★ 冪等 (2 回目は増えない)
    System.out.println( "  " + SshKeys.install( home, k ) );
    File ak = SshKeys.authorizedKeys( home );
    long n1 = countKeys( ak );
    String again = SshKeys.install( home, k );
    long n2 = countKeys( ak );
    check( n1 == 1 && n2 == 1, "同じ鍵を 2 回登録しても増えない (" + n1 + " -> " + n2 + ")" );
    System.out.println( "       2 回目: " + again );
    check( SshKeys.installed( home ).contains( k.fingerprint ), "登録済みとして認識される" );

    // (5) ★ 既存の行を消さない
    java.nio.file.Files.write( ak.toPath(),
        ( "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQC other@host\n"
        + new String( java.nio.file.Files.readAllBytes( ak.toPath() ), "UTF-8" ) ).getBytes( "UTF-8" ) );
    File good2 = new File( tmp, "good2.pub" );
    java.nio.file.Files.write( good2.toPath(),
        ( "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL1Zx5Q0Nq0iH6h7oPWQ0m3n5H1cQ9y1YvC0dW8kK2pQ b@c\n" )
            .getBytes( "UTF-8" ) );
    SshKeys.install( home, SshKeys.parse( good2, "test" ) );
    check( countKeys( ak ) == 3, "既存の行を消さずに追記する (" + countKeys( ak ) + " 件)" );

    // (n) ★ 非 root ユーザー (#380) にも配る — issue #963 の実機確認で踏んだ形。
    //   root → user のコピーは sshd の**起動時にしか**走らないので、ここで配らないと
    //   稼働中の sshd では `ssh <user>@` が Permission denied のままになる。
    {
      File h2 = new File( tmp, "dist2" );
      new File( h2, "rootfs/root/.ssh" ).mkdirs();
      new File( h2, "rootfs/etc" ).mkdirs();
      java.nio.file.Files.write( new File( h2, "rootfs/etc/emulin-user" ).toPath(),
                                 "kiyoka\n".getBytes( "UTF-8" ) );
      File rootAk = new File( h2, "rootfs/root/.ssh/authorized_keys" );
      File userAk = new File( h2, "rootfs/home/kiyoka/.ssh/authorized_keys" );
      SshKeys.PubKey pk = SshKeys.parse( good, "test" );

      String msg = SshKeys.install( h2, pk );
      System.out.println( "  install -> " + msg );
      check( countKeys( rootAk ) == 1 && countKeys( userAk ) == 1,
             "root と非 root ユーザーの両方に配る (root " + countKeys( rootAk )
             + " / user " + countKeys( userAk ) + ")" );
      check( SshKeys.installed( h2 ).contains( pk.fingerprint ), "登録済みとして認識される" );
      check( SshKeys.install( h2, pk ).startsWith( "this key is already installed" ),
             "2 回目は足さない (冪等)" );

      // ★ 負のコントロール: 非 root 側だけ欠けた状態を作る。ここで「登録済み」と
      //   言ってしまうと、実機で踏んだ「登録したのに繋がらない」に戻る。
      userAk.delete();
      check( !SshKeys.installed( h2 ).contains( pk.fingerprint ),
             "片方に欠けている鍵を「登録済み」と言わない" );
      SshKeys.install( h2, pk );
      check( countKeys( userAk ) == 1 && countKeys( rootAk ) == 1,
             "押し直すと欠けている方にだけ配って自己修復する" );
    }

    // (o) ★ issue #1012: **接続案内どおり打って通る形になっているか**。
    //   実害: 案内に `-i` が無いため、既定の名前 (~/.ssh/id_ed25519 等) の鍵を
    //   持たない利用者は「提示する鍵が 1 本も無い」まま Permission denied になった。
    //   サーバ側は完全に正常なので、原因に辿り着くのに時間がかかる。
    {
      File h3 = new File( tmp, "dist3" );
      new File( h3, "rootfs/root/.ssh" ).mkdirs();
      new File( h3, "rootfs/etc" ).mkdirs();
      java.nio.file.Files.write( new File( h3, "rootfs/etc/emulin-user" ).toPath(),
                                 "kiyoka\n".getBytes( "UTF-8" ) );

      // ★ 鍵が **1 本も登録されていない**とき、通らないと分かっているコマンドを出さない。
      //   出すと「案内どおりなのに入れない」になり、利用者はサーバ側を疑う。
      java.util.List<String> none = new SshdService( h3 ).connectHints();
      boolean noCmd = true;
      for( String s : none ) if( s.contains( "ssh " ) && s.contains( "@" ) ) noCmd = false;
      check( noCmd && none.toString().contains( "Add public key" ),
             "鍵が未登録なら、通らないコマンドではなく Add public key を案内する" );

      // 偽の home に鍵ペアを置き、find() に拾わせる
      File fakeHome = new File( tmp, "home3" );
      File sshDir   = new File( fakeHome, ".ssh" );
      sshDir.mkdirs();
      File pub  = new File( sshDir, "mykey.pub" );
      File priv = new File( sshDir, "mykey" );
      java.nio.file.Files.write( pub.toPath(),
          ( "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL1Zx5Q0Nq0iH6h7oPWQ0m3n5H1cQ9y1YvC0dW8kK2pQ"
          + " me@here\n" ).getBytes( "UTF-8" ) );
      java.nio.file.Files.write( priv.toPath(), "dummy\n".getBytes( "UTF-8" ) );

      String savedHome = System.getProperty( "user.home" );
      System.setProperty( "user.home", fakeHome.getPath() );
      try {
        SshKeys.install( h3, SshKeys.parse( pub, "test" ) );
        java.util.List<String> hints = new SshdService( h3 ).connectHints();
        System.out.println( "  hints -> " + hints );
        boolean withI = false, cmds = false;
        for( String s : hints ) {
          if( s.contains( "@127.0.0.1" ) ) {
            cmds = true;
            if( s.contains( "-i " ) && s.contains( priv.getPath() ) ) withI = true;
          }
        }
        check( cmds, "鍵が登録されていれば接続コマンドを案内する" );
        // ★ ここが #1012 の本体。`-i` と **登録した鍵の実パス**が載ること。
        check( withI, "案内に -i と登録した鍵のパスが載る (issue #1012)" );

        // ★ **秘密鍵が無いときに .pub を勧めない**。無い鍵を勧めると却って迷わせる。
        check( SshKeys.privateKeyOf( SshKeys.parse( pub, "t" ) ) != null, "秘密鍵を見つける" );
        priv.delete();
        check( SshKeys.privateKeyOf( SshKeys.parse( pub, "t" ) ) == null,
               "秘密鍵が無ければ null (.pub を -i に勧めない)" );
      } finally {
        if( savedHome != null ) System.setProperty( "user.home", savedHome );
      }
    }

    // (p) ★ issue #1012: WSL の行には **WSL から見た path** を載せる。
    //   Windows の path をそのまま載せても WSL の ssh からは使えない。
    check( "/home/me/.ssh/key".equals(
               SshKeys.toWslPath( "\\\\wsl.localhost\\Debian\\home\\me\\.ssh\\key" ) ),
           "\\\\wsl.localhost\\<distro>\\... を WSL の path に直す" );
    check( "/mnt/c/Users/x/.ssh/key".equals(
               SshKeys.toWslPath( "C:\\Users\\x\\.ssh\\key" ) ),
           "C:\\... を /mnt/c/... に直す" );
    check( SshKeys.toWslPath( "/already/unix" ) == null, "変換できないものは null" );
    check( "\"C:\\a b\\k\"".equals( SshKeys.quoteArg( "C:\\a b\\k" ) ),
           "空白を含む path は括る" );
    // ★ 文面の全分岐を **純粋関数**で覆う。connectHints 経由では test 環境で
    //   Windows 形式の鍵 path を作れず、WSL 行の分岐に到達できなかった
    //   (負のコントロールで判明: -i を消しても緑のままだった)。
    {
      File win = new File( "C:\\Users\\x\\.ssh\\key" );

      // 鍵あり + 秘密鍵あり + WSL の IP が分かる
      java.util.List<String> h = SshdService.hintLines( 2222, "kiyoka", true, "", win, "172.25.144.1" );
      String all = String.join( "\n", h );
      check( all.contains( "ssh -i \"C:\\Users\\x\\.ssh\\key\" -p 2222 root@127.0.0.1" )
          || all.contains( "ssh -i C:\\Users\\x\\.ssh\\key -p 2222 root@127.0.0.1" ),
             "127.0.0.1 の行に -i と Windows の path が載る" );
      check( all.contains( "-i /mnt/c/Users/x/.ssh/key" ),
             "WSL の行には **WSL から見た path** が載る (issue #1012)" );
      check( all.contains( "@172.25.144.1" ), "WSL の行は gateway の IP を使う" );

      // WSL の IP が分からないとき (else 側) も -i が載る
      String all2 = String.join( "\n",
          SshdService.hintLines( 2222, "kiyoka", true, "", win, null ) );
      check( all2.contains( "-i /mnt/c/Users/x/.ssh/key" ),
             "IP を確定できない案内でも WSL 用の -i が載る" );

      // 鍵は登録済みだが秘密鍵が host に無い → 指紋を出し、-i は付けない
      java.util.List<String> h3 = SshdService.hintLines( 2222, "kiyoka", true, "SHA256:abc",
                                                        null, "10.0.0.1" );
      boolean cmdHasI = false;
      for( String s : h3 ) if( s.trim().startsWith( "ssh " ) && s.contains( "-i " ) ) cmdHasI = true;
      // ★ 案内文の方には "(add -i <private key>)" と書いてよい。見るのは **コマンド行**だけ。
      //   最初ここを行を分けずに見ていて、自分の検査の方が誤っていた。
      check( !cmdHasI && String.join( "\n", h3 ).contains( "SHA256:abc" ),
             "秘密鍵が無いときは指紋を出し、コマンド行には -i を付けない" );

      // 鍵が 1 本も無い → **コマンドを出さない**
      String all4 = String.join( "\n",
          SshdService.hintLines( 2222, "kiyoka", false, "", null, "10.0.0.1" ) );
      check( !all4.contains( "@127.0.0.1" ) && !all4.contains( "@10.0.0.1" )
             && all4.contains( "Add public key" ),
             "鍵が未登録なら通らないコマンドを出さない" );

      // 非 root ユーザーが居ないときも root の行は出る
      String all5 = String.join( "\n",
          SshdService.hintLines( 2222, null, true, "", win, null ) );
      check( all5.contains( "root@127.0.0.1" ), "非 root ユーザーが無くても root の行は出る" );
    }

    if( failures == 0 ) { System.out.println( "SshKeys smoke OK" ); System.exit( 0 ); }
    System.out.println( "SshKeys smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }

  private static long countKeys( File f ) throws Exception {
    if( !f.isFile() ) return 0;
    long n = 0;
    for( String l : new String( java.nio.file.Files.readAllBytes( f.toPath() ), "UTF-8" ).split( "\\R" ) )
      if( !l.trim().isEmpty() ) n++;
    return n;
  }
}
