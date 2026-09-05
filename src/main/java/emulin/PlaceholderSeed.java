package emulin;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

// --------------------------------------------------------------------
//  PlaceholderSeed — placeholder を rootfs ごとに固定するための seed (issue #955)
//
//  ★ 何を解決するか: 以前は placeholder を **起動ごとに** SecureRandom で作り直して
//    いた。同じ rootfs で 2 つ目の Emulin が起動すると guest 内の credential ファイルが
//    新しい placeholder で上書きされ、**先に動いていたセッション**の MITM は自分が
//    知らない値を受け取る。swap できないので素通しして 401 になり、claude は
//    "Login expired" と言う。**壊れるのは操作した側ではなく、動いていた側**で、
//    警告も出ないため原因に辿り着けない (実運用で繰り返し踏んだ)。
//
//  ★ seed を rootfs ごとにファイルへ保存して使い回せば、同時に動く複数の Emulin が
//    **同じ placeholder** を使うので、この壊れ方が消える。
//
//  ★ placeholder は秘密ではない。MITM の外では無価値で、guest には元から見えている
//    (guest のファイルに書き込むのが仕事)。毎回変える必要は元々無かった。
//    とはいえ seed ファイル自体は他人に見せる意味が無いので 0600 に寄せる。
//
//  ★ 保存先を rootfs の中にしない。guest から読めてしまうし、rootfs を作り直すと
//    消える。host 側の ~/.emulin/seeds/ に置く (instances/ ports/ と同じ並び)。
// --------------------------------------------------------------------
public final class PlaceholderSeed {

  private PlaceholderSeed() { }

  /** seed 32 バイトと、JWT の基準時刻 (epoch 秒)。 */
  public static final class Seed {
    public final byte[] bytes;
    public final long   iat;
    Seed( byte[] b, long i ) { bytes = b; iat = i; }
  }

  /** rootfs に対応する seed を返す。無ければ作って保存する。
   *  取得できなければ null (呼び出し側は従来どおり毎回ランダムで動く)。 */
  public static Seed forRootfs( File emulinDir, String rootfsPath ) {
    if( emulinDir == null || rootfsPath == null || rootfsPath.isEmpty() ) return null;
    try {
      // ★ canonical にして比べる。symlink / junction 越しに同じ rootfs を掴む形が
      //   実際にあり (#955 の実害)、生の文字列で分けると seed が分かれて意味が無くなる。
      String key = InstanceRegistry.canon( rootfsPath );
      File dir = new File( emulinDir, "seeds" );
      if( !dir.isDirectory() && !dir.mkdirs() ) return null;
      File f = new File( dir, sha256hex( key ).substring( 0, 32 ) + ".txt" );
      Seed s = read( f );
      if( s != null ) return s;
      byte[] b = new byte[32];
      new SecureRandom().nextBytes( b );
      long iat = System.currentTimeMillis() / 1000L;
      write( f, key, b, iat );
      return new Seed( b, iat );
    } catch( Exception e ) {
      return null;
    }
  }

  private static Seed read( File f ) {
    if( !f.isFile() ) return null;
    try {
      String txt = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                               java.nio.charset.StandardCharsets.UTF_8 );
      String seed = null; long iat = 0;
      for( String line : txt.split( "\\R" ) ) {
        int i = line.indexOf( '=' );
        if( i <= 0 ) continue;
        String k = line.substring( 0, i ).trim(), v = line.substring( i + 1 ).trim();
        if( k.equals( "seed" ) ) seed = v;
        else if( k.equals( "iat" ) ) try { iat = Long.parseLong( v ); } catch( Exception ignore ) { }
      }
      // ★ 壊れた / 途中まで書けたファイルは**使わない**。null を返せば作り直される。
      //   中途半端な seed で動くと、起動ごとに placeholder が変わる元の症状に静かに戻る。
      if( seed == null || seed.length() < 32 || iat <= 0 ) return null;
      return new Seed( unhex( seed ), iat );
    } catch( Exception e ) { return null; }
  }

  private static void write( File f, String rootfs, byte[] seed, long iat ) throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append( "version=1\n" )
      .append( "rootfs=" ).append( rootfs ).append( '\n' )
      .append( "seed=" ).append( hex( seed ) ).append( '\n' )
      .append( "iat=" ).append( iat ).append( '\n' );
    java.nio.file.Files.write( f.toPath(),
        sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    try {
      f.setReadable( false, false ); f.setReadable( true, true );
      f.setWritable( false, false ); f.setWritable( true, true );
    } catch( Exception ignore ) { }
  }

  static String sha256hex( String s ) throws Exception {
    MessageDigest md = MessageDigest.getInstance( "SHA-256" );
    return hex( md.digest( s.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) );
  }

  static String hex( byte[] b ) {
    StringBuilder sb = new StringBuilder();
    for( byte x : b ) sb.append( Character.forDigit( ( x >> 4 ) & 0xF, 16 ) )
                        .append( Character.forDigit( x & 0xF, 16 ) );
    return sb.toString();
  }

  static byte[] unhex( String s ) {
    String t = s.trim().toLowerCase( Locale.ROOT );
    byte[] out = new byte[t.length() / 2];
    for( int i = 0; i < out.length; i++ )
      out[i] = (byte) Integer.parseInt( t.substring( i * 2, i * 2 + 2 ), 16 );
    return out;
  }
}
