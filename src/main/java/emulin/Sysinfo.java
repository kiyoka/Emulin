// ----------------------------------------
//  System Information of Process
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;

public class Sysinfo extends Mount {

  public Sysinfo( int __verbose_level, boolean __debug ) {
    _verbose_level  = __verbose_level;
    _debug          = __debug;
    // デフォルト値
  }

  // host の stdin/stdout/stderr (fd 0/1/2) が実端末かを一度だけ判定してキャッシュする。
  //   which: 0=stdin, 1=stdout, 2=stderr。
  //   redirected file / pipe のときに Emulin が fd0 を対話端末と誤認し、
  //   (a) TCGETS 成功 → isatty(0)=true で bc/dc が対話モードに入る
  //   (b) StdConsole が CR 破棄 + 行単位読みで binary stdin を壊す
  //   のを防ぐための土台。実 Linux は redirected/pipe を非 tty と報告する。
  private int[] _host_std_tty = null;   // -1 未判定, 0 非tty, 1 tty

  public boolean host_std_is_tty( int which ) {
    if( which < 0 || which > 2 ) return false;
    if( _host_std_tty == null )
      _host_std_tty = new int[]{ probe_host_tty( 0 ), probe_host_tty( 1 ), probe_host_tty( 2 ) };
    return _host_std_tty[which] != 0;
  }

  //   Linux: /proc/self/fd/<n> の symlink 先で判定 (/dev/pts/* や /dev/tty* は tty、
  //   regular file / pipe:[..] / socket:[..] は非 tty)。env で明示上書き可
  //   (EMULIN_STDIN_ISATTY / _STDOUT_ / _STDERR_ = 0|1)。probe 不可 (非 Linux 等) は
  //   従来挙動維持のため tty とみなす (= 回帰なし)。
  private static int probe_host_tty( int fd ) {
    String ov = System.getenv( "EMULIN_STD" + ( fd == 0 ? "IN" : fd == 1 ? "OUT" : "ERR" ) + "_ISATTY" );
    if( ov != null ) return ( ov.equals( "1" ) || ov.equalsIgnoreCase( "true" ) ) ? 1 : 0;
    try {
      java.nio.file.Path p = java.nio.file.Paths.get( "/proc/self/fd/" + fd );
      if( java.nio.file.Files.exists( p, java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) {
        String t = java.nio.file.Files.readSymbolicLink( p ).toString();
        return ( t.startsWith( "/dev/pts/" ) || t.startsWith( "/dev/tty" ) ) ? 1 : 0;
      }
    } catch( Exception e ) { /* fall through */ }
    return 1;   // 判定不可は従来通り tty 扱い
  }

  // コンフィグファイルを読み込む
  public boolean load_config( String filename ) {
    boolean ret = true;
    RandomAccessFile in;
    String token[] = new String[10];
    String str;
    int len, i;
    try { in = new RandomAccessFile( get_native_path( filename ), "r" ); }
    catch ( IOException m ) { kernel.println( "File open error [" + filename + "]" ); return( false ); }
    for( ; ; ) {
      try { str = in.readLine( ); }
      catch ( IOException m ) { kernel.println( "File read error [" + filename + "]" ); return( false ); }
      if( null == str ) { break; }

      if( verbose( )) {
	kernel.println( "read_line : str = " + str );
      }
      // トークンに分割する。
      for( i = 0 ; ; i++ ) {
	str = str.trim( );
	len = str.indexOf( ' ' );
	if( -1 == len ) { token[i] = str; }
	else            { token[i] = new String( str.substring( 0, len )); }
	token[i] = token[i].trim( );
	if( verbose( )) {
	  kernel.println( " token[" + i + "] = " + token[i] );
	}
	if( -1 == len ) { break; }
	else            { str = str.substring( len ); }
      }
      if( 0 == token[0].length( )) continue; // 空行
      if( '#' != token[0].charAt( 0 )) { // コメント以外なら
	if( token[0].equals( "root" )) {
          set_root( token[1] );
	}
      }
    }
    return( ret );
  }
}
