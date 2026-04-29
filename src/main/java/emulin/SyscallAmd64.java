// ----------------------------------------
//  SyscallAmd64: x86-64 Linux ABI ディスパッチ層
//
//  x86-64 ABI 固有部分:
//    - 番号テーブル (AMD64 unistd.h 準拠, i386 と全く別)
//    - call_amd64() ディスパッチ (RAX=sysno, RDI/RSI/RDX/R10/R8/R9=引数)
//    - 64-bit 固有実装: read/write (long アドレス), stat (AMD64 struct layout),
//      mmap (6 直接引数), writev (8-byte iov)
//    - それ以外は int キャストして親クラス sys_* に委譲
//    - duplicate() (SyscallAmd64 インスタンスを複製)
//
//  共通の sys_* 実装は親クラス Syscall に残る。
// ----------------------------------------
package emulin;

public class SyscallAmd64 extends Syscall
{
  SyscallAmd64( Sysinfo _sysinfo, Process _process ) {
    super( _sysinfo, _process );
  }

  @Override
  public Syscall duplicate( Process _process ) {
    SyscallAmd64 _syscall = new SyscallAmd64( sysinfo, _process );
    _syscall.mem = mem;
    _syscall.update_info( (FileAccess)this );
    return( _syscall );
  }

  // ---------------------------------------------------------------
  // メイン ディスパッチ
  //   AMD64 ABI: RAX=sysno, RDI=a1, RSI=a2, RDX=a3, R10=a4, R8=a5, R9=a6
  //   戻り値 → RAX (呼び出し元で設定)
  // ---------------------------------------------------------------
  public long call_amd64( long sysno, long a1, long a2, long a3, long a4, long a5, long a6 ) {
    int n = (int)sysno;

    // --- 64-bit 固有実装が必要なもの ---
    if( n ==   0 ) return amd64_read(   a1, a2, a3 );       // read
    if( n ==   1 ) return amd64_write(  a1, a2, a3 );       // write
    if( n ==   4 ) return amd64_stat(   a1, a2 );            // stat
    if( n ==   5 ) return amd64_fstat(  a1, a2 );            // fstat
    if( n ==   6 ) return amd64_stat(   a1, a2 );            // lstat (同一実装)
    if( n ==   9 ) return amd64_mmap(   a1, a2, a3, a4, a5, a6 ); // mmap
    if( n ==  20 ) return amd64_writev( a1, a2, a3 );       // writev
    if( n ==  60 ) return amd64_exit(   a1 );                // exit
    if( n == 231 ) return amd64_exit(   a1 );                // exit_group

    // --- 親クラス sys_* に int キャストして委譲 ---
    // アドレス引数は静的バイナリの低アドレス範囲では int 変換可
    if( n ==   2 ) return sys_open(    (int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==   3 ) return sys_close(   (int)a1, 0, 0, 0, 0 );
    if( n ==   8 ) return sys_lseek(   (int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  10 ) return sys_mprotect((int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  11 ) return sys_munmap(  (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  12 ) return sys_brk( (int)a1, 0, 0, 0, 0 ) & 0xFFFFFFFFL;
    if( n ==  16 ) return amd64_ioctl( a1, a2, a3 );             // ioctl
    if( n ==  21 ) return sys_access(  (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  22 ) return amd64_pipe( a1 );
    if( n ==  23 ) return sys_select(  (int)a1,(int)a2,(int)a3,(int)a4,(int)a5 );
    if( n ==  25 ) return sys_mremap(  (int)a1,(int)a2,(int)a3,(int)a4, 0 );
    if( n ==  32 ) return sys_dup(     (int)a1, 0, 0, 0, 0 );
    if( n ==  33 ) return sys_dup2(    (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  34 ) return sys_pause(   0, 0, 0, 0, 0 );
    if( n ==  35 ) return amd64_nanosleep( a1, a2 );
    if( n ==  37 ) return sys_alarm(   (int)a1, 0, 0, 0, 0 );
    if( n ==  39 ) return sys_getpid(  0, 0, 0, 0, 0 );
    if( n ==  57 ) return sys_fork(    0, 0, 0, 0, 0 );
    if( n ==  59 ) return amd64_execve( a1, a2, a3 );
    if( n ==  61 ) return amd64_wait4( a1, a2, a3, a4 );
    if( n ==  62 ) return sys_kill(    (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  63 ) return sys_uname(   (int)a1, 0, 0, 0, 0 );
    if( n ==  72 ) return sys_fcntl(   (int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  73 ) return sys_flock(   (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  74 ) return sys_sync(    0, 0, 0, 0, 0 );
    if( n ==  77 ) return sys_ftruncate((int)a1,(int)a2, 0, 0, 0 );
    if( n ==  78 ) return sys_getdents((int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  80 ) return sys_chdir(   (int)a1, 0, 0, 0, 0 );
    if( n ==  81 ) return sys_fchdir(  (int)a1, 0, 0, 0, 0 );
    if( n ==  82 ) return sys_rename(  (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  83 ) return sys_mkdir(   (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  84 ) return sys_rmdir(   (int)a1, 0, 0, 0, 0 );
    if( n ==  87 ) return sys_unlink(  (int)a1, 0, 0, 0, 0 );
    if( n ==  89 ) return sys_readlink((int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  90 ) return sys_chmod(   (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  91 ) return sys_fchmod(  (int)a1,(int)a2, 0, 0, 0 );
    if( n ==  92 ) return sys_chown(   (int)a1,(int)a2,(int)a3, 0, 0 );
    if( n ==  95 ) return sys_umask(   (int)a1, 0, 0, 0, 0 );
    if( n ==  96 ) return amd64_gettimeofday( a1, a2 );
    if( n ==  97 ) return sys_getrlimit((int)a1,(int)a2, 0, 0, 0 );
    if( n ==  98 ) return 0;  // getrusage (stub)
    if( n == 100 ) return sys_times(   (int)a1, 0, 0, 0, 0 );
    if( n == 102 ) return sys_getuid(  0, 0, 0, 0, 0 );
    if( n == 104 ) return sys_getgid(  0, 0, 0, 0, 0 );
    if( n == 105 ) return sys_setuid(  (int)a1, 0, 0, 0, 0 );
    if( n == 106 ) return sys_setgid(  (int)a1, 0, 0, 0, 0 );
    if( n == 107 ) return sys_geteuid( 0, 0, 0, 0, 0 );
    if( n == 108 ) return sys_getegid( 0, 0, 0, 0, 0 );
    if( n == 109 ) return sys_setpgid( (int)a1,(int)a2, 0, 0, 0 );
    if( n == 110 ) return sys_getppid( 0, 0, 0, 0, 0 );
    if( n == 111 ) return sys_getpgrp( 0, 0, 0, 0, 0 );
    if( n == 112 ) return sys_setsid(  0, 0, 0, 0, 0 );
    if( n == 113 ) return 0;  // setreuid (stub)
    if( n == 115 ) return sys_getgroups((int)a1,(int)a2, 0, 0, 0 );
    if( n == 121 ) return sys_getpgrp( 0, 0, 0, 0, 0 );  // getpgid → getpgrp
    if( n == 124 ) return sys_setsid(  0, 0, 0, 0, 0 );  // getsid → setsid stub
    if( n == 135 ) return sys_personality((int)a1, 0, 0, 0, 0 );
    if( n == 160 ) return sys_setrlimit((int)a1,(int)a2, 0, 0, 0 );
    if( n == 161 ) return 0;  // chroot (stub)
    if( n == 162 ) return sys_sync(    0, 0, 0, 0, 0 );
    if( n == 165 ) return sys_mount(   (int)a1,(int)a2,(int)a3,(int)a4,(int)a5 );
    if( n == 166 ) return sys_umount(  (int)a1, 0, 0, 0, 0 );
    if( n == 170 ) return 0;  // sethostname (stub)

    // --- 追加スタブ (glibc 静的リンクバイナリ起動に必要) ---
    if( n ==  13 ) return 0;  // rt_sigaction (stub)
    if( n ==  14 ) return 0;  // rt_sigprocmask (stub)
    if( n ==  28 ) return 0;  // madvise (stub)
    if( n == 158 ) return amd64_arch_prctl( a1, a2 );  // arch_prctl
    if( n == 218 ) return sys_getpid(0,0,0,0,0);       // set_tid_address → pid
    if( n == 228 ) return 0;  // clock_gettime (stub)
    if( n == 231 ) return amd64_exit( a1 );             // exit_group (already above, but guard)
    if( n == 302 ) return 0;  // prlimit64 (stub)
    if( n == 318 ) return ENOSYS; // getrandom → ENOSYS (glibc falls back)
    if( n == 186 ) return sys_getpid( 0, 0, 0, 0, 0 );  // gettid → pid
    if( n == 234 ) return 0;  // tgkill (stub)
    if( n == 257 ) return sys_open( (int)a1, (int)a2, (int)a3, 0, 0 );  // openat (dirfd ignored)
    if( n == 267 ) return amd64_readlinkat( (int)a1, a2, a3, (int)a4 ); // readlinkat
    if( n == 273 ) return 0;  // set_robust_list (stub)
    if( n == 334 ) return 0;  // rseq (stub)

    process.println( "Emulin Error : Unsupported amd64 syscall sysno=[" + sysno + "]" );
    sys_exit( 1, 0, 0, 0, 0 );
    return 0;
  }

  // ---------------------------------------------------------------
  // 64-bit 固有実装
  // ---------------------------------------------------------------

  // read(fd, buf, count)
  private long amd64_read( long fd, long addr, long count ) {
    int len = (int)count;
    if( isSTD((int)fd) || isERR((int)fd) ) {
      byte[] buf = new byte[len];
      len = sysinfo.kernel.console.read( buf, process );
      for( int i = 0; i < len; i++ ) mem.store8( addr + i, buf[i] );
    } else {
      byte[] buf = new byte[len];
      len = FileRead( (int)fd, buf );
      if( len < 0 ) return EBADF;
      for( int i = 0; i < len; i++ ) mem.store8( addr + i, buf[i] );
    }
    return len;
  }

  // write(fd, buf, count)
  private long amd64_write( long fd, long addr, long count ) {
    int len = (int)count;
    byte[] buf = new byte[len];
    for( int i = 0; i < len; i++ ) buf[i] = mem.load8( addr + i );
    if( isSTD((int)fd) || isERR((int)fd) ) {
      sysinfo.kernel.console.write( buf, isERR((int)fd) );
    } else {
      if( !FileWrite((int)fd, buf) ) return -EPIPE;
    }
    return len;
  }

  // writev(fd, iov, iovcnt)  — AMD64: iov_base(8), iov_len(8)
  private long amd64_writev( long fd, long iov_ptr, long iovcnt ) {
    long total = 0;
    for( int i = 0; i < (int)iovcnt; i++ ) {
      long base = mem.load64( iov_ptr + i * 16 );
      long len  = mem.load64( iov_ptr + i * 16 + 8 );
      if( len > 0 ) total += amd64_write( fd, base, len );
    }
    return total;
  }

  // execve(path, argv, envp) — argv/envp は 8 バイトポインタの NULL 終端配列
  private long amd64_execve( long path_addr, long argv_addr, long envp_addr ) {
    String name = mem.loadString( path_addr );
    java.util.ArrayList<String> args = new java.util.ArrayList<>( );
    java.util.ArrayList<String> envs = new java.util.ArrayList<>( );
    if( argv_addr != 0 ) {
      for( int i = 0; ; i++ ) {
        long p = mem.load64( argv_addr + i*8L );
        if( p == 0 ) break;
        args.add( mem.loadString( p ) );
      }
    }
    if( envp_addr != 0 ) {
      for( int i = 0; ; i++ ) {
        long p = mem.load64( envp_addr + i*8L );
        if( p == 0 ) break;
        envs.add( mem.loadString( p ) );
      }
    }
    if( args.isEmpty( ) ) args.add( name );
    else                  args.set( 0, name );
    String[] _args = args.toArray( new String[0] );
    String[] _envs = envs.toArray( new String[0] );
    /* kernel main loop の 1 秒ポーリングを待たず直接 exec を呼ぶ。
       自スレッド (= 旧プロセスのスレッド) はこの後 set_exit_flag() で
       run() ループを抜けて死に、kernel.exec が新プロセスを start する。
       注意: kernel.exec は syscall.process を新プロセスに張り替えるので、
       旧プロセスの参照を先に確保しておく必要がある。 */
    Process old = process;
    sysinfo.kernel.exec( old.pid, _args, _envs );
    old.set_exit_flag( );
    return 0;
  }

  // wait4(pid, status, options, rusage) — int 切り詰めを避けて long アドレスで status を書く
  private long amd64_wait4( long pid_l, long status_addr, long options_l, long rusage_addr ) {
    final int WNOHANG = 1;
    int pid = (int)pid_l;
    int options = (int)options_l;
    int ret_pid = 0;
    if( pid == -1 ) {
      while( true ) {
        ret_pid = sysinfo.kernel.is_child_exited( process.pid );
        if( options == WNOHANG ) {
          if( 0 < ret_pid ) ret_pid = -1;
          break;
        }
        if( -1 < ret_pid ) break;
        Thread.yield( );
        try { Thread.sleep( 100L ); } catch( InterruptedException m ) { }
        if( -1 != process.psig( )) { ret_pid = EINTR; break; }
      }
    }
    if( status_addr != 0 ) {
      mem.store32( status_addr, 0 );  // 現状は子の終了コードを 0 として返す
    }
    return ret_pid;
  }

  // pipe(pipefd[2]) — int 切り詰めを避けて long アドレスで直接書く
  private long amd64_pipe( long array_addr ) {
    int ret_in  = FileOpen( "<pipe>", "r",  O_RDONLY );
    int ret_out = FileOpen( "<pipe>", "rw", O_WRONLY );
    mem.store32( array_addr,     ret_in );
    mem.store32( array_addr + 4, ret_out );
    int pipe_no = sysinfo.kernel.connect_pipe( );
    set_pipe( pipe_no, ret_in );
    set_pipe( pipe_no, ret_out );
    return 0;
  }

  // gettimeofday(tv, tz) — tv は struct timeval {long tv_sec; long tv_usec;}
  private long amd64_gettimeofday( long tv_addr, long tz_addr ) {
    if( tv_addr != 0 ) {
      long ms = System.currentTimeMillis();
      mem.store64( tv_addr,     ms / 1000L );
      mem.store64( tv_addr + 8, (ms % 1000L) * 1000L );
    }
    /* tz は廃止予定なので無視 */
    return 0;
  }

  // nanosleep(req, rem) — req は struct timespec {long tv_sec; long tv_nsec;}
  private long amd64_nanosleep( long req_addr, long rem_addr ) {
    long sec  = mem.load64( req_addr );
    long nsec = mem.load64( req_addr + 8 );
    long ms   = sec * 1000L + nsec / 1_000_000L;
    if( ms < 0 || sec < 0 || nsec < 0 || nsec >= 1_000_000_000L ) return -22; // EINVAL
    if( ms > 0 ) {
      try { Thread.sleep( ms ); }
      catch( InterruptedException e ) { /* 短いスリープなので EINTR は無視 */ }
    }
    if( rem_addr != 0 ) {
      mem.store64( rem_addr,     0L );
      mem.store64( rem_addr + 8, 0L );
    }
    return 0;
  }

  // exit(code)
  private long amd64_exit( long code ) {
    sysinfo.kernel.last_exit_code = (int)code;
    process.set_exit_flag();
    return 0;
  }

  // ioctl — 64-bit address version
  private long amd64_ioctl( long fd_l, long req, long addr ) {
    int fd = (int)fd_l, i;
    int request = (int)req;
    long address = addr;
    boolean done = false;
    Fileinfo finfo = get_finfo( fd );
    if( TCGETS == request ) {
      mem.store32( address, finfo.c_iflag  ); address+=4;
      mem.store32( address, finfo.c_oflag  ); address+=4;
      mem.store32( address, finfo.c_cflag  ); address+=4;
      mem.store32( address, finfo.c_lflag  ); address+=4;
      mem.store8 ( address, finfo.c_line   ); address+=1;
      for( i=0; i<19; i++ ) { mem.store8( address, finfo.c_cc[i] ); address++; }
      done = true;
    }
    if( TCSETS==request || TCSETSW==request ) {
      finfo.c_iflag = mem.load32( address ); address+=4;
      finfo.c_oflag = mem.load32( address ); address+=4;
      finfo.c_cflag = mem.load32( address ); address+=4;
      finfo.c_lflag = mem.load32( address ); address+=4;
      finfo.c_line  = mem.load8 ( address ); address+=1;
      for( i=0; i<19; i++ ) { finfo.c_cc[i]=mem.load8(address); address++; }
      if( isSTD(fd) || isERR(fd) )
        sysinfo.kernel.console.set_parameter( finfo.c_lflag, finfo.c_iflag, finfo.c_oflag, finfo.c_cc );
      done = true;
    }
    if( TIOCGWINSZ == request ) {
      mem.store16( address, (short)25 ); address+=2;
      mem.store16( address, (short)80 ); address+=2;
      mem.store16( address, (short)0  ); address+=2;
      mem.store16( address, (short)0  ); address+=2;
      done = true;
    }
    if( FIONBIO == request ) { done = true; }
    if( !done ) process.println( " Unsupported ioctl request=0x"+Integer.toHexString(request) );
    return 0;
  }

  // mmap(addr, len, prot, flags, fd, offset) — AMD64: 6 直接引数
  private long amd64_mmap( long addr, long length, long prot, long flags, long fd, long offset ) {
    long result = mem.alloc_and_map( addr, (int)length, (int)fd, (int)offset );
    return result;
  }

  // stat(path, buf) — AMD64 struct stat (144 bytes)
  private long amd64_stat( long path_addr, long buf_addr ) {
    String name = mem.loadString( path_addr );
    name = sysinfo.get_full_path( process.get_curdir(), name );
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    return 0;
  }

  // fstat(fd, buf) — AMD64 struct stat (144 bytes)
  private long amd64_fstat( long fd, long buf_addr ) {
    if( isSTD((int)fd) || isERR((int)fd) || isPIPE((int)fd) ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    String name = get_name( (int)fd );
    if( name == null ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir(), name );
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    return 0;
  }

  // AMD64 struct stat レイアウト (144 bytes):
  //   st_dev(8) st_ino(8) st_nlink(8) st_mode(4) st_uid(4) st_gid(4) __pad0(4)
  //   st_rdev(8) st_size(8) st_blksize(8) st_blocks(8)
  //   st_atime(8) st_atime_nsec(8) st_mtime(8) st_mtime_nsec(8)
  //   st_ctime(8) st_ctime_nsec(8) __unused[3](24)
  private void _set_file_stat64( long addr, Inode inode ) {
    mem.store64( addr,      inode.st_dev   & 0xFFFFL ); addr += 8;  // st_dev
    mem.store64( addr,      inode.st_ino   & 0xFFFFFFFFL ); addr += 8; // st_ino
    mem.store64( addr,      inode.st_nlink & 0xFFFFL ); addr += 8;  // st_nlink
    mem.store32( addr,      inode.st_mode  & 0xFFFF ); addr += 4;   // st_mode
    mem.store32( addr,      inode.st_uid   & 0xFFFF ); addr += 4;   // st_uid
    mem.store32( addr,      inode.st_gid   & 0xFFFF ); addr += 4;   // st_gid
    mem.store32( addr,      0              );           addr += 4;   // __pad0
    mem.store64( addr,      inode.st_rdev  & 0xFFFFL ); addr += 8;  // st_rdev
    mem.store64( addr,      inode.st_size  & 0xFFFFFFFFL ); addr += 8; // st_size
    mem.store64( addr,      inode.st_blksize & 0xFFFFFFFFL ); addr += 8; // st_blksize
    mem.store64( addr,      inode.st_blocks  & 0xFFFFFFFFL ); addr += 8; // st_blocks
    mem.store64( addr,      inode.st_atime & 0xFFFFFFFFL ); addr += 8; // st_atime
    mem.store64( addr,      0              ); addr += 8;              // st_atime_nsec
    mem.store64( addr,      inode.st_mtime & 0xFFFFFFFFL ); addr += 8; // st_mtime
    mem.store64( addr,      0              ); addr += 8;              // st_mtime_nsec
    mem.store64( addr,      inode.st_ctime & 0xFFFFFFFFL ); addr += 8; // st_ctime
    mem.store64( addr,      0              ); addr += 8;              // st_ctime_nsec
    mem.store64( addr,      0              ); addr += 8;              // __unused[0]
    mem.store64( addr,      0              ); addr += 8;              // __unused[1]
    mem.store64( addr,      0              );                         // __unused[2]
  }

  // arch_prctl(code, addr) — ARCH_SET_FS (0x1002) でFS baseを設定
  private static final int ARCH_SET_GS = 0x1001;
  private static final int ARCH_SET_FS = 0x1002;
  private static final int ARCH_GET_FS = 0x1003;
  private static final int ARCH_GET_GS = 0x1004;

  private long amd64_readlinkat( int dirfd, long path_addr, long buf_addr, int bufsiz ) {
    String path = mem.loadString( path_addr );
    String target = null;
    if( "/proc/self/exe".equals(path) || "/proc/self/fd/0".equals(path) ) {
      target = process.name; // argv[0] as executable path
    }
    if( target == null ) return ENOENT;
    byte[] b = target.getBytes();
    int len = Math.min(b.length, bufsiz);
    for(int i=0;i<len;i++) mem.store8(buf_addr+i, b[i]);
    return len;
  }

  private long amd64_arch_prctl( long code, long addr ) {
    if( (int)code == ARCH_SET_FS ) {
      ((Cpu64)process.cpu).fs_base = addr;
      return 0;
    }
    if( (int)code == ARCH_GET_FS ) {
      mem.store64( addr, ((Cpu64)process.cpu).fs_base );
      return 0;
    }
    // ARCH_SET_GS / ARCH_GET_GS: ignored
    return 0;
  }

  // TTY (stdin/stdout/stderr/pipe) 用の固定 stat
  private void _set_tty_stat64( long addr ) {
    mem.store64( addr, 0x16 );      addr += 8;  // st_dev (char dev)
    mem.store64( addr, 0x8BF2 );    addr += 8;  // st_ino
    mem.store64( addr, 1 );         addr += 8;  // st_nlink
    mem.store32( addr, 0x21B6 );    addr += 4;  // st_mode (S_IFCHR|0666)
    mem.store32( addr, 0 );         addr += 4;  // st_uid
    mem.store32( addr, 0 );         addr += 4;  // st_gid
    mem.store32( addr, 0 );         addr += 4;  // __pad0
    mem.store64( addr, 0x302 );     addr += 8;  // st_rdev (tty dev)
    mem.store64( addr, 0 );         addr += 8;  // st_size
    mem.store64( addr, 0x1000 );    addr += 8;  // st_blksize
    mem.store64( addr, 0 );         addr += 8;  // st_blocks
    // zero out remaining 6×8 + 3×8 = 72 bytes
    for( int i = 0; i < 9; i++ ) { mem.store64( addr, 0 ); addr += 8; }
  }
}
