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
//  設定は **env 1 個・リスト 1 本**だけ:
//
//    EMULIN_FS_ALLOW=<path>[;<path>...]
//        未設定  … 従来どおり無制限 (既存利用者の挙動を変えない)
//        設定    … **rootfs と、ここに書いた場所だけ**が見える。
//                  それ以外の host は「存在しない」として扱う (ENOENT)。
//
//  ★ **deny リストは置かない。** deny は「書き忘れ = 見える」に倒れる。host の秘密
//    (~/.ssh, ~/.aws, 他リポジトリの .env, ブラウザプロファイル …) を列挙し切ることは
//    原理的に不可能なので、deny を境界と思って運用すると必ず穴が開く。allow なら
//    「書き忘れ = 見えない」に倒れる。「一部だけ隠す」は **allow を狭く書く**で表す。
//    (2026-09-13: allow/deny 2 本立てから allow 一本化。deny は rootfs まで巻き込めて
//     guest が自分自身を見失う非対称もあった。)
//
//  ★ 書く値は **guest から見える名前でも host の名前でもよい**。起動時に mount 表で
//    host パスへ変換し、両方を許可 prefix にする。Windows でも WSL でも
//    `EMULIN_FS_ALLOW=/mnt/c/dev/EmulinDev` と書けば通る。
//
//  ★ 変換は **起動時に 1 回だけ行い、そこで凍結する** (freeze)。凍結しないと、guest が
//    `mount(2)` で許可された名前に別の host dir を載せ替えて allowlist を広げられる
//    (mount の src は host パスなので、guest は任意の host dir を持ち込める)。
//    **露出集合は guest が動き出す前に確定していなければ境界にならない。**
//
//  ★ 判定は **symlink 解決後の host パス**で行う。guest パスで判定すると、許可ゾーン内から
//    外を指す symlink や `..` で抜けられる。Mount.get_native_path は解決済みの host パスを
//    返すので、その値を canonical 化して見る。
//
//  ★ 拒否は **ENOENT (存在しないものとして扱う)**。EACCES だと「そこに何かはある」ことを
//    教えてしまう。issue はどちらでもよいとしているので、漏らさない方を採る。
// --------------------------------------------------------------------
public final class FsPolicy {

  private FsPolicy() { }

  /** ★ Windows の DrvFs は大文字小文字を区別しない。区別する実装にすると
   *  `/mnt/C/...` で allowlist をすり抜ける。host が Windows なら畳んで比べる。 */
  private static final boolean FOLD_CASE =
      System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT ).startsWith( "windows" );

  /** env の生値 (guest 表記 / host 表記のどちらでもよい)。 */
  private static final List<String> RAW = parse( System.getenv( "EMULIN_FS_ALLOW" ) );

  /** ★ 有効かどうかを 1 個の boolean にしておく。既定 (未設定) では以降の処理を
   *  一切しない — hot path (openat/stat) に判定が乗るのを避ける。 */
  public static final boolean ENABLED = !RAW.isEmpty();

  /** freeze() で確定する許可 host prefix。null = まだ凍結していない (= 起動途中)。 */
  private static volatile List<String> allowHost = null;
  /** rootfs の host パス。★ここを塞ぐと guest が 1 命令も動かないので常に許可する。 */
  private static volatile String rootfs = "";

  // ------------------------------------------------------------------
  /** ★ 起動時に 1 回だけ呼ぶ (mount 表と rootfs が確定した後、guest が動き出す前)。
   *  ここで guest 表記 → host パスの変換を済ませて**凍結**する。以後 guest が
   *  mount(2) で何をしても許可集合は広がらない。2 回目以降は無視する。 */
  public static synchronized void freeze( Mount mount ) {
    if( !ENABLED || allowHost != null ) return;
    if( mount != null && mount.root != null ) rootfs = norm( canon( mount.root ) );
    List<String> out = new ArrayList<>();
    for( String e : RAW ) {
      add( out, canon( e ) );                       // (a) host 表記としてそのまま
      if( mount != null && e.startsWith( "/" ) ) {  // (b) guest 表記 → mount 表で host へ
        try {
          String np = mount.get_native_path( e );
          // mount に載っていない guest path は rootfs 配下に落ちる。rootfs は元から
          //   常に許可なので、prefix として足す意味は無い (足すと describe が濁る)。
          if( np != null && !np.isEmpty() && !under( norm( canon( np ) ), rootfs ) )
            add( out, canon( np ) );
        } catch( Throwable t ) { /* 解決不能な指定は (a) だけで扱う */ }
      }
    }
    allowHost = out;
  }

  private static void add( List<String> out, String p ) {
    String n = norm( p );
    if( !n.isEmpty() && !out.contains( n ) ) out.add( n );
  }

  private static List<String> parse( String v ) {
    List<String> out = new ArrayList<>();
    if( v == null ) return out;
    for( String s : v.split( "[;" + File.pathSeparator + "]" ) ) {
      String t = s.trim();
      if( !t.isEmpty() ) out.add( t );
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
    if( prefix == null || prefix.isEmpty() ) return false;
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
    List<String> list = allowHost;
    // ★ 凍結前 = まだ guest が動き出していない emulator 自身の起動処理。ここで塞ぐと
    //   rootfs を読めず起動できない。guest 実行前に freeze() を呼ぶのが前提。
    if( list == null ) return true;
    String p = norm( canon( hostPath ) );
    if( under( p, rootfs ) ) return true;
    for( String a : list ) if( under( p, a ) ) return true;
    return false;
  }

  /** 画面に出す 1 行 (起動時の案内用)。無効なら null。
   *  ★ 効いていることが**見えない**と、設定をタイプミスしても無制限のまま気付けない。 */
  public static String describe() {
    if( !ENABLED ) return null;
    List<String> list = allowHost;
    StringBuilder b = new StringBuilder( "[sandbox] filesystem policy: allow=" );
    b.append( String.join( " ", ( list == null ) ? RAW : list ) );
    b.append( " (rootfs is always allowed; anything else is reported as not existing)" );
    return b.toString();
  }
}
