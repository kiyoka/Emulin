// ----------------------------------------
//  Syscall ( Linux System call support )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
  // Phase 30: env-gated debug flag を起動時 1 回 cache。hot path で
  // System.getenv() を毎回呼ぶと HashMap lookup overhead で並列回帰
  // テストが timing flake する。
  private static final boolean TRACE_OPEN = System.getenv("EMULIN_TRACE_OPEN") != null;

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
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    return open_resolved( name, (int)cx );
  }

  // Phase 28-3i: 解決済 path で open する内部 helper。
  //   sys_open と amd64_openat (dirfd 解決) の両方から呼ばれる。
  long open_resolved( String name, int full_md ) {
    String mode = "r";
    int md = full_md & O_ACCMODE;
    int ret = 0;

    boolean trace_open = TRACE_OPEN;

    // issue #41 Phase 2: /dev/ptmx と /dev/pts/N の special handling。
    //   /dev/ptmx open ごとに新しい pty pair を作って master fd を返す。
    //   /dev/pts/N open は既存 pair から slave 側 fd を返す。
    if( "/dev/ptmx".equals( name ) ) {
      int pa = sysinfo.kernel.connect_pipe();
      int pb = sysinfo.kernel.connect_pipe();
      int master_fd = FileOpen( "<pty-master>", "rw", O_RDWR );
      if( master_fd < 0 ) return -1L;
      Fileinfo mf = (Fileinfo)flist.elementAt( master_fd );
      // master: read from pb (slave→master), write to pa (master→slave)
      mf.set_pipe_pair( pb, pa );
      mf.pty_master = true;
      mf.pty_ptn = sysinfo.kernel.pty.register( pa, pb );
      if( trace_open ) {
        System.err.println("DBG open: /dev/ptmx → master_fd="+master_fd
          +" ptn="+mf.pty_ptn+" pipe_a="+pa+" pipe_b="+pb);
      }
      return master_fd;
    }
    int ptn = PtyManager.parse_slave_path( name );
    if( ptn >= 0 ) {
      PtyManager.PtyPair pair = sysinfo.kernel.pty.get( ptn );
      if( pair == null ) return ENOENT;
      int slave_fd = FileOpen( "<pty-slave>", "rw", O_RDWR );
      if( slave_fd < 0 ) return -1L;
      Fileinfo sf = (Fileinfo)flist.elementAt( slave_fd );
      // slave: read from pa (master→slave), write to pb (slave→master)
      sf.set_pipe_pair( pair.pipe_a, pair.pipe_b );
      sf.pty_slave = true;
      sf.pty_ptn = ptn;
      if( trace_open ) {
        System.err.println("DBG open: "+name+" → slave_fd="+slave_fd
          +" pipe_a="+pair.pipe_a+" pipe_b="+pair.pipe_b);
      }
      return slave_fd;
    }

    // /proc/self/maps (及び /proc/<pid>/maps) は emu の実メモリ配置から動的生成。
    //   静的 sandbox file (cp 等の残骸) だと glibc が stack 境界を誤算し JSC が
    //   abort する (Bun/claude 起動失敗の根本原因)。Memory.genProcSelfMaps 参照。
    if( name.equals("/proc/self/maps") || name.equals("/proc/"+process.pid+"/maps") ) {
      int mfd = FileOpen( "<procmaps>", "r", O_RDONLY );
      if( mfd < 0 ) return -1L;
      Fileinfo mfi = (Fileinfo)flist.elementAt( mfd );
      mfi.memContent = mem.genProcSelfMaps().getBytes( java.nio.charset.StandardCharsets.UTF_8 );
      mfi.memPos = 0;
      if( (full_md & 0x80000) != 0 ) set_cloexec( mfd, true );  // O_CLOEXEC
      if( trace_open ) System.err.println("DBG open: /proc/self/maps → dynamic fd="+mfd+" ("+mfi.memContent.length+" bytes)");
      return mfd;
    }

    Inode inode = new Inode( name, sysinfo );
    if( trace_open ) {
      System.err.println("DBG open: name='"+name+"' md="+md+" full_md=0x"+Integer.toHexString(full_md)
        +" exists="+inode.isExists()
        +" readable="+inode.isReadable()+" st_mode=0o"+Integer.toOctalString(inode.st_mode & 0xFFFF));
    }
    // O_DIRECTORY (0x10000): path が directory でなければ ENOTDIR (-20)。
    //   Phase 29-D: emacs 29 の file-directory-p は
    //   `openat(path, O_PATH|O_DIRECTORY|O_CLOEXEC)` (= 0x290000) で directory
    //   判定する。flag を無視して fd を返すと file を directory と誤認、
    //   find-file が default-directory として "path/" に chdir 試行で失敗。
    final int O_DIRECTORY = 0x10000;
    if( (full_md & O_DIRECTORY) != 0 && inode.isExists() && !inode.isDirectory() ) {
      return -20;  // ENOTDIR
    }
    // /dev/null /dev/tty 等のデバイスは Inode 上は存在しないが is_exist_device で
    //   FileOpen 経由 (Fileinfo の null_flag/std) に処理させる。O_RDONLY の存在/
    //   可読チェックで早期 ENOENT すると、例えば OpenSSL が OPENSSL_CONF=/dev/null
    //   を fopen("r") して失敗し、host (open 成功) と挙動が分かれる。
    boolean isDevice = sysinfo.kernel.is_exist_device( name ) != null;
    if((md == O_RDONLY) && !inode.isExists( ) && !isDevice) { ret = ENOENT; }  // No such file or directory
    else {
      if( (md == O_RDONLY) && !inode.isReadable( ) && !isDevice) { ret = ENOENT; } // not readable → ENOENT 扱い
      else {
	if( md == O_RDONLY ) { mode = "r"; }
	if( md == O_WRONLY ) { mode = "rw"; }
	if( md == O_RDWR )   { mode = "rw"; }
	ret = FileOpen( name, mode, full_md );
	if( trace_open ) {
	  System.err.println("  open ret="+ret+" mode="+mode);
	}
	if( sysinfo.verbose( )) {
	  process.println( "  " + ret + " = SYS_OPEN( \"" + name + "\",\"" + mode + "\")" );
	}
	// Phase 27 step 25: FileOpen が -1 を返したら parent dir の有無で
	//   ENOENT (= -2) か EACCES (= -13) に振り分ける。git は ENOENT を
	//   見て mkdir + retry するが -1 (= -EPERM) では諦める。
	if( ret == -1 ) {
	  String native_name = sysinfo.get_native_path( name );
	  java.io.File parent = new java.io.File( native_name ).getParentFile();
	  if( parent == null || !parent.isDirectory() ) {
	    ret = ENOENT;
	  } else {
	    ret = -13;  // EACCES
	  }
	}
	// O_CLOEXEC (0x80000) を反映 (Phase 27 step 39 — per-fd 管理)
	if( ret >= 0 && (full_md & 0x80000) != 0 ) {
	  set_cloexec( ret, true );
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
    String name = mem.loadString( bx );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    return unlink_resolved( name );
  }
  // Phase 28-3j: 解決済 path 版。amd64_unlinkat と sys_unlink から共有。
  long unlink_resolved( String name ) {
    int ret = 0;
    // issue #126: 存在チェックは NOFOLLOW。旧 Inode.isExists() は File.exists()
    //   で symlink を follow し、target 不在の dangling symlink (emacs lock file
    //   ".#<name>" -> "user@host.pid") を ENOENT にして消せなかった。unlink()
    //   本体は既に nofollow で symlink 自身を消すので、入口のゲートだけ揃える。
    if( !exists_nofollow( name )) { ret = ENOENT; }
    else if( !unlink( name ))  { ret = EPERM; }
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
    return do_chmod( name, (int)cx & 07777 );
  }
  // chmod 本体 (sys_chmod / amd64_fchmodat 共用)。name は guest full path。
  long do_chmod( String name, int mode ) {
    // issue #80: glibc は set-file-modes 'nofollow で fchmodat2(452)→ENOSYS の
    //   後、open(O_PATH) した fd に対し chmod("/proc/self/fd/N") で fallback する。
    //   /proc/self/fd/N は実 file が無いので、fd N の実 path に解決して chmod。
    String pf = resolve_proc_self_fd( name );
    if( pf != null ) name = pf;
    // issue #41 Phase 2: /dev/pts/N と /dev/ptmx は virtual device で
    //   sandbox 実 file としては存在しない。sshd の pty_setowner は
    //   chmod(/dev/pts/N, 0600) を呼ぶので no-op success で返す。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      return 0;
    }
    String native_path = sysinfo.get_native_path( name );
    java.io.File f = new java.io.File( native_path );
    if( !f.exists( ) ) return( ENOENT );
    // issue #68 Phase 2: Cygwin mode では mode を xattr に保存して永続化
    //   (NTFS は POSIX 9-bit を保持しないため)。stat 側は Inode.get_st_mode
    //   が xattr から読み戻す。setuid/setgid/sticky 含む 12-bit を保存。
    if( CygMode.enabled() ) {
      CygMode.setMode( native_path, mode );
    }
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
  // /proc/self/fd/N (及び /proc/<pid>/fd/N で pid==自分) を fd N の実 guest
  //   path に解決する。該当しなければ null。issue #80 (glibc の chmod fallback)。
  String resolve_proc_self_fd( String name ) {
    String pfx = null;
    if( name.startsWith( "/proc/self/fd/" ) ) pfx = "/proc/self/fd/";
    else if( name.startsWith( "/proc/" + process.pid + "/fd/" ) ) pfx = "/proc/" + process.pid + "/fd/";
    if( pfx == null ) return null;
    try {
      int fd = Integer.parseInt( name.substring( pfx.length() ) );
      String n = get_name( fd );
      return ( n != null && n.length() > 0 && !n.startsWith( "<" ) ) ? n : null;
    } catch( NumberFormatException e ) { return null; }
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
  // pause(): 何らかのシグナル (handle されないものは exit を引き起こす) が到達
  //   するまで sleep。Linux 仕様では常に -EINTR を返す (handler 実行後に
  //   復帰)。Phase 27 step 23: 旧実装は Thread.sleep の無限ループで pending
  //   signal を全くチェックしていなかった (= alarm + pause が hang) ので、
  //   psig() != -1 になるまで polling する形に修正。
  long sys_pause( long bx, long cx, long dx, long si, long di )  {
    if( sysinfo.verbose( )) {
      process.println( " Info : process is pause" );
    }
    while( true ) {
      if( process.psig() != -1 ) return -4L;  // -EINTR
      try { Thread.sleep( 10L ); }
      catch( InterruptedException m ) { return -4L; }
    }
  }
  long sys_utime( long bx, long cx, long dx, long si, long di )  {    return( 0 );   }
  // access(path, mode): mode は F_OK(0)/R_OK(4)/W_OK(2)/X_OK(1) のビット和。
  // Phase 27 step 25: 旧実装は以下の 2 重バグで全 access call が壊れていた。
  //   1) `mode &= F_OK` (= mode &= 0) でローカル変数 mode を 0 上書き → 後続の
  //      R_OK / W_OK チェックが全部 dead code 化していた。`&` (非破壊) が正解
  //   2) 失敗時に -1 (= -EPERM) を返していた。Linux 規約では存在しないなら
  //      -ENOENT (-2)、permission 不足なら -EACCES (-13)。git は EPERM を
  //      "Operation not permitted" と解釈して fatal abort していた
  long sys_access( long bx, long cx, long dx, long si, long di ) {
    long name_p = bx;
    int mode = (int)cx;
    String name = mem.loadString( name_p );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    // issue #76: /dev/ptmx と /dev/pts/N は virtual pty device で実 fs に
    //   存在しない。emacs の allocate_pty は ptsname の後に
    //   faccessat2(slave, R_OK|W_OK) で slave が使えるか確認し、失敗すると
    //   pty を諦めて pipe fallback する (M-x shell が pipe 経由になり hang)。
    //   常に存在 + R/W 可として 0 を返し、emacs に pty を使わせる。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      return 0;
    }
    Inode inode = new Inode( name, sysinfo );
    if( sysinfo.verbose( )) {
      process.println( " sys_access : mode = " + Util.hexstr( mode, 8 ));
    }
    if( !inode.isExists( ))                                   return -2;  // ENOENT
    if( (mode & R_OK) != 0 && !inode.isReadable( ))           return -13; // EACCES
    if( (mode & W_OK) != 0 && !inode.isWritable( ))           return -13;
    // X_OK: file は execute、dir は search のパーミッション。
    //   旧実装は silent 成功にしていたが、emacs の file-directory-p は
    //   `access(path, F_OK|X_OK)` で「dir として search できるか」で
    //   directory 判定するため、regular file (mode 0644 = no +x) を
    //   directory と誤認する。st_mode の S_IXUSR/IXGRP/IXOTH を見て
    //   ちゃんと EACCES を返す (Phase 29-D)。
    if( (mode & X_OK) != 0 && !inode.isExecutable( ))         return -13;
    return 0;
  }
  long sys_sync( long bx, long cx, long dx, long si, long di )   {    return( 0 );   }
  long sys_kill( long bx, long cx, long dx, long si, long di )   {    return( 0 );   }
  long sys_rename( long bx, long cx, long dx, long si, long di ) {
    String name_from = mem.loadString( bx );
    String name_to   = mem.loadString( cx );
    name_from = sysinfo.get_full_path( process.get_curdir( ), name_from );
    name_to   = sysinfo.get_full_path( process.get_curdir( ), name_to   );
    return rename_resolved( name_from, name_to );
  }
  // Phase 28-3j: 解決済 path 版。amd64_renameat と sys_rename から共有。
  long rename_resolved( String name_from, String name_to ) {
    int ret = 0;
    Inode inode = new Inode( name_from, sysinfo );
    if( !inode.isExists( )) { ret = ENOENT; }
    else if( !rename( name_from, name_to )) { ret = EPERM; }
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
    } else {
      // issue #131 (tmux): 要求 mode を反映する。Java File.mkdir は host umask
      //   が効いて 0755 等になるが、tmux 等は mkdir(0700) で作って後の stat
      //   で S_IRWXO=0 を要求するので、明示的に chmod で要求値に揃える。
      //   sticky/setuid 等の 12-bit を保持するため mode & 07777。
      if( mode != 0 ) {
        do_chmod( name, mode & 07777 );
      }
    }
    return( ret );
  }
  long sys_rmdir( long bx, long cx, long dx, long si, long di ) {  return( sys_unlink( bx, cx, dx, si, di )); }
  long sys_dup( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    // issue #41 Phase 2: oldfd が無効 (closed / 範囲外) なら EBADF (-9) を返す。
    //   旧実装は flist.elementAt() の null を Dup → isPIPE() で NPE。
    //   sshd の rexec 経路で「dup2(6,4) → close(6) → close(4) → 後で dup(4)」
    //   のような closed fd への dup を確実に EBADF に変換する。
    if( fd < 0 || fd >= flist.size() || flist.elementAt( fd ) == null ) {
      return -9;  // -EBADF
    }
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
      // F_DUPFD_CLOEXEC は新 fd に FD_CLOEXEC を立てる (Phase 27 step 39)。
      // issue #41 Phase 2: oldfd が無効なら EBADF (sys_dup と同じ理由)。
      if( fd < 0 || fd >= flist.size() || flist.elementAt( fd ) == null ) {
        return -9;  // -EBADF
      }
      int newfd = arg;
      while( newfd < flist.size( ) && flist.elementAt( newfd ) != null ) newfd++;
      Dup( fd, newfd );
      // POSIX: F_DUPFD は new fd の cloexec をクリア、F_DUPFD_CLOEXEC はセット
      set_cloexec( newfd, (command == F_DUPFD_CLOEXEC) );
      return( newfd );
    }
    // F_GETFD/F_SETFD/F_GETFL/F_SETFL は存在する fd 上でのみ有効。無効な fd は
    //   -EBADF を返す (POSIX)。旧実装は無条件 0 (成功) を返していたため、
    //   node の「fcntl(fd, F_SETFD, CLOEXEC) を EBADF が出るまで fd++ する
    //   open-fd 列挙ループ」が永久ループ (fd を 700万まで回す) になっていた。
    boolean validFd = fd >= 0 && fd < flist.size() && flist.elementAt( fd ) != null;
    if( F_SETFD == command ) {	/* set FD_CLOEXEC bit (Phase 27 step 39) */
      if( !validFd ) return -9;  // -EBADF
      set_cloexec( fd, ((arg & 1) != 0) );  // FD_CLOEXEC = 1
      return( 0 );
    }
    if( F_GETFD == command ) {	/* get FD_CLOEXEC bit */
      if( !validFd ) return -9;  // -EBADF
      return( is_cloexec( fd )? 1L : 0L );
    }
    if( F_GETFL == command ) {	/* more flags (cloexec) */
      if( !validFd ) return -9;  // -EBADF
      return( GetModeBit( fd ));
    }
    if( F_SETFL == command ) {	/* set f_flags */
      if( !validFd ) return -9;  // -EBADF
      // O_NONBLOCK のみ Fileinfo に追跡する (read で EAGAIN を返すため)。
      Fileinfo finfo = get_finfo( fd );
      if( finfo != null ) {
        finfo.nonBlock = ((arg & O_NONBLOCK) != 0);
      }
      return( 0 );
    }
    return( 0 );
  }
  long sys_setpgid( long bx, long cx, long dx, long si, long di ) {
    // issue #102: setpgid(pid=bx, pgid=cx)。bash の job-control 初期化は
    //   setpgid(0,0) で自分を process group leader (pgrp=自 pid) にし、続いて
    //   tcsetpgrp で端末 foreground pgrp を奪い、getpgrp()==tcgetpgrp() を検証
    //   する。no-op だと getpgrp が旧値を返して不一致 →「no job control」で
    //   諦める。自プロセス (bx==0 または自 pid) の pgrp を追跡する。
    if( bx == 0 || (int)bx == process.pid ) {
      process.pgrp = ( cx == 0 ) ? process.pid : (int)cx;
    }
    return( 0 );
  }
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
    // issue #41 Phase 2: oldfd が無効なら EBADF (POSIX 仕様、Dup の NPE 回避)。
    if( oldfd < 0 || oldfd >= flist.size() || flist.elementAt( oldfd ) == null ) {
      return -9;  // -EBADF
    }
    if( oldfd == newfd ) return( newfd );
    if( newfd >= 0 && newfd < flist.size( ) && flist.elementAt( newfd ) != null ) {
      FileClose( newfd );
    }
    Dup( oldfd, newfd );
    // POSIX: dup2 は new fd の FD_CLOEXEC を **クリア** (Phase 27 step 39)。
    //   per-fd 管理なので oldfd の cloexec は触らない。
    set_cloexec( newfd, false );
    return( newfd );
  }
  long sys_getppid( long bx, long cx, long dx, long si, long di ) {    return( 8 );  }
  long sys_getpgrp( long bx, long cx, long dx, long si, long di ) {
    // issue #102: setpgid で設定済の pgrp、未設定なら自 pid (自分が pgrp leader)。
    //   getpgid(pid) (amd64 #121) もこれに dispatch される (自プロセス前提)。
    return( process.pgrp >= 0 ? process.pgrp : process.pid );
  }
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
    long length = cx;
    // mem.free は exact-match (address=allocation 先頭 かつ size 完全一致) の
    //   ときだけ 0、それ以外 -1。だが Linux の munmap は valid な mapped range
    //   なら partial / trim でも 0 成功。V8 は snapshot 展開で大領域を mmap →
    //   trim munmap するため size 不一致で -1 になり、CHECK(0==munmap) で fatal。
    //   best-effort で free し、munmap としては常に成功 (0) を返す。
    mem.free( address, (int)length );
    return 0;
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
  // issue #109: uname の machine フィールド。amd64 は SyscallAmd64 が "x86_64" を
  //   override で返す。base (i386) は "i386"。
  protected String unameMachine( ) { return "i386"; }
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
      // issue #109: sysname は "Linux" を返す。CPython の sysconfig.get_platform()
      //   は uname sysname が "linux" 始まりのときだけ "linux-<machine>" を返し、
      //   それ以外は "<sysname>-<release>-<machine>" になる。後者だと pip の
      //   packaging.tags が linux_x86_64 / manylinux_*_x86_64 tag を生成できず、
      //   x86_64 binary wheel が "from versions: none" で弾かれていた。
      //   Emulin の identifier は version フィールドに載せて `uname -a` で見える
      //   ようにする。machine は arch 別 (amd64="x86_64" / i386="i386")。
      mem.storeString( address + SYS_NMLN * 0 ,  "Linux" );
      mem.storeString( address + SYS_NMLN * 1 ,  s );
      mem.storeString( address + SYS_NMLN * 2 ,  Version.get_version( ));
      mem.storeString( address + SYS_NMLN * 3 ,  "Emulin " + Version.get_version( ));
      mem.storeString( address + SYS_NMLN * 4 ,  unameMachine( ));
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
