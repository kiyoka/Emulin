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
  String syscall_name[];
  
  Syscall( Sysinfo _sysinfo, Process _process ) {
    int i = 0;
    sysinfo = _sysinfo;
    process = _process;
    syscall_name = new String[256];
    syscall_name[i++] = "sys_setup";		/* 0 */
    syscall_name[i++] = "sys_exit";
    syscall_name[i++] = "sys_fork";
    syscall_name[i++] = "sys_read";
    syscall_name[i++] = "sys_write";
    syscall_name[i++] = "sys_open";		/* 5 */
    syscall_name[i++] = "sys_close";
    syscall_name[i++] = "sys_waitpid";
    syscall_name[i++] = "sys_creat";
    syscall_name[i++] = "sys_link";
    syscall_name[i++] = "sys_unlink";		/* 10 */
    syscall_name[i++] = "sys_execve";
    syscall_name[i++] = "sys_chdir";
    syscall_name[i++] = "sys_time";
    syscall_name[i++] = "sys_mknod";
    syscall_name[i++] = "sys_chmod";		/* 15 */
    syscall_name[i++] = "sys_chown";
    syscall_name[i++] = "sys_break";
    syscall_name[i++] = "sys_stat";
    syscall_name[i++] = "sys_lseek";
    syscall_name[i++] = "sys_getpid";		/* 20 */
    syscall_name[i++] = "sys_mount";
    syscall_name[i++] = "sys_umount";
    syscall_name[i++] = "sys_setuid";
    syscall_name[i++] = "sys_getuid";
    syscall_name[i++] = "sys_stime";		/* 25 */
    syscall_name[i++] = "sys_ptrace";
    syscall_name[i++] = "sys_alarm";
    syscall_name[i++] = "sys_fstat";
    syscall_name[i++] = "sys_pause";
    syscall_name[i++] = "sys_utime";		/* 30 */
    syscall_name[i++] = "sys_stty";
    syscall_name[i++] = "sys_gtty";
    syscall_name[i++] = "sys_access";
    syscall_name[i++] = "sys_nice";
    syscall_name[i++] = "sys_ftime";		/* 35 */
    syscall_name[i++] = "sys_sync";
    syscall_name[i++] = "sys_kill";
    syscall_name[i++] = "sys_rename";
    syscall_name[i++] = "sys_mkdir";
    syscall_name[i++] = "sys_rmdir";		/* 40 */
    syscall_name[i++] = "sys_dup";
    syscall_name[i++] = "sys_pipe";
    syscall_name[i++] = "sys_times";
    syscall_name[i++] = "sys_prof";
    syscall_name[i++] = "sys_brk";		/* 45 */
    syscall_name[i++] = "sys_setgid";
    syscall_name[i++] = "sys_getgid";
    syscall_name[i++] = "sys_signal";
    syscall_name[i++] = "sys_geteuid";
    syscall_name[i++] = "sys_getegid";		/* 50 */
    syscall_name[i++] = "sys_acct";
    syscall_name[i++] = "sys_phys";
    syscall_name[i++] = "sys_lock";
    syscall_name[i++] = "sys_ioctl";
    syscall_name[i++] = "sys_fcntl";		/* 55 */
    syscall_name[i++] = "sys_mpx";
    syscall_name[i++] = "sys_setpgid";
    syscall_name[i++] = "sys_ulimit";
    syscall_name[i++] = "sys_olduname";
    syscall_name[i++] = "sys_umask";		/* 60 */
    syscall_name[i++] = "sys_chroot";
    syscall_name[i++] = "sys_ustat";
    syscall_name[i++] = "sys_dup2";
    syscall_name[i++] = "sys_getppid";
    syscall_name[i++] = "sys_getpgrp";		/* 65 */
    syscall_name[i++] = "sys_setsid";
    syscall_name[i++] = "sys_sigaction";
    syscall_name[i++] = "sys_sgetmask";
    syscall_name[i++] = "sys_ssetmask";
    syscall_name[i++] = "sys_setreuid";		/* 70 */
    syscall_name[i++] = "sys_setregid";
    syscall_name[i++] = "sys_sigsuspend";
    syscall_name[i++] = "sys_sigpending";
    syscall_name[i++] = "sys_sethostname";
    syscall_name[i++] = "sys_setrlimit";	/* 75 */
    syscall_name[i++] = "sys_getrlimit";
    syscall_name[i++] = "sys_getrusage";
    syscall_name[i++] = "sys_gettimeofday";
    syscall_name[i++] = "sys_settimeofday";
    syscall_name[i++] = "sys_getgroups";	/* 80 */
    syscall_name[i++] = "sys_setgroups";
    syscall_name[i++] = "old_select";
    syscall_name[i++] = "sys_symlink";
    syscall_name[i++] = "sys_lstat";
    syscall_name[i++] = "sys_readlink";		/* 85 */
    syscall_name[i++] = "sys_uselib";
    syscall_name[i++] = "sys_swapon";
    syscall_name[i++] = "sys_reboot";
    syscall_name[i++] = "old_readdir";
    syscall_name[i++] = "old_mmap";		/* 90 */
    syscall_name[i++] = "sys_munmap";
    syscall_name[i++] = "sys_truncate";
    syscall_name[i++] = "sys_ftruncate";
    syscall_name[i++] = "sys_fchmod";
    syscall_name[i++] = "sys_fchown";		/* 95 */
    syscall_name[i++] = "sys_getpriority";
    syscall_name[i++] = "sys_setpriority";
    syscall_name[i++] = "sys_profil";
    syscall_name[i++] = "sys_statfs";
    syscall_name[i++] = "sys_fstatfs";		/* 100 */
    syscall_name[i++] = "sys_ioperm";
    syscall_name[i++] = "sys_socketcall";
    syscall_name[i++] = "sys_syslog";
    syscall_name[i++] = "sys_setitimer";
    syscall_name[i++] = "sys_getitimer";	/* 105 */
    syscall_name[i++] = "sys_newstat";
    syscall_name[i++] = "sys_newlstat";
    syscall_name[i++] = "sys_newfstat";
    syscall_name[i++] = "sys_uname";
    syscall_name[i++] = "sys_iopl";		/* 110 */
    syscall_name[i++] = "sys_vhangup";
    syscall_name[i++] = "sys_idle";
    syscall_name[i++] = "sys_vm86old";
    syscall_name[i++] = "sys_wait4";
    syscall_name[i++] = "sys_swapoff";		/* 115 */
    syscall_name[i++] = "sys_sysinfo";
    syscall_name[i++] = "sys_ipc";
    syscall_name[i++] = "sys_fsync";
    syscall_name[i++] = "sys_sigreturn";
    syscall_name[i++] = "sys_clone";		/* 120 */
    syscall_name[i++] = "sys_setdomainname";
    syscall_name[i++] = "sys_newuname";
    syscall_name[i++] = "sys_modify_ldt";
    syscall_name[i++] = "sys_adjtimex";
    syscall_name[i++] = "sys_mprotect";		/* 125 */
    syscall_name[i++] = "sys_sigprocmask";
    syscall_name[i++] = "sys_create_module";
    syscall_name[i++] = "sys_init_module";
    syscall_name[i++] = "sys_delete_module";
    syscall_name[i++] = "sys_get_kernel_syms";	/* 130 */
    syscall_name[i++] = "sys_quotactl";
    syscall_name[i++] = "sys_getpgid";
    syscall_name[i++] = "sys_fchdir";
    syscall_name[i++] = "sys_bdflush";
    syscall_name[i++] = "sys_sysfs";		/* 135 */
    syscall_name[i++] = "sys_personality";
    syscall_name[i++] = "no syscall( for_afs )";          /* for afs_syscall */
    syscall_name[i++] = "sys_setfsuid";
    syscall_name[i++] = "sys_setfsgid";
    syscall_name[i++] = "sys_llseek";		/* 140 */
    syscall_name[i++] = "sys_getdents";
    syscall_name[i++] = "sys_select";
    syscall_name[i++] = "sys_flock";
    syscall_name[i++] = "sys_msync";
    syscall_name[i++] = "sys_readv";		/* 145 */
    syscall_name[i++] = "sys_writev";
    syscall_name[i++] = "sys_getsid";
    syscall_name[i++] = "sys_fdatasync";
    syscall_name[i++] = "sys_sysctl";
    syscall_name[i++] = "sys_mlock";		/* 150 */
    syscall_name[i++] = "sys_munlock";
    syscall_name[i++] = "sys_mlockall";
    syscall_name[i++] = "sys_munlockall";
    syscall_name[i++] = "sys_sched_setparam";
    syscall_name[i++] = "sys_sched_getparam";   /* 155 */
    syscall_name[i++] = "sys_sched_setscheduler";
    syscall_name[i++] = "sys_sched_getscheduler";
    syscall_name[i++] = "sys_sched_yield";
    syscall_name[i++] = "sys_sched_get_priority_max";
    syscall_name[i++] = "sys_sched_get_priority_min";  /* 160 */
    syscall_name[i++] = "sys_sched_rr_get_interval";
    syscall_name[i++] = "sys_nanosleep";
    syscall_name[i++] = "sys_mremap";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 165 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 170 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 175 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 180 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 185 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 190 */
  }

  // 自分の複製を返す
  public Syscall duplicate( Process _process ) {
    int i;
    Syscall _syscall = new Syscall( sysinfo, _process );
    _syscall.mem  = mem;
    _syscall.update_info( (FileAccess)this );
    return( _syscall );
  }

  // メモリシステムを接続する
  void connect_mem( Memory _mem ) {
    mem = _mem;
  }

  // システムコール
  int call( int id, int bx, int cx, int dx, int si, int di ) {
    int ret = 0;
    boolean done = false;
    if( sysinfo.verbose( )) {
      process.println( " System call id=[" + id + "] " + syscall_name[id] + "(" 
		       + Util.hexstr( bx, 8 ) + ","
		       + Util.hexstr( cx, 8 ) + ","
		       + Util.hexstr( dx, 8 ) + ","
		       + Util.hexstr( si, 8 ) + ","
		       + Util.hexstr( di, 8 ) + ")    evals = " + process.evals( ));
    }
    if( id ==   1 ) {      ret = sys_exit(  bx, cx, dx, si, di );           done = true; }
    if( id ==   2 ) {      ret = sys_fork(  bx, cx, dx, si, di );           done = true; }
    if( id ==   3 ) {      ret = sys_read(  bx, cx, dx, si, di );           done = true; }
    if( id ==   4 ) {      ret = sys_write(  bx, cx, dx, si, di );          done = true; }
    if( id ==   5 ) {      ret = sys_open(  bx, cx, dx, si, di );           done = true; }
    if( id ==   6 ) {      ret = sys_close(  bx, cx, dx, si, di );          done = true; }
    if( id ==  10 ) {      ret = sys_unlink(  bx, cx, dx, si, di );         done = true; }
    if( id ==  11 ) {      ret = sys_execve(  bx, cx, dx, si, di );         done = true; }
    if( id ==  12 ) {      ret = sys_chdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  13 ) {      ret = sys_time(  bx, cx, dx, si, di );           done = true; }
    if( id ==  15 ) {      ret = sys_chmod(  bx, cx, dx, si, di );          done = true; }
    if( id ==  16 ) {      ret = sys_chown(  bx, cx, dx, si, di );          done = true; }
    if( id ==  19 ) {      ret = sys_lseek(  bx, cx, dx, si, di );          done = true; }
    if( id ==  20 ) {      ret = sys_getpid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  21 ) {      ret = sys_mount(  bx, cx, dx, si, di );          done = true; }
    if( id ==  22 ) {      ret = sys_umount(  bx, cx, dx, si, di );         done = true; }
    if( id ==  23 ) {      ret = sys_setuid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  24 ) {      ret = sys_getuid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  27 ) {      ret = sys_alarm(  bx, cx, dx, si, di );          done = true; }
    if( id ==  29 ) {      ret = sys_pause(  bx, cx, dx, si, di );          done = true; }
    if( id ==  30 ) {      ret = sys_utime(  bx, cx, dx, si, di );          done = true; }
    if( id ==  33 ) {      ret = sys_access(  bx, cx, dx, si, di );         done = true; }
    if( id ==  36 ) {      ret = sys_sync(  bx, cx, dx, si, di );           done = true; }
    if( id ==  37 ) {      ret = sys_kill(  bx, cx, dx, si, di );           done = true; }
    if( id ==  38 ) {      ret = sys_rename(  bx, cx, dx, si, di );         done = true; }
    if( id ==  39 ) {      ret = sys_mkdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  40 ) {      ret = sys_rmdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  41 ) {      ret = sys_dup(  bx, cx, dx, si, di );            done = true; }
    if( id ==  42 ) {      ret = sys_pipe(  bx, cx, dx, si, di );           done = true; }
    if( id ==  43 ) {      ret = sys_times(  bx, cx, dx, si, di );          done = true; }
    if( id ==  45 ) {      ret = sys_brk(  bx, cx, dx, si, di );            done = true; }
    if( id ==  46 ) {      ret = sys_setgid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  47 ) {      ret = sys_getgid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  49 ) {      ret = sys_geteuid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  50 ) {      ret = sys_getegid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  54 ) {      ret = sys_ioctl(  bx, cx, dx, si, di );          done = true; }
    if( id ==  55 ) {      ret = sys_fcntl(  bx, cx, dx, si, di );          done = true; }
    if( id ==  57 ) {      ret = sys_setpgid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  60 ) {      ret = sys_umask(  bx, cx, dx, si, di );          done = true; }
    if( id ==  63 ) {      ret = sys_dup2(  bx, cx, dx, si, di );           done = true; }
    if( id ==  64 ) {      ret = sys_getppid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  65 ) {      ret = sys_getpgrp(  bx, cx, dx, si, di );        done = true; }
    if( id ==  66 ) {      ret = sys_setsid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  67 ) {      ret = sys_sigaction(  bx, cx, dx, si, di );      done = true; }
    if( id ==  75 ) {      ret = sys_setrlimit(  bx, cx, dx, si, di );      done = true; }
    if( id ==  76 ) {      ret = sys_getrlimit(  bx, cx, dx, si, di );      done = true; }
    if( id ==  78 ) {      ret = sys_gettimeofday(  bx, cx, dx, si, di );   done = true; }
    if( id ==  80 ) {      ret = sys_getgroups(  bx, cx, dx, si, di );      done = true; }
    if( id ==  85 ) {      ret = sys_readlink(  bx, cx, dx, si, di );       done = true; }
    if( id ==  90 ) {      ret = sys_mmap(  bx, cx, dx, si, di );           done = true; }
    if( id ==  91 ) {      ret = sys_munmap(  bx, cx, dx, si, di );         done = true; }
    if( id ==  93 ) {      ret = sys_ftruncate(  bx, cx, dx, si, di );      done = true; }
    if( id ==  94 ) {      ret = sys_fchmod(  bx, cx, dx, si, di );         done = true; }
    if( id == 102 ) {      ret = sys_socketcall(  bx, cx, dx, si, di );     done = true; }
    if( id == 106 ) {      ret = sys_stat(  bx, cx, dx, si, di );           done = true; }
    if( id == 107 ) {      ret = sys_stat(  bx, cx, dx, si, di );           done = true; }
    if( id == 108 ) {      ret = sys_fstat(  bx, cx, dx, si, di );          done = true; }
    if( id == 114 ) {      ret = sys_wait4(  bx, cx, dx, si, di );          done = true; }
    if( id == 122 ) {      ret = sys_uname(  bx, cx, dx, si, di );          done = true; }
    if( id == 125 ) {      ret = sys_mprotect(  bx, cx, dx, si, di );       done = true; }
    if( id == 126 ) {      ret = sys_sigprocmask(  bx, cx, dx, si, di );    done = true; }
    if( id == 133 ) {      ret = sys_fchdir(  bx, cx, dx, si, di );         done = true; }
    if( id == 136 ) {      ret = sys_personality(  bx, cx, dx, si, di );    done = true; }
    if( id == 141 ) {      ret = sys_getdents(  bx, cx, dx, si, di );       done = true; }
    if( id == 142 ) {      ret = sys_select(  bx, cx, dx, si, di );         done = true; }
    if( id == 143 ) {      ret = sys_flock(  bx, cx, dx, si, di );          done = true; }
    if( id == 146 ) {      ret = sys_writev(  bx, cx, dx, si, di );         done = true; }
    if( id == 162 ) {      ret = sys_nanosleep(  bx, cx, dx, si, di );      done = true; }
    if( id == 163 ) {      ret = sys_mremap(  bx, cx, dx, si, di );         done = true; }
    if( id >= 167 ) {      ret = 0;                                         done = true; }
    if( !done ) {
      process.println( "Emulin Error : Unsupported system call... id=[" + id + "]  ( evals = " + process.evals( ) + ")" );
      // エラーの場合そのプロセスだけ終了する。
      // エラーコード 1 で終了する。
      sys_exit( 1, 0, 0, 0, 0 );
      ret = 0;
    }
    else {
      if( sysinfo.verbose( )) {
	process.println( "          Syscall ret = " + ret );
      }
    }
    return( ret );
  }

  int sys_exit( int bx, int cx, int dx, int si, int di ) {
    int exit_code = bx;
    if( sysinfo.debug( )) {
      process.println( "Program is terminated ... ( " + exit_code + " ) " );
    }
    System.out.flush( );
    System.err.flush( );
    sysinfo.kernel.last_exit_code = exit_code;
    process.set_exit_flag( );
    return( 0 );
  }
  synchronized int sys_fork( int bx, int cx, int dx, int si, int di ) {
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
  int sys_read( int bx, int cx, int dx, int si, int di ) {
    int fd      = bx;
    int address = cx;
    int len     = dx;
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
  int sys_write( int bx, int cx, int dx, int si, int di ) {
    int fd      = bx;
    int address = cx;
    int len     = dx;

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
  int sys_open( int bx, int cx, int dx, int si, int di ) {
    String name = mem.loadString( bx );
    String mode = "r";
    int full_md = cx;
    int md = cx & O_ACCMODE;
    int ret = 0;
    Inode inode;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    inode = new Inode( name, sysinfo );

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
  int sys_close( int bx, int cx, int dx, int si, int di ) {
    int fd  = bx;
    int ret = -1;
    if( fd >= 0 ) {
      if( FileClose( fd )) { ret = 0; }
    }
    return( ret );
  }
  int sys_unlink( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
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
  int sys_execve( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
    String name = mem.loadString( name_p );
    String tmp_s[] = new String[256];
    String _args[];
    String _envs[];
    int argv = cx;
    int envp = dx;
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
  int sys_chdir( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
    int ret = 0;
    String name = mem.loadString( name_p ); 
    Inode inode;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    process.set_curdir( name );
    return( ret );
  }
  int sys_time( int bx, int cx, int dx, int si, int di ) {
    int ret = (int)(System.currentTimeMillis( ) / 1000L);
    return( ret );
  }
  int sys_chmod( int bx, int cx, int dx, int si, int di ) { return( 0 ); }
  int sys_chown( int bx, int cx, int dx, int si, int di ) { return( 0 ); }
  int sys_lseek( int bx, int cx, int dx, int si, int di ) {
    int fd = bx;
    int offset = cx;
    int whence = dx;
    return( FileSeek( fd, offset, whence ));
  }
  int sys_getpid(  int bx, int cx, int dx, int si, int di ) {    return( process.pid );  }
  int sys_mount(  int bx, int cx, int dx, int si, int di )  { 
    int devname_p = bx;
    int dirname_p = cx;
    String devname = mem.loadString( devname_p ); 
    String dirname = mem.loadString( dirname_p ); 
    sysinfo.add_mountpoint( dirname, devname );
    if( sysinfo.verbose( )) {
      process.println( " sys_mount : dev[" +devname+ "]  dir[" +dirname+ "]" );
    }
    return( 0 );
  }
  int sys_umount(  int bx, int cx, int dx, int si, int di )  { 
    int name_p = bx;
    String name = mem.loadString( name_p );
    sysinfo.remove_mountpoint( name );
    if( sysinfo.verbose( )) {
      process.println( " sys_umount : [" +name+ "]" );
    }
    return( 0 );
  }
  int sys_alarm(  int bx, int cx, int dx, int si, int di )  {    return( 0 ); }
  int sys_pause(  int bx, int cx, int dx, int si, int di )  {  
    if( sysinfo.verbose( )) {
      process.println( " Info : process is pause" );
    }
    while( true ) {
      Thread.yield( );
      try { Thread.sleep( 1000L ); }
      catch( InterruptedException m ) { };
    }
  }
  int sys_utime(  int bx, int cx, int dx, int si, int di )  {    return( 0 );   }
  int sys_access( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
    int mode = cx;
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
  int sys_sync(  int bx, int cx, int dx, int si, int di )   {    return( 0 );   }
  int sys_kill(  int bx, int cx, int dx, int si, int di )   {    return( 0 );   }
  int sys_rename( int bx, int cx, int dx, int si, int di ) {
    int _name_from = bx;
    int _name_to   = cx;
    int ret = 0;
    String name_from = mem.loadString( _name_from ); 
    String name_to   = mem.loadString( _name_to   ); 
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
  int sys_mkdir( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
    int mode = cx;
    int ret = 0;
    String name = sysinfo.get_full_path( process.get_curdir( ), mem.loadString( name_p ));
    if( !mkdir( name )) {
      ret = EPERM;
    }
    return( ret );
  }
  int sys_rmdir( int bx, int cx, int dx, int si, int di ) {  return( sys_unlink( bx, cx, dx, si, di )); }
  int sys_dup(  int bx, int cx, int dx, int si, int di ) {
    int i;
    int fd = bx;
    int new_fd = search_empty_fd( );
    Dup( fd, new_fd );
    return( new_fd );
  }

  int sys_pipe( int bx, int cx, int dx, int si, int di ) {
    int array_p = bx;
    int ret_in;
    int ret_out;
    int pipe_no;
    ret_in  = FileOpen( "<pipe>", "r",  O_RDONLY );
    ret_out = FileOpen( "<pipe>", "rw", O_WRONLY );
    mem.store32( array_p,   ret_in );
    mem.store32( array_p+4, ret_out );
    pipe_no = sysinfo.kernel.connect_pipe( );
    set_pipe( pipe_no, ret_in );
    set_pipe( pipe_no, ret_out );
    if( sysinfo.verbose( )) {
      process.println( "  " + ret_in + "," + ret_out + " = SYS_PIPE( )" );
    }
    return( 0 );
  }
  int sys_times( int bx, int cx, int dx, int si, int di )   {    return( 0 ); }
  int sys_setuid(  int bx, int cx, int dx, int si, int di ) {    process.uid = bx; return( 0 );   }
  int sys_getuid(  int bx, int cx, int dx, int si, int di ) {    return( process.uid ); }
  int sys_setgid(  int bx, int cx, int dx, int si, int di ) {    process.gid = bx; return( 0 );   }
  int sys_getgid(  int bx, int cx, int dx, int si, int di ) {    return( process.gid ); }
  int sys_brk(  int bx, int cx, int dx, int si, int di ) {
    int ret = 0;
    if( bx == 0 ) {
      ret = mem.get_curbrk( );
    }
    else {
      mem.set_curbrk( bx );
      ret = mem.get_curbrk( );
    }
    return( ret );
  }
  int sys_geteuid(  int bx, int cx, int dx, int si, int di ) {
    return( process.uid );
  }
  int sys_getegid(  int bx, int cx, int dx, int si, int di ) {
    return( process.gid );
  }
  int sys_ioctl(  int bx, int cx, int dx, int si, int di ) {
    int i;
    int fd = bx;
    int request = cx;
    int address = dx;
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
  int sys_fcntl(  int bx, int cx, int dx, int si, int di ) {
    int i;
    int fd = bx;
    int command = cx;
    int arg = dx;

    if( F_DUPFD == command ) {  /* dup */
      FileClose( arg );
      Dup( fd, arg );
      return( arg );
    }
    if( F_SETFD == command ) {	/* set f_flags */
      return( 0 );
    }
    if( F_GETFL == command ) {	/* more flags (cloexec) */
      return( GetModeBit( fd ));
    }
    return( 0 );
  }
  int sys_setpgid( int bx, int cx, int dx, int si, int di ) {   return( 0 ); }
  int sys_umask(  int bx, int cx, int dx, int si, int di ) {
    return( 0 );
  }
  int sys_dup2(  int bx, int cx, int dx, int si, int di ) {
    return( sys_fcntl( bx, cx, F_DUPFD, 0, 0 ));
  }
  int sys_getppid(  int bx, int cx, int dx, int si, int di ) {    return( 8 );  }
  int sys_getpgrp(  int bx, int cx, int dx, int si, int di ) {    return( 9 );  }
  int sys_setsid(  int bx, int cx, int dx, int si, int di )  {    return( 1 );  }
  int sys_sigaction(  int bx, int cx, int dx, int si, int di ) {
    int signum   = bx;
    int act_p    = cx;
    int oldact_p = dx;
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
  int sys_setrlimit(  int bx, int cx, int dx, int si, int di ) {  return( 0 );  }
  int sys_getrlimit(  int bx, int cx, int dx, int si, int di ) {
    int resource = bx;
    int address  = cx;
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
  int sys_gettimeofday(  int bx, int cx, int dx, int si, int di ) {
    int resource = bx;
    int address  = cx;
    return( 0 );
  }
  int sys_getgroups( int bx, int cx, int dx, int si, int di ) {   return( 0 ); }
  int sys_readlink( int bx, int cx, int dx, int si, int di )  {   return( EINVAL ); }
  int sys_mmap( int bx, int cx, int dx, int si, int di ) {
    int adrs   = arg( bx, -1 );
    int length = arg( bx, 0 );
    int fd     = arg( bx, 3 );
    int offset = arg( bx, 4 );
    adrs = mem.alloc_and_map( adrs, length, fd, offset );
    return( adrs );
  }
  int sys_munmap( int bx, int cx, int dx, int si, int di ) {
    int address = bx;
    int length = cx;
    return( mem.free( address, length ));
  }
  int sys_ftruncate( int bx, int cx, int dx, int si, int di )  {   return( 0 ); }
  int sys_fchmod( int bx, int cx, int dx, int si, int di )     {   return( 0 ); }
  int sys_socketcall( int bx, int cx, int dx, int si, int di ) {
    int func_id = bx;
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
  int sys_stat( int bx, int cx, int dx, int si, int di ) {
    int name_p = bx;
    int address = cx;
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

  int sys_fstat( int bx, int cx, int dx, int si, int di ) {
    int fd  = bx;
    int address = cx;
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
  int sys_wait4( int bx, int cx, int dx, int si, int di ) {
    int WNOHANG = 1;
    int pid          = bx;
    int status_p     = cx;
    int options      = dx;
    int rusage_p     = si;
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
  void _set_file_stat( int address, String name ) {
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
    mem.store32( address , inode.st_size );      address += 4;
    mem.store32( address , inode.st_blksize );   address += 4;
    mem.store32( address , inode.st_blocks  );   address += 4;
    mem.store32( address , inode.st_atime   );   address += 4;
    mem.store32( address , 0 );                  address += 4;  // __unused1
    mem.store32( address , inode.st_mtime   );   address += 4;
    mem.store32( address , 0 );                  address += 4;  // __unused2
    mem.store32( address , inode.st_ctime   );   address += 4;
    mem.store32( address , 0 );                  address += 4;  // __unused3
    mem.store32( address , 0 );                  address += 4;  // __unused4
    mem.store32( address , 0 );                  address += 4;  // __unused5
  }
  int sys_uname( int bx, int cx, int dx, int si, int di )       {
    final int SYS_NMLN = 65;
    int address = bx;
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
  int sys_mprotect( int bx, int cx, int dx, int si, int di )    { return( 0 ); }
  int sys_sigprocmask( int bx, int cx, int dx, int si, int di ) { return( 0 ); }
  int sys_fchdir( int bx, int cx, int dx, int si, int di ) {
    int fd = bx;
    int ret = 0;
    String name = get_name( fd ); 
    process.set_curdir( name );
    if( sysinfo.verbose( )) {
      process.println( " fchdir( " + fd + " )  path = " +  name );
    }
    return( ret );
  }
  int sys_personality( int bx, int cx, int dx, int si, int di ) { return( 0 ); }
  int sys_getdents( int bx, int cx, int dx, int si, int di ) {
    int fd = bx;
    int dirp = cx;
    int count = dx;
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
  int sys_select( int bx, int cx, int dx, int si, int di )      {
    int n = bx;
    int read_fds   = cx;
    int write_fds  = dx;
    int except_fds = si;
    int timeout_p  = di;
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
  int sys_flock( int bx, int cx, int dx, int si, int di ) {   return( 0 ); }
  int sys_writev( int bx, int cx, int dx, int si, int di ) {
    // memo:writeシステムコールを使用する
    int fd      = bx;
    int iovec_p = cx;
    int count   = dx;
    int i;
    int ret     = 0;
    for( i = 0 ; i < count ; i++ ) {
      int cur_p   = mem.load32( iovec_p + (i * 8) + 0 );
      int cur_len = mem.load32( iovec_p + (i * 8) + 4 );
      int len;
      len = sys_write( fd, cur_p, cur_len, 0, 0 );
      if( len < 0 ) {
	ret = -1;
	break;
      }
      ret += len;
    }
    return( ret );
  }
  int sys_nanosleep( int bx, int cx, int dx, int si, int di )   { return( -1 ); }
  int sys_mremap( int bx, int cx, int dx, int si, int di ) {
    int old_address  = arg( bx, 0 );
    int old_size     = arg( bx, 1 );
    int new_size     = arg( bx, 2 );
    int flags        = arg( bx, 3 );
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
