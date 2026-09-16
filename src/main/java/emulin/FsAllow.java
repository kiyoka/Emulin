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
//  ★ **読めなかったら閉じる側に倒す** (2026-09-16)。「無い」= 制限なし、「在るのに読めない」
//    = **rootfs 以外すべて不可**。以前は両方とも空リストになり、設定ファイルが壊れただけで
//    **制限が黙って外れていた** (実機でも再現: cp932 で保存された日本語パス / dir)。
//    緩む方向に倒れる規則をここに置かない。判定は `FsPolicy` 側。
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

  /** 設定の読み取り結果。
   *
   *  ★ **「設定ファイルが無い」と「在るのに読めない」を混ぜない。** 混ぜると読めなかった
   *    時に空 = 無制限へ倒れ、**制限が黙って外れる** (fail-open)。この機能の前提は
   *    「渡し忘れたら外れる形にしてはいけない」なので、読めなかったら**閉じる**側に倒す。
   *    実際に起きる: 日本語のフォルダを許可した設定を cp932 のエディタで保存すると
   *    UTF-8 として読めなくなり、**同じファイルに書いてある正常な行まで全部落ちる**。 */
  public static final class Config {
    /** 読めた許可パス。読めなかったときは空。 */
    public final List<String> entries;
    /** null = 正常。非 null = **設定ファイルは在るのに読めなかった**理由 (画面に出す)。 */
    public final String error;
    Config( List<String> entries, String error ) { this.entries = entries; this.error = error; }
    /** ★ true なら「制限なし」ではなく **rootfs 以外すべて不可** として扱う。 */
    public boolean unreadable() { return error != null; }
  }

  /** 「在るのに読めなかった」を表す結果。呼び出し側が例外を握ったときの受け皿。 */
  public static Config unreadable( String why ) {
    return new Config( new ArrayList<>(), ( why == null ) ? "unreadable" : why );
  }

  /** 設定を読む。ファイルが無ければ空 (= 従来どおり制限なし)。`#` 始まりと空行は無視。
   *
   *  ★ **`load()` は置かない。** 「List を返すだけ」の入口があると、読めなかった事を
   *    捨てる呼び出しがまた生える。呼ぶ側に必ず `error` を見せる。 */
  public static Config read() {
    List<String> out = new ArrayList<>();
    File f;
    try { f = configFile(); }
    catch( Throwable t ) { return new Config( out, "cannot locate the config file: " + t ); }

    // 無い = 設定していない。ここだけが「制限なし」。
    // ★ 限界: `exists()` は「無い」と「見に行けない」(到達できない network home 等) を
    //   区別できないので、その場合は無制限のままになる。credential も同じ dir なので
    //   そこが見えない時点で環境が壊れているが、**ここは境界ではない**と知っておく。
    if( !f.exists() ) return new Config( out, null );
    // 在るのに通常ファイルでない (dir など) は、読めなかったのと同じに扱う。
    if( !f.isFile() ) return new Config( out, "not a regular file" );

    List<String> lines;
    try { lines = Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 ); }
    catch( IOException | RuntimeException e ) {
      // ★ 不正バイト列 (MalformedInputException) / 権限 / 排他ロックなど。
      return new Config( out, String.valueOf( e ) );
    }
    for( int i = 0; i < lines.size(); i++ ) {
      String raw = lines.get( i );
      // ★ **先頭行の BOM を剥がす。** Windows のメモ帳などが付ける `\uFEFF` が残ると、
      //   1 行目が `#` 始まりでなくなり **ヘッダのコメント行がそのまま許可エントリになる**
      //   (実機で確認: `allow=\uFEFF# emulin C:\dev\...`)。trim() は BOM を落とさない。
      if( i == 0 && !raw.isEmpty() && raw.charAt( 0 ) == '\uFEFF' ) raw = raw.substring( 1 );
      String t = raw.trim();
      if( t.isEmpty() || t.charAt( 0 ) == '#' ) continue;
      if( !invalid( t ) && !out.contains( t ) ) out.add( t );
    }
    return new Config( out, null );
  }

  /** 設定を書く。重複は畳む。★ 親 dir が無ければ作る。
   *
   *  ★ **読めない設定ファイルは黙って上書きしない。** 読めないと一覧は空に見えるので、
   *    そこで 1 件足すと「残っていた許可を全部捨てて 1 件だけ」に書き換わる。利用者は
   *    足したつもりで**設定を失う**。直す場所を言って断る。 */
  public static void save( List<String> entries ) throws IOException {
    Config cur = read();
    if( cur.unreadable() )
      throw new IOException( "refusing to overwrite " + configFile().getPath()
          + " - it exists but cannot be read (" + cur.error
          + "). Fix or delete that file first." );
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
