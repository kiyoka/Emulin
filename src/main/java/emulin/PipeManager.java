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
// Phase 28-3 注意: read() / write() / disconnect 系は全て synchronized で
// monitor を取る。pthread 後の世界で parent git と child upload-pack が
// 別 Java thread から同じ Pipeinfo を read/write し合うと、buf[]/used/wp/rp
// の compound update が racing して "fatal: protocol error: bad line length"
// "early EOF" "fetch-pack: bad band #N" 等の data corruption 系エラーが発生
// する。git clone --no-hardlinks file:// で並列負荷下に再現していた。
class Pipeinfo {
  static int buf_size = 64*1024;// バッファサイズ
  byte buf[];                   // パイプ用バッファ
  int used;                     // 使用バイト数
  int wp;                       // 出力の書き込みポインタ
  int rp;                       // 入力の読みだしポインタ
  int i_connected;              // 接続された回数 in
  int o_connected;              // 接続された回数 out
  // issue #219: O_ASYNC + F_SETOWN を設定した read 端の SIGIO 送り先 pid。
  //   この pipe にデータが書かれたら owner に SIGIO を配信する (emacs 等の
  //   interrupt-driven 端末入力)。-1 = async 未設定。
  int async_owner = -1;
  int dbgPipeNo = -1;           // issue #353: TRACE_PIPE 用の自分の pipe_no (connect_pipe で設定)
  PipeManager mgr = null;       // issue #353: watchdog の全テーブルダンプ用 back-ref

  public Pipeinfo( ) {
    buf = new byte[ buf_size ];
    used = 0;
    wp  = 0;
    rp  = 0;
    i_connected  = 1;
    o_connected = 1;
  }

  // 接続されているか？ (synchronized で memory visibility 確保)
  public synchronized boolean is_connected( ) {
    if( i_connected <= 0 || o_connected <= 0 ) { return( false ); }
    return( true );
  }

  // issue #41 (sshd): pipe buffer に未読 byte があるかを返す。
  //   socketpair 経由の sshd privsep monitor↔preauth 通信で、poll が
  //   pipe の data availability を知る必要がある。is_connected だけでは
  //   「接続中」しか分からず、書き込み済みの byte があるかは見えない。
  public synchronized int available( ) {
    return used;
  }

  // リードしたバイト数を返す。
  // POSIX read セマンティクス:
  //   - バッファに 1 byte でも来ていればその時点で返る (= short read を許す)
  //   - 完全に空なら最初の 1 byte 到着まで block
  //   - pipe 切断時はその時点で受け取った分を返す (EOF は 0 byte)
  //   - nonBlock=true のとき空 + 接続中なら -2 (caller が EAGAIN に変換)
  public int read( byte _buf[] ) { return read( _buf, false ); }
  public synchronized int read( byte _buf[], boolean nonBlock ) {
    int i;
    int blockedTicks = 0;   // issue #353: TRACE_PIPE 用の block 継続カウンタ
    for( i = 0 ; i < _buf.length ; ) {
      if( rp >= buf_size ) { rp = 0; } // バッファのリング化
      while( used <= 0 ) {
        if( i_connected <= 0 || o_connected <= 0 ) return( i ); // pipe 切断
        if( i > 0 ) return( i );                 // partial read は即返す
        if( nonBlock ) return -2;
        // issue #562: blocking read 中に pending signal が来たら -EINTR(-4) で復帰する
        //   (Linux: signal 到達で read が中断。SA_RESTART なら上位が read を再実行)。i==0
        //   (まだ 1 byte も読んでいない) のときのみ EINTR。partial read は上の i>0 で返済み。
        {
          java.util.function.BooleanSupplier sp = PipeManager.SIG_PENDING.get();
          if( sp != null && sp.getAsBoolean() ) return -4;  // -EINTR
        }
        try { wait( 50L ); }                     // writer の notify を待つ
        catch( InterruptedException m ) { }
        // issue #353: connected (i/o_connected != 0) のままデータが来ず block し
        //   続けている pipe を ~5s ごとに出力 + 全 pipe テーブルをダンプ。どの pipe の
        //   どちら側の参照が残留して reader が永久 block しているかを特定する。
        //   WATCHDOG はホットパス (used>0 の通常 read) では一切発火しないので
        //   native のタイミングを乱さず race (hang) を再現できる。
        if( ( PipeManager.WATCHDOG || PipeManager.TRACE_PIPE ) && ( ++blockedTicks % 100 ) == 0 ) {
          System.err.println( "[pipe] STILL-BLOCKED-READ pipe_no=" + dbgPipeNo
              + " in=" + i_connected + " out=" + o_connected + " used=" + used
              + " waited=" + ( blockedTicks * 50 ) + "ms " + PipeManager.pipeTag( ));
          if( mgr != null ) mgr.dumpPipes( "blocked-read pipe_no=" + dbgPipeNo );
        }
      }
      _buf[i++] = buf[rp++];
      used--;
    }
    notifyAll();  // writer が full で wait していれば起こす
    return( i );
  }

  // issue #480: MSG_PEEK 用。buffer の先頭から available 分だけ非破壊で読む
  //   (rp/used は変更しない)。block はしない (peek は「今あるものだけ」返す)。
  public synchronized int peek( byte[] _buf ) {
    int n = Math.min( _buf.length, used );
    int p = rp;
    for( int i = 0; i < n; i++ ) {
      if( p >= buf_size ) p = 0;
      _buf[i] = buf[p++];
    }
    return n;
  }

  // ライトしたバイト数を返す。
  // 後方互換: blocking write。全部書けたら true、切断で false。
  public synchronized boolean write( byte _buf[] ) {
    return( writeNB( _buf, false ) >= 0 );
  }

  // issue #551: nonBlock 対応 write。read(nonBlock) と対称に、full かつ
  //   nonBlock なら書けた分だけ書いて返す (partial write)。全く書けなければ 0
  //   (caller が EAGAIN に変換)。切断は -1。blocking(nonBlock=false) は full で
  //   reader を待つ従来動作。
  public synchronized int writeNB( byte _buf[], boolean nonBlock ) {
    int i;
    if( i_connected <= 0 || o_connected <= 0 ) return( -1 );

    for( i = 0 ; i < _buf.length ; i++ ) {
      if( wp >= buf_size ) { wp = 0; }           // バッファのリング化
      while( buf_size <= used ) {                // バッファフル
        if( i_connected <= 0 || o_connected <= 0 ) return( i > 0 ? i : -1 );
        if( nonBlock ) { if( i > 0 ) { notifyAll(); PollKick.kick(); } return( i ); }  // 書けた分を返す
        try { wait( 1000L ); }                   // reader の notify を待つ
        catch( InterruptedException m ) { }
      }
      buf[wp++] = _buf[i];
      used++;
    }
    notifyAll();  // reader が空で wait していれば起こす
    if( i > 0 ) PollKick.kick();  // issue #709 (案C): pipe readable → poll/epoll 待ちの poller を即起こす
    return( i );
  }

  // issue #551: 空きバイト数 (buf_size - used)。poll/epoll の POLLOUT 判定用。
  public synchronized int space( ) {
    return( buf_size - used );
  }
}

public class PipeManager extends XKernel {
  Vector pipetable; // パイプテーブル

  // issue #353: native(WHP) backend で apt が pipe read で永久ハングする件の調査用。
  //   pipe の connect/duplicate/disconnect を pipe_no + i/o_connected + 呼び出し
  //   thread (guest tid) 付きで出力し、どの pipe の o_connected が誰の close 漏れで
  //   0 に落ちないかのタイムラインを取る。EMULIN_TRACE_PIPE=1 で有効。
  static final boolean TRACE_PIPE = System.getenv( "EMULIN_TRACE_PIPE" ) != null;
  // issue #353: per-op トレース (TRACE_PIPE) は System.err I/O で native のタイミングを
  //   乱し race (hang) が再現しなくなる heisenbug。WATCHDOG は通常 read には一切 print
  //   せず、read が ~5s 以上 block した時だけ「詰まっている pipe + 全 pipe テーブルの
  //   in/out/used」をダンプする (ホットパス 0 オーバーヘッド = タイミングを乱さず再現)。
  static final boolean WATCHDOG = System.getenv( "EMULIN_PIPE_WATCHDOG" ) != null;
  // issue #562: pipe の blocking read を signal で中断するための per-thread pending-signal
  //   supplier。amd64_read が read 前に set (() -> process.psig() != -1)、Pipeinfo.read の
  //   blocking loop が pending を検知したら -EINTR(-4) を返す (SA_RESTART の再開は上位が扱う)。
  //   FUTEX_WAIT の sigPending (#533) と同じ仕組みを pipe read に広げたもの。
  public static final ThreadLocal<java.util.function.BooleanSupplier> SIG_PENDING = new ThreadLocal<>();
  static String pipeTag( ) {
    Thread t = Thread.currentThread( );
    return ( t instanceof GuestThread g ) ? ( "tid" + g.guestTid( )) : t.getName( );
  }
  // 全 pipe の状態を 1 行ずつダンプ (watchdog からのみ呼ぶ。best-effort、並行変更は無視)。
  void dumpPipes( String why ) {
    StringBuilder sb = new StringBuilder( "[pipe] TABLE-DUMP (" + why + "):\n" );
    try {
      for( int p = 0 ; p < pipetable.size( ) ; p++ ) {
        Pipeinfo pi = (Pipeinfo)pipetable.elementAt( p );
        if( pi == null ) continue;
        if( pi.i_connected != 0 || pi.o_connected != 0 || pi.used != 0 )
          sb.append( "    pipe_no=" + p + " in=" + pi.i_connected + " out="
              + pi.o_connected + " used=" + pi.used + "\n" );
      }
    } catch( Throwable t ) { sb.append( "    (dump interrupted: " + t + ")\n" ); }
    System.err.print( sb.toString( ) );
  }

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
    pipe.dbgPipeNo = pipetable.size( );
    pipe.mgr = this;
    pipetable.addElement( (Object)pipe );
    disp_pipe( pipetable.size( )-1 );
    if( TRACE_PIPE ) System.err.println( "[pipe] connect  pipe_no=" + ( pipetable.size( )-1 )
        + " in=" + pipe.i_connected + " out=" + pipe.o_connected + " " + pipeTag( ));
    return( pipetable.size( )-1 );
  }

  // issue #219: async I/O (SIGIO) の送り先 pid を pipe (read 端) に記録/取得する。
  public synchronized void set_async_owner( int pipe_no, int owner ) {
    if( pipe_no >= 0 && pipe_no < pipetable.size( ) ) {
      Pipeinfo p = (Pipeinfo)pipetable.elementAt( pipe_no );
      if( p != null ) p.async_owner = owner;
    }
  }
  public synchronized int get_async_owner( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size( ) ) return -1;
    Pipeinfo p = (Pipeinfo)pipetable.elementAt( pipe_no );
    return ( p != null ) ? p.async_owner : -1;
  }

  // 既に接続されているか調べる
  public boolean is_pipe_connected( int pipe_no ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    // 入力または出力の参照数が 0 なら切断されている。
    return( pipe.is_connected( ));
  }

  // issue #41 (sshd): pipe_no の buffer に未読 byte が何 byte 入っているか
  //   返す。poll の POLLIN 判定に使う。
  public int pipe_available( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size() ) return 0;
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) return 0;
    return pipe.available();
  }

  // issue #480: MSG_PEEK 用、非破壊読み出し。
  public int pipe_peek( int pipe_no, byte buf[] ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    return pipe.peek( buf );
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

  // issue #551: nonBlock 対応の pipe write。書けたバイト数 (>=0)、切断は -1。
  //   nonBlock=true で full なら 0 (caller が EAGAIN に変換)。
  public int pipe_write_nb( int pipe_no, byte buf[], boolean nonBlock ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    disp_pipe( pipe_no );
    if( !is_pipe_connected( pipe_no )) { return( -1 ); }
    return( pipe.writeNB( buf, nonBlock ));
  }

  // issue #551: pipe の空きバイト数。満杯 (0) なら poll/epoll で POLLOUT を立てない。
  public int pipe_space( int pipe_no ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) return( 0 );
    return( pipe.space( ) );
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
  // synchronized + notifyAll で wait 中の reader/writer を起こす
  // (i_connected または o_connected が 0 になると EOF / EPIPE 扱い)。
  public void disconnect_pipe( int pipe_no, boolean input_flag ) {
    Pipeinfo pipe = null;
    if( pipe_no < 0 )  {return;}

    pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) {return;}
    synchronized( pipe ) {
      // issue #353: 0 でクランプして負に振らせない。disconnect が connect/duplicate
      //   より多く呼ばれる over-disconnect (fork 分割と close の計数ずれ等) で
      //   i/o_connected が負になると、EOF 判定が「0 ちょうど」を待つ実装では永久に
      //   満たされず reader が hang していた (dpkg --configure --pending のトリガ
      //   処理で実機再現)。0 未満は「writer/reader 皆無 = EOF」と同義なのでクランプ。
      if( input_flag ) { if( pipe.i_connected > 0 ) pipe.i_connected--; }
      else             { if( pipe.o_connected > 0 ) pipe.o_connected--; }
      pipe.notifyAll();
    }
    if( TRACE_PIPE ) System.err.println( "[pipe] disconnect pipe_no=" + pipe_no
        + " dir=" + ( input_flag ? "in" : "out" ) + " -> in=" + pipe.i_connected
        + " out=" + pipe.o_connected + " " + pipeTag( ));
    if( sysinfo.verbose( )) {
      println( " ---- disconnect_pipe( " + pipe_no + " );  i_connected = " + pipe.i_connected + "  o_connected = " + pipe.o_connected );
    }
    disp_pipe( pipe_no );
  }

  // パイプをduplicate する。
  public void duplicate_pipe( int pipe_no, boolean input_flag ) {
    Pipeinfo pipe = (Pipeinfo)pipetable.elementAt( pipe_no );
    if( pipe == null ) {return;}

    synchronized( pipe ) {
      if( input_flag ) { pipe.i_connected++; }
      else             { pipe.o_connected++; }
    }
    if( TRACE_PIPE ) System.err.println( "[pipe] duplicate  pipe_no=" + pipe_no
        + " dir=" + ( input_flag ? "in" : "out" ) + " -> in=" + pipe.i_connected
        + " out=" + pipe.o_connected + " " + pipeTag( ));
    if( sysinfo.verbose( )) {
      println( " ---- duplicate_pipe( " + pipe_no + " );  i_connected = " + pipe.i_connected + "  o_connected = " + pipe.o_connected );
    }

    disp_pipe( pipe_no );

  }
}
