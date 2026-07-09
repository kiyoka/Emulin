// ----------------------------------------
//  Emulin FileAccess
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import java.util.*;
import emulin.*;

public class FileAccess
{
  Process process;
  Sysinfo sysinfo;
  static int SEEK_SET = 0;
  static int SEEK_CUR = 1;
  static int SEEK_END = 2;
  static int SEEK_DATA = 3;   // issue #609
  static int SEEK_HOLE = 4;   // issue #609
  Vector flist;
  // ★ issue #221 step 3d-2c-42: fd table の構造変更 (空き fd の確保 + 配置 / close の slot クリア /
  //   dup の slot 確保 + cloexec/tty_alias ArrayList) を直列化する lock。Vector / ArrayList は個々の
  //   操作こそ atomic だが「search_empty_fd → addElement/setElementAt」の compound は非アトミックで、
  //   2 thread が同時に open すると同じスロットを掴む / addElement の index ずれで返した fd が別
  //   Fileinfo (または null) を指す → read が EBADF/誤内容 (go build の並列 goroutine が "bad file
  //   descriptor" で落ちる真因)。blocking I/O (finfo.open/finfo.close) は lock 外に置くこと。
  final Object fdLock = new Object();
  // Phase 27 step 39: FD_CLOEXEC は POSIX 上「fd table のフラグ」であって
  //   Fileinfo (= open file description) の属性ではない。Dup/dup2 で
  //   Fileinfo を共有しても cloexec は per-fd で別管理が必要なので
  //   flist と同じ index で boolean を持つ。fd が無いか CLOEXEC 立てて
  //   いない場合 false。
  java.util.ArrayList<Boolean> cloexec_fds = new java.util.ArrayList<>();

  // issue #580: clone(CLONE_FILES) で fd table を親と共有している子。true のとき、この子の
  //   exit で all_file_close しない (共有 fd を閉じると親の fd を壊す)。
  boolean sharesFdTable = false;

  FileAccess( ) {
    flist = new Vector( );
  }

  // issue #580: clone(CLONE_FILES) — fd table (open file description の表) を親と参照共有する。
  //   子の open/close/dup が親の fd table に反映される。flist / cloexec / tty_alias を同一
  //   インスタンスにし、以後この子は fork の pipe_connection (複製) を行わない。
  public void shareFdTableWith( FileAccess parent ) {
    this.flist         = parent.flist;
    this.cloexec_fds   = parent.cloexec_fds;
    this.tty_alias_fds = parent.tty_alias_fds;
    this.sharesFdTable = true;
  }

  // cloexec_fds の取得 / 設定 (fd は範囲外なら false)
  //   ★ ArrayList は thread-unsafe で並列 open の set 中 resize で corruption する → fdLock で直列化。
  public boolean is_cloexec( int fd ) {
    synchronized( fdLock ) {
      if( fd < 0 || fd >= cloexec_fds.size() ) return false;
      Boolean b = cloexec_fds.get( fd );
      return b != null && b;
    }
  }
  public void set_cloexec( int fd, boolean v ) {
    if( fd < 0 ) return;
    synchronized( fdLock ) {
      while( cloexec_fds.size() <= fd ) cloexec_fds.add( Boolean.FALSE );
      cloexec_fds.set( fd, v );
    }
  }

  // issue #219: /dev/tty (制御端末) は fd 0/1/2 の pty slave と同一 Fileinfo を
  //   共有する free-rider fd。入力 (pselect/read) を fd 0 と完全一致させるには
  //   オブジェクト共有が要る (独立 Fileinfo では入力が届かない) が、その close で
  //   finfo.close() を呼ぶと共有 pty pipe を disconnect して fd 0/1/2 が EOF を
  //   受け session が落ちる。よって alias fd の close は flist から外すだけの
  //   no-op にする。cloexec と同じく per-fd フラグで持つ。
  java.util.ArrayList<Boolean> tty_alias_fds = new java.util.ArrayList<>();
  public boolean is_tty_alias( int fd ) {
    synchronized( fdLock ) {
      if( fd < 0 || fd >= tty_alias_fds.size() ) return false;
      Boolean b = tty_alias_fds.get( fd );
      return b != null && b;
    }
  }
  public void set_tty_alias( int fd, boolean v ) {
    if( fd < 0 ) return;
    synchronized( fdLock ) {
      while( tty_alias_fds.size() <= fd ) tty_alias_fds.add( Boolean.FALSE );
      tty_alias_fds.set( fd, v );
    }
  }

  // 指定インスタンスの情報で自分をアップデートする。
  public void update_info( FileAccess _p ) {
    sysinfo = _p.sysinfo;
  }

  // パイプの接続処理
  public void pipe_connection( FileAccess _p ) {
    int i;
    int pipe_in_fd = -1;
    int pipe_out_fd = -1;
    Fileinfo finfo = null;
    // issue #349: 親で 1 つの pipe Fileinfo を複数 fd slot が共有 (dup/dup2 由来)
    //   していた場合、子でも同じ 1 つの複製を共有させるための親→子対応表。
    //   pipe Fileinfo は fork で process ごとに複製するが、共有 slot を slot ごとに
    //   別々へ複製すると、各複製が「親の opened (= 共有 slot 数) を丸ごと継いだまま
    //   1 slot しか指さない」状態になり、last close (opened<1) に到達せず
    //   disconnect 漏れ → o_connected 残留 → reader が EOF を受け取れず永久 block
    //   する (apt の dpkg --status-fd 系 status pipe hang と同型)。
    IdentityHashMap<Fileinfo,Fileinfo> pipeDup = new IdentityHashMap<Fileinfo,Fileinfo>( );
    if( PipeManager.TRACE_PIPE ) System.err.println( "[pipe] fork child_pid=" + process.pid
        + " name=" + process.name + " from parent_pid=" + _p.process.pid );
    // 1) 全てのファイルポインタをコピーする
    for( i = 0 ; i < _p.flist.size( ) ; i++ ) {
      finfo = (Fileinfo)_p.flist.elementAt( i );
      if( sysinfo.verbose( )) {
	if( finfo == null ) {
	  process.println( " FileAccess.pipe_connection : fd = " + i + " finfo = " + finfo );
	}
	else {
	  process.println( " FileAccess.pipe_connection : fd = " + i + " finfo = " + finfo + " in = " + finfo.is_pipe( true ) + " out = " + finfo.is_pipe( false ) );
	}
      }
      if( finfo != null ) {
	// パイプの場合は多重化する。
	if( finfo.isPIPE( )) {
	  Fileinfo child = pipeDup.get( finfo );
	  if( child == null ) {
	    // 初出: 1 holder 分だけ複製して pipe refcount (o/i_connected) を立てる。
	    //   親側の opened (共有 slot 数) は引き継がず、この子プロセス内で
	    //   この pipe を指す fd slot 数として 1 から数え直す。
	    child = finfo.duplicate( );
	    child.opened = 1;
	    child.duplicate_pipe( sysinfo );
	    pipeDup.put( finfo, child );
	  }
	  else {
	    // 親で同一 Fileinfo を共有していた sibling slot: 子でも同じ複製を
	    //   共有し、opened だけ増やす (pipe refcount は増やさない = 1 holder)。
	    child.duplicate_file( sysinfo );
	  }
	  finfo = child;
	}
	else {
	  // 通常ファイル/console: 親子で共有される finfo の opened をインクリメント。
	  // こうしないと子の all_file_close で親側の fd が誤って閉じられる。
	  finfo.duplicate_file( sysinfo );
	}
      }
      flist.addElement( (Object)finfo );
      // issue #76: fork 時に CLOEXEC フラグも子へコピーする。旧実装はコピー
      //   していなかったため、子の exec 時に close_cloexec_files が CLOEXEC fd
      //   を閉じられなかった。emacs の make-process は exec 完了検知用に
      //   pipe2(O_CLOEXEC) を張り、子が exec で write 端を閉じる → 親の
      //   read(status pipe) が EOF を受け取る、という同期をするが、CLOEXEC が
      //   伝播しないと write 端が exec 後も開いたままで親が永久 block し、
      //   M-x shell が hang していた。
      set_cloexec( i, _p.is_cloexec( i ) );
      set_tty_alias( i, _p.is_tty_alias( i ) );  // issue #219: alias フラグも継承
    }
  }

  // 全てのファイルをクローズする。
  public void all_file_close( ) {
    int i;
    // issue #580: clone(CLONE_FILES) で fd table を親と共有している子は、exit で fd を閉じない
    //   (共有テーブルの fd を閉じると親側の fd を壊す。fd table は親が所有し続ける)。
    if( sharesFdTable ) return;
    if( PipeManager.TRACE_PIPE ) System.err.println( "[pipe] all_file_close ENTER pid="
        + process.pid + " name=" + process.name + " " + fdtag( ));
    for( i = 0 ; i < flist.size( ) ; i++ ) {
      Fileinfo finfo = (Fileinfo)flist.elementAt( i );
      if( sysinfo.verbose( )) {
	process.println( " all_file_close: i = " + i + " finfo = " + finfo );
      }
      if( null != finfo ) {
	FileClose( i );
	if( sysinfo.verbose( )) {
	  process.println( " all_file_close: i = " + i + " closed " );
	}
      }
    }
  }

  /**
   * Phase 27 step 39: execve 時に FD_CLOEXEC が立った fd を全部閉じる。
   * git の child notify pipe (CLOEXEC) はこれが無いと exec 越しに残り、
   * 親の read(notify_pipe) が EOF を受け取れず deadlock する。
   * Kernel.exec から呼ぶ (新 Process を作る前、syscall を引き継ぐ前)。
   */
  public void close_cloexec_files( ) {
    for( int i = 0; i < flist.size( ); i++ ) {
      Fileinfo finfo = (Fileinfo)flist.elementAt( i );
      if( finfo != null && is_cloexec( i ) ) {
        FileClose( i );
      }
    }
  }

  // ファイルオープン時のモードビットを返す
  public int GetModeBit( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    return( finfo.get_mode_bit( ));
  }

  // ファイルをオープンする
  public int FileOpen( String vpath, String mode, int mode_bit ) {
    boolean open_flag = false;
    Fileinfo finfo = new Fileinfo( );
    String path = sysinfo.get_native_path( vpath );
    Inode inode = new Inode( vpath , sysinfo );
    // ディレクトリなら実際にオープンはせず fd を返す ( Linux アプリから見れば open 成功 )
    if( inode.isDirectory( )) {
      finfo.opendir( path );
      open_flag = true;
    }
    else {
	if( sysinfo.kernel.is_device( vpath )) {
	    if( null != sysinfo.kernel.is_exist_device( vpath )) {
		path = sysinfo.kernel.is_exist_device( vpath );
		/* デバイスをオープンする */
		if( finfo.open( path, mode, mode_bit )) { open_flag = true; }
	    }
	    // Phase 27 step 25: 旧実装は「is_device で /dev/* が match したが
	    //   is_exist_device が null (= /dev/null 以外) → -1」で諦めていた。
	    //   結果 sandbox/dev/urandom 等を user が用意しても open できず
	    //   git/openssl が "Operation not permitted" で fail。
	    //   未認識の /dev/* は regular file として開きを試みる方針に変更。
	    else {
		if( finfo.open( path, mode, mode_bit )) { open_flag = true; }
	    }
	}
	else {
	    // ファイルならjavaの機能を使ってファイルオープンする
	    // issue #349: O_CREAT で異 case の sibling と衝突する新規 file は別名へ encode (WinCaseMap)。
	    if( ( mode_bit & Syscall.O_CREAT ) != 0 ) path = WinCaseMap.resolveCreate( path );
	    if( finfo.open( path, mode, mode_bit )) { open_flag = true; }
	}
    }
    if( open_flag ) {
      int _fd = place_fd( finfo );   // ★ atomic 確保 (並列 open 競合回避、3d-2c-42)
      if( sysinfo.verbose( )) {
	int i;
	for( i = 0 ; i < flist.size( ) ; i++ ) {
	  if( i == _fd ) {
	    process.println( "  FileOpen : *fd = " + i + " flist = " + flist.elementAt( i ) );
	  }
	  else {
	    process.println( "  FileOpen :  fd = " + i + " flist = " + flist.elementAt( i ) );
	  }
	}
      }
      return( _fd );
    }
    return( -1 );
  }

  // issue #78: eventfd / timerfd / epoll 等の anonymous fd を fd table に登録。
  //   finfo は呼び出し側で flag を設定済みのものを渡す。
  public int alloc_anon_fd( Fileinfo finfo ) {
    finfo.opened = 1;
    return place_fd( finfo );
  }

  // ★ issue #221 step 3d-2c-42: 空き fd を探して finfo を配置する atomic 操作。fdLock 下で
  //   search_empty_fd と addElement/setElementAt を 1 critical section にまとめ、並列 open の
  //   compound 競合 (同スロット二重確保 / index ずれ) を防ぐ。blocking I/O は含まない。
  static final boolean TRACE_FD = System.getenv("EMULIN_TRACE_FD") != null;
  static String fdtag() { Thread t = Thread.currentThread();
    return (t instanceof GuestThread g) ? ("tid"+g.guestTid()) : t.getName(); }
  public int place_fd( Fileinfo finfo ) {
    synchronized( fdLock ) {
      int _fd = search_empty_fd( );
      if( _fd == flist.size( ) ) { flist.addElement( (Object)finfo );        }
      else                       { flist.setElementAt( (Object)finfo, _fd ); }
      if( TRACE_FD ) System.err.println("[fd] OPEN fd="+_fd+" "+fdtag()+" name="+(finfo!=null?finfo.get_name():"?"));
      return _fd;
    }
  }

  // 空の fd を返す ( 番号の若いほうから )。★ compound 確保では必ず fdLock 下で呼ぶこと。
  public int search_empty_fd( ) {
    int _fd = flist.size( );
    int i;
    // もし番号の若い番号が空いていればそれを使う
    for( i = 0 ; i < flist.size( ) ; i++ ) {
      Fileinfo _finfo = (Fileinfo)flist.elementAt( i );
      if( sysinfo.debug( )) { process.println( "  FileOpen : fd = " + i + " flist = " + _finfo ); }
      if( _finfo == null )  { _fd = i; break; } // 空いていた
    }
    return( _fd );
  }

  // ファイルをクローズする
  boolean FileClose( int fd ) {
    boolean ret = true;
    if( fd >= flist.size( )) { return( true ); } // オープンしていない fd 番号はつねにクローズ成功とする。
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) {
      return( true );
    }
    // issue #219: /dev/tty alias fd は制御端末 (fd 0/1/2) と Fileinfo を共有する
    //   free-rider。finfo.close() を呼ぶと共有 pty pipe を disconnect して
    //   fd 0/1/2 が EOF を受け session が落ちる ("Not a tty device" /
    //   "closed by remote host")。alias の close は flist から外すだけにする
    //   (pipe lifecycle は fd 0/1/2 が所有)。
    if( is_tty_alias( fd ) ) {
      synchronized( fdLock ) {   // ★ slot クリアを atomic に (place_fd の search と整合、3d-2c-42)
        flist.setElementAt( (Object)null, fd );
        set_cloexec( fd, false );
        set_tty_alias( fd, false );
      }
      return( true );
    }
    if( TRACE_FD ) System.err.println("[fd] CLOSE fd="+fd+" "+fdtag()+" name="+finfo.get_name());
    ret = finfo.close( sysinfo );   // ★ blocking I/O は lock 外
    if( sysinfo.verbose( )) {
      process.println( "  FileClose : fd = " + fd );
    }
    synchronized( fdLock ) {   // ★ slot クリア + cloexec クリアを atomic に
      flist.setElementAt( (Object)null, fd ); // メモリを開放する。かわりに null オブジェクトをぶら下げておく
      set_cloexec( fd, false ); // cloexec フラグもクリア
    }
    return( ret );
  }

  // ファイルのリードを行う
  // Phase 27 step 38: 旧実装は synchronized で全 thread を serialize していた。
  //   pthread 経由で main thread が socket Read で block している間、
  //   worker thread が別 fd を read しようとして monitor entry で deadlock。
  //   git fetch via git:// で main が server からの read 待ちの間、worker が
  //   pack を unpack できず固まる。fd 単位の独立性は Fileinfo / Pipeinfo 側で
  //   担保されているので、ここの method-level synchronized は外して安全。
  int FileRead( int fd, byte buf[] ) {
    int ret = 0;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( -1 );
    }
    if( sysinfo.verbose( )) {
      process.println( " FileAccess.FileRead( ) " );
    }
    if( finfo.is_pipe( true )) { // パイプ
      ret = sysinfo.kernel.pipe_read( finfo.pipe_no, buf, finfo.nonBlock );
      // issue #377: pty slave からの read で line discipline の入力 CR/NL 処理
      //   (ICRNL) を適用する。出力側 ONLCR (FileWrite, #229/#230) の対称形。
      //   SSH client は Enter を CR(0x0d) で送るが、apt 等の canonical-mode reader
      //   は libc の getline が NL(0x0a) でしか行終端しない。ICRNL で CR→NL 変換
      //   しないと "Y\r" を受けた apt の [Y/n] 確認が永久に行終端を待ってハングする。
      //   raw mode の bash/vim/emacs は tcsetattr で ICRNL を OFF にするので素通り。
      //   slave 自身の c_iflag を見るので termios は常に現在値。ICRNL(0x100) は
      //   CR→NL の 1:1 変換で byte 数不変 (ret 不変・EOF 誤検知なし)。INLCR/IGNCR
      //   は既定 OFF かつ byte 破棄で EOF エッジを生むため最小修正では見送り。
      if( finfo.pty_slave && ret > 0 && (finfo.c_iflag & 0x100) != 0 ) {
        for( int i = 0; i < ret; i++ ) {
          if( buf[i] == 0x0d ) buf[i] = 0x0a;   // CR → NL
        }
      }
      if( sysinfo.verbose( )) {
	process.println( " FileRead (pipe) : pipe_no = " + finfo.pipe_no + " ret = " + ret );
      }
    }
    else { // それ以外
      if( sysinfo.verbose( )) {
	process.println( "isOPEN( ) =   " + finfo.isOPEN( ));
	process.println( "Avaialbe( ) = " + finfo.Available( ));
      }

      if( sysinfo.verbose( )) {
	  process.println( " FileRead (file) : start" );
      }

      // issue #41 (sshd): STREAM (TCP) socket は subprocess の ring buffer
      //   経路 (read_byte_top) を使わず finfo.Read で socket から直接読む。
      //   理由: ring buffer は 1024 byte 固定で SSH packet (KEX_INIT+ECDH_INIT
      //   合計 2200+ byte) を 1 byte ずつ subprocess が詰めるため、user が
      //   chunked read すると packet 境界をまたいで「半分しか届かない」状態が
      //   生じ、sshd が "Bad packet length 0" で fatal する。socket 直 read
      //   なら OS の TCP buffer 全部を 1 回で読めて packet 境界も保たれる。
      //   DGRAM / pipe は ring buffer 経路を残す (recvfrom が読み出すため)。
      boolean stream_sock = finfo.isSOCKET() && finfo.isSTREAM();
      if( null != finfo.subprocess && !stream_sock ) {
	  if( sysinfo.verbose( )) {
	      process.println( " FileRead (file) : ( use subprocess )" );
	  }
	  ret = finfo.subprocess.read_byte_top( buf, false );
      }
      else {
	  if( sysinfo.verbose( )) {
	      process.println( " FileRead (file) : ( use finfo.Read( )  )" );
	  }
	  ret = finfo.Read( buf );
      }
      if( sysinfo.verbose( )) {
	  process.println( " FileRead (file) : end" );
      }
    }
    return( ret );
  }

  // ファイルの書き込みを行う
  // 後方互換: blocking write。書けたら true、finfo NULL / EPIPE で false。
  boolean FileWrite( int fd, byte buf[] ) {
    return( FileWriteNB( fd, buf, false ) >= 0 );
  }

  // issue #551: nonBlock 対応 write。返り: 書けたバイト数 (>=0)、finfo NULL / EPIPE は -1。
  //   nonBlock pipe が full なら 0 (caller が EAGAIN に変換)。blocking(nonBlock=false)
  //   は従来通り full で reader を待つ。
  int FileWriteNB( int fd, byte buf[], boolean nonBlock ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) {
      process.println( "FileWrite( )  finfo is NULL   fd = " + fd );
      return( -1 );
    }

    if( sysinfo.verbose( )) {
      process.println( " FileAccess.FileWrite( ) " );
    }
    if( finfo.is_pipe( false )) { // パイプ
      // socketpair の双方向: write 側は pipe_write_no を使う (>=0 のとき)。
      //   通常 pipe では pipe_write_no=-1 のままなので pipe_no で書く。
      int wpipe = (finfo.pipe_write_no >= 0) ? finfo.pipe_write_no : finfo.pipe_no;
      // issue #229: pty slave への write で line discipline の出力 post-processing
      //   (OPOST + ONLCR) を適用し \n を \r\n に展開する。bash/ls/cat 等は \n 1
      //   byte で行終端するため、未対応だと Tera Term 等の SSH client が CR 無し
      //   の LF を「下行同列」へ移動するだけと解釈して階段崩れ / 1 行詰まりに
      //   なる (例: "lsSumibi  ..." と連結)。raw mode (emacs/vim/less/tmux) は
      //   tcsetattr で c_oflag &= ~OPOST を立てるので素通り (= 影響なし)。
      byte[] out = buf;
      if( finfo.pty_slave && (finfo.c_oflag & 0x01) != 0 && (finfo.c_oflag & 0x04) != 0 ) {
        int extra = 0;
        for( int i = 0; i < buf.length; i++ ) if( buf[i] == 0x0a ) extra++;
        if( extra > 0 ) {
          byte[] nb = new byte[ buf.length + extra ];
          int j = 0;
          for( int i = 0; i < buf.length; i++ ) {
            if( buf[i] == 0x0a ) { nb[j++] = 0x0d; nb[j++] = 0x0a; }
            else                 { nb[j++] = buf[i]; }
          }
          out = nb;
        }
      }
      // issue #551: nonBlock pipe は full で partial/EAGAIN を返す。OPOST で長さが
      //   変わった場合 (out != buf) は partial の逆算が困難なので blocking で全書き
      //   (OPOST は raw mode で off なので nonBlock との併用は稀)。
      int wrote;
      if( out == buf ) {
        wrote = sysinfo.kernel.pipe_write_nb( wpipe, out, nonBlock );
      } else {
        wrote = sysinfo.kernel.pipe_write( wpipe, out ) ? buf.length : -1;
      }
      // issue #219: この pipe の read 端に O_ASYNC owner が登録されていれば、
      //   データ到着を SIGIO で通知する。emacs 等は端末入力を interrupt-driven
      //   (O_ASYNC+F_SETOWN) で受け、SIGIO ハンドラ内で read する。これが無いと
      //   入力が pipe に届いても emacs が永遠に読まず無反応になる (pty 越し emacs)。
      if( wrote > 0 ) {
        int owner = sysinfo.kernel.get_async_owner( wpipe );
        if( owner > 0 ) sysinfo.kernel.kill( owner, Signal.SIGIO );
      }
      if( sysinfo.verbose( )) {
	process.println( " FileWrite (pipe) : pipe_no = " + wpipe  + " wrote = " + wrote );
      }
      return( wrote );
    }
    else { // それ以外
      if( sysinfo.verbose( )) {
	process.println( " FileWrite (file or socket) : " );
      }
      // issue #443: O_APPEND は毎回書き込み前に末尾へシークする (POSIX の追記保証)。
      if( finfo.appendMode && finfo.f != null ) {
	try { finfo.f.seek( finfo.f.length( ) ); } catch( IOException ig ) {}
      }
      // issue #616: MAP_SHARED file mapping の coherence — real file への write(2) を、
      //   同一 file を map している MAP_SHARED 領域に反映する (write→map 方向)。
      //   backend (software=Memory / native=NativeMemoryBackend) は process.syscall.mem。
      //   gate (mayHaveSharedFileMaps) が false のときは offset 取得もせずノーコスト。
      MemoryBackend mb616 = ( process != null && process.syscall != null ) ? process.syscall.mem : null;
      boolean track616 = ( mb616 != null && mb616.mayHaveSharedFileMaps() && finfo.f != null );
      long wpos616 = track616 ? FileSeek( fd, 0, FileAccess.SEEK_CUR ) : -1L;
      boolean ok616 = finfo.Write( buf );
      if( ok616 && track616 && wpos616 >= 0 )
        mb616.propagateWriteToSharedMaps( finfo.get_name(), wpos616, buf, buf.length );
      return( ok616 ? buf.length : -1 );
    }
  }

  // ファイルシーク
  // issue #336: off_t は 64-bit。offset / 内部位置 / 戻り値を long で扱う
  //   (旧 int 実装は 2GB(2^31) 境界で seek 位置・size・返り値を切り詰めていた)。
  long FileSeek( int fd, long offset, int whence ) {
    boolean ret = true;
    long o = 0;
    long size = 0;
    long curptr = 0;
    // issue #334 (apt install 中に露呈): 無効 fd は EBADF(-9) を返す。旧実装は fd が
    //   範囲外なら elementAt で AIOOBE、finfo==null なら print 後に下の finfo.f 参照で
    //   NPE となり、apt の worker thread が落ちて親が wait4 で永久 block していた
    //   (#324 が read/write/dup に入れた EBADF guard の lseek 版)。amd64 の lseek(8) も
    //   sys_lseek 経由なので両 ABI に効く。
    if( fd < 0 || fd >= flist.size() ) {
      return( -9 );  // EBADF
    }
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {
      return( -9 );  // EBADF
    }
    // issue #191: pipe / socket は seek 不可なので ESPIPE(-29) を返す。これらは
    //   finfo.f == null のため従来は下の「directory 扱い」経路で偽の offset を
    //   返しており、dpkg が data.tar 展開後に pipe を lseek して末尾位置を確認
    //   する "zap trailing zeros" 処理を壊し、archive 処理が
    //   "cannot zap possible trailing zeros from dpkg-deb" で失敗していた。
    if( finfo.pipe_in_flag || finfo.pipe_out_flag || finfo.socket_flag ) {
      return( -29 );  // ESPIPE
    }
    // issue #411: 合成ファイル (memContent: /proc/meminfo /proc/<pid>/stat 等) の lseek。
    //   libproc2 は /proc/meminfo を open したまま query 毎に lseek(0)+re-read するため、
    //   memPos を whence に従って動かさないと 2 回目以降の read が EOF になり、MemTotal=0
    //   → ps aux が %MEM の分母 0 で DIV/0 する。
    if( finfo.memContent != null ) {
      long len = finfo.memContent.length;
      long np;
      if( whence == SEEK_SET )      np = offset;
      else if( whence == SEEK_CUR ) np = finfo.memPos + offset;
      else if( whence == SEEK_END ) np = len + offset;
      else return( -22 );  // EINVAL
      if( np < 0 ) return( -22 );
      if( np > len ) np = len;
      finfo.memPos = (int)np;
      return( np );
    }

    if( sysinfo.debug( )) {
      process.println( "  FileSeek : fd = " + fd + " offset = " + offset );
      int i;
      for( i = 0 ; i < flist.size( ) ; i++ ) {
	process.println( "  FileSeek : fd = " + i + " flist = " + flist.elementAt( i ) );
      }
    }
    if( null != finfo.f ) { // ディレクトリ以外なら実際のシークを実行する
      try { curptr = finfo.f.getFilePointer( ); }   // issue #336: long (旧 (int) 切り詰め)
      catch ( IOException m ) { ret = false; }
      try { size = finfo.f.length( ); }              // issue #336: long (旧 (int) 切り詰め)
      catch ( IOException m ) { ret = false; }
      if( ret ) {
	// issue #442: 不正な whence は EINVAL。
	if( whence == SEEK_SET )      o = offset;
	else if( whence == SEEK_CUR ) o = curptr + offset;
	else if( whence == SEEK_END ) o = size + offset;
	// issue #609: SEEK_DATA/SEEK_HOLE の基本実装 (完全割当ファイル前提)。Java FileChannel は
	//   穴検出を露出しないため内部の穴はスキップしない近似だが、EINVAL より実機に近く、
	//   SEEK_HOLE/DATA を probe するプログラムが正しく扱える。
	//   SEEK_DATA(off): off が data → off / EOF 以降は ENXIO。
	//   SEEK_HOLE(off): 唯一の穴 = EOF → size / EOF 以降は ENXIO。
	else if( whence == SEEK_DATA || whence == SEEK_HOLE ) {
	  if( offset < 0 )      return( -22 );  // EINVAL
	  if( offset >= size )  return( -6 );   // ENXIO: EOF 以降に data/hole 無し
	  o = ( whence == SEEK_HOLE ) ? size : offset;
	}
	else return( -22 );  // EINVAL
	// issue #439: 結果オフセットが負なら EINVAL (POSIX)。旧実装は負値を
	//   finfo.f.seek(o) に渡し、未捕捉の IllegalArgumentException で syscall
	//   thread が死んでいた (catch は IOException のみ)。
	if( o < 0 ) return( -22 );  // EINVAL
	try { finfo.f.seek( o ); }
	catch ( IOException m ) { ret = false; }
      }
    }
    else { // ディレクトリの場合はポインタのみ変更しておく
      curptr = get_ptr( fd );
      if( whence == SEEK_SET ) {
	o = offset;
      }
      if( whence == SEEK_CUR ) {
	o = curptr + offset;
      }
      if( whence == SEEK_END ) {
	o = size + offset;
      }
      set_ptr( fd, (int)o );   // issue #336: dir cookie は小さいので (int) で可
    }
    if( ret ) {
      return( o );
    }
    return( 0 );
  }

  // ファイル名を返す
  String get_name( int fd ) {
    String ret = null;
    Fileinfo finfo = get_finfo( fd );  // issue #10: 範囲外 fd 安全化
    if( finfo == null ) { return( "<noname>" ); }
    if( finfo.isSOCKET( )) {
      ret = finfo.get_name( );
    }
    // issue #589: procfs 合成 dir (/proc, /proc/<pid>, /proc/self/fd 等) の fd は native
    //   backing を持たないため、_openProcfs/open_resolved (SyscallAmd64) が Fileinfo.name に
    //   「仮想 guest path」をそのまま格納する (_getdents64_procfs の前提と同じ)。これを native
    //   path と誤認して get_virtual_path に渡すと、"/proc" はどの mount にもマッチせず
    //   Mount.get_virtual_path が System.exit(1) で emulin プロセス全体 (= guest 全体、対話中の
    //   bash も含む) を即死させる。resolve_at_path 等の dirfd 解決経由でここに来ると再現する
    //   (opendir("/proc/self/fd") を dirfd にした openat/readlinkat 等、apt-get 中の systemd
    //   postinst で発火)。proc 系 fd は仮想 path をそのまま返す。
    else if( finfo.proc_dir || finfo.proc_fd_dir ) {
      ret = finfo.get_name( );
    }
    else {
      ret = sysinfo.get_virtual_path( finfo.get_name( ));
    }
    return( ret );
  }

  // ファイルのカレント位置をセットする
  void set_ptr( int fd, int ptr ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    finfo.set_ptr( ptr );
  }
  // ファイルのカレント位置を読み出す
  int get_ptr( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    return( finfo.get_ptr( ));
  }

  // 指定ファイルの情報インスタンスを返す。
  // issue #10: fd が flist の範囲外 (= 未 open / 閉じ済) のとき
  //   flist.elementAt は ArrayIndexOutOfBoundsException を投げる。
  //   gpg-agent が未 open fd を fstat する等で実際に発生したので、
  //   範囲外 / 負値は null を返して caller が EBADF を返せるようにする。
  Fileinfo get_finfo( int fd ) {
    if( fd < 0 || fd >= flist.size( ) ) { return( null ); }
    return( (Fileinfo)flist.elementAt( fd ));
  }

  // 標準入出力かどうかを返す
  boolean isSTD( int fd ) {
    Fileinfo finfo = get_finfo( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.isSTD( ));
  }

  // エラー入出力かどうかを返す
  boolean isERR( int fd ) {
    Fileinfo finfo = get_finfo( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.isERR( ));
  }

  // パイプ入出力かどうかを返す。
  boolean isPIPE( int fd ) {
    Fileinfo finfo = get_finfo( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.is_pipe( true ) || finfo.is_pipe( false ));
  }

  // ソケットかどうかを返す。
  boolean isSOCKET( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.isSOCKET( ));
  }

  // パイプ入力または出力かどうかを返す。
  boolean is_pipe( int fd, boolean input_flag ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.is_pipe( input_flag ) );
  }

  // 指定ファイルを消去する。
  // issue #126: unlink 前の存在チェックは symlink を follow してはいけない。
  //   File.exists() / Inode.isExists() は target を解決するため、dangling
  //   symlink (emacs の lock file ".#<name>" -> "user@host.pid" 等) を「存在
  //   しない」と誤判定して ENOENT を返し、unlink を実行すらしなかった
  //   (ls/lstat は NOFOLLOW 対応済なので見えるのに rm で消せない非対称)。
  //   NOFOLLOW で symlink 自身 (Cygwin magic file 含む) の存在を見る。unlink()
  //   と同じ native path 解決を使い、チェックと削除の対象を一致させる。
  public boolean exists_nofollow( String vpath ) {
    // issue #322: 最終 component の symlink を追従しない。get_native_path は
    //   (Cygwin/Linux いずれも) 最終 symlink を follow するようになったので、
    //   存在判定の対象 (symlink 自身) を見るには nofollow を使う。
    String nat = sysinfo.get_native_path_nofollow( vpath );
    java.nio.file.Path p = java.nio.file.Paths.get( nat );
    return java.nio.file.Files.exists( p, java.nio.file.LinkOption.NOFOLLOW_LINKS )
        || java.nio.file.Files.isSymbolicLink( p );
  }

  public boolean unlink( String vpath ) {
    // Phase 33: Windows native FS で File.delete が偶発的に false を返す
    // (open handle や属性差異)。NIO Files.delete は失敗時に例外を投げて
    // くれるので、ENOENT / 他エラーの区別がつき信頼性が高い。
    // issue #68 / #322: unlink は symlink 自身を消す → 最終 component は追従しない。
    // get_native_path は (Cygwin/Linux いずれも) 最終 symlink を follow するように
    // なったので、symlink を消すつもりが target を消す事故を避けるため nofollow を使う。
    String nat = sysinfo.get_native_path_nofollow( vpath );
    // issue #495: dentry cache 無効化。「symlink → 消滅/通常 file 化」の stale を防ぐ
    //   (dpkg の file⇔symlink 置換等)。削除失敗でも invalidate は無害 (次回 miss するだけ)。
    if( CygSymlink.enabled() ) CygSymlink.dentryInvalidate( nat );
    java.nio.file.Path p = java.nio.file.Paths.get( nat );
    return unlink_with_retry( p, 0 );
  }
  // Phase 33-8: Windows JVM 既知挙動の緩和。emulator 内で読み書きした file
  // の Java handle (FileInputStream/RandomAccessFile/Files API) が GC で
  // 解放されるまで NTFS は「ファイル使用中」と判定し、AccessDenied を返す。
  // System.gc() + 短い sleep で handle を release させてから retry する。
  // git clone した repo の rm -rf で .git/ 配下の packed objects / index
  // が消せず Operation not permitted で失敗する現象の対策 (Phase 33-8)。
  private boolean unlink_with_retry( java.nio.file.Path p, int retry ) {
    boolean trace = System.getenv("EMULIN_TRACE_UNLINK") != null;
    // Phase 33-9f: Windows で Files.delete が broken symlink (target 存在
    // しない git 用 .git/<rand> -> testing) を silently 残す bug 回避。
    // NIO より先に legacy File.delete (Win32 DeleteFile 直接呼び) を試行
    // する。symlink について Win32 DeleteFile の方が確実に削除できる。
    java.io.File legacy = p.toFile();
    if( legacy.exists() || java.nio.file.Files.isSymbolicLink( p ) ) {
      try { legacy.setWritable( true, false ); } catch( Exception ignored ) {}
      if( legacy.delete() ) {
        if( trace ) System.err.println("DBG unlink OK (legacy): "+p);
        return true;
      }
    }
    try {
      java.nio.file.Files.delete( p );
      // Phase 33-9e: Files.delete が success を返しても残存する場合がある
      // ので NOFOLLOW で再 check し、残っていれば再度 legacy delete。
      if( java.nio.file.Files.exists( p, java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) {
        if( legacy.delete() ) {
          if( trace ) System.err.println("DBG unlink OK (zombie cleanup): "+p);
          return true;
        }
        return false;
      }
      if( trace ) System.err.println("DBG unlink OK: "+p);
      return true;
    } catch( java.nio.file.NoSuchFileException e ) {
      return false;
    } catch( java.nio.file.AccessDeniedException e ) {
      // issue #301: 別プロセス (典型: Ctrl-C で死んだ git index-pack 子) が leak
      //   した host handle、または read-only 属性 (git packed objects 0444) で
      //   Windows NTFS が delete を拒否するケース。leaked handle を強制 close +
      //   read-only 外し + GC 待ち retry で対処。
      //   ★sharing violation (open 中 file の FileSystemException) は別経路 (下の
      //   IOException 側で gentle に扱う)。apt が open したまま unlink する検証用
      //   temp (apt.data/apt.sig/apt.sqvout 等) の live handle をここで強制 close
      //   すると apt の sqv 署名検証を壊すため (issue #322 回帰の原因だった)。
      if( trace ) System.err.println("DBG unlink AccessDenied: "+p+" : "+e.getMessage());
      if( sysinfo != null && sysinfo.kernel != null ) {
        int forced = sysinfo.kernel.close_open_handles_for_path( p.toString( ) );
        if( forced > 0 ) {
          if( trace ) System.err.println("DBG unlink: force-closed "+forced+" leaked handle(s) for "+p);
          System.gc();
          try { Thread.sleep( 30L ); } catch( InterruptedException ie ) {}
          try { java.nio.file.Files.delete( p ); return true; }
          catch( Exception e2 ) { /* read-only / gc retry に fall through */ }
        }
      }
      java.io.File f = p.toFile();
      if( f.exists() ) {
        f.setWritable( true, false );  // read-only 属性外し (Phase 33-7)
        if( f.delete() ) return true;
        if( retry < 2 ) {              // handle release 待ち retry (Phase 33-8)
          System.gc();
          try { Thread.sleep( 50L ); } catch ( InterruptedException ie ) {}
          return unlink_with_retry( p, retry + 1 );
        }
      }
      return false;
    } catch( java.nio.file.DirectoryNotEmptyException e ) {
      // Phase 33-12: rmdir 失敗時に残存 children を eagerly cleanup する。
      // Windows で broken symlink (.git/<rand> -> testing) を rm が
      // 認識せず unlink を呼ばないケースで、symlink を我々側で強制削除。
      // 通常 rmdir は呼出側 (rm -rf) が children を先に消す前提だが、
      // 例外的に残った dangling symlink を sweep する。
      //
      // ★ issue #324: この sweep は **Windows (Cygwin マジック symlink) 専用** の対策。
      //   Linux では非空 directory の rmdir は ENOTEMPTY で失敗するのが正しく、children の
      //   symlink を勝手に消してはならない。実際 Debian base (usrmerge) bundle で apt が
      //   rootfs root に解決される path を rmdir した際、この sweep が root の正当な symlink
      //   (/bin /lib /lib64 /sbin → usr/*) を全削除して dynamic linker (/lib64/ld-linux) を
      //   壊し、以後全 binary が起動不能になっていた。よって sweep+retry は CygSymlink モード
      //   のときだけ行い、Linux では即 false (= ENOTEMPTY) を返す。
      if( !CygSymlink.enabled() ) return false;
      try {
        java.io.File dir = p.toFile();
        java.io.File[] kids = dir.listFiles();
        if( kids != null ) {
          for( java.io.File kid : kids ) {
            java.nio.file.Path kp = kid.toPath();
            // Windows symlink (broken でも) を強制削除
            if( java.nio.file.Files.isSymbolicLink( kp ) ) {
              kid.setWritable( true, false );
              boolean ok = kid.delete();
              if( !ok ) {
                try { java.nio.file.Files.delete( kp ); ok = true; }
                catch( Exception ignored ) {}
              }
              if( trace ) System.err.println("DBG sweep symlink "+(ok?"OK":"FAIL")+": "+kp);
            }
          }
        }
      } catch( Exception ignored ) {}
      if( retry < 3 ) {
        System.gc();
        try { Thread.sleep( 50L ); } catch ( InterruptedException ie ) {}
        return unlink_with_retry( p, retry + 1 );
      }
      return false;
    } catch( java.io.IOException e ) {
      // FileSystemException (ERROR_SHARING_VIOLATION = その file を今 open 中) は
      //   ここに来る。★force-close しない (issue #322):
      //   apt は署名検証で temp (apt.data / apt.sig / apt.sqvout / apt.sqverr /
      //   clearsigned.message / .apt-acquire-privs-test) を open したまま unlink
      //   する POSIX idiom を使う。その live handle を close_open_handles_for_path で
      //   強制 close すると apt 自身が握る fd を奪い、sqv 署名検証が "Broken pipe" /
      //   "weak security" で壊れて apt-get update が失敗する (27f2705 の回帰)。
      //   Linux 同様 unlink は失敗のままにする (file は close 時に host 側で自動
      //   cleanup される)。leaked-handle / read-only ケースは上の AccessDeniedException
      //   経路が扱う。最後に legacy File.delete だけ試す (NIO 拒否でも通る場合あり)。
      if( trace ) System.err.println("DBG unlink IOException (gentle, no force-close): "+p+" : "+e);
      return new File( p.toString() ).delete();
    }
  }

  // 指定ファイルの名前を変更する
  public boolean rename( String vpath_from, String vpath_to ) {
    // Phase 33: File.renameTo は Windows native NTFS で偶発的に false を
    // 返す (target 既存、case 違い、内部 handle 等)。NIO Files.move +
    // REPLACE_EXISTING は cross-platform で確実に動く。git config 書き込み
    // (lock_file → rename) が Windows で EPERM になる現象の根本対策。
    // issue #322: rename(2) は最終 component の symlink を追従しない (symlink 自身を
    //   rename する)。get_native_path が最終 symlink を follow するようになったので
    //   src/dst とも nofollow で解決する (中間 component の symlink は追従する)。
    java.nio.file.Path src = java.nio.file.Paths.get( sysinfo.get_native_path_nofollow( vpath_from ) );
    // issue #349: rename 先が異 case の sibling と衝突するなら dst を別名へ encode (WinCaseMap)。
    //   src は native_path 解決時に登録済 encode 名へ map 置換される (mapPath)。
    java.nio.file.Path dst = java.nio.file.Paths.get(
        WinCaseMap.resolveCreate( sysinfo.get_native_path_nofollow( vpath_to ) ) );
    // issue #495: dentry cache 無効化。src/dst 自身 + (directory rename なら) 配下 prefix。
    //   directory rename で旧 path 配下の symlink キャッシュが phantom 追従するのを防ぐ。
    if( CygSymlink.enabled() ) {
      CygSymlink.dentryInvalidate( src.toString() );
      CygSymlink.dentryInvalidate( dst.toString() );
      try {
        if( java.nio.file.Files.isDirectory( src, java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) {
          String sep = java.io.File.separator;
          CygSymlink.dentryInvalidatePrefix( src.toString() + sep );
          CygSymlink.dentryInvalidatePrefix( dst.toString() + sep );
        }
      } catch( Throwable ignore ) {}
    }
    return rename_with_retry( src, dst, 0 );
  }

  // issue #322: Windows NTFS での rename 堅牢化。MoveFileEx(REPLACE_EXISTING) は
  //   src / dst のいずれかが open 中 (FILE_SHARE_DELETE 無し) だと失敗する。dpkg の
  //   status-new → status / apt の extended_states.XXXXXX → extended_states の
  //   rename がこれで EPERM になり `apt-get install` が
  //   "error installing new file '/var/lib/dpkg/status'" で失敗していた。
  //   ★実機 trace で確定: 失敗は AccessDeniedException ではなく generic
  //   FileSystemException (ERROR_SHARING_VIOLATION = open 中 file)。Java は
  //   SHARING_VIOLATION を AccessDenied に map しないので、AccessDenied だけ
  //   防御していた旧実装では素通りしていた。よって catch を IOException に広げ、
  //   unlink_with_retry (Phase 33-7/8, issue #301) と同じ防御を回す:
  //   (1) src/dst を握る host handle を強制 close、(2) dst が read-only なら属性を
  //   外す、(3) System.gc() + sleep で GC 待ちの host handle を解放させて retry。
  //   Linux では 1 発目の Files.move で成功するので追加コストなし (catch 不通)。
  private boolean rename_with_retry( java.nio.file.Path src, java.nio.file.Path dst, int retry ) {
    boolean trace = System.getenv("EMULIN_TRACE_RENAME") != null;
    try {
      java.nio.file.Files.move( src, dst,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      if( trace ) System.err.println("DBG rename OK: "+src+" -> "+dst);
      return true;
    } catch( java.nio.file.NoSuchFileException e ) {
      // src が消えている → 失敗 (ENOENT 相当)。retry しても無駄。
      if( trace ) System.err.println("DBG rename NoSuchFile: "+src+" -> "+dst);
      return false;
    } catch( java.io.IOException e ) {
      // AccessDeniedException (read-only / ACL / 別プロセスの leaked handle) と
      // FileSystemException (ERROR_SHARING_VIOLATION = src/dst が現在 open 中) の
      // 両方をここで拾う。Windows は open file の rename を SHARING_VIOLATION で
      // 弾くが Java はこれを AccessDenied でなく generic FileSystemException に
      // map するため、旧実装 (AccessDenied だけ防御) は素通りで EPERM を返し、
      // dpkg の status / apt の extended_states の rename が失敗していた (#322)。
      if( trace ) System.err.println("DBG rename IOException (recover): "+src+" -> "+dst+" : "+e);
      // (1) src/dst を open している host handle を強制 close。dst 側が
      //     replace を弾く主因なので dst を先に。
      if( sysinfo != null && sysinfo.kernel != null ) {
        int forced = sysinfo.kernel.close_open_handles_for_path( dst.toString() )
                   + sysinfo.kernel.close_open_handles_for_path( src.toString() );
        if( forced > 0 && trace )
          System.err.println("DBG rename: force-closed "+forced+" leaked handle(s)");
      }
      // (2) dst が read-only だと Windows は replace を拒否する → 属性を外す。
      java.io.File dstFile = dst.toFile();
      if( dstFile.exists() ) {
        try { dstFile.setWritable( true, false ); } catch( Exception ignored ) {}
      }
      // (3) handle release を待って retry (最大 3 回)。
      if( retry < 3 ) {
        System.gc();
        try { Thread.sleep( 50L ); } catch( InterruptedException ie ) {}
        return rename_with_retry( src, dst, retry + 1 );
      }
      // 最後の手段 (data-loss しない): dst を一旦退避 → src を dst へ → 退避を
      //   削除。MoveFileEx(REPLACE) が dst の open handle で弾かれても、dst を
      //   別名へ退避できれば src を素の move で置ける。退避 rename 自体が失敗
      //   したら何も触らず false (dst は無傷)。退避後に src move が失敗したら
      //   dst を復元する。
      if( dstFile.exists() ) {
        java.nio.file.Path bak = java.nio.file.Paths.get( dst.toString() + ".emulin-rnbak" );
        try {
          java.nio.file.Files.move( dst, bak,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING );
          try {
            java.nio.file.Files.move( src, dst );
            unlink_with_retry( bak, 0 );   // 退避を片付け (best-effort)
            if( trace ) System.err.println("DBG rename OK (aside-move): "+src+" -> "+dst);
            return true;
          } catch( Exception e2 ) {
            // src move 失敗 → dst を復元
            try { java.nio.file.Files.move( bak, dst,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING ); }
            catch( Exception e3 ) {}
            if( trace ) System.err.println("DBG rename aside-move failed (dst restored): "+e2);
          }
        } catch( Exception eBak ) {
          if( trace ) System.err.println("DBG rename: dst aside-move rejected: "+eBak);
        }
      }
      // legacy renameTo fallback (dst が存在しない場合等)
      return new File( src.toString() ).renameTo( new File( dst.toString() ) );
    }
  }

  // 指定ディレクトリの作成を行う
  public boolean mkdir( String vpath ) {
    String path = sysinfo.get_native_path( vpath );
    // issue #349: 異 case の sibling と衝突する dir 名は別名へ encode して作成 (WinCaseMap)。
    //   非 Windows / 衝突無しは no-op (素の path を返す)。
    path = WinCaseMap.resolveCreate( path );
    File file;
    file = new File( path );
    return( file.mkdir( ));
  }

  // mkdir の失敗理由を errno (負値、0=成功) で返す。File.mkdir() は boolean しか返さず
  //   「親 dir 不在 (ENOENT)」と「権限 (EACCES)」を区別できないため、node 等の再帰 mkdir
  //   (mkdirp) が ENOENT を見て「親を作ればよい」と判断できず npm cache 作成等が失敗していた。
  //   Files.createDirectory の例外で errno を判定する。
  public int mkdirErrno( String vpath ) {
    String path = sysinfo.get_native_path( vpath );
    path = WinCaseMap.resolveCreate( path );
    try {
      java.nio.file.Files.createDirectory( java.nio.file.Paths.get( path ) );
      return 0;
    } catch( java.nio.file.FileAlreadyExistsException e ) {
      return -17;  // EEXIST
    } catch( java.nio.file.NoSuchFileException e ) {
      return -2;   // ENOENT (親 dir 不在 — node の mkdirp が親作成に必要)
    } catch( java.nio.file.AccessDeniedException e ) {
      return -13;  // EACCES
    } catch( java.io.IOException e ) {
      // issue #442: 親 component が通常ファイル等の非ディレクトリなら ENOTDIR。
      try {
        java.nio.file.Path parent = java.nio.file.Paths.get( path ).getParent();
        if( parent != null && java.nio.file.Files.exists( parent )
            && !java.nio.file.Files.isDirectory( parent ) ) return -20;  // ENOTDIR
      } catch( Exception ig ) {}
      return -1;   // EPERM (その他)
    }
  }

  // fd番号が from 番のファイルを to 番に複製する。
  //   ★ issue #221 step 3d-2c-42: slot 確保 + 配置 + refcount bump を fdLock で atomic に
  //   (並列 dup / dup と open の競合回避)。duplicate_* は opened++ / pipe refcount のみで非 blocking。
  public void Dup( int from, int to ) {
    synchronized( fdLock ) {
      Fileinfo finfo = (Fileinfo)flist.elementAt( from );
      if( sysinfo.verbose( )) {
        process.println( " Dup   finfo = " + finfo );
      }
      while( to >= flist.size( )) {
        flist.addElement( (Object)null );
      }
      flist.setElementAt( (Object)finfo, to );
      if( sysinfo.verbose( )) {
        process.println( " Dup ( " + from + "," + to + " );  isSTD( to ) = " + finfo.isSTD( ));
      }
      // issue #349: dup/dup2 は同一 Fileinfo を複数 fd slot で共有する操作
      //   (新たな open file description = 新 holder ではない)。よって pipe でも
      //   file と同様に opened (= この Fileinfo を指す fd 数) を増やす。旧実装は
      //   pipe のとき duplicate_pipe で o/i_connected (pipe holder 数) だけ増やし
      //   opened を据え置いたため、dup2 した 2 fd が opened=1 を共有 → 1 つ目の
      //   close で opened=0+disconnect、2 つ目の close で参照カウントが破綻
      //   (opened<1 のまま二重 disconnect で負 / または冪等 close で skip され
      //   o_connected が残留 → pipe reader が EOF を受け取れず永久 block。
      //   apt install systemd の dpkg --status-fd write 端で再現し apt が status
      //   pipe の read でハング)。fork は process ごとに別 Fileinfo (= 別 holder)
      //   を作るので duplicate_pipe で o/i_connected を増やすが、その際この
      //   dup 共有 (opened>1) を子側でも 1 holder にまとめる必要がある
      //   (pipe_connection の pipeDup 参照)。
      //
      //   issue #370: ただし pty (双方向 pipe pair、pty_master/pty_slave) は
      //   例外で pre-#351 の duplicate_pipe (per-fd holder bump) に戻す。pty の
      //   slave は /dev/pts/N を set_pipe_pair で「既存 pipe を参照」する特殊経路で
      //   開かれ、open 時に holder を増やさず connect_pipe の初期 1 に依存するため、
      //   #351 の opened のみ方式だと sshd の対話 session (dup2(slave,0/1/2) +
      //   fork) で holder が不足し、login shell (-bash) の pty slave read が起動
      //   直後に EOF → publickey 認証成功後に即切断していた (command mode は
      //   stdin を読まないので無事)。regular pipe (apt の dpkg status pipe) は
      //   #351 のまま (duplicate_file) で apt の hang 修正を保つ。pty が OLD 挙動で
      //   対話 login が動くことは v0.6.0 (= #351 前) で実証済み。
      if( finfo.pty_master || finfo.pty_slave ) {
        finfo.duplicate_pipe( sysinfo );
      } else {
        finfo.duplicate_file( sysinfo );
      }
    }
  }

  // 指定ディレクトリ内のエントリリストを返す
  // ( Java API で得たリストに . .. を追加する。 )
  public String [] file_list( String vpath ) {
    int i;
    String _list[];
    String list[];
    int slide;
    File file = new File( sysinfo.get_native_path( vpath ));

    _list = file.list( );

    if( sysinfo.verbose( )) {
      process.println( "FileAccess.file_list( )  file = " + file + " _list.length = " + _list.length );
    }

    if( 0 == _list.length ) {  // リストが空なら,配列要素を補う
      _list = new String[ 1 ];
      _list[0] = ".";
    }
    list = _list;

    // . と .. を先頭に追加する (Java の File.list() は . / .. を含まない)
    slide = 2;
    if( _list.length >= 1 && _list[0].equals( "." ) ) {
      // 既に "." を含んでいる稀なケース → ".." のみ追加
      slide = 1;
    }
    list = new String[ _list.length + slide ];
    if( slide == 2 ) {
      list[0] = ".";
      list[1] = "..";
    } else {
      list[0] = "..";
    }
    for( i = 0 ; i < _list.length ; i++ ) {
      list[i + slide] = _list[i];
    }
    return( list );
  }

  // パイプがあるかどうかを返す。
  public int search_pipe( boolean input_flag ) {
    int i;
    Fileinfo finfo;
    // 出力パイプを探す
    for( i = 0 ; i < flist.size( ) ; i++ ) {
      finfo = (Fileinfo)flist.elementAt( i );
      if( null != finfo ) {
	if( finfo.is_pipe( input_flag ))  { return( i ); }
      }
    }
    return( -1 ); // 見付からなかった。
  }

  // 出力パイプが接続されているかを返す。
  public boolean is_pipe_connected( int fd ) {
    int i;
    Fileinfo finfo;
    // 出力パイプを探す
    finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) {
      return( false );
    }
    return( finfo.is_pipe_connected( sysinfo, process ));
  }

  // 出力パイプに入力を接続する。
  public boolean set_pipe( int pipe_no, int fd ) {
    Fileinfo finfo;
    finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) { return( false ); }
    finfo.set_pipe( pipe_no );
    if( sysinfo.verbose( )) {
      process.println( "  set_pipe( ) : pipe_no = " + pipe_no + " fd = " + fd );
    }
    return( true );
  }

  // なんらかのイベントがあったか？
  public boolean isEvent( int fd ) {
    boolean ret = false;
    Fileinfo finfo;
    finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) { return( false ); }
    return( finfo.isEvent( ));
  }

  // だれかの手によって既にクローズされたか？
  public boolean isClosed( int fd ) {
    boolean ret = false;
    Fileinfo finfo;
    finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) { return( false ); }
    if( finfo.isPIPE( )) {
      if( !finfo.is_pipe_connected( sysinfo, process )) {
	ret = true;
      }
    }
    if( finfo.isSOCKET( )) {
      if( !finfo.is_connected( )) {
	ret = true;
      }
    }
    if( sysinfo.verbose( )) {
      process.println( " isClosed( " + fd + " ) = " + ret );
    }
    return( ret );
  }
}
