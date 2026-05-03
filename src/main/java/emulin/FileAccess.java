// ----------------------------------------
//  Emulin FileAccess
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/10 18:59:40 $ 
//  $Id: FileAccess.java,v 1.48 2000/02/10 18:59:40 kiyoka Exp $
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

  FileAccess( ) {
    flist = new Vector( );
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
	    else {
		return( -1 ); /* オープン失敗 */
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
    ret = finfo.close( sysinfo );
    if( sysinfo.verbose( )) {
      process.println( "  FileClose : fd = " + fd );
    }
    flist.setElementAt( (Object)null, fd ); // メモリを開放する。かわりに null オブジェクトをぶら下げておく
    return( ret );
  }

  // ファイルのリードを行う
  synchronized int FileRead( int fd, byte buf[] ) {
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

      if( null != finfo.subprocess ) {
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
      ret = sysinfo.kernel.pipe_write( finfo.pipe_no, buf );
      if( sysinfo.verbose( )) {
	process.println( " FileWrite (pipe) : pipe_no = " + finfo.pipe_no  + " ret = " + ret );
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
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
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

  // 指定ファイルの情報インスタンスを返す
  Fileinfo get_finfo( int fd ) {
    return( (Fileinfo)flist.elementAt( fd ));
  }

  // 標準入出力かどうかを返す
  boolean isSTD( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.isSTD( ));
  }

  // エラー入出力かどうかを返す
  boolean isERR( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }
    return( finfo.isERR( ));
  }

  // パイプ入出力かどうかを返す。
  boolean isPIPE( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
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
  public boolean unlink( String vpath ) {
    File file;
    file = new File( sysinfo.get_native_path( vpath ));
    return( file.delete( ));
  }

  // 指定ファイルの名前を変更する
  public boolean rename( String vpath_from, String vpath_to ) {
    File file_from, file_to;
    String path_from = sysinfo.get_native_path( vpath_from );
    String path_to   = sysinfo.get_native_path( vpath_to   );
    file_from = new File( path_from );
    file_to   = new File( path_to );
    return( file_from.renameTo( file_to ));
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
