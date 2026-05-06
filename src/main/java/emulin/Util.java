// ----------------------------------------
//  Java Utility
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  $Date: 1999/06/07 16:41:01 $ 
//  $Id: Util.java,v 1.14 1999/06/07 16:41:01 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.net.*;
import emulin.*;

// ユーティリティークラス
public class Util {
  // 16進数文字列への変換
  public static String hexstr( int value, int width ) {
    String buf;
    String zero = "00000000";
    buf = Long.toString( (long)value & 0xFFFFFFFFL, 16 );
    if( (0 != width) && ( buf.length( ) < width) ) {
      buf = zero.substring( 0, width - buf.length( )) + buf;
    }
    return( buf );
  }

  public static String hexstr( long value, int width ) {
    String buf;
    String zero = "0000000000000000";
    buf = Long.toUnsignedString( value, 16 );
    if( (0 != width) && ( buf.length( ) < width) ) {
      buf = zero.substring( 0, width - buf.length( )) + buf;
    }
    return( buf );
  }

  // byte 配列 から 16bit への変換 (リトルエンディアン)
  public static short to16( byte buf[], int offset ) {
    return( (short)(
		   (0xFF & (short)buf[offset]  )  |
		   (0xFF & (short)buf[offset+1]) << 8
		   )
	    );
  }

  // byte 配列 から 32bit への変換 (リトルエンディアン)
  public static int to32( byte buf[], int offset ) {
    return( (0xFF & (int)buf[offset]  )  |
	    (0xFF & (int)buf[offset+1]) << 8 |
	    (0xFF & (int)buf[offset+2]) << 16 |
	    (0xFF & (int)buf[offset+3]) << 24
	    );
  }

  // 16ビットの エンディアンをスワップする。
  public static short swap16( short s ) {
    return((short) (((s >> 8) & 0xFF) | ((s << 8) & 0xFF00 )));
  }

  // 32ビットの エンディアンをスワップする。
  public static int swap32( int s ) {
    return(
		  ((s >> 24) & 0xFF) | ((s << 24) & 0xFF000000 ) | 
		  (( s >> 8 ) & 0xFF00 ) | (( s << 8 ) & 0xFF0000 )
		   );
  }

  // aaa/bbb/../ccc を aaa/ccc にする。
  public  static String realname( String name ) {
    int i, j;
    name = _realname( name );
    for( j = 0 ; j < 3 ; j++ ) {
      for( i = 2 ; i < name.length( ) ; i++ ) {
	if( '.' == name.charAt( i-2 ) &&
	    '.' == name.charAt( i-1 ) &&
	    '/' == name.charAt( i   )) {
	  String s1 = name.substring( 0, i );
	  String s2 = name.substring( i+1 );
	  //	  System.out.println( " sep = [" + s1 + "][" + s2 + "]" );
	  name = _realname( s1 );
	  if( '/' != name.charAt( name.length( )-1 )) {  name += "/"; }
	  name += s2;
	  break;
	}
      }
    }
    //    System.out.println( " result = [" + name + "]" );
    return( name );
  }

  // aaa/bbb/.  を aaa/bbb に
  // aaa/bbb/.. を aaa     にする
  private static String _realname( String name ) {
    int n = name.length( );
    int nest = 0;
    int i;
    if( 2 > n ) { return( name ); }
    while( '.' == name.charAt( n-1 )) {
      if( '.' == name.charAt( n-2 )) {
	name = basename( name );
	nest++;
      }
      else { name = basename( name ); }
      n = name.length( );  
      if( 2 > n ) { break; }
    }
    for( i = 0 ; i < nest ; i++ ) {      name = basename( name );    }
    if( name.equals( "" ))        {      name = "/";    }
    return( name );
  }

  // basename を求める
  private static String basename( String name ) {
    int i;
    int n = 0;
    for( i = 0 ; i < name.length( ) ; i++ ) {
      if( '/' == name.charAt( i )) { n = i; }
    }
    name = name.substring( 0, n );
    return( name );
  }

  // 符号拡張を行う
  public static int expand_sign( int value, int size ) {
    if( size == 1 ) {
      value &= 0xFF;
      if( 0 != ( value & 0x80 ))   { value |= 0xFFFFFF00; }
    }
    if( size == 2 ) {
      value &= 0xFFFF;
      if( 0 != ( value & 0x8000 )) { value |= 0xFFFF0000; }
    }
    return( value );
  }

  // LE ip(32bit) を 255.255.255.255 形式の文字列に変換する。
  public static String ip_str( int ip ) {
    String str = Integer.toString( (ip >> 0) & 0xFF );
    str += ".";
    str += Integer.toString( (ip >>  8) & 0xFF );
    str += ".";
    str += Integer.toString( (ip >> 16) & 0xFF );
    str += ".";
    str += Integer.toString( (ip >> 24)& 0xFF );
    return( str );
  }

  // 255.255.255.255 形式の文字列を LE ip(32bit)に変換する。
  public static int ip( String ip_str ) {
    InetAddress addr;
    byte b[];
    int ret = 0;
    try{ addr = InetAddress.getByName( ip_str ); }
    catch( UnknownHostException m ) { return( 0 ); };
    b = addr.getAddress( );
    ret |= ((int)b[3] << 24) & 0xFF000000;
    ret |= ((int)b[2] << 16) & 0xFF0000;
    ret |= ((int)b[1] <<  8) & 0xFF00;
    ret |= ((int)b[0] <<  0) & 0xFF;
    return( ret );
  }

  // select( ) システムコールのビットフィールド配列をバイト配列に変換する。
  public static void selectbits_to_fds( byte fds[], int bits[] )
  {
    int i;
    int n = fds.length;
    for( i = 0 ; i < n ; i++ ) {
      fds[i] = (byte) ((bits[i/32] >> i%32) & 1);
    }
  }

  // select( ) システムコールのバイト配列をビットフィールド配列に変換する。
  public static void fds_to_selectbits( int bits[], byte fds[] )
  {
    int i;
    int n = fds.length;
    for( i = 0 ; i < n/32 ; i++ ) {
      bits[i] = 0;
    }
    for( i = 0 ; i < n ; i++ ) {
      bits[i/32] |= ((long)fds[i] << i%32);
    }
  }
}
