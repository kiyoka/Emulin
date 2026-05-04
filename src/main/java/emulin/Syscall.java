// ----------------------------------------
//  Syscall ( Linux System call support )
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/07 15:22:34 $ 
//  $Id: Syscall.java,v 1.111 2000/02/07 15:22:34 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;
import emulin.device.*;
import java.net.*;

// Linux system call ID は   /usr/src/linux/arch/i386/kernel/entry.S を参照すること

public class Syscall extends EmuSocket
{
  static int O_ACCMODE = 0003;
  static int O_RDONLY  = 00;
  static int O_WRONLY  = 01;
  static int O_RDWR    = 02;
  static int O_CREAT   = 0100;	/* not fcntl */
  static int O_EXCL    = 0200;	/* not fcntl */
  static int O_NOCTTY  = 0400;	/* not fcntl */
  static int O_TRUNC   = 01000;	/* not fcntl */
  static int O_APPEND  = 02000;
  static int O_NONBLOCK = 04000;
  static int O_NDELAY	= O_NONBLOCK;
  static int O_SYNC	= 010000;
  static int O_FSYNC	= O_SYNC;
  static int O_ASYNC	= 020000;

  static int F_DUPFD	= 0;	/* dup */
  static int F_GETFD	= 1;	/* get f_flags */
  static int F_SETFD	= 2;	/* set f_flags */
  static int F_GETFL	= 3;	/* more flags (cloexec) */
  static int F_SETFL	= 4;
  static int F_DUPFD_CLOEXEC = 1030;	/* dup with FD_CLOEXEC */
  static int F_GETLK	= 5;
  static int F_SETLK	= 6;
  static int F_SETLKW	= 7;
  static int F_SETOWN	= 8;	/*  for sockets. */
  static int F_GETOWN	= 9;	/*  for sockets. */

  // ioctl用
  static int TCGETS	= 0x5401;
  static int TCSETS	= 0x5402;
  static int TCSETSW	= 0x5403;
  static int TCSETSF	= 0x5404;
  static int TCGETA	= 0x5405;
  static int TCSETA	= 0x5406;
  static int TCSETAW	= 0x5407;
  static int TCSETAF	= 0x5408;
  static int TCSBRK	= 0x5409;
  static int TCXONC	= 0x540A;
  static int TCFLSH	= 0x540B;
  static int TIOCEXCL	= 0x540C;
  static int TIOCNXCL	= 0x540D;
  static int TIOCSCTTY	= 0x540E;
  static int TIOCGPGRP	= 0x540F;
  static int TIOCSPGRP	= 0x5410;
  static int TIOCOUTQ	= 0x5411;
  static int TIOCSTI	= 0x5412;
  static int TIOCGWINSZ	= 0x5413;
  static int TIOCSWINSZ	= 0x5414;
  static int TIOCMGET	= 0x5415;
  static int TIOCMBIS	= 0x5416;
  static int TIOCMBIC	= 0x5417;
  static int TIOCMSET	= 0x5418;
  static int TIOCGSOFTCAR	= 0x5419;
  static int TIOCSSOFTCAR	= 0x541A;
  static int FIONREAD	= 0x541B;
  static int TIOCINQ	= FIONREAD;
  static int TIOCLINUX	= 0x541C;
  static int TIOCCONS	= 0x541D;
  static int TIOCGSERIAL = 0x541E;
  static int TIOCSSERIAL = 0x541F;
  static int TIOCPKT	 = 0x5420;
  static int FIONBIO	 = 0x5421;
  static int TIOCNOTTY	= 0x5422;
  static int TIOCSETD	= 0x5423;
  static int TIOCGETD	= 0x5424;
  static int TCSBRKP		= 0x5425;  // Needed for POSIX tcsendbreak()
  static int TIOCTTYGSTRUCT	= 0x5426;  // For debugging only
  static int FIONCLEX	        = 0x5450;  // these numbers need to be adjusted.
  static int FIOCLEX		= 0x5451;
  static int FIOASYNC	        = 0x5452;
  static int TIOCSERCONFIG	= 0x5453;
  static int TIOCSERGWILD	= 0x5454;
  static int TIOCSERSWILD	= 0x5455;
  static int TIOCGLCKTRMIOS	= 0x5456;
  static int TIOCSLCKTRMIOS	= 0x5457;
  static int TIOCSERGSTRUCT	= 0x5458;  // For debugging only
  static int TIOCSERGETLSR      = 0x5459;  // Get line status register
  static int TIOCSERGETMULTI    = 0x545A;  // Get multiport config
  static int TIOCSERSETMULTI    = 0x545B;  // Set multiport config
  static int TIOCMIWAIT	        = 0x545C;  // wait for a change on serial input line(s)
  static int TIOCGICOUNT        = 0x545D;  // read serial port inline interrupt counts

  // getrlimit用
  static int   RLIMIT_CPU      = 0;
  static int   RLIMIT_FSIZE    = 1;
  static int   RLIMIT_DATA     = 2;
  static int   RLIMIT_STACK    = 3;
  static int   RLIMIT_CORE     = 4;
  static int   RLIMIT_RSS      = 5;
  static int   RLIMIT_NOFILE   = 6;
  static int   RLIMIT_OFILE    = 7;
  static int   RLIMIT_AS       = 8;
  static int   RLIMIT_NPROC    = 9;
  static int   RLIMIT_MEMLOCK  = 10;
  static int   RLIMIT_NLIMITS  = 11;
  static int   RLIM_NLIMITS    = 12;
  static int   RLIM_INFINITY   = 13;

  // socketcall用
  static int   SOCKOP_socket		 = 1;
  static int   SOCKOP_bind		 = 2;
  static int   SOCKOP_connect		 = 3;
  static int   SOCKOP_listen		 = 4;
  static int   SOCKOP_accept		 = 5;
  static int   SOCKOP_getsockname	 = 6;
  static int   SOCKOP_getpeername	 = 7;
  static int   SOCKOP_socketpair	 = 8;
  static int   SOCKOP_send		 = 9;
  static int   SOCKOP_recv		 = 10;
  static int   SOCKOP_sendto		 = 11;
  static int   SOCKOP_recvfrom		 = 12;
  static int   SOCKOP_shutdown		 = 13;
  static int   SOCKOP_setsockopt	 = 14;
  static int   SOCKOP_getsockopt	 = 15;
  static int   SOCKOP_sendmsg		 = 16;
  static int   SOCKOP_recvmsg		 = 17;

  // sigaction用
  static int   SA_NOCLDSTOP		 = 1;
  static int   SA_ONESHOT		 = 0x80000000;
  static int   SA_RESETHAND		 = 0x80000000;
  static int   SA_RESTART		 = 0x10000000;
  static int   SA_NOMASK		 = 0x40000000;
  static int   SA_NODEFER	 	 = 0x40000000;

  static int MSG_OOB        = 0x1;  /* process out-of-band data */
  static int MSG_DONTROUTE  = 0x4;  /* bypass routing, use direct interface */

  // システムコールのエラー番号
  static int EPERM		= - 1;	/* Operation not permitted */
  static int ENOENT		= - 2;	/* No such file or directory */
  static int ESRCH		= - 3;	/* No such process */
  static int EINTR		= - 4;	/* Interrupted system call */
  static int EIO		= - 5;	/* I/O error */
  static int ENXIO		= - 6;	/* No such device or address */
  static int E2BIG		= - 7;	/* Arg list too long */
  static int ENOEXEC		= - 8;	/* Exec format error */
  static int EBADF		= - 9;	/* Bad file number */
  static int ECHILD		= -10;	/* No child processes */
  static int EAGAIN		= -11;	/* Try again */
  static int ENOMEM		= -12;	/* Out of memory */
  static int EACCES		= -13;	/* Permission denied */
  static int EFAULT		= -14;	/* Bad address */
  static int ENOTBLK		= -15;	/* Block device required */
  static int EBUSY		= -16;	/* Device or resource busy */
  static int EEXIST		= -17;	/* File exists */
  static int EXDEV		= -18;	/* Cross-device link */
  static int ENODEV		= -19;	/* No such device */
  static int ENOTDIR		= -20;	/* Not a directory */
  static int EISDIR		= -21;	/* Is a directory */
  static int EINVAL		= -22;	/* Invalid argument */
  static int ENFILE		= -23;	/* File table overflow */
  static int EMFILE		= -24;	/* Too many open files */
  static int ENOTTY		= -25;	/* Not a typewriter */
  static int ETXTBSY		= -26;	/* Text file busy */
  static int EFBIG		= -27;	/* File too large */
  static int ENOSPC		= -28;	/* No space left on device */
  static int ESPIPE		= -29;	/* Illegal seek */
  static int EROFS		= -30;	/* Read-only file system */
  static int EMLINK		= -31;	/* Too many links */
  static int EPIPE		= -32;	/* Broken pipe */
  static int EDOM		= -33;	/* Math argument out of domain of func */
  static int ERANGE		= -34;	/* Math result not representable */
  static int EDEADLK		= -35;	/* Resource deadlock would occur */
  static int ENAMETOOLONG	= -36;	/* File name too long */
  static int ENOLCK		= -37;	/* No record locks available */
  static int ENOSYS		= -38;	/* Function not implemented */
  static int ENOTEMPTY          = -39;	/* Directory not empty */
  static int ELOOP		= -40;	/* Too many symbolic links encountered */
  static int EWOULDBLOCK	= EAGAIN;	/* Operation would block */
  static int ENOMSG		= -42;	/* No message of desired type */
  static int EIDRM		= -43;	/* Identifier removed */
  static int ECHRNG		= -44;	/* Channel number out of range */
  static int EL2NSYNC	        = -45;	/* Level 2 not synchronized */
  static int EL3HLT		= -46;	/* Level 3 halted */
  static int EL3RST		= -47;	/* Level 3 reset */
  static int ELNRNG		= -48;	/* Link number out of range */
  static int EUNATCH		= -49;	/* Protocol driver not attached */
  static int ENOCSI		= -50;	/* No CSI structure available */
  static int EL2HLT		= -51;	/* Level 2 halted */
  static int EBADE		= -52;	/* Invalid exchange */
  static int EBADR		= -53;	/* Invalid request descriptor */
  static int EXFULL		= -54;	/* Exchange full */
  static int ENOANO		= -55;	/* No anode */
  static int EBADRQC		= -56;	/* Invalid request code */
  static int EBADSLT		= -57;	/* Invalid slot */

  static int EBFONT		= -59;	/* Bad font file format */
  static int ENOSTR		= -60;	/* Device not a stream */
  static int ENODATA		= -61;	/* No data available */
  static int ETIME		= -62;	/* Timer expired */
  static int ENOSR		= -63;	/* Out of streams resources */
  static int ENONET		= -64;	/* Machine is not on the network */
  static int ENOPKG		= -65;	/* Package not installed */
  static int EREMOTE		= -66;	/* Object is remote */
  static int ENOLINK		= -67;	/* Link has been severed */
  static int EADV		= -68;	/* Advertise error */
  static int ESRMNT		= -69;	/* Srmount error */
  static int ECOMM		= -70;	/* Communication error on send */
  static int EPROTO		= -71;	/* Protocol error */
  static int EMULTIHOP		= -72;	/* Multihop attempted */
  static int EDOTDOT		= -73;	/* RFS specific error */
  static int EBADMSG		= -74;	/* Not a data message */
  static int EOVERFLOW		= -75;	/* Value too large for defined data type */
  static int ENOTUNIQ		= -76;	/* Name not unique on network */
  static int EBADFD		= -77;	/* File descriptor in bad state */
  static int EREMCHG		= -78;	/* Remote address changed */
  static int ELIBACC		= -79;	/* Can not access a needed shared library */
  static int ELIBBAD		= -80;	/* Accessing a corrupted shared library */
  static int ELIBSCN		= -81;	/* .lib section in a.out corrupted */
  static int ELIBMAX		= -82;	/* Attempting to link in too many shared libraries */
  static int ELIBEXEC		= -83;	/* Cannot exec a shared library directly */
  static int EILSEQ		= -84;	/* Illegal byte sequence */
  static int ERESTART		= -85;	/* Interrupted system call should be restarted */
  static int ESTRPIPE		= -86;	/* Streams pipe error */
  static int EUSERS		= -87;	/* Too many users */
  static int ENOTSOCK		= -88;	/* Socket operation on non-socket */
  static int EDESTADDRREQ	= -89;	/* Destination address required */
  static int EMSGSIZE		= -90;	/* Message too long */
  static int EPROTOTYPE		= -91;	/* Protocol wrong type for socket */
  static int ENOPROTOOPT	= -92;	/* Protocol not available */
  static int EPROTONOSUPPORT	= -93;	/* Protocol not supported */
  static int ESOCKTNOSUPPORT	= -94;	/* Socket type not supported */
  static int EOPNOTSUPP		= -95;	/* Operation not supported on transport endpoint */
  static int EPFNOSUPPORT	= -96;	/* Protocol family not supported */
  static int EAFNOSUPPORT	= -97;	/* Address family not supported by protocol */
  static int EADDRINUSE		= -98;	/* Address already in use */
  static int EADDRNOTAVAIL	= -99;	/* Cannot assign requested address */
  static int ENETDOWN		= -100;	/* Network is down */
  static int ENETUNREACH	= -101;	/* Network is unreachable */
  static int ENETRESET		= -102;	/* Network dropped connection because of reset */
  static int ECONNABORTED	= -103;	/* Software caused connection abort */
  static int ECONNRESET		= -104;	/* Connection reset by peer */
  static int ENOBUFS		= -105;	/* No buffer space available */
  static int EISCONN		= -106;	/* Transport endpoint is already connected */
  static int ENOTCONN		= -107;	/* Transport endpoint is not connected */
  static int ESHUTDOWN		= -108;	/* Cannot send after transport endpoint shutdown */
  static int ETOOMANYREFS	= -109;	/* Too many references: cannot splice */
  static int ETIMEDOUT		= -110;	/* Connection timed out */
  static int ECONNREFUSED	= -111;	/* Connection refused */
  static int EHOSTDOWN		= -112;	/* Host is down */
  static int EHOSTUNREACH	= -113;	/* No route to host */
  static int EALREADY		= -114;	/* Operation already in progress */
  static int EINPROGRESS	= -115;	/* Operation now in progress */
  static int ESTALE		= -116;	/* Stale NFS file handle */
  static int EUCLEAN		= -117;	/* Structure needs cleaning */
  static int ENOTNAM		= -118;	/* Not a XENIX named type file */
  static int ENAVAIL		= -119;	/* No XENIX semaphores available */
  static int EISNAM		= -120;	/* Is a named type file */
  static int EREMOTEIO		= -121;	/* Remote I/O error */
  static int EDQUOT		= -122;	/* Quota exceeded */
  static int ENOMEDIUM		= -123;	/* No medium found */
  static int EMEDIUMTYPE	= -124;	/* Wrong medium type */

  static int R_OK = 4;		/* Test for read permission.  */
  static int W_OK = 2;		/* Test for write permission.  */
  static int X_OK = 1;		/* Test for execute permission.  */
  static int F_OK = 0;		/* Test for existence.  */
  Memory mem;

  Syscall( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
  }

  // 自分の複製を返す (サブクラスでオーバーライドすること)
  public Syscall duplicate( Process _process ) {
    Syscall _syscall = new Syscall( sysinfo, _process );
    _syscall.mem  = mem;
    _syscall.update_info( (FileAccess)this );
    return( _syscall );
  }

  // メモリシステムを接続する
  void connect_mem( Memory _mem ) {
    mem = _mem;
  }

  // ABI サブクラス (SyscallI386 等) でオーバーライドする
  public long call( int id, long bx, long cx, long dx, long si, long di ) {
    process.println( "Emulin Error : Syscall.call() must be overridden by ABI subclass" );
    sys_exit( 1, 0, 0, 0, 0 );
    return( 0 );
  }

  long sys_exit( long bx, long cx, long dx, long si, long di ) {
    int exit_code = (int)bx;
    if( sysinfo.debug( )) {
      process.println( "Program is terminated ... ( " + exit_code + " ) " );
    }
    System.out.flush( );
    System.err.flush( );
    sysinfo.kernel.last_exit_code = exit_code;
    process.exit_code = exit_code;
    process.set_exit_flag( );
    return( 0 );
  }
  synchronized long sys_fork( long bx, long cx, long dx, long si, long di ) {
    int p = 0;
    if( sysinfo.verbose( )) {
      process.println( "1:Forking process  [ " + process.pid + " ]  new pid = [" + p + "]");
    }
    p = sysinfo.kernel.fork( process );
    if( sysinfo.verbose( )) {
      process.println( "2:Forking process  [ " + process.pid + " ]  new pid = [" + p + "]");
    }
    return( p );
  }
  long sys_read( long bx, long cx, long dx, long si, long di ) {
    int fd      = (int)bx;
    long address = cx;
    int len     = (int)dx;
    if( sysinfo.debug( ) ) { 
      process.println( "sys_read( fd = " + fd + " adrs = " + Util.hexstr( address, 8 ) + " len = " + len + " )" );
    }

    if( isSTD( fd ) || isERR( fd )) { // stdin,out or stderr
      byte buf[] = new byte[len];
      int i;
      len = sysinfo.kernel.console.read( buf, process );

      for( i = 0 ; i < len ; i++ ) {
	mem.store8( address + i, buf[i] );
      }
    }
    else {
      int i;
      byte buf[] = new byte[len];
      if( sysinfo.verbose( )) {
	process.println( " Not std read " );
      }
      len = FileRead( fd, buf );
      if( len < 0 ) { len = EBADF; } /* Bad file number */
      else {
	for( i = 0 ; i < len ; i++ ) {
	  mem.store8( address + i, (byte)buf[i] );
	}
      }
      if( sysinfo.verbose( )) {
	//	mem.dump( address, len+16 );
      }
    }
    return( len );
  }
  long sys_write( long bx, long cx, long dx, long si, long di ) {
    int fd      = (int)bx;
    long address = cx;
    int len     = (int)dx;

    if( sysinfo.verbose( )) {
      process.println( "sys_write: isSTD( fd ) = " + isSTD( fd ) + " isERR( fd ) = " + isERR( fd ));
    }

    if( isSTD( fd ) || isERR( fd )) { // stdout || stderr
      int i;
      byte buf[] = new byte[len];
      for( i = 0 ; i < len ; i++ ) {
	buf[i] = mem.load8( address + i );
      }
      sysinfo.kernel.console.write( buf, isERR( fd ));
    }
    else {
      byte buf[] = new byte[len];
      mem.fetch( address, buf );
      if( sysinfo.verbose( )) {
	process.println( " Not std write " );
      }
      if( !FileWrite( fd, buf )) {
	len = EPIPE;
      }
      if( sysinfo.verbose( )) {
	//	mem.dump( address, len+16 );
      }
    }
    return( len );
  }
  long sys_open( long bx, long cx, long dx, long si, long di ) {
    String name = mem.loadString( bx );
    String mode = "r";
    int full_md = (int)cx;
    int md = (int)cx & O_ACCMODE;
    int ret = 0;
    Inode inode;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    inode = new Inode( name, sysinfo );

    if( System.getenv("EMULIN_TRACE_OPEN") != null ) {
      System.err.println("DBG open: name='"+name+"' md="+md+" exists="+inode.isExists());
    }
    if((md == O_RDONLY) && !inode.isExists( )) { ret = ENOENT; }  // No such file or directory
    else {
      if( (md == O_RDONLY) && !inode.isReadable( )) { ret = EPERM; } // not Permitted 
      else {
	if( md == O_RDONLY ) { mode = "r"; }
	if( md == O_WRONLY ) { mode = "rw"; }
	if( md == O_RDWR )   { mode = "rw"; }
	ret = FileOpen( name, mode, full_md );
	if( sysinfo.verbose( )) {
	  process.println( "  " + ret + " = SYS_OPEN( \"" + name + "\",\"" + mode + "\")" );
	}
      }
    }
    return( ret );
  }
  long sys_close( long bx, long cx, long dx, long si, long di ) {
    int fd  = (int)bx;
    int ret = -1;
    if( fd >= 0 ) {
      if( FileClose( fd )) { ret = 0; }
    }
    return( ret );
  }
  long sys_unlink( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    int ret = 0;
    String name = mem.loadString( name_p ); 
    Inode inode;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    inode = new Inode( name, sysinfo );
    if( !inode.isExists( )) { ret = ENOENT; }  // No such file or directory
    else {
      if( !unlink( name ))  { ret = EPERM; }
    }
    if( sysinfo.verbose( )) {
      process.println( "   " + ret + " = unlink( '" + name + "' ); " );
    }
    return( ret );
  }
  long sys_execve( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    String name = mem.loadString( name_p );
    String tmp_s[] = new String[256];
    String _args[];
    String _envs[];
    int argv = (int)cx;
    int envp = (int)dx;
    int p;
    int i;
    // args の解析
    for( i = 0 ; true ; i++ ) {
      p = mem.load32( argv+i*4 );
      if( 0 == p ) break;
      tmp_s[i] = mem.loadString( p );
      if( sysinfo.verbose( )) {
	process.println( "exec arg: " +  i + " str = " + tmp_s[i] );
      }
    }
    _args = new String[i];
    System.arraycopy( tmp_s, 0, _args, 0, i );
    _args[0] = name;
    // envs の解析
    for( i = 0 ; true ; i++ ) {
      p = mem.load32( envp+i*4 );
      if( 0 == p ) break;
      tmp_s[i] = mem.loadString( p );
      if( sysinfo.verbose( )) {
	process.println( "exec env: " +  i + " str = " + tmp_s[i] );
      }
    }
    _envs = new String[i];
    System.arraycopy( tmp_s, 0, _envs, 0, i );

    while( !sysinfo.kernel.exec_request( process.pid, _args, _envs )) {
      if( sysinfo.verbose( )) {
	process.println( "execve : waiting   exec request  accept " );
      }
      if( sysinfo.verbose( )) { process.println( "wait exec_request  " ); }
      try { Thread.sleep( 1000L ); }
      catch( InterruptedException m ) { };
      Thread.yield( );
    }
    process.set_exit_flag( ); // 自スレッドが run() の while ループを抜けて自然終了する
    return( 0 );
  }
  long sys_chdir( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    String name = mem.loadString( name_p );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists( )) return ENOENT;
    process.set_curdir( name );
    return( 0 );
  }
  long sys_time( long bx, long cx, long dx, long si, long di ) {
    int ret = (int)(System.currentTimeMillis( ) / 1000L);
    return( ret );
  }
  long sys_chmod( long bx, long cx, long dx, long si, long di ) {
    String name = mem.loadString( bx );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    String native_path = sysinfo.get_native_path( name );
    int mode = (int)cx & 0777;
    java.io.File f = new java.io.File( native_path );
    if( !f.exists( ) ) return( ENOENT );
    /* Java.io.File は POSIX 9bit を直接設定できないので、まず java.nio で試し、
       失敗したら canRead/canWrite/canExecute だけ反映する */
    try {
      java.nio.file.Path p = f.toPath( );
      java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = new java.util.HashSet<>( );
      if( (mode & 0400) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_READ );
      if( (mode & 0200) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_WRITE );
      if( (mode & 0100) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE );
      if( (mode & 0040) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_READ );
      if( (mode & 0020) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_WRITE );
      if( (mode & 0010) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE );
      if( (mode & 0004) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_READ );
      if( (mode & 0002) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE );
      if( (mode & 0001) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE );
      java.nio.file.Files.setPosixFilePermissions( p, perms );
    } catch( UnsupportedOperationException | java.io.IOException e ) {
      f.setReadable(  (mode & 0400) != 0, true );
      f.setWritable(  (mode & 0200) != 0, true );
      f.setExecutable((mode & 0100) != 0, true );
    }
    return( 0 );
  }
  long sys_chown( long bx, long cx, long dx, long si, long di ) { return( 0 ); }
  long sys_lseek( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    long offset = cx;  // off_t は 64-bit
    int whence = (int)dx;
    return( FileSeek( fd, (int)offset, whence ));
  }
  long sys_getpid( long bx, long cx, long dx, long si, long di ) {    return( process.pid );  }
  long sys_mount( long bx, long cx, long dx, long si, long di )  {
    String devname = mem.loadString( bx );
    String dirname = mem.loadString( cx );
    sysinfo.add_mountpoint( dirname, devname );
    if( sysinfo.verbose( )) {
      process.println( " sys_mount : dev[" +devname+ "]  dir[" +dirname+ "]" );
    }
    return( 0 );
  }
  long sys_umount( long bx, long cx, long dx, long si, long di )  {
    long name_p = bx;
    String name = mem.loadString( name_p );
    sysinfo.remove_mountpoint( name );
    if( sysinfo.verbose( )) {
      process.println( " sys_umount : [" +name+ "]" );
    }
    return( 0 );
  }
  long sys_alarm( long bx, long cx, long dx, long si, long di )  {    return( 0 ); }
  long sys_pause( long bx, long cx, long dx, long si, long di )  {  
    if( sysinfo.verbose( )) {
      process.println( " Info : process is pause" );
    }
    while( true ) {
      Thread.yield( );
      try { Thread.sleep( 1000L ); }
      catch( InterruptedException m ) { };
    }
  }
  long sys_utime( long bx, long cx, long dx, long si, long di )  {    return( 0 );   }
  long sys_access( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    int mode = (int)cx;
    int ret = 0;
    String name = mem.loadString( name_p ); 
    Inode inode;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    inode = new Inode( name, sysinfo );
    if( sysinfo.verbose( )) {
      process.println( " sys_access : mode = " + Util.hexstr( mode, 8 ));
    }
    if( 0 != ( mode &= F_OK )) {
      if( !inode.isExists( )) { ret = -1; }  // No such file or directory
    }
    if( 0 != ( mode &= R_OK )) {
      if( !inode.isReadable( )) { ret = -1; }  // No such file or directory
    }      
    if( 0 != ( mode &= W_OK )) {
      if( !inode.isWritable( )) { ret = -1; }  // No such file or directory
    }      
    if( !inode.isExists( )) { ret = -1; }
    return( ret );
  }
  long sys_sync( long bx, long cx, long dx, long si, long di )   {    return( 0 );   }
  long sys_kill( long bx, long cx, long dx, long si, long di )   {    return( 0 );   }
  long sys_rename( long bx, long cx, long dx, long si, long di ) {
    int ret = 0;
    String name_from = mem.loadString( bx );
    String name_to   = mem.loadString( cx );
    name_from = sysinfo.get_full_path( process.get_curdir( ), name_from );
    name_to   = sysinfo.get_full_path( process.get_curdir( ), name_to   );
    Inode inode = new Inode( name_from, sysinfo );
    if( !inode.isExists( )) { ret = ENOENT; }  // No such file or directory
    else {
      if( !rename( name_from, name_to )) { ret = EPERM; }
    }
    if( sysinfo.verbose( )) {
      process.println( "   " + ret + " = rename( '" + name_from + "," + name_to + "' ); " );
    }
    return( ret );
  }
  long sys_mkdir( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    int mode = (int)cx;
    int ret = 0;
    String name = sysinfo.get_full_path( process.get_curdir( ), mem.loadString( name_p ));
    Inode inode = new Inode( name, sysinfo );
    if( inode.isExists( ) ) return EEXIST;  // mkdir -p の中間階層用
    if( !mkdir( name )) {
      ret = EPERM;
    }
    return( ret );
  }
  long sys_rmdir( long bx, long cx, long dx, long si, long di ) {  return( sys_unlink( bx, cx, dx, si, di )); }
  long sys_dup( long bx, long cx, long dx, long si, long di ) {
    int i;
    int fd = (int)bx;
    int new_fd = search_empty_fd( );
    Dup( fd, new_fd );
    return( new_fd );
  }

  long sys_pipe( long bx, long cx, long dx, long si, long di ) {
    int ret_in;
    int ret_out;
    int pipe_no;
    ret_in  = FileOpen( "<pipe>", "r",  O_RDONLY );
    ret_out = FileOpen( "<pipe>", "rw", O_WRONLY );
    mem.store32( bx,   ret_in );
    mem.store32( bx+4, ret_out );
    pipe_no = sysinfo.kernel.connect_pipe( );
    set_pipe( pipe_no, ret_in );
    set_pipe( pipe_no, ret_out );
    if( sysinfo.verbose( )) {
      process.println( "  " + ret_in + "," + ret_out + " = SYS_PIPE( )" );
    }
    return( 0 );
  }
  long sys_times( long bx, long cx, long dx, long si, long di )   {    return( 0 ); }
  long sys_setuid( long bx, long cx, long dx, long si, long di ) {    process.uid = (int)bx; return( 0 );   }
  long sys_getuid( long bx, long cx, long dx, long si, long di ) {    return( process.uid ); }
  long sys_setgid( long bx, long cx, long dx, long si, long di ) {    process.gid = (int)bx; return( 0 );   }
  long sys_getgid( long bx, long cx, long dx, long si, long di ) {    return( process.gid ); }
  long sys_brk( long bx, long cx, long dx, long si, long di ) {
    if( bx != 0 ) {
      mem.set_curbrk( bx );
    }
    return mem.get_curbrk( );
  }
  long sys_geteuid( long bx, long cx, long dx, long si, long di ) {
    return( process.uid );
  }
  long sys_getegid( long bx, long cx, long dx, long si, long di ) {
    return( process.gid );
  }
  long sys_ioctl( long bx, long cx, long dx, long si, long di ) {
    int i;
    int fd = (int)bx;
    int request = (int)cx;
    long address = dx;
    boolean done = false;
    Fileinfo finfo = get_finfo( fd );
    if( TCGETS == request ) {  // TCGETS
      mem.store32( address , finfo.c_iflag   );   address += 4;
      mem.store32( address , finfo.c_oflag   );   address += 4;
      mem.store32( address , finfo.c_cflag   );   address += 4;
      mem.store32( address , finfo.c_lflag   );   address += 4;
      mem.store8 ( address , finfo.c_line    );   address += 1;
      for( i = 0 ; i < 19 ; i++ ) {
        mem.store8( address , finfo.c_cc[i]   );   address += 1;
      }
      done = true;
    }
    if(( TCSETS == request ) || (TCSETSW == request )) {
      finfo.c_iflag = mem.load32( address );   address += 4;
      finfo.c_oflag = mem.load32( address );   address += 4;
      finfo.c_cflag = mem.load32( address );   address += 4;
      finfo.c_lflag = mem.load32( address );   address += 4;
      finfo.c_line  = mem.load8 ( address );   address += 1;
      for( i = 0 ; i < 19 ; i++ ) {
        finfo.c_cc[i] = mem.load8( address );   address += 1;
      }
      if( isSTD( fd ) || isERR( fd )) {
	sysinfo.kernel.console.set_parameter( finfo.c_lflag, finfo.c_iflag, finfo.c_oflag, finfo.c_cc );
      }
      done = true;
    }
    if( TIOCGWINSZ == request ) {  //                   const struct winsize *
      mem.store16( address , (short)25   );  address += 2;
      mem.store16( address , (short)80   );  address += 2;
      mem.store16( address , (short)0   );   address += 2;
      mem.store16( address , (short)0   );   address += 2;
      done = true;
    }
    if( FIONBIO == request ) { // Non blocking IO 
      // Fix Me! : このパラメータに対する動作がわかりません。どなたか記述してください。!  (とりあえずなにもせず,処理完了したような顔をしておきます。)
      done = true;
    }
    if( !done ) {
      process.println( " Unsupported request_no = [" + Util.hexstr( request, 4 ) + "] in sys_ioctl( ) " );
    }
    return( 0 );
  }
  long sys_fcntl( long bx, long cx, long dx, long si, long di ) {
    int i;
    int fd = (int)bx;
    int command = (int)cx;
    int arg = (int)dx;

    if( F_DUPFD == command || F_DUPFD_CLOEXEC == command ) {
      // F_DUPFD : arg 以上の最小空き fd を探して fd を dup する。
      // CLOEXEC は exec 越しの自動 close フラグだが本実装では未追跡のため無視。
      int newfd = arg;
      while( newfd < flist.size( ) && flist.elementAt( newfd ) != null ) newfd++;
      Dup( fd, newfd );
      return( newfd );
    }
    if( F_SETFD == command ) {	/* set f_flags */
      return( 0 );
    }
    if( F_GETFD == command ) {	/* get f_flags */
      return( 0 );
    }
    if( F_GETFL == command ) {	/* more flags (cloexec) */
      return( GetModeBit( fd ));
    }
    if( F_SETFL == command ) {	/* set f_flags */
      // O_NONBLOCK のみ Fileinfo に追跡する (read で EAGAIN を返すため)。
      Fileinfo finfo = get_finfo( fd );
      if( finfo != null ) {
        finfo.nonBlock = ((arg & O_NONBLOCK) != 0);
      }
      return( 0 );
    }
    return( 0 );
  }
  long sys_setpgid( long bx, long cx, long dx, long si, long di ) {   return( 0 ); }
  long sys_umask( long bx, long cx, long dx, long si, long di ) {
    int prev = process.umask;
    process.umask = (int)(bx & 0777);
    return( prev );
  }
  long sys_dup2( long bx, long cx, long dx, long si, long di ) {
    // dup2(oldfd=bx, newfd=cx) : 「newfd を強制的に oldfd の複製にする」
    // 既存の newfd が開いていれば閉じてから dup する。
    int oldfd = (int)bx;
    int newfd = (int)cx;
    if( oldfd == newfd ) return( newfd );
    if( newfd >= 0 && newfd < flist.size( ) && flist.elementAt( newfd ) != null ) {
      FileClose( newfd );
    }
    Dup( oldfd, newfd );
    return( newfd );
  }
  long sys_getppid( long bx, long cx, long dx, long si, long di ) {    return( 8 );  }
  long sys_getpgrp( long bx, long cx, long dx, long si, long di ) {    return( 9 );  }
  long sys_setsid( long bx, long cx, long dx, long si, long di )  {    return( 1 );  }
  long sys_sigaction( long bx, long cx, long dx, long si, long di ) {
    int signum   = (int)bx;
    int act_p    = (int)cx;
    int oldact_p = (int)dx;
    int sa_handler  = mem.load32( act_p +  0 );
    int sa_mask     = mem.load32( act_p +  4 );
    int sa_flags    = mem.load32( act_p +  8 );
    int sa_restorer = mem.load32( act_p + 12 );
    process.set_sigaction( signum, sa_handler );
    if( sysinfo.verbose( )) {
	process.println( "  act_p       = " + Util.hexstr( act_p, 8 ));
	process.println( "  signum      = " + signum + "(" + process.get_signame( signum ) + ")" );
	process.println( "  sa_handler  = " + Util.hexstr( sa_handler, 8 ));
	process.println( "  sa_flags    = " + Util.hexstr( sa_flags,   4 ));
    }
    return( 0 ); 
  }
  long sys_setrlimit( long bx, long cx, long dx, long si, long di ) {  return( 0 );  }
  long sys_getrlimit( long bx, long cx, long dx, long si, long di ) {
    int resource = (int)bx;
    long address = cx;
    boolean done = false;
    if( resource == RLIMIT_OFILE ) {
      mem.store32( address,   1024 );
      mem.store32( address+4, 1024 );
      done = true;
    }
    if( resource == RLIMIT_STACK ) {
      done = true;
    }
    if( !done ) {
      process.println( " Emulin error : getrlimit( )  unsupported resource no = " + resource );
    }
    return( 0 );
  }
  long sys_gettimeofday( long bx, long cx, long dx, long si, long di ) {
    long address = bx;
    if( address != 0 ) {
      long ms = System.currentTimeMillis( );
      mem.store64( address,     ms / 1000L );      // tv_sec
      mem.store64( address + 8, (ms % 1000L) * 1000L ); // tv_usec
    }
    return( 0 );
  }
  long sys_getgroups( long bx, long cx, long dx, long si, long di ) {   return( 0 ); }
  long sys_readlink( long bx, long cx, long dx, long si, long di )  {   return( EINVAL ); }
  long sys_mmap( long bx, long cx, long dx, long si, long di ) {
    int  base   = (int)bx;
    long adrs   = (long)arg( base, -1 ) & 0xFFFFFFFFL;
    int  length = arg( base, 0 );
    int  fd     = arg( base, 3 );
    int  offset = arg( base, 4 );
    adrs = mem.alloc_and_map( adrs, length, fd, offset );
    return( adrs );
  }
  long sys_munmap( long bx, long cx, long dx, long si, long di ) {
    long address = bx;
    int length = (int)cx;
    return( mem.free( address, length ));
  }
  long sys_ftruncate( long bx, long cx, long dx, long si, long di )  {
    int fd = (int)bx;
    long length = (long)cx & 0xFFFFFFFFL;
    String name = get_name( fd );
    if( name == null ) return( EBADF );
    /* <std>/<err>/<pipe> 等の特殊 fd は ftruncate 不可 */
    if( name.startsWith( "<" ) ) return( EINVAL );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    String native_path = sysinfo.get_native_path( name );
    try ( java.io.RandomAccessFile rf = new java.io.RandomAccessFile( native_path, "rw" ) ) {
      rf.setLength( length );
    } catch( java.io.IOException e ) { return( -1 ); }
    return( 0 );
  }
  long sys_fchmod( long bx, long cx, long dx, long si, long di )     {   return( 0 ); }
  long sys_socketcall( long bx, long cx, long dx, long si, long di ) {
    int func_id = (int)bx;
    int a0 = mem.load32( cx + 0 );
    int a1 = mem.load32( cx + 4 );
    int a2 = mem.load32( cx + 8 );
    int a3 = mem.load32( cx + 12 );
    int a4 = mem.load32( cx + 16 );
    boolean done = false;
    int ret = 0;
    if( SOCKOP_socket == func_id ) {
      int fd = a0;
      ret  = socket( fd, a1, a2 );
      if( sysinfo.verbose( )) {
	process.println( "socket( " + fd + " )" );
      }
      done = true;
    }
    if( SOCKOP_bind == func_id ) {
      int fd = a0;
      int port = Util.swap16( mem.load16( a1+2 ));
      int ip   = Util.swap32( mem.load32( a1+4 ));
      if( sysinfo.verbose( )) {
	process.println( "bind( " + fd + " )  ip = " + Util.ip_str( Util.swap32( ip )) + "  port = " + port );
      }
      if( !bind( fd, ip, port )) {
	ret  = -1;
      }
      done = true;
    }
    if( SOCKOP_connect == func_id ) {
      int fd = a0;
      int port = Util.swap16( mem.load16( a1+2 ));
      int ip   = Util.swap32( mem.load32( a1+4 ));
      if( sysinfo.verbose( )) {
	process.println( "connect( " + fd + " )   ip = " + Util.ip_str( Util.swap32( ip )));
      }
      if( !connect( fd, ip, port )) {
	ret = ECONNREFUSED;
      }
      done = true;
    }
    if( SOCKOP_listen == func_id ) {
      int fd = a0;
      int back_log = a1;
      if( sysinfo.verbose( )) {
	process.println( "listen( " + fd + " )"  );
      }
      listen( fd, back_log );
      done = true;
    }
    if( SOCKOP_accept == func_id ) {
      int fd = a0;
      int address = a1;
      int len     = a2;
      ret = accept( fd );
      if( ret >= 0 ) {
        mem.store16( address+0, (short)EmuSocket.AF_INET );
	mem.store16( address+2, Util.swap16( (short)get_partner_port( ret )));
	mem.store32( address+4, Util.swap32( get_partner_ip_address( ret )));
	if( sysinfo.verbose( )) {
	  process.println( " " + ret + " = accept( " + fd + " )  ip = " + Util.ip_str( get_partner_ip_address( ret )));
	}
	done = true;
      }
    }
    if( SOCKOP_setsockopt == func_id ) {
      process.println( " Warning : emulin is not impliment setsockopt( ) " );
      done = true;
    }
    if( SOCKOP_getsockname == func_id ) {
      int fd      = a0;
      int address = a1;
      int len     = a2;
      int port    = get_port( fd );
      int ip      = get_ip_address( fd );
      mem.store16( address+0, (short)EmuSocket.AF_INET );
      mem.store16( address+2, Util.swap16( (short)port ));
      mem.store32( address+4, Util.swap32( ip ));
      if( sysinfo.verbose( )) {
	process.println( "getsockname( " + fd + " )  ip = " + Util.ip_str( Util.swap32( ip )));
      }
      done = true;
    }
    if( SOCKOP_send == func_id ) {
      int fd      = a0;
      int address = a1;
      int len     = a2;
      byte[] buf = new byte[len];
      mem.fetch( address, buf );
      //      FileWrite( fd, buf );
      ret = len;
      if( sysinfo.verbose( )) {
	process.println( " Socket send " );
      }
      if( !FileWrite( fd, buf )) {
	ret = EPIPE;
      }

      if( sysinfo.verbose( )) {
	//	mem.dump( address, len+16 );
      }
      done = true;
    }
    if( SOCKOP_recv == func_id ) {
      int fd      = a0;
      int address = a1;
      int len     = a2;
      byte[] buf = new byte[len];
      /*      process.println( "SOCKOP_recv ... " ); */
      ret = FileRead( fd, buf );
      if( sysinfo.verbose( )) {
	process.println( " Socket recv " );
      }
      if( ret < 0 ) { ret = EBADF; } /* Bad file number */
      else {
	for( int i = 0 ; i < ret ; i++ ) {
	  mem.store8( address + i, (byte)buf[i] );
	}
      }
      if( sysinfo.verbose( )) {
	//	mem.dump( address, ret+16 );
      }
      done = true;
    }
    if( SOCKOP_sendto == func_id ) {
      int fd      = a0;
      int address = a1;
      int len     = a2;
      int flags   = a3;
      int to_p    = a4;
      int port = Util.swap16( mem.load16( a4+2 ));
      int ip   = Util.swap32( mem.load32( a4+4 ));
      byte[] buf = new byte[len];
      mem.fetch( address, buf );
      ret = len;
      if( !sendto( fd, buf, flags, ip, port )) {
	ret = EPIPE;
      }
      if( sysinfo.verbose( )) {
	process.println( " Socket sendto " );
      }
      if( sysinfo.verbose( )) {
	//	mem.dump( address, len+16 );
      }
      done = true;
    }
    if( SOCKOP_recvfrom == func_id ) {
      int fd      = a0;
      int address = a1;
      int len     = a2;
      int flags   = a3;
      int from_p  = a4;
      int iaddr_info[] = new int[2];
      byte[] buf = new byte[len];

      ret = recvfrom( fd, buf, flags, iaddr_info );
      if( ret < 0 ) { ret = EPIPE; } /* Bad file number */
      else {
	for( int i = 0 ; i < ret ; i++ ) {
	  mem.store8( address + i, (byte)buf[i] );
	}
	mem.store16( from_p+0, (short)EmuSocket.AF_INET );
	mem.store16( from_p+2, Util.swap16( (short)iaddr_info[1] )); //  port
	mem.store32( from_p+4, Util.swap32( iaddr_info[0]        )); //  ip address
      }
      if( sysinfo.verbose( )) {
	process.println( " Socket recvfrom : ip = " + Util.ip_str( Util.swap32( iaddr_info[0] )) + " port = " + iaddr_info[1] );
      }
      if( sysinfo.verbose( )) {
	//	mem.dump( address, ret+16 );
      }
      done = true;
    }
    if( SOCKOP_shutdown == func_id ) {
      done = true;
      ret = 0;
    }
    if( !done ) {
      process.println( " socketcall : Unsupported func_id = " + func_id );
      return( -1 );
    }
    return( ret );
  }
  long sys_stat( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    long address = cx;
    int ret = 0;
    String name = mem.loadString( name_p ); 
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists( )) { ret = ENOENT; }  // No such file or directory
    else {                 _set_file_stat( address, name ); }
    if( sysinfo.verbose( )) {
      process.println( "   " + ret + " = stat( '" + name + "' ); " );
    }
    return( ret );
  }

  long sys_fstat( long bx, long cx, long dx, long si, long di ) {
    int fd  = (int)bx;
    long address = cx;
    if( isSTD( fd ) || isERR( fd ) || isPIPE( fd )) {
      mem.store16( address , (short)0x1601 );  address += 2;
      mem.store16( address , (short)   0x0 );  address += 2;
      mem.store32( address ,        0x8bf2 );  address += 4;
      mem.store16( address , (short)0x21b6 );  address += 2;
      mem.store16( address , (short)0x1 );     address += 2;
      mem.store16( address , (short)0x0 );     address += 2;
      mem.store16( address , (short)0x0 );     address += 2;
      mem.store16( address , (short)0x302 );   address += 2;
      mem.store16( address , (short)0x0 );     address += 2;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0x1000 );         address += 4;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0x3680B8BC );     address += 4;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0x3680B8BC );     address += 4;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0x367BE8D1 );     address += 4;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0 );              address += 4;
      mem.store32( address , 0 );              address += 4;
    }
    else {
      _set_file_stat( address, get_name( fd ));
    }
    return( 0 );
  }
  long sys_wait4( long bx, long cx, long dx, long si, long di ) {
    int WNOHANG = 1;
    int pid          = (int)bx;
    int status_p     = (int)cx;
    int options      = (int)dx;
    int rusage_p     = (int)si;
    int ret_pid = 0;
    if( pid == -1 ) {
      while( true ) {
	if( sysinfo.verbose( )) {
	  process.println( "wait4 : waiting exit childs for pid = " + process.pid  );
	}
	ret_pid = sysinfo.kernel.is_child_exited( process.pid );
	if( options == WNOHANG ) { 
	  if( 0 < ret_pid ) {
	    ret_pid = -1;
	  }
	  break;
	}
	if( -1 < ret_pid ) { break; }
	Thread.yield( );
	try { Thread.sleep( 1000L ); }
	catch( InterruptedException m ) { };

	if( -1 != process.psig( )) {
	    ret_pid = EINTR;
	    break;
	}
      }
    }
    else {
      process.println( "wait4 : Not exist such pid process = " + pid  );
    }
    if( 0 != status_p ) {
      mem.store32( status_p, 0 );  // 現状は 常に 0 を返す
    }
    return( ret_pid ); 
  }

  // inode情報を指定アドレスに書き込む( Syscallクラスローカルメソッド )
  void _set_file_stat( long address, String name ) {
    Inode inode = new Inode( name, sysinfo );
    if( sysinfo.verbose( )) {
      process.println( "_set_file_stat( , " + name + " );   inode = " + inode.st_ino );
    }
    mem.store16( address , inode.st_dev  );      address += 2;
    mem.store16( address , (short)   0x0 );      address += 2;  // __pad1
    mem.store32( address , inode.st_ino  );      address += 4;
    mem.store16( address , inode.st_mode );      address += 2;
    mem.store16( address , inode.st_nlink   );   address += 2;
    mem.store16( address , inode.st_uid  );      address += 2;
    mem.store16( address , inode.st_gid  );      address += 2;
    mem.store16( address , inode.st_rdev );      address += 2;
    mem.store16( address , (short)   0x0 );      address += 2;  // __pad2
    mem.store32( address , (int)inode.st_size );      address += 4;
    mem.store32( address , inode.st_blksize );        address += 4;
    mem.store32( address , (int)inode.st_blocks  );   address += 4;
    mem.store32( address , (int)inode.st_atime   );   address += 4;
    mem.store32( address , 0 );                       address += 4;  // __unused1
    mem.store32( address , (int)inode.st_mtime   );   address += 4;
    mem.store32( address , 0 );                       address += 4;  // __unused2
    mem.store32( address , (int)inode.st_ctime   );   address += 4;
    mem.store32( address , 0 );                  address += 4;  // __unused3
    mem.store32( address , 0 );                  address += 4;  // __unused4
    mem.store32( address , 0 );                  address += 4;  // __unused5
  }
  long sys_uname( long bx, long cx, long dx, long si, long di )       {
    final int SYS_NMLN = 65;
    long address = bx;
    int ret = 0;
    InetAddress iaddr = null;
    String s;
    try { iaddr = InetAddress.getLocalHost( ); }
    catch( UnknownHostException m ) { ret = -1; }
    if( ret == 0 ) {
      s = iaddr.getHostName( );
      if( sysinfo.verbose( )) {
	process.println( "DEBUG : hostname = " + s );
      }
      mem.storeString( address + SYS_NMLN * 0 ,  "Emulin" );
      mem.storeString( address + SYS_NMLN * 1 ,  s );
      mem.storeString( address + SYS_NMLN * 2 ,  Version.get_version( ));
      mem.storeString( address + SYS_NMLN * 3 ,  "" );
      mem.storeString( address + SYS_NMLN * 4 ,  "i386" );
    }
    return( ret );
  }
  long sys_mprotect( long bx, long cx, long dx, long si, long di )    { return( 0 ); }
  long sys_sigprocmask( long bx, long cx, long dx, long si, long di ) { return( 0 ); }
  long sys_fchdir( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    int ret = 0;
    String name = get_name( fd ); 
    process.set_curdir( name );
    if( sysinfo.verbose( )) {
      process.println( " fchdir( " + fd + " )  path = " +  name );
    }
    return( ret );
  }
  long sys_personality( long bx, long cx, long dx, long si, long di ) { return( 0 ); }
  long sys_getdents( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    int dirp = (int)cx;
    int count = (int)dx;
    int i;
    int address = dirp;
    String list[];
    String name = get_name( fd );
    int start = get_ptr( fd );
    int d_off  = 0;
    int w_size = 0;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    list = file_list( name );

    for( i = 0 ; i < list.length ; i++ ) {
      int   old_d_off;
      short d_reclen = 0;
      String d_name  = list[i];
      int   memlen = list[i].length( )+1+10;
      int   len  =   (memlen / 4);      // alignment処理
      if( 0 != (memlen % 4)) {
	len++;
      }
      len *= 4;

      d_reclen       =  (short)len;
      old_d_off      =  d_off;
      d_off          += d_reclen;
      // メモリサイズをオーバーしたら,書き込まず終了する
      if( count < d_off ) { break; }

      // 書き込む
      if( start <= old_d_off ) {
	String fname = get_name( fd );
	Inode inode;
	if( '/' != fname.charAt( fname.length( )-1 )) { fname += "/"; }
	inode = new Inode( fname + d_name, sysinfo );
	if( sysinfo.verbose( )) {
	  process.println( "  " + inode.st_ino + ": "  + "memlen = " + memlen + " name = " + sysinfo.get_full_path( process.get_curdir( ), d_name ) + " d_reclen = "+ d_reclen );
	}
	mem.store32( address+0, inode.st_ino );
	mem.store32( address+4, d_off );
	mem.store16( address+8, d_reclen );
	mem.storeString( address+10, d_name );
	w_size += d_reclen;
	address = dirp + w_size;
      }
    }
    set_ptr( fd, d_off );
    return( w_size );
  }
  long sys_select( long bx, long cx, long dx, long si, long di )      {
    int n = (int)bx;
    int read_fds   = (int)cx;
    int write_fds  = (int)dx;
    int except_fds = (int)si;
    int timeout_p  = (int)di;
    int u_time = 0;
    boolean forever = false;
    int i;
    byte in_fds[];
    byte out_fds[];
    int bits[];

    if( n < 32 ) {
      n = 32;
    }
    bits     = new int[ n/32 ];
    in_fds   = new byte[ n ];
    out_fds  = new byte[ n ];
    int ret = 0;

    if( sysinfo.verbose( )) {
      process.println( " timeout_p       = " + timeout_p );
    }
    if( timeout_p == 0 ) {
      forever = true; // 永久にblockする = タイムアウトしない。
    }
    else {
      u_time = mem.load32( timeout_p + 0 ) * 1000 + mem.load32( timeout_p + 4 );
      if( sysinfo.verbose( )) {
	process.println( " u_time  = "  + u_time );
      }
    }

    for( ; ret == 0 ; ) {
      if( !forever ) {
	if( u_time < 0 ) { ret = 0; break; }
      }

      try { Thread.sleep( 20L ); }
      catch( InterruptedException m ) { };
      Thread.yield( );
      u_time -= 20L;

      if( sysinfo.verbose( )) {
	process.println( " n = " + n + " read_fds = " + Util.hexstr( read_fds, 8 ) + " write_fds = " + Util.hexstr( write_fds, 8 ));
      }
      
      if( read_fds != 0 ) {
	// 入力を調べる。
	for( i = 0 ; i < n/32 ; i++ ) {
	  bits[i] = mem.load32( read_fds+i*4 );
	  if( sysinfo.verbose( )) {
	    process.println( " read_fds [ " + i + " ] = " + Util.hexstr( bits[i], 8 ));
	  }
	}
	Util.selectbits_to_fds( in_fds, bits );
	for( i = 0 ; i < n ; i++ ) {
	  if( sysinfo.verbose( )) {
	    process.println( " fd in = " + i + " :  fds[fd] = " + in_fds[i] );
	  }
	  if( in_fds[i] == 1 ) {
	    if( isSTD( i ) || isERR( i )) { 
	      if( sysinfo.kernel.console.Available( )) {
		if( sysinfo.verbose( )) {
		  process.println( " fd = " + i + " : std  in available (STD|ERR)" );
		}
		ret++;
	      }
	      else { in_fds[i] = 0; }
	    }
	    else {
	      if( isEvent( i )) { // イベントがあったら,selectを有効にする
		if( sysinfo.verbose( )) {
		  process.println( " fd = " + i + " : file in available " );
		}
		ret++;
	      }
	      else { in_fds[i] = 0; }
	    }
	  }
	}
      }

      if( (write_fds != 0) ) {
	// 出力を調べる。
	for( i = 0 ; i < n/32 ; i++ ) {
	  bits[i] = mem.load32( write_fds+i );
	}
	Util.selectbits_to_fds( out_fds, bits );
	for( i = 0 ; i < n ; i++ ) {
	  if( sysinfo.verbose( )) {
	    process.println( " fd out = " + i + " :  fds[fd] = " + out_fds[i] );
	  }
	  if( ret != 0 ) { // 入力が存在している時は出力は遠慮する。
	     out_fds[i] = 0;
	  }
	  else {

	    if( out_fds[i] == 1 ) {
	      if( isSTD( i ) || isERR( i )) { 
		if( sysinfo.verbose( )) {
		  process.println( " fd = " + i + " : std  out available " );
		}
		ret++;
	      }
	      else {
		if( sysinfo.verbose( )) {
		  process.println( " fd = " + i + " : file out available " );
		}
		ret++;
	      }
	    }
	  }
	}
      }
    }

    if( read_fds != 0 ) {
      Util.fds_to_selectbits( bits, in_fds );
      // 書き戻し
      for( i = 0 ; i < n/32 ; i++ ) {
	mem.store32( read_fds+i, bits[i] );
      }
    }

    if( write_fds != 0 ) {
      Util.fds_to_selectbits( bits, out_fds );
      // 書き戻し
      for( i = 0 ; i < n/32 ; i++ ) {
	mem.store32( write_fds+i, bits[i] );
      }
    }

    if( except_fds != 0 ) {
      // 書き戻し
      for( i = 0 ; i < n/32 ; i++ ) {
	bits[i] = 0;
	mem.store32( except_fds+i, bits[i] );
      }
    }
    return( ret );
  }
  long sys_flock( long bx, long cx, long dx, long si, long di ) {   return( 0 ); }
  long sys_writev( long bx, long cx, long dx, long si, long di ) {
    // memo:writeシステムコールを使用する
    int fd      = (int)bx;
    int iovec_p = (int)cx;
    int count   = (int)dx;
    int i;
    long ret    = 0;
    for( i = 0 ; i < count ; i++ ) {
      int cur_p   = mem.load32( iovec_p + (i * 8) + 0 );
      int cur_len = mem.load32( iovec_p + (i * 8) + 4 );
      long len;
      len = sys_write( fd, cur_p, cur_len, 0, 0 );
      if( len < 0 ) {
	ret = -1;
	break;
      }
      ret += len;
    }
    return( ret );
  }
  long sys_nanosleep( long bx, long cx, long dx, long si, long di )   { return( -1 ); }
  long sys_mremap( long bx, long cx, long dx, long si, long di ) {
    int base         = (int)bx;
    int old_address  = arg( base, 0 );
    int old_size     = arg( base, 1 );
    int new_size     = arg( base, 2 );
    int flags        = arg( base, 3 );
    int new_adrs = mem.realloc( old_address, new_size );
    return( new_adrs );
  }



  int arg( int base, int no ) {
    int value = mem.load32( base + 4 + no*4 );
    if( sysinfo.verbose( )) {
      process.println( "   arg " + no + ": " + Util.hexstr( value, 8 ));
    }
    return( value );
  }
}
