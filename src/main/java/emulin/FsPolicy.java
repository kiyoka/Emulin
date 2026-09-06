package emulin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// --------------------------------------------------------------------
//  FsPolicy — guest が触れてよい **host パス** を allowlist で制限する (issue #732)
//
//  ★ なぜエミュレータ層なのか: AI エージェントは任意コマンドを実行する。guest 内部の
//    sandbox (bwrap 等) は guest 自身が回避できるので安全対策にならない (#497)。
//    Emulin は**全 file syscall を仲介する**ので、ここで弾けば **どんなバイナリでも
//    回避できない**。
//
//  ★ 判定は **host パス**で行う。guest パスで判定すると、allowlist 内から外を指す
//    symlink で抜けられる。Mount.get_native_path は symlink を解決済みの host パスを
//    返すので、その値を見る。さらに `..` を潰すため canonical 化する。
//
//  ★ **既定は無制限**。env が未設定なら boolean 1 個の判定で素通しし、既存利用者の
//    挙動も hot path のコストも変えない。
//
//  設定 (host 側の env):
//    EMULIN_FS_ALLOW=<host path>[;<host path>...]
//        これを設定すると **allowlist モード**になる。rootfs 配下と、ここに挙げた
//        path 配下だけが見える。それ以外は「存在しない」として扱う (ENOENT)。
//    EMULIN_FS_DENY=<host path>[;<host path>...]
//        個別に塞ぐ。**allow より優先**する (deny-first)。
//
//  ★ deny の返し方は **ENOENT (存在しないものとして扱う)**。EACCES だと「そこに何かは
//    ある」ことを教えてしまう。issue はどちらでもよいとしているので、漏らさない方を採る。
// --------------------------------------------------------------------
public final class FsPolicy {

  private FsPolicy() { }

  /** ★ Windows の DrvFs は大文字小文字を区別しない。区別する実装にすると
   *  `/mnt/C/...` で allowlist をすり抜ける。host が Windows なら畳んで比べる。 */
  private static final boolean FOLD_CASE =
      System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT ).startsWith( "windows" );

  private static final List<String> ALLOW = parse( System.getenv( "EMULIN_FS_ALLOW" ) );
  private static final List<String> DENY  = parse( System.getenv( "EMULIN_FS_DENY" ) );
  /** rootfs 配下は常に許可する (ここを塞ぐと guest が 1 命令も動かない)。 */
  private static volatile String rootfs = null;

  /** ★ 有効かどうかを 1 個の boolean にしておく。既定 (未設定) では以降の処理を
   *  一切しない — hot path (openat/stat) に判定が乗るのを避ける。 */
  public static final boolean ENABLED = !ALLOW.isEmpty() || !DENY.isEmpty();

  /** rootfs の host パスを教える (Mount が決まった時点で 1 回)。 */
  public static void setRootfs( String hostPath ) {
    if( hostPath != null && !hostPath.isEmpty() ) rootfs = norm( canon( hostPath ) );
  }

  private static List<String> parse( String v ) {
    List<String> out = new ArrayList<>();
    if( v == null ) return out;
    for( String s : v.split( "[;" + File.pathSeparator + "]" ) ) {
      String t = s.trim();
      if( !t.isEmpty() ) out.add( norm( canon( t ) ) );
    }
    return out;
  }

  /** `..` と symlink を潰した絶対パス。解決できなければ入力をそのまま返す
   *  (存在しない path も判定対象なので、失敗を許可に倒さない)。 */
  private static String canon( String p ) {
    try { return new File( p ).getCanonicalPath(); }
    catch( Exception e ) {
      try { return new File( p ).getAbsolutePath(); } catch( Exception e2 ) { return p; }
    }
  }

  private static String norm( String p ) {
    if( p == null ) return "";
    String t = p.replace( '\\', '/' );
    while( t.length() > 1 && t.endsWith( "/" ) ) t = t.substring( 0, t.length() - 1 );
    return FOLD_CASE ? t.toLowerCase( Locale.ROOT ) : t;
  }

  /** ★ **境界で比べる**。素の startsWith だと prefix `/work` が `/workspace-secret` に
   *  一致してしまう (許可したつもりのない場所が開く)。 */
  private static boolean under( String path, String prefix ) {
    if( prefix.isEmpty() ) return false;
    if( path.equals( prefix ) ) return true;
    return path.startsWith( prefix ) && path.length() > prefix.length()
           && path.charAt( prefix.length() ) == '/';
  }

  /** この host パスに guest が触れてよいか。★ 判定は **symlink 解決後の host パス**で。 */
  public static boolean allowed( String hostPath ) {
    if( !ENABLED ) return true;
    if( hostPath == null || hostPath.isEmpty() ) return true;
    // 合成パス (<stdin> 等) は実ファイルではないので対象外。
    if( hostPath.charAt( 0 ) == '<' ) return true;
    String p = norm( canon( hostPath ) );
    for( String d : DENY ) if( under( p, d ) ) return false;   // deny が最優先
    if( ALLOW.isEmpty() ) return true;                          // deny だけの運用
    String r = rootfs;
    if( r != null && under( p, r ) ) return true;               // rootfs 配下は常に許可
    for( String a : ALLOW ) if( under( p, a ) ) return true;
    return false;
  }

  /** 画面に出す 1 行 (起動時の案内用)。無効なら null。 */
  public static String describe() {
    if( !ENABLED ) return null;
    StringBuilder b = new StringBuilder( "[sandbox] filesystem policy: " );
    if( !ALLOW.isEmpty() ) b.append( "allow=" ).append( String.join( " ", ALLOW ) ).append( ' ' );
    if( !DENY.isEmpty() )  b.append( "deny=" ).append( String.join( " ", DENY ) ).append( ' ' );
    b.append( "(rootfs is always allowed; anything else is reported as not existing)" );
    return b.toString();
  }
}
