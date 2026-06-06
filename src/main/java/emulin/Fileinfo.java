// ----------------------------------------
//  File Information
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import java.net.*;
import emulin.*;

public class Fileinfo
{
  int opened;
  String name;
  RandomAccessFile f;
  String mode;
  int ptr;
  int mode_bit;
  int c_iflag;
  int c_oflag;
  int c_cflag;
  int c_lflag;
  byte c_line;
  byte c_cc[];
  boolean std_flag;
  boolean stderr_flag;
  boolean null_flag;     // /dev/null: read=EOF / write=discard
  boolean urandom_flag;  // /dev/urandom, /dev/random: read=乱数バイト
  byte[]  memContent;    // in-memory 合成ファイル (/proc/self/maps 等)、逐次 read
  int     memPos;        // memContent の read 位置
  boolean pipe_in_flag;
  boolean pipe_out_flag;
  int pipe_no;
  // socketpair 双方向: read 側は pipe_no、write 側は pipe_write_no を使う。
  //   通常 pipe では -1 のまま (= write も pipe_no で行う、後方互換)。
  //   socketpair では 2 つの異なる pipe を割り当て、両端で対称に持つ。
  int pipe_write_no;
  boolean socket_flag;
  boolean stream_flag;
  String  ip_str;
  int     ip;
  int     port;
  int     back_log;
  Socket         conn;
  ServerSocket   sconn;
  DatagramSocket dgram;
  SubProcess     subprocess;
  // issue #9: AF_UNIX (Unix domain socket) — JDK 21 の SocketChannel +
  //   StandardProtocolFamily.UNIX で host file system 上の Unix socket に
  //   接続する。ssh-agent / nscd / D-Bus 等が target。socket_flag=true /
  //   stream_flag=true で識別、unixSocket が非 null かどうかで AF_UNIX か
  //   AF_INET を見分ける。
  java.nio.channels.SocketChannel unixSocket;
  // issue #43 Phase 4-4: AF_UNIX server 側の ServerSocketChannel。
  //   bind/listen で作って、accept で SocketChannel に変換する。
  java.nio.channels.ServerSocketChannel unixServer;
  // amd64_poll が non-blocking accept で先取りした SocketChannel。
  //   次の accept() syscall がこれを優先 consume する。
  java.nio.channels.SocketChannel unixQueued;
  // issue #9: AF_INET6 socket か。socket() で AF_INET6 が指定されたら true、
  //   AF_INET なら false。connect 等で sockaddr_in6 を使うかの判定に使う。
  boolean family_v6;
  // issue #9: AF_INET6 UDP の connected dest (connect() で保存)。sendto を
  //   addr 省略で呼んだとき / sendmsg で msg_name=NULL のときに使う。
  byte[] connected_v6_addr;  // 16 byte、null なら未接続
  int    connected_v6_port;

  // MSG_PEEK 用のバッファ。recvfrom(MSG_PEEK) でいくつか読んだあと、
  //   実際の read/recvfrom でその先頭バイト群を再消費させる。
  byte[]   peekBuf;
  int      peekLen;
  // socket / pipe が EOF に到達したかどうか。peek/read で Java 側の
  //   EOF を検知したら立てる。pselect6 がこのフラグをチェックして
  //   EOF 後の無限ポーリングを止める。
  boolean  socketEof;
  // issue #41 Phase 2: pty (/dev/ptmx master) であるかと、対応 ptn 番号。
  //   ioctl(TIOCGPTN) で *addr に書き出す。slave 側 fd には ptn は持たせない
  //   (path /dev/pts/N から抽出済)。
  boolean  pty_master;
  boolean  pty_slave;
  int      pty_ptn = -1;
  // issue #102: tcsetpgrp (ioctl TIOCSPGRP) が設定した foreground process
  //   group。tcgetpgrp (TIOCGPGRP) はこれを返し、bash の job-control 初期化
  //   (tcsetpgrp で設定 → tcgetpgrp で読み戻して shell_pgrp と一致するか検証)
  //   を成立させる。-1 = 未設定で、TIOCGPGRP は従来どおり sys_getpgrp に
  //   fallback する (ash の setjobctl ループ互換)。
  int      tty_fg_pgrp = -1;
  // O_NONBLOCK が立っているかどうか。fcntl(F_SETFL) で設定される。
  //   非 blocking read で peekBuf 空 + データ未着なら EAGAIN を返す。
  boolean  nonBlock;
  // issue #219: 非同期 I/O (O_ASYNC + F_SETOWN)。emacs 等は端末 fd に O_ASYNC を
  //   立て F_SETOWN で自分を owner にし、入力到着時に SIGIO で読み取る。
  //   async=O_ASYNC 有効、async_owner=SIGIO 送り先 pid (F_SETOWN)。
  boolean  async;
  int      async_owner;
  // 注意: FD_CLOEXEC は Fileinfo (= POSIX open file description) ではなく
  //   fd table のフラグなので FileAccess.cloexec_fds で per-fd 管理している
  //   (Phase 27 step 39)。dup/dup2 で Fileinfo を共有しても cloexec は別。
  // UDP socket: poll が短い setSoTimeout で先読みしたパケット 1 つを
  //   ここに溜める。次の recvfrom が cache を消費。Java DatagramSocket
  //   には available() が無いので、これで「読めるか」を判定する。
  java.net.DatagramPacket cachedDatagram;

  // issue #78 (node/libuv): eventfd / timerfd / epoll の状態。
  //   いずれも anonymous fd で、read/write/poll を特別扱いする。
  boolean eventfd_flag;
  long    eventfd_count;       // 現在のカウンタ値 (read で 0 or -1、write で加算)
  boolean eventfd_semaphore;   // EFD_SEMAPHORE: read は 1 ずつ
  boolean timerfd_flag;
  long    timerfd_expire_ms;   // 次回満了の絶対時刻 (currentTimeMillis 基準)。0=未武装
  long    timerfd_interval_ms; // 周期 (0=one-shot)
  // epoll: 監視対象 fd → {events, data_lo, data_hi}。
  boolean epoll_flag;
  java.util.LinkedHashMap<Integer,long[]> epoll_interest;

  // issue #131: /proc/<pid>/fd の合成 directory fd。tmux/openssh の closefrom
  //   等が opendir で fd を列挙する経路で必要。fstat は S_IFDIR、getdents64
  //   は SyscallAmd64 側で flist を走査して entries を合成する。
  boolean proc_fd_dir;

  // issue #131 (tmux layer 9): listen socket の bind 後、tmux libevent は
  //   listen fd を event loop に「accept 待ち」として登録する。emulin の poll
  //   は accept() が null を返したら POLLIN を立てず、tmux は次の poll で再度
  //   listen fd を試行 — そのループ自体が tmux 内で発生せず、listen fd が
  //   2 回 poll された後 event loop が回らなくなる現象を解決するため、
  //   bind 直後と「accept が成功していない間」は POLLIN を必ず立てる。
  //   accept 後の queued 状態 (unixQueued != null) は通常経路で POLLIN を返す。
  boolean listenPollinReady;

  Fileinfo( ) {
    opened = 0;
    c_cc = new byte[19];
    c_iflag = 0x500;
    c_oflag = 0x01;
    c_cflag = 0xBF;
    c_lflag = 0x8A3B;
    c_line = (byte)0;
    c_cc[ 0] =  (byte)0x03;    c_cc[ 1] = (byte)0x1C;    c_cc[ 2] =  (byte)0x08;
    c_cc[ 3] =  (byte)0x00;    c_cc[ 4] = (byte)0x04;
    c_cc[ 5] =  (byte)0x00;    c_cc[ 6] = (byte)0x01;    c_cc[ 7] =  (byte)0x00;
    ptr = 0;
    std_flag    = false;
    stderr_flag = false;
    null_flag   = false;
    pipe_in_flag  = false;
    pipe_out_flag = false;
    socket_flag   = false;
    pipe_no       = -1;
    pipe_write_no = -1;
    socket_flag   = false;
    stream_flag   = false;
    conn          = null;
    sconn         = null;
    dgram         = null;
    subprocess    = null;
  }
  
  // 複製
  public Fileinfo duplicate( ) {
    Fileinfo _finfo = new Fileinfo( );
    _finfo.opened = opened;
    _finfo.ptr    = ptr;
    _finfo.std_flag       = std_flag;
    _finfo.stderr_flag    = stderr_flag;
    _finfo.null_flag      = null_flag;
    _finfo.pipe_in_flag   = pipe_in_flag;
    _finfo.pipe_out_flag  = pipe_out_flag;
    _finfo.pipe_no        = pipe_no;
    _finfo.pipe_write_no  = pipe_write_no;
    _finfo.socket_flag    = socket_flag;
    _finfo.stream_flag    = stream_flag;
    _finfo.conn           = conn;
    _finfo.unixSocket     = unixSocket;  // issue #9
    // issue #113: fork した子も AF_UNIX listen socket を継承する必要がある。
    //   旧実装は unixServer を未コピーで、gpg-agent が bind/listen 後に fork した
    //   daemon child が listen socket を失い accept できずハングしていた。
    _finfo.unixServer     = unixServer;
    _finfo.unixQueued     = unixQueued;
    _finfo.subprocess     = subprocess;
    _finfo.nonBlock       = nonBlock;
    // issue #131: /proc/<pid>/fd 合成 directory flag を継承。fork 後の子も
    //   親が opendir した /proc fd を継承するが、getdents64 では子自身の flist
    //   を走査する (Fileinfo は flist の参照を持たないため、SyscallAmd64 側で
    //   現在の process の flist を見る)。
    _finfo.proc_fd_dir    = proc_fd_dir;
    // issue #131 (tmux): pty フラグを継承する。tmux server は /dev/pts/N を
    //   open (pty_slave=true) して子に fork で渡す。旧 duplicate は pty_slave
    //   をコピーしておらず、子の ioctl(0,TCGETS) (= dup2 された slave fd) が
    //   pty 認識を失って ENOTTY (-25) → 子が exit 1 → session が即終了。
    _finfo.pty_master     = pty_master;
    _finfo.pty_slave      = pty_slave;
    _finfo.pty_ptn        = pty_ptn;
    // issue #131 (layer 9): listen socket pollin ready flag は親プロセスの
    //   状態をそのまま継承 (fork 後 child は通常 listen socket を使わない)。
    _finfo.listenPollinReady = listenPollinReady;
    return( _finfo );
  }

  // ストリームソケットかデータグラムソケットかを指定する
  public void set_socket_type( boolean _stream_flag ) {
    stream_flag = _stream_flag;
  }

  // issue #9: AF_INET6 用 client socket。16 byte IPv6 address を受け取り
  //   Inet6Address 経由で Java Socket を開く。read/write/close は v4 と
  //   同じ path (Fileinfo.conn 上で透過)。
  public boolean client_socket_v6( byte[] ipv6_addr, int _port ) {
    port = _port;
    boolean trace_net = System.getenv("EMULIN_TRACE_NET") != null;
    if( ipv6_addr == null || ipv6_addr.length != 16 ) return false;
    try {
      java.net.Inet6Address v6 = (java.net.Inet6Address)
          java.net.Inet6Address.getByAddress( null, ipv6_addr, 0 );
      ip_str = v6.getHostAddress();
      if( trace_net ) System.err.println("DBG client_socket_v6: connecting to ["+ip_str+"]:"+_port);
      if( stream_flag ) {
        conn = new Socket( v6, _port );
        try { conn.setReceiveBufferSize( 4 * 1024 * 1024 ); }
        catch ( IOException ignored ) {}
        if( trace_net ) System.err.println("DBG client_socket_v6: connected rcvbuf="+conn.getReceiveBufferSize());
      }
    } catch ( IOException m ) {
      if( trace_net ) System.err.println("DBG client_socket_v6: FAILED "+m);
      return false;
    }
    return true;
  }

  // クライアントソケットとして初期化する。
  public boolean client_socket( int _ip, int _port ) {
    boolean  ret = true;
    ip_str = Util.ip_str( Util.swap32( _ip ));
    ip     = _ip;
    port   = _port;
    boolean trace_net = System.getenv("EMULIN_TRACE_NET") != null;
    if( trace_net ) System.err.println("DBG client_socket: connecting to "+ip_str+":"+_port);
    if( stream_flag ) {
      try {
        conn = new Socket( ip_str, _port );
        // Phase 33-6: Windows native で git clone 大 repo が中盤で server
        // 側 timeout (curl 18, HTTP/2 CANCEL 等) になる根本原因は emulator
        // の slow CPU で TCP 受信が遅延 → server flow control が backpressure
        // を検出して connection を打ち切る、というシナリオ。
        // SO_RCVBUF を大きく (4 MB) すると kernel buffer 内に多めに queue
        // できるので、emulator が裏で processing している間 server 側は
        // 「client は受信中」と認識し続けてくれる。
        try { conn.setReceiveBufferSize( 4 * 1024 * 1024 ); }
        catch ( IOException ignored ) {}
        if( trace_net ) System.err.println("DBG client_socket: connected rcvbuf="+conn.getReceiveBufferSize());
      }
      catch ( IOException m ) { if( trace_net ) System.err.println("DBG client_socket: FAILED "+m); ret = false; }
      {
	  //	  boolean val = false;
	  //	  int error_flag = 0;
	  //	  try { val = conn.getTcpNoDelay( ); }
	  //	  catch ( IOException m ) { error_flag = 1; }
	  //	  try { conn.setTcpNoDelay( true ); }
	  //	  catch ( IOException m ) { error_flag = 2; }
	  //	  try { conn.setSendBufferSize( 1 ); }
	  //	  catch ( IOException m ) { error_flag = 2; }
	  //	  System.out.println( " getTcpNoDelay( ) = " + val + " error flag = " + error_flag );
      }
    }
    else {
      //      System.out.println( "DEBUG: 1  ip_str = " + ip_str );
      //      System.out.println( "DEBUG: 2  port = " + port );
    }
    return( ret );
  }

  // サーバーソケットを作成する。
  public boolean make_server_socket( int port ) {
    if( stream_flag ) {
      try { sconn = new ServerSocket( port, back_log ); }
      catch ( IOException m ) { return( false ); }
    }
    else {
      if( port < 0 ) {        
	try { dgram = new DatagramSocket( );  }
	catch ( SocketException m ) { return( false ); }
      }
      else { 
	dgram.close( );
	try { dgram = new DatagramSocket( port ); }
	catch ( SocketException m ) { return( false ); }
      }
    }
    return( true );
  }

  // サーバーソケットインスタンスをセットする
  public void set_server_socket( Socket _conn ) {
    conn = _conn;
  }

  // サーバーソケットとして初期化する。
  //  public boolean server_socket( ) {
  //    boolean  ret = true;
  //    try { conn = sconn.accept( ); }
  //    catch ( IOException m ) { ret = false; }
  //    return( ret );
  //  }

  // サーバーソケットインスタンスを返す。
  public ServerSocket get_sconn( ) {
    return( sconn );
  }

  // ソケットが接続済か？
  public boolean is_connected( ) {
    boolean ret = true;
    if( conn == null ) { ret = false; }
    else {
      if( false ) { /* これでは接続先がクローズされたのを検出できない */
	int len = 0;
	InputStream s = null;
	byte buf[] = new byte[1];
	try{ s =  conn.getInputStream( ); }
	catch ( IOException m ) { return( false ); }
	s.mark( 1 );
	try{ len = s.read( buf ); }
	catch ( IOException m ) { ret = false; }
	try{ s.reset( ); }
	catch ( IOException m ) { ret = false; }
	if( len < 0 ) {
	  ret = false;
	}
	//	System.out.println( "DEBUG: available  len = " + len );
      }
    }
    return( ret );
  }

  // ファイルの複製
  void duplicate_file( Sysinfo sysinfo ) {
    opened++;
    if( sysinfo.verbose( )) {
      sysinfo.kernel.println( " Fileinfo.duplicate_file( )   opened = " + opened );
    }
  }

  // リード
  public int Read( byte[] buf ) {
    int ret = 0;
    InputStream s = null;
    if( null_flag ) { return 0; }  // /dev/null read は即 EOF
    if( urandom_flag ) {           // /dev/urandom: 要求 byte 数だけ乱数を返す
      SyscallAmd64.fillRandom( buf );  // issue #98: EMULIN_DET_RANDOM で決定化可
      return buf.length;
    }
    if( memContent != null ) {     // 合成ファイル (/proc/self/maps): 逐次 read + EOF
      int rem = memContent.length - memPos;
      if( rem <= 0 ) return 0;
      int take = Math.min( rem, buf.length );
      System.arraycopy( memContent, memPos, buf, 0, take );
      memPos += take;
      return take;
    }
    // issue #9: AF_UNIX (Unix domain socket) は SocketChannel.read() で読む。
    if( unixSocket != null ) {
      // issue #43 Phase 4-4 完走: poll が先読みして peekBuf に積んだ byte を
      //   優先 consume する。これを忘れると agent forwarding で「communication
      //   with agent failed」 (peek した byte が捨てられて以後の read 内容と
      //   ズレる) になる。
      if( peekBuf != null && peekLen > 0 ) {
        int take = Math.min( peekLen, buf.length );
        System.arraycopy( peekBuf, 0, buf, 0, take );
        int rest = peekLen - take;
        if( rest > 0 ) System.arraycopy( peekBuf, take, peekBuf, 0, rest );
        peekLen = rest;
        if( rest == 0 ) peekBuf = null;
        return take;
      }
      try {
        // poll で non-blocking にしていた場合があるので、明示的に blocking に
        // 戻す (caller は blocking read を期待することが多い)。
        try { unixSocket.configureBlocking( true ); }
        catch ( IOException ignored ) {}
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap( buf );
        int n = unixSocket.read( bb );
        if( n < 0 ) { socketEof = true; return 0; }  // EOF
        return n;
      } catch ( IOException m ) { return -1; }
    }
    if( isSOCKET( )) {
      if( stream_flag ) {
	if( null == conn ) { return( -1 ); }
	// peekBuf からの再消費を優先 (MSG_PEEK で先読みしたバイト)
	if( peekBuf != null && peekLen > 0 ) {
	  int take = Math.min( peekLen, buf.length );
	  System.arraycopy( peekBuf, 0, buf, 0, take );
	  int rest = peekLen - take;
	  if( rest > 0 ) System.arraycopy( peekBuf, take, peekBuf, 0, rest );
	  peekLen = rest;
	  if( rest == 0 ) peekBuf = null;
	  return take;
	}
	if( socketEof ) return 0;  // 既に EOF 検出済 → 即 0
	try{ s =  conn.getInputStream( ); }
	catch ( IOException m ) { ret = -1; return( ret ); }
	// 非 blocking モードでは「即返す」必要がある。Java の available() は
	//   InputStream の内部バッファだけ見るので、kernel に届いていても 0 を
	//   返すことがある (= EAGAIN ループの原因)。そこで setSoTimeout(短)
	//   経由で実 read を試行し、SocketTimeoutException で「データなし」と
	//   判定する。socketEof は read 後に確認する。
	if( nonBlock ) {
	  if( SyscallAmd64.DET_SOCKET ) {  // issue #113: 決定的 chunking (固定 4096 byte を blocking で正確に読む)
	    int want = Math.min( buf.length, 4096 ); int got = 0;
	    try { conn.setSoTimeout( 0 );
	      while( got < want ) { int n = s.read( buf, got, want - got ); if( n < 0 ) break; got += n; }
	    } catch ( IOException m ) {}
	    if( got == 0 ) { socketEof = true; return 0; }
	    return got;
	  }
	  try {
	    int prev = conn.getSoTimeout();
	    conn.setSoTimeout( 1 );  // 1ms の short timeout
	    try {
	      ret = s.read( buf );
	    } catch ( java.net.SocketTimeoutException ste ) {
	      conn.setSoTimeout( prev );
	      return -2;  // EAGAIN sentinel
	    }
	    conn.setSoTimeout( prev );
	  } catch ( IOException m ) { ret = 0; socketEof = true; return( ret ); }
	  if( ret == -1 ) { ret = 0; socketEof = true; }
	  return ret;
	}
	try{ ret = s.read( buf ); }
	catch ( IOException m ) {
	  // Phase 33: 旧実装は IOException → ret=0 (EOF) で返していた。
	  // GnuTLS は EOF without close_notify を -110 (PREMATURE_TERMINATION)
	  // と判定し、git clone 中盤で「TLS connection was non-properly
	  // terminated」エラーになる (Windows JVM で発生報告)。
	  // EOF と「真の I/O エラー」を区別しないと TLS が正しく recover
	  // できないので、IOException は EBADF (-9) で返す (= read error、
	  // EOF ではない)。socketEof は立てない。
	  if( System.getenv("EMULIN_TRACE_NET") != null )
	    System.err.println("DBG Fileinfo.Read IOException (return -1): "+m);
	  return -1;
	}
	// Phase 33-5: Windows JVM 既知挙動の保険。read が -1 (= EOF) を返した
	// 直後でも internal buffer にまだ data が残っている場合があり、curl/git
	// が「811 bytes 残し close」のように pack 末尾を取りこぼす。
	// EOF を返す前に available() で残 byte を 1 回だけ吸い上げる。
	if( ret == -1 ) {
	  try {
	    int avail = s.available();
	    if( avail > 0 ) {
	      int n = Math.min( avail, buf.length );
	      int got = s.read( buf, 0, n );
	      if( got > 0 ) {
	        if( System.getenv("EMULIN_TRACE_NET") != null )
	          System.err.println("DBG Fileinfo.Read drain after EOF: got="+got);
	        return got;
	      }
	    }
	  } catch ( IOException ignored ) { /* fall through to EOF */ }
	  ret = 0; socketEof = true;
	}
	}
      else {
	//	System.out.println( " Fileinfo.Read( read from dgram socket ) " );
      }
    }
    else {
      // RAF 無し (f==null) の通常 read 経路 = opendir で開いた directory fd
      //   (null/urandom/socket 等は上で処理済)。read(2) on directory は EISDIR。
      if( f == null ) return -21;  // -EISDIR
      try{ ret = f.read( buf ); }
      catch ( IOException m ) { ret = -1; return( ret ); }
      if( ret == -1 ) { ret = 0; }
    }
    return( ret );
  }

  // MSG_PEEK 相当: 読み出すが peekBuf に入れて次回の Read で再消費させる。
  //   既に peekBuf に何かあればそれをまず先頭にコピーし (消費はせず)、
  //   足りなければ socket から追加で読んで peekBuf に append。
  public int Peek( byte[] buf ) {
    if( !isSOCKET() || !stream_flag || conn == null ) return -1;
    int want = buf.length;
    int filled = 0;
    // 既存 peekBuf 分をそのままコピー (まだ消費しない)
    if( peekBuf != null && peekLen > 0 ) {
      int take = Math.min( peekLen, want );
      System.arraycopy( peekBuf, 0, buf, 0, take );
      filled = take;
    }
    if( filled >= want ) return filled;
    if( socketEof ) return filled;  // EOF に達していたら追加読みは諦める
    // 不足分を socket から取得し、peekBuf に append (これも消費しない)
    InputStream s;
    try { s = conn.getInputStream(); }
    catch ( IOException m ) { return filled > 0 ? filled : -1; }
    // 非 blocking モード or peekBuf に既にデータあり (filled > 0) の場合は
    //   available() を使って即時 readable のみ取得する。peek が無限 block
    //   して wget が EOF を見逃す事故を回避。
    int avail;
    try { avail = s.available(); }
    catch ( IOException m ) { socketEof = true; return filled; }
    if( avail <= 0 ) {
      // 非 blocking なら EAGAIN sentinel、blocking で filled>0 ならそのまま返す
      if( nonBlock && filled == 0 ) return -2;
      if( filled > 0 ) return filled;
    }
    int toRead = Math.min( want - filled, avail > 0 ? avail : (want - filled) );
    byte[] more = new byte[ toRead ];
    int got;
    try { got = s.read( more ); }
    catch ( IOException m ) { socketEof = true; return filled; }
    if( got <= 0 ) { socketEof = true; return filled; }
    // buf にコピーするのは「peekBuf 既存分」+ 「socket からの新規 got bytes」
    // の合計のうち、まだ buf に書いてない部分。peekBuf 既存分は filled として
    // 既にコピー済みなので、ここでは got 分を buf の filled 位置以降に書く。
    int copyToBuf = Math.min( got, want - filled );
    System.arraycopy( more, 0, buf, filled, copyToBuf );
    int oldLen = (peekBuf != null) ? peekLen : 0;
    byte[] nb = new byte[ oldLen + got ];
    if( oldLen > 0 ) System.arraycopy( peekBuf, 0, nb, 0, oldLen );
    System.arraycopy( more, 0, nb, oldLen, got );
    peekBuf = nb;
    peekLen = oldLen + got;
    return filled + copyToBuf;
  }

  // ライト
  public boolean Write( byte[] buf ) {
    boolean ret = true;
    OutputStream s = null;
    if( null_flag ) { return true; }  // /dev/null write は黙って discard
    // issue #9: AF_UNIX socket は SocketChannel.write() で書く。
    if( unixSocket != null ) {
      try {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap( buf );
        while( bb.hasRemaining() ) unixSocket.write( bb );
        return true;
      } catch ( IOException m ) { return false; }
    }
    if( isSOCKET( )) {
      if( stream_flag ) {
	if( null ==  conn ) { return( false ); }
	else {
	  try{ s =  conn.getOutputStream( ); }
	  catch ( IOException m ) { return( false ); }
	}
	try{ s.write( buf ); s.flush(); }
	catch ( IOException m ) { ret = false; }
      }
      else {
	//	System.out.println( " Fileinfo.Write( DGRAM ) " );
	ret = sendto( buf );
      }
    }
    else {
      if( f == null ) {
	// issue #191: f (RandomAccessFile) が無い fd への write は NPE で
	//   process を crash させていた (Thread が死んで親が hang)。例えば
	//   guest が directory を open して write した場合 (apt が /tmp を
	//   O_TMPFILE 的に開く経路等) に発生。crash させず write 失敗 (false) で
	//   返す。EMULIN_TRACE_WRITE=1 で fd 種別を出す。
	if( System.getenv("EMULIN_TRACE_WRITE") != null )
	  System.err.println("DBG_WRITE_NULLF name='"+name+"' pty_ptn="+pty_ptn
	    +" proc_fd_dir="+proc_fd_dir+" mem="+(memContent!=null));
	ret = false;
      }
      else try{ f.write( buf ); }
      catch ( IOException m ) { ret = false; }
    }      
    return( ret );
  }

  // 入力になんらかのイベントがあったか？
  public boolean isEvent( ) {
    if( null == subprocess ) {
      return( false );
    }
    else {
      return( subprocess.isEvent( ));
    }
  }

  // ファイルがオープンされているか？
  public boolean isOPEN( ) {
    if( null == subprocess ) {
      return( opened > 0 );
    }
    else {
      return( subprocess.isOPEN( ));
    }
  }

  // ファイルがクローズされているか？
  public boolean isCLOSE( ) {
    if( null == subprocess ) {
      return( opened <= 0 );
    }
    else {
      return( subprocess.isCLOSE( ));
    }
  }

  // ファイルがクローズされているか？
  public boolean Available( ) {
    if( null == subprocess ) {
      return( false );
    }
    else {
      return( subprocess.Available( ));
    }
  }

  public int get_mode_bit( ) {
    return( mode_bit );
  }

  public boolean isSTD( ) {
    return( std_flag );
  }

  public boolean isERR( ) {
    return( stderr_flag );
  }

  public void set_ptr( int _ptr ) {
    ptr = _ptr;
  }

  public int get_ptr( ) {
    return( ptr );
  }

  public boolean opendir( String _name ) {
    boolean ret = true;
    name = _name;
    opened = 1;
    return( ret );
  }

  public boolean open( String _name, String mode, int _mode_bit ) {
    boolean ret = true;
    name = _name;
    opened = 1;
    File file;
    mode_bit = _mode_bit;
    if( _name.equals( "<std>" )) { // 標準入出力
      std_flag = true;
      return( ret );
    }
    if( _name.equals( "<err>" )) { // エラー入出力
      stderr_flag = true;
      return( ret );
    }
    if( _name.equals( "<null>" )) { // /dev/null
      null_flag = true;
      return( ret );
    }
    if( _name.equals( "<urandom>" )) { // /dev/urandom, /dev/random
      urandom_flag = true;
      return( ret );
    }
    if( _name.equals( "<procmaps>" )) { // /proc/self/maps (memContent は caller が設定)
      return( ret );
    }
    if( _name.equals( "<pipe>" )) { // パイプ
      if( mode.equals( "r" )) { // リード
	pipe_in_flag = true;
      }
      else {    // ライト
	pipe_out_flag = true;
      }
      return( ret );
    }
    if( _name.equals( "<sock>" )) { // ソケット
      socket_flag = true;
      return( ret );
    }
    // issue #219: /dev/ptmx (master) と /dev/pts/N (slave) を裏付ける pty fd。
    //   open_resolved (Syscall.open_resolved) が FileOpen 直後に set_pipe_pair()
    //   で双方向 pipe を結びつけるので、ここでは実 file を一切触らず opened=1 の
    //   まま成功するだけでよい (<procmaps>/<pipe> と同じ virtual fd 扱い)。
    //   ★Windows 固有 EPERM (openpty: Operation not permitted) の真因はここ:
    //   この特別扱いが無いと下の new RandomAccessFile("<pty-master>", "rw") が
    //   走る。`<` `>` は NTFS の予約文字なので Windows では FileNotFoundException
    //   → ret=false → FileOpen=-1 → open_resolved が -1(=-EPERM) を返し、glibc の
    //   openpty が posix_openpt(/dev/ptmx) 段で "Operation not permitted" で失敗
    //   する。Linux では `<` `>` が合法で junk file を作って偶然成功していた
    //   (= Linux のみ動作・Windows のみ失敗の切り分けと一致。junk file 漏れも解消)。
    if( _name.equals( "<pty-master>" ) || _name.equals( "<pty-slave>" )) {
      return( ret );
    }
    // それ以外のファイル
    // ファイルを削除する。
    if( mode.equals( "rw" )) { // 書き込みモードなら
      if( 0 != ( _mode_bit & Syscall.O_TRUNC )) {
	file = new File( _name );
	file.delete( );
      }
    }
    // ファイルをオープンする。
    try { f = new RandomAccessFile( _name, mode ); }
    catch ( IOException m ) {  ret = false; opened = 0; }
    // O_APPEND : 既存ファイルの末尾にシーク
    if( ret && f != null && 0 != ( _mode_bit & Syscall.O_APPEND ) ) {
      try { f.seek( f.length( ) ); }
      catch ( IOException m ) { /* fall through */ }
    }
    return( ret );
  }

  public boolean close( Sysinfo sysinfo ) {
    boolean ret = true;
    opened--;
    // issue #41 (sshd): subprocess.close() は subprocess の opened フラグを
    //   false にして read loop を止める。複数 fd (dup/dup2/fork) で同じ
    //   Fileinfo を共有しているうちに 1 個だけ close されたケースでは
    //   subprocess を止めるとまだ生きている fd の Read が EOF を返してしまう。
    //   opened < 1 になった last close でだけ subprocess を停止する。
    //   sshd の rexec 経路で dup2(newsock, 3) 後 close(newsock) すると、
    //   subprocess.opened=false になって fd 3 read が EOF になる回帰を修正。
    if( opened < 1 ) {
      if( subprocess != null ) {
        subprocess.close( ); // opened = false でループ終了を促す
        subprocess.interrupt( );
      }
      if( pipe_write_no >= 0 ) {
	// socketpair: read 用 pipe_no の i_connected と write 用 pipe_write_no
	//   の o_connected をそれぞれ落とす
	sysinfo.kernel.disconnect_pipe( pipe_no,       true  );
	sysinfo.kernel.disconnect_pipe( pipe_write_no, false );
      }
      else {
	if( is_pipe( true )) {
	  sysinfo.kernel.disconnect_pipe( pipe_no, true );
	}
	if( is_pipe( false )) {
	  sysinfo.kernel.disconnect_pipe( pipe_no, false );
	}
      }
      if( f != null ) {
	try { f.close( ); }
	catch ( IOException m ) {  ret = false; }
      }
      if( conn != null ) {
	try{ conn.close( ); }
	catch ( IOException m ) {  ret = false; }
      }
      // issue #9: AF_UNIX SocketChannel も close
      if( unixSocket != null ) {
	try{ unixSocket.close( ); }
	catch ( IOException m ) {  ret = false; }
      }
      // issue #43 Phase 4-4: AF_UNIX server side も close
      if( unixServer != null ) {
	try{ unixServer.close( ); }
	catch ( IOException m ) {  ret = false; }
      }
      if( sysinfo.verbose( )) {
	sysinfo.kernel.println( " Fileinfo.close( )   close done = " + ret);
      }
    }
    return( ret );
  }

  public String get_name( ) {
    String ret = null;
    if( isSOCKET( )) {
      ret = "<sock>";
      if(  conn != null ) { ret =  conn.toString( ); }
    }
    else {
      ret = name;
    }
    return( ret );
  }

  // パイプをセットする。
  public void set_pipe( int _pipe_no ) {
    pipe_no = _pipe_no;
  }

  // socketpair: 双方向 pipe の pair をセットする (read 用と write 用)。
  //   in/out 両方の direction フラグを立て、Read は pipe_no、Write は
  //   pipe_write_no 経由で行うようにする。
  public void set_pipe_pair( int read_pipe, int write_pipe ) {
    pipe_no       = read_pipe;
    pipe_write_no = write_pipe;
    pipe_in_flag  = true;
    pipe_out_flag = true;
  }
  
  // パイプ入出力かどうかを返す。
  public boolean isPIPE( ) {
    return( is_pipe( true ) || is_pipe( false ));
  }

  // ソケットかどうかを返す。
  public boolean isSOCKET( ) {
    return( socket_flag );
  }

  // ストリームソケットかどうかを返す
  public boolean isSTREAM( ) {
    return( socket_flag && ( dgram == null ));
  }

  // 入力または出力パイプか？
  public boolean is_pipe( boolean input_flag ) {
    if( input_flag ) {
      return( pipe_in_flag );
    }
    return( pipe_out_flag );
  }

  // パイプが接続されているか？
  public boolean is_pipe_connected( Sysinfo sysinfo, Process process ) {
    return( sysinfo.kernel.is_pipe_connected( pipe_no ));
  }

  // パイプを複製する。
  public void duplicate_pipe( Sysinfo sysinfo ) {
    if( pipe_write_no >= 0 ) {
      // socketpair: 両端の参照数をそれぞれ増やす
      sysinfo.kernel.duplicate_pipe( pipe_no,       true  );
      sysinfo.kernel.duplicate_pipe( pipe_write_no, false );
      return;
    }
    if( is_pipe( true )) {
      sysinfo.kernel.duplicate_pipe( pipe_no, true );
    }
    else {
      sysinfo.kernel.duplicate_pipe( pipe_no, false );
    }
  }

  // IPアドレスを返す
  public int get_ip_address( ) {
    int p = ip;
    if( conn != null ) {
      InetAddress addr = conn.getLocalAddress( );
      p = Util.swap32( Util.ip( addr.getHostAddress( )));
    }
    return( p );
  }

  // 接続先のIPアドレスを返す
  public int get_partner_ip_address( ) {
    InetAddress addr = conn.getInetAddress( );
    return( Util.swap32( Util.ip( addr.getHostAddress( ))));
  }

  // IPアドレスを設定する
  public void set_ip_address( int _ip ) {
    ip = _ip;
    ip_str = Util.ip_str( Util.swap32( _ip ));
  }

  // ポート番号を返す。
  // 注意: UDP socket の場合 `port` field は connect() 時の DEST port を
  //   保持していて、ここでは「相手向けに送る port」を返す方が呼び出し側に
  //   都合がよい (sendmsg/sendto の connected UDP path がこれを期待)。
  //   getsockname のように「自分の bound local port」が必要な場合は
  //   get_local_port() を呼ぶこと。
  public int get_port( ) {
    int p = port;
    if( conn  != null ) { p =  conn.getLocalPort( ); }
    if( sconn != null ) { p = sconn.getLocalPort( ); }
    return( p );
  }

  // issue #9: getsockname 用に「自分が bind されている local port」を返す。
  //   UDP は dgram.getLocalPort()、TCP listener は sconn.getLocalPort()、
  //   TCP connected は conn.getLocalPort()。どれも無ければ port field を返す。
  public int get_local_port( ) {
    if( conn  != null ) return  conn.getLocalPort( );
    if( sconn != null ) return sconn.getLocalPort( );
    if( dgram != null ) return dgram.getLocalPort( );
    return port;
  }

  // 接続先のポート番号を返す
  public int get_partner_port( ) {
    return( conn.getPort( ));
  }

  // ポート番号を設定する
  public void set_port( int _port ) {
    port = _port;
  }

  // データダイアグラムを送信する
  public boolean sendto( byte buf[] ) {
    boolean ret = true;
    DatagramPacket p;
    InetAddress iaddr;
    if( dgram == null ) return false;
    try{ iaddr = InetAddress.getByName( ip_str ); }
    catch( UnknownHostException m ) { return( false ); }
    p = new DatagramPacket( buf, buf.length, iaddr, port );
    try { dgram.send( p ); }
    catch( IOException m ) { ret = false; }
    if( System.getenv("EMULIN_TRACE_NET") != null )
      System.err.println("DGRAM-SENDTO: ip_str="+ip_str+" port="+port+" len="+buf.length+" ok="+ret);
    return( ret );
  }

  // issue #9: AF_INET6 UDP の sendto。byte[16] の v6 address + port を受け取り
  //   Inet6Address.getByAddress 経由で DatagramPacket を組み立てて送る。
  public boolean sendto_v6( byte buf[], byte addr16[], int port_v6 ) {
    if( dgram == null ) return false;
    if( addr16 == null || addr16.length != 16 ) return false;
    boolean ret = true;
    try {
      java.net.Inet6Address v6 = (java.net.Inet6Address)
          java.net.Inet6Address.getByAddress( null, addr16, 0 );
      DatagramPacket p = new DatagramPacket( buf, buf.length, v6, port_v6 );
      try { dgram.send( p ); }
      catch( IOException m ) { ret = false; }
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("DGRAM-SENDTO-V6: ip="+v6.getHostAddress()+" port="+port_v6+" len="+buf.length+" ok="+ret);
    } catch ( IOException m ) {
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("DGRAM-SENDTO-V6 FAILED: "+m);
      return false;
    }
    return ret;
  }

  // issue #9: AF_INET6 UDP の recvfrom。src を 16 byte で書き戻す。
  //   v4 source は v4-mapped (::ffff:a.b.c.d) として 16 byte 化する。
  //   outPort[0] に source port を、戻り値が受信長 (-1 で失敗)。
  public int recvfrom_v6( byte buf[], byte outAddr16[], int outPort[] ) {
    DatagramPacket p;
    if( dgram == null ) return -1;
    if( cachedDatagram != null ) {
      p = cachedDatagram;
      cachedDatagram = null;
    } else {
      p = new DatagramPacket( buf, buf.length );
      try { dgram.receive( p ); }
      catch( IOException m ) { return -1; }
    }
    byte recv_buf[] = p.getData();
    int n = Math.min( p.getLength(), buf.length );
    for( int i = 0; i < n; i++ ) buf[i] = recv_buf[i];
    InetAddress iaddr = p.getAddress();
    outPort[0] = p.getPort();
    byte raw[] = iaddr.getAddress();
    if( raw.length == 16 ) {
      for( int i = 0; i < 16; i++ ) outAddr16[i] = raw[i];
    } else {
      // v4 source → ::ffff:a.b.c.d
      for( int i = 0; i < 10; i++ ) outAddr16[i] = 0;
      outAddr16[10] = (byte)0xFF;
      outAddr16[11] = (byte)0xFF;
      outAddr16[12] = raw[0];
      outAddr16[13] = raw[1];
      outAddr16[14] = raw[2];
      outAddr16[15] = raw[3];
    }
    return n;
  }

  // データダイアグラムを受信する
  public int recvfrom( byte buf[], int addr_info[] ) {
    int ret = 0;
    int i;
    InetAddress iaddr;
    byte recv_buf[];
    DatagramPacket p;

    // poll で先読みしたパケットがあれば優先して消費
    if( cachedDatagram != null ) {
      p = cachedDatagram;
      cachedDatagram = null;
    } else {
      p = new DatagramPacket( buf, buf.length );
      try { dgram.receive( p ); }
      catch( IOException m ) { return( -1 ); }
    }

    // 戻り値の設定
    recv_buf = p.getData( );
    ret      = p.getLength( );
    int n = Math.min( ret, buf.length );  // user buf より大きい packet は切り詰め
    for( i = 0 ; i < n ; i++ ) {
      buf[i] = recv_buf[i];
    }
    ret = n;
    iaddr = p.getAddress( );
    addr_info[0] = Util.swap32( Util.ip( iaddr.getHostAddress( )));
    addr_info[1] = p.getPort( );

    //    System.out.println( " Fileinfo.recvfrom( )  iaddr.toString( ) = " + iaddr.getHostAddress( ));
    //    System.out.println( " Fileinfo.recvfrom( )  p.getPort( ) = " + p.getPort( ));

    return( ret );
  }

  // back_log数を設定する。
  public void set_back_log( int _back_log ) {
    back_log = _back_log;
  }
}

