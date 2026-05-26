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
  // Phase 30: env-gated debug flags を起動時 1 回だけ評価する。
  // 旧実装は wait4 / execve / read 等の hot path で毎回 System.getenv()
  // を呼んでおり、HashMap lookup の overhead で並列回帰テストが timing
  // flake していた。cache すれば wait4 の throughput が回復する。
  private static final boolean TRACE_EXEC = System.getenv("EMULIN_TRACE_EXEC") != null;

  // Phase 31: syscall 別累積時間プロファイラ。EMULIN_PROFILE_SYS=1 で有効化。
  // shutdown hook で「count, total_ns, avg_ns」を sysno ごとに stderr に dump。
  // I/O / kernel emulation がボトルネックかを切り分けるための一次計測。
  private static final boolean PROFILE_SYS = System.getenv("EMULIN_PROFILE_SYS") != null;
  private static final long[] PROFILE_COUNT = new long[ 512 ];
  private static final long[] PROFILE_TOTAL_NS = new long[ 512 ];
  private static volatile long PROFILE_FIRST_NS = 0L;
  private static volatile long PROFILE_LAST_NS = 0L;
  // issue #3-#5: pselect6 が ready=1 で return した後の syscall を 20 件 log
  private int debug_sys_after_pselect = 0;
  static {
    if( PROFILE_SYS ) {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        long total = 0L;
        long count = 0L;
        for( int i = 0; i < PROFILE_TOTAL_NS.length; i++ ) {
          total += PROFILE_TOTAL_NS[ i ];
          count += PROFILE_COUNT[ i ];
        }
        long span = ( PROFILE_FIRST_NS == 0L || PROFILE_LAST_NS == 0L )
          ? 0L : ( PROFILE_LAST_NS - PROFILE_FIRST_NS );
        System.err.println( "===== EMULIN_PROFILE_SYS =====" );
        System.err.println( String.format(
          "syscall total: count=%d total=%.3fms span=%.3fms (= time between first and last syscall)",
          count, total / 1e6, span / 1e6 ) );
        // top entries by total time
        Integer[] idx = new Integer[ 512 ];
        for( int i = 0; i < 512; i++ ) idx[ i ] = i;
        java.util.Arrays.sort( idx, ( a, b )
          -> Long.compare( PROFILE_TOTAL_NS[ b ], PROFILE_TOTAL_NS[ a ] ) );
        System.err.println( "  sysno    count       total_ms    avg_us  pct" );
        for( int k = 0; k < 25; k++ ) {
          int sn = idx[ k ];
          long c = PROFILE_COUNT[ sn ];
          if( c == 0 ) break;
          long t = PROFILE_TOTAL_NS[ sn ];
          double pct = total > 0 ? ( 100.0 * t / total ) : 0.0;
          System.err.println( String.format(
            "  %5d  %8d  %12.3f  %8.2f  %5.1f%%",
            sn, c, t / 1e6, ( t / 1000.0 ) / c, pct ) );
        }
        System.err.println( "==============================" );
      }, "EmulinProfileSysDump" ) );
    }
  }

  SyscallAmd64( Sysinfo _sysinfo, Process _process ) {
    super( _sysinfo, _process );
  }

  // issue #98 (difftrace): EMULIN_DET_RANDOM=1 で getrandom / /dev/urandom を
  //   固定 seed の決定的乱数にする。命令単位 diff には emulin が run 間で
  //   決定的に実行される必要があるが、既定の ThreadLocalRandom は run ごとに
  //   別系列を返すため node の実行経路が毎回変わり diff が成立しない。host
  //   側も同じ系列を返すよう揃えれば (hosttrace の getrandom intercept 等)、
  //   両者を命令単位で突き合わせられる。既定 (env 無し) は従来どおり真の乱数。
  private static final boolean DET_RANDOM = System.getenv("EMULIN_DET_RANDOM") != null;
  private static final java.util.Random DET_RNG = new java.util.Random( 0xC0FFEEL );
  public static synchronized void fillRandom( byte[] b ) {
    if( DET_RANDOM ) DET_RNG.nextBytes( b );
    else             java.util.concurrent.ThreadLocalRandom.current().nextBytes( b );
  }
  // issue #113: EMULIN_DET_CLOCK=1 で時刻系 syscall (clock_gettime/gettimeofday/
  //   time) を決定的な単調カウンタ (1µs/call) にし、DET_RANDOM と併用で run を
  //   完全決定化する (既定 off では従来どおり実時刻)。
  static final boolean DET_CLOCK = System.getenv("EMULIN_DET_CLOCK") != null;
  private static final java.util.concurrent.atomic.AtomicLong DET_CLOCK_US =
      new java.util.concurrent.atomic.AtomicLong( 1700000000000000L );  // 固定 base (~2023-11) µs
  static long detClockUs() { return DET_CLOCK_US.getAndAdd( 1L ); }  // µs、呼ぶたび 1µs 進む

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
    boolean trace = System.getenv("EMULIN_TRACE_SH") != null;
    if( trace ) {
      System.err.println("DBG[pid="+process.pid+"] syscall #"+n+" a1=0x"+Long.toHexString(a1)+" a2=0x"+Long.toHexString(a2)+" a3=0x"+Long.toHexString(a3)+" a4=0x"+Long.toHexString(a4));
      System.err.flush();
    }
    // issue #3-#5: pselect6 が ready=1 で return した後、emacs が next pselect
    // までに何をしているかを見るための短い trace。Ctrl-X を 1 回押すと
    // pselect6 が ready 返却 → expect=20 → 次の 20 syscalls を log。
    if( debug_sys_after_pselect > 0 && System.getenv("EMULIN_DEBUG_TTY") != null ) {
      debug_sys_after_pselect--;
      System.err.println("DBG_SYS_AFTER_PSELECT #"+n+" a1=0x"+Long.toHexString(a1)+" a2=0x"+Long.toHexString(a2)+" a3=0x"+Long.toHexString(a3));
    }
    long t0 = PROFILE_SYS ? System.nanoTime() : 0L;
    if( PROFILE_SYS && PROFILE_FIRST_NS == 0L ) PROFILE_FIRST_NS = t0;
    long ret = call_amd64_impl( n, a1, a2, a3, a4, a5, a6 );
    if( PROFILE_SYS ) {
      long t1 = System.nanoTime();
      PROFILE_LAST_NS = t1;
      if( n >= 0 && n < PROFILE_TOTAL_NS.length ) {
        PROFILE_TOTAL_NS[ n ] += ( t1 - t0 );
        PROFILE_COUNT[ n ] += 1;
      }
    }
    if( trace ) {
      System.err.println("DBG[pid="+process.pid+"]  ret #"+n+" = 0x"+Long.toHexString(ret)+" ("+ret+")");
      System.err.flush();
    }
    return ret;
  }

  private long call_amd64_impl( int n, long a1, long a2, long a3, long a4, long a5, long a6 ) {

    // --- 64-bit 固有実装が必要なもの ---
    if( n ==   0 ) return amd64_read(   a1, a2, a3 );       // read
    if( n ==   1 ) return amd64_write(  a1, a2, a3 );       // write
    if( n ==   4 ) return amd64_stat(   a1, a2 );            // stat
    if( n ==   5 ) return amd64_fstat(  a1, a2 );            // fstat
    if( n ==   6 ) return amd64_lstat(  a1, a2 );            // lstat (Phase 33-9: symlink を follow しない)
    if( n ==   9 ) return amd64_mmap(   a1, a2, a3, a4, a5, a6 ); // mmap
    if( n ==  20 ) return amd64_writev( a1, a2, a3 );       // writev
    if( n ==  60 ) return amd64_exit_thread( a1 );          // exit (per-thread)
    if( n == 231 ) return amd64_exit(   a1 );                // exit_group (whole process)

    // --- 親クラス sys_* に long のまま委譲 ---
    // (Phase 9 で Syscall.java の sys_* シグネチャを long 化したので、
    //  高位スタックアドレスも切り詰められず正しく渡る。)
    if( n ==   2 ) return sys_open( a1, a2, a3, 0, 0 );
    if( n ==   3 ) return sys_close( a1, 0, 0, 0, 0 );
    if( n ==   8 ) return sys_lseek( a1, a2, a3, 0, 0 );
    if( n ==  10 ) return sys_mprotect( a1, a2, a3, 0, 0 );
    if( n ==  11 ) return sys_munmap( a1, a2, 0, 0, 0 );
    if( n ==  17 ) return amd64_pread64( a1, a2, a3, a4 );  // pread64
    if( n ==  53 ) return amd64_socketpair( a1, a2, a3, a4 );  // socketpair
    if( n ==  12 ) return sys_brk( a1, 0, 0, 0, 0 );
    if( n ==  16 ) return amd64_ioctl( a1, a2, a3 );             // ioctl
    if( n ==  21 ) return sys_access( a1, a2, 0, 0, 0 );
    if( n ==  22 ) return amd64_pipe( a1, 0 );
    if( n == 293 ) return amd64_pipe( a1, a2 );  // pipe2(fd[2], flags) — O_NONBLOCK のみ反映
    if( n ==  23 ) return sys_select( a1, a2, a3, a4, a5 );
    if( n ==   7 ) return amd64_poll( a1, a2, a3 );  // poll
    // ppoll(fds, nfds, timespec*, sigmask, sigsetsize) — pselect6 の poll 版。
    // emacs/glibc 2.34+ が pselect を内部で ppoll に置換することがあるので
    // 別 entry を用意する (timespec → ms 変換 → amd64_poll)。
    if( n == 271 ) {
      long timeout_ms = -1L;  // NULL → 無限
      if( a3 != 0 ) {
        long sec  = mem.load64( a3 );
        long nsec = mem.load64( a3 + 8 );
        timeout_ms = sec * 1000L + nsec / 1_000_000L;
      }
      if( System.getenv("EMULIN_DEBUG_TTY") != null )
        System.err.println("DBG_PPOLL nfds="+a2+" timeout_ms="+timeout_ms);
      return amd64_poll( a1, a2, timeout_ms );
    }
    if( n ==  25 ) return amd64_mremap( a1, a2, a3, a4, a5 );
    if( n ==  24 ) return 0;  // sched_yield: CPU を譲るヒント、no-op で成功
    if( n ==  32 ) return sys_dup( a1, 0, 0, 0, 0 );
    if( n ==  33 ) return sys_dup2( a1, a2, 0, 0, 0 );
    if( n == 292 ) return amd64_dup3( a1, a2, a3 );  // dup3(oldfd, newfd, flags)
    if( n ==  56 ) {
      // clone(flags, child_stack, ptid, ctid, tls) — flags は a1。
      //   CLONE_VM (0x100) | CLONE_THREAD (0x10000) が立っていれば pthread
      //   生成 = アドレス空間共有スレッド。Phase 27 step 28 で真対応した
      //   (Java Thread + 新 Cpu64 + Memory/Syscall 共有)。
      if( (a1 & 0x10100L) == 0x10100L ) {
        return amd64_clone_thread( a1, a2, a3, a4, a5 );
      }
      // clone(CLONE_VM|CLONE_VFORK, child_stack, ...): posix_spawn 経路。
      //   child_stack (a2) を子の rsp に設定する (CLONE_THREAD 無しなので
      //   pthread ではなく fork 相当だが、子は専用 stack を要求する)。
      return sysinfo.kernel.fork( process, a2 );
    }
    if( n ==  57 ) return sys_fork( 0, 0, 0, 0, 0 );    // fork
    if( n ==  58 ) return sys_fork( 0, 0, 0, 0, 0 );    // vfork — fork 相当 (本来は親 block するが、無視)
    if( n ==  34 ) return sys_pause(   0, 0, 0, 0, 0 );
    if( n ==  35 ) return amd64_nanosleep( a1, a2 );
    if( n ==  37 ) return process.set_alarm( a1 );  // alarm — Phase 27 step 23 で真対応
    if( n ==  39 ) return sys_getpid(  0, 0, 0, 0, 0 );
    if( n ==  59 ) return amd64_execve( a1, a2, a3 );
    if( n ==  61 ) return amd64_wait4( a1, a2, a3, a4 );
    if( n ==  62 ) return amd64_kill( a1, a2 );
    if( n ==  63 ) return sys_uname( a1, 0, 0, 0, 0 );
    if( n ==  72 ) return sys_fcntl( a1, a2, a3, 0, 0 );
    if( n ==  73 ) return sys_flock( a1, a2, 0, 0, 0 );
    if( n ==  74 ) return sys_sync(    0, 0, 0, 0, 0 );
    if( n ==  77 ) return sys_ftruncate( a1, a2, 0, 0, 0 );
    if( n ==  78 ) return sys_getdents( a1, a2, a3, 0, 0 );
    if( n ==  79 ) return amd64_getcwd( a1, a2 );
    if( n == 217 ) return amd64_getdents64( a1, a2, a3 );
    if( n ==  80 ) return sys_chdir( a1, 0, 0, 0, 0 );
    if( n ==  81 ) return sys_fchdir( a1, 0, 0, 0, 0 );
    if( n ==  82 ) return sys_rename( a1, a2, 0, 0, 0 );
    if( n ==  83 ) return sys_mkdir( a1, a2, 0, 0, 0 );
    // mkdirat(dirfd, pathname, mode) — Phase 28-3h:
    //   AT_FDCWD or 絶対パスは sys_mkdir と同じ動作。dirfd が実在 fd の場合は
    //   その path を取得して relative path と結合する (cp -r が使う経路)。
    if( n == 258 ) return amd64_mkdirat( (int)a1, a2, (int)a3 );
    if( n ==  84 ) return sys_rmdir( a1, 0, 0, 0, 0 );
    if( n ==  87 ) return sys_unlink( a1, 0, 0, 0, 0 );
    // unlinkat(dirfd, path, flags) — Phase 28-3j で dirfd 解決対応。
    //   AT_REMOVEDIR (0x200) は rmdir 経路 (現状 sys_rmdir = sys_unlink alias)。
    if( n == 263 ) return amd64_unlinkat( (int)a1, a2, (int)a3 );
    if( n == 264 ) return amd64_renameat( (int)a1, a2, (int)a3, a4 );  // renameat
    if( n == 316 ) return amd64_renameat( (int)a1, a2, (int)a3, a4 );  // renameat2 (flags 無視)
    // readlink(path, buf, bufsiz) → readlinkat(AT_FDCWD, path, buf, bufsiz)
    //   issue #41 Phase 2: 旧 sys_readlink は EINVAL stub だったため、
    //   ttyname(3) が /proc/self/fd/N readlink で fail → pty 起動不能。
    //   amd64_readlinkat に redirect して /proc/self/exe / /proc/self/fd/N
    //   special case と通常 symlink を統一して扱う。
    if( n ==  89 ) return amd64_readlinkat( (int)0xffffff9c /*AT_FDCWD*/, a1, a2, (int)a3 );
    if( n ==  90 ) return sys_chmod( a1, a2, 0, 0, 0 );
    if( n ==  91 ) return sys_fchmod( a1, a2, 0, 0, 0 );
    if( n == 268 ) return amd64_fchmodat( (int)a1, a2, a3, a4 );  // fchmodat(dirfd,path,mode,flags)
    if( n ==  92 ) return sys_chown( a1, a2, a3, 0, 0 );
    if( n ==  95 ) return sys_umask( a1, 0, 0, 0, 0 );
    if( n ==  96 ) return amd64_gettimeofday( a1, a2 );
    if( n ==  97 ) return sys_getrlimit( a1, a2, 0, 0, 0 );
    if( n ==  98 ) return 0;  // getrusage (stub)
    if( n == 100 ) return sys_times( a1, 0, 0, 0, 0 );
    if( n == 102 ) return sys_getuid(  0, 0, 0, 0, 0 );
    if( n == 104 ) return sys_getgid(  0, 0, 0, 0, 0 );
    // issue #41 (sshd): setuid/setgid paranoia — non-root が root (0) に
    //   戻ろうとするのは EPERM。sshd の permanently_set_uid は
    //   setresuid 後に setgid(0) で「root に戻れないこと」を確認する。
    if( n == 105 ) {  // setuid(uid)
      int target = (int)a1;
      if( process.uid != 0 && target == 0 ) return -1L;  // EPERM
      return sys_setuid( a1, 0, 0, 0, 0 );
    }
    if( n == 106 ) {  // setgid(gid)
      int target = (int)a1;
      if( process.gid != 0 && target == 0 ) return -1L;  // EPERM
      return sys_setgid( a1, 0, 0, 0, 0 );
    }
    if( n == 107 ) return sys_geteuid( 0, 0, 0, 0, 0 );
    if( n == 108 ) return sys_getegid( 0, 0, 0, 0, 0 );
    if( n == 109 ) return sys_setpgid( a1, a2, 0, 0, 0 );
    if( n == 110 ) return amd64_getppid();
    if( n == 111 ) return sys_getpgrp( 0, 0, 0, 0, 0 );
    if( n == 112 ) return sys_setsid(  0, 0, 0, 0, 0 );
    if( n == 113 ) return 0;  // setreuid (stub)
    if( n == 114 ) return 0;  // setregid (stub)
    if( n == 115 ) return sys_getgroups( a1, a2, 0, 0, 0 );
    // issue #41 (sshd): setresuid/getresuid/setresgid/getresgid (117-120)。
    //   sshd の privsep child は permanently_set_uid で setresgid → setresuid
    //   を順に呼んだ後、paranoia check として seteuid(0) / setegid(0) を呼ぶ
    //   (= glibc は setresuid(-1,0,-1) / setresgid(-1,0,-1) に変換)。
    //   この check が「成功した」と判定されると sshd は privsep 破綻と見做して
    //   fatal exit する ("permanently_set_uid: was able to restore old [e]gid")。
    //   そのため emulator は process.uid/gid を実際に追跡し、once non-root に
    //   切り替わったら root (uid/gid=0) への戻しは EPERM を返す。
    //   -1 (= 0xFFFFFFFF) は「変更なし」を意味する POSIX 仕様。
    if( n == 117 ) {  // setresuid(ruid, euid, suid)
      int ruid = (int)a1, euid = (int)a2, suid = (int)a3;
      int cur = process.uid;
      int target = (euid != -1) ? euid : (ruid != -1) ? ruid : (suid != -1) ? suid : cur;
      if( cur != 0 && target == 0 ) return -1L;  // EPERM (paranoia)
      process.uid = target;
      return 0;
    }
    if( n == 119 ) {  // setresgid(rgid, egid, sgid)
      int rgid = (int)a1, egid = (int)a2, sgid = (int)a3;
      int cur = process.gid;
      int target = (egid != -1) ? egid : (rgid != -1) ? rgid : (sgid != -1) ? sgid : cur;
      if( cur != 0 && target == 0 ) return -1L;  // EPERM
      process.gid = target;
      return 0;
    }
    if( n == 118 ) {           // getresuid(ruid*, euid*, suid*)
      int u = sysinfo.get_default_uid();
      if( a1 != 0 ) mem.store32( a1, u );
      if( a2 != 0 ) mem.store32( a2, u );
      if( a3 != 0 ) mem.store32( a3, u );
      return 0;
    }
    if( n == 120 ) {           // getresgid(rgid*, egid*, sgid*)
      int g = sysinfo.get_default_gid();
      if( a1 != 0 ) mem.store32( a1, g );
      if( a2 != 0 ) mem.store32( a2, g );
      if( a3 != 0 ) mem.store32( a3, g );
      return 0;
    }
    // issue #41 (sshd): setgroups (116) — supplementary group list を変更。
    //   実 Linux では root のみ可。emulator は単一ユーザ root として動くので
    //   無条件に success (0) を返す。sshd の privsep child は setgroups()
    //   失敗 (EPERM) で fatal exit するため必須。x86-64 syscall 表で 116 は
    //   syslog/klogctl ではなく setgroups (klogctl は 103)。
    if( n == 116 ) return 0;
    // syslog/klogctl (103) — kernel log read/control。sshd は audit 目的で
    //   呼ぶが emulator では kernel log を持たない。EPERM を返すと許容して続行。
    if( n == 103 ) return -1L;  // EPERM
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
    if( n ==  14 ) return amd64_rt_sigprocmask( a1, a2, a3, a4 );
    if( n ==  28 ) return 0;  // madvise (stub)
    // issue #10: mlock / munlock / mlockall / munlockall / mlock2。
    //   GnuPG (gpg / gpgsm / pinentry) は秘密鍵 page を swap 対象外に
    //   するため mlock を呼ぶ。emulin は swap しないので成功扱い (0) で
    //   stub する。実 memory protection は不要。
    if( n == 149 || n == 150 || n == 151 || n == 152 || n == 325 ) return 0;
    if( n == 158 ) return amd64_arch_prctl( a1, a2 );  // arch_prctl
    if( n == 157 ) return amd64_prctl( a1, a2 );
    if( n == 201 ) {
      // time(t): 秒単位の現在時刻。a1 が non-null ならそこへも書き込む。
      long sec = DET_CLOCK ? (detClockUs() / 1_000_000L) : (System.currentTimeMillis() / 1000L);  // issue #113
      if( a1 != 0 ) mem.store64( a1, sec );
      return sec;
    }
    if( n == 218 ) return sys_getpid(0,0,0,0,0);       // set_tid_address → pid
    if( n == 228 ) return amd64_clock_gettime( a1, a2 );
    if( n == 229 ) return amd64_clock_getres(  a1, a2 );
    if( n == 230 ) return amd64_clock_nanosleep( a1, a2, a3, a4 );
    // pselect6 / select: 簡易には「全 fd ready」で即返す。本来は fd セット
    //   から readable/writable な fd だけビットを立てるべきだが、
    //   blocking 系 socket では大抵そのまま動く。
    if( n == 270 ) return amd64_pselect6( a1, a2, a3, a4, a5 );
    if( n == 231 ) return amd64_exit( a1 );             // exit_group (whole process)
    if( n == 302 ) return amd64_prlimit64( a2, a3, a4 );  // prlimit64(pid, resource, new, old)
    // getrandom(buf, buflen, flags): Python 等は ENOSYS だと fatal で死ぬので
    //   実際に Java の Random でバッファを埋めて要求量返す。
    //   暗号品質は不要 (hash randomization 用程度の用途)。
    // issue #9: glibc 2.36+ の arc4random() は getrandom(buf, 4, 0) を毎回
    //   syscall するため、call 数が 1000+ になる。`new java.util.Random()` を
    //   syscall ごとに alloc していた旧実装は 1 call ~3ms で総和 5-10s の
    //   時間を消費し ssh-keyscan が timeout する原因になっていた。
    //   thread-safe な ThreadLocalRandom を使い alloc / 競合を排除する。
    if( n == 318 ) {
      long buf = a1; int len = (int)a2;
      byte[] bytes = new byte[ Math.max(0, len) ];
      fillRandom( bytes );
      // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
      mem.bulkStoreToMem( buf, bytes, 0, bytes.length );
      return len;
    }
    if( n ==  40 ) return ENOSYS; // sendfile → ENOSYS (busybox cat falls back to read+write)
    if( n == 186 ) {
      // gettid: thread-specific TID。pthread spawn 時は Thread64.tid を、
      //   メイン thread は process.pid を返す。Phase 27 step 28。
      Thread cur = Thread.currentThread();
      if( cur instanceof Thread64 ) return ((Thread64)cur).tid;
      return sys_getpid( 0, 0, 0, 0, 0 );
    }
    if( n == 234 ) return amd64_tgkill( a1, a2, a3 );  // tgkill(tgid, tid, sig)
    // clone3 (#435): glibc は ENOSYS を返すと clone (#56 = sys_fork) に
    // フォールバックする。Phase 25 では真のスレッド (CLONE_VM 共有メモリ) は
    // 未対応なので、まずは ENOSYS を返してプロセス分離 fork ベースで進める。
    if( n == 435 ) return -38L;  // -ENOSYS
    // futex(uaddr, op, val, timeout|val2, uaddr2, val3) — pthread 同期用。
    //   FUTEX_WAIT (0):  *uaddr == val なら timeout まで block、不一致は EAGAIN
    //   FUTEX_WAKE (1):  uaddr の waiter を val 個 wake、wake count を返す
    //   それ以外: ENOSYS で諦めさせる (PI lock 等)
    //   Phase 27 step 28 で真対応。pthread を実装するまでは大半 no-op で
    //   通っていたが、CLONE_VM スレッドが入ると実際の wait/wake が必要に。
    if( n == 202 ) return amd64_futex( a1, a2, a3, a4 );
    if( n == 257 ) return amd64_openat( (int)a1, a2, a3, a4 );  // openat(dirfd, path, flags, mode)
    if( n == 262 ) return amd64_newfstatat( (int)a1, a2, a3, (int)a4 ); // newfstatat
    if( n == 267 ) return amd64_readlinkat( (int)a1, a2, a3, (int)a4 ); // readlinkat (Phase 28-3j で dirfd 対応)
    if( n == 273 ) return 0;  // set_robust_list (stub)
    if( n == 334 ) return 0;  // rseq (stub)
    if( n ==  86 ) return amd64_link( a1, a2 );         // link(oldpath, newpath)
    if( n ==  88 ) return amd64_symlink( a1, a2 );      // symlink(target, linkpath)
    if( n == 265 ) return amd64_linkat( (int)a1, a2, (int)a3, a4 );    // linkat (Phase 28-3j: dirfd 対応)
    if( n == 266 ) return amd64_symlinkat( a1, (int)a2, a3 );          // symlinkat(target, newdirfd, linkpath)
    if( n == 280 ) return amd64_utimensat( (int)a1, a2, a3, (int)a4 ); // utimensat
    // issue #9: chown 系は emulator では actual な ownership 変更を行わず、
    //   常に成功を返す no-op stub。emulin の sandbox は単一 root ユーザ前提で
    //   ssh の StrictModes / .ssh ownership は _fixup_stat_mode で root 偽装。
    //   chown(2) を呼ぶ実機 binary (例: bash chown command、git checkout の
    //   restore) が ENOSYS で abort するのを回避する。
    if( n ==  92 ) return 0;  // chown(path, uid, gid)
    if( n ==  93 ) return 0;  // fchown(fd, uid, gid)
    if( n ==  94 ) return 0;  // lchown(path, uid, gid)
    if( n == 260 ) return 0;  // fchownat(dirfd, path, uid, gid, flags)
    if( n == 132 ) return 0;  // utime (stub: 成功扱い)
    if( n == 235 ) return 0;  // utimes (stub)
    // faccessat2(dirfd, path, mode, flags) — bash の heredoc tmpfile 等で必要。
    //   AT_FDCWD のみ対応。flags 無視。
    if( n == 439 ) return sys_access( a2, a3, 0, 0, 0 );
    // statfs / fstatfs: ls / df 等が呼ぶ。FS 情報を聞いているだけなので
    //   ENOSYS で返すと caller が fall back する場合が多い。
    if( n == 137 ) return -38L; // statfs → ENOSYS
    if( n == 138 ) return -38L; // fstatfs → ENOSYS
    // statx: glibc は ENOSYS で newfstatat に fall back する
    if( n == 332 ) return -38L; // statx → ENOSYS
    // socket / connect / sendto / recvfrom 等: AF_INET TCP を Java の Socket で
    //   実装する。AF_UNIX や AF_INET 以外は EAFNOSUPPORT。
    if( n == 41 ) return amd64_socket( a1, a2, a3 );    // socket
    if( n == 42 ) return amd64_connect( a1, a2, a3 );   // connect
    if( n == 44 ) return amd64_sendto( a1, a2, a3, a4, a5, a6 ); // sendto
    if( n == 45 ) return amd64_recvfrom( a1, a2, a3, a4, a5, a6 ); // recvfrom
    if( n == 46 ) return amd64_sendmsg( a1, a2, a3 );   // sendmsg
    if( n == 47 ) return amd64_recvmsg( a1, a2, a3 );   // recvmsg
    if( n == 307 ) return amd64_sendmmsg( a1, a2, a3, a4 );  // sendmmsg
    if( n == 299 ) return amd64_recvmmsg( a1, a2, a3, a4, a5 );  // recvmmsg
    if( n == 48 ) return 0;                              // shutdown — close で十分
    if( n == 49 ) return amd64_bind( a1, a2, a3 );      // bind
    if( n == 50 ) return amd64_listen( a1, a2 );        // listen
    if( n == 43 ) return amd64_accept4( a1, a2, a3, 0 ); // accept (= accept4 with flags=0)
    if( n == 288 ) return amd64_accept4( a1, a2, a3, a4 ); // accept4
    if( n == 54 ) return 0;                              // setsockopt — no-op
    if( n == 55 ) return amd64_getsockopt( a1, a2, a3, a4, a5 ); // getsockopt
    if( n == 51 ) return amd64_getsockname( a1, a2, a3 );  // getsockname
    if( n == 52 ) return amd64_getpeername( a1, a2, a3 );  // getpeername
    // setitimer / getitimer: ITIMER_REAL は SIGALRM、ITIMER_VIRTUAL/PROF は
    //   それぞれ SIGVTALRM/SIGPROF。Phase 27 step 23 で ITIMER_REAL を真対応
    //   (Java background thread で sleep → kernel.kill(SIGALRM))。
    //   ITIMER_VIRTUAL/PROF は CPU 時間追跡できないので no-op。
    if( n == 38 ) return amd64_setitimer( a1, a2, a3 );
    if( n == 36 ) return 0;  // getitimer — 実時刻追跡は省略
    // POSIX timer 系: curl が --max-time / --connect-timeout を実装するのに
    //   timer_create + timer_settime で SIGALRM を仕込む。stub で「成功」を返す
    //   だけだと SIGALRM が来ないので、timer_settime で実際に Java の background
    //   thread で sleep → kernel.kill(pid, SIGALRM) を仕込む。
    if( n == 222 ) return amd64_timer_create( a1, a2, a3 );
    if( n == 223 ) return amd64_timer_settime( a1, a2, a3, a4 );
    if( n == 224 ) return 0;  // timer_gettime stub
    if( n == 225 ) return 0;  // timer_getoverrun stub
    if( n == 226 ) return 0;  // timer_delete stub
    // rt_sigsuspend(set, sigsetsize): 指定 sigmask に置き換えて任意のシグナル
    //   到達まで sleep、戻り値は常に -EINTR。signal mask の追跡はしていないので
    //   psig() != -1 になるまで待って -EINTR を返す簡易実装。
    if( n == 130 ) return amd64_rt_sigsuspend( a1, a2 );
    // rt_sigtimedwait(set, info, timeout, sigsetsize): 指定 sigmask の signal
    // を timeout まで待つ。vim が insert mode 切替時等に呼ぶ。timeout が 0
    // の peek なら即 -EAGAIN、timeout > 0 なら sleep。NULL なら無限待ち。
    // 簡易実装: 常に -EAGAIN を返して caller に「signal なし」を伝える。
    if( n == 128 ) return -11L;  // rt_sigtimedwait → EAGAIN
    // fadvise64: ヒントだけなので no-op で OK (cat / GNU coreutils 多用)
    if( n == 221 ) return 0;
    // mincore: ENOSYS で返すと glibc は busy-scan を諦める。
    //   0 を返すと grep 等が「マップ済み」と勘違いして 4MB 刻みの
    //   無限スキャンに陥るので必ず ENOSYS にする。
    if( n == 27 ) return -38L;
    // sigaltstack: シグナルハンドラ用代替スタック。今は固定 stack なので
    //   設定 (oss=NULL or *_SIGSTKSZ) を成功扱いで OK。
    if( n == 131 ) return 0;
    // sysinfo(struct sysinfo *info): メモリ・uptime 等の概況。GNU sort が
    //   buffer サイズの判定に使う。雑にゼロ埋めで十分。実際の構造体は
    //   uptime / loads[3] / totalram / freeram / sharedram / bufferram /
    //   totalswap / freeswap / procs (uint16) / pad / totalhigh / freehigh /
    //   mem_unit / pad で計 64+ byte。先頭 112 byte をゼロにしておく。
    if( n == 99 ) {
      // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
      mem.bulkZero( a1, 112 );
      // issue #78 (node/V8): totalram=0 だと V8 が「物理メモリ 0」と誤認して
      //   heap 上限を極小に設定 → 最初の本格確保で std::bad_alloc。現実的な
      //   メモリ量を報告する。struct sysinfo (x86-64):
      //   totalram +32 / freeram +40 / procs(uint16) +80 / mem_unit(uint32) +104
      mem.store64( a1 + 32, 4L*1024*1024*1024 );  // totalram = 4 GB
      mem.store64( a1 + 40, 4L*1024*1024*1024 );  // freeram  = 4 GB
      mem.store16( a1 + 80, (short)1 );           // procs = 1
      mem.store32( a1 + 104, 1 );                 // mem_unit = 1 (byte 単位)
      return 0;
    }
    // sched_getaffinity(pid, cpusetsize, mask): GNU sort 等が「CPU 数を
    //   見積もるため」に呼ぶ。ENOSYS で返すと libc が _SC_NPROCESSORS で
    //   /proc/cpuinfo にフォールバックして余計に面倒なので、CPU 1 個だけ
    //   set した mask を書いて成功扱いにする (戻り値は書いた byte 数)。
    if( n == 204 ) {
      int sz = (int)a2;
      long mask_addr = a3;
      if( sz > 0 && mask_addr != 0 ) {
        mem.store8( mask_addr, 1 );  // bit 0 (CPU 0) のみ on
        // Phase 34-B2 (issue #3-#1): per-byte loop → bulk zero
        if( sz > 1 ) mem.bulkZero( mask_addr + 1, sz - 1 );
      }
      return (sz > 0) ? sz : 8;
    }

    // issue #78 (node/libuv): epoll / eventfd / timerfd を実装。libuv の
    //   event loop (uv_loop_init) はこれらを必須とする。
    if( n == 213 ) return amd64_epoll_create();              // epoll_create(size)
    if( n == 291 ) return amd64_epoll_create();              // epoll_create1(flags)
    if( n == 232 ) return amd64_epoll_wait( a1, a2, a3, a4 ); // epoll_wait(epfd,ev,max,timeout)
    if( n == 233 ) return amd64_epoll_ctl( a1, a2, a3, a4 );  // epoll_ctl(epfd,op,fd,ev)
    if( n == 281 ) return amd64_epoll_wait( a1, a2, a3, a4 ); // epoll_pwait (sigmask 無視)
    if( n == 284 ) return amd64_eventfd( a1, 0 );            // eventfd(initval)
    if( n == 290 ) return amd64_eventfd( a1, a2 );           // eventfd2(initval,flags)
    if( n == 283 ) return amd64_timerfd_create( a2 );        // timerfd_create(clockid,flags)
    if( n == 286 ) return amd64_timerfd_settime( a1, a2, a3, a4 ); // timerfd_settime
    if( n == 287 ) return amd64_timerfd_gettime( a1, a2 );   // timerfd_gettime
    // Phase 29-emacs: emacs / Python 等が optional に使う syscall は ENOSYS。
    if( n == 282 ) return -38L;  // signalfd
    if( n == 289 ) return -38L;  // signalfd4
    if( n == 434 ) return -38L;  // pidfd_open
    if( n == 436 ) return -38L;  // close_range
    if( n == 441 ) return -38L;  // epoll_pwait2
    if( n == 122 ) return 0;     // setfsuid (stub success — uid 不変)
    if( n == 123 ) return 0;     // setfsgid (stub success — gid 不変)
    // xattr: vim 等が file の xattr を読もうとする。未対応で ENOSYS を
    // 返すと caller は xattr 機能を諦める。ENODATA でも可だが ENOSYS が
    // 一般的な「機能無し」signal。
    if( n == 188 ) return -38L;  // setxattr
    if( n == 189 ) return -38L;  // lsetxattr
    if( n == 190 ) return -38L;  // fsetxattr
    if( n == 191 ) return -38L;  // getxattr
    if( n == 192 ) return -38L;  // lgetxattr
    if( n == 193 ) return -38L;  // fgetxattr
    if( n == 194 ) return -38L;  // listxattr
    if( n == 195 ) return -38L;  // llistxattr
    if( n == 196 ) return -38L;  // flistxattr
    if( n == 197 ) return -38L;  // removexattr
    if( n == 198 ) return -38L;  // lremovexattr
    if( n == 199 ) return -38L;  // fremovexattr
    // 未知 syscall は abort せず ENOSYS を返す (Linux 実機の挙動)。
    //   旧実装は sys_exit(1) で即死していたが、新しい binary (node 等) は
    //   未対応 syscall を ENOSYS で受けて fallback することが多い。
    //   sysno 毎に 1 回だけ警告 (EMULIN_TRACE_SYSCALL=1 で毎回)。
    if( EMULIN_WARN_UNKNOWN_SYS.add( n ) || System.getenv("EMULIN_TRACE_SYSCALL") != null ) {
      System.err.println( "Emulin Warning : Unsupported amd64 syscall sysno=[" + n + "] → ENOSYS" );
    }
    return -38L;  // -ENOSYS
  }
  private static final java.util.Set<Integer> EMULIN_WARN_UNKNOWN_SYS =
      java.util.Collections.synchronizedSet( new java.util.HashSet<Integer>() );

  // ---------------------------------------------------------------
  // 64-bit 固有実装
  // ---------------------------------------------------------------

  // pread64(fd, buf, count, offset) - 指定ファイルオフセットから読み込む。
  // Linux 仕様: ファイル自身のオフセットは進めない。
  // 簡易実装として 現位置を SEEK_CUR で取得 → SEEK_SET で offset → read →
  // 元位置に SEEK_SET で復帰、で代用する。
  private long amd64_pread64( long fd, long addr, long count, long offset ) {
    int len = (int)count;
    int ifd = (int)fd;
    if( isSTD(ifd) || isERR(ifd) ) return -1L;
    int saved = FileSeek( ifd, 0, FileAccess.SEEK_CUR );
    FileSeek( ifd, (int)offset, FileAccess.SEEK_SET );
    byte[] buf = new byte[len];
    int got = FileRead( ifd, buf );
    FileSeek( ifd, saved, FileAccess.SEEK_SET );
    if( got < 0 ) return EBADF;
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
    mem.bulkStoreToMem( addr, buf, 0, got );
    return got;
  }

  // read(fd, buf, count)
  private long amd64_read( long fd, long addr, long count ) {
    int len = (int)count;
    // issue #41 Phase 3: 無効 fd は EBADF (amd64_write と同じ理由 — array
    // index out-of-bounds で thread が死んで親が hang する regression を
    // 防ぐ)。
    int ifd = (int)fd;
    if( ifd < 0 ) return -9L;
    if( !isSTD(ifd) && !isERR(ifd) ) {
      if( ifd >= flist.size() || get_finfo( ifd ) == null ) return -9L;
    }
    if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
      Fileinfo dbg_finfo = get_finfo(ifd);
      String name = (dbg_finfo != null) ? dbg_finfo.get_name() : "(null)";
      System.err.println("DBG_READ fd="+fd+" len="+count+" name='"+name+"' isSTD="+isSTD(ifd)+" isERR="+isERR(ifd));
      System.err.flush();  // hang した read を確実に capture
    }
    // issue #78: eventfd / timerfd は 8 byte counter を read する anon fd。
    {
      Fileinfo af = get_finfo( ifd );
      if( af != null && (af.eventfd_flag || af.timerfd_flag) ) {
        return anon_read( af, addr );
      }
    }
    if( isSTD(ifd) || isERR(ifd) ) {
      // issue #55: stdin/stderr が O_NONBLOCK で開かれていれば、data 無し時に
      //   EAGAIN を返す。ssh client は fcntl(0, F_SETFL, O_NONBLOCK) で stdin
      //   を non-blocking 化、select/poll で readable を確認してから read を
      //   呼ぶ前提だが、blocking-only な console.read を直接呼ぶと永久 hang
      //   (post-auth で socket reply を待てなくなる、Windows native で再現)。
      //   data available check は Console.Available() (JLine 経由) で行う。
      Fileinfo tty_finfo = get_finfo(ifd);
      if( tty_finfo != null && tty_finfo.nonBlock
          && !sysinfo.kernel.console.Available() ) {
        return -11L;  // -EAGAIN
      }
      byte[] buf = new byte[len];
      len = sysinfo.kernel.console.read( buf, process );
      // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy で I/O 高速化
      mem.bulkStoreToMem( addr, buf, 0, len );
    } else {
      byte[] buf = new byte[len];
      len = FileRead( (int)fd, buf );
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("READ fd="+fd+" req="+count+" got="+len);
      if( len == -2 ) return -11L;  // EAGAIN sentinel
      if( len < 0 ) return EBADF;
      mem.bulkStoreToMem( addr, buf, 0, len );
      if( System.getenv("EMULIN_TRACE_BIGREAD") != null && len > 100000 ) {
        StringBuilder sb = new StringBuilder("BIGREAD fd="+fd+" addr=0x"+Long.toHexString(addr)+" len="+len+" first 80 bytes: [");
        for( int i = 0; i < Math.min(80, len); i++ ) {
          char c = (char)(buf[i] & 0xff);
          if( c >= 0x20 && c < 0x7f ) sb.append(c);
          else sb.append('.');
        }
        sb.append("] last 80 bytes: [");
        for( int i = Math.max(0, len-80); i < len; i++ ) {
          char c = (char)(buf[i] & 0xff);
          if( c >= 0x20 && c < 0x7f ) sb.append(c);
          else sb.append('.');
        }
        sb.append("]");
        System.err.println(sb.toString());
      }
    }
    return len;
  }

  // write(fd, buf, count)
  private long amd64_write( long fd, long addr, long count ) {
    int len = (int)count;
    // issue #41 Phase 3: fd が無効 (負数 / 範囲外 / null) なら EBADF を返す。
    //   旧実装は flist.elementAt(-1) で ArrayIndexOutOfBoundsException が
    //   thrown され、catch されず Process.run thread が死ぬ。親プロセスは
    //   wait4 で永遠に block して emulator 全体が hang。
    //   sshd の fork-rexec child が「log fd が -1 (uninitialized)」のまま
    //   write を呼ぶ regression を捕捉。
    int ifd = (int)fd;
    if( ifd < 0 ) return -9L;  // -EBADF
    if( !isSTD(ifd) && !isERR(ifd) ) {
      if( ifd >= flist.size() || get_finfo( ifd ) == null ) return -9L;
    }
    // issue #78: eventfd への 8 byte write は counter 加算。
    {
      Fileinfo af = get_finfo( ifd );
      if( af != null && af.eventfd_flag ) {
        long v = 0;
        for( int i = 0; i < 8 && i < len; i++ ) v |= (mem.load8(addr+i)&0xFFL) << (8*i);
        synchronized( af ) { af.eventfd_count += v; }
        return 8;
      }
    }
    byte[] buf = new byte[len];
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy で I/O 高速化
    mem.bulkLoadFromMem( addr, buf, 0, len );
    if( System.getenv("EMULIN_TRACE_WRITE") != null ) {
      String prev = new String( buf, 0, Math.min(len, 80) ).replaceAll("[\\x00-\\x1f]", ".");
      System.err.println( "[write] fd="+ifd+" len="+len+" : "+prev );
    }
    if( isSTD(ifd) || isERR(ifd) ) {
      sysinfo.kernel.console.write( buf, isERR(ifd) );
    } else {
      // EPIPE は既に -32 で定義されているので - を付けない (付けると +32 となり
      //   「32 bytes 書けた」と誤解釈され partial-write retry ループになる)
      if( !FileWrite(ifd, buf) ) return EPIPE;
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
    /* argv[0] は保持する (busybox は applet 識別に使う)。
       実行ファイルの path は別途 _exec_path として渡す。 */
    if( args.isEmpty( ) ) args.add( name );
    String[] _args = args.toArray( new String[0] );
    String[] _envs = envs.toArray( new String[0] );
    /* kernel main loop の 1 秒ポーリングを待たず直接 exec を呼ぶ。
       自スレッド (= 旧プロセスのスレッド) はこの後 set_exit_flag() で
       run() ループを抜けて死に、kernel.exec が新プロセスを start する。
       注意: kernel.exec は syscall.process を新プロセスに張り替えるので、
       旧プロセスの参照を先に確保しておく必要がある。 */
    if( TRACE_EXEC ) {
      StringBuilder sb = new StringBuilder("DBG_EXEC name='" + name + "' argv=[");
      for( int j = 0; j < _args.length; j++ ) {
        if( j > 0 ) sb.append(", ");
        sb.append("'").append(_args[j]).append("'");
      }
      sb.append("]");
      System.err.println( sb.toString() );
    }
    Process old = process;
    sysinfo.kernel.exec( old.pid, name, _args, _envs );
    old.set_exit_flag( );
    return 0;
  }

  // kill(pid, sig)
  // 対象 pid を ptable から探してシグナルを recv() させる。
  // pid が存在しなければ -ESRCH を返す。
  // getppid() — 自プロセスの親 pid を返す
  private long amd64_getppid() {
    ProcessInfo me = sysinfo.kernel.get_pinfo( process.pid );
    if( me == null ) return 1; // フォールバック
    return me.ppid;
  }

  private long amd64_kill( long pid_l, long sig_l ) {
    int target_pid = (int)pid_l;
    int sig = (int)sig_l;
    if( target_pid <= 0 ) target_pid = process.pid; // pid<=0 は self へ送信 (簡易実装)
    Process target = sysinfo.kernel.find_process( target_pid );
    if( target == null ) return -3L; // -ESRCH
    if( sig > 0 ) target.recv( sig );
    return 0;
  }

  // tgkill(tgid, tid, sig): 特定 thread (tid) に signal を送る。
  //   POSIX: pthread_kill が glibc 内部で tgkill を使う。signal は target tid
  //   の thread の pending にだけ入る。Process 経由ではなく Signal の per-thread
  //   pending に直接 enqueue (Phase 27 step 35)。
  private long amd64_tgkill( long tgid_l, long tid_l, long sig_l ) {
    int target_tid = (int)tid_l;
    int sig = (int)sig_l;
    if( System.getenv("EMULIN_TRACE_WRITE") != null ) {
      System.err.println( "[tgkill] tgid="+(int)tgid_l+" tid="+target_tid+" sig="+sig );
    }
    if( sig <= 0 || sig >= 32 ) return -22L; // -EINVAL
    // Process は Signal を継承しているので process.recv_to_thread が使える。
    // tid は Thread64.tid または process.pid (main thread)。
    process.recv_to_thread( target_tid, sig );
    return 0;
  }

  // rt_sigaction(signum, &act, &oldact) — Linux kernel struct (32 byte):
  //   offset  0: sa_handler / sa_sigaction (8 byte)
  //   offset  8: sa_flags                  (8 byte, long)
  //   offset 16: sa_restorer               (8 byte)  ← 我々は使わない
  //   offset 24: sa_mask (sigset_t)        (8 byte)  ← Phase 27 step 27 で対応
  // Phase 27 step 27: sa_mask を Siginfo に保存し、ハンドラ進入時に
  //   process の signal mask に OR される。oldact 書き戻しは flags / mask も含める。
  private long amd64_rt_sigaction( long signum, long act_addr, long oldact_addr ) {
    int sn = (int)signum;
    if( sn < 0 || sn >= 32 ) return -22L; // -EINVAL
    if( oldact_addr != 0 ) {
      mem.store64( oldact_addr,      process.get_func_adrs( sn ) );
      mem.store64( oldact_addr +  8, process.get_sa_flags( sn ) );
      mem.store64( oldact_addr + 16, 0L );  // sa_restorer
      mem.store64( oldact_addr + 24, process.get_sa_mask( sn ) );
    }
    if( act_addr != 0 ) {
      long handler = mem.load64( act_addr );
      long flags   = mem.load64( act_addr + 8 );
      long mask    = mem.load64( act_addr + 24 );
      process.set_sigaction( sn, handler );
      process.set_sa_flags( sn, flags );
      process.set_sa_mask( sn, mask );
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
  //   is_child_exited 戻り値:
  //     >0 ... 終了済み子プロセスの pid
  //      0 ... 子プロセスが存在しない (Linux なら ECHILD)
  //     -1 ... 子はいるがまだ終了していない (block)
  private long amd64_wait4( long pid_l, long status_addr, long options_l, long rusage_addr ) {
    final int WNOHANG = 1;
    int pid = (int)pid_l;
    int options = (int)options_l;
    int ret_pid = 0;
    if( pid == -1 ) {
      while( true ) {
        ret_pid = sysinfo.kernel.is_child_exited( process.pid );
        if( ret_pid > 0 ) break;                      // 子が終了
        if( ret_pid == 0 ) { ret_pid = ECHILD; break; } // 子がいない → ECHILD
        // ret_pid == -1: 子はいるがまだ終了していない
        if( options == WNOHANG ) { ret_pid = 0; break; }
        Thread.yield( );
        try { Thread.sleep( 100L ); } catch( InterruptedException m ) { }
        if( -1 != process.psig( )) {
          // シグナルが pending — ただし sleep 中に子も終了していれば
          // Linux は子の pid を優先して返す (EINTR にしない)。
          int recheck = sysinfo.kernel.is_child_exited( process.pid );
          if( recheck > 0 ) { ret_pid = recheck; break; }
          ret_pid = EINTR; break;
        }
      }
    } else if( pid > 0 ) {
      // Phase 27 step 40: specific pid 待ち。git の start_command+finish_command は
      //   waitpid(child_pid, ...) で個別 pid を待つので、ここを実装しないと
      //   git が "waitpid is confused" を出す。
      while( true ) {
        ProcessInfo pi = sysinfo.kernel.get_pinfo( pid );
        if( pi == null ) { ret_pid = ECHILD; break; }
        // Phase 30: pi.process が exec_replacing 中 (= 旧 process が
        // exit_flag=true、新 process との差し替え途中) では「終了」では
        // ない。OLD の exit_flag を見て即 ret=pid を返すと vim 等の
        // wait4(pid, WNOHANG) が「子は終わった」と誤認する。
        // exec_replacing が解消されるまで待つ。
        Process pp = pi.process;
        boolean really_exited = pp.exit_flag && !pp.exec_replacing;
        if( TRACE_EXEC ) {
          System.err.println("DBG_WAIT4 pid="+pid+" exit_flag="+pp.exit_flag
            +" exec_replacing="+pp.exec_replacing+" really_exited="+really_exited
            +" name="+pp.name);
        }
        if( !really_exited ) {
          // まだ走っている (or exec 差し替え中)
          if( options == WNOHANG ) {
            // Phase 30: 即 return。child の race は really_exited 判定
            // (exec_replacing 中は終了扱いしない) で本質 fix 済。yield や
            // sleep を入れると並列回帰テストが timing flake する。
            ret_pid = 0;
            break;
          }
          Thread.yield( );
          try { Thread.sleep( 50L ); } catch( InterruptedException m ) { }
          if( -1 != process.psig( )) {
            // sleep 中に終了したかチェック
            if( pi.process.exit_flag ) { ret_pid = pid; break; }
            ret_pid = EINTR; break;
          }
          continue;
        }
        // 終了済み — wait4 した相手の pid を返す
        ret_pid = pid;
        break;
      }
    }
    if( status_addr != 0 ) {
      // wait status の Linux レイアウト:
      //   normal exit : (exit_code & 0xFF) << 8
      //   signal exit : signal & 0x7F (本実装は normal exit のみ対応)
      int wstatus = 0;
      if( ret_pid > 0 ) {
        ProcessInfo pi = sysinfo.kernel.get_pinfo( ret_pid );
        if( pi != null ) wstatus = (pi.exit_code & 0xFF) << 8;
      }
      mem.store32( status_addr, wstatus );
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
      // Phase 27 step 42: ファイル名は UTF-8 byte 長で reclen を計算する
      //   (旧 char 長は U+0080 以上で短くなる)。
      int name_bytes = d_name.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
      // header 19 bytes + name + NUL, 8 バイトアライメント
      int memlen = 19 + name_bytes + 1;
      int reclen = (memlen + 7) & ~7;
      long old_d_off = d_off;
      d_off += reclen;
      if( count < d_off ) break;
      if( start <= old_d_off ) {
        String full_child = dir_with_slash + d_name;
        Inode inode = new Inode( full_child, sysinfo );
        int d_type = 0; // DT_UNKNOWN. 厳密には inode.st_mode から判定すべき
        // Phase 33-11: symlink (broken でも) を DT_LNK で返す。
        // 旧実装は isExists()==false (broken target) で DT_UNKNOWN を返し
        // rm が「broken entry」とみなして skip → .git/<rand> 残存。
        // 先に isSymbolicLink を check して DT_LNK = 10 を確実に返す。
        try {
          // 最終 component は追従しない (symlink 自身の種別を見る)
          String native_child = sysinfo.get_native_path_nofollow( full_child );
          // issue #68: Cygwin マジックファイルも DT_LNK
          if( CygSymlink.enabled() && CygSymlink.isMagic( native_child ) ) {
            d_type = 10; // DT_LNK
          } else {
            java.nio.file.Path cp = java.nio.file.Paths.get( native_child );
            if( java.nio.file.Files.isSymbolicLink( cp ) ) {
              d_type = 10; // DT_LNK
            }
          }
        } catch( Exception ignored ) {}
        if( d_type == 0 ) {
          if( inode.isDirectory() ) d_type = 4; // DT_DIR
          else if( inode.isExists() ) d_type = 8; // DT_REG
        }
        long ino_val = inode.st_ino & 0xFFFFFFFFL;
        if( ino_val == 0 ) ino_val = ( full_child.hashCode() & 0xFFFFFFFFL );
        if( ino_val == 0 ) ino_val = 1;
        mem.store64( address +  0, ino_val );
        mem.store64( address +  8, d_off );
        mem.store16( address + 16, (short)reclen );
        mem.store8 ( address + 18, d_type );
        mem.storeString( address + 19, d_name );
        // storeString は終端 NUL も書く想定。残りはゼロ埋め
        for( int p = 19 + name_bytes + 1; p < reclen; p++ ) {
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
  // getcwd(buf, size) — Linux はバッファに NULL 終端文字列を書き、長さ (NULL 含む) を返す
  private long amd64_getcwd( long buf_addr, long size ) {
    String cwd = process.get_curdir();
    if( cwd == null || cwd.length() == 0 ) cwd = "/";
    int needed = cwd.length() + 1; // +1 for NUL
    if( size < needed ) return -34; // -ERANGE
    mem.storeString( buf_addr, cwd );
    return needed;
  }

  // socketpair(domain, type, protocol, fds[2]) — 簡易実装。
  // pipe を 2 本使って双方向にする。fd[0] と fd[1] それぞれに in/out 両方の
  // pipe_no を割り当てる。read/write 時に方向に応じて適切な pipe を使う。
  // (Phase 25 続き)
  // AF_INET ソケット系: TCP/UDP を Java の Socket / DatagramSocket で実装
  //   既存の EmuSocket / Fileinfo の socket 機構を amd64 syscall に橋渡しする。
  //   Phase 27 step 11 で curl からインターネット接続できるように。

  // clock_gettime(clk_id, struct timespec *tp) — 実時刻を返す。
  //   タイムスタンプ生成 (date / log) 等で必要。clk_id は無視 (CLOCK_REALTIME
  //   と CLOCK_MONOTONIC を同じ system time で実装)。
  private long amd64_clock_gettime( long clk_id, long ts_addr ) {
    long sec, nsec;
    if( DET_CLOCK ) {  // issue #113: 決定的 clock
      long us = detClockUs();
      sec  = us / 1_000_000L;
      nsec = (us % 1_000_000L) * 1000L;
    } else {
    long now_ms = System.currentTimeMillis();
    sec  = now_ms / 1000;
    nsec = (now_ms % 1000) * 1_000_000;
    if( clk_id == 1 /* CLOCK_MONOTONIC */ || clk_id == 6 /* CLOCK_MONOTONIC_RAW */ ) {
      // monotonic は nano resolution の方が正確だが ms ベースで十分
      long mono_ns = System.nanoTime();
      sec  = mono_ns / 1_000_000_000L;
      nsec = mono_ns % 1_000_000_000L;
    }
    }
    if( ts_addr != 0 ) {
      mem.store64( ts_addr,     sec );
      mem.store64( ts_addr + 8, nsec );
    }
    return 0;
  }

  // clock_getres(clk_id, struct timespec *res) — 解像度を返す。
  //   Java の System.currentTimeMillis() 解像度 = 1ms と申告。
  private long amd64_clock_getres( long clk_id, long res_addr ) {
    if( res_addr != 0 ) {
      mem.store64( res_addr,     0 );
      mem.store64( res_addr + 8, 1_000_000 );  // 1ms = 1,000,000 ns
    }
    return 0;
  }

  // setitimer(which, new_value, old_value):
  //   which: ITIMER_REAL=0 (SIGALRM), ITIMER_VIRTUAL=1 (SIGVTALRM), ITIMER_PROF=2 (SIGPROF)
  //   struct itimerval { struct timeval it_interval; struct timeval it_value; };
  //   struct timeval { long tv_sec; long tv_usec; };
  //   = 32 byte (4 longs)。it_value=0 で disarm。
  // Phase 27 step 28: clone(CLONE_VM | CLONE_THREAD | ...) で pthread を spawn。
  //   x86_64 ABI:
  //     a1 (rdi) = flags
  //     a2 (rsi) = child_stack (pointer)
  //     a3 (rdx) = parent_tid_addr (pointer)
  //     a4 (r10) = child_tid_addr  (pointer)
  //     a5 (r8 ) = tls (FS base pointer)
  //   親の戻り値 = child の tid。子は親の register state を継承し、rsp =
  //   child_stack、rax = 0、fs_base = tls (CLONE_SETTLS) で開始。
  //   Memory / FileAccess / Signal は親と共有 (CLONE_VM | CLONE_FS | CLONE_FILES
  //   想定)。
  private long amd64_clone_thread( long flags, long child_stack, long ptid, long ctid, long tls ) {
    final long CLONE_PARENT_SETTID = 0x100000L;
    final long CLONE_CHILD_CLEARTID = 0x200000L;
    final long CLONE_SETTLS = 0x80000L;

    Cpu64 parent_cpu = (Cpu64) process.cpu;
    Cpu64 child_cpu  = new Cpu64( sysinfo, process );
    // 親のレジスタを子にコピー → 子側で rax=0、rsp=child_stack、rip=next を上書き
    child_cpu.copy_state_from( parent_cpu );
    // exec_syscall が r64[R_RCX] = next_pc を設定済み (syscall ABI で rcx は
    //   syscall return address)。子はそこから実行を再開する。
    child_cpu.set_ip( parent_cpu.r64[1] );  // R_RCX = 1 → next_pc
    child_cpu.set_ax( 0 );
    if( child_stack != 0 ) child_cpu.set_sp( child_stack );
    if( (flags & CLONE_SETTLS) != 0 ) child_cpu.fs_base = tls;
    child_cpu.connect_devices( mem, this );

    if( System.getenv("EMULIN_TRACE_MMAP") != null ) {
      System.err.println( "[clone] flags=0x"+Long.toHexString(flags)+" child_stack=0x"+Long.toHexString(child_stack)
        +" tls=0x"+Long.toHexString(tls) );
    }
    int tid = sysinfo.kernel.next_tid( );
    long ctid_for_clear = ((flags & CLONE_CHILD_CLEARTID) != 0) ? ctid : 0L;
    // Phase 27 step 34: 子 thread は親の現 signal_mask を継承 (POSIX clone 仕様)
    long parent_mask = process.get_signal_mask_bits();
    Thread64 t = new Thread64( process, child_cpu, tid, mem, ctid_for_clear, parent_mask );

    if( (flags & CLONE_PARENT_SETTID) != 0 && ptid != 0 ) {
      mem.store32( ptid, tid );
    }
    if( ctid != 0 ) mem.store32( ctid, tid );

    t.start();
    return tid;
  }

  // futex(uaddr, op, val, timeout|val2, uaddr2, val3)
  private long amd64_futex( long uaddr, long op_l, long val_l, long timeout_addr ) {
    int op = (int)op_l & FutexManager.FUTEX_OP_MASK;
    int val = (int)val_l;
    if( op == FutexManager.FUTEX_WAIT || op == FutexManager.FUTEX_WAIT_BITSET ) {
      long timeout_ms = -1;  // 無期限
      if( timeout_addr != 0 ) {
        long sec  = mem.load64( timeout_addr );
        long nsec = mem.load64( timeout_addr + 8 );
        timeout_ms = sec * 1000L + nsec / 1_000_000L;
      }
      return FutexManager.wait( uaddr, val, timeout_ms, mem );
    }
    if( op == FutexManager.FUTEX_WAKE || op == FutexManager.FUTEX_WAKE_BITSET ) {
      return FutexManager.wake( uaddr, val );
    }
    // PI lock 等は未対応 — ENOSYS で諦めさせる
    return -38L; // -ENOSYS
  }

  private long amd64_setitimer( long which, long new_p, long old_p ) {
    // 旧値の書き出しは省略 (caller が読まない実装が多い、必要なら ENOSYS でなく 0)
    if( old_p != 0 ) {
      mem.store64( old_p,      0L );
      mem.store64( old_p + 8,  0L );
      mem.store64( old_p + 16, 0L );
      mem.store64( old_p + 24, 0L );
    }
    // ITIMER_REAL のみ真対応
    if( which != 0 ) return 0;
    if( new_p == 0 ) {
      // POSIX: new_value=NULL は EFAULT だが、安全側で disarm 扱い
      process.set_itimer_real( 0L, 0L );
      return 0;
    }
    long iv_sec  = mem.load64( new_p );
    long iv_usec = mem.load64( new_p + 8  );
    long val_sec = mem.load64( new_p + 16 );
    long val_usec= mem.load64( new_p + 24 );
    long initial_ms  = val_sec * 1000L + val_usec / 1000L;
    long interval_ms = iv_sec  * 1000L + iv_usec  / 1000L;
    process.set_itimer_real( initial_ms, interval_ms );
    return 0;
  }

  // rt_sigprocmask(how, set, oldset, sigsetsize):
  //   how: SIG_BLOCK=0 / SIG_UNBLOCK=1 / SIG_SETMASK=2
  //   sigset_t は kernel ABI で 8 byte (64 bit、最初の 64 signal 分)。
  //   bit 0 = SIGHUP (signum 1)、... bit 30 = SIGUNUSED (signum 31)。
  //   Phase 27 step 23: 旧 stub (常に 0) は sigprocmask が「成功したフリ」を
  //   していたが、実 mask は反映されていなかった。Siginfo.mask 経由で
  //   per-signal mask を実際に設定する。
  private long amd64_rt_sigprocmask( long how, long set_p, long oldset_p, long sigsetsize ) {
    // 旧 mask を書き出す
    if( oldset_p != 0 ) {
      mem.store64( oldset_p, process.get_signal_mask_bits() );
    }
    if( set_p == 0 ) return 0;
    long newbits = mem.load64( set_p );
    long cur = process.get_signal_mask_bits();
    long updated;
    if( how == 0 )      updated = cur | newbits;       // SIG_BLOCK
    else if( how == 1 ) updated = cur & ~newbits;      // SIG_UNBLOCK
    else                updated = newbits;             // SIG_SETMASK
    process.set_signal_mask_bits( updated );
    return 0;
  }

  // timer_create(clockid, sevp, timerid_out): POSIX タイマを作成。
  //   今は 1 プロセスに 1 タイマだけサポート。timerid に 0 を書いて返す。
  //   sevp の sigev_signo を timer_settime で読むのは省略 (= 既定 SIGALRM)。
  private long amd64_timer_create( long clockid, long sevp, long timerid_out ) {
    if( timerid_out != 0 ) {
      mem.store32( timerid_out, 0 );
      // 残り 4 byte は 0 で安心させる
      mem.store32( timerid_out + 4, 0 );
    }
    return 0;
  }

  // timer_settime(timerid, flags, new_value, old_value): タイマを arm。
  //   new_value は struct itimerspec = { it_interval (timespec), it_value (timespec) }
  //   = 32 byte (8+8 + 8+8)。it_value=0 の場合は disarm。
  //   it_value が non-zero なら background スレッドで sleep してから
  //   kernel.kill(pid, SIGALRM) を投げる。これで curl --max-time の
  //   タイムアウトが効く。
  private long amd64_timer_settime( long timerid, long flags, long new_p, long old_p ) {
    if( new_p == 0 ) return 0;
    long it_val_sec  = mem.load64( new_p + 16 ); // it_value.tv_sec
    long it_val_nsec = mem.load64( new_p + 24 ); // it_value.tv_nsec
    long ms = it_val_sec * 1000L + it_val_nsec / 1_000_000L;
    if( ms <= 0 ) return 0;  // disarm or zero
    final int target_pid = process.pid;
    final long delay_ms = ms;
    Thread t = new Thread( () -> {
      try { Thread.sleep( delay_ms ); }
      catch ( InterruptedException ignored ) { return; }
      sysinfo.kernel.kill( target_pid, Signal.SIGALRM );
    }, "emulin-timer-" + target_pid );
    t.setDaemon( true );
    t.start();
    return 0;
  }

  // rt_sigsuspend(set, sigsetsize): 任意のシグナル到達まで sleep して -EINTR。
  //   signal mask の追跡はしていないので、psig() != -1 になるまで yield + sleep
  //   する単純実装。SIGCHLD 自動配信 (Phase 23) や上で arm した SIGALRM が
  //   到来して帰ってくる。
  private long amd64_rt_sigsuspend( long set_p, long sigsetsize ) {
    while( true ) {
      if( process.psig() != -1 ) return -4L;  // -EINTR
      Thread.yield();
      try { Thread.sleep( 10L ); }
      catch ( InterruptedException ignored ) { return -4L; }
    }
  }

  // pselect6(nfds, readfds, writefds, exceptfds, timeout, sigmask)
  //   readfds の各 fd について実際に readable かを判定する。
  //   socket fd で EOF 検知済みの場合も「readable」として扱う (read で 0 を
  //   返してくれるので caller は EOF を認識できる)。
  //   ただし読み込みが進まない (peekBuf 空 + EOF) socket だけが残った場合
  //   は ready=0 を返して timeout を発生させ、caller がポーリングを抜ける。
  private long amd64_pselect6( long nfds, long readfds, long writefds, long exceptfds, long timeout ) {
    int n = (int)nfds;
    // timeout (struct timespec*) を読む。NULL なら無限。
    //   timespec: tv_sec (8) + tv_nsec (8)
    long deadline_ms = -1;  // -1 = 無限
    long total_ms_for_log = -1;
    if( timeout != 0 ) {
      long sec  = mem.load64( timeout );
      long nsec = mem.load64( timeout + 8 );
      long total_ms = sec * 1000L + nsec / 1_000_000L;
      deadline_ms = System.currentTimeMillis() + total_ms;
      total_ms_for_log = total_ms;
    }
    if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
      long rfds = (readfds != 0) ? mem.load64(readfds) : 0L;
      System.err.println("DBG_PSELECT nfds="+n+" rfds=0x"+Long.toHexString(rfds)+" timeout_ms="+total_ms_for_log);
    }
    int nwords = (n + 63) / 64;
    if( nwords < 1 ) nwords = 1;
    int max_iter = Integer.MAX_VALUE;
    while( max_iter-- > 0 ) {
      int ready = 0;
      boolean any_alive = false;
      // issue #3-#5 (c): result bitmap を計算。Linux pselect は ready な fd
      // のみ bit を残し、他は clear する仕様。我々の旧実装は input bitmap を
      // 全く触らず、結果 emacs が「fd 3 は readable」と誤判定して読まずに
      // 即 pselect 再呼び出し (busy spin)。
      long[] new_rfds = new long[nwords];
      long[] new_wfds = new long[nwords];
      // 読み判定 — 実 read を試行してデータがあれば peekBuf にキャッシュ
      if( readfds != 0 ) {
        for( int fd = 0; fd < n; fd++ ) {
          long word = mem.load64( readfds + (fd/64)*8 );
          if( ((word >>> (fd%64)) & 1L) == 0 ) continue;
          Fileinfo finfo = get_finfo( fd );
          if( finfo == null ) continue;
          boolean is_ready = false;
          if( finfo.peekBuf != null && finfo.peekLen > 0 ) {
            is_ready = true; any_alive = true;
          }
          // issue #41 (sshd): listen socket (sconn) は SubProcess の
          //   accept_flag を覗いて、ACCEPT_DONE なら readable とする。
          else if( finfo.isSOCKET() && finfo.sconn != null && finfo.subprocess != null ) {
            any_alive = true;
            if( finfo.subprocess.Accepted() == SubProcess.ACCEPT_DONE ) {
              is_ready = true;
            }
          }
          // issue #113: AF_UNIX listen socket (ServerSocketChannel)。保留接続が
          //   あれば readable にする (旧実装は分岐が無く readable にならず、
          //   gpg-agent daemon が client 接続を accept できずハングしていた)。
          //   non-blocking accept で取り出して unixQueued に積み、後続の
          //   accept(2) がそれを返す (amd64_accept4 は unixQueued を参照済み)。
          else if( finfo.isSOCKET() && finfo.unixServer != null ) {
            any_alive = true;
            if( finfo.unixQueued != null ) {
              is_ready = true;
            } else {
              try {
                finfo.unixServer.configureBlocking( false );
                java.nio.channels.SocketChannel ch = finfo.unixServer.accept();
                if( ch != null ) { finfo.unixQueued = ch; is_ready = true; }
              } catch ( java.io.IOException ignored ) {}
            }
          }
          // issue #113: AF_UNIX 接続済 socket (unixSocket) の read-readiness。
          //   non-blocking read で 1 byte peek し、あれば peekBuf に積んで readable。
          //   Fileinfo.Read は peekBuf を先に消費する。
          else if( finfo.isSOCKET() && finfo.unixSocket != null && !finfo.socketEof ) {
            any_alive = true;
            if( finfo.peekBuf != null && finfo.peekLen > 0 ) {
              is_ready = true;
            } else {
              try {
                finfo.unixSocket.configureBlocking( false );
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate( 1 );
                int r = finfo.unixSocket.read( bb );
                if( r > 0 ) {
                  finfo.peekBuf = new byte[]{ bb.get(0) }; finfo.peekLen = 1;
                  is_ready = true;
                } else if( r < 0 ) {
                  finfo.socketEof = true; is_ready = true;  // EOF も readable
                }
              } catch ( java.io.IOException ignored ) { finfo.socketEof = true; }
            }
          }
          else if( finfo.isSOCKET() && finfo.conn != null && !finfo.socketEof ) {
            try {
              if( finfo.conn.getInputStream().available() > 0 ) {
                is_ready = true; any_alive = true;
              } else {
                int prev = finfo.conn.getSoTimeout();
                finfo.conn.setSoTimeout( 1 );
                try {
                  byte[] one = new byte[1];
                  int r = finfo.conn.getInputStream().read( one );
                  if( r > 0 ) {
                    byte[] nb = (finfo.peekBuf == null) ? new byte[1]
                              : new byte[finfo.peekLen + 1];
                    if( finfo.peekBuf != null )
                      System.arraycopy( finfo.peekBuf, 0, nb, 0, finfo.peekLen );
                    nb[nb.length - 1] = one[0];
                    finfo.peekBuf = nb; finfo.peekLen = nb.length;
                    is_ready = true; any_alive = true;
                  } else if( r < 0 ) {
                    finfo.socketEof = true;
                    is_ready = true;  // EOF も「読める」 (read で 0 を返すと caller が EOF 認識)
                  }
                } catch ( java.net.SocketTimeoutException ste ) {
                  any_alive = true;  // socket alive、まだデータ無し
                } finally {
                  finfo.conn.setSoTimeout( prev );
                }
              }
            } catch ( java.io.IOException ignored ) {
              finfo.socketEof = true;
            }
          } else if( finfo.is_pipe( true ) ) {
            // issue #76: pipe / pty (master/slave の pipe_pair) の read 端は、
            //   pipe_available で実データを確認してから ready にする。旧実装は
            //   下の else で「常に ready」にしていたため、emacs M-x shell の
            //   pty I/O が「ready なのに read で block」して hang した。
            //   amd64_poll は既に同じ pipe_available 判定をしている。
            //   切断済 pipe は read で 0 (EOF) を返すので readable 扱い。
            if( !sysinfo.kernel.is_pipe_connected( finfo.pipe_no ) ) {
              is_ready = true; any_alive = true;
            } else {
              any_alive = true;
              if( sysinfo.kernel.pipe_available( finfo.pipe_no ) > 0 ) is_ready = true;
            }
          } else if( !finfo.isSOCKET() ) {
            // issue #55: amd64_poll と同様、native TTY は raw / cooked 問わず
            // 実 Available() check が必要 (cooked mode 「常に ready」だと ssh
            // post-auth の blocking read(stdin) で永久 hang)。
            boolean tty = finfo.isSTD() || finfo.isERR();
            if( tty && sysinfo.kernel.console.is_native_tty() ) {
              if( sysinfo.kernel.console.Available() ) is_ready = true;
              any_alive = true;
            } else {
              is_ready = true; any_alive = true;
            }
          }
          if( is_ready ) {
            new_rfds[fd/64] |= (1L << (fd%64));
            ready++;
          }
        }
      }
      if( writefds != 0 ) {
        for( int fd = 0; fd < n; fd++ ) {
          long word = mem.load64( writefds + (fd/64)*8 );
          if( ((word >>> (fd%64)) & 1L) == 0 ) continue;
          new_wfds[fd/64] |= (1L << (fd%64));
          ready++; any_alive = true;
        }
      }
      if( ready > 0 ) {
        // result bitmap を write back。exceptfds は常に 0 clear (例外無し)。
        if( readfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( readfds + (long)w*8L, new_rfds[w] );
        if( writefds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( writefds + (long)w*8L, new_wfds[w] );
        if( exceptfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( exceptfds + (long)w*8L, 0L );
        if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
          System.err.println("DBG_PSELECT_RET ready="+ready+" rfds=0x"+Long.toHexString(new_rfds[0]));
          // 初回 ready=1 のときだけ次の 20 syscalls を log (連続 ready の度に reset すると無限 log になる)
          if( debug_sys_after_pselect == 0 ) debug_sys_after_pselect = 20;
        }
        return ready;
      }
      if( !any_alive ) {
        if( readfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( readfds + (long)w*8L, 0L );
        if( writefds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( writefds + (long)w*8L, 0L );
        if( exceptfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( exceptfds + (long)w*8L, 0L );
        if( System.getenv("EMULIN_DEBUG_TTY") != null )
          System.err.println("DBG_PSELECT_RET no_alive");
        return 0;
      }
      if( deadline_ms >= 0 && System.currentTimeMillis() >= deadline_ms ) {
        if( readfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( readfds + (long)w*8L, 0L );
        if( writefds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( writefds + (long)w*8L, 0L );
        if( exceptfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( exceptfds + (long)w*8L, 0L );
        if( System.getenv("EMULIN_DEBUG_TTY") != null )
          System.err.println("DBG_PSELECT_RET timeout deadline="+deadline_ms+" now="+System.currentTimeMillis());
        return 0;
      }
      try { Thread.sleep( 10 ); } catch ( InterruptedException ie ) {
        if( System.getenv("EMULIN_DEBUG_TTY") != null )
          System.err.println("DBG_PSELECT_RET interrupted");
        return 0;
      }
    }
    return 0;
  }

  private long amd64_socket( long domain, long type, long protocol ) {
    // socket(domain, type, protocol)
    //   type には SOCK_CLOEXEC (0x80000) / SOCK_NONBLOCK (0x800) のフラグが
    //   含まれることがあるので低位 0xFF だけ取り出す。
    int t = (int)type & 0xFF;
    boolean nonblock = ((int)type & 0x800) != 0;  // SOCK_NONBLOCK
    boolean cloexec  = ((int)type & 0x80000) != 0; // SOCK_CLOEXEC
    // issue #9: AF_UNIX (Unix domain socket) を許可。socket() 段階では
    //   Fileinfo を作るだけで、実際の channel は connect() 時に作る。
    //   SOCK_STREAM のみサポート (DGRAM AF_UNIX はレア)。これで
    //   ssh-add / nscd / D-Bus 等が host の Unix socket に talk できる。
    if( (int)domain == EmuSocket.AF_UNIX ) {
      if( t != EmuSocket.SOCK_STREAM ) return -97L;
      int rc = socket( (int)domain, t, (int)protocol );
      if( rc < 0 ) return -97L;
      if( nonblock ) {
        Fileinfo finfo = get_finfo( rc );
        if( finfo != null ) finfo.nonBlock = true;
      }
      if( cloexec ) set_cloexec( rc, true );
      return rc;
    }
    // issue #9: AF_INET6 (10) を許可。Java Socket は v4/v6 透過で動くので、
    //   socket() 段階では Fileinfo を作るだけ。connect() で sockaddr_in6 を
    //   解釈して Inet6Address 経由で接続する。AF_INET と同じ EmuSocket.socket
    //   path に乗せる ("<sock>" virtual で stream_flag を set)。
    if( (int)domain == EmuSocket.AF_INET6 ) {
      int rc = socket( EmuSocket.AF_INET, t, (int)protocol );  // 内部表現は v4 と共通
      if( rc < 0 ) return -97L;
      Fileinfo finfo = get_finfo( rc );
      if( finfo != null ) {
        finfo.family_v6 = true;  // 後で connect/getsockname で識別
        if( nonblock ) finfo.nonBlock = true;
      }
      if( cloexec ) set_cloexec( rc, true );
      return rc;
    }
    // AF_INET6 以外で AF_INET でも AF_UNIX でもないものは未対応。
    if( (int)domain != EmuSocket.AF_INET ) return -97L;
    // AF_INET の SOCK_DGRAM (UDP) は EmuSocket.socket → Fileinfo.
    //   make_server_socket(-1) で Java DatagramSocket をエフェメラル port
    //   にバインドする。glibc resolver が /etc/resolv.conf 経由で実 DNS
    //   サーバに query を送れるようにする (Phase 27 step 14)。
    int rc = socket( (int)domain, t, (int)protocol );
    if( rc < 0 ) return -97L; // EAFNOSUPPORT
    // SOCK_NONBLOCK / SOCK_CLOEXEC を反映 (cloexec は per-fd 管理)
    if( nonblock ) {
      Fileinfo finfo = get_finfo( rc );
      if( finfo != null ) finfo.nonBlock = true;
    }
    if( cloexec ) set_cloexec( rc, true );
    return rc;
  }

  // sockaddr_in (16 byte): family(2) + port(2 BE) + addr(4 BE) + zero(8)
  // sockaddr_in6 (28 byte): family(2) + port(2 BE) + flowinfo(4) + addr(16) + scope(4)
  private static class SockaddrIn {
    int family, port, ipForLegacy;
  }
  // 既存の EmuSocket.connect / Fileinfo.client_socket は内部で
  //   `Util.ip_str(Util.swap32(_ip))` していて、結果として _ip は
  //   「メモリから読んだ network 4 byte を更に swap した値」を期待する
  //   (i386 socketcall も同じ規約)。amd64 でも同じ規約に揃えるため、
  //   addr+4 の生 4 byte を一旦 mem.load32 で host 順 (LE) に取り、
  //   さらに Util.swap32 を掛けてから渡す。
  private SockaddrIn loadSockaddrIn( long addr ) {
    SockaddrIn r = new SockaddrIn();
    r.family = mem.load16( addr ) & 0xFFFF;
    int portBE = mem.load16( addr + 2 ) & 0xFFFF;
    r.port = ((portBE & 0xFF) << 8) | ((portBE >>> 8) & 0xFF);  // BE → host
    int rawIp = mem.load32( addr + 4 );
    r.ipForLegacy = Util.swap32( rawIp );
    return r;
  }

  private long amd64_connect( long fd, long addr_ptr, long addrlen ) {
    int family = mem.load16( addr_ptr ) & 0xFFFF;
    // issue #9: AF_UNIX (1) — sockaddr_un は family(2) + path(108 byte, NUL 終端)。
    //   path を読んで Java の UnixDomainSocketAddress に connect する。
    //   path は host file system 上の絶対 path として扱う (sandbox の virtual
    //   path に解決すると本物の ssh-agent socket に届かなくなるので、emulin
    //   の AF_UNIX は意図的に host file system pass-through)。
    if( family == EmuSocket.AF_UNIX ) {
      Fileinfo finfo = get_finfo( (int)fd );
      if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
      // sockaddr_un.path を NUL 終端で読み出す。最大 108 byte。
      StringBuilder sb = new StringBuilder();
      int n = (int)Math.min( addrlen - 2, 108 );
      for( int i = 0; i < n; i++ ) {
        int b = mem.load8( addr_ptr + 2 + i ) & 0xFF;
        if( b == 0 ) break;
        sb.append( (char)b );
      }
      String virtPath = sb.toString();
      if( virtPath.isEmpty() ) return -2L;  // ENOENT
      // issue #43 Phase 4-4: bind 側で sandbox 内に socket を作れるように
      //   なったので、connect も「sandbox 内 → host fs」の順で試行する。
      //   sandbox 内 (= emulin 内 sshd の forwarded agent) を優先し、
      //   無ければ host fs pass-through (= host の ssh-agent)。
      String sandboxPath = sysinfo.get_native_path(
        sysinfo.get_full_path( process.get_curdir(), virtPath ) );
      java.io.IOException last = null;
      for( String tryPath : new String[]{ sandboxPath, virtPath } ) {
        if( !java.nio.file.Files.exists( java.nio.file.Paths.get( tryPath ) ) ) continue;
        try {
          java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(
              java.net.StandardProtocolFamily.UNIX );
          ch.connect( java.net.UnixDomainSocketAddress.of( tryPath ) );
          finfo.unixSocket = ch;
          return 0;
        } catch ( java.io.IOException m ) {
          last = m;
        }
      }
      return -2L;  // ENOENT (socket file 不在 / 接続失敗)
    }
    // issue #9: AF_INET6 (10) — sockaddr_in6 (28 byte) layout:
    //   sin6_family (2) + sin6_port (2 BE) + sin6_flowinfo (4) +
    //   sin6_addr (16) + sin6_scope_id (4)
    if( family == EmuSocket.AF_INET6 ) {
      Fileinfo finfo = get_finfo( (int)fd );
      if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
      int portBE = mem.load16( addr_ptr + 2 ) & 0xFFFF;
      int port = ((portBE & 0xFF) << 8) | ((portBE >>> 8) & 0xFF);  // BE → host
      byte[] addr16 = new byte[16];
      for( int i = 0; i < 16; i++ ) addr16[i] = (byte)(mem.load8( addr_ptr + 8 + i ) & 0xFF);
      if( !finfo.isSTREAM() ) {
        // UDP v6: POSIX 仕様で connect は dest を保存するだけ (実際の通信は
        //   send/sendmsg まで発生しない)。後で sendto を addr 省略で呼んだら
        //   この保存値を使う。失敗パスは無いので 0 を返す。
        finfo.connected_v6_addr = addr16;
        finfo.connected_v6_port = port;
        if( System.getenv("EMULIN_TRACE_NET") != null ) {
          StringBuilder sb = new StringBuilder();
          for( byte b : addr16 ) sb.append(String.format("%02x", b & 0xFF));
          System.err.println("CONNECT-V6-UDP fd="+fd+" dst="+sb.toString()+" port="+port);
        }
        return 0;
      }
      boolean ok = finfo.client_socket_v6( addr16, port );
      if( !ok ) return -101L;  // ENETUNREACH (host が IPv6 routable で
                               // なければ Java Socket constructor が失敗)
      if( finfo.nonBlock ) return -115L;  // EINPROGRESS
      return 0;
    }
    if( family != EmuSocket.AF_INET ) {
      return -2L; // ENOENT
    }
    SockaddrIn sa = loadSockaddrIn( addr_ptr );
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo == null || !finfo.isSOCKET() ) return -9L; // EBADF
    // UDP socket への connect は dest IP/port を覚えるだけ (POSIX 仕様)。
    //   この後 send() が dest_addr 省略で呼ばれたら sendto は finfo.ip/port を
    //   使う。glibc DNS resolver はこのパターンで /etc/resolv.conf の
    //   nameserver に query を送る。
    if( !finfo.isSTREAM() ) {
      finfo.set_ip_address( sa.ipForLegacy );
      finfo.set_port( sa.port );
      return 0;
    }
    // amd64 経路では SubProcess を起動せず、Fileinfo の Java Socket を
    //   直接 read/write する (背景スレッド読み出しとレースしないように)。
    boolean ok = finfo.client_socket( sa.ipForLegacy, sa.port );
    if( !ok ) return -111L;  // ECONNREFUSED
    // 非 blocking socket では -EINPROGRESS を返す (Linux 互換)。
    //   TCP 接続自体は Java Socket constructor で同期的に完了済みだが、
    //   curl は「connect=0 即時成功」だと一部経路で abort してしまう
    //   (poll + getsockopt(SO_ERROR) で完了確認するパスに乗らないため)。
    //   呼び出し元はこのあと poll で POLLOUT を待ち、getsockopt(SO_ERROR)
    //   で完了確認する想定なので EINPROGRESS の方が互換性が高い。
    if( finfo.nonBlock ) return -115L;  // EINPROGRESS
    return 0;
  }

  private long amd64_bind( long fd, long addr_ptr, long addrlen ) {
    int family = mem.load16( addr_ptr ) & 0xFFFF;
    // issue #43 Phase 4-4: AF_UNIX bind — sockaddr_un.path を sandbox 内に
    //   解決して ServerSocketChannel(UNIX) を bind する (= virtual path)。
    //   client connect は host fs pass-through だが (issue #9、ssh-agent
    //   への接続)、bind は emulin 内の process が作る socket なので
    //   sandbox 経由 native path に変換する。sshd の ssh-agent forwarding
    //   が /tmp/ssh-XXXXXX/agent.PID を作る経路で必須。
    //   parent dir が無ければ mkdir -p してから bind。
    if( family == EmuSocket.AF_UNIX ) {
      Fileinfo finfo = get_finfo( (int)fd );
      if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
      StringBuilder sb = new StringBuilder();
      int n = (int)Math.min( addrlen - 2, 108 );
      for( int i = 0; i < n; i++ ) {
        int b = mem.load8( addr_ptr + 2 + i ) & 0xFF;
        if( b == 0 ) break;
        sb.append( (char)b );
      }
      String virtPath = sb.toString();
      if( virtPath.isEmpty() ) return -22L;  // EINVAL
      // sandbox 経由 native path に変換 (絶対 path として解決)
      String nativePath = sysinfo.get_native_path(
        sysinfo.get_full_path( process.get_curdir(), virtPath ) );
      try {
        // parent dir を mkdir -p (sshd は事前に mkdir するが念のため)
        java.nio.file.Path np = java.nio.file.Paths.get( nativePath );
        java.nio.file.Path parent = np.getParent();
        if( parent != null ) {
          try { java.nio.file.Files.createDirectories( parent ); }
          catch ( java.io.IOException ignored ) {}
        }
        // 既存 stale socket を unlink (Linux 慣習)
        try { java.nio.file.Files.deleteIfExists( np ); }
        catch ( java.io.IOException ignored ) {}
        java.nio.channels.ServerSocketChannel ss = java.nio.channels.ServerSocketChannel.open(
            java.net.StandardProtocolFamily.UNIX );
        ss.bind( java.net.UnixDomainSocketAddress.of( nativePath ) );
        finfo.unixServer = ss;
        return 0;
      } catch ( java.io.IOException m ) {
        return -98L;  // EADDRINUSE
      }
    }
    // issue #9: AF_INET6 (10) sockaddr_in6 (28 byte) を受けて v6 ServerSocket /
    //   DatagramSocket を bind する。Java の ServerSocket/DatagramSocket は
    //   InetAddress を指定すれば v6 wildcard (`::`) でも特定アドレスでも bind 可。
    if( family == EmuSocket.AF_INET6 && addrlen >= 28 ) {
      Fileinfo finfo = get_finfo( (int)fd );
      if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
      int portBE = mem.load16( addr_ptr + 2 ) & 0xFFFF;
      int port_v6 = ((portBE & 0xFF) << 8) | ((portBE >>> 8) & 0xFF);
      byte[] addr16 = new byte[16];
      for( int i = 0; i < 16; i++ ) addr16[i] = (byte)(mem.load8( addr_ptr + 8 + i ) & 0xFF);
      try {
        java.net.InetAddress local = java.net.Inet6Address.getByAddress( null, addr16, 0 );
        if( finfo.isSTREAM() ) {
          if( finfo.sconn != null ) { try { finfo.sconn.close(); } catch ( java.io.IOException ignored ) {} }
          finfo.sconn = new java.net.ServerSocket( port_v6, 0, local );
        } else {
          // UDP v6: socket() で eagerly bind 済みの dgram を一度閉じて、指定 addr/port で再 bind
          if( finfo.dgram != null ) { finfo.dgram.close(); }
          finfo.dgram = new java.net.DatagramSocket( port_v6, local );
        }
        return 0;
      } catch ( java.io.IOException m ) {
        return -98L; // EADDRINUSE
      }
    }
    SockaddrIn sa = loadSockaddrIn( addr_ptr );
    if( sa.family != EmuSocket.AF_INET ) return -97L;
    boolean ok = bind( (int)fd, sa.ipForLegacy, sa.port );
    if( !ok ) return -98L; // EADDRINUSE
    return 0;
  }

  private long amd64_listen( long fd, long backlog ) {
    // issue #43 Phase 4-4: AF_UNIX server は ServerSocketChannel.bind の時点で
    //   listening 状態に入っているので no-op success。
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo != null && finfo.unixServer != null ) return 0;
    boolean ok = listen( (int)fd, (int)backlog );
    if( !ok ) return -22L; // EINVAL
    return 0;
  }

  // issue #9: amd64 の accept(43) / accept4(288). 既存の EmuSocket.accept を
  //   薄くラップする。listener が SOCK_NONBLOCK のときは subprocess の
  //   accept_flag を覗いて EAGAIN を即返す (block しない)。flags の SOCK_NONBLOCK
  //   は新 accept された fd に適用 (Linux semantics)。AF_INET6 listener なら
  //   peer addr を sockaddr_in6 (28 byte) で返す。
  private long amd64_accept4( long fd, long addr_ptr, long addrlen_ptr, long flags ) {
    boolean nb_new   = ((int)flags & 0x800)   != 0;  // SOCK_NONBLOCK for new fd
    boolean cloexec  = ((int)flags & 0x80000) != 0;  // SOCK_CLOEXEC
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
    // issue #43 Phase 4-4: AF_UNIX accept — ServerSocketChannel.accept() で
    //   SocketChannel を取得して新 fd の unixSocket に保存。non-blocking と
    //   peer addr 書き戻し (sockaddr_un、最小実装) も対応。
    if( finfo.unixServer != null ) {
      try {
        java.nio.channels.SocketChannel ch;
        if( finfo.unixQueued != null ) {
          ch = finfo.unixQueued;
          finfo.unixQueued = null;
        } else {
          if( finfo.nonBlock ) {
            finfo.unixServer.configureBlocking( false );
          } else {
            finfo.unixServer.configureBlocking( true );
          }
          ch = finfo.unixServer.accept();
        }
        if( ch == null ) return -11L;  // EAGAIN (non-blocking で接続無し)
        int new_fd = FileOpen( "<sock>", "rw", Syscall.O_RDWR );
        if( new_fd < 0 ) { try { ch.close(); } catch( java.io.IOException ignored ) {} return -24L; }
        Fileinfo new_finfo = get_finfo( new_fd );
        if( new_finfo == null ) return -22L;
        new_finfo.set_socket_type( true );  // STREAM
        new_finfo.unixSocket = ch;
        if( cloexec ) set_cloexec( new_fd, true );
        if( nb_new ) new_finfo.nonBlock = true;
        // peer addr (AF_UNIX で peer の path は通常 "" — abstract と等価)
        if( addr_ptr != 0 ) {
          mem.store16( addr_ptr, (short)EmuSocket.AF_UNIX );
          mem.store8( addr_ptr + 2, 0 );  // path[0] = NUL
          if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 3 );  // family(2) + 1
        }
        return new_fd;
      } catch ( java.io.IOException m ) {
        return -103L;  // ECONNABORTED
      }
    }
    if( finfo.sconn == null )       return -22L; // EINVAL — bind/listen 未呼び
    if( finfo.subprocess == null )  return -22L; // EINVAL — listen 未呼び
    // listener が non-blocking のときは accept_flag を覗いて EAGAIN を即返す。
    if( finfo.nonBlock ) {
      int st = finfo.subprocess.Accepted();
      if( st == SubProcess.ACCEPT_WAIT ) return -11L;   // EAGAIN
      if( st == SubProcess.ACCEPT_MISS ) return -103L;  // ECONNABORTED
    }
    int new_fd = accept( (int)fd );
    if( new_fd < 0 ) return -22L; // EINVAL
    if( cloexec ) set_cloexec( new_fd, true );
    Fileinfo new_finfo = get_finfo( new_fd );
    if( new_finfo == null ) return -22L;
    if( nb_new ) new_finfo.nonBlock = true;
    new_finfo.family_v6 = finfo.family_v6;
    // peer sockaddr 書き戻し
    if( addr_ptr != 0 && new_finfo.conn != null ) {
      java.net.InetAddress peer = new_finfo.conn.getInetAddress();
      int peer_port = new_finfo.conn.getPort();
      if( peer != null ) {
        byte[] raw = peer.getAddress();
        if( finfo.family_v6 ) {
          byte[] addr16 = new byte[16];
          if( raw.length == 16 ) System.arraycopy( raw, 0, addr16, 0, 16 );
          else { addr16[10] = (byte)0xFF; addr16[11] = (byte)0xFF;
                 addr16[12] = raw[0]; addr16[13] = raw[1];
                 addr16[14] = raw[2]; addr16[15] = raw[3]; }
          mem.store16( addr_ptr,     (short)EmuSocket.AF_INET6 );
          mem.store16( addr_ptr + 2, (short)(((peer_port & 0xFF) << 8) | ((peer_port >>> 8) & 0xFF)) );
          mem.store32( addr_ptr + 4, 0 );
          for( int i = 0; i < 16; i++ ) mem.store8( addr_ptr + 8 + i, addr16[i] );
          mem.store32( addr_ptr + 24, 0 );
          if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 28 );
        } else {
          int ip = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                 | ((raw[2] & 0xFF) << 8)  |  (raw[3] & 0xFF);
          mem.store16( addr_ptr,     (short)EmuSocket.AF_INET );
          mem.store16( addr_ptr + 2, (short)(((peer_port & 0xFF) << 8) | ((peer_port >>> 8) & 0xFF)) );
          mem.store32( addr_ptr + 4, Util.swap32( ip ));
          mem.store64( addr_ptr + 8, 0 );
          if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 16 );
        }
      }
    }
    return new_fd;
  }

  private long amd64_sendto( long fd, long buf_addr, long len, long flags, long dest_addr, long addrlen ) {
    int n = (int)len;
    if( n < 0 ) return -22L;
    byte[] buf = new byte[n];
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
    mem.bulkLoadFromMem( buf_addr, buf, 0, n );
    if( dest_addr != 0 && addrlen >= 16 ) {
      int fam = mem.load16( dest_addr ) & 0xFFFF;
      // issue #9: AF_INET6 dest — sockaddr_in6 (28 byte) を解釈して v6 send
      if( fam == EmuSocket.AF_INET6 && addrlen >= 28 ) {
        Fileinfo finfo = get_finfo( (int)fd );
        if( finfo == null || !finfo.isSOCKET() ) return -9L;
        int portBE = mem.load16( dest_addr + 2 ) & 0xFFFF;
        int port_v6 = ((portBE & 0xFF) << 8) | ((portBE >>> 8) & 0xFF);
        byte[] addr16 = new byte[16];
        for( int i = 0; i < 16; i++ ) addr16[i] = (byte)(mem.load8( dest_addr + 8 + i ) & 0xFF);
        boolean ok = finfo.sendto_v6( buf, addr16, port_v6 );
        return ok ? n : -32L;
      }
      if( fam == EmuSocket.AF_INET ) {
        SockaddrIn sa = loadSockaddrIn( dest_addr );
        boolean ok = sendto( (int)fd, buf, (int)flags, sa.ipForLegacy, sa.port );
        return ok ? n : -32L;
      }
    }
    // dest_addr 未指定 = 接続済み socket への send
    // issue #9: AF_INET6 UDP の connected dest があれば v6 send
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo != null && finfo.family_v6 && !finfo.isSTREAM() && finfo.connected_v6_addr != null ) {
      boolean ok = finfo.sendto_v6( buf, finfo.connected_v6_addr, finfo.connected_v6_port );
      return ok ? n : -32L;
    }
    if( !FileWrite( (int)fd, buf ) ) return -32L; // EPIPE
    return n;
  }

  private long amd64_recvfrom( long fd, long buf_addr, long len, long flags, long src_addr, long addrlen_ptr ) {
    int n = (int)len;
    if( n < 0 ) return -22L;
    byte[] buf = new byte[n];
    Fileinfo finfo = get_finfo( (int)fd );
    int r;
    int MSG_PEEK = 2;
    if( finfo != null && finfo.isSTREAM() ) {
      if( ((int)flags & MSG_PEEK) != 0 ) {
        r = finfo.Peek( buf );
        if( System.getenv("EMULIN_TRACE_NET") != null )
          System.err.println("PEEK fd="+fd+" req="+n+" got="+r);
      } else {
        r = finfo.Read( buf );
        if( System.getenv("EMULIN_TRACE_NET") != null )
          System.err.println("RECV fd="+fd+" req="+n+" got="+r);
      }
      if( r == -2 ) return -11L;  // EAGAIN (Fileinfo.Read の sentinel)
      if( r < 0 ) return -104L;
    } else if( finfo != null && finfo.family_v6 ) {
      // issue #9: AF_INET6 UDP — src を sockaddr_in6 (28 byte) で返す
      byte[] addr16 = new byte[16];
      int[] portOut = new int[1];
      r = finfo.recvfrom_v6( buf, addr16, portOut );
      if( r < 0 ) return -104L;
      if( src_addr != 0 ) {
        mem.store16( src_addr,     (short)EmuSocket.AF_INET6 );
        int p = portOut[0];
        mem.store16( src_addr + 2, (short)(((p & 0xFF) << 8) | ((p >>> 8) & 0xFF)) );
        mem.store32( src_addr + 4, 0 );  // flowinfo
        for( int i = 0; i < 16; i++ ) mem.store8( src_addr + 8 + i, addr16[i] );
        mem.store32( src_addr + 24, 0 ); // scope_id
        if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 28 );
      }
    } else {
      int[] addr_info = new int[2];
      r = recvfrom( (int)fd, buf, (int)flags, addr_info );
      if( r < 0 ) return -104L;
      if( src_addr != 0 ) {
        mem.store16( src_addr,     (short)EmuSocket.AF_INET );
        int p = addr_info[1];
        mem.store16( src_addr + 2, (short)(((p & 0xFF) << 8) | ((p >>> 8) & 0xFF)) );
        // addr_info[0] は BE int (swap32 済) なので、store32 (LE 書き出し) の
        // 前にもう一度 swap して in-memory がネットワーク順 [a,b,c,d] に
        // なるようにする (getsockname と同じパターン)。
        mem.store32( src_addr + 4, Util.swap32( addr_info[0] ));
        mem.store64( src_addr + 8, 0 );
        if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 16 );
      }
    }
    if( r > 0 ) mem.bulkStoreToMem( buf_addr, buf, 0, r );
    return r;
  }

  // struct msghdr (56 byte on x86_64):
  //   +0  void *msg_name           (8) — dest sockaddr (UDP) or NULL (TCP)
  //   +8  socklen_t msg_namelen    (4 + 4 pad)
  //   +16 struct iovec *msg_iov    (8) — array of iovec (each = base+len = 16 byte)
  //   +24 size_t msg_iovlen        (8)
  //   +32 void *msg_control        (8) — ancillary data (cmsg)
  //   +40 size_t msg_controllen    (8)
  //   +48 int msg_flags            (4 + 4 pad)
  private long amd64_sendmsg( long fd, long msghdr_addr, long flags ) {
    long name_addr = mem.load64( msghdr_addr + 0 );
    int  namelen   = (int)mem.load32( msghdr_addr + 8 );
    long iov_addr  = mem.load64( msghdr_addr + 16 );
    long iov_count = mem.load64( msghdr_addr + 24 );
    // 全 iov を結合した 1 バッファに収集 (DNS query は大抵 1 datagram)
    long total_len = 0;
    for( long i = 0; i < iov_count; i++ ) {
      total_len += mem.load64( iov_addr + i*16 + 8 );
    }
    byte[] buf = new byte[(int)total_len];
    int off = 0;
    for( long i = 0; i < iov_count; i++ ) {
      long base = mem.load64( iov_addr + i*16 );
      long sz   = mem.load64( iov_addr + i*16 + 8 );
      // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
      mem.bulkLoadFromMem( base, buf, off, (int)sz );
      off += (int)sz;
    }
    // msg_name 指定があれば sendto (UDP datagram の dest 指定経路)。
    //   無ければ connected socket への send 相当 (TCP / connected UDP)。
    if( name_addr != 0 && namelen >= 16 ) {
      int fam = mem.load16( name_addr ) & 0xFFFF;
      // issue #9: AF_INET6 dest
      if( fam == EmuSocket.AF_INET6 && namelen >= 28 ) {
        Fileinfo finfo = get_finfo( (int)fd );
        if( finfo == null || !finfo.isSOCKET() ) return -9L;
        int portBE = mem.load16( name_addr + 2 ) & 0xFFFF;
        int port_v6 = ((portBE & 0xFF) << 8) | ((portBE >>> 8) & 0xFF);
        byte[] addr16 = new byte[16];
        for( int i = 0; i < 16; i++ ) addr16[i] = (byte)(mem.load8( name_addr + 8 + i ) & 0xFF);
        if( System.getenv("EMULIN_TRACE_NET") != null )
          System.err.println("SENDMSG-UDP-V6 fd="+fd+" len="+buf.length+" -> port="+port_v6);
        boolean ok = finfo.sendto_v6( buf, addr16, port_v6 );
        return ok ? buf.length : -32L;
      }
      if( fam == EmuSocket.AF_INET ) {
        SockaddrIn sa = loadSockaddrIn( name_addr );
        if( System.getenv("EMULIN_TRACE_NET") != null )
          System.err.println("SENDMSG-UDP fd="+fd+" len="+buf.length+" -> "+Util.ip_str(Util.swap32(sa.ipForLegacy))+":"+sa.port);
        boolean ok = sendto( (int)fd, buf, (int)flags, sa.ipForLegacy, sa.port );
        return ok ? buf.length : -32L;
      }
    }
    Fileinfo finfo = get_finfo( (int)fd );
    // issue #9: connected AF_INET6 UDP — finfo.connected_v6_addr が立っていれば v6 send
    if( finfo != null && finfo.family_v6 && !finfo.isSTREAM() && finfo.connected_v6_addr != null ) {
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("SENDMSG-UDP-V6-CONN fd="+fd+" len="+buf.length+" port="+finfo.connected_v6_port);
      boolean ok = finfo.sendto_v6( buf, finfo.connected_v6_addr, finfo.connected_v6_port );
      return ok ? buf.length : -32L;
    }
    if( finfo != null && !finfo.isSTREAM() ) {
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("SENDMSG-UDP-CONN fd="+fd+" len="+buf.length+" -> ip="+finfo.get_ip_address()+" port="+finfo.get_port());
      // connected UDP: finfo.ip / finfo.port (connect で設定済) に送る
      boolean ok = sendto( (int)fd, buf, (int)flags, finfo.get_ip_address(), finfo.get_port() );
      return ok ? buf.length : -32L;
    }
    if( !FileWrite( (int)fd, buf ) ) return -32L;
    return buf.length;
  }

  private long amd64_recvmsg( long fd, long msghdr_addr, long flags ) {
    long name_addr   = mem.load64( msghdr_addr + 0 );
    int  namelen_max = (int)mem.load32( msghdr_addr + 8 );
    long iov_addr    = mem.load64( msghdr_addr + 16 );
    long iov_count   = mem.load64( msghdr_addr + 24 );
    long total_max   = 0;
    for( long i = 0; i < iov_count; i++ ) total_max += mem.load64( iov_addr + i*16 + 8 );
    byte[] buf = new byte[(int)total_max];
    int r;
    Fileinfo finfo = get_finfo( (int)fd );
    int[] addr_info = new int[2];
    if( finfo != null && finfo.family_v6 && !finfo.isSTREAM() ) {
      // issue #9: AF_INET6 UDP — src を sockaddr_in6 (28 byte) で返す
      byte[] addr16 = new byte[16];
      int[] portOut = new int[1];
      r = finfo.recvfrom_v6( buf, addr16, portOut );
      if( r < 0 ) return -104L;
      if( name_addr != 0 && namelen_max >= 28 ) {
        mem.store16( name_addr,     (short)EmuSocket.AF_INET6 );
        int p = portOut[0];
        mem.store16( name_addr + 2, (short)(((p & 0xFF) << 8) | ((p >>> 8) & 0xFF)) );
        mem.store32( name_addr + 4, 0 );  // flowinfo
        for( int i = 0; i < 16; i++ ) mem.store8( name_addr + 8 + i, addr16[i] );
        mem.store32( name_addr + 24, 0 ); // scope_id
        mem.store32( msghdr_addr + 8, 28 );  // msg_namelen
      }
    } else if( finfo != null && !finfo.isSTREAM() ) {
      r = recvfrom( (int)fd, buf, (int)flags, addr_info );
      if( r < 0 ) return -104L;
      // 受信元アドレスを msg_name に書き戻す (UDP)
      if( name_addr != 0 && namelen_max >= 16 ) {
        mem.store16( name_addr,     (short)EmuSocket.AF_INET );
        int p = addr_info[1];
        mem.store16( name_addr + 2, (short)(((p & 0xFF) << 8) | ((p >>> 8) & 0xFF)) );
        // BE int を store32 LE 書き出しのため再度 swap (getsockname と同じ)
        mem.store32( name_addr + 4, Util.swap32( addr_info[0] ));
        mem.store64( name_addr + 8, 0 );
        mem.store32( msghdr_addr + 8, 16 );  // msg_namelen
      }
    } else {
      // TCP / 通常 socket: stream 経由で読む
      r = (finfo != null && finfo.isSTREAM()) ? finfo.Read( buf ) : 0;
      if( r == -2 ) return -11L;
      if( r < 0 ) return -104L;
      if( name_addr != 0 ) mem.store32( msghdr_addr + 8, 0 );
    }
    // 結果を iov[] に分配
    int off = 0;
    for( long i = 0; i < iov_count && off < r; i++ ) {
      long base = mem.load64( iov_addr + i*16 );
      long sz   = mem.load64( iov_addr + i*16 + 8 );
      int n2 = Math.min( (int)sz, r - off );
      // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
      mem.bulkStoreToMem( base, buf, off, n2 );
      off += n2;
    }
    mem.store32( msghdr_addr + 48, 0 );  // msg_flags = 0
    return r;
  }

  // sendmmsg(fd, msgvec, vlen, flags): 各 mmsghdr の msg_hdr で sendmsg を
  //   呼び、msg_len に結果を書き戻す。msg_hdr は 56 byte、msg_len 4 byte
  //   + 4 byte padding = 64 byte/エントリ。
  private long amd64_sendmmsg( long fd, long msgvec, long vlen, long flags ) {
    int n = (int)vlen;
    int sent = 0;
    for( int i = 0; i < n; i++ ) {
      long ent = msgvec + (long)i * 64L;
      long r = amd64_sendmsg( fd, ent, flags );
      if( r < 0 ) return sent > 0 ? sent : r;
      mem.store32( ent + 56, (int)r );
      sent++;
    }
    return sent;
  }

  // recvmmsg(fd, msgvec, vlen, flags, timeout): 同上 recv 版。
  private long amd64_recvmmsg( long fd, long msgvec, long vlen, long flags, long timeout ) {
    int n = (int)vlen;
    int recvd = 0;
    for( int i = 0; i < n; i++ ) {
      long ent = msgvec + (long)i * 64L;
      long r = amd64_recvmsg( fd, ent, flags );
      if( r < 0 ) return recvd > 0 ? recvd : r;
      mem.store32( ent + 56, (int)r );
      recvd++;
    }
    return recvd;
  }

  // issue #109: amd64 の uname machine は "x86_64" (base の "i386" を override)。
  @Override
  protected String unameMachine( ) { return "x86_64"; }

  private long amd64_getsockopt( long fd, long level, long optname, long optval, long optlen_ptr ) {
    // SO_ERROR (=4) は 0 を返す = 接続成功。それ以外も大半は 0 で OK。
    //   optlen_ptr が NULL でも optval には書く必要がある (curl が
    //   getsockopt(fd, SOL_SOCKET, SO_ERROR, &v, &len) で len=4 を期待し、
    //   v が初期化されないとスタックゴミを「エラーコード」として読んで
    //   curl: (7) Failed to connect になる)。
    // issue #41 (sshd): SOL_IP (=0) / IP_OPTIONS (=4) は「IP optionが無い」
    //   ことを示すため optlen=0 を返す。4 byte ゼロを返すと sshd が
    //   "Connection from ... with IP opts:  00 00 00 00" を出した後
    //   IP source routing detection で cleanup_exit(255) する。
    //   実 Linux も「IP options 無し」は optlen=0 で返す。
    if( level == 0 /* SOL_IP */ && optname == 4 /* IP_OPTIONS */ ) {
      if( optlen_ptr != 0 ) mem.store32( optlen_ptr, 0 );
      return 0;
    }
    // SO_TYPE (=3): socket の種別を返す。0 のままだと CPython の ssl module が
    //   getsockopt(SOL_SOCKET, SO_TYPE) != SOCK_STREAM で wrap_socket を弾き、
    //   pip 等の HTTPS が "only stream sockets are supported" で失敗する。
    //   emulin は Fileinfo.stream_flag で type を追跡しているのでそれを返す。
    if( level == 1 /* SOL_SOCKET */ && optname == 3 /* SO_TYPE */ ) {
      Fileinfo finfo = get_finfo( (int)fd );
      int stype = ( finfo != null && !finfo.stream_flag ) ? 2 /* SOCK_DGRAM */ : 1 /* SOCK_STREAM */;
      if( optval != 0 ) mem.store32( optval, stype );
      if( optlen_ptr != 0 ) mem.store32( optlen_ptr, 4 );
      return 0;
    }
    int olen = 4;
    if( optlen_ptr != 0 ) olen = mem.load32( optlen_ptr );
    if( olen <= 0 ) olen = 4;
    if( optval != 0 ) {
      for( int i = 0; i < Math.min(olen, 4); i++ ) mem.store8( optval + i, 0 );
    }
    if( optlen_ptr != 0 ) mem.store32( optlen_ptr, Math.min(olen, 4) );
    return 0;
  }

  private long amd64_getsockname( long fd, long addr_ptr, long addrlen_ptr ) {
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo == null || !finfo.isSOCKET() ) return -88L;  // ENOTSOCK
    // issue #9: AF_INET6 socket は sockaddr_in6 (28 byte) で返す。
    //   unbound (conn/dgram/sconn 全部 null) のときは :: + port 0 を返す。
    //   UDP は make_server_socket(-1) で eagerly に ephemeral port に bind
    //   されているので、port は get_local_port() で local bound port を取る。
    //   (get_port は connected UDP で dest port を返すので getsockname 不適。)
    // glibc getaddrinfo の source address selection は「v4-mapped dest に
    //   connect した v6 socket の getsockname は v4-mapped source を返す」
    //   ことを assert (IN6_IS_ADDR_V4MAPPED)。connected_v6_addr が v4-mapped
    //   (::ffff:a.b.c.d) なら local 側も ::ffff:0.0.0.0 を返す。
    if( finfo.family_v6 ) {
      int port = (finfo.conn != null || finfo.dgram != null || finfo.sconn != null) ? get_local_port( (int)fd ) : 0;
      if( System.getenv("EMULIN_TRACE_NET") != null ) {
        String dstStr = "<none>";
        if( finfo.connected_v6_addr != null ) {
          StringBuilder sb = new StringBuilder();
          for( byte b : finfo.connected_v6_addr ) sb.append(String.format("%02x", b & 0xFF));
          dstStr = sb.toString();
        }
        System.err.println("GETSOCKNAME-V6 fd="+fd+" port="+port+" dst="+dstStr+" STREAM="+finfo.isSTREAM());
      }
      mem.store16( addr_ptr,     (short)EmuSocket.AF_INET6 );
      mem.store16( addr_ptr + 2, (short)(((port & 0xFF) << 8) | ((port >>> 8) & 0xFF)) );
      mem.store32( addr_ptr + 4, 0 );          // flowinfo
      // glibc getaddrinfo の source addr selection (nss/getaddrinfo.c:2542) は
      //   AF_INET6 socket に対する getsockname が v4-mapped を返すことを assert
      //   する path がある (q->ai_family == AF_INET && af == AF_INET6 の fallback)。
      //   実 Linux dual-stack では、v4 / v4-mapped dest に connect 後の v6
      //   getsockname は v4-mapped local を返す。emulator では:
      //   - connected_v6_addr が v4-mapped (::ffff:a.b.c.d) → v4-mapped で返す
      //   - connected_v6_addr が :: or null + UDP (dgram あり) → 同じく v4-mapped
      //     にしておく方が glibc の assert を満たし、現実の用途 (DNS resolver /
      //     sshd) でも問題ない。
      //   STREAM (TCP) は dual-stack 挙動が複雑なので従来通り :: を維持。
      boolean v4mapped_dest = false;
      if( finfo.connected_v6_addr != null && finfo.connected_v6_addr.length == 16 ) {
        byte[] d = finfo.connected_v6_addr;
        v4mapped_dest = (d[0]==0 && d[1]==0 && d[2]==0 && d[3]==0
                      && d[4]==0 && d[5]==0 && d[6]==0 && d[7]==0
                      && d[8]==0 && d[9]==0
                      && d[10]==(byte)0xFF && d[11]==(byte)0xFF);
      }
      // issue #41 (sshd): v6 UDP は connected_v6_addr の有無/内容に関係なく
      //   v4-mapped で返して glibc assert を通す。dgram があれば確定。
      if( finfo.dgram != null && !finfo.isSTREAM() ) {
        v4mapped_dest = true;
      }
      if( v4mapped_dest ) {
        for( int i = 0; i < 10; i++ ) mem.store8( addr_ptr + 8 + i, 0 );
        mem.store8( addr_ptr + 8 + 10, (byte)0xFF );
        mem.store8( addr_ptr + 8 + 11, (byte)0xFF );
        for( int i = 12; i < 16; i++ ) mem.store8( addr_ptr + 8 + i, 0 );
      } else {
        for( int i = 0; i < 16; i++ ) mem.store8( addr_ptr + 8 + i, 0 ); // :: addr
      }
      mem.store32( addr_ptr + 24, 0 );         // scope_id
      if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 28 );
      return 0;
    }
    // AF_INET の場合。unbound でも 0.0.0.0:0 を返す (real Linux 仕様)。
    //   issue #78: listening socket は sconn を持ち conn は null なので、旧実装
    //   (conn != null のときだけ port 返却) だと node の listen(0) で
    //   s.address().port が 0 になり HTTP server が機能しなかった。v6 経路と
    //   同様に conn/sconn/dgram を見て get_local_port で実 bound port を返す。
    int ip   = (finfo.conn != null) ? get_ip_address( (int)fd ) : 0;
    int port = (finfo.conn != null || finfo.sconn != null || finfo.dgram != null) ? get_local_port( (int)fd ) : 0;
    mem.store16( addr_ptr,     (short)EmuSocket.AF_INET );
    mem.store16( addr_ptr + 2, (short)(((port & 0xFF) << 8) | ((port >>> 8) & 0xFF)) );
    // get_ip_address は BE int を返す。store32 は LE 書き出しなので、もう一度
    // swap して in-memory がネットワーク順 [a, b, c, d] になるようにする。
    // (i386 の sys_accept も同パターンで Util.swap32 を二重に掛けている)
    mem.store32( addr_ptr + 4, Util.swap32( ip ));
    mem.store64( addr_ptr + 8, 0 );
    if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 16 );
    return 0;
  }

  private long amd64_getpeername( long fd, long addr_ptr, long addrlen_ptr ) {
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo == null || !finfo.isSOCKET() ) return -88L;  // ENOTSOCK
    // issue #9: peer が居ない (= 未接続) なら ENOTCONN
    if( finfo.conn == null ) return -107L;  // ENOTCONN
    int ip   = get_partner_ip_address( (int)fd );
    int port = get_partner_port( (int)fd );
    mem.store16( addr_ptr,     (short)EmuSocket.AF_INET );
    mem.store16( addr_ptr + 2, (short)(((port & 0xFF) << 8) | ((port >>> 8) & 0xFF)) );
    mem.store32( addr_ptr + 4, Util.swap32( ip ));
    mem.store64( addr_ptr + 8, 0 );
    if( addrlen_ptr != 0 ) mem.store32( addrlen_ptr, 16 );
    return 0;
  }

  private long amd64_socketpair( long domain, long type, long protocol, long fds_addr ) {
    // 双方向 socketpair: 2 つの pipe を作る
    //   pipe A: fd[0] writes → fd[1] reads
    //   pipe B: fd[1] writes → fd[0] reads
    // 各 fd は両端 (in + out) として開き、Fileinfo.set_pipe_pair で
    //   read 用 pipe_no と write 用 pipe_write_no を別々に持たせる。
    int fd0 = FileOpen( "<pipe>", "r", O_RDWR );  // 一旦 in として作る
    int fd1 = FileOpen( "<pipe>", "r", O_RDWR );
    int pa = sysinfo.kernel.connect_pipe( );
    int pb = sysinfo.kernel.connect_pipe( );
    Fileinfo f0 = get_finfo( fd0 );
    Fileinfo f1 = get_finfo( fd1 );
    if( f0 == null || f1 == null ) return -1L;
    // fd[0]: read from pb, write to pa
    f0.set_pipe_pair( pb, pa );
    // fd[1]: read from pa, write to pb
    f1.set_pipe_pair( pa, pb );
    // SOCK_CLOEXEC (0x80000) / SOCK_NONBLOCK (0x800) を反映
    if( ((int)type & 0x80000) != 0 ) { set_cloexec( fd0, true ); set_cloexec( fd1, true ); }
    if( ((int)type & 0x800)   != 0 ) { f0.nonBlock = true; f1.nonBlock = true; }
    mem.store32( fds_addr,     fd0 );
    mem.store32( fds_addr + 4, fd1 );
    return 0;
  }

  // dup3(oldfd, newfd, flags) — flags は O_CLOEXEC (0x80000) のみ。
  //   dup2 と違い、oldfd == newfd は EINVAL。
  //   Phase 27 step 39: O_CLOEXEC を new fd に反映。
  private long amd64_dup3( long oldfd_l, long newfd_l, long flags ) {
    int oldfd = (int)oldfd_l;
    int newfd = (int)newfd_l;
    if( oldfd == newfd ) return -22L; // EINVAL
    long ret = sys_dup2( oldfd_l, newfd_l, 0, 0, 0 );
    if( ret >= 0 && (flags & 0x80000) != 0 ) {
      set_cloexec( newfd, true );
    }
    return ret;
  }

  private long amd64_pipe( long array_addr, long flags ) {
    int ret_in  = FileOpen( "<pipe>", "r",  O_RDONLY );
    int ret_out = FileOpen( "<pipe>", "rw", O_WRONLY );
    mem.store32( array_addr,     ret_in );
    mem.store32( array_addr + 4, ret_out );
    int pipe_no = sysinfo.kernel.connect_pipe( );
    set_pipe( pipe_no, ret_in );
    set_pipe( pipe_no, ret_out );
    // pipe2(flags) で O_NONBLOCK (0x800) が指定されたら両端 fd の Fileinfo
    //   に反映。curl が AsynchDNS 用の non-blocking pipe で必要。
    if( (flags & 0x800) != 0 ) {
      Fileinfo f_in  = get_finfo( ret_in );
      Fileinfo f_out = get_finfo( ret_out );
      if( f_in  != null ) f_in.nonBlock  = true;
      if( f_out != null ) f_out.nonBlock = true;
    }
    // pipe2(O_CLOEXEC=0x80000): execve 越しに自動 close される。
    //   git の child notify pipe で必須 (Phase 27 step 39)。
    if( (flags & 0x80000) != 0 ) {
      set_cloexec( ret_in, true );
      set_cloexec( ret_out, true );
    }
    return 0;
  }

  // gettimeofday(tv, tz) — tv は struct timeval {long tv_sec; long tv_usec;}
  private long amd64_gettimeofday( long tv_addr, long tz_addr ) {
    if( tv_addr != 0 ) {
      if( DET_CLOCK ) {  // issue #113: 決定的 clock
        long us = detClockUs();
        mem.store64( tv_addr,     us / 1_000_000L );
        mem.store64( tv_addr + 8, us % 1_000_000L );
      } else {
      long ms = System.currentTimeMillis();
      mem.store64( tv_addr,     ms / 1000L );
      mem.store64( tv_addr + 8, (ms % 1000L) * 1000L );
      }
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

  // clock_nanosleep(clockid, flags, req, rem) — #230。
  //   issue #113: 旧 stub は即 return で実際に sleep せず、gpg-agent の
  //   housekeeping tick スレッドが stat+clock_nanosleep を busy-spin して
  //   npth (協調スレッド) で accept スレッドを starve させ、gpg-agent が接続を
  //   受けられず emacs (package 署名検証) がハングしていた。実際に sleep する。
  //   flags=0 は相対 (nanosleep と同じ)、flags&TIMER_ABSTIME(1) は絶対時刻まで。
  private static final int TIMER_ABSTIME = 1;
  private long amd64_clock_nanosleep( long clockid, long flags, long req_addr, long rem_addr ) {
    long sec  = mem.load64( req_addr );
    long nsec = mem.load64( req_addr + 8 );
    if( sec < 0 || nsec < 0 || nsec >= 1_000_000_000L ) return -22; // EINVAL
    long ms;
    if( ((int)flags & TIMER_ABSTIME) != 0 ) {
      // 絶対時刻まで: CLOCK_REALTIME は epoch ms との差。他 clock も近似で wall
      //   clock 基準で扱う (gpg-agent は CLOCK_REALTIME + 相対なので主経路は下)。
      long target_ms = sec * 1000L + nsec / 1_000_000L;
      ms = target_ms - System.currentTimeMillis();
    } else {
      ms = sec * 1000L + nsec / 1_000_000L;
      // sub-ms (sec=0, nsec<1e6) でも busy-spin しないよう最低 1ms 寝る。
      if( ms == 0 && nsec > 0 ) ms = 1;
    }
    if( ms > 0 ) {
      try { Thread.sleep( ms ); }
      catch( InterruptedException e ) { /* EINTR は無視 (full sleep 扱い) */ }
    }
    // req と rem が同一バッファのことがある (clock_nanosleep(clk,0,&ts,&ts))。
    //   kernel は EINTR 時のみ rem を書く。full sleep 完了時に rem を書くと
    //   reused req を 0 に破壊し、次回 ms=0 で sleep せず busy-spin するので
    //   rem は書かない。
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

  // exit_group(code) — process 全体を exit
  private long amd64_exit( long code ) {
    if( System.getenv("EMULIN_TRACE_WRITE") != null ) {
      System.err.println( "[exit_group] code="+(int)code );
    }
    sysinfo.kernel.last_exit_code = (int)code;
    process.exit_code = (int)code;
    process.set_exit_flag();
    return 0;
  }

  // exit(code) — Phase 27 step 28: pthread の場合は thread だけ exit。
  //   Phase 27 step 39: main thread が sys_exit を呼んだ時、worker
  //     thread が生きていれば「main の eval を抜けた状態」のまま worker
  //     の自然終了を待ち、最後に process 全体 exit する。Linux 仕様では
  //     main thread sys_exit でも process は worker が居る限り生きている
  //     (glibc の pthread_exit が main の場合に sys_exit するのに合う)。
  //     旧実装は即 process exit していたため git の AsynchDNS worker が
  //     宙ぶらりんで segfault していた (RIP=0x401525e9 系)。
  private long amd64_exit_thread( long code ) {
    Thread cur = Thread.currentThread();
    if( cur instanceof Thread64 ) {
      throw new ThreadExitException( (int)code );
    }
    // main thread: worker が居るなら全部 done になるまで待つ
    if( process.active_thread_count.get() > 0 ) {
      synchronized( process.active_thread_count ) {
        while( process.active_thread_count.get() > 0 ) {
          try { process.active_thread_count.wait( 100 ); }
          catch( InterruptedException ie ) { break; }
        }
      }
    }
    return amd64_exit( code );
  }

  // pthread thread exit 用の controlled exception
  static class ThreadExitException extends RuntimeException {
    final int code;
    ThreadExitException( int c ) { code = c; }
  }

  // poll(fds[], nfds, timeout_ms) — 全 fd を即時 ready にして返すスタブ。
  // 対話 ash の入力待ち poll() を回す程度の最小実装。実際のブロッキングは
  // 後続の read() 側で Std_read が System.in で待ってくれる。
  // struct pollfd { int fd; short events; short revents; } = 8 bytes
  private long amd64_poll( long fds_addr, long nfds, long timeout_ms ) {
    int n = (int)nfds;
    // issue #3-#5: 旧実装は 1 回 scan して即 return していたため、emacs が
    // poll(STDIN, -1) で blocking 待ちを期待しても TTY raw mode で
    // Available()=false なら 0 が即返り、emacs が busy loop で input を
    // 取れなくなる。pselect6 と同じ wait-loop 形式に refactor。
    long deadline_ms = (timeout_ms < 0) ? -1L : System.currentTimeMillis() + timeout_ms;
    if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
      // 最初の fd だけ表示 (emacs は通常 fd 0 = STDIN しか poll しない)
      int first_fd = (n > 0) ? (int)mem.load32(fds_addr) : -1;
      int first_ev = (n > 0) ? (mem.load16(fds_addr + 4) & 0xFFFF) : 0;
      System.err.println("DBG_POLL nfds="+n+" first_fd="+first_fd+" events=0x"+Integer.toHexString(first_ev)+" timeout_ms="+timeout_ms);
    }
    while( true ) {
      int ready = 0;
      boolean any_alive = false;
      for( int i=0; i<n; i++ ) {
        long ent = fds_addr + (long)i * 8L;
        int fd     = (int)mem.load32( ent + 0 );
        int events = mem.load16( ent + 4 ) & 0xFFFF;
        int revents = 0;
        Fileinfo finfo = (fd >= 0) ? get_finfo( fd ) : null;
        // POLLOUT (0x4) / POLLWRNORM (0x100): 書き込み可能。socket / pipe の
        //   書き込み端は基本いつでも writable とみなして OK。
        if( (events & 0x104) != 0 ) revents |= (events & 0x104);
        // POLLIN (0x1) / POLLRDNORM (0x40) / POLLPRI (0x2): 読める時だけ立てる。
        //   socket: peekBuf に残データ or 接続中 (= EOF 未) で データ available
        //   pipe:   PipeManager に問い合わせる手段が無いので「読める想定」
        //           を避け、データが無いなら立てない (= curl 等の wakeup pipe で
        //           「常に ready 詐欺」を起こさないため)
        //   通常 fd: 不明なので保守的に立てる (read で実際に block / EAGAIN が
        //           判定する)
        if( (events & 0x43) != 0 && finfo != null ) {
          if( finfo.isSOCKET() ) {
            boolean readable = (finfo.peekBuf != null && finfo.peekLen > 0) || !finfo.socketEof;
            // TCP socket: setSoTimeout 経由で実 read を試行 (Java の available()
            //   は内部 buffer しか見ないので kernel に到着済の data を見逃す)。
            //   読めたら peekBuf にキャッシュして次の Read で消費させる。
            //   wait-loop 化により各 iter の wait_ms は短く (10ms)。total は
            //   outer loop の deadline_ms が制御する。
            if( readable && finfo.conn != null ) {
              if( !finfo.socketEof ) any_alive = true;
              // 既に peekBuf にデータ → 即 ready
              if( finfo.peekBuf != null && finfo.peekLen > 0 ) {
                revents |= (events & 0x43);
              } else {
                int wait_ms = (timeout_ms == 0) ? 1 : 10;
                try {
                  java.io.InputStream is = finfo.conn.getInputStream();
                  if( is.available() > 0 ) {
                    revents |= (events & 0x43);
                  } else {
                    int prev = finfo.conn.getSoTimeout();
                    finfo.conn.setSoTimeout( wait_ms );
                    byte[] one = new byte[1];
                    try {
                      int n2 = is.read( one );
                      if( n2 > 0 ) {
                        // peekBuf にキャッシュ
                        byte[] nb = (finfo.peekBuf == null) ? new byte[1]
                                  : new byte[finfo.peekLen + 1];
                        if( finfo.peekBuf != null )
                          System.arraycopy( finfo.peekBuf, 0, nb, 0, finfo.peekLen );
                        nb[nb.length - 1] = one[0];
                        finfo.peekBuf = nb; finfo.peekLen = nb.length;
                        revents |= (events & 0x43);
                      } else if( n2 < 0 ) {
                        finfo.socketEof = true;
                        revents |= 0x10;  // POLLHUP
                      }
                    } catch ( java.net.SocketTimeoutException ste ) {
                      // 来てない — POLLIN 立てない
                    } finally {
                      finfo.conn.setSoTimeout( prev );
                    }
                  }
                } catch ( java.io.IOException ignored ) {
                  revents |= 0x10;  // POLLHUP
                }
              }
            }
            // UDP socket: DatagramSocket.available() が無いので setSoTimeout を
            //   短く設定して非 blocking receive を試行。受信できたら
            //   Fileinfo.cachedDatagram に積み、次の recvfrom が消費する。
            else if( finfo.dgram != null ) {
              any_alive = true;
              if( finfo.cachedDatagram != null ) {
                if( System.getenv("EMULIN_TRACE_NET") != null )
                  System.err.println("POLL-UDP fd="+fd+" cached → ready");
                revents |= (events & 0x43);
              } else {
                int wait_ms = (timeout_ms == 0) ? 1 : 10;
                try {
                  int prev = finfo.dgram.getSoTimeout();
                  finfo.dgram.setSoTimeout( wait_ms );
                  byte[] dbuf = new byte[ 65535 ];  // UDP max
                  java.net.DatagramPacket dp = new java.net.DatagramPacket( dbuf, dbuf.length );
                  try {
                    finfo.dgram.receive( dp );
                    finfo.cachedDatagram = dp;
                    if( System.getenv("EMULIN_TRACE_NET") != null )
                      System.err.println("POLL-UDP fd="+fd+" wait="+wait_ms+" RECEIVED "+dp.getLength()+" bytes from "+dp.getAddress());
                    revents |= (events & 0x43);
                  } catch ( java.net.SocketTimeoutException ste ) {
                    if( System.getenv("EMULIN_TRACE_NET") != null )
                      System.err.println("POLL-UDP fd="+fd+" wait="+wait_ms+" TIMEOUT");
                  } finally {
                    finfo.dgram.setSoTimeout( prev );
                  }
                } catch ( java.io.IOException e ) {
                  if( System.getenv("EMULIN_TRACE_NET") != null )
                    System.err.println("POLL-UDP fd="+fd+" IOException: "+e);
                  revents |= 0x10;  // POLLHUP
                }
              }
            }
            if( finfo.socketEof ) revents |= 0x10;  // POLLHUP
            // issue #43 Phase 4-4 完走: AF_UNIX SocketChannel の data
            //   availability を判定。sshd の channel multiplexer は accept
            //   した agent-connection fd の read ready を poll で判定する。
            //   読めるなら 1 byte を peek して finfo.peekBuf にキャッシュ
            //   → 次の Read で消費させる (TCP socket の peek 経路と同じ
            //   pattern)。
            else if( finfo.unixSocket != null ) {
              any_alive = true;
              if( finfo.peekBuf != null && finfo.peekLen > 0 ) {
                revents |= (events & 0x43);
              } else {
                try {
                  finfo.unixSocket.configureBlocking( false );
                  java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate( 1 );
                  int n2 = finfo.unixSocket.read( bb );
                  if( n2 > 0 ) {
                    byte b = bb.array()[0];
                    byte[] nb = (finfo.peekBuf == null) ? new byte[1]
                              : new byte[finfo.peekLen + 1];
                    if( finfo.peekBuf != null )
                      System.arraycopy( finfo.peekBuf, 0, nb, 0, finfo.peekLen );
                    nb[nb.length - 1] = b;
                    finfo.peekBuf = nb; finfo.peekLen = nb.length;
                    revents |= (events & 0x43);
                  } else if( n2 < 0 ) {
                    finfo.socketEof = true;
                    revents |= 0x10;  // POLLHUP
                  }
                } catch ( java.io.IOException ignored ) {
                  revents |= 0x10;  // POLLHUP
                }
              }
            }
            // issue #41 (sshd): listen socket (sconn) は SubProcess の
            //   accept_flag を覗いて、ACCEPT_DONE なら readable と報告。
            //   sshd の main loop が select(listen_fd) で待っている。
            else if( finfo.sconn != null && finfo.subprocess != null ) {
              any_alive = true;
              if( finfo.subprocess.Accepted() == SubProcess.ACCEPT_DONE ) {
                revents |= (events & 0x43);
                if( System.getenv("EMULIN_TRACE_NET") != null )
                  System.err.println("POLL-LISTEN fd="+fd+" ACCEPT_DONE → ready");
              }
            }
            // issue #43 Phase 4-4: AF_UNIX listening server。
            //   ServerSocketChannel を non-blocking にして accept() を試行、
            //   非 null なら queue に積んで「ready」と報告。
            //   queued な channel は次の accept() syscall が consume する。
            else if( finfo.unixServer != null ) {
              any_alive = true;
              if( finfo.unixQueued != null ) {
                revents |= (events & 0x43);
              } else {
                try {
                  finfo.unixServer.configureBlocking( false );
                  java.nio.channels.SocketChannel ch = finfo.unixServer.accept();
                  if( ch != null ) {
                    finfo.unixQueued = ch;
                    revents |= (events & 0x43);
                  }
                } catch ( java.io.IOException ignored ) {}
              }
            }
          }
          else if( finfo.is_pipe( true )) {
            // issue #41 (sshd): pipe / socketpair の read 端は実際に
            //   buffer に data がある時だけ POLLIN を立てる。旧実装は
            //   常に立てないため sshd の privsep monitor が socketpair
            //   write を ∞ poll で取りこぼし、preauth が応答待ちで hang。
            //   切断されている場合は POLLHUP を返す。
            if( !sysinfo.kernel.is_pipe_connected( finfo.pipe_no ) ) {
              revents |= 0x10;  // POLLHUP
            } else {
              any_alive = true;
              if( sysinfo.kernel.pipe_available( finfo.pipe_no ) > 0 ) {
                revents |= (events & 0x43);
              }
            }
          }
          else {
            // Phase 30 follow-up8: native TTY (raw / cooked 問わず) は実 input
            // availability を見る。「常に ready」だと:
            //   - raw mode bash readline: typed char を Enter まで batching
            //   - cooked mode ssh post-auth (issue #55): stdin poll が常に
            //     ready 返却 → ssh が blocking read(stdin) → 永久 hang
            // どちらも Available() check で解決する。pipe stdin / dumb
            // terminal (= is_native_tty()=false) は従来通り「常に ready」。
            boolean tty = finfo.isSTD() || finfo.isERR();
            if( tty && sysinfo.kernel.console.is_native_tty() ) {
              if( sysinfo.kernel.console.Available() ) revents |= (events & 0x43);
              any_alive = true;
            } else {
              revents |= (events & 0x43);
            }
          }
        }
        mem.store16( ent + 6, (short)revents );
        if( revents != 0 ) ready++;
      }
      if( ready > 0 ) return ready;
      if( timeout_ms == 0 ) return 0;       // non-blocking poll
      if( !any_alive ) return 0;             // 待つべき fd が無い
      if( deadline_ms >= 0 && System.currentTimeMillis() >= deadline_ms ) return 0;
      try { Thread.sleep( 10 ); } catch ( InterruptedException ie ) { return 0; }
    }
  }

  // ioctl — 64-bit address version
  private long amd64_ioctl( long fd_l, long req, long addr ) {
    int fd = (int)fd_l, i;
    int request = (int)req;
    long address = addr;
    boolean done = false;
    Fileinfo finfo = get_finfo( fd );
    if( TCGETS == request ) {
      // Phase 29: TCGETS は terminal でなければ -ENOTTY を返さなければ
      // ならない。glibc の isatty() は tcgetattr の戻り値 (0/-1+ENOTTY)
      // で判定する。pipe / socket / regular file で常に成功させると、
      // less が「stdin が tty」と誤認して "Missing filename" になる。
      // stdout/stderr (isSTD/isERR) は実 console なので OK、それ以外は
      // tty でないと判定。
      // issue #41 Phase 2: pty master / slave は tty として扱う。
      //   sshd の openpty 後の isatty(slave_fd) check を通すのに必要。
      boolean is_pty = finfo != null && (finfo.pty_master || finfo.pty_slave);
      if( !isSTD(fd) && !isERR(fd) && !is_pty ) {
        return -25L;  // ENOTTY
      }
      mem.store32( address, finfo.c_iflag  ); address+=4;
      mem.store32( address, finfo.c_oflag  ); address+=4;
      mem.store32( address, finfo.c_cflag  ); address+=4;
      mem.store32( address, finfo.c_lflag  ); address+=4;
      mem.store8 ( address, finfo.c_line   ); address+=1;
      for( i=0; i<19; i++ ) { mem.store8( address, finfo.c_cc[i] ); address++; }
      done = true;
    }
    // TCSETS (0x5402) / TCSETSW (0x5403) / TCSETSF (0x5404) は payload 同じ。
    // W は出力 drain、F は入出力 flush。emulator では同 set_parameter 動作。
    if( TCSETS==request || TCSETSW==request || request==0x5404 ) {
      int new_iflag = mem.load32( address ); address+=4;
      int new_oflag = mem.load32( address ); address+=4;
      int new_cflag = mem.load32( address ); address+=4;
      int new_lflag = mem.load32( address ); address+=4;
      byte new_line = (byte)mem.load8( address ); address+=1;
      byte[] new_cc = new byte[19];
      for( i=0; i<19; i++ ) { new_cc[i]=(byte)mem.load8(address); address++; }
      finfo.c_iflag = new_iflag; finfo.c_oflag = new_oflag;
      finfo.c_cflag = new_cflag; finfo.c_lflag = new_lflag;
      finfo.c_line  = new_line;
      System.arraycopy( new_cc, 0, finfo.c_cc, 0, 19 );
      // Phase 30 follow-up5: TTY は本来 device 単位の state なので、
      // STD/ERR/<std> 系すべての fd で termios を共有させる。これを
      // やらないと bash が tcsetattr(0, ECHO off) → tcgetattr(2) →
      // 「fd 2 はまだ ECHO on」と誤読して manual echo を抑制 (Windows
      // raw mode で typed char が表示されない真因)。
      if( isSTD(fd) || isERR(fd) ) {
        for( int xfd = 0; xfd < flist.size(); xfd++ ) {
          if( xfd == fd ) continue;
          Fileinfo xfi = (Fileinfo)flist.elementAt( xfd );
          if( xfi != null && (xfi.isSTD() || xfi.isERR()) ) {
            xfi.c_iflag = new_iflag; xfi.c_oflag = new_oflag;
            xfi.c_cflag = new_cflag; xfi.c_lflag = new_lflag;
            xfi.c_line  = new_line;
            System.arraycopy( new_cc, 0, xfi.c_cc, 0, 19 );
          }
        }
        sysinfo.kernel.console.set_parameter( finfo.c_lflag, finfo.c_iflag, finfo.c_oflag, finfo.c_cc );
      }
      done = true;
    }
    // TCSBRK (0x5409) / TCXONC (0x540A) / TCFLSH (0x540B): TTY 制御 — no-op
    // emacs / less / vi 等が startup で呼ぶ。flow control / break / queue flush。
    if( request == 0x5409 || request == 0x540A || request == 0x540B ) { done = true; }
    // 0x4B66 = KDGKBMODE: keyboard mode query — Linux console specific。
    // -ENOTTY で fall back (emacs のデフォルト assume) を使うのでここでは捕捉しない。
    if( TIOCGWINSZ == request ) {
      // Phase 22 step 3d: 可能なら JLine の現在の端末サイズを返す。
      // dumb terminal / 非 tty で 0 を返してきた場合は 25x80 にフォールバック。
      int rows = sysinfo.kernel.console.getRows( );
      int cols = sysinfo.kernel.console.getColumns( );
      if( rows <= 0 ) rows = 25;
      if( cols <= 0 ) cols = 80;
      mem.store16( address, (short)rows ); address+=2;
      mem.store16( address, (short)cols ); address+=2;
      mem.store16( address, (short)0    ); address+=2;
      mem.store16( address, (short)0    ); address+=2;
      done = true;
    }
    if( TIOCSWINSZ == request ) {
      // bash がジョブ起動時等に window size を host 側に伝える。
      // emulator では host 端末のサイズは emulator 側から変更できないので
      // 受信値は読み捨てて success を返す。
      done = true;
    }
    if( FIONBIO == request ) { done = true; }
    // FIONREAD (0x541B): socket / pipe / tty で読める byte 数を *addr に書く。
    //   glibc resolver は recvmsg 前にこれで応答パケットサイズを確認し、
    //   0 だと「応答なし」と判断する。UDP では cachedDatagram のサイズを、
    //   無ければ 0 を、TCP では Java InputStream.available() を使う。
    //   issue #3-#5 (d): emacs は pselect で fd 3 ready と判断した後、
    //   FIONREAD で「実際に読める byte 数」を確認する。0 が返ると select が
    //   ウソをついたと判断して read を skip する。TTY raw mode で
    //   console.Available() が true なら最低 1 byte を返さなければならない。
    if( request == 0x541B ) {
      int avail = 0;
      if( finfo != null ) {
        if( finfo.cachedDatagram != null ) {
          avail = finfo.cachedDatagram.getLength();
        } else if( finfo.dgram != null ) {
          // ポーリング側で受信を試行 (短い setSoTimeout)。受信できたら
          // cachedDatagram に積む = 次の recvfrom が消費できる状態に。
          try {
            int prev = finfo.dgram.getSoTimeout();
            finfo.dgram.setSoTimeout( 1 );
            byte[] dbuf = new byte[ 65535 ];
            java.net.DatagramPacket dp = new java.net.DatagramPacket( dbuf, dbuf.length );
            try {
              finfo.dgram.receive( dp );
              finfo.cachedDatagram = dp;
              avail = dp.getLength();
            } catch ( java.net.SocketTimeoutException ste ) {}
            finfo.dgram.setSoTimeout( prev );
          } catch ( java.io.IOException ignored ) {}
        } else if( finfo.peekBuf != null ) {
          avail = finfo.peekLen;
        } else if( finfo.conn != null ) {
          try { avail = finfo.conn.getInputStream().available(); }
          catch ( java.io.IOException ignored ) {}
        } else if( finfo.isSTD() || finfo.isERR() ) {
          // TTY: console から読める byte 数 (raw mode の JLine reader buffer)。
          // JLine の NonBlockingReader は ready() しか公開していないので、
          // 1 char ある = avail >= 1、無し = avail = 0 として返す。
          // emacs は「>= 1 ならとりあえず read」が判定基準なので 1 で十分。
          if( sysinfo.kernel.console.Available() ) avail = 1;
        }
      }
      mem.store32( address, avail );
      done = true;
    }
    // FIOCLEX (0x5451) / FIONCLEX (0x5450): close-on-exec の set/clear。
    //   Python 等が fd の CLOEXEC 設定で呼ぶ。fd の生存にしか影響しないので
    //   no-op で十分 (本物の close-on-exec は exec 経由でしか効かない)。
    if( request == 0x5451 || request == 0x5450 ) { done = true; }
    // TIOCGPGRP / TIOCSPGRP: ash の setjobctl 初期化ループが
    // tcgetpgrp() == getpgrp() を満たすまで killpg(SIGTTIN) を呼び続ける。
    // 自分の pgrp を返して 1 回でループを抜けさせる (job control 無効と等価)。
    if( TIOCGPGRP == request ) {
      // issue #102: tcsetpgrp で設定済なら記録した fg pgrp を返す。未設定なら
      //   従来どおり自分の pgrp を返す (ash の setjobctl ループ互換)。bash の
      //   job-control 初期化は tcsetpgrp 後に tcgetpgrp で読み戻し shell_pgrp と
      //   一致するか検証するため、設定値を返さないと「cannot set terminal
      //   process group」で job control を諦める。
      //   pty fd は ptn 単位で共有 (bash は fd 0 で set, fd 255 で get する)。
      //   それ以外の tty fd (console 等) は fd 単位。未設定は自分の pgrp。
      int fg = -1;
      if( finfo != null && finfo.pty_ptn >= 0 )
        fg = sysinfo.kernel.pty.get_fg_pgrp( finfo.pty_ptn );
      else if( finfo != null && finfo.tty_fg_pgrp >= 0 )
        fg = finfo.tty_fg_pgrp;
      if( fg < 0 ) fg = (int)sys_getpgrp(0,0,0,0,0);
      mem.store32( address, fg );
      done = true;
    }
    if( TIOCSPGRP == request ) {
      // issue #102: 設定された foreground process group を記録 (tcgetpgrp が
      //   読み戻す)。pty fd は ptn 単位 (同一 pty の master/slave 全 fd で共有)、
      //   それ以外の tty fd は fd 単位で保持。
      if( finfo != null ) {
        int pgrp = mem.load32( address );
        if( finfo.pty_ptn >= 0 ) sysinfo.kernel.pty.set_fg_pgrp( finfo.pty_ptn, pgrp );
        else finfo.tty_fg_pgrp = pgrp;
      }
      done = true;
    }
    // issue #41 Phase 2: PTY ioctl
    //   TIOCGPTN  (0x80045430) : master fd → *addr に slave 番号 (ptn) を書く
    //   TIOCSPTLCK(0x40045431) : unlockpt = slave open を許可。emulator では
    //                            常に許可なので no-op で 0 返却
    //   TIOCGPTPEER(0x40045441): Linux 4.13+ で master から slave fd を直接
    //                            取得 (path 経由不要)。我々は path 経由が必要
    //                            なので -EINVAL を返す → glibc は ptsname
    //                            経由 open に fall back する
    if( request == 0x80045430 ) {  // TIOCGPTN
      if( finfo != null && finfo.pty_master ) {
        mem.store32( address, finfo.pty_ptn );
        done = true;
      } else {
        return -25L;  // -ENOTTY (master ではない fd)
      }
    }
    if( request == 0x40045431 ) {  // TIOCSPTLCK
      done = true;  // unlockpt は常に成功扱い
    }
    if( request == 0x5441 ) {  // TIOCGPTPEER (_IO('T', 0x41)) → fall back させる
      return -22L;  // -EINVAL → glibc は ptsname+open(/dev/pts/N) 経由に fall back
    }
    // issue #41 Phase 3: TIOCSCTTY (0x540e) と TIOCNOTTY (0x5422) を no-op
    //   success に。session leader が controlling tty を設定/解除する ioctl で、
    //   emulator では session/process group の概念が緩いので無視して OK。
    //   旧実装は "Unsupported ioctl" warning を出すが non-fatal だった。
    if( request == 0x540e || request == 0x5422 ) {  // TIOCSCTTY / TIOCNOTTY
      done = true;
    }
    // Phase 28-3i: FICLONE (0x40049409) / FICLONERANGE (0x4020940d)
    //   = btrfs/xfs reflink. cp が高速複製のために試す。我々の VFS は普通の
    //   read+write copy しかしないので "Operation not supported" を返して
    //   cp に fallback させる必要がある。0 (success) を返すと「複製した」
    //   と思い込んで実際には何もコピーされない致命的 silent failure になる。
    if( request == 0x40049409 || request == 0x4020940d ) return -95L;  // -EOPNOTSUPP
    // Phase 28-3i: 上記以外で未知の ioctl は -ENOTTY (-25) を返す。
    //   従来 0 (silent success) だったが、glibc/coreutils は ENOTTY を見て
    //   「この fd は ioctl 未対応」と判断して fallback する。0 success だと
    //   間違った結果を「成功」として処理してしまう。
    //   既知の ioctl はこれより上の case で done=true 済なので影響なし。
    if( !done ) {
      // 既知の noisy ioctl は warning 抑制 (-ENOTTY を返すのは同じ)。
      //   0x4B66 = KDGKBMODE: emacs の Linux console 検出
      //   0x5403 = TCSETSW: 一部の TTY 設定 (上の TCSETSF/W と区別しない実装あり)
      //   0x5600-0x5607 = VT_* (virtual terminal) ioctl family。
      //     nano / ncurses が「Linux VT console かどうか」を VT_GETSTATE
      //     (0x5603) 等で probe する。-ENOTTY を返せば「VT ではない」と
      //     正しく判断して generic terminal handling に fallback するので
      //     挙動は正しい。warning だけ抑制する。
      boolean silent = (request == 0x4B66)
                       || (request >= 0x5600 && request <= 0x5607);
      if( !silent ) {
        process.println( " Unsupported ioctl request=0x"+Integer.toHexString(request) );
      }
      return -25L;  // -ENOTTY
    }
    return 0;
  }

  // mremap(old_addr, old_size, new_size, flags, new_addr_if_fixed) — AMD64.
  //
  // Phase 28-3 修正: 旧実装は sys_mremap (i386 ABI) を直接呼んでいた。
  //   sys_mremap は bx を「stack pointer」と解釈して `(int)bx` でキャストし、
  //   stack 上の args を mem.load32 で読む i386 仕様。amd64 は register 直接
  //   渡しなので、bx (= rdi = old_addr 64-bit) を (int) すると上位 32-bit が
  //   失われ、続く mem.load32(bx + 4) が sign-extend されて 0xffffffff____
  //   になり segfault する。
  //
  //   git clone HTTPS の libcurl-gnutls 内 malloc が mremap で arena を
  //   resize しようとして発火していた。
  private long amd64_mremap( long old_addr, long old_size, long new_size, long flags, long new_addr_if_fixed ) {
    final long PAGE = 0x1000L;
    long aligned_new = (new_size + PAGE - 1) & ~(PAGE - 1);
    if( aligned_new <= 0 ) aligned_new = PAGE;
    // Try in-place resize first (most common — glibc malloc shrinks/grows arena)
    int rc = mem.realloc( old_addr, (int)aligned_new );
    if( rc == 0 ) return old_addr;
    // Failed in-place. MREMAP_MAYMOVE (1) flag allows relocation.
    final long MREMAP_MAYMOVE = 1L;
    if( (flags & MREMAP_MAYMOVE) == 0 ) return -12L;  // -ENOMEM
    long new_addr = mem.alloc_and_map( 0, (int)aligned_new, -1, 0 );
    if( new_addr == 0 ) return -12L;
    long copy_len = Math.min( old_size, new_size );
    for( long i = 0; i < copy_len; i++ ) {
      mem.store8( new_addr + i, mem.load8( old_addr + i ) );
    }
    mem.free( old_addr, (int)old_size );
    return new_addr;
  }

  // mmap(addr, len, prot, flags, fd, offset) — AMD64: 6 直接引数
  // Linux: ページ境界 (4KB) に切り上げてマップする。length 以下はファイル
  // 内容、それ以降ページ末尾までゼロ詰めで OK。
  // Phase 32: prot を AllocInfo に保持して fork 時の reference share 判定に使う。
  private long amd64_mmap( long addr, long length, long prot, long flags, long fd, long offset ) {
    final long PAGE = 0x1000L;
    long aligned = (length + PAGE - 1) & ~(PAGE - 1);
    if( aligned <= 0 ) aligned = PAGE;
    long result;
    if( aligned > 0x7FFFFFFFL && (int)fd < 0 ) {
      // multi-GB anonymous mmap (JSC gigacage / WASM cage 等)。Java byte[] の
      //   2GB 上限を超えるので sparse (chunk 遅延 alloc) で backing する。
      result = mem.alloc_huge( addr, aligned, (int)prot );
    } else {
      result = mem.alloc_and_map( addr, (int)aligned, (int)fd, (int)offset, (int)prot );
    }
    if( System.getenv("EMULIN_TRACE_MMAP") != null ) {
      System.err.println( "[mmap] addr=0x"+Long.toHexString(addr)+" len=0x"+Long.toHexString(length)
        +" prot=0x"+Long.toHexString(prot)+" flags=0x"+Long.toHexString(flags)+" fd="+(int)fd
        +" off=0x"+Long.toHexString(offset)+" => 0x"+Long.toHexString(result)
        +" .. 0x"+Long.toHexString(result+aligned) );
    }
    return result;
  }

  // stat の st_mode / st_uid / st_gid を path に応じて補正する共通 helper。
  //   - /dev/urandom / /dev/random: S_IFCHR | 0666 (OpenSSL の S_ISCHR チェック用)
  //   - issue #9: .ssh/ dir 及び配下 file は ssh の StrictModes 要求に
  //     合わせて 0700 / 0600 を強制、加えて owner も uid=0 (root) に偽装。
  //     NTFS 上では生 perm が "全員 readable"・owner が host user の uid
  //     (例 501) として見えるため、emulin /usr/bin/ssh が秘密鍵を
  //     "Permissions 0644 are too open" / config を "Bad owner or permissions"
  //     として拒否してしまうのを回避する。sandbox は単一ユーザ (root) で
  //     動く前提なので .ssh/* を全部 root 所有にして問題ない。
  //   AMD64 struct stat layout: st_mode(24,4) st_uid(28,4) st_gid(32,4)
  private void _fixup_stat_mode( long buf_addr, String name, Inode inode ) {
    if( "/dev/urandom".equals(name) || "/dev/random".equals(name) ) {
      mem.store32( buf_addr + 24, 0x21B6 );  // S_IFCHR | 0666
      return;
    }
    if( name.endsWith("/.ssh") || name.contains("/.ssh/") ) {
      // issue #68 Phase 2: explicit chmod が xattr に mode を保存していれば
      //   そちら (Inode.get_st_mode が反映済) を尊重し、0600/0700 偽装は
      //   しない。xattr 未保存 (= copy しただけで chmod していない鍵) の
      //   ときだけ従来の安全網として 0600/0700 を強制する。
      if( CygMode.enabled() ) {
        String np = sysinfo.get_native_path_nofollow( name );
        if( CygMode.getMode( np ) >= 0 ) {
          // 実 mode を尊重 (uid/gid だけ root に偽装は維持)
          mem.store32( buf_addr + 28, 0 );  // st_uid = 0 (root)
          mem.store32( buf_addr + 32, 0 );  // st_gid = 0 (root)
          return;
        }
      }
      if( inode.isDirectory() ) {
        mem.store32( buf_addr + 24, 0x41C0 );  // S_IFDIR | 0700
      } else {
        mem.store32( buf_addr + 24, 0x8180 );  // S_IFREG | 0600
      }
      mem.store32( buf_addr + 28, 0 );  // st_uid = 0 (root)
      mem.store32( buf_addr + 32, 0 );  // st_gid = 0 (root)
    }
  }

  // stat(path, buf) — AMD64 struct stat (144 bytes)
  private long amd64_stat( long path_addr, long buf_addr ) {
    String name = mem.loadString( path_addr );
    name = sysinfo.get_full_path( process.get_curdir(), name );
    // issue #41 Phase 2: /dev/ptmx と /dev/pts/N は character device として
    //   stat する。ttyname(3) は fstat(slave_fd) と stat(/dev/pts/N) の
    //   st_dev/st_rdev が一致することを要求 — _set_tty_stat64 で固定値を
    //   返す両者を一致させる。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // Phase 33-9: lstat(path, buf) — symlink を follow しない stat。
  // git は filesystem の symlink サポート test (.git/<random> -> testing) を
  // 「symlink → lstat → S_ISLNK チェック」で行い、true なら test 用 symlink
  // を unlink して core.symlinks=true を config に書く。
  // 旧実装は lstat=stat で symlink を follow し、target "testing" を探して
  // ENOENT。git は test 失敗と判定し cleanup せず → dangling symlink が
  // .git/ に残留 → rm -rf .git/ が rmdir で常に失敗。
  // NIO Files.readSymbolicLink で symlink 検出して S_IFLNK を返す。
  private long amd64_lstat( long path_addr, long buf_addr ) {
    String name = mem.loadString( path_addr );
    name = sysinfo.get_full_path( process.get_curdir(), name );
    // lstat は symlink 自身を見る → 最終 component は追従しない
    String native_path = sysinfo.get_native_path_nofollow( name );
    // issue #68: Cygwin mode はマジックファイルを symlink として報告
    String cyg_target = CygSymlink.enabled() ? CygSymlink.read( native_path ) : null;
    boolean is_symlink = ( cyg_target != null )
        || java.nio.file.Files.isSymbolicLink( java.nio.file.Paths.get( native_path ) );
    if( is_symlink ) {
      // symlink 自身の stat を返す: S_IFLNK | 0777, st_size = target 文字列長
      try {
        String target = ( cyg_target != null ) ? cyg_target
            : java.nio.file.Files.readSymbolicLink( java.nio.file.Paths.get( native_path ) ).toString();
        // 全 field を 0 で初期化
        for( int i = 0; i < 144; i += 8 ) mem.store64( buf_addr + i, 0L );
        // AMD64 struct stat: st_dev(0) st_ino(8) st_nlink(16) st_mode(24)
        // st_uid(28) st_gid(32) __pad0(36) st_rdev(40) st_size(48)
        mem.store64( buf_addr + 16, 1L );      // st_nlink = 1
        mem.store32( buf_addr + 24, 0xA1FF );  // st_mode = S_IFLNK (0xA000) | 0777
        mem.store64( buf_addr + 48, (long)target.length() ); // st_size = symlink 文字列長
        return 0;
      } catch( java.io.IOException e ) {
        return ENOENT;
      }
    }
    // 通常 file/dir は既存 amd64_stat 同等処理 (Inode 経由)
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // Phase 28-3i: at-syscall (mkdirat / fstatat / 等) 用 dirfd 解決ヘルパー。
  //   dirfd == AT_FDCWD or path 絶対 → process.get_curdir() を起点
  //   dirfd == 実 fd + path 相対 → get_name(dirfd) を起点
  //   dirfd 解決失敗時は null (caller は EBADF を返す)
  private String resolve_at_path( int dirfd, String path ) {
    final int AT_FDCWD = -100;
    if( dirfd == AT_FDCWD || path.startsWith( "/" ) ) {
      return sysinfo.get_full_path( process.get_curdir(), path );
    }
    String dirpath = get_name( dirfd );
    if( dirpath == null ) return null;
    return sysinfo.get_full_path( dirpath, path );
  }

  // openat(dirfd, pathname, flags, mode) — Phase 28-3i.
  //   find / cp -r が dirfd != AT_FDCWD で呼ぶ。dirfd を解決して
  //   open_resolved に渡すだけ。
  private long amd64_openat( int dirfd, long path_addr, long flags, long mode ) {
    String path = mem.loadString( path_addr );
    String name = resolve_at_path( dirfd, path );
    if( name == null ) return EBADF;
    return open_resolved( name, (int)flags );
  }

  // mkdirat(dirfd, pathname, mode) — Phase 28-3h.
  //   cp -r が destination directory を作るのに使う syscall (#258)。
  //   AT_FDCWD or 絶対パス: cwd を起点にした sys_mkdir と同じ。
  //   dirfd 実 fd + 相対パス: get_name(dirfd) で dir の path を取得して結合。
  private long amd64_mkdirat( int dirfd, long path_addr, int mode ) {
    String path = mem.loadString( path_addr );
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    Inode inode = new Inode( full, sysinfo );
    if( inode.isExists() ) return -17L;  // EEXIST
    if( !mkdir( full ) ) return -1L;     // EPERM
    return 0;
  }

  // fchmodat(dirfd, pathname, mode, flags) — issue #80。
  //   Emacs 28+ の対話 save (basic-save-buffer) は symlink を辿らないよう
  //   set-file-modes ... 'nofollow を使い、これが fchmodat(AT_SYMLINK_NOFOLLOW)
  //   を発行する。未実装だと「Doing chmod: Operation not supported」で save 失敗
  //   (古い版では process が落ちる)。chmod(90) と同じ do_chmod を at-path 解決
  //   して呼ぶ。AT_EMPTY_PATH は fd 自身の fchmod。
  //   flags の AT_SYMLINK_NOFOLLOW は emacs が regular file に使うので
  //   実害なし (symlink でない限り通常 chmod と同じ)。
  private long amd64_fchmodat( int dirfd, long path_addr, long mode, long flags ) {
    final int AT_EMPTY_PATH = 0x1000;
    String path = (path_addr != 0) ? mem.loadString( path_addr ) : "";
    if( (flags & AT_EMPTY_PATH) != 0 || path.isEmpty() ) {
      return sys_fchmod( dirfd, mode, 0, 0, 0 );  // fd 自身
    }
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    return do_chmod( full, (int)mode & 07777 );
  }

  // newfstatat(dirfd, path, buf, flags) — Phase 28-3i 改修。
  //   dirfd 実 fd + 相対パス を正しく解決するように。
  //   AT_EMPTY_PATH (0x1000) のときは fd 自身を fstat する。
  //   find が recursive 走査で fstatat(open_dir_fd, entry_name, ...) を
  //   呼ぶので、dirfd 解決がないと recurse 中に false ENOENT になる。
  private long amd64_newfstatat( int dirfd, long path_addr, long buf_addr, int flags ) {
    final int AT_EMPTY_PATH = 0x1000;
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    String path = (path_addr != 0) ? mem.loadString( path_addr ) : "";
    if( (flags & AT_EMPTY_PATH) != 0 || path.isEmpty() ) {
      return amd64_fstat( (long)dirfd, buf_addr );
    }
    String name = resolve_at_path( dirfd, path );
    if( name == null ) return EBADF;
    // issue #41 Phase 2: pty (path-based) は character device として stat。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    // Phase 33-9c: AT_SYMLINK_NOFOLLOW (= glibc lstat の実体) — path が
    // symlink なら target を follow せず symlink 自身の stat を返す。
    // 旧実装は flag 無視で target を follow し、broken symlink (e.g. git の
    // symlink test 用 .git/<rand> -> testing) で ENOENT 返却 → git は
    // 「symlink test 失敗」と誤判定し dangling symlink を残す → rm -rf
    // で .git/ rmdir 失敗。
    if( (flags & AT_SYMLINK_NOFOLLOW) != 0 ) {
      // 最終 component は追従しない (symlink 自身を見る)
      String native_path = sysinfo.get_native_path_nofollow( name );
      java.nio.file.Path p = java.nio.file.Paths.get( native_path );
      // issue #68: Cygwin マジックファイルも symlink として扱う
      String cyg_target = CygSymlink.enabled() ? CygSymlink.read( native_path ) : null;
      if( cyg_target != null || java.nio.file.Files.isSymbolicLink( p ) ) {
        try {
          String target = ( cyg_target != null ) ? cyg_target
              : java.nio.file.Files.readSymbolicLink( p ).toString();
          for( int i = 0; i < 144; i += 8 ) mem.store64( buf_addr + i, 0L );
          // Phase 33-11: rm/fts は st_ino=0 を「無効な entry」とみなして
          // skip するので、path から hash を取って non-zero にする。
          long fake_ino = ( name.hashCode() & 0xFFFFFFFFL );
          if( fake_ino == 0 ) fake_ino = 1;
          mem.store64( buf_addr +  0, 1L );         // st_dev (non-zero)
          mem.store64( buf_addr +  8, fake_ino );   // st_ino (non-zero)
          mem.store64( buf_addr + 16, 1L );         // st_nlink = 1
          mem.store32( buf_addr + 24, 0xA1FF );     // st_mode = S_IFLNK | 0777
          mem.store32( buf_addr + 28, 0x1F5 );      // st_uid = 501 (default)
          mem.store32( buf_addr + 32, 0x64 );       // st_gid = 100
          mem.store64( buf_addr + 48, (long)target.length() );  // st_size
          long now = System.currentTimeMillis() / 1000L;
          mem.store64( buf_addr + 72, now );        // st_atime
          mem.store64( buf_addr + 88, now );        // st_mtime
          mem.store64( buf_addr +104, now );        // st_ctime
          return 0;
        } catch( java.io.IOException e ) {
          return ENOENT;
        }
      }
    }
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // fstat(fd, buf) — AMD64 struct stat (144 bytes)
  private long amd64_fstat( long fd, long buf_addr ) {
    if( isSTD((int)fd) || isERR((int)fd) || isPIPE((int)fd) ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    // issue #10: 未 open / 範囲外 fd は EBADF (gpg-agent が未 open fd を
    //   fstat する経路で実際に発生)。
    Fileinfo dbg = get_finfo( (int)fd );
    if( dbg == null ) return EBADF;
    // issue #41 Phase 2: pty master / slave は character device として返す。
    //   ttyname(3) は fstat で S_ISCHR を確認後 readlink(/proc/self/fd/N) で
    //   path を取得する。S_IFREG だと ENOTTY で諦める。
    if( dbg.pty_master || dbg.pty_slave ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    String name = get_name( (int)fd );
    if( name == null ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir(), name );
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // AMD64 struct stat レイアウト (144 bytes、Linux x86-64 ABI):
  //   st_dev(8) st_ino(8) st_nlink(8) st_mode(4) st_uid(4) st_gid(4) __pad0(4)
  //   st_rdev(8) st_size(8) st_blksize(8) st_blocks(8)
  //   st_atime(8) st_atime_nsec(8) st_mtime(8) st_mtime_nsec(8)
  //   st_ctime(8) st_ctime_nsec(8) __unused[3](24)
  // Phase 27 step 22: 旧実装は st_size / st_atime 等を 32-bit mask で
  //   切り詰めていた (>2GB ファイル truncate / Y2038 wrap)。マスクを
  //   除去して Inode の long フィールドをそのまま使う。
  private void _set_file_stat64( long addr, Inode inode ) {
    mem.store64( addr,      inode.st_dev   & 0xFFFFL ); addr += 8;  // st_dev
    mem.store64( addr,      inode.st_ino   & 0xFFFFFFFFL ); addr += 8; // st_ino
    mem.store64( addr,      inode.st_nlink & 0xFFFFL ); addr += 8;  // st_nlink
    mem.store32( addr,      inode.st_mode  & 0xFFFF ); addr += 4;   // st_mode
    mem.store32( addr,      inode.st_uid   & 0xFFFF ); addr += 4;   // st_uid
    mem.store32( addr,      inode.st_gid   & 0xFFFF ); addr += 4;   // st_gid
    mem.store32( addr,      0              );           addr += 4;   // __pad0
    mem.store64( addr,      inode.st_rdev  & 0xFFFFL ); addr += 8;  // st_rdev
    mem.store64( addr,      inode.st_size  ); addr += 8;            // st_size (long)
    mem.store64( addr,      inode.st_blksize & 0xFFFFFFFFL ); addr += 8; // st_blksize
    mem.store64( addr,      inode.st_blocks  ); addr += 8;          // st_blocks (long)
    mem.store64( addr,      inode.st_atime ); addr += 8;            // st_atime (long)
    mem.store64( addr,      inode.st_atime_nsec ); addr += 8;       // st_atime_nsec
    mem.store64( addr,      inode.st_mtime ); addr += 8;            // st_mtime (long)
    mem.store64( addr,      inode.st_mtime_nsec ); addr += 8;       // st_mtime_nsec
    mem.store64( addr,      inode.st_ctime ); addr += 8;            // st_ctime (long)
    mem.store64( addr,      inode.st_ctime_nsec ); addr += 8;       // st_ctime_nsec
    mem.store64( addr,      0              ); addr += 8;            // __unused[0]
    mem.store64( addr,      0              ); addr += 8;            // __unused[1]
    mem.store64( addr,      0              );                       // __unused[2]
  }

  // arch_prctl(code, addr) — ARCH_SET_FS (0x1002) でFS baseを設定
  private static final int ARCH_SET_GS = 0x1001;
  private static final int ARCH_SET_FS = 0x1002;
  private static final int ARCH_GET_FS = 0x1003;
  private static final int ARCH_GET_GS = 0x1004;

  // link(oldpath, newpath): hard link 作成。git の object commit が
  //   tmp_obj_xxx → 最終 hash 名へ rename の代わりに link + unlink で
  //   atomic にする。Java NIO Files.createLink を使用。
  // unlinkat(dirfd, path, flags) — Phase 28-3j: dirfd 対応版。
  //   flags の AT_REMOVEDIR (0x200) は rmdir 経路 (= unlink alias、ディレクトリ
  //   削除は unlink_resolved で File.delete() が走る)。
  private long amd64_unlinkat( int dirfd, long path_addr, int flags ) {
    String path = mem.loadString( path_addr );
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    return unlink_resolved( full );
  }

  // renameat(olddirfd, oldpath, newdirfd, newpath) — Phase 28-3j 新規実装。
  //   2 つの dirfd を独立に解決して rename_resolved。
  private long amd64_renameat( int olddirfd, long old_addr, int newdirfd, long new_addr ) {
    String oldp = mem.loadString( old_addr );
    String newp = mem.loadString( new_addr );
    String old_full = resolve_at_path( olddirfd, oldp );
    String new_full = resolve_at_path( newdirfd, newp );
    if( old_full == null || new_full == null ) return EBADF;
    return rename_resolved( old_full, new_full );
  }

  // linkat(olddirfd, oldpath, newdirfd, newpath, flags) — Phase 28-3j: dirfd 対応。
  //   git の object commit の atomic rename (link + unlink) で重要。
  private long amd64_linkat( int olddirfd, long old_addr, int newdirfd, long new_addr ) {
    String oldp = mem.loadString( old_addr );
    String newp = mem.loadString( new_addr );
    String old_full = resolve_at_path( olddirfd, oldp );
    String new_full = resolve_at_path( newdirfd, newp );
    if( old_full == null || new_full == null ) return EBADF;
    String old_native = sysinfo.get_native_path( old_full );
    String new_native = sysinfo.get_native_path( new_full );
    try {
      java.nio.file.Files.createLink(
        java.nio.file.Paths.get( new_native ),
        java.nio.file.Paths.get( old_native ));
      return 0;
    } catch( java.nio.file.NoSuchFileException m ) {
      return -2;  // ENOENT
    } catch( java.nio.file.FileAlreadyExistsException m ) {
      return -17; // EEXIST
    } catch( Exception m ) {
      return -1;
    }
  }

  // 旧 link(oldpath, newpath) (#86) — AT_FDCWD で linkat を呼ぶだけ
  private long amd64_link( long old_addr, long new_addr ) {
    return amd64_linkat( -100, old_addr, -100, new_addr );
  }

  // symlinkat(target, newdirfd, linkpath) — Phase 28-3j: newdirfd 対応。
  //   target は symlink 内容 (resolve しない)、linkpath だけ newdirfd で解決。
  private long amd64_symlinkat( long target_addr, int newdirfd, long linkpath_addr ) {
    String target = mem.loadString( target_addr );
    String linkpath = mem.loadString( linkpath_addr );
    String full = resolve_at_path( newdirfd, linkpath );
    if( full == null ) return EBADF;
    // issue #68: Cygwin mode では link 自身は追従しない (nofollow) で native
    // path を解決し、マジックファイルとして書く。
    if( CygSymlink.enabled() ) {
      String native_link = sysinfo.get_native_path_nofollow( full );
      return CygSymlink.write( native_link, target ) ? 0 : -1L;
    }
    String native_link = sysinfo.get_native_path( full );
    try {
      java.nio.file.Files.createSymbolicLink(
        java.nio.file.Paths.get( native_link ),
        java.nio.file.Paths.get( target ) );
      return 0;
    } catch( Exception m ) {
      return -1;
    }
  }

  // 旧 symlink(target, linkpath) (#88) — AT_FDCWD で symlinkat を呼ぶだけ
  private long amd64_symlink( long target_addr, long linkpath_addr ) {
    return amd64_symlinkat( target_addr, -100, linkpath_addr );
  }

  // utimensat(dirfd, path, struct timespec[2], flags) — Phase 28-3j: dirfd 対応。
  // path == 0 の場合は dirfd 自身に対する操作 (futimens)。
  // AT_SYMLINK_NOFOLLOW などのフラグは無視。
  private long amd64_utimensat( int dirfd, long path_addr, long times_addr, int flags ) {
    if( path_addr == 0 ) return 0;  // futimens 経路は touch では使われない
    String path = mem.loadString( path_addr );
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    String native_path = sysinfo.get_native_path( full );
    java.io.File f = new java.io.File( native_path );
    if( !f.exists( ) ) {
      try { f.createNewFile( ); } catch( Exception m ) { return -2; }
    }
    long mtime_ms;
    if( times_addr == 0 ) {
      mtime_ms = System.currentTimeMillis( );
    } else {
      // times[1] (offset 16) が mtime: { tv_sec (8), tv_nsec (8) }
      long sec = mem.load64( times_addr + 16 );
      long nsec = mem.load64( times_addr + 24 );
      // UTIME_NOW / UTIME_OMIT は無視して現在時刻を使う
      mtime_ms = sec * 1000L + nsec / 1000000L;
      if( mtime_ms <= 0 ) mtime_ms = System.currentTimeMillis( );
    }
    f.setLastModified( mtime_ms );
    return 0;
  }

  // readlinkat(dirfd, path, buf, bufsiz) — Phase 28-3j: dirfd 対応。
  //   /proc/self/exe / /proc/self/fd/0 は special-case で exec_path を返す。
  private long amd64_readlinkat( int dirfd, long path_addr, long buf_addr, int bufsiz ) {
    String path = mem.loadString( path_addr );
    String target = null;
    if( "/proc/self/exe".equals(path) || "/proc/self/fd/0".equals(path) ) {
      // 絶対パス必須 (glibc の _dl_get_origin が leading '/' を assert する)
      target = (process.exec_path != null) ? process.exec_path : process.name;
    }
    // issue #41 Phase 2: /proc/self/fd/N — N の fd が pty slave なら
    //   /dev/pts/<ptn> を返す。ttyname(3) が isatty 後の path 解決で使う。
    //   それ以外は今のところ未対応 (ENOENT)。
    if( target == null && path != null && path.startsWith("/proc/self/fd/") ) {
      try {
        int n = Integer.parseInt( path.substring("/proc/self/fd/".length()) );
        Fileinfo fi = get_finfo( n );
        if( fi != null && fi.pty_slave ) {
          target = "/dev/pts/" + fi.pty_ptn;
        }
      } catch( NumberFormatException e ) { /* fall through */ }
    }
    if( target == null ) {
      // 通常 path: dirfd 解決 + symlink 自身を読む (最終 component は追従しない)
      String full = resolve_at_path( dirfd, path );
      if( full == null ) return EBADF;
      // issue #68: Cygwin mode はマジックファイルから target を読む
      if( CygSymlink.enabled() ) {
        String native_path = sysinfo.get_native_path_nofollow( full );
        target = CygSymlink.read( native_path );
        if( target == null ) {
          java.io.File f = new java.io.File( native_path );
          return f.exists() ? -22L /*EINVAL: not a symlink*/ : ENOENT;
        }
      } else {
      String native_path = sysinfo.get_native_path( full );
      try {
        java.nio.file.Path link = java.nio.file.Paths.get( native_path );
        java.nio.file.Path tgt = java.nio.file.Files.readSymbolicLink( link );
        target = tgt.toString();
      } catch( java.nio.file.NotLinkException m ) {
        return -22;  // EINVAL (not a symlink)
      } catch( java.nio.file.NoSuchFileException m ) {
        return ENOENT;
      } catch( Exception m ) {
        return ENOENT;
      }
      }
    }
    byte[] b = target.getBytes();
    int len = Math.min(b.length, bufsiz);
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
    mem.bulkStoreToMem( buf_addr, b, 0, len );
    return len;
  }

  // ================================================================
  // issue #78 (node/libuv): epoll / eventfd / timerfd
  // ================================================================
  private static final int EPOLLIN=0x1, EPOLLOUT=0x4, EPOLLERR=0x8, EPOLLHUP=0x10;

  // eventfd2(initval, flags): flags EFD_SEMAPHORE=1 / EFD_NONBLOCK=0x800 /
  //   EFD_CLOEXEC=0x80000。8 byte counter fd を返す。
  private long amd64_eventfd( long initval, long flags ) {
    Fileinfo f = new Fileinfo();
    f.eventfd_flag = true;
    f.eventfd_count = initval;
    f.eventfd_semaphore = (flags & 1) != 0;
    f.nonBlock = (flags & 0x800) != 0;
    int fd = ((FileAccess)this).alloc_anon_fd( f );
    if( (flags & 0x80000) != 0 ) set_cloexec( fd, true );
    return fd;
  }

  private long anon_read( Fileinfo af, long addr ) {
    if( af.eventfd_flag ) {
      synchronized( af ) {
        if( af.eventfd_count == 0 ) return -11L;  // EAGAIN (常に non-block 扱い)
        long v = af.eventfd_semaphore ? 1 : af.eventfd_count;
        af.eventfd_count -= v;
        for( int i = 0; i < 8; i++ ) mem.store8( addr+i, (byte)((v >>> (8*i)) & 0xFF) );
      }
      return 8;
    }
    // timerfd: 満了回数を返す
    long now = System.currentTimeMillis();
    long ticks = 0;
    synchronized( af ) {
      if( af.timerfd_expire_ms != 0 && now >= af.timerfd_expire_ms ) {
        if( af.timerfd_interval_ms > 0 ) {
          ticks = 1 + (now - af.timerfd_expire_ms) / af.timerfd_interval_ms;
          af.timerfd_expire_ms += ticks * af.timerfd_interval_ms;
        } else {
          ticks = 1;
          af.timerfd_expire_ms = 0;  // one-shot 終了
        }
      }
    }
    if( ticks == 0 ) return -11L;  // EAGAIN (未満了)
    for( int i = 0; i < 8; i++ ) mem.store8( addr+i, (byte)((ticks >>> (8*i)) & 0xFF) );
    return 8;
  }

  private long amd64_timerfd_create( long flags ) {
    Fileinfo f = new Fileinfo();
    f.timerfd_flag = true;
    f.nonBlock = (flags & 0x800) != 0;
    int fd = ((FileAccess)this).alloc_anon_fd( f );
    if( (flags & 0x80000) != 0 ) set_cloexec( fd, true );
    return fd;
  }

  // timerfd_settime(fd, flags, new_value*, old_value*): struct itimerspec =
  //   { it_interval{sec,nsec}, it_value{sec,nsec} } = 32 byte。
  //   flags TFD_TIMER_ABSTIME=1。簡略: 常に相対扱い (libuv は相対指定が主)。
  private long amd64_timerfd_settime( long fd, long flags, long new_addr, long old_addr ) {
    Fileinfo f = get_finfo( (int)fd );
    if( f == null || !f.timerfd_flag ) return -9L;  // EBADF
    long isec = mem.load64( new_addr );
    long insec = mem.load64( new_addr + 8 );
    long vsec = mem.load64( new_addr + 16 );
    long vnsec = mem.load64( new_addr + 24 );
    long interval_ms = isec*1000 + insec/1_000_000;
    long value_ms = vsec*1000 + vnsec/1_000_000;
    if( old_addr != 0 ) { for( int i=0; i<32; i++ ) mem.store8( old_addr+i, (byte)0 ); }
    synchronized( f ) {
      f.timerfd_interval_ms = interval_ms;
      if( vsec==0 && vnsec==0 ) f.timerfd_expire_ms = 0;  // disarm
      else {
        boolean abstime = (flags & 1) != 0;
        f.timerfd_expire_ms = abstime ? value_ms : (System.currentTimeMillis() + value_ms);
        if( f.timerfd_expire_ms == 0 ) f.timerfd_expire_ms = 1;  // 0 は disarm 予約値
      }
    }
    return 0;
  }

  private long amd64_timerfd_gettime( long fd, long old_addr ) {
    Fileinfo f = get_finfo( (int)fd );
    if( f == null || !f.timerfd_flag ) return -9L;
    if( old_addr != 0 ) {
      long rem = (f.timerfd_expire_ms==0) ? 0 : Math.max(0, f.timerfd_expire_ms - System.currentTimeMillis());
      mem.store64( old_addr,      f.timerfd_interval_ms/1000 );
      mem.store64( old_addr + 8,  (f.timerfd_interval_ms%1000)*1_000_000 );
      mem.store64( old_addr + 16, rem/1000 );
      mem.store64( old_addr + 24, (rem%1000)*1_000_000 );
    }
    return 0;
  }

  private long amd64_epoll_create() {
    Fileinfo f = new Fileinfo();
    f.epoll_flag = true;
    f.epoll_interest = new java.util.LinkedHashMap<Integer,long[]>();
    int fd = ((FileAccess)this).alloc_anon_fd( f );
    set_cloexec( fd, true );  // epoll_create1(EPOLL_CLOEXEC) が主
    return fd;
  }

  // epoll_ctl(epfd, op, fd, event*): op ADD=1/DEL=2/MOD=3。
  //   struct epoll_event は packed: { uint32 events; uint64 data; } = 12 byte。
  private long amd64_epoll_ctl( long epfd, long op, long fd, long ev_addr ) {
    Fileinfo ep = get_finfo( (int)epfd );
    if( ep == null || !ep.epoll_flag ) return -9L;  // EBADF
    int tgt = (int)fd;
    if( op == 2 ) {  // EPOLL_CTL_DEL
      ep.epoll_interest.remove( tgt );
      return 0;
    }
    if( get_finfo( tgt ) == null ) return -9L;  // EBADF
    long events = mem.load32( ev_addr ) & 0xFFFFFFFFL;
    long data   = mem.load64( ev_addr + 4 );
    ep.epoll_interest.put( tgt, new long[]{ events, data } );
    return 0;  // ADD / MOD
  }

  // 監視 fd の現在 ready な epoll event mask を返す (interest で要求された bit のみ)。
  private int epoll_revents( int fd, int interest ) {
    Fileinfo f = get_finfo( fd );
    if( f == null ) return EPOLLHUP;
    int r = 0;
    if( f.eventfd_flag ) {
      if( f.eventfd_count > 0 ) r |= EPOLLIN;
      r |= EPOLLOUT;
    } else if( f.timerfd_flag ) {
      if( f.timerfd_expire_ms != 0 && System.currentTimeMillis() >= f.timerfd_expire_ms ) r |= EPOLLIN;
    } else if( f.is_pipe( true ) ) {
      if( !sysinfo.kernel.is_pipe_connected( f.pipe_no ) ) r |= EPOLLHUP;
      else if( sysinfo.kernel.pipe_available( f.pipe_no ) > 0 ) r |= EPOLLIN;
      r |= EPOLLOUT;
    } else if( f.isSOCKET() && f.conn != null ) {
      try {
        if( f.socketEof || f.peekBuf != null && f.peekLen > 0 ) r |= EPOLLIN;
        else if( f.conn.getInputStream().available() > 0 ) r |= EPOLLIN;
      } catch ( java.io.IOException e ) { r |= EPOLLHUP; }
      r |= EPOLLOUT;
    } else if( f.isSOCKET() && f.sconn != null && f.subprocess != null ) {
      if( f.subprocess.Accepted() == SubProcess.ACCEPT_DONE ) r |= EPOLLIN;
    } else if( isSTD(fd) || isERR(fd) ) {
      if( (isSTD(fd)) && sysinfo.kernel.console.Available() ) r |= EPOLLIN;
      r |= EPOLLOUT;  // stdout/stderr は常に writable
    } else {
      r |= EPOLLIN | EPOLLOUT;  // 通常 file は常に ready
    }
    // interest で要求された bit + 常時報告される ERR/HUP のみ返す
    return r & (interest | EPOLLERR | EPOLLHUP);
  }

  // epoll_wait(epfd, events*, maxevents, timeout_ms)
  private long amd64_epoll_wait( long epfd, long ev_addr, long maxevents, long timeout ) {
    Fileinfo ep = get_finfo( (int)epfd );
    if( ep == null || !ep.epoll_flag ) return -9L;  // EBADF
    int maxev = (int)maxevents;
    long timeout_ms = timeout;  // -1 = 無限、0 = 即 return
    long deadline = (timeout_ms < 0) ? -1L : System.currentTimeMillis() + timeout_ms;
    while( true ) {
      int n = 0;
      // snapshot して ConcurrentModification を避ける
      java.util.Map<Integer,long[]> snap;
      synchronized( ep ) { snap = new java.util.LinkedHashMap<Integer,long[]>( ep.epoll_interest ); }
      for( java.util.Map.Entry<Integer,long[]> e : snap.entrySet() ) {
        if( n >= maxev ) break;
        int fd = e.getKey();
        int interest = (int)e.getValue()[0];
        long data = e.getValue()[1];
        int rev = epoll_revents( fd, interest );
        if( rev != 0 ) {
          mem.store32( ev_addr + (long)n*12,     rev );
          mem.store64( ev_addr + (long)n*12 + 4, data );
          n++;
        }
      }
      if( n > 0 ) return n;
      if( timeout_ms == 0 ) return 0;
      if( deadline >= 0 && System.currentTimeMillis() >= deadline ) return 0;
      // EINTR / signal 配信のチェックは呼び出し側の SA_RESTART に委ねる。
      try { Thread.sleep( 5 ); } catch ( InterruptedException ie ) { return 0; }
    }
  }

  // prlimit64(pid, resource, new_limit, old_limit) — struct rlimit は
  //   { rlim_cur(8), rlim_max(8) } の 16 byte。Phase 27 step 40: 旧 stub は
  //   常に return 0 で何も書かなかったため、glibc pthread が
  //   getrlimit(STACK)→ rlim_cur=0 を見て stack 不足と判定 → 最小スタック
  //   (~16 KB) で thread を spawn → 64 KB 以上 stack 使う関数で probe loop
  //   が unmapped page に当たって segfault していた。
  //   resource: 0=CPU 1=FSIZE 2=DATA 3=STACK 4=CORE 5=RSS 6=NPROC 7=NOFILE
  //             8=MEMLOCK 9=AS 10=LOCKS 11=SIGPENDING 12=MSGQUEUE 13=NICE
  //             14=RTPRIO 15=RTTIME
  private long amd64_prlimit64( long resource_l, long new_addr, long old_addr ) {
    int resource = (int)resource_l;
    if( old_addr != 0 ) {
      long cur, max;
      switch( resource ) {
        case 3:  // RLIMIT_STACK: 8 MB / 無制限
          cur = 8L * 1024 * 1024;
          max = -1L; // RLIM_INFINITY
          break;
        case 7:  // RLIMIT_NOFILE: 1024 / 4096
          cur = 1024;
          max = 4096;
          break;
        case 9:  // RLIMIT_AS (address space): unlimited
        case 4:  // RLIMIT_CORE
        case 2:  // RLIMIT_DATA
          cur = -1L;
          max = -1L;
          break;
        default:
          cur = -1L;
          max = -1L;
      }
      mem.store64( old_addr,     cur );
      mem.store64( old_addr + 8, max );
    }
    return 0;
  }

  private long amd64_arch_prctl( long code, long addr ) {
    // Phase 27 step 40: 呼び出し thread の Cpu64 を取得 (main or pthread worker)。
    //   旧実装は常に process.cpu (= main) に書いていたため、worker が
    //   ARCH_SET_FS で自分の TLS を設定すると main の fs_base が上書きされ、
    //   その後 main が %fs:offset を load するとガベージで segfault した
    //   (git clone git:// で post-pack の libc 内 mov %fs:0x10,%rax)。
    Thread cur = Thread.currentThread();
    Cpu64 cpu = (cur instanceof Thread64)? ((Thread64)cur).cpu : (Cpu64)process.cpu;
    if( (int)code == ARCH_SET_FS ) {
      cpu.fs_base = addr;
      return 0;
    }
    if( (int)code == ARCH_GET_FS ) {
      mem.store64( addr, cpu.fs_base );
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
