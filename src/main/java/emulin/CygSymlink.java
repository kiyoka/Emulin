// ----------------------------------------
//  CygSymlink — Cygwin 互換のマジックファイル symlink
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  issue #68: Windows native では NTFS の native symlink が admin /
//  Developer Mode を要求し、tar.exe の symlink 展開も壊れやすい。Cygwin と
//  同じく symlink を「特殊な regular file (マジックファイル)」として表現する
//  ことで、admin 不要・どの FS でも動く symlink を pure Java で実現する。
//
//  マジックファイル形式 (Cygwin 互換):
//    - 先頭 10 byte: cookie "!<symlink>"
//    - 続く 2 byte:  UTF-16LE BOM (0xFF 0xFE)
//    - 以降:          target を UTF-16LE で格納 + NUL 終端 (UTF-16 の 0x0000)
//    - file 属性:     DOS system 属性を立てる (Cygwin が symlink と認識する条件)
//
//  検出は cookie byte を見る (DrvFs 等で system 属性が保持されない場合に
//  備え、属性ではなく内容で判定)。書き込み時は属性も立てて Cygwin 互換に。
// ----------------------------------------
package emulin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;

public final class CygSymlink {

  // Cygwin のマジック cookie。
  static final byte[] COOKIE = "!<symlink>".getBytes( StandardCharsets.US_ASCII );
  // UTF-16LE BOM。
  private static final byte[] BOM = { (byte)0xFF, (byte)0xFE };

  // ------------------------------------------------------------------
  //  mode 判定
  //    - Windows host では常時 on
  //    - EMULIN_FORCE_CYGWIN_SYMLINK=1 で OS を問わず on (Linux テスト用)
  //  static final で 1 度だけ評価 (hot path から呼ばれるため)。
  // ------------------------------------------------------------------
  private static final boolean ENABLED;
  static {
    boolean win = System.getProperty( "os.name", "" )
                        .toLowerCase().startsWith( "windows" );
    boolean forced = System.getenv( "EMULIN_FORCE_CYGWIN_SYMLINK" ) != null;
    ENABLED = win || forced;
  }

  public static boolean enabled() { return ENABLED; }

  // ------------------------------------------------------------------
  //  マジックファイルとして symlink を書き込む。
  //    native_link : sandbox 内の symlink を置く host 上の絶対 path
  //    target      : symlink の指す先 (POSIX path 文字列、そのまま格納)
  //  戻り値: 成功なら true。
  // ------------------------------------------------------------------
  public static boolean write( String native_link, String target ) {
    try {
      Path p = Paths.get( native_link );
      // 既存 file があれば上書き (symlink 再作成)。
      byte[] tgt = target.getBytes( StandardCharsets.UTF_16LE );
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      bos.write( COOKIE );
      bos.write( BOM );
      bos.write( tgt );
      bos.write( 0 ); bos.write( 0 );    // UTF-16 NUL 終端
      Files.write( p, bos.toByteArray(),
                   StandardOpenOption.CREATE,
                   StandardOpenOption.TRUNCATE_EXISTING,
                   StandardOpenOption.WRITE );
      // Cygwin 互換のため DOS system + hidden 属性を立てる (可能なら)。
      // DrvFs 等で未対応でも内容 (cookie) で検出できるので無視。
      try {
        DosFileAttributeView dos =
          Files.getFileAttributeView( p, DosFileAttributeView.class );
        if( dos != null ) {
          dos.setSystem( true );
          dos.setHidden( true );
        }
      } catch( Exception ignore ) { }
      return true;
    } catch( Exception e ) {
      return false;
    }
  }

  // ------------------------------------------------------------------
  //  native path がマジックファイル symlink なら target を返す。
  //  そうでなければ null。
  //  検出は先頭 cookie の一致で行う (属性に依存しない)。
  // ------------------------------------------------------------------
  public static String read( String native_path ) {
    File f = new File( native_path );
    // regular file かつ最低 cookie 長以上のときだけ読む。
    if( !f.isFile() ) return null;
    long len = f.length();
    if( len < COOKIE.length ) return null;
    try( InputStream in = new BufferedInputStream( new FileInputStream( f ) ) ) {
      byte[] head = new byte[ COOKIE.length ];
      int n = in.readNBytes( head, 0, COOKIE.length );
      if( n != COOKIE.length ) return null;
      for( int i = 0; i < COOKIE.length; i++ ) {
        if( head[i] != COOKIE[i] ) return null;   // cookie 不一致 = 通常 file
      }
      // 残りを読んで BOM を skip、UTF-16 として target を decode。
      byte[] rest = in.readAllBytes();
      int off = 0;
      if( rest.length >= 2 && (rest[0] & 0xFF) == 0xFF && (rest[1] & 0xFF) == 0xFE ) {
        off = 2;   // BOM skip
      }
      // NUL 終端 (UTF-16 0x0000) までを target とする。
      int end = rest.length;
      for( int i = off; i + 1 < rest.length; i += 2 ) {
        if( rest[i] == 0 && rest[i+1] == 0 ) { end = i; break; }
      }
      return new String( rest, off, end - off, StandardCharsets.UTF_16LE );
    } catch( Exception e ) {
      return null;
    }
  }

  // ------------------------------------------------------------------
  //  native path がマジックファイル symlink かどうか (target は読まない軽量版)。
  // ------------------------------------------------------------------
  public static boolean isMagic( String native_path ) {
    File f = new File( native_path );
    if( !f.isFile() ) return false;
    if( f.length() < COOKIE.length ) return false;
    try( InputStream in = new FileInputStream( f ) ) {
      byte[] head = new byte[ COOKIE.length ];
      int n = in.readNBytes( head, 0, COOKIE.length );
      if( n != COOKIE.length ) return false;
      for( int i = 0; i < COOKIE.length; i++ ) {
        if( head[i] != COOKIE[i] ) return false;
      }
      return true;
    } catch( Exception e ) {
      return false;
    }
  }

  // ------------------------------------------------------------------
  //  issue #322: Windows NTFS で filename に使えない予約文字を Cygwin と同じ
  //  Unicode private-use area (U+F000 + char) へ encode / decode する。
  //
  //  動機: dpkg multiarch は info file を `<pkg>:<arch>.list` で持つ (例
  //  `gcc-14-base:amd64.list`)。`:` は NTFS の ADS 区切りで filename に使えず
  //  (`* ? " < > | \` も同様)、createLink/open が "Operation not permitted" で
  //  失敗する。Cygwin と同じ慣習で `:`(0x3A)→U+F03A 等に変換すれば host FS 上は
  //  正当な名前で扱え、guest には元の `:` で見せられる。
  //
  //  encode は guest→native (Mount.native_path_raw、path 区切り `/` は除外)、
  //  decode は native→guest (getdents の d_name)。予約文字を含まない圧倒的多数の
  //  path では新規 alloc せず引数をそのまま返す (hot path 配慮)。
  // ------------------------------------------------------------------
  private static boolean isReserved( char c ) {
    // NTFS で filename に使えない文字 (path 区切り `/` は対象外)。
    return c == ':' || c == '*' || c == '?' || c == '"'
        || c == '<' || c == '>' || c == '|' || c == '\\';
  }

  public static String encodeReservedPath( String s ) {
    if( s == null ) return null;
    int n = s.length();
    int i = 0;
    for( ; i < n; i++ ) if( isReserved( s.charAt( i ) ) ) break;
    if( i == n ) return s;                         // 予約文字なし → 無 alloc
    StringBuilder sb = new StringBuilder( n + 4 );
    sb.append( s, 0, i );
    for( ; i < n; i++ ) {
      char c = s.charAt( i );
      sb.append( isReserved( c ) ? (char)( 0xF000 + c ) : c );
    }
    return sb.toString();
  }

  public static String decodeReservedPath( String s ) {
    if( s == null ) return null;
    int n = s.length();
    int i = 0;
    for( ; i < n; i++ ) {
      char c = s.charAt( i );
      if( c >= 0xF000 && c <= 0xF0FF && isReserved( (char)( c - 0xF000 ) ) ) break;
    }
    if( i == n ) return s;                         // encode 済み文字なし → 無 alloc
    StringBuilder sb = new StringBuilder( n );
    sb.append( s, 0, i );
    for( ; i < n; i++ ) {
      char c = s.charAt( i );
      if( c >= 0xF000 && c <= 0xF0FF && isReserved( (char)( c - 0xF000 ) ) )
        sb.append( (char)( c - 0xF000 ) );
      else
        sb.append( c );
    }
    return sb.toString();
  }
}
