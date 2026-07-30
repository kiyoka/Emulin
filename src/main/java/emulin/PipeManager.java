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
  // issue #709 (案A): この pipe を poll/epoll で待つ poller の待ち行列。write (readable 化) /
  //   read (space 化) / disconnect (EOF/EPIPE 化) の状態遷移で wake する。pty も pipe pair
  //   裏打ち (set_pipe_pair) なので、pty master/slave の入出力もこの source で event 化される。
  final WaitHub.Source source = new WaitHub.Source();
  // issue #799: SOCK_DGRAM / SOCK_SEQPACKET の socketpair は **datagram 境界を保つ**。
  //   write 1 回 = 1 メッセージなので長さをここに積み、read は先頭 1 件分だけを返す
  //   (受信 buffer が足りなければ Linux 同様に切り捨て、残りは捨てる)。
  //   null = byte stream (通常の pipe / SOCK_STREAM / pty) = 従来動作。
  java.util.ArrayDeque<Integer> msgLens = null;
  // issue #802: 直近の datagram read が受信 buffer に収まらず**切り捨てられた**か。
  //   recvmsg が MSG_TRUNC を立てるのに使う。datagram モードでは余りを捨てるので、
  //   従来の「buffer full かつ pipe にデータが残っている」ヒューリスティックでは
  //   切り捨てを検出できない (それが #799 導入時の回帰になった)。
  boolean lastDgramTruncated = false;
  synchronized void setDatagramMode( ) {
    if( msgLens == null ) msgLens = new java.util.ArrayDeque<Integer>();
  }

  /** issue #109: datagram モードの不変条件 — **msgLens の総和がバッファ内バイト数と一致**。
   *  SOCK_DGRAM は境界を保つのが仕様なので、ここがずれたら境界が失われている
   *  (公開 #799 = 境界喪失で guest ハング、#802 = 余りの扱いで MSG_TRUNC 破綻)。 */
  boolean dgramLenSumMatches( ) {
    if( msgLens == null ) return true;   // byte stream モードは対象外
    long sum = 0;
    for( Integer n : msgLens ) sum += n.intValue();
    return sum == (long)used;
  }
  String dgramDebug( ) {
    long sum = 0;
    for( Integer n : ( msgLens == null ? new java.util.ArrayDeque<Integer>() : msgLens ) )
      sum += n.intValue();
    return "sum=" + sum + " used=" + used + " msgs=" + ( msgLens == null ? 0 : msgLens.size() );
  }

  public Pipeinfo( ) {
    buf = new byte[ buf_size ];
    used = 0;
    wp  = 0;
    rp  = 0;
    i_connected  = 1;
    o_connected = 1;
  }

  // issue #833: 両端が閉じた pipe の 64KB バッファを手放す。
  //   pipetable は append 専用 (pipe_no = guest の Fileinfo が持つ index) なので
  //   要素そのものは詰められないが、**バッファさえ解放すれば実害は消える**
  //   (殻は数十バイト)。放置すると「プロセス生存中に作った pipe の総数 x 64KB」が
  //   永久に残り、pipe を作り捨てし続けるワークロードが OOM でハングしていた。
  //
  //   ★ 未読データが残っている間は解放しない。POSIX 的には両端 close で
  //     データは捨ててよいが、emulin には issue #353 の over-disconnect
  //     (disconnect が connect/duplicate より多く呼ばれ、fd が生きているのに
  //      カウンタだけ先に 0 になる) 経路がある。used == 0 に限れば
  //     **捨てるものが何も無い**ので、この修正で挙動が変わることはない。
  synchronized void releaseBuffer( ) {
    if( buf == null ) return;
    if( used != 0 ) return;
    if( msgLens != null && !msgLens.isEmpty( ) ) return;
    buf = null;
    wp = 0;
    rp = 0;
    lastDgramTruncated = false;
  }

  // issue #833: 参照が 1 つも無いことが確定した pipe を捨てる (pty のペア解放用)。
  //   releaseBuffer と違い未読データが残っていても捨てる。呼び側が
  //   「誰も参照していない」ことを確認済であることが前提 (Kernel の flist 全走査)。
  synchronized void discardBuffer( ) {
    buf = null;
    used = 0;
    wp = 0;
    rp = 0;
    if( msgLens != null ) msgLens.clear( );
    lastDgramTruncated = false;
  }

  // issue #833: 解放後に再び使われたら確保し直す。
  //   ★ 「解放したら二度と使わない」前提は置けない (上記 over-disconnect)。
  //   呼ぶのは write 側だけ。read 側で呼ぶと、閉じた pipe を read するたびに
  //   64KB を確保し直してリークが復活する (read は used == 0 で即 EOF を返すので不要)。
  private void ensureBuffer( ) {
    if( buf == null ) {
      buf = new byte[ buf_size ];
      used = 0;
      wp = 0;
      rp = 0;
    }
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
  public int read( byte _buf[], boolean nonBlock ) {
    int r = readCore( _buf, nonBlock );
    // issue #709 (案A): 消費して空きができた → POLLOUT を待つ poller を起こす。
    if( r > 0 ) source.wake();
    return r;
  }
  private synchronized int readCore( byte _buf[], boolean nonBlock ) {
    if( msgLens != null ) return readDatagram( _buf, nonBlock );   // issue #799
    int i;
    int blockedTicks = 0;   // issue #353: TRACE_PIPE 用の block 継続カウンタ
    for( i = 0 ; i < _buf.length ; ) {
      if( rp >= buf_size ) { rp = 0; } // バッファのリング化
      // issue #833/#109: buf を手放すのは used == 0 のときだけなので、
      //   ここに来て used > 0 なら buf は必ず生きている。
      assert ( buf != null || used <= 0 )
          : Invariant.mark( "pipe.buf.released_with_data",
                            "pipe_no=" + dbgPipeNo + " used=" + used );
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

  // issue #799: datagram 境界を保つ read。**1 回の read = 1 メッセージ**。
  //   受信 buffer が足りない場合は Linux と同じく切り捨て、そのメッセージの残りは捨てる
  //   (次の read には持ち越さない = 境界が保たれる)。
  //   呼び出しは readCore から (synchronized 済み)。
  private int readDatagram( byte _buf[], boolean nonBlock ) {
    while( msgLens.isEmpty( ) ) {
      if( i_connected <= 0 || o_connected <= 0 ) return( 0 );   // 切断 = EOF
      if( nonBlock ) return( -2 );                              // EAGAIN
      {
        java.util.function.BooleanSupplier sp = PipeManager.SIG_PENDING.get();
        if( sp != null && sp.getAsBoolean() ) return( -4 );      // -EINTR
      }
      try { wait( 50L ); } catch( InterruptedException m ) { }
    }
    assert dgramLenSumMatches()
      : Invariant.mark( "datagram 読み出し前の msgLens 総和 == バッファ長", dgramDebug() );
    int mlen = msgLens.pollFirst( ).intValue( );
    int take = Math.min( mlen, _buf.length );
    lastDgramTruncated = ( mlen > _buf.length );   // issue #802: MSG_TRUNC 判定用
    for( int i = 0; i < take; i++ ) {
      if( rp >= buf_size ) rp = 0;
      _buf[i] = buf[rp++];
      used--;
    }
    for( int d = take; d < mlen; d++ ) {   // 切り捨てた分を捨てる
      if( rp >= buf_size ) rp = 0;
      rp++; used--;
    }
    notifyAll();
    return( take );
  }

  // issue #480: MSG_PEEK 用。buffer の先頭から available 分だけ非破壊で読む
  //   (rp/used は変更しない)。block はしない (peek は「今あるものだけ」返す)。
  public synchronized int peek( byte[] _buf ) {
    int n = Math.min( _buf.length, used );
    assert ( buf != null || n <= 0 )
        : Invariant.mark( "pipe.buf.released_with_data",
                          "peek pipe_no=" + dbgPipeNo + " used=" + used );
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
  public int writeNB( byte _buf[], boolean nonBlock ) {
    int r = writeNBCore( _buf, nonBlock );
    // issue #709 (案A): データが入った → POLLIN を待つ poller を起こす。
    if( r > 0 ) source.wake();
    return r;
  }
  private synchronized int writeNBCore( byte _buf[], boolean nonBlock ) {
    int i;
    if( i_connected <= 0 || o_connected <= 0 ) return( -1 );
    ensureBuffer( );   // issue #833: 解放済みなら確保し直す (切断チェックの後に置くこと)

    // issue #799: datagram モードは **1 メッセージ丸ごとか、何も書かないか** (Linux の
    //   datagram write は atomic で部分書込が無い)。空きが足りなければ待つ / EAGAIN。
    if( msgLens != null ) {
      if( _buf.length > buf_size ) return( -1 );   // buffer より大きい datagram は送れない
      while( buf_size - used < _buf.length ) {
        if( i_connected <= 0 || o_connected <= 0 ) return( -1 );
        if( nonBlock ) return( 0 );                // EAGAIN (呼び出し側が変換)
        source.wake();
        try { wait( 1000L ); } catch( InterruptedException m ) { }
      }
      for( i = 0; i < _buf.length; i++ ) {
        if( wp >= buf_size ) wp = 0;
        buf[wp++] = _buf[i];
        used++;
      }
      msgLens.addLast( Integer.valueOf( _buf.length ) );
      // issue #109: SOCK_DGRAM は**境界を保つ**のが仕様。msgLens の総和が
      //   バッファ内のバイト数と一致していなければ境界が失われている
      //   (公開 #799 = 境界喪失で guest ハング、#802 = 余りの扱いで MSG_TRUNC 破綻)。
      assert dgramLenSumMatches()
        : Invariant.mark( "datagram の msgLens 総和 == バッファ長", dgramDebug() );
      notifyAll();
      return( _buf.length );
    }

    for( i = 0 ; i < _buf.length ; i++ ) {
      if( wp >= buf_size ) { wp = 0; }           // バッファのリング化
      while( buf_size <= used ) {                // バッファフル
        if( i_connected <= 0 || o_connected <= 0 ) return( i > 0 ? i : -1 );
        if( nonBlock ) { if( i > 0 ) notifyAll(); return( i ); }  // 書けた分を返す (wake は wrapper)
        // issue #709 (案A): full で block する前に poller を起こす。長い blocking write の間
        //   バッファは full=確実に readable なのに、wrapper の wake は write 完了まで来ない。
        //   ここで起こさないと reader 側 poller が backstop まで寝てスループットが落ちる。
        source.wake();
        try { wait( 1000L ); }                   // reader の notify を待つ
        catch( InterruptedException m ) { }
      }
      buf[wp++] = _buf[i];
      used++;
    }
    notifyAll();  // reader が空で wait していれば起こす
    return( i );
  }

  // issue #551: 空きバイト数 (buf_size - used)。poll/epoll の POLLOUT 判定用。
  public synchronized int space( ) {
    return( buf_size - used );
  }
}

public class PipeManager extends XKernel {
  Vector pipetable; // パイプテーブル

  // issue #835: discard_pipe した slot を再利用する free list。
  //   pipetable は append 専用で pipe_no = その index なので、これが無いと
  //   「作った pipe の総数 x 168 バイト」(Pipeinfo + WaitHub.Source +
  //    ConcurrentHashMap + KeySetView) が永久に積み上がる。
  //
  //   ★ 再利用してよいのは「参照している fd が 1 つも無い」ことを**呼び側が
  //     確認済**の slot だけ。今のところ pty の解放経路 (Kernel の
  //     releasePtyIfUnreferenced が ptable 全体の flist を走査して確認する) だけが
  //     これを満たす。
  //   ★ 一般の disconnect 経路をここに乗せてはいけない。issue #353 の
  //     over-disconnect (disconnect が connect/duplicate より多く呼ばれ、fd が
  //     生きているのにカウンタだけ先に 0 になる) があるため、カウンタ 0 は
  //     「参照ゼロ」を意味しない。そこで slot を再利用すると**古い fd が新しい
  //     pipe を読み書きする** (別の相手にデータが混ざる) という、再現も追跡も
  //     極めて難しい壊れ方をする。
  private final java.util.ArrayDeque<Integer> free_slots = new java.util.ArrayDeque<Integer>();
  private final Object slot_lock = new Object();

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
    String base = ( t instanceof GuestThread g ) ? ( "tid" + g.guestTid( )) : t.getName( );
    // issue #759 診断: どの fd の read が詰まっているかを併記する。同じ pty (pipe_no) を
    //   複数の fd が指す (継承した stdin の dup 群 = blocking / 別 open した /dev/pts/N =
    //   O_NONBLOCK) ため、pipe_no だけでは「blocking な方を読んでいる」ことが判らない。
    String f = CUR_READ_FD.get( );
    return ( f != null ) ? ( base + " " + f ) : base;
  }

  // issue #759 診断: 現在 read 中の fd の説明 (FileAccess.FileRead が set)。
  //   WATCHDOG / TRACE_PIPE 有効時のみ設定される (通常運転では null = ゼロコスト)。
  public static final ThreadLocal<String> CUR_READ_FD = new ThreadLocal<>();
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
    pipe.mgr = this;
    int pipe_no;
    // issue #835: slot の確保は free list ごと 1 つのロックで直列化する。
    //   ★ 従来は addElement( ) の後に size( )-1 を読んでおり、Vector 自体は
    //     synchronized でも**この 2 手はアトミックでない**。2 thread が同時に
    //     connect_pipe すると両方が同じ番号を返し得た (pipe(2) と pty を別 thread が
    //     同時に作ると起きる)。free list 導入に合わせてここも閉じる。
    synchronized( slot_lock ) {
      Integer reuse = free_slots.pollFirst( );
      if( reuse != null ) {
        pipe_no = reuse.intValue( );
        pipe.dbgPipeNo = pipe_no;
        pipetable.setElementAt( (Object)pipe, pipe_no );
      }
      else {
        pipe_no = pipetable.size( );
        pipe.dbgPipeNo = pipe_no;
        pipetable.addElement( (Object)pipe );
      }
    }
    if( sysinfo.verbose( )) {
      println( " connect_pipe( ) : pipe_no = " + pipe_no );
    }
    disp_pipe( pipe_no );
    if( TRACE_PIPE ) System.err.println( "[pipe] connect  pipe_no=" + pipe_no
        + " in=" + pipe.i_connected + " out=" + pipe.o_connected + " " + pipeTag( ));
    return( pipe_no );
  }

  // issue #219: async I/O (SIGIO) の送り先 pid を pipe (read 端) に記録/取得する。
  public synchronized void set_async_owner( int pipe_no, int owner ) {
    if( pipe_no >= 0 && pipe_no < pipetable.size( ) ) {
      Pipeinfo p = pipe_at( pipe_no );
      if( p != null ) p.async_owner = owner;
    }
  }
  public synchronized int get_async_owner( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size( ) ) return -1;
    Pipeinfo p = pipe_at( pipe_no );
    return ( p != null ) ? p.async_owner : -1;
  }

  // issue #99 (leak check): まだ接続されたままの pipe 数。emulin 終了時に 0 でなければ
  //   「close 漏れ」か「終了処理を通らない経路」がある。診断専用で動作は変えない。
  public int debugConnectedPipes( ) {
    int n = 0;
    for( int i = 0; i < pipetable.size( ); i++ ) {
      Pipeinfo p = (Pipeinfo)pipetable.elementAt( i );
      if( p != null && p.is_connected( ) ) n++;
    }
    return n;
  }

  // issue #779: pipe_no は guest 由来 (Fileinfo.pipe_no) なので、無効値 (-1 や範囲外) が
  //   来ても例外を投げない。従来は pipetable.elementAt(-1) が
  //   ArrayIndexOutOfBoundsException になり syscall 実装を貫通してスレッドが死んでいた
  //   (fuzz が read(2) 経由で検出)。呼び側は null を「無効 pipe」として errno に変換する。
  Pipeinfo pipe_at( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size( ) ) return null;
    return (Pipeinfo)pipetable.elementAt( pipe_no );
  }

  // 既に接続されているか調べる
  public boolean is_pipe_connected( int pipe_no ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return( false );   // issue #779: 無効 pipe_no は未接続扱い
    // 入力または出力の参照数が 0 なら切断されている。
    return( pipe.is_connected( ));
  }

  // issue #41 (sshd): pipe_no の buffer に未読 byte が何 byte 入っているか
  //   返す。poll の POLLIN 判定に使う。
  public int pipe_available( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size() ) return 0;
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return 0;
    return pipe.available();
  }

  // issue #480: MSG_PEEK 用、非破壊読み出し。
  public int pipe_peek( int pipe_no, byte buf[] ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return 0;          // issue #779
    return pipe.peek( buf );
  }

  // パイプからリードする。
  public int pipe_read( int pipe_no, byte buf[] ) { return pipe_read( pipe_no, buf, false ); }
  public int pipe_read( int pipe_no, byte buf[], boolean nonBlock ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    // issue #779: 無効 pipe_no (-1 や範囲外) は -1 = 汎用エラー。amd64_read はこれを
    //   EBADF に変換する。従来は elementAt(-1) が例外になりスレッドが死んでいた。
    if( pipe == null ) return( -1 );
    disp_pipe( pipe_no );
    return( pipe.read( buf, nonBlock ));
  }

  // issue #802: 直近の datagram read が切り捨てられたか (recvmsg の MSG_TRUNC 用)。
  //   datagram モードでない pipe では常に false。
  public boolean pipe_last_truncated( int pipe_no ) {
    Pipeinfo pi = pipe_at( pipe_no );
    return pi != null && pi.lastDgramTruncated;
  }

  // パイプへライトする。
  public boolean pipe_write( int pipe_no, byte buf[] ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return( false );   // issue #779

    disp_pipe( pipe_no );

    // 切断されていたらリード失敗
    if( !is_pipe_connected( pipe_no )) { return( false ); }

    return( pipe.write( buf ));
  }

  // issue #551: nonBlock 対応の pipe write。書けたバイト数 (>=0)、切断は -1。
  //   nonBlock=true で full なら 0 (caller が EAGAIN に変換)。
  public int pipe_write_nb( int pipe_no, byte buf[], boolean nonBlock ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return( -1 );      // issue #779
    disp_pipe( pipe_no );
    if( !is_pipe_connected( pipe_no )) { return( -1 ); }
    return( pipe.writeNB( buf, nonBlock ));
  }

  // issue #551: pipe の空きバイト数。満杯 (0) なら poll/epoll で POLLOUT を立てない。
  public int pipe_space( int pipe_no ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return( 0 );
    return( pipe.space( ) );
  }

  // issue #709 (案A): poll/epoll の poller が subscribe する pipe の待ち行列を返す。
  public WaitHub.Source pipe_source( int pipe_no ) {
    if( pipe_no < 0 || pipe_no >= pipetable.size( ) ) return null;
    Pipeinfo pipe = pipe_at( pipe_no );
    return( pipe == null ? null : pipe.source );
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

    pipe = pipe_at( pipe_no );
    if( pipe == null ) {return;}
    synchronized( pipe ) {
      // issue #353: 0 でクランプして負に振らせない。disconnect が connect/duplicate
      //   より多く呼ばれる over-disconnect (fork 分割と close の計数ずれ等) で
      //   i/o_connected が負になると、EOF 判定が「0 ちょうど」を待つ実装では永久に
      //   満たされず reader が hang していた (dpkg --configure --pending のトリガ
      //   処理で実機再現)。0 未満は「writer/reader 皆無 = EOF」と同義なのでクランプ。
      if( input_flag ) { if( pipe.i_connected > 0 ) pipe.i_connected--; }
      else             { if( pipe.o_connected > 0 ) pipe.o_connected--; }
      // issue #833: 両端が閉じたら 64KB バッファを手放す。
      //   pipetable は append 専用なので、これをやらないと
      //   「作った pipe の総数 x 64KB」が永久に残る。
      if( pipe.i_connected <= 0 && pipe.o_connected <= 0 ) pipe.releaseBuffer( );
      pipe.notifyAll();
    }
    // issue #709 (案A): 切断は EOF (POLLIN/POLLHUP) / EPIPE 遷移 → 待機中の poller を起こす。
    pipe.source.wake();
    if( TRACE_PIPE ) System.err.println( "[pipe] disconnect pipe_no=" + pipe_no
        + " dir=" + ( input_flag ? "in" : "out" ) + " -> in=" + pipe.i_connected
        + " out=" + pipe.o_connected + " " + pipeTag( ));
    if( sysinfo.verbose( )) {
      println( " ---- disconnect_pipe( " + pipe_no + " );  i_connected = " + pipe.i_connected + "  o_connected = " + pipe.o_connected );
    }
    disp_pipe( pipe_no );
  }

  // issue #833: 誰も参照していないことが確定した pipe を捨てる。
  //   pty は裏打ちに pipe を 2 本使うが (master→slave / slave→master)、
  //   PtyManager の解放経路がこの 2 本を放置していたため、pty を作り捨てする
  //   ワークロード (シェル / sshd / 端末アプリ) で 1 pty あたり 128KB が
  //   永久に残っていた。
  public void discard_pipe( int pipe_no ) {
    Pipeinfo pipe = pipe_at( pipe_no );
    if( pipe == null ) return;
    synchronized( pipe ) {
      pipe.i_connected = 0;
      pipe.o_connected = 0;
      pipe.discardBuffer( );
      pipe.notifyAll( );
    }
    pipe.source.wake( );
    // issue #835: slot を空けて再利用可能にする。ここまで来た時点で
    //   「参照している fd は 1 つも無い」ことは呼び側が確認済 (上の注記参照)。
    //   ★ 同じ slot を 2 度積まないよう、まだ自分が載っていることを確認する
    //     (二重 discard で free list に重複が入ると、1 つの slot を 2 本の
    //      pipe が同時に使うことになる)。
    synchronized( slot_lock ) {
      if( pipe_no >= 0 && pipe_no < pipetable.size( )
          && pipetable.elementAt( pipe_no ) == pipe ) {
        pipetable.setElementAt( null, pipe_no );
        free_slots.addLast( Integer.valueOf( pipe_no ) );
      }
    }
    if( TRACE_PIPE ) System.err.println( "[pipe] discard  pipe_no=" + pipe_no );
  }

  // issue #835 診断: 再利用待ちの slot 数 (pipetable.size( ) との差でリークが判る)。
  public int debugFreeSlotCount( ) {
    synchronized( slot_lock ) { return free_slots.size( ); }
  }

  // パイプをduplicate する。
  public void duplicate_pipe( int pipe_no, boolean input_flag ) {
    Pipeinfo pipe = pipe_at( pipe_no );
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
