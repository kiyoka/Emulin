// ----------------------------------------
//  System Information of Process
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/05/11 17:08:04 $ 
//  $Id: Sysinfo.java,v 1.5 1999/05/11 17:08:04 kiyoka Exp $
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
