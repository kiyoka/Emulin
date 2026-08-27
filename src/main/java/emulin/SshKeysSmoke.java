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
