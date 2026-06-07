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
  Vector flist;
  // Phase 27 step 39: FD_CLOEXEC は POSIX 上「fd table のフラグ」であって
  //   Fileinfo (= open file description) の属性ではない。Dup/dup2 で
  //   Fileinfo を共有しても cloexec は per-fd で別管理が必要なので
  //   flist と同じ index で boolean を持つ。fd が無いか CLOEXEC 立てて
  //   いない場合 false。
  java.util.ArrayList<Boolean> cloexec_fds = new java.util.ArrayList<>();

  FileAccess( ) {
    flist = new Vector( );
  }

  // cloexec_fds の取得 / 設定 (fd は範囲外なら false)
  public boolean is_cloexec( int fd ) {
    if( fd < 0 || fd >= cloexec_fds.size() ) return false;
    Boolean b = cloexec_fds.get( fd );
    return b != null && b;
  }
  public void set_cloexec( int fd, boolean v ) {
    if( fd < 0 ) return;
    while( cloexec_fds.size() <= fd ) cloexec_fds.add( Boolean.FALSE );
    cloexec_fds.set( fd, v );
  }

  // issue #219: /dev/tty (制御端末) は fd 0/1/2 の pty slave と同一 Fileinfo を
  //   共有する free-rider fd。入力 (pselect/read) を fd 0 と完全一致させるには
  //   オブジェクト共有が要る (独立 Fileinfo では入力が届かない) が、その close で
  //   finfo.close() を呼ぶと共有 pty pipe を disconnect して fd 0/1/2 が EOF を
  //   受け session が落ちる。よって alias fd の close は flist から外すだけの
  //   no-op にする。cloexec と同じく per-fd フラグで持つ。
  java.util.ArrayList<Boolean> tty_alias_fds = new java.util.ArrayList<>();
  public boolean is_tty_alias( int fd ) {
    if( fd < 0 || fd >= tty_alias_fds.size() ) return false;
    Boolean b = tty_alias_fds.get( fd );
    return b != null && b;
  }
  public void set_tty_alias( int fd, boolean v ) {
    if( fd < 0 ) return;
    while( tty_alias_fds.size() <= fd ) tty_alias_fds.add( Boolean.FALSE );
    tty_alias_fds.set( fd, v );
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
	  finfo = finfo.duplicate( );
	  finfo.duplicate_pipe( sysinfo );
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
	    if( finfo.open( path, mode, mode_bit )) { open_flag = true; }
	}
    }
    if( open_flag ) {
      int _fd = search_empty_fd( );
      if( _fd == flist.size( )) { flist.addElement( (Object)finfo );        }
      else {                      flist.setElementAt( (Object)finfo, _fd ); }
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
    int _fd = search_empty_fd( );
    if( _fd == flist.size( ) ) { flist.addElement( (Object)finfo );        }
    else                       { flist.setElementAt( (Object)finfo, _fd ); }
    return _fd;
  }

  // 空の fd を返す ( 番号の若いほうから )
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
      flist.setElementAt( (Object)null, fd );
      set_cloexec( fd, false );
      set_tty_alias( fd, false );
      return( true );
    }
    ret = finfo.close( sysinfo );
    if( sysinfo.verbose( )) {
      process.println( "  FileClose : fd = " + fd );
    }
    flist.setElementAt( (Object)null, fd ); // メモリを開放する。かわりに null オブジェクトをぶら下げておく
    set_cloexec( fd, false ); // cloexec フラグもクリア
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
  boolean FileWrite( int fd, byte buf[] ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( null == finfo ) {
      process.println( "FileWrite( )  finfo is NULL   fd = " + fd );
      return( false );
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
      ret = sysinfo.kernel.pipe_write( wpipe, out );
      // issue #219: この pipe の read 端に O_ASYNC owner が登録されていれば、
      //   データ到着を SIGIO で通知する。emacs 等は端末入力を interrupt-driven
      //   (O_ASYNC+F_SETOWN) で受け、SIGIO ハンドラ内で read する。これが無いと
      //   入力が pipe に届いても emacs が永遠に読まず無反応になる (pty 越し emacs)。
      if( ret && buf.length > 0 ) {
        int owner = sysinfo.kernel.get_async_owner( wpipe );
        if( owner > 0 ) sysinfo.kernel.kill( owner, Signal.SIGIO );
      }
      if( sysinfo.verbose( )) {
	process.println( " FileWrite (pipe) : pipe_no = " + wpipe  + " ret = " + ret );
      }
    }
    else { // それ以外
      if( sysinfo.verbose( )) {
	process.println( " FileWrite (file or socket) : " );
      }
      ret =  finfo.Write( buf );
    }
    return( ret );
  }

  // ファイルシーク
  int FileSeek( int fd, int offset, int whence ) {
    boolean ret = true;
    int o = 0;
    int size = 0;
    int curptr = 0;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {
      process.println( "FileSeek( ) : finfo is NULL   fd = " + fd );
    }
    // issue #191: pipe / socket は seek 不可なので ESPIPE(-29) を返す。これらは
    //   finfo.f == null のため従来は下の「directory 扱い」経路で偽の offset を
    //   返しており、dpkg が data.tar 展開後に pipe を lseek して末尾位置を確認
    //   する "zap trailing zeros" 処理を壊し、archive 処理が
    //   "cannot zap possible trailing zeros from dpkg-deb" で失敗していた。
    if( finfo != null && (finfo.pipe_in_flag || finfo.pipe_out_flag || finfo.socket_flag) ) {
      return( -29 );  // ESPIPE
    }

    if( sysinfo.debug( )) {
      process.println( "  FileSeek : fd = " + fd + " offset = " + offset );
      int i;
      for( i = 0 ; i < flist.size( ) ; i++ ) {
	process.println( "  FileSeek : fd = " + i + " flist = " + flist.elementAt( i ) );
      }
    }
    if( null != finfo.f ) { // ディレクトリ以外なら実際のシークを実行する
      try { curptr = (int)finfo.f.getFilePointer( ); }
      catch ( IOException m ) { ret = false; }
      try { size = (int)finfo.f.length( ); }
      catch ( IOException m ) { ret = false; }
      if( ret ) {
	if( whence == SEEK_SET ) {
	  o = offset;
	}
	if( whence == SEEK_CUR ) {
	  o = curptr + offset;
	}
	if( whence == SEEK_END ) {
	  o = size + offset;
	}
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
      set_ptr( fd, o );
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
    String nat = CygSymlink.enabled()
        ? sysinfo.get_native_path_nofollow( vpath )
        : sysinfo.get_native_path( vpath );
    java.nio.file.Path p = java.nio.file.Paths.get( nat );
    return java.nio.file.Files.exists( p, java.nio.file.LinkOption.NOFOLLOW_LINKS )
        || java.nio.file.Files.isSymbolicLink( p );
  }

  public boolean unlink( String vpath ) {
    // Phase 33: Windows native FS で File.delete が偶発的に false を返す
    // (open handle や属性差異)。NIO Files.delete は失敗時に例外を投げて
    // くれるので、ENOENT / 他エラーの区別がつき信頼性が高い。
    // issue #68: unlink は symlink 自身を消す → 最終 component は追従しない
    // (Cygwin mode で get_native_path が target を follow すると、symlink を
    // 消すつもりが target を消してしまうため nofollow を使う)。
    String nat = CygSymlink.enabled()
        ? sysinfo.get_native_path_nofollow( vpath )
        : sysinfo.get_native_path( vpath );
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
      if( trace ) System.err.println("DBG unlink AccessDenied: "+p+" : "+e.getMessage());
      // Phase 33-7: git の packed objects は mode 0444 (read-only) で
      // 作られる。Windows NTFS だと「read-only 属性 = delete 不可」扱い
      // で Files.delete が AccessDeniedException を投げ、rm -rf sekka/
      // が「Operation not permitted」で失敗する。read-only 属性を外して
      // から再 try (Java side で setWritable した上で File.delete)。
      java.io.File f = p.toFile();
      if( f.exists() ) {
        f.setWritable( true, false );  // 全 user write OK
        if( f.delete() ) return true;
        // Phase 33-8: handle release を待って retry (最大 2 回)
        if( retry < 2 ) {
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
      if( trace ) System.err.println("DBG unlink IOException: "+p+" : "+e);
      // 旧 File.delete fallback (NIO が拒否しても古い API なら通る場合あり)
      return new File( p.toString() ).delete();
    }
  }

  // 指定ファイルの名前を変更する
  public boolean rename( String vpath_from, String vpath_to ) {
    // Phase 33: File.renameTo は Windows native NTFS で偶発的に false を
    // 返す (target 既存、case 違い、内部 handle 等)。NIO Files.move +
    // REPLACE_EXISTING は cross-platform で確実に動く。git config 書き込み
    // (lock_file → rename) が Windows で EPERM になる現象の根本対策。
    java.nio.file.Path src = java.nio.file.Paths.get( sysinfo.get_native_path( vpath_from ) );
    java.nio.file.Path dst = java.nio.file.Paths.get( sysinfo.get_native_path( vpath_to   ) );
    try {
      java.nio.file.Files.move( src, dst,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      return true;
    } catch( java.io.IOException e ) {
      // Files.move 失敗時は legacy renameTo に fallback
      return new File( src.toString() ).renameTo( new File( dst.toString() ) );
    }
  }

  // 指定ディレクトリの作成を行う
  public boolean mkdir( String vpath ) {
    String path = sysinfo.get_native_path( vpath );
    File file;
    file = new File( path );
    return( file.mkdir( ));
  }

  // fd番号が from 番のファイルを to 番に複製する。
  public void Dup( int from, int to ) {
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
    if( finfo.isPIPE( )) { finfo.duplicate_pipe( sysinfo ); }
    else {                 finfo.duplicate_file( sysinfo ); }
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
