// ----------------------------------------
//  RingBuffer ( リングバッファ )
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/10 18:59:41 $ 
//  $Id: RingBuffer.java,v 1.2 2000/02/10 18:59:41 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.net.*;
import emulin.*;

public class RingBuffer {
  int  bufsize;
  byte buf[];
  int  rp;
  int  wp;
  int  use;

  // バッファインスタンスの生成
  public RingBuffer( Sysinfo _sysinfo, int size ) {
    // オブジェクトの生成
    bufsize = size;
    buf     = new byte[size];
    rp      = 0;
    wp      = 0;
    use     = 0;
  }
    
  // サーバソケットインスタンスを返す
  synchronized public byte rw( byte data, boolean read_flag ) {
    byte ret = 0;
    if( read_flag ) {
      if( use > 0 ) {
          ret = buf[rp++];
	  use--;
	  if( rp >= bufsize ) { rp = 0; }
      }
    }
    else {
      if( use < bufsize ) {
          buf[wp++] = data;
	  use++;
	  if( wp >= bufsize ) { wp = 0; }
      }
    }
    return( ret );
  }

  // たまっているバイト数を返す
  synchronized public int get_size( ) {
    return( use );
  }

  // FULL かどうか調べる
  synchronized public boolean full( ) {
    return( use == bufsize );
  }

  // EMPTY かどうか調べる
  synchronized public boolean empty( ) {
    return( 0 == use );
  }
}
