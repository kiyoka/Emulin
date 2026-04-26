// ----------------------------------------
//  Java File Loading Utility
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/04/06 13:19:06 $ 
//  $Id: LoadUtil.java,v 1.8 1999/04/06 13:19:06 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;

// エンディアン対応クラス
class LoadUtil {

  // リトルエンディアンでの読み込み
  public static short little16( RandomAccessFile in, Kernel kernel ) {
    byte buf2[] = new byte[2];
    try {  in.read( buf2 ); }
    catch ( java.io.IOException m ) {  kernel.println( "File read error" ); return( 0 ); }
    return( (short)(
	   (short)buf2[0] |
	   ((short)buf2[1] << 8)));
  }
  public static int little32( RandomAccessFile in, Kernel kernel ) {
    byte buf4[] = new byte[4];
    int intval = 0;
    try { in.read( buf4 ); }
    catch ( java.io.IOException m ) {  kernel.println( "File read error" ); return( 0 ); }
    intval |= ((int)buf4[0] & 0xFF) << (8*0);
    intval |= ((int)buf4[1] & 0xFF) << (8*1);
    intval |= ((int)buf4[2] & 0xFF) << (8*2);
    intval |= ((int)buf4[3] & 0xFF) << (8*3);
    return( intval );
  }
  public static boolean bytes( RandomAccessFile in, byte b[], Kernel kernel ) {
    try { in.read( b ); }
    catch ( java.io.IOException m ) {  kernel.println( "File read error" ); return( false ); }
    return( true );
  }
}
