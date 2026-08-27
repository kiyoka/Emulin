package emulin;

import java.io.File;
import java.util.*;

// --------------------------------------------------------------------
//  SshKeys — sshd 用の公開鍵を host から探して guest に登録する (issue #964)
//
//  ★ 一番の危険は **秘密鍵を登録してしまうこと**。
//    `~/.ssh` には id_ed25519 (秘密鍵) と id_ed25519.pub (公開鍵) が並んでいる。
//    秘密鍵を guest に書き込むと **#401 の不変条件 (実 credential は host 側にのみ置く) が
//    真っ向から破れる**。拡張子だけで判断せず、**中身を見て**判定する。
//
//  ★ 探す場所は Windows と WSL の**両方**。ランチャーは Windows の GUI アプリなので
//    user.home は C:\Users\<name> になるが、鍵を作ったのは WSL の ~/.ssh かもしれない。
//    setcred が Claude のログインを探すときに踏んだのと同じ罠 (#935)。
// --------------------------------------------------------------------
public final class SshKeys {

  private SshKeys() { }

  /** OpenSSH の公開鍵として認める種別。 */
  private static final String[] TYPES = {
      "ssh-ed25519", "ssh-rsa", "ssh-dss",
      "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
      "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
  };

  public static final class PubKey {
    public File   path;
    public String origin = "";      // どこで見つけたか (Windows / WSL Debian など)
    public String type = "";
    public String comment = "";
    public String fingerprint = "";
    public String line = "";        // authorized_keys に書く 1 行
    @Override public String toString() {
      return type + "  " + fingerprint + "  " + ( comment.isEmpty() ? "(コメント無し)" : comment );
    }
  }

  /** 秘密鍵など、公開鍵でないものを弾く。問題があれば理由を返す (OK なら null)。 */
  public static String rejectReason( String text ) {
    if( text == null ) return "ファイルを読めません";
    String t = text.trim();
    if( t.isEmpty() ) return "中身が空です";
    // ★ 拡張子ではなく中身で判定する。.pub という名前の秘密鍵もあり得る。
    if( t.startsWith( "-----BEGIN" ) || t.contains( "PRIVATE KEY" ) )
      return "これは**秘密鍵**です。guest には絶対に置けません (.pub の方を選んでください)";
    String first = t.split( "\\R", 2 )[0].trim();
    for( String ty : TYPES ) if( first.startsWith( ty + " " ) ) return null;
    return "OpenSSH の公開鍵に見えません (先頭が " + TYPES[0] + " 等ではない)";
  }

  /** 公開鍵 1 行を読み取る。公開鍵でなければ null。 */
  public static PubKey parse( File f, String origin ) {
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                                java.nio.charset.StandardCharsets.UTF_8 );
      if( rejectReason( text ) != null ) return null;
      String line = text.split( "\\R", 2 )[0].trim();
      String[] p = line.split( "\\s+", 3 );
      PubKey k = new PubKey();
      k.path = f;
      k.origin = origin;
      k.line = line;
      k.type = p[0];
      k.comment = ( p.length >= 3 ) ? p[2] : "";
      k.fingerprint = fingerprint( p.length >= 2 ? p[1] : "" );
      return k;
    } catch( Exception e ) { return null; }
  }

  /** OpenSSH と同じ SHA256 fingerprint (`SHA256:...`、パディング無し)。
   *  ★ 鍵は見た目が似ているので、これが無いと取り違える。 */
  public static String fingerprint( String base64Blob ) {
    try {
      byte[] raw = Base64.getDecoder().decode( base64Blob );
      byte[] h = java.security.MessageDigest.getInstance( "SHA-256" ).digest( raw );
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString( h );
    } catch( Exception e ) { return "(fingerprint 不明)"; }
  }

  /** host 側にある公開鍵を探す (Windows のホーム + WSL の各ディストリ)。 */
  public static List<PubKey> find() {
    LinkedHashMap<String,PubKey> out = new LinkedHashMap<>();   // fingerprint で重複排除
    File winHome = new File( System.getProperty( "user.home", "." ) );
    collect( new File( winHome, ".ssh" ), "このホーム", out );
    for( String d : SetCred.wslDistros() ) {
      File base = new File( "\\\\wsl.localhost\\" + d + "\\home" );
      File[] users = base.listFiles();
      if( users != null )
        for( File u : users ) collect( new File( u, ".ssh" ), "WSL " + d + " / " + u.getName(), out );
      collect( new File( "\\\\wsl.localhost\\" + d + "\\root\\.ssh" ), "WSL " + d + " / root", out );
    }
    return new ArrayList<>( out.values() );
  }

  private static void collect( File sshDir, String origin, Map<String,PubKey> out ) {
    try {
      File[] fs = sshDir.listFiles( ( d, n ) -> n.endsWith( ".pub" ) );
      if( fs == null ) return;
      Arrays.sort( fs, Comparator.comparing( File::getName ) );
      for( File f : fs ) {
        PubKey k = parse( f, origin );
        if( k != null ) out.putIfAbsent( k.fingerprint, k );
      }
    } catch( Exception ignore ) { }
  }

  /** guest の authorized_keys に既に入っている鍵の fingerprint。 */
  public static Set<String> installed( File home ) {
    Set<String> out = new LinkedHashSet<>();
    File f = authorizedKeys( home );
    if( !f.isFile() ) return out;
    try {
      for( String line : new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                                     java.nio.charset.StandardCharsets.UTF_8 ).split( "\\R" ) ) {
        String t = line.trim();
        if( t.isEmpty() || t.startsWith( "#" ) ) continue;
        String[] p = t.split( "\\s+", 3 );
        if( p.length >= 2 ) out.add( fingerprint( p[1] ) );
      }
    } catch( Exception ignore ) { }
    return out;
  }

  public static File authorizedKeys( File home ) {
    return new File( GuestLaunch.rootfs( home ), "root/.ssh/authorized_keys" );
  }

  /** 登録する。★ **冪等** — 同じ鍵が既にあれば足さない。既存の行は消さない。
   *  @return 画面に出すメッセージ */
  public static String install( File home, PubKey k ) {
    if( k == null ) return "鍵が選ばれていません";
    File f = authorizedKeys( home );
    try {
      if( installed( home ).contains( k.fingerprint ) )
        return "この鍵は既に登録済みです (" + k.fingerprint + ")";
      File dir = f.getParentFile();
      if( !dir.isDirectory() && !dir.mkdirs() ) return "ディレクトリを作れません: " + dir;
      StringBuilder sb = new StringBuilder();
      if( f.isFile() ) {
        String cur = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                                 java.nio.charset.StandardCharsets.UTF_8 );
        sb.append( cur );
        if( !cur.isEmpty() && !cur.endsWith( "\n" ) ) sb.append( '\n' );
      }
      sb.append( k.line ).append( '\n' );
      java.nio.file.Files.write( f.toPath(),
          sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
      // 実 sshd と同じ permission に寄せる (DrvFs では効かないことがある)
      try {
        dir.setReadable( false, false ); dir.setReadable( true, true );
        dir.setWritable( false, false ); dir.setWritable( true, true );
        dir.setExecutable( false, false ); dir.setExecutable( true, true );
        f.setReadable( false, false ); f.setReadable( true, true );
        f.setWritable( false, false ); f.setWritable( true, true );
      } catch( Exception ignore ) { }
      return "登録しました: " + k.type + " " + k.fingerprint
           + ( k.comment.isEmpty() ? "" : " (" + k.comment + ")" );
    } catch( Exception e ) {
      return "登録に失敗しました: " + e;
    }
  }
}
