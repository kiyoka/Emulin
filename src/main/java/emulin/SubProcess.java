// ----------------------------------------
//  SubProcess ( ネットワーク入力監視用 )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.net.*;
import emulin.*;

public class SubProcess extends Thread {
  public static int ACCEPT_WAIT = 0;
  public static int ACCEPT_DONE = 1;
  public static int ACCEPT_MISS = 2;
  static int BUFSIZE = 1024;
  Sysinfo  sysinfo;
  RingBuffer ringbuffer;
  Fileinfo finfo;
  byte buf[];
  int addr_info[];
  int fd;
  boolean opened;
  boolean listen_mode;
  // issue #43 Phase 4-2: accept された Socket は queue に積み、emulin の
  //   accept() consumer (EmuSocket.accept) が 1 個ずつ取り出す。SubProcess
  //   は 1 個ずつ accept してそのまま enqueue、複数 client が同時に来ても
  //   sconn.accept() の連続呼び出しで全部処理できる。
  //   旧 conn (単一 Socket) は「直近 1 つだけ保持」で複数接続を取りこぼし
  //   ていた + 古いコードが listen_start で新 SubProcess を作って race。
  //   ConcurrentLinkedQueue で thread-safe。
  final java.util.concurrent.ConcurrentLinkedQueue<Socket> accept_queue
      = new java.util.concurrent.ConcurrentLinkedQueue<>();
  volatile int accept_flag;  // ACCEPT_MISS 検出用、queue の有無は accept_queue で判定
  ServerSocket  sconn;
  Socket         conn;  // legacy: 直近の accept 結果 (旧 EmuSocket.accept が参照)


  // サブプロセスの生成
  public SubProcess( Sysinfo _sysinfo, Fileinfo _finfo, int _fd ) {
    // オブジェクトの生成
    sysinfo = _sysinfo;
    finfo   = _finfo;
    fd      = _fd;
    buf     = new byte[BUFSIZE];
    ringbuffer = new RingBuffer( _sysinfo, BUFSIZE );
    addr_info = new int[2]; 
    opened = true;
    listen_mode = false;
  }

  // リッスン監視モードに入る
  public void set_listen_mode( ServerSocket _sconn ) {
    sconn = _sconn;
    listen_mode = true;
  }
  
  // サーバソケットインスタンスを返す
  public ServerSocket get_sconn( ) {
    return( sconn );
  }

  // socketからの先読み処理
  public void run( ) {
    if( listen_mode ) { // LISTENモード
      accept_flag = ACCEPT_WAIT;
      while( true ) {
	if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:top(listen) " ); }
	Socket s;
	try { s = sconn.accept( ); }
	catch ( IOException m ) { accept_flag = ACCEPT_MISS; break; }
	if( s == null ) continue;
	// issue #43 Phase 4-2: 受け取った Socket を queue に積む。conn には
	//   「直近 1 つ」も入れて legacy 互換 (旧 EmuSocket.accept が参照)。
	accept_queue.offer( s );
	conn = s;
	accept_flag = ACCEPT_DONE;
	if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:accept(queued, size=" + accept_queue.size() + ") " ); }
      }
    }
    else {
      // issue #41 (sshd): STREAM (TCP) は FileRead が finfo.Read で socket
      //   から直接読むので、SubProcess が ring buffer に先読みすると bytes
      //   を steal する race になる。STREAM 用は何もせず即終了する。
      //   subprocess インスタンス自体は他経路 (poll の sconn check 等) が
      //   null check で迂回するため残す必要があるが、run の loop は不要。
      if( finfo != null && finfo.isSTREAM() ) {
        return;
      }
      while( true ) {
	if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:top " ); }
	if( finfo == null ) { opened = false; break; } // 無効な fd なら
	if( opened ) {
	  if( finfo.isSTREAM( )) { /* ストリーム */
	    while( ringbuffer.full( )) { // バッファフルなら空くまで待つ
	      try { Thread.sleep( 100L ); }
	      catch( InterruptedException m ) { };
	      Thread.yield( );
	    }
	    read_byte_sub( );
	  }
	  else { /* データグラムパケット */
            if( ringbuffer.empty( )) {
	      synchronized ( finfo ) {
		int len, i;
		if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:read(DGRAM) " ); }
		len = finfo.recvfrom( buf, addr_info );
		if( len == 0 ) { opened = false; }
		else {
		  for( i = 0 ; i < len ; i++ ) {
		    ringbuffer.rw( buf[i], false );
		  }
		}
	      }
	    }
	  }
	}
	if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:before sleep " ); }
	//	try { Thread.sleep( 30L ); }
	//	catch( InterruptedException m ) { };
	Thread.yield( );
	if( sysinfo.verbose( )) {
	  sysinfo.kernel.println( "fd=" + fd + " sub:len = " + ringbuffer.get_size( ) + " (" + Util.hexstr( (int)buf[0] & 0xFF, 2 ) + ") " );
	}
	if( !opened ) {
	  break;
	}
      }
    }
    if( sysinfo.verbose( )) {
      sysinfo.kernel.println( "fd=" + fd + " sub:END" );
    }
  }
  
  // ファイルをクローズする
  public void close( ) {
    opened = false;
  }

  // なにかイベントがあったか？
  public boolean isEvent( ) {
    boolean ret = false;
    if( sysinfo.verbose( )) {
      sysinfo.kernel.println( "fd=" + fd + " sub.isOPEN( i )    " + isOPEN( ));
      sysinfo.kernel.println( "fd=" + fd + " sub.Available( i ) " + Available( ));
      sysinfo.kernel.println( "fd=" + fd + " sub.isCLOSE( i )   " + this.isCLOSE( ));
    }
    if( isOPEN( ) && Available( )) { ret = true; }
    if( isCLOSE( ))                { ret = true; }
    if( sysinfo.verbose( )) {
      sysinfo.kernel.println( "fd=" + fd + " sub.isEvent( ) " + ret );
    }
    return( ret );
  }

  // オープン中か？
  public boolean isOPEN( ) {
    return( !isCLOSE( ));
  }

  // 接続相手がクローズしたか？
  public boolean isCLOSE( ) {
    return( !opened );
  }

  // アクセプトできたか？
  //   issue #43 Phase 4-2: queue ベース判定に変更。queue に socket があれば
  //   ACCEPT_DONE。空 + accept_flag が MISS なら MISS、それ以外は WAIT。
  public int Accepted( ) {
    if( !accept_queue.isEmpty() ) return ACCEPT_DONE;
    if( accept_flag == ACCEPT_MISS ) return ACCEPT_MISS;
    return ACCEPT_WAIT;
  }

  // issue #43 Phase 4-2: 1 つ取り出す (なければ null)。
  public Socket poll_accepted( ) {
    return accept_queue.poll();
  }

  // 読み込みできたか？
  public boolean Available( ) {
    if( sysinfo.verbose( )) {
      sysinfo.kernel.println( "fd=" + fd + " sub:availe = " + ringbuffer.get_size( ) );
    }
    return( ringbuffer.get_size( ) > 0 );
  }

  // ソケットからの入力を行う
  int read_byte_top( byte _buf[], boolean subprocess_flag ) {
    int ret = 0;
    ret = read_byte( _buf );
    return( ret );
  }

  // サブプロセスからコールする
  int read_byte_sub( ) {
    byte __byte_buf[];
    __byte_buf = new byte[1];
    if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub:read(STREAM) " ); }

    // 1バイト読み込む
    if( !ringbuffer.full( )) {
      if( 0 == finfo.Read( __byte_buf )) {
	if( ringbuffer.empty( )) {
	  if( sysinfo.verbose( )) { sysinfo.kernel.println( "fd=" + fd + " sub: peer is closed. " ); }
	  opened = false;
	}
      }
      else { // 追記
	ringbuffer.rw( __byte_buf[0], false );
	if( sysinfo.verbose( )) {
	    sysinfo.kernel.println( "fd=" + fd + " sub:len = " + ringbuffer.get_size( ) + "buf[0]=" + __byte_buf[0] ); 
	}
      }
    }
    return( 1 );
  }

  // 先読み済みのデータを読み込む
  int read_byte( byte _buf[] ) {
    int ret = 0;
    int i;
    int cnt;
    for( cnt = 0 ; ringbuffer.empty( ) ; cnt++ ) { // empty の間待つ
	if( sysinfo.verbose( )) {
	    if( 0 == ( cnt % 20 )) {
		sysinfo.kernel.println( " read.byte( )  blocking  opened = " + opened ); 
	    }
	}
	Thread.yield( );
	try { Thread.sleep( 100L ); }
	catch( InterruptedException m ) { };
	if( !opened ) return( 0 ); // peer closed.
    }
    ret = ringbuffer.get_size( );
    if( _buf.length < ret ) {
      ret = _buf.length;
    }
    for( i = 0 ; i < ret ; i++ ) {
      _buf[i] = ringbuffer.rw( (byte)0, true );
    }
    return( ret );
  }

  // 先読みしていたバイトを読み込んだ
  public int read_byte_top( byte _buf[], int _addr_info[], boolean subprocess_flag ) {
    _addr_info[0] = addr_info[0];
    _addr_info[1] = addr_info[1];
    return( read_byte_top( _buf, subprocess_flag ));
  }
}
