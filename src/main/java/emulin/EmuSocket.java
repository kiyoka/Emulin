// ----------------------------------------
//  Emulin Socket
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/11 15:37:40 $ 
//  $Id: EmuSocket.java,v 1.11 2000/02/11 15:37:40 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.net.*;
import emulin.*;

public class EmuSocket extends FileAccess
{
  // Protocol families.  */
  static int 	AF_UNSPEC	 = 0;	/* Unspecified.  */
  static int 	AF_LOCAL	 = 1;	/* Local to host (pipes and file-domain).  */
  static int 	AF_UNIX	         = 1;       /* Old BSD name for PF_LOCAL.  */
  static int 	AF_FILE	         = 1;       /* POSIX name for PF_LOCAL.  */
  static int 	AF_INET		 = 2;	/* IP protocol family.  */
  static int 	AF_AX25		 = 3;	/* Amateur Radio AX.25.  */
  static int 	AF_IPX		 = 4;	/* Novell Internet Protocol.  */
  static int 	AF_APPLETALK	 = 5;	/* Don't use this.  */
  static int 	AF_NETROM	 = 6;	/* Amateur radio NetROM.  */
  static int 	AF_BRIDGE	 = 7;	/* Multiprotocol bridge.  */
  static int 	AF_AAL5		 = 8;	/* Reserved for Werner's ATM.  */
  static int 	AF_X25		 = 9;	/* Reserved for X.25 project.  */
  static int 	AF_INET6	 = 10;	/* IP version 6.  */
  static int 	AF_ROSE		 = 11;	/* Amateur Radio X.25 PLP       */
  static int 	AF_DECnet	 = 12;	/* Reserved for DECnet project  */
  static int 	AF_NETBEUI	 = 13;	/* Reserved for 802.2LLC project*/
  static int 	AF_SECURITY	 = 14;	/* Security callback pseudo AF */
  static int 	AF_KEY		 = 15;	/* PF_KEY key management API */
  static int 	AF_NETLINK	 = 16;
  static int 	AF_ROUTE	 = 16;      /* Alias to emulate 4.4BSD */
  static int 	AF_PACKET	 = 17;	/* Packet family                */
  static int 	AF_MAX		 = 32;	/* For now.. */

  // socketcall 用 
  static int    SOCK_STREAM    = 1;		/* Sequenced, reliable, connection-based
				                  byte streams.  */
  static int    SOCK_DGRAM     = 2;		/* Connectionless, unreliable datagrams
				                  of fixed maximum length.  */
  static int    SOCK_RAW       = 3;		/* Raw protocol interface.  */
  static int    SOCK_RDM       = 4;		/* Reliably-delivered messages.  */
  static int    SOCK_SEQPACKET = 5;		/* Sequenced, reliable, connection-based,
				                  datagrams of fixed maximum length.  */
  static int    SOCK_PACKET    = 10;		/* Linux specific way of getting packets
                   				   at the dev level.  For writing rarp and
                				   other similar things on the user level. */

  // 指定インスタンスの情報で自分をアップデートする。
  public void update_info( FileAccess _p ) {
    super.update_info( _p );
  }

  // emulation socket( ) of Linux
  public int socket( int domain, int type, int protocol ) {
    int ret = 0;
    Fileinfo finfo;
    if(! (( domain == AF_UNIX) || ( domain == AF_INET )) ) {
      process.println( " socket Error : domain " + domain + " not supported " );
      ret = -1;
    }
    if(! ((type == SOCK_STREAM) || (type == SOCK_DGRAM)) ) {
      process.println( " socket Error : type " + type + " not supported " );
      ret = -1;
    }
    if( 0 == ret ) {
      ret = FileOpen( "<sock>", "rw", Syscall.O_RDWR );
      if( sysinfo.verbose( ) ) {
	if( ret > 0 ) {
	  process.println( " socket( ) opened : " );
	  if( type == SOCK_STREAM ) { process.println( "    SOCK_STREAM " ); }
	  if( type == SOCK_DGRAM  ) { process.println( "    SOCK_DGRAM  " ); }
	}
	else {
	  process.println( " socket( ) open miss ! " );
	  return( -1 );
	}
      }
      finfo = (Fileinfo)flist.elementAt( ret );
      if( type == SOCK_STREAM ) { finfo.set_socket_type( true  ); }
      if( type == SOCK_DGRAM  ) { 
	finfo.set_socket_type( false );
	// ソケットを作成しておく( ポート指定なし )
	if( !finfo.make_server_socket( -1 )) {
	  return( -1 );
	}
      }
    }
    return( ret );
  }

  // emulation bind( ) of Linux
  public boolean bind( int fd, int ip, int port ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( false );
    }
    if( sysinfo.verbose( )) {
      process.println( " EmuSocket.bind( )    ip = " + Util.ip_str( Util.swap32( ip )));
    }

    // サーバーソケットを作成する ( ポート指定あり )
    if( !finfo.make_server_socket( port )) {
      return( false );
    }
    finfo.set_ip_address( ip );
    return( ret );
  }

  // emulation listen( ) of Linux
  public boolean listen( int fd, int back_log ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( false );
    }
    finfo.set_back_log( back_log );
    listen_start( fd, finfo );
    return( ret );
  }

  // リッスンの開始を行なう
  public void listen_start( int fd, Fileinfo finfo ) {
    finfo.subprocess = new SubProcess( sysinfo, finfo, fd );
    finfo.subprocess.set_listen_mode( finfo.get_sconn( ));
    finfo.subprocess.start( );
  }

  // emulation accept( ) of Linux
  // 新しく確保した fd を返す。
  public int accept( int fd ) {
    int new_fd = 0;
    int ip;
    int port;
    int u_time = 0;
    Fileinfo finfo     = (Fileinfo)flist.elementAt( fd );
    Fileinfo new_finfo;
    if( finfo == null ) {  // 無効な fd なら
      return( -1 );
    }

    if( sysinfo.verbose( )) {
      process.println( "EmuSocket.accept( ) " );
    }

    // listen がまだ開始されていない場合は自力で listen を開始する。
    if( null == finfo.subprocess ) {
      listen_start( fd, finfo );
      if( sysinfo.verbose( )) {
        process.println( "EmuSocket.accept( )    listen_started." );
      }
    }
    // listenポートに要求があるまで待つ
    while( SubProcess.ACCEPT_WAIT == finfo.subprocess.Accepted( )) {
      if( sysinfo.verbose( )) {
        process.println( "EmuSocket.accept( )    wait accept..." );
      }
      try { Thread.sleep( 500L ); }
      catch( InterruptedException m ) { };
      Thread.yield( );
      u_time -= 500L;
    }
    // ミスしたか？
    if( SubProcess.ACCEPT_MISS == finfo.subprocess.Accepted( )) {  return( -1 ); }

    // 新しい fd を取得する。
    new_fd = FileOpen( "<sock>", "rw", Syscall.O_RDWR );
    new_finfo = (Fileinfo)flist.elementAt( new_fd );
    if( new_finfo == null ) {  // 無効な fd なら
      if( sysinfo.verbose( )) {
	process.println( " new_finfo = null \n" );
      }
      return( -1 );
    }

    // 既にオープンされているサーバソケットをコピーする。
    new_finfo.sconn = finfo.sconn;
    
    // ip と port をコピーする。
    new_finfo.set_ip_address( finfo.get_ip_address( ));
    new_finfo.set_port(       finfo.get_port( ));

    // ストリームタイプに設定する。
    new_finfo.set_socket_type( true );

    // サーバーソケットを作成する。
    if( !ServerSocketOpen( new_fd, finfo.subprocess.conn )) {
      new_fd = -1;
    }

    if( sysinfo.verbose( )) {
      process.println( " EmuSocket.accept( )    set ip = " + Util.ip_str( Util.swap32( new_finfo.get_ip_address( ))));
    }

    // listen を再開する
    listen_start( fd, finfo );

    return( new_fd );
  }

  // サーバーソケットをオープンする
  public boolean ServerSocketOpen( int fd, Socket _conn ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }

    finfo.set_server_socket( _conn );

    if( true /* finfo.server_socket( ) */ ) {
      if( sysinfo.verbose( ) ) {
	process.println( " S:connect( ) OK!" );
      }
      start_subprocess( fd );
    }
    else {
      if( sysinfo.verbose( ) ) {
	process.println( " S:connect( ) missed!" );
      }
      ret = false;
    }
    return( ret );
  }


  // emulation connect( ) of Linux
  public boolean connect( int fd, int ip, int port ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( false );
    }
    if( !finfo.isSOCKET( )) {
      process.println( " socket Error : fd = " + fd + " is not opened. " );
      return( false );
    }
    ret = ClientSocketOpen( fd, ip, port );
    return( ret );
  }

  // emulation sendto( ) of Linux
  public boolean sendto( int fd, byte buf[], int flags, int ip, int port ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    // 無効な fd なら
    if( finfo == null ) { return( false ); }
    // flags は無視する
    finfo.set_ip_address( ip );
    finfo.set_port( port );
    if( !finfo.sendto( buf )) {
      if( sysinfo.verbose( )) {
	process.println( " sendto Error : fd = " + fd + " is not opened. " );
      }
      ret = false;
    }
    return( ret );
  }
  
  // emulation recvfrom( ) of Linux
  public int recvfrom( int fd, byte buf[], int flags, int addr_info[] ) {
    int ret = 0;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    // 無効な fd なら
    if( finfo == null ) { return( -1 ); }
    
    if( sysinfo.verbose( )) {
      process.println( " recvfrom : buf.length = " + buf.length );
    }

    // flags は無視する
    if( finfo.isOPEN( ) && finfo.Available( ) ) {
      synchronized ( finfo ) {
	if( finfo.isSTREAM( )) {
	  ret = finfo.Read( buf );
	}
	else {
	  ret = finfo.subprocess.read_byte_top( buf, addr_info, false );
	}
      }
    }
    else {
      ret = finfo.recvfrom( buf, addr_info );
    }
    if( ret < 0 ) {
      if( sysinfo.verbose( )) {
	process.println( " recvto Error : fd = " + fd + " is not opened. " );
      }
    }
    return( ret );
  }

  // クライアントソケットをオープンする。
  public boolean ClientSocketOpen( int fd, int ip, int port ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) { return( false ); }

    if( sysinfo.verbose( ) ) {
      process.println( " try connect( " + Util.ip_str( Util.swap32( ip )) + " , " + port + " ) " );
    }
    if( finfo.client_socket( ip, port )) {
      if( sysinfo.verbose( ) ) {
	process.println( " C:connect( ) OK!" );
      }
      start_subprocess( fd );
    }
    else {
      if( sysinfo.verbose( ) ) {
	process.println( " C:connect( ) missed!" );
      }
      ret = false;
    }
    return( ret );
  }

  // network入力監視用サブプロセスをスタートさせる。
  public boolean start_subprocess( int fd ) {
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( false );
    }
    finfo.subprocess = new SubProcess( sysinfo, finfo, fd );
    finfo.subprocess.start( );
    return( true );
  }

  // IPアドレスを返す
  public int get_ip_address( int fd ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( 0 );
    }
    return( finfo.get_ip_address( ));
  }

  // 接続先のIPアドレスを返す
  public int get_partner_ip_address( int fd ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( 0 );
    }
    return( finfo.get_partner_ip_address( ));
  }

  // ポート番号を返す
  public int get_port( int fd ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( 0 );
    }
    return( finfo.get_port( ));
  }

  // 接続先のポート番号を返す
  public int get_partner_port( int fd ) {
    boolean ret = true;
    Fileinfo finfo = (Fileinfo)flist.elementAt( fd );
    if( finfo == null ) {  // 無効な fd なら
      return( 0 );
    }
    return( finfo.get_partner_port( ));
  }

}
