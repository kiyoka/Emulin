package emulin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// --------------------------------------------------------------------
//  FsAllow — ランチャーが持つ「guest に見せる host パス」の設定 (issue #1046)
//
//  #732 の allowlist (`EMULIN_FS_ALLOW`) は env でしか設定できなかった。ここに保存して
//  **起こす guest 全部に同じ値を渡す**ことで、env を知らなくても制限をかけられるようにする。
//
//  ★ **ここは保存と適用だけ。画面は FsAllowDialog。** 保存を UI 側に書くと
//    「CLI では書くのに UI では書かない」型がすぐ入る (#968 で決めた取り決め)。
//
//  ★ **判定規則 (境界一致) はここに書かない。** `FsPolicy.covered` を呼ぶ。
//    別実装を置くと `/work` が `/work-secret` に一致する型の食い違いがここだけ復活する。
//
//  ★ **起動口は 2 系統ある** (#919 / #963 / #985 と同じ形):
//      - `GuestLaunch.builder(...)`        … X 端末 / sshd / apt 等のジョブ
//      - `LauncherApp.terminalBuilder(...)` … Open terminal (emulin.bat 経由)
//    どちらからも `apply()` を呼ぶ。**1 つでも呼び忘れると、そこだけ無制限の guest が
//    起きる。** FsAllowSmoke が両方を実際に組み立てて確かめる。
//
//  ★ 空のときに env を**消さない**。host の env で `EMULIN_FS_ALLOW` が設定されている
//    場合、消すと**制限が外れる方向**に倒れる。設定が空なら host の指定をそのまま通し、
//    画面にはそう出す (fail-closed)。設定があればそちらが優先。
// --------------------------------------------------------------------
public final class FsAllow {

  private FsAllow() { }

  /** guest 側 (FsPolicy) が読む env 名。★ 綴りを 2 箇所に書かない。 */
  public static final String ENV = "EMULIN_FS_ALLOW";

  /** 区切り。★ guest 側 (FsPolicy.parse) と同じ文字でなければならない。 */
  public static final char SEP = ';';

  /** 保存先。★ `~/.emulin` は credential と同じ場所で、導出は Egress に集約されている。 */
  public static File configFile() { return new File( Egress.emulinDir(), "fs-allow.txt" ); }

  /** 保存できない値。★ 区切り文字が入ると env の分解がずれて**別の場所が許可される**。 */
  public static boolean invalid( String entry ) {
    if( entry == null ) return true;
    String t = entry.trim();
    return t.isEmpty() || t.indexOf( SEP ) >= 0 || t.indexOf( '\n' ) >= 0;
  }

  /** 設定を読む。ファイルが無ければ空 (= 制限なし)。`#` 始まりと空行は無視。 */
  public static List<String> load() {
    List<String> out = new ArrayList<>();
    File f = configFile();
    if( !f.isFile() ) return out;
    try {
      for( String line : Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ) ) {
        String t = line.trim();
        if( t.isEmpty() || t.charAt( 0 ) == '#' ) continue;
        if( !invalid( t ) && !out.contains( t ) ) out.add( t );
      }
    } catch( IOException e ) { /* 読めなければ「制限なし」ではなく空扱い (画面が出す) */ }
    return out;
  }

  /** 設定を書く。重複は畳む。★ 親 dir が無ければ作る。 */
  public static void save( List<String> entries ) throws IOException {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    if( entries != null ) for( String e : entries ) if( !invalid( e ) ) set.add( e.trim() );
    File f = configFile();
    File dir = f.getParentFile();
    if( dir != null && !dir.isDirectory() ) dir.mkdirs();
    StringBuilder b = new StringBuilder();
    b.append( "# emulin: guest に見せる host パス (issue #732 / #1046)\n" );
    b.append( "#   1 行 1 パス。空なら制限なし (guest は host を自由に見られる)。\n" );
    for( String e : set ) b.append( e ).append( '\n' );
    Files.write( f.toPath(), b.toString().getBytes( StandardCharsets.UTF_8 ) );
  }

  /** env に入れる値。空なら null (= 設定していない)。 */
  public static String envValue( List<String> entries ) {
    if( entries == null || entries.isEmpty() ) return null;
    StringBuilder b = new StringBuilder();
    for( String e : entries ) {
      if( invalid( e ) ) continue;
      if( b.length() > 0 ) b.append( SEP );
      b.append( e.trim() );
    }
    return ( b.length() == 0 ) ? null : b.toString();
  }

  /** ★ guest を起こす **すべての** ProcessBuilder の env に適用する。
   *  設定が空なら何もしない (host の env による指定を消さない = 制限を外さない)。 */
  public static void apply( Map<String,String> env ) {
    if( env == null ) return;
    String v = envValue( load() );
    if( v != null ) env.put( ENV, v );
  }

  /** host の env による指定 (設定が空のときに効いているもの)。無ければ null。 */
  public static String inheritedEnv() {
    String v = System.getenv( ENV );
    return ( v == null || v.trim().isEmpty() ) ? null : v;
  }

  /** いま guest に渡る値 (設定 → 無ければ host の env)。制限なしなら null。 */
  public static String effective() {
    String v = envValue( load() );
    return ( v != null ) ? v : inheritedEnv();
  }

  /** ★ **設定ファイル自身が許可範囲に入っていないか。** 入っていると guest が
   *  `~/.emulin/fs-allow.txt` を書き換えて、**次に起こす guest の制限を自分で広げられる**。
   *  画面で警告するために使う。 */
  public static boolean selfExposed( List<String> entries ) {
    if( entries == null || entries.isEmpty() ) return false;
    return FsPolicy.covered( configFile().getAbsolutePath(), entries );
  }
}
