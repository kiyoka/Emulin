// ----------------------------------------
//  SyscallAmd64: x86-64 Linux ABI ディスパッチ層
//
//  x86-64 ABI 固有部分:
//    - 番号テーブル (AMD64 unistd.h 準拠, i386 と全く別)
//    - call_amd64() ディスパッチ (RAX=sysno, RDI/RSI/RDX/R10/R8/R9=引数)
//    - 64-bit 固有実装: read/write (long アドレス), stat (AMD64 struct layout),
//      mmap (6 直接引数), writev (8-byte iov)
//    - それ以外は long のまま親クラス sys_* に委譲
//      (Phase 9 で Syscall.java を long 化したので int 切り詰めは不要)
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

    // --- 親クラス sys_* に long のまま委譲 ---
    // (Phase 9 で Syscall.java の sys_* シグネチャを long 化したので、
    //  高位スタックアドレスも切り詰められず正しく渡る。)
    if( n ==   2 ) return sys_open( a1, a2, a3, 0, 0 );
    if( n ==   3 ) return sys_close( a1, 0, 0, 0, 0 );
    if( n ==   8 ) return sys_lseek( a1, a2, a3, 0, 0 );
    if( n ==  10 ) return sys_mprotect( a1, a2, a3, 0, 0 );
    if( n ==  11 ) return sys_munmap( a1, a2, 0, 0, 0 );
    if( n ==  12 ) return sys_brk( a1, 0, 0, 0, 0 ) & 0xFFFFFFFFL;
    if( n ==  16 ) return amd64_ioctl( a1, a2, a3 );             // ioctl
    if( n ==  21 ) return sys_access( a1, a2, 0, 0, 0 );
    if( n ==  22 ) return amd64_pipe( a1 );
    if( n ==  23 ) return sys_select( a1, a2, a3, a4, a5 );
    if( n ==  25 ) return sys_mremap( a1, a2, a3, a4, 0 );
    if( n ==  32 ) return sys_dup( a1, 0, 0, 0, 0 );
    if( n ==  33 ) return sys_dup2( a1, a2, 0, 0, 0 );
    if( n ==  34 ) return sys_pause(   0, 0, 0, 0, 0 );
    if( n ==  35 ) return amd64_nanosleep( a1, a2 );
    if( n ==  37 ) return sys_alarm( a1, 0, 0, 0, 0 );
    if( n ==  39 ) return sys_getpid(  0, 0, 0, 0, 0 );
    if( n ==  57 ) return sys_fork(    0, 0, 0, 0, 0 );
    if( n ==  59 ) return amd64_execve( a1, a2, a3 );
    if( n ==  61 ) return amd64_wait4( a1, a2, a3, a4 );
    if( n ==  62 ) return amd64_kill( a1, a2 );
    if( n ==  63 ) return sys_uname( a1, 0, 0, 0, 0 );
    if( n ==  72 ) return sys_fcntl( a1, a2, a3, 0, 0 );
    if( n ==  73 ) return sys_flock( a1, a2, 0, 0, 0 );
    if( n ==  74 ) return sys_sync(    0, 0, 0, 0, 0 );
    if( n ==  77 ) return sys_ftruncate( a1, a2, 0, 0, 0 );
    if( n ==  78 ) return sys_getdents( a1, a2, a3, 0, 0 );
    if( n == 217 ) return amd64_getdents64( a1, a2, a3 );
    if( n ==  80 ) return sys_chdir( a1, 0, 0, 0, 0 );
    if( n ==  81 ) return sys_fchdir( a1, 0, 0, 0, 0 );
    if( n ==  82 ) return sys_rename( a1, a2, 0, 0, 0 );
    if( n ==  83 ) return sys_mkdir( a1, a2, 0, 0, 0 );
    if( n ==  84 ) return sys_rmdir( a1, 0, 0, 0, 0 );
    if( n ==  87 ) return sys_unlink( a1, 0, 0, 0, 0 );
    if( n ==  89 ) return sys_readlink( a1, a2, a3, 0, 0 );
    if( n ==  90 ) return sys_chmod( a1, a2, 0, 0, 0 );
    if( n ==  91 ) return sys_fchmod( a1, a2, 0, 0, 0 );
    if( n ==  92 ) return sys_chown( a1, a2, a3, 0, 0 );
    if( n ==  95 ) return sys_umask( a1, 0, 0, 0, 0 );
    if( n ==  96 ) return amd64_gettimeofday( a1, a2 );
    if( n ==  97 ) return sys_getrlimit( a1, a2, 0, 0, 0 );
    if( n ==  98 ) return 0;  // getrusage (stub)
    if( n == 100 ) return sys_times( a1, 0, 0, 0, 0 );
    if( n == 102 ) return sys_getuid(  0, 0, 0, 0, 0 );
    if( n == 104 ) return sys_getgid(  0, 0, 0, 0, 0 );
    if( n == 105 ) return sys_setuid( a1, 0, 0, 0, 0 );
    if( n == 106 ) return sys_setgid( a1, 0, 0, 0, 0 );
    if( n == 107 ) return sys_geteuid( 0, 0, 0, 0, 0 );
    if( n == 108 ) return sys_getegid( 0, 0, 0, 0, 0 );
    if( n == 109 ) return sys_setpgid( a1, a2, 0, 0, 0 );
    if( n == 110 ) return sys_getppid( 0, 0, 0, 0, 0 );
    if( n == 111 ) return sys_getpgrp( 0, 0, 0, 0, 0 );
    if( n == 112 ) return sys_setsid(  0, 0, 0, 0, 0 );
    if( n == 113 ) return 0;  // setreuid (stub)
    if( n == 115 ) return sys_getgroups( a1, a2, 0, 0, 0 );
    if( n == 121 ) return sys_getpgrp( 0, 0, 0, 0, 0 );  // getpgid → getpgrp
    if( n == 124 ) return sys_setsid(  0, 0, 0, 0, 0 );  // getsid → setsid stub
    if( n == 135 ) return sys_personality( a1, 0, 0, 0, 0 );
    if( n == 160 ) return sys_setrlimit( a1, a2, 0, 0, 0 );
    if( n == 161 ) return 0;  // chroot (stub)
    if( n == 162 ) return sys_sync(    0, 0, 0, 0, 0 );
    if( n == 165 ) return sys_mount( a1, a2, a3, a4, a5 );
    if( n == 166 ) return sys_umount( a1, 0, 0, 0, 0 );
    if( n == 170 ) return 0;  // sethostname (stub)

    // --- 追加スタブ (glibc 静的リンクバイナリ起動に必要) ---
    if( n ==  13 ) return amd64_rt_sigaction( a1, a2, a3 );
    if( n ==  15 ) return amd64_rt_sigreturn( );
    if( n ==  14 ) return 0;  // rt_sigprocmask (stub)
    if( n ==  28 ) return 0;  // madvise (stub)
    if( n == 158 ) return amd64_arch_prctl( a1, a2 );  // arch_prctl
    if( n == 157 ) return amd64_prctl( a1, a2 );
    if( n == 201 ) {
      // time(t): 秒単位の現在時刻。a1 が non-null ならそこへも書き込む。
      long sec = System.currentTimeMillis() / 1000L;
      if( a1 != 0 ) mem.store64( a1, sec );
      return sec;
    }
    if( n == 218 ) return sys_getpid(0,0,0,0,0);       // set_tid_address → pid
    if( n == 228 ) return 0;  // clock_gettime (stub)
    if( n == 231 ) return amd64_exit( a1 );             // exit_group (already above, but guard)
    if( n == 302 ) return 0;  // prlimit64 (stub)
    if( n == 318 ) return ENOSYS; // getrandom → ENOSYS (glibc falls back)
    if( n ==  40 ) return ENOSYS; // sendfile → ENOSYS (busybox cat falls back to read+write)
    if( n == 186 ) return sys_getpid( 0, 0, 0, 0, 0 );  // gettid → pid
    if( n == 234 ) return 0;  // tgkill (stub)
    if( n == 257 ) return sys_open( a2, a3, a4, 0, 0 );  // openat(dirfd, path, flags, mode) → dirfd 無視
    if( n == 262 ) return amd64_newfstatat( (int)a1, a2, a3, (int)a4 ); // newfstatat
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

  // kill(pid, sig)
  // 対象 pid を ptable から探してシグナルを recv() させる。
  // pid が存在しなければ -ESRCH を返す。
  private long amd64_kill( long pid_l, long sig_l ) {
    int target_pid = (int)pid_l;
    int sig = (int)sig_l;
    if( target_pid <= 0 ) target_pid = process.pid; // pid<=0 は self へ送信 (簡易実装)
    Process target = sysinfo.kernel.find_process( target_pid );
    if( target == null ) return -3L; // -ESRCH
    if( sig > 0 ) target.recv( sig );
    return 0;
  }

  // rt_sigaction(signum, &act, &oldact)
  //   act struct: sa_handler (8 bytes) at offset 0
  // oldact が non-null なら旧ハンドラを書き戻す。
  private long amd64_rt_sigaction( long signum, long act_addr, long oldact_addr ) {
    int sn = (int)signum;
    if( sn < 0 || sn >= 32 ) return -22L; // -EINVAL
    if( oldact_addr != 0 ) {
      mem.store64( oldact_addr, process.get_func_adrs( sn ) );
    }
    if( act_addr != 0 ) {
      long handler = mem.load64( act_addr );
      process.set_sigaction( sn, handler );
    }
    return 0;
  }

  // rt_sigreturn — シグナルハンドラから戻る。
  // 我々は「ハンドラ進入時に rip を push」する単純実装なので、
  // ret 命令で復帰できるため、rt_sigreturn は通常使われない。
  // glibc のシグナル配信経路 (sa_restorer) と互換にするためのスタブ。
  private long amd64_rt_sigreturn( ) {
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

  // getdents64(fd, dirp, count) — AMD64 dirent64 レイアウト
  //   __u64 d_ino     (offset 0,  8 bytes)
  //   __s64 d_off     (offset 8,  8 bytes)
  //   __u16 d_reclen  (offset 16, 2 bytes)
  //   __u8  d_type    (offset 18, 1 byte) — DT_UNKNOWN(0) でもとりあえず動く
  //   char  d_name[]  (offset 19, NULL 終端)
  private long amd64_getdents64( long fd_l, long dirp, long count_l ) {
    int fd = (int)fd_l;
    int count = (int)count_l;
    String name = get_name( fd );
    if( name == null ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    String[] list = file_list( name );
    int start = get_ptr( fd );      // 前回の途中位置 (バイトオフセット)
    long d_off = 0;
    long w_size = 0;
    long address = dirp;
    String dir_with_slash = ('/' != name.charAt( name.length( )-1 )) ? name + "/" : name;

    for( int i = 0; i < list.length; i++ ) {
      String d_name = list[i];
      // header 19 bytes + name + NUL, 8 バイトアライメント
      int memlen = 19 + d_name.length() + 1;
      int reclen = (memlen + 7) & ~7;
      long old_d_off = d_off;
      d_off += reclen;
      if( count < d_off ) break;
      if( start <= old_d_off ) {
        Inode inode = new Inode( dir_with_slash + d_name, sysinfo );
        int d_type = 0; // DT_UNKNOWN. 厳密には inode.st_mode から判定すべき
        if( inode.isDirectory() ) d_type = 4; // DT_DIR
        else if( inode.isExists() ) d_type = 8; // DT_REG
        mem.store64( address +  0, inode.st_ino & 0xFFFFFFFFL );
        mem.store64( address +  8, d_off );
        mem.store16( address + 16, (short)reclen );
        mem.store8 ( address + 18, d_type );
        mem.storeString( address + 19, d_name );
        // storeString は終端 NUL も書く想定。残りはゼロ埋め
        for( int p = 19 + d_name.length() + 1; p < reclen; p++ ) {
          mem.store8( address + p, 0 );
        }
        w_size += reclen;
        address = dirp + w_size;
      }
    }
    set_ptr( fd, (int)d_off );
    return w_size;
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

  // prctl(option, arg2, ...) — 主要な操作のみ実装
  //   PR_SET_NAME (15): プロセス名をセット (arg2 から最大 16 バイト読み込み)
  //   PR_GET_NAME (16): プロセス名を arg2 へ書き込む (最大 16 バイト, NULL 終端)
  private static final int PR_SET_NAME = 15;
  private static final int PR_GET_NAME = 16;
  private long amd64_prctl( long option, long arg2 ) {
    int op = (int)option;
    if( op == PR_GET_NAME && arg2 != 0 ) {
      String name = process.name != null ? process.name : "";
      // basename を取り出す
      int slash = name.lastIndexOf('/');
      if( slash >= 0 ) name = name.substring( slash + 1 );
      byte[] b = name.getBytes();
      int n = Math.min( b.length, 15 );  // 16 - 1 (NULL) = 15
      for( int i = 0; i < n; i++ ) mem.store8( arg2 + i, b[i] );
      mem.store8( arg2 + n, 0 );  // NULL 終端
      return 0;
    }
    if( op == PR_SET_NAME ) {
      // 必要なら process.name を更新する。今は無視。
      return 0;
    }
    return 0; // その他は no-op
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

  // newfstatat(dirfd, path, buf, flags) — dirfd は AT_FDCWD のみサポート
  // AT_EMPTY_PATH (0x1000) のときは fd 自身を fstat する
  private long amd64_newfstatat( int dirfd, long path_addr, long buf_addr, int flags ) {
    final int AT_EMPTY_PATH = 0x1000;
    String path = (path_addr != 0) ? mem.loadString( path_addr ) : "";
    if( (flags & AT_EMPTY_PATH) != 0 || path.isEmpty() ) {
      return amd64_fstat( (long)dirfd, buf_addr );
    }
    // 絶対パスでも相対パスでも sys_stat と同じ扱い (dirfd 非対応)
    String name = sysinfo.get_full_path( process.get_curdir(), path );
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
