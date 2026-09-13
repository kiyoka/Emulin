package emulin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

// --------------------------------------------------------------------
//  FsAllow — ランチャーが持つ「guest に見せる host パス」の設定 (issue #1046)
//
//  #732 の allowlist は env でしか設定できなかった。ここに保存し、**guest 側が起動時に
//  自分で読む**ことで、env を知らなくても制限をかけられるようにする。
//
//  ★ **ここは保存だけ。画面は FsAllowDialog。** 保存を UI 側に書くと
//    「CLI では書くのに UI では書かない」型がすぐ入る (#968 で決めた取り決め)。
//
//  ★ **判定規則 (境界一致) はここに書かない。** `FsPolicy.covered` を呼ぶ。
//    別実装を置くと `/work` が `/work-secret` に一致する型の食い違いがここだけ復活する。
//
//  ★ **env では渡さない** (2026-09-13)。guest 側 (`FsPolicy`) が起動時にこのファイルを
//    直接読む。ランチャーが env で渡す形は、`Open terminal` が `wt.exe` 経由で
//    **別プロセス文脈から起動し直される**と落ちて、**そこだけ無制限の guest が起きる**。
//    制限は「渡し忘れたら外れる」形にしてはいけない。credential (`credentials.json`) が
//    既にこの形で Windows のどの起動口でも効いているので、それに揃える。
// --------------------------------------------------------------------
public final class FsAllow {

  private FsAllow() { }

  /** 保存先。★ `~/.emulin` は credential と同じ場所で、導出は Egress に集約されている。 */
  public static File configFile() { return new File( Egress.emulinDir(), "fs-allow.txt" ); }

  /** 保存できない値。★ ファイルは 1 行 1 パスなので、改行を含む値だけ弾けばよい
   *  (env で渡していた頃は `;` も弾く必要があったが、その制約は無くなった)。 */
  public static boolean invalid( String entry ) {
    if( entry == null ) return true;
    String t = entry.trim();
    return t.isEmpty() || t.indexOf( '\n' ) >= 0 || t.indexOf( '\r' ) >= 0;
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

  /** ★ **設定ファイル自身が許可範囲に入っていないか。** 入っていると guest が
   *  `~/.emulin/fs-allow.txt` を書き換えて、**次に起こす guest の制限を自分で広げられる**。
   *  画面で警告するために使う。 */
  public static boolean selfExposed( List<String> entries ) {
    if( entries == null || entries.isEmpty() ) return false;
    return FsPolicy.covered( configFile().getAbsolutePath(), entries );
  }
}
