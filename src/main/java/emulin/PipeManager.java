// ----------------------------------------
//  Emulin Pipe Manager
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;

// 名前無しパイプの情報
class Pipeinfo {
  static int buf_size = 64*1024;// バッファサイズ
  byte buf[];                   // パイプ用バッファ
  int used;                     // 使用バイト数
  int wp;                       // 出力の書き込みポインタ
  int rp;                       // 入力の読みだしポインタ
  int i_connected;              // 接続された回数 in
  int o_connected;              // 接続された回数 out

  public Pipeinfo( ) {
    buf = new byte[ buf_size ];
    used = 0;
    wp  = 0;
    rp  = 0;
    i_connected  = 1;
    o_connected = 1;
  }

  // 接続されているか？
  public boolean is_connected( ) {
    if( i_connected == 0 || o_connected == 0 ) { return( false ); }
    return( true );
  }

  // リードしたバイト数を返す。
  // POSIX read セマンティクス:
  //   - バッファに 1 byte でも来ていればその時点で返る (= short read を許す)
  //   - 完全に空なら最初の 1 byte 到着まで block
  //   - pipe 切断時はその時点で受け取った分を返す (EOF は 0 byte)
  //   - nonBlock=true のとき空 + 接続中なら -2 (caller が EAGAIN に変換)
  public int read( byte _buf[] ) { return read( _buf, false ); }
  public int read( byte _buf[], boolean nonBlock ) {
    int i;
    for( i = 0 ; i < _buf.length ; ) {
      if( rp >= buf_size ) { rp = 0; } // バッファのリング化
      while( used <= 0 ) {
        if( !is_connected( )) { return( i ); } // pipe 切断 = EOF または partial
        if( i > 0 ) { return( i ); }            // 既に何 byte か読めた → 即返す
        // バッファ空 + 何も読めていない → blocking なら待つ、non-block なら EAGAIN
        if( nonBlock ) return -2;
        try { Thread.sleep( 50L ); }
        catch( InterruptedException m ) { };
        Thread.yield( );
      }
      _buf[i++] = buf[rp++];
      used--;
    }
    return( i );
  }

  // ライトしたバイト数を返す。
  public boolean write( byte _buf[] ) {
    int i;
    // Kernel.println( "  Pipeinfo.write( )     rp = " + rp + " wp = " + wp );
    if( !is_connected( )) {
      return( false );
    }

    for( i = 0 ; i < _buf.length ; i++ ) {
      if( wp >= buf_size ) { wp = 0; } // バッファのリング化 
      while( buf_size <= used ) { // バッファフルの間待つ
	if( !is_connected( )) { return( false ); } // パイプの切断
	// Kernel.println( "  Pipeinfo.write( )    waiting  for ...  i = " + i );
	try { Thread.sleep( 1000L ); }
	catch( InterruptedException m ) { };
	Thread.yield( );
      }
      buf[wp++] = _buf[i];
      used++;
    }
    return( true );
  }
}

public class PipeManager extends XKernel {
  Vector pipetable; // パイプテーブル

  public PipeManager( ) {
    pipetable = new Vector( );
  }

  // パイプを生成する。
  // 生成したパイプの番号を返す。
  public int connect_pipe( ) {
    // 生成
    Pipeinfo pipe  = new Pipeinfo( );
    // プロセスへの設定
    if( sysinfo.verbose( )) {
      println( " connect_pipe( ) : pipe_no = " + pipetable.size( ));
    }
    pipetable.addElement( (Object)pipe );
    disp_pipe( pipetable.size( )-1 );
    return( pipetable.size( )-1 );
  }

  // 既に接続されているか調べる
  public boolean is_pipe_connected( int pipe_no ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    // 入力または出力の参照数が 0 なら切断されている。
    return( pipe.is_connected( ));
  }

  // パイプからリードする。
  public int pipe_read( int pipe_no, byte buf[] ) { return pipe_read( pipe_no, buf, false ); }
  public int pipe_read( int pipe_no, byte buf[], boolean nonBlock ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    disp_pipe( pipe_no );
    return( pipe.read( buf, nonBlock ));
  }

  // パイプへライトする。
  public boolean pipe_write( int pipe_no, byte buf[] ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );

    disp_pipe( pipe_no );

    // 切断されていたらリード失敗
    if( !is_pipe_connected( pipe_no )) { return( false ); }

    return( pipe.write( buf ));
  }

  // パイプの接続状況を表示する。
  private void disp_pipe( int pipe_no ) {
    int i;
    if( false ) {
      println( "disp_pipe : pipe_no = " + pipe_no  );
      for( i = 0 ; i < pipetable.size( ) ; i++ ) {
	Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( i );
	if( pipe == null ) {
	  println( "    ---- pipe[" + i + "]  is null " );
	}
	else {
	  println( "    ---- pipe[" + i + "]  i_connected = " + pipe.i_connected + " o_connected = " + pipe.o_connected );
	}
      }
    }
  }


  // パイプを切断する。
  public void disconnect_pipe( int pipe_no, boolean input_flag ) {
    Pipeinfo pipe = null;
    if( pipe_no < 0 )  {return;}

    pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) {return;}
    if( input_flag ) { pipe.i_connected--; }
    else             { pipe.o_connected--; }
    if( sysinfo.verbose( )) {
      println( " ---- disconnect_pipe( " + pipe_no + " );  i_connected = " + pipe.i_connected + "  o_connected = " + pipe.o_connected );
    }
    disp_pipe( pipe_no );
  }

  // パイプをduplicate する。
  public void duplicate_pipe( int pipe_no, boolean input_flag ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) {return;}

    if( input_flag ) { pipe.i_connected++; }
    else             { pipe.o_connected++; }
    if( sysinfo.verbose( )) {
      println( " ---- duplicate_pipe( " + pipe_no + " );  i_connected = " + pipe.i_connected + "  o_connected = " + pipe.o_connected );
    }

    disp_pipe( pipe_no );

  }
}
