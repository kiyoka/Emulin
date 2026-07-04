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
  // issue #413/#416: claude(Bun standalone も npm/node 版も) は自分の stdin(fd0) を読まず
  //   /dev/ptmx master を開いて TTY stdin にし、その master を epoll/read して入力を待つ
  //   (libuv/Bun の TTY 機構)。JLine console の打鍵を master の read pipe へ橋渡しすると
  //   届く。node 単一プロセスの最小 stdin テストで打鍵 a/b/c/q が process.stdin に到達する
  //   ことを実証済。EMULIN_PTY_CONSOLE_BRIDGE=1 で有効化 (既定 OFF: sshd/tmux 等が子 pty 用に
  //   /dev/ptmx を開く経路で console を誤って横取りしないよう、対話 TUI 起動時のみ明示有効化)。
  private static final boolean PTY_CONSOLE_BRIDGE = System.getenv("EMULIN_PTY_CONSOLE_BRIDGE") != null;
  private static volatile boolean ptyBridgeStarted = false;

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
  // issue #427: termios2 版 (44 byte struct = termios 36 byte + c_ispeed/c_ospeed 各 4 byte)。
  //   OpenAI Codex (Rust) の raw-mode 設定が TCGETS2/TCSETS2 を使う。
  static int TCGETS2	= 0x802c542a;
  static int TCSETS2	= 0x402c542b;
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
  // issue #221 step 3d-2a: syscall 層が触る guest memory を `MemoryBackend`
  //   interface 型にする (旧 concrete `Memory`)。software では `Memory`
  //   (IS-A MemoryBackend) がそのまま入るので behavior 不変、native backend
  //   (#221) では `NativeMemoryBackend` (= KVM guest 物理 RAM) を connect_mem で
  //   差し込める。syscall 層 (Syscall/SyscallAmd64/SyscallI386/FileAccess) は
  //   mem を load/store/bulk/alloc_and_map/get_curbrk 等 MemoryBackend interface
  //   メソッド経由でしか触らない (execLock #113 GIL 等 Memory 固有 member は
  //   CPU 側 AbstractCpu.mem だけが使い、そちらは Memory 型のまま据置)。
  MemoryBackend mem;

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

  // メモリシステムを接続する (issue #221 step 3d-2a: MemoryBackend 型。
  //   software は Memory、native は NativeMemoryBackend を渡せる)
  void connect_mem( MemoryBackend _mem ) {
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

  // issue #219: このプロセスの制御端末となっている pty slave の fd 番号を返す。
  //   sshd セッションの子は fd 0/1/2 のいずれかが pty slave (sshd が pty を
  //   割当て dup2 したもの) なので、それを「制御端末」とみなす。見つからなければ
  //   -1 (= 制御端末は launcher console)。/dev/tty open の解決に使う。
  int controlling_pty_fd( ) {
    for( int fd = 0; fd <= 2; fd++ ) {
      if( fd < flist.size( ) ) {
        Fileinfo fi = (Fileinfo)flist.elementAt( fd );
        if( fi != null && fi.pty_slave && fi.pty_ptn >= 0 ) return fd;
      }
    }
    return -1;
  }

  // Phase 28-3i: 解決済 path で open する内部 helper。
  //   sys_open と amd64_openat (dirfd 解決) の両方から呼ばれる。
  long open_resolved( String name, int full_md ) {
    String mode = "r";
    int md = full_md & O_ACCMODE;
    int ret = 0;

    boolean trace_open = TRACE_OPEN;

    // issue #191: O_TMPFILE (0x410000 = __O_TMPFILE|O_DIRECTORY) — directory 内に
    //   「名前の無い一時 inode」を作って開く Linux 拡張。apt が download/atomic-write
    //   の scratch に使う。emulin は file を仮想パスで識別するので真の匿名 inode と
    //   相性が悪い (unlink した瞬間 path ベースの fstat が ENOENT で壊れ、apt が
    //   "Unable to determine file size for fd" で fail する)。
    //   apt は O_TMPFILE 非対応 fs (NFS/古い tmpfs/overlayfs 等) を想定して、open が
    //   どんな errno で失敗しても mkstemp 名前付き temp + rename へ無条件 fallback
    //   する (apt-pkg/contrib/fileutl.cc)。その portable 経路は emulin が完全対応
    //   する normal open/write/fstat/rename だけを使うので、ここでは O_TMPFILE を
    //   非対応として EOPNOTSUPP を返し fallback させるのが最も堅牢。
    //   旧実装は O_DIRECTORY 部分だけ見て dir をそのまま開き f==null → write NPE
    //   していた。必ず O_DIRECTORY 処理より前で intercept する。
    final int O_TMPFILE_FLAG = 0x410000;
    if( (full_md & O_TMPFILE_FLAG) == O_TMPFILE_FLAG ) {
      if( trace_open ) System.err.println("DBG open: O_TMPFILE in '"+name+"' → EOPNOTSUPP (apt mkstemp fallback)");
      return EOPNOTSUPP;
    }

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
      // issue #413/#416: console→pty-master bridge。claude(Bun standalone / npm-node 版とも)
      //   は fd0 を読まず /dev/ptmx master(read=pb) を epoll/read して入力を待つ。JLine console
      //   入力を pb へ流し込むと master read/epoll で打鍵が届く。最初の master 1 本だけ橋渡し。
      if( PTY_CONSOLE_BRIDGE && sysinfo.is_console_jline() && !ptyBridgeStarted ) {
        ptyBridgeStarted = true;
        sysinfo.kernel.console.setBridgeMode( true );  // main thread を reader 非接触にし競合を防ぐ
        final int bridgePipe = pb;
        Thread bt = new Thread(() -> {
          byte[] buf = new byte[256];
          try {
            while( true ) {
              int n = sysinfo.kernel.console.bridgeRead( buf );  // ESC 列 coalesce 付き blocking read
              if( n <= 0 ) break;
              // issue #413: 端末 capability query への unsolicited 応答 (DA: ESC[?…c / DA2: ESC[>…c) は
              //   master へ流さない。これらが claude の capability 検出に partial に届くと「残りの応答待ち」で
              //   onboarding が hang し theme が描画されない (bridge 無しでは応答ゼロ→timeout→描画される)。
              //   report を drop して claude を no-bridge と同じ timeout 経路に乗せ、打鍵 (arrows ESC[A-D /
              //   SS3 ESC O x / 印字/制御) だけ届ける。ESC[?…/ESC[>… で始まる列のみ drop (打鍵は該当しない)。
              if( n >= 3 && buf[0] == 0x1b && buf[1] == 0x5b && (buf[2] == 0x3f || buf[2] == 0x3e) )
                continue;  // ESC[? (DA) / ESC[> (DA2) report → drop
              // issue #413: SS3 矢印 (ESC O A/B/C/D = application cursor key mode) を CSI (ESC[A/B/C/D) に
              //   変換。WT が DECCKM 状態 (前回 claude 起動の残留等) で矢印を SS3 で送るが、capability 検出を
              //   timeout した claude(Ink) は CSI 矢印を期待して SS3 を解釈しないため選択が動かない。A-D のみ。
              if( n == 3 && buf[0] == 0x1b && buf[1] == 0x4f && buf[2] >= 0x41 && buf[2] <= 0x44 )
                buf[1] = 0x5b;  // ESC O X → ESC [ X
              byte[] out = new byte[n];
              System.arraycopy( buf, 0, out, 0, n );
              sysinfo.kernel.pipe_write( bridgePipe, out );      // master の read pipe(pb) へ
            }
          } catch( Throwable e ) { /* console 終了等は無視 */ }
        }, "pty-console-bridge");
        bt.setDaemon( true );
        bt.start();
        if( trace_open ) System.err.println("DBG pty-console-bridge started → pb="+pb);
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

    // issue #219: /dev/tty (制御端末) の解決。sshd セッションの子プロセスは
    //   fd 0/1/2 が pty slave なので、/dev/tty はその pty slave に解決すべき。
    //   さもないと emacs 等が /dev/tty を open して描画する出力が launcher の
    //   console (= サーバ側) に流れてしまう (bash は fd 0/1/2 を直接使うので
    //   client に出るが、emacs は制御端末 /dev/tty を開くためサーバ側に表示
    //   される不具合)。制御 pty が無い直接起動 (fd 0/1/2 が console) では
    //   従来どおり下の is_exist_device("/dev/tty") = <std> に fall through。
    if( "/dev/tty".equals( name ) ) {
      int ctty_fd = controlling_pty_fd( );
      if( ctty_fd >= 0 ) {
        // 制御端末 (fd 0/1/2 の pty slave) と「同一 Fileinfo」を共有する fd を
        //   返す (実機 Linux で /dev/tty と pts fd が同一 tty を指すのと同じ)。
        //   オブジェクト共有により termios/pipe/状態が fd 0 と完全一致し、emacs
        //   の対話入力 (制御端末 /dev/tty への pselect→read) が fd 0 と整合して
        //   動く (独立 Fileinfo では描画は出るが入力が届かないことを実 emacs で
        //   確認済み)。★この fd を tty_alias に印し close を no-op 化する
        //   (FileClose 参照)。接続計数を一切触らないので、/dev/tty の開閉で
        //   共有 pty pipe を切らず、fd 0/1/2 が EOF で落ちる事故 (session 断) を
        //   防ぐ。pipe lifecycle は本物の制御端末 fd 0/1/2 が所有する。
        //   なお emacs は /dev/tty を O_RDWR で open 後 fcntl(F_GETFL) で書込可を
        //   確認するが、pty fd は F_GETFL を O_RDWR に補正して返す (sys_fcntl 参照)
        //   ので "Not a tty device" にならない。
        int new_fd;
        synchronized( fdLock ) {   // ★ alias fd 確保を atomic に (3d-2c-42)
          new_fd = search_empty_fd( );
          while( new_fd >= flist.size( ) ) flist.addElement( (Object)null );
          flist.setElementAt( flist.elementAt( ctty_fd ), new_fd );
          set_tty_alias( new_fd, true );
        }
        if( trace_open ) {
          System.err.println("DBG open: /dev/tty → ctty alias(fd "+ctty_fd+") → "+new_fd);
        }
        return new_fd;
      }
      // 制御 pty 無し → <std> (launcher console) に fall through (従来挙動)
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

    // issue #349: /proc/self/fd/N (fd_reopen) — fd N の実 path を開き直す。systemd は
    //   fd_reopen(fd) = open("/proc/self/fd/N", flags) を多用する (O_PATH で開いた dir を
    //   実 fd に開き直す等)。未対応だと open が ENOENT になり systemd-tmpfiles が
    //   "Failed to reopen '/var/log/journal': Bad file descriptor" で失敗していた。
    //   実 path に解決して同じ flags で開き直す (実 path に /proc/self/fd は無いので無限再帰しない)。
    {
      String reopenPath = resolve_proc_self_fd( name );
      if( reopenPath != null ) {
        if( trace_open ) System.err.println("DBG open: "+name+" → reopen '"+reopenPath+"'");
        return open_resolved( reopenPath, full_md );
      }
    }

    // issue #349: O_PATH (0x200000、O_DIRECTORY 無し) — path への参照だけを開く。
    //   内容 access せず、最終 component の symlink も follow しない (symlink 自身を
    //   指す path-handle)。systemd-tmpfiles が作ったばかりの symlink を
    //   open(O_PATH|O_NOFOLLOW) して fchown/relabel する経路で必要。RAF で開くと
    //   symlink target を follow し、target 不在 (/run/shm → /dev/shm 等) で ENOENT に
    //   なり "Failed to open symlink we just created" で systemd-tmpfiles が失敗していた。
    //   O_PATH|O_DIRECTORY (emacs file-directory-p) は従来の O_DIRECTORY 経路に任せる。
    //   ★intercept は「最終 component が symlink」のときだけに限定する。dir/file/
    //   /proc 等の O_PATH は従来の open 経路 (FileOpen/opendir) に任せる (これらを
    //   path-handle 化すると *at 系の dirfd 解決や getcwd で native↔virtual 変換が
    //   壊れる)。symlink 判定は get_native_path_nofollow (virtual→native) のみで
    //   get_virtual_path を呼ばないので /proc でも安全。
    final int O_PATH_FLAG  = 0x200000;
    final int O_DIRECTORY2 = 0x10000;
    if( (full_md & O_PATH_FLAG) != 0 && (full_md & O_DIRECTORY2) == 0 ) {
      boolean isLink = false;
      String np = null;
      try {
        np = sysinfo.get_native_path_nofollow( name );
        isLink = java.nio.file.Files.isSymbolicLink( java.nio.file.Paths.get( np ) )
              || ( CygSymlink.enabled( ) && CygSymlink.read( np ) != null );
      } catch( RuntimeException e ) { isLink = false; }
      if( isLink ) {
        Fileinfo pf = new Fileinfo( );
        // get_name は name を native として virtual に逆変換するので、通常 file と同様に
        //   native path を格納する (virtual を入れると get_virtual_path が abort する)。
        pf.opendir( np );     // native path + opened=1 (RAF 無し)
        pf.o_path = true;
        int pfd = place_fd( pf );
        if( pfd >= 0 && (full_md & 0x80000) != 0 ) set_cloexec( pfd, true );  // O_CLOEXEC
        if( trace_open ) System.err.println("DBG open: O_PATH symlink '"+name+"' → path-handle fd="+pfd);
        return pfd;
      }
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
    // issue: 無効/未オープン fd の close は EBADF (旧実装は FileClose が範囲外を
    //   成功扱いにするため close(999) 等が 0 を返していた)。
    if( fd < 0 || fd >= flist.size() || get_finfo( fd ) == null ) return -9;  // EBADF
    if( FileClose( fd )) return 0;
    return -9;
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
    else if( !unlink( name ))  {
      // issue #191 (dpkg): rmdir/unlink が失敗した対象が「空でない
      // ディレクトリ」なら ENOTEMPTY を返す。dpkg の path_remove_tree は
      // rmdir(非空dir) の errno が ENOTEMPTY のときだけ `rm -rf` を fork して
      // tree を消す。旧実装は一律 EPERM だったため dpkg-deb が
      // "unable to securely remove '/tmp/dpkg-deb.XXX'" で中断し、結果
      // dpkg -i が package を unpack せず silently 失敗していた。
      ret = is_nonempty_dir( name ) ? ENOTEMPTY : EPERM;
    }
    if( sysinfo.verbose( )) {
      process.println( "   " + ret + " = unlink( '" + name + "' ); " );
    }
    return( ret );
  }

  // issue #191: vpath が「空でないディレクトリ」かを native fs で判定する。
  //   unlink_resolved が rmdir(非空) を ENOTEMPTY に分類するための補助。
  private boolean is_nonempty_dir( String name ) {
    try {
      java.io.File f = new java.io.File( sysinfo.get_native_path( name ) );
      if( !f.isDirectory( ) ) return false;
      String[] kids = f.list( );
      return kids != null && kids.length > 0;
    } catch( Exception e ) {
      return false;
    }
  }

  // issue #191: rmdir(2) は directory 専用。対象が directory でなければ ENOTDIR
  //   を返す。旧実装は sys_rmdir = sys_unlink alias だったため、regular file に
  //   rmdir すると unlink が走り「成功 (0)」を返して file を消していた。dpkg は
  //   pkg_infodb_update で `rmdir(control)==0` を「その control が directory だ」
  //   の判定に使うため、これだと regular な control file を directory と誤認して
  //   "package metadata contained directory" で archive 処理を中断し、かつ
  //   control file 自体を消してしまっていた (stat/lstat/fstat は全て S_IFREG を
  //   正しく返していたのに失敗していた真因)。non-dir → ENOTDIR を返した上で、
  //   空 dir の削除 / 非空 dir の ENOTEMPTY は unlink_resolved に委譲する。
  long rmdir_resolved( String name ) {
    if( !exists_nofollow( name ) ) return ENOENT;
    try {
      java.nio.file.Path p = java.nio.file.Paths.get( sysinfo.get_native_path_nofollow( name ) );
      if( java.nio.file.Files.isSymbolicLink( p )
          || !java.nio.file.Files.isDirectory( p, java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) {
        return ENOTDIR;
      }
    } catch( Exception e ) {
      return ENOTDIR;
    }
    return unlink_resolved( name );
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
    if( !inode.isDirectory( )) return ENOTDIR;   // 非ディレクトリへの chdir は ENOTDIR
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
    // issue (errno cluster): PosixFilePermission は 9 bit のみで suid/sgid/sticky
    //   (07000) が落ちる。unix view の "mode" 属性 (実体は chmod(2)) なら 12 bit
    //   全て設定できる。非対応 host (Windows) は従来経路へ fallback。
    try {
      java.nio.file.Files.setAttribute( f.toPath( ), "unix:mode", mode & 07777 );
      return( 0 );
    } catch( Exception e ) { /* fallback */ }
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
    return( FileSeek( fd, offset, whence ));   // issue #336: off_t を切り詰めない
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
    // issue #322: 空 path は ENOENT (real Linux: access("",...) / faccessat 無 AT_EMPTY_PATH)。
    //   旧実装は get_full_path("") が curdir (= 実行可能な dir) に化けて access("",X_OK)
    //   を成功させ、dpkg postinst の `[ -x "$(command -v <tool>)" ]` ガード (tool 未導入で
    //   `command -v` が空文字) が誤って真になり、未導入 tool を呼んで exit 127 で configure
    //   が失敗していた (dh_installmenu の update-menus 等、多数の package に影響)。
    if( name == null || name.isEmpty() ) return -2;  // ENOENT
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    return access_resolved( name, mode );
  }
  // 解決済み full path 版 (amd64_faccessat から共有、issue: dirfd 対応)。
  long access_resolved( String name, int mode ) {
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
    // issue #322: rename は symlink 自身を移動する → 存在チェックも NOFOLLOW。
    //   Inode.isExists() は symlink を follow するため、broken な絶対 symlink
    //   (dpkg の <file>.dpkg-new -> 未導入 package の絶対 path) を「不在」と
    //   誤判定し rename を ENOENT にしていた。
    if( !exists_nofollow( name_from )) { ret = ENOENT; }
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
    // issue(npm): 失敗を一律 EPERM(-1) でなく errno (ENOENT=親不在/EACCES 等) で返す。
    //   node の再帰 mkdir(mkdirp) は ENOENT を見て親を作るので、-1 だと親を作れず npm cache 等が失敗していた。
    int rc = mkdirErrno( name );
    if( rc != 0 ) return rc;
    // issue #131 (tmux): 要求 mode を反映する。Java File.mkdir は host umask が効いて 0755 等になるが、
    //   tmux 等は mkdir(0700) で作って後の stat で S_IRWXO=0 を要求するので明示 chmod で揃える (mode & 07777)。
    if( mode != 0 ) do_chmod( name, (mode & 07777) & ~process.umask );  // issue #450: 作成 mode に umask 適用
    return 0;
  }
  long sys_rmdir( long bx, long cx, long dx, long si, long di ) {
    String name = mem.loadString( bx );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    return rmdir_resolved( name );
  }
  long sys_dup( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    // issue #41 Phase 2: oldfd が無効 (closed / 範囲外) なら EBADF (-9) を返す。
    //   旧実装は flist.elementAt() の null を Dup → isPIPE() で NPE。
    //   sshd の rexec 経路で「dup2(6,4) → close(6) → close(4) → 後で dup(4)」
    //   のような closed fd への dup を確実に EBADF に変換する。
    if( fd < 0 || fd >= flist.size() || flist.elementAt( fd ) == null ) {
      return -9;  // -EBADF
    }
    synchronized( fdLock ) {   // ★ 空き fd 確保 + Dup を 1 critical section に (3d-2c-42)
      int new_fd = search_empty_fd( );
      Dup( fd, new_fd );
      return( new_fd );
    }
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
  long sys_times( long bx, long cx, long dx, long si, long di )   {
    // struct tms { utime, stime, cutime, cstime } を埋める (CPU 時間は未追跡=0)。
    //   戻り値は起点不明の clock tick 数 (エラー -1 ではない値)。
    if( bx != 0 ) {
      mem.store64( bx,      0 );
      mem.store64( bx +  8, 0 );
      mem.store64( bx + 16, 0 );
      mem.store64( bx + 24, 0 );
    }
    return( System.currentTimeMillis() / 10 );   // 100Hz 相当の単調増加 tick
  }
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
      // issue #225: pty fd は pty 単位に保持した winsize を返す。それ以外は
      //   25x80 既定 (i386 は launcher console size 非対応の従来挙動を踏襲)。
      int rows = 25, cols = 80, xpix = 0, ypix = 0;
      if( finfo != null && finfo.pty_ptn >= 0 ) {
        int[] ws = sysinfo.kernel.pty.get_winsize( finfo.pty_ptn );
        if( ws[0] > 0 ) rows = ws[0];
        if( ws[1] > 0 ) cols = ws[1];
        xpix = ws[2]; ypix = ws[3];
      }
      mem.store16( address , (short)rows );  address += 2;
      mem.store16( address , (short)cols );  address += 2;
      mem.store16( address , (short)xpix );  address += 2;
      mem.store16( address , (short)ypix );  address += 2;
      done = true;
    }
    if( TIOCSWINSZ == request ) {
      // issue #225: pty fd への TIOCSWINSZ は winsize を保持し、その pty の
      //   foreground process group へ SIGWINCH を配信する (amd64 と同経路。
      //   SSH/emacs のリサイズ追従)。pty でない tty は従来どおり読み捨て success。
      if( finfo != null && finfo.pty_ptn >= 0 ) {
        int rows = mem.load16( address )     & 0xffff;
        int cols = mem.load16( address + 2 ) & 0xffff;
        int xpix = mem.load16( address + 4 ) & 0xffff;
        int ypix = mem.load16( address + 6 ) & 0xffff;
        sysinfo.kernel.pty.set_winsize( finfo.pty_ptn, rows, cols, xpix, ypix );
        int fg = sysinfo.kernel.pty.get_fg_pgrp( finfo.pty_ptn );
        if( fg > 0 ) sysinfo.kernel.kill( -fg, Signal.SIGWINCH );
        else         sysinfo.kernel.kill( -1, Signal.SIGWINCH );
      }
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
      // issue #440: arg が負 / RLIMIT_NOFILE (1024) 以上なら EINVAL (POSIX)。
      //   旧実装は arg=-1 で newfd=-1 → elementAt(-1) 例外、巨大 arg では下の
      //   Dup(fd, newfd) が巨大 fd まで Vector を確保しようとして無限ループ/OOM
      //   になっていた (guest から踏める hang)。dx を long で範囲チェックして塞ぐ。
      if( dx < 0 || dx >= 1024 ) {
        return -22;  // -EINVAL
      }
      synchronized( fdLock ) {   // ★ arg 以上の空き fd 探索 + Dup + cloexec を atomic に (3d-2c-42)
        int newfd = arg;
        while( newfd < flist.size( ) && flist.elementAt( newfd ) != null ) newfd++;
        Dup( fd, newfd );
        // POSIX: F_DUPFD は new fd の cloexec をクリア、F_DUPFD_CLOEXEC はセット
        set_cloexec( newfd, (command == F_DUPFD_CLOEXEC) );
        return( newfd );
      }
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
      int mb = GetModeBit( fd );
      // issue #219: pty (master/slave) は常に読み書き可能なデバイス。pty slave
      //   は fork/dup2 を経て fd 0/1/2 になる過程で mode_bit が O_RDONLY(0) に
      //   なり得るが、emacs は制御端末 /dev/tty を O_RDWR で open 後に
      //   fcntl(F_GETFL) で書込可を確認するため、O_RDONLY が返ると
      //   "Not a tty device: /dev/tty" で起動失敗する。pty fd はアクセスモードを
      //   O_RDWR に補正して返す (他フラグ O_NONBLOCK 等は保持)。
      Fileinfo ff = get_finfo( fd );
      if( ff != null && (ff.pty_slave || ff.pty_master) ) {
        mb = (mb & ~O_ACCMODE) | O_RDWR;
      }
      if( ff != null && ff.async ) mb |= O_ASYNC;  // issue #219
      // issue #443: 現在の file status flags (O_NONBLOCK/O_APPEND) を反映 (F_SETFL 変更含む)。
      if( ff != null ) {
        mb &= ~(O_NONBLOCK | O_APPEND);
        if( ff.nonBlock )   mb |= O_NONBLOCK;
        if( ff.appendMode ) mb |= O_APPEND;
      }
      return( mb );
    }
    if( F_SETFL == command ) {	/* set f_flags */
      if( !validFd ) return -9;  // -EBADF
      // O_NONBLOCK のみ Fileinfo に追跡する (read で EAGAIN を返すため)。
      Fileinfo finfo = get_finfo( fd );
      if( finfo != null ) {
        finfo.nonBlock = ((arg & O_NONBLOCK) != 0);
        finfo.appendMode = ((arg & O_APPEND) != 0);  // issue #443: O_APPEND を追跡 (write で末尾追記)
        // issue #219: O_ASYNC (非同期 SIGIO 入力)。emacs 等が端末 fd に立てる。
        //   pipe/pty の read 端なら、その pipe に SIGIO 送り先 (async_owner) を
        //   登録/解除する → 入力到着 (pipe_write) 時に owner へ SIGIO を配信。
        finfo.async = ((arg & O_ASYNC) != 0);
        if( finfo.is_pipe( true ) )
          sysinfo.kernel.set_async_owner( finfo.pipe_no, finfo.async ? finfo.async_owner : -1 );
      }
      return( 0 );
    }
    // issue #219: F_SETOWN/F_GETOWN — SIGIO の送り先 pid。emacs は F_SETOWN で
    //   自分を owner にしてから O_ASYNC を立てる。
    if( F_SETOWN == command ) {
      if( !validFd ) return -9;  // -EBADF
      Fileinfo finfo = get_finfo( fd );
      if( finfo != null ) {
        finfo.async_owner = arg;
        if( finfo.async && finfo.is_pipe( true ) )
          sysinfo.kernel.set_async_owner( finfo.pipe_no, arg );
      }
      return( 0 );
    }
    if( F_GETOWN == command ) {
      if( !validFd ) return -9;  // -EBADF
      Fileinfo finfo = get_finfo( fd );
      return( finfo != null ? finfo.async_owner : 0 );
    }
    // issue #427: F_GETLK は「競合する他者のロックが無ければ flock.l_type を F_UNLCK に
    //   書き換える」のが POSIX 仕様。emulin は単一プロセスで record lock を no-op
    //   (F_SETLK/F_SETLKW は常に成功) としているので競合は常に無い。旧実装は flock を
    //   書き換えず 0 を返すだけで、l_type が入力 (F_WRLCK 等) のまま残り、SQLite (WAL の
    //   ロック確認経路) が「競合あり」と誤認して SQLITE_PROTOCOL でリトライ→state DB の
    //   migration 失敗 (OpenAI Codex の state_*.sqlite)。F_UNLCK を書いて「競合なし」を返す。
    //   struct flock の l_type は offset 0 の 2 byte (i386/amd64 共通)、F_UNLCK=2。
    if( F_GETLK == command ) {
      if( !validFd ) return -9;  // -EBADF
      // 注意: 上の arg は (int)dx で 32bit 切り詰め済 (amd64 の 64bit flock ポインタが
      //   符号拡張で壊れ unmapped vaddr クラッシュになる)。書き込み先は full 64bit の dx。
      mem.store8( dx,     (byte)2 );  // l_type = F_UNLCK (little-endian short の下位)
      mem.store8( dx + 1, (byte)0 );  //                  (上位)
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
    // Linux: length=0 / 非ページ整列アドレスは EINVAL。
    if( length == 0 ) return EINVAL;
    if( (address & 0xFFFL) != 0 ) return EINVAL;
    // mem.free は exact-match (address=allocation 先頭 かつ size 完全一致) の
    //   ときだけ 0、それ以外 -1。だが Linux の munmap は valid な mapped range
    //   なら partial / trim でも 0 成功。V8 は snapshot 展開で大領域を mmap →
    //   trim munmap するため size 不一致で -1 になり、CHECK(0==munmap) で fatal。
    //   best-effort で free し、munmap としては常に成功 (0) を返す。
    mem.free( address, length );   // issue #392 review #1: long で渡す (≥2GB munmap の int 切り詰め防止)
    mem.unregisterFileBacked( address, length );   // issue #403: file-backed 記録を除去 (同 VA を anon 再利用時に DONTNEED zero 化を skip しないため = #113 回帰防止)
    return 0;
  }
  long sys_ftruncate( long bx, long cx, long dx, long si, long di )  {
    int fd = (int)bx;
    // 無効 fd は EBADF (get_name は範囲外に "<noname>" を返すため先に検証する)。
    if( fd < 0 || fd >= flist.size() || get_finfo( fd ) == null ) return( EBADF );
    if( (long)cx < 0 ) return( EINVAL );                 // 負の length は EINVAL
    if( (get_finfo( fd ).get_mode_bit() & 3) == 0 ) return( EINVAL );  // O_RDONLY は書込不可 → EINVAL
    long length = (long)cx;
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
  // issue #191: fchmod(fd, mode) — 従来は no-op (return 0) で mode を捨てていた。
  //   dpkg は data.tar の実行ファイルを open(O_CREAT,000) で作ってから
  //   fchmod(fd, 0755) で実行権を付ける (tar member の mode 反映)。no-op だと
  //   展開された binary が 0644 のまま (実行権欠落) になっていた。fd を実 path に
  //   解決して do_chmod に流す (sys_chmod と同じ実装、fstat と同じ resolve)。
  long sys_fchmod( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    int mode = (int)cx & 07777;
    if( get_finfo( fd ) == null ) return EBADF;
    String name = get_name( fd );
    if( name == null || "<noname>".equals( name ) ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    // 実ファイルなら mode を適用。std / pipe / socket 等の特殊 fd (sandbox に
    //   実 file が無い) への fchmod は no-op success にする (real Linux も
    //   pipe/tty への fchmod は成功扱い)。旧 no-op 実装との後方互換でもある。
    if( !new java.io.File( sysinfo.get_native_path( name ) ).exists( ) ) return 0;
    return do_chmod( name, mode );
  }
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
    // Linux waitid(2) flags は bitmask (WNOHANG=1, WUNTRACED=2 等)。
    // issue #131: 旧実装は `options == WNOHANG` の完全一致比較で WNOHANG|WUNTRACED (=3)
    //   を blocking 経路と誤認していた。bitwise AND に変更。
    int WNOHANG = 1;
    int pid          = (int)bx;
    int status_p     = (int)cx;
    int options      = (int)dx;
    int rusage_p     = (int)si;
    boolean nohang   = (options & WNOHANG) != 0;
    int ret_pid = 0;
    if( pid == -1 ) {
      while( true ) {
	if( sysinfo.verbose( )) {
	  process.println( "wait4 : waiting exit childs for pid = " + process.pid  );
	}
	ret_pid = sysinfo.kernel.is_child_exited( process.pid );
	if( nohang ) {
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
      mem.storeString( address + SYS_NMLN * 5 ,  "(none)" );  // issue #445: domainname (未設定だと NUL 終端なしのゴミ)
    }
    return( ret );
  }
  long sys_mprotect( long bx, long cx, long dx, long si, long di ) {
    // issue #442: 非整列 addr は EINVAL、未マップ領域は ENOMEM (POSIX)。emulin は
    //   page protection を強制しないので検証のみ行い、成功時は 0 を返す。ENOMEM は
    //   正当な mprotect (ld.so の RELRO 等) を誤爆しないよう先頭ページのみで判定する。
    long addr = bx;
    long len  = cx;
    if( (addr & 0xFFFL) != 0 ) return( -22 );  // EINVAL: 非整列 addr
    if( len == 0 ) return( 0 );
    // ENOMEM: 未マップ領域。ただし native backend (KVM/WHP) は mmap を software の
    //   alloclist に登録せず mem.in() が mmap 済み領域を追えないため、この検査を
    //   すると正当な mprotect (Rust の sigaltstack guard = mprotect(PROT_NONE) 等) が
    //   ENOMEM 誤爆し codex 等が起動時 panic する (issue #435 調査で判明)。native では
    //   検査せず 0 を返す (emulin は protection を強制しない)。software のみ検査。
    if( !mem.in( addr ) && !(process.cpu instanceof NativeCpuBackend) ) return( -12 );
    return( 0 );
  }
  long sys_sigprocmask( long bx, long cx, long dx, long si, long di ) { return( 0 ); }
  long sys_fchdir( long bx, long cx, long dx, long si, long di ) {
    int fd = (int)bx;
    if( fd < 0 || fd >= flist.size() || get_finfo( fd ) == null ) return EBADF;
    String name = get_name( fd );
    // fchdir の対象はディレクトリでなければ ENOTDIR。
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists( ) || !inode.isDirectory( )) return ENOTDIR;
    process.set_curdir( name );
    if( sysinfo.verbose( )) {
      process.println( " fchdir( " + fd + " )  path = " +  name );
    }
    return( 0 );
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
    // issue #322: 反復開始 (start==0) で dir を 1 度だけ snapshot して固定
    //   (amd64_getdents64 と同じ。反復中の dir 変更で重複/skip を防ぐ)。
    Fileinfo fi_dir = get_finfo( fd );
    if( start == 0 || fi_dir == null || fi_dir.dirSnapshot == null ) {
      list = file_list( name );
      if( fi_dir != null ) fi_dir.dirSnapshot = list;
    } else {
      list = fi_dir.dirSnapshot;
    }

    for( i = 0 ; i < list.length ; i++ ) {
      int   old_d_off;
      short d_reclen = 0;
      // issue #322: host 名は NTFS 予約文字が encode 済み。guest へ返す名前は
      //   decode する (Inode 解決は get_native_path が再 encode するので decode 名で可)。
      // issue #349: case 衝突で別名 encode された leaf も decode して元名で見せる。
      String d_name  = CygSymlink.enabled()
          ? WinCaseMap.decodeCase( CygSymlink.decodeReservedPath( list[i] ) ) : list[i];
      int   memlen = d_name.length( )+1+10;
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
