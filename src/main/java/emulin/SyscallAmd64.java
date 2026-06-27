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
  // issue #113: EMULIN_DET_TTY=1 で TTY を決定的「入力なし」に固定し、poll/pselect/
  //   read の console.Available() タイミング依存を排除する (完全決定化の最後の一手)。
  static final boolean DET_TTY = System.getenv("EMULIN_DET_TTY") != null;
  // issue #113: EMULIN_DET_SOCKET=1 で socket read を決定的な固定チャンク (4096)
  //   blocking 読みにし、poll/pselect は conn を常に readable とする。HTTP download
  //   の chunking 非決定性を排除しつつ read↔redisplay interleave は保つ。
  static final boolean DET_SOCKET = System.getenv("EMULIN_DET_SOCKET") != null;
  // issue #206: poll/pselect/epoll の待機を「TTY 入力到着で即復帰する blocking
  //   peek」+「deadline までの正確な wait」にして、busy-sleep(10ms 固定粒度)の
  //   対話レイテンシ(emacs カーソル移動/isearch 等が CPU 低負荷なのに重い)を解消する。
  //   EMULIN_NO_BLOCKING_POLL=1 で旧 busy-sleep 挙動に戻せる (A/B 用)。
  static final boolean BLOCKING_POLL = System.getenv("EMULIN_NO_BLOCKING_POLL") == null;

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
  // issue #221 go/no-go: 命令/syscall 比の計測用 (EMULIN_REPORT_COUNTS=1)。native vs software の
  //   break-even は trap-cost / per-instruction-savings ≈ 一定の「命令/syscall」閾値で決まるので、
  //   実 workload がその閾値の上か下かを知りたい。software は process.evals (Cpu64 が更新する実行
  //   命令数) と本 counter で比を出せる (native は同じ命令を実行するので比は共通)。
  private static final boolean REPORT_COUNTS = System.getenv("EMULIN_REPORT_COUNTS") != null;
  long syscallTotal = 0;

  public long call_amd64( long sysno, long a1, long a2, long a3, long a4, long a5, long a6 ) {
    int n = (int)sysno;
    if( REPORT_COUNTS ) syscallTotal++;
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
    if( n ==  19 ) return amd64_readv(  a1, a2, a3 );       // readv
    if( n ==  20 ) return amd64_writev( a1, a2, a3 );       // writev
    if( n ==  60 ) return amd64_exit_thread( a1 );          // exit (per-thread)
    if( n == 231 ) return amd64_exit(   a1 );                // exit_group (whole process)

    // --- 親クラス sys_* に long のまま委譲 ---
    // (Phase 9 で Syscall.java の sys_* シグネチャを long 化したので、
    //  高位スタックアドレスも切り詰められず正しく渡る。)
    if( n ==   2 ) {  // open(path, flags, mode)。issue #411: procfs を open(2) 経路でも扱う。
      String oraw = mem.loadString( a1 );
      if( oraw != null && oraw.startsWith( "/proc" ) ) {   // /proc 系のみ procfs を試行 (Mount 検証回避)
        long procfd = _openProcfs( oraw, a2 );
        if( procfd != -2L ) return procfd;
      }
      return sys_open( a1, a2, a3, 0, 0 );
    }
    if( n ==   3 ) return sys_close( a1, 0, 0, 0, 0 );
    if( n ==   8 ) return sys_lseek( a1, a2, a3, 0, 0 );
    if( n ==  10 ) return sys_mprotect( a1, a2, a3, 0, 0 );
    if( n ==  11 ) return sys_munmap( a1, a2, 0, 0, 0 );
    if( n ==  17 ) return amd64_pread64( a1, a2, a3, a4 );  // pread64
    if( n ==  18 ) return amd64_pwrite64( a1, a2, a3, a4 ); // pwrite64
    if( n == 295 ) return amd64_preadv(  a1, a2, a3, a4, a5 );  // preadv
    if( n == 296 ) return amd64_pwritev( a1, a2, a3, a4, a5 );  // pwritev
    if( n == 327 ) return amd64_preadv(  a1, a2, a3, a4, a5 );  // preadv2 (RWF_* flags a6 は無視)
    if( n == 328 ) return amd64_pwritev( a1, a2, a3, a4, a5 );  // pwritev2 (flags a6 は無視)
    if( n ==  53 ) return amd64_socketpair( a1, a2, a3, a4 );  // socketpair
    if( n ==  12 ) return sys_brk( a1, 0, 0, 0, 0 );
    if( n ==  16 ) return amd64_ioctl( a1, a2, a3 );             // ioctl
    if( n ==  21 ) return sys_access( a1, a2, 0, 0, 0 );
    if( n ==  22 ) return amd64_pipe( a1, 0 );
    if( n == 293 ) return amd64_pipe( a1, a2 );  // pipe2(fd[2], flags) — O_NONBLOCK のみ反映
    if( n ==  23 ) return amd64_select( a1, a2, a3, a4, a5 );  // 64bit addr + pipe/socket 対応 (旧 sys_select は (int) 切り詰め)
    if( n ==   7 ) return amd64_poll( a1, a2, a3, -1L );  // poll (sigmask 無し)
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
      // issue #225: ppoll の 4th arg = const sigset_t* (待機中だけ適用する mask)。
      //   NULL なら -1 (mask 変更なし)。emacs はこの mask で SIGWINCH/SIGIO を
      //   unblock し、idle ppoll 中に届いた signal で wake する (resize 追従)。
      long sigmask_bits = ( a4 != 0 ) ? mem.load64( a4 ) : -1L;
      if( System.getenv("EMULIN_DEBUG_TTY") != null )
        System.err.println("DBG_PPOLL nfds="+a2+" timeout_ms="+timeout_ms);
      return amd64_poll( a1, a2, timeout_ms, sigmask_bits );
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
    if( n ==  74 ) return sys_sync(    0, 0, 0, 0, 0 );  // fsync
    if( n ==  75 ) return sys_sync(    0, 0, 0, 0, 0 );  // fdatasync (sqlite の durability、issue #221 cov13)
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
    // ★ raw fchmodat(268) は 3 引数 syscall (dirfd, pathname, mode)。第 4 引数 flags は
    //   存在せず、glibc が userspace で AT_SYMLINK_NOFOLLOW / AT_EMPTY_PATH を処理してから
    //   この 3 引数 syscall を発行する。よって r10 (=a4) は未初期化のゴミ値であり、
    //   flags として読んではならない。旧実装は a4 を flags として渡しており、ゴミ値に
    //   たまたま AT_EMPTY_PATH(0x1000) bit が立つと amd64_fchmodat が sys_fchmod(AT_FDCWD,
    //   mode) へ分岐 → get_finfo(-100)==null → EBADF を返し、emacs の package install
    //   (tar 展開後の set-file-modes) が "Doing chmod: Bad file descriptor" で非決定的に
    //   失敗していた。268 は flags=0 固定で渡す。
    if( n == 268 ) return amd64_fchmodat( (int)a1, a2, a3, 0 );  // fchmodat(dirfd,path,mode) — flags 無し
    // fchmodat2 (Linux 6.6+, syscall 452): こちらは 4 引数で flags が実在する
    //   (dirfd,path,mode,flags)。glibc 2.39+ は fchmodat を fchmodat2 経由で発行し、
    //   ENOSYS だと 268 に fallback する。ddskk の make install (chmod) 等で顕在化。
    if( n == 452 ) return amd64_fchmodat( (int)a1, a2, a3, a4 );  // fchmodat2(dirfd,path,mode,flags)
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
    if( n == 105 ) {  // setuid(uid) — #324: euid==0 なら r/e/s 全部、非特権なら euid のみ
      int target = (int)a1;
      int curR = process.uid;
      int curE = (process.euid < 0) ? curR : process.euid;
      int curS = (process.suid < 0) ? curR : process.suid;
      if( curE == 0 ) { process.uid = target; process.euid = target; process.suid = target; return 0; }
      if( target == curR || target == curE || target == curS ) { process.euid = target; return 0; }
      return -1L;  // EPERM (非特権で許可外の uid)
    }
    if( n == 106 ) {  // setgid(gid) — #324: 同上 (gid)
      int target = (int)a1;
      int curR = process.gid;
      int curE = (process.egid < 0) ? curR : process.egid;
      int curS = (process.sgid < 0) ? curR : process.sgid;
      if( curE == 0 ) { process.gid = target; process.egid = target; process.sgid = target; return 0; }
      if( target == curR || target == curE || target == curS ) { process.egid = target; return 0; }
      return -1L;  // EPERM
    }
    if( n == 107 ) return ( process.euid < 0 ) ? process.uid : process.euid;  // geteuid (#324: effective)
    if( n == 108 ) return ( process.egid < 0 ) ? process.gid : process.egid;  // getegid (#324: effective)
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
    if( n == 117 ) {  // setresuid(ruid, euid, suid) — #324: trio を POSIX 通りに更新
      int curR = process.uid;
      int curE = (process.euid < 0) ? curR : process.euid;
      int curS = (process.suid < 0) ? curR : process.suid;
      int nr = ((int)a1 == -1) ? curR : (int)a1;   // -1 = 変更なし
      int ne = ((int)a2 == -1) ? curE : (int)a2;
      int ns = ((int)a3 == -1) ? curS : (int)a3;
      boolean priv = ( curE == 0 );
      // 非特権は各 new 値を現在の {r,e,s} のいずれかにしかできない。これで sshd の permanent
      //   drop (setresuid(u,u,u) で 3 つとも非 0) 後は 0 に戻せず (#41 paranoia)、apt の
      //   seteuid(0)(=setresuid(-1,0,-1)、real/saved が 0 のまま) は許可される。
      if( !priv && ( !(nr==curR||nr==curE||nr==curS)
                  || !(ne==curR||ne==curE||ne==curS)
                  || !(ns==curR||ns==curE||ns==curS) ) ) return -1L;  // EPERM
      process.uid = nr; process.euid = ne; process.suid = ns;
      return 0;
    }
    if( n == 119 ) {  // setresgid(rgid, egid, sgid) — #324: 同上 (gid)
      int curR = process.gid;
      int curE = (process.egid < 0) ? curR : process.egid;
      int curS = (process.sgid < 0) ? curR : process.sgid;
      int nr = ((int)a1 == -1) ? curR : (int)a1;
      int ne = ((int)a2 == -1) ? curE : (int)a2;
      int ns = ((int)a3 == -1) ? curS : (int)a3;
      boolean priv = ( curE == 0 );
      if( !priv && ( !(nr==curR||nr==curE||nr==curS)
                  || !(ne==curR||ne==curE||ne==curS)
                  || !(ns==curR||ns==curE||ns==curS) ) ) return -1L;  // EPERM
      process.gid = nr; process.egid = ne; process.sgid = ns;
      return 0;
    }
    if( n == 118 ) {           // getresuid(ruid*, euid*, suid*) — #324: trio を返す
      int r = process.uid;
      int e = (process.euid < 0) ? r : process.euid;
      int s = (process.suid < 0) ? r : process.suid;
      if( a1 != 0 ) mem.store32( a1, r );
      if( a2 != 0 ) mem.store32( a2, e );
      if( a3 != 0 ) mem.store32( a3, s );
      return 0;
    }
    if( n == 120 ) {           // getresgid(rgid*, egid*, sgid*) — #324: trio を返す
      int r = process.gid;
      int e = (process.egid < 0) ? r : process.egid;
      int s = (process.sgid < 0) ? r : process.sgid;
      if( a1 != 0 ) mem.store32( a1, r );
      if( a2 != 0 ) mem.store32( a2, e );
      if( a3 != 0 ) mem.store32( a3, s );
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
    // issue #191: sync_file_range(fd, off, nbytes, flags) — writeback ヒント。
    //   emulin は host fs へ同期書込なので no-op success。ENOSYS だと dpkg-deb が
    //   "cannot zap possible trailing zeros" で archive 処理を中断する。
    if( n == 277 ) return 0;  // sync_file_range (no-op success)
    // issue #191: msync(addr, len, flags) — mmap の dirty page を backing file へ
    //   flush。emulin の file-backed mmap は書込が永続化される (msync ENOSYS でも
    //   hello-emulin の apt cache 読み書きは成功していた実績) ため no-op success。
    //   ENOSYS だと apt が "Unable to synchronize mmap - msync" で pkgcache.bin
    //   構築を fatal abort し、実 package の依存解決ができなかった。
    if( n == 26 ) return 0;  // msync (no-op success)
    if( n == 165 ) return sys_mount( a1, a2, a3, a4, a5 );
    if( n == 166 ) return sys_umount( a1, 0, 0, 0, 0 );
    if( n == 170 ) return 0;  // sethostname (stub)

    // --- 追加スタブ (glibc 静的リンクバイナリ起動に必要) ---
    if( n ==  13 ) return amd64_rt_sigaction( a1, a2, a3 );
    if( n ==  15 ) return amd64_rt_sigreturn( );
    if( n ==  14 ) return amd64_rt_sigprocmask( a1, a2, a3, a4 );
    if( n ==  28 ) return amd64_madvise( a1, a2, a3 );  // madvise
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
    if( n == 270 ) return amd64_pselect6( a1, a2, a3, a4, a5, a6 );
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
    // copy_file_range(fd_in, off_in, fd_out, off_out, len, flags) — Linux 4.5+ の fd 間
    //   カーネルコピー。emacs の package install 等がファイルコピーに使う。未実装だと
    //   "Unsupported amd64 syscall sysno=[326]" warning が出る (glibc は read/write
    //   fallback するので致命でないが noisy)。実 fd→fd コピーで実装する。
    if( n == 326 ) return amd64_copy_file_range( a1, a2, a3, a4, a5, a6 );
    if( n == 186 ) {
      // gettid: thread-specific TID。pthread spawn 時は worker thread の tid を、
      //   メイン thread は process.pid を返す。Phase 27 step 28 / #221 native は
      //   GuestThread (Thread64 / NativeCpuBackend.Worker 共通) で判定。
      Thread cur = Thread.currentThread();
      if( cur instanceof GuestThread g ) return g.guestTid();
      return sys_getpid( 0, 0, 0, 0, 0 );
    }
    if( n == 234 ) return amd64_tgkill( a1, a2, a3 );  // tgkill(tgid, tid, sig)
    // clone3 (#435): glibc は ENOSYS を返すと clone (#56 = sys_fork) に
    // フォールバックする。Phase 25 では真のスレッド (CLONE_VM 共有メモリ) は
    // 未対応なので、まずは ENOSYS を返してプロセス分離 fork ベースで進める。
    if( n == 435 ) return -38L;  // -ENOSYS
    // issue #349: name_to_handle_at (#303) — file の opaque handle を返す Linux 拡張。
    //   emulin の VFS は file handle 概念を持たない。ENOSYS だと dpkg 1.22 の unpack が
    //   躓く (file 識別経路) ので、「この fs は handle 非対応」の正規 errno EOPNOTSUPP を
    //   返して呼び出し側を stat ベースの fallback に乗せる (systemd-tmpfiles 等も EOPNOTSUPP
    //   を graceful に扱う)。open_by_handle_at (#304) も同様。
    if( n == 303 || n == 304 ) return -95L;  // -EOPNOTSUPP
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
    // statfs(path,buf) / fstatfs(fd,buf): FS の容量等を返す。
    //   issue #191: 旧実装は ENOSYS stub だったが、apt (apt-get install) は
    //   /var/cache/apt/archives の空き容量を statvfs(→statfs) で確認し、
    //   失敗すると "Couldn't determine free space" で fatal abort する。host fs
    //   の実値 (FileStore) を返すよう実装。
    if( n == 137 ) return amd64_statfs(  a1, a2 );
    if( n == 138 ) return amd64_fstatfs( a1, a2 );
    // issue #130: statx を実装 (旧 ENOSYS スタブ)。Rust std の fstat=statx 経路で
    //   ENOSYS fallback が ripgrep/fd の segfault を誘発していた。
    if( n == 332 ) return amd64_statx( (int)a1, a2, (int)a3, (int)a4, a5 );
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
    if( n == 48 ) return amd64_shutdown( a1, a2 );       // shutdown
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
    // sigaltstack(uss, uoss): per-thread 代替 signal stack。Go runtime は全 handler に
    //   SA_ONSTACK を立て M ごとに alt stack を登録する。これを honor しないと handler が
    //   割込み点の goroutine stack で走り Go の adjustSignalStack が foreign stack 扱い→
    //   needm→lockextra 無限 spin (issue #221)。struct sigaltstack { void *ss_sp; int
    //   ss_flags; size_t ss_size; } = ss_sp@0 / ss_flags@8 / ss_size@16 (size 24)。
    if( n == 131 ) return amd64_sigaltstack( a1, a2 );
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
    // issue #416: io_uring。Debian system libuv (apt node) が使用。未実装(ENOSYS)だと
    //   libuv が event loop 初期化で startup 早期終了するため実装する。
    if( n == 425 ) return amd64_io_uring_setup( a1, a2 );            // io_uring_setup(entries, params)
    if( n == 426 ) return amd64_io_uring_enter( a1, a2, a3, a4, a5, a6 ); // io_uring_enter
    if( n == 427 ) return -22L;                              // io_uring_register → EINVAL (非登録経路へ)
    // issue #413: inotify (fs.watch) は ENOSYS のままにする。eventfd 等で代用すると claude(Bun) が
    //   その fd を busy-loop で read して event loop が spin した (576050 reads/15s)。fs.watch の失敗
    //   (ENOSYS) は claude では非致命で、theme/onboarding 画面は描画される (no-watcher 経路)。
    if( n == 253 || n == 294 ) return -38L;  // inotify_init / inotify_init1 → ENOSYS
    if( n == 254 || n == 255 ) return -38L;  // inotify_add_watch / inotify_rm_watch → ENOSYS
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
    long saved = FileSeek( ifd, 0, FileAccess.SEEK_CUR );   // issue #336: off_t 64-bit
    FileSeek( ifd, offset, FileAccess.SEEK_SET );
    byte[] buf = new byte[len];
    int got = FileRead( ifd, buf );
    FileSeek( ifd, saved, FileAccess.SEEK_SET );
    if( got < 0 ) return EBADF;
    // Phase 34-B1 (issue #3-#1): per-byte loop → bulk arraycopy
    mem.bulkStoreToMem( addr, buf, 0, got );
    return got;
  }

  // pwrite64(fd, buf, count, offset) — 指定オフセットに書き込み、ファイル位置は進めない (POSIX、
  //   pread64 と対称)。sqlite が DB page / journal の positioned write に多用するため必須
  //   (未実装だと ENOSYS → sqlite が "disk I/O error" で create table から失敗、issue #221 cov13)。
  private long amd64_pwrite64( long fd, long addr, long count, long offset ) {
    int len = (int)count;
    int ifd = (int)fd;
    if( ifd < 0 ) return -9L;                       // EBADF
    if( isSTD(ifd) || isERR(ifd) ) return -29L;     // pipe/console は非 seekable → ESPIPE
    if( ifd >= flist.size() || get_finfo( ifd ) == null ) return -9L;  // EBADF
    long saved = FileSeek( ifd, 0, FileAccess.SEEK_CUR );   // issue #336: off_t 64-bit
    FileSeek( ifd, offset, FileAccess.SEEK_SET );
    byte[] buf = new byte[len];
    mem.bulkLoadFromMem( addr, buf, 0, len );
    boolean ok = FileWrite( ifd, buf );
    FileSeek( ifd, saved, FileAccess.SEEK_SET );    // 元位置に復帰 (pwrite はオフセット不変)
    if( !ok ) return -5L;  // EIO
    return len;
  }

  // copy_file_range(fd_in, off_in, fd_out, off_out, len, flags) — Linux 4.5+ の fd 間
  //   カーネルコピー。off_* が NULL(0) なら該当 fd の現在位置から読み書きして位置を
  //   進め、非 NULL なら *off から読み書きして fd の位置は変えず *off を進める。flags
  //   は 0 のみ (それ以外 EINVAL)。短いコピー (len 未満) を返してよく、呼出側が 0 に
  //   なるまでループするので 1 回 1 チャンク (上限 1MB) で実装する。
  private long amd64_copy_file_range( long fd_in, long off_in_ptr, long fd_out,
                                      long off_out_ptr, long len, long flags ) {
    if( flags != 0 ) return -22L;  // EINVAL
    int ifd = (int)fd_in;
    int ofd = (int)fd_out;
    // 無効 fd は EBADF (amd64_read/write と同じ invariant、thread 死亡 hang を防ぐ)
    if( ifd < 0 || ofd < 0 ) return -9L;
    if( ifd >= flist.size() || get_finfo( ifd ) == null ) return -9L;
    if( ofd >= flist.size() || get_finfo( ofd ) == null ) return -9L;
    int want = (int) Math.min( len, 1L << 20 );  // 1 回のチャンク上限 (巨大 len の OOM 回避)
    if( want <= 0 ) return 0;

    // --- fd_in から読む ---
    long in_saved = -1;
    long in_off   = 0;
    if( off_in_ptr != 0 ) {                       // 明示 offset 指定: fd の現在位置は変えない
      in_off   = mem.load64( off_in_ptr );
      in_saved = FileSeek( ifd, 0, FileAccess.SEEK_CUR );
      FileSeek( ifd, in_off, FileAccess.SEEK_SET );
    }
    byte[] buf = new byte[ want ];
    int got = FileRead( ifd, buf );
    if( off_in_ptr != 0 && in_saved >= 0 ) FileSeek( ifd, in_saved, FileAccess.SEEK_SET );
    if( got == -2 ) return -11L;  // EAGAIN
    if( got <  0 )  return -9L;   // EBADF / 読込エラー
    if( got == 0 )  return 0;     // EOF → コピー終端
    if( off_in_ptr != 0 ) mem.store64( off_in_ptr, in_off + got );

    // --- fd_out へ書く ---
    byte[] out = ( got == buf.length ) ? buf : java.util.Arrays.copyOf( buf, got );
    long out_saved = -1;
    long out_off   = 0;
    if( off_out_ptr != 0 ) {
      out_off   = mem.load64( off_out_ptr );
      out_saved = FileSeek( ofd, 0, FileAccess.SEEK_CUR );
      FileSeek( ofd, out_off, FileAccess.SEEK_SET );
    }
    boolean ok = FileWrite( ofd, out );
    if( off_out_ptr != 0 && out_saved >= 0 ) FileSeek( ofd, out_saved, FileAccess.SEEK_SET );
    if( !ok ) return EPIPE;       // 書込失敗 (amd64_write と同じ慣習)
    if( off_out_ptr != 0 ) mem.store64( off_out_ptr, out_off + got );

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
      // issue #131 (layer 17): nonBlock read の EAGAIN 判定は Available() (ready())
      //   だけでなく availablePeek() (stream 能動 probe) も見る。forked tmux server
      //   が passed tty fd を nonBlock read するとき ready() が buffer 空で false
      //   固定になり、poll が peek で POLLIN を立てても read が EAGAIN を返して
      //   打鍵を取りこぼす問題を防ぐ。
      if( tty_finfo != null && tty_finfo.nonBlock
          && ( DET_TTY || ( !sysinfo.kernel.console.Available()
                            && !sysinfo.kernel.console.availablePeek() ) ) ) {  // issue #113 / #131
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
      if( len == -1 ) return EBADF; // 汎用エラー / 無効 fd → EBADF (従来動作)
      if( len < 0 ) return len;     // -21 (EISDIR、directory read) 等の具体的 errno はそのまま返す
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
        synchronized( af ) { af.eventfd_count += v; af.eventfd_writes++; }  // issue #427: write 世代を進める
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

  // readv(fd, iov, iovcnt) — writev と対称。issue #131: tmux server が pipe
  //   からの response 読みに使う。各 iov に順次 amd64_read を呼ぶ単純実装。
  //   実 Linux は atomic 1 回 read だが、emulin 内 buffer 経由なら同等。
  //   1 つでも負値 (エラー) なら部分 read 済の合計を返す (POSIX 互換)。
  private long amd64_readv( long fd, long iov_ptr, long iovcnt ) {
    long total = 0;
    for( int i = 0; i < (int)iovcnt; i++ ) {
      long base = mem.load64( iov_ptr + i * 16 );
      long len  = mem.load64( iov_ptr + i * 16 + 8 );
      if( len <= 0 ) continue;
      long r = amd64_read( fd, base, len );
      if( r < 0 ) {
        return ( total > 0 ) ? total : r;  // 既読 byte があれば返す、無ければエラー
      }
      total += r;
      if( r < len ) break;  // short read → 残り iov は読まない (POSIX 仕様)
    }
    return total;
  }

  // preadv / preadv2 (issue #413: claude(Bun) が config 読みに使用)。readv を offset 指定にした版。
  //   ABI: (fd, iov, iovcnt, pos_l, pos_h[, flags])。offset = (pos_h<<32)|pos_l。
  //   pos_l == -1 は「現在位置」= readv 相当 (preadv2 仕様)。flags(RWF_*) は無視。
  private long amd64_preadv( long fd, long iov_ptr, long iovcnt, long pos_l, long pos_h ) {
    long offset = (pos_h << 32) | (pos_l & 0xFFFFFFFFL);
    // issue #422: offset=-1 は「現在位置」= readv 相当 (preadv2 仕様)。Bun は
    //   preadv2(fd,iov,1,-1,RWF_NOWAIT) を stdin に使うが、pos_l が 32bit -1
    //   (0xFFFFFFFF) で渡ると旧 `pos_l == -1L` 判定が漏れて positioned 経路
    //   (amd64_pread64) に落ち、std/pipe で -1/EBADF を返して入力が壊れる。
    //   合成 offset で -1 を検出して確実に stream read へ分岐する。
    if( offset == -1L ) return amd64_readv( fd, iov_ptr, iovcnt );
    long total = 0;
    for( int i = 0; i < (int)iovcnt; i++ ) {
      long base = mem.load64( iov_ptr + i * 16 );
      long len  = mem.load64( iov_ptr + i * 16 + 8 );
      if( len <= 0 ) continue;
      long r = amd64_pread64( fd, base, len, offset );
      if( r < 0 ) return ( total > 0 ) ? total : r;
      total  += r;
      offset += r;
      if( r < len ) break;  // short read
    }
    return total;
  }

  // pwritev / pwritev2 — preadv の write 版。
  private long amd64_pwritev( long fd, long iov_ptr, long iovcnt, long pos_l, long pos_h ) {
    long offset = (pos_h << 32) | (pos_l & 0xFFFFFFFFL);
    if( offset == -1L ) return amd64_writev( fd, iov_ptr, iovcnt );  // issue #422: offset=-1 = 現在位置 (pwritev2 仕様、32bit -1 も検出)
    long total = 0;
    for( int i = 0; i < (int)iovcnt; i++ ) {
      long base = mem.load64( iov_ptr + i * 16 );
      long len  = mem.load64( iov_ptr + i * 16 + 8 );
      if( len <= 0 ) continue;
      long r = amd64_pwrite64( fd, base, len, offset );
      if( r < 0 ) return ( total > 0 ) ? total : r;
      total  += r;
      offset += r;
      if( r < len ) break;
    }
    return total;
  }

  // execve(path, argv, envp) — argv/envp は 8 バイトポインタの NULL 終端配列
  private long amd64_execve( long path_addr, long argv_addr, long envp_addr ) {
    String name = mem.loadString( path_addr );
    // issue #191: exec 対象が存在しない / directory なら、process を差し替える前に
    //   ENOENT / EACCES を返す。さもないと kernel.exec が新 Process の ELF load に
    //   失敗して "Can't execute process" を出力し、guest libc の execvp が PATH の
    //   次の dir を試せずに止まる (dpkg が PATH 先頭の非存在 helper を execvp する
    //   経路で発生、例: PATH=/usr/local/sbin:... で dpkg-split を探す)。
    //   /proc/self/exe は kernel.exec が親 process 名に解決するので除外。
    if( !"/proc/self/exe".equals( name ) ) {
      String _ef = sysinfo.get_full_path( process.get_curdir( ), name );
      Inode _ei = new Inode( _ef, sysinfo );
      if( !_ei.isExists( ) )    return ENOENT;
      if( _ei.isDirectory( ) )  return -13;   // EACCES (directory は実行不可)
      // issue #390: ELF でも shebang(#!) でもないファイルは process を差し替える前に -ENOEXEC を返す。
      //   Linux の execve は ENOEXEC を返し、呼び出し元シェルが /bin/sh で再実行する (POSIX shell の
      //   ENOEXEC fallback)。差し替えてから load が "Not Elf Format" で失敗すると旧 process を kill 済みで
      //   ENOEXEC を返せず、shebang 無しスクリプト (npm の bin が shebang 無しスタブのとき等) が動かない。
      if( !is_exec_format( _ef ) ) return -8L;   // ENOEXEC
    }
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

  // issue #390: 実行ファイルが ELF magic (0x7f 'E' 'L' 'F') か shebang (#!) で始まるか判定する。
  //   どちらでもなければ execve は process を差し替えず -ENOEXEC を返すべき (Linux 同様)。
  //   読めない場合 (権限/競合等) は true を返し従来挙動 (Process load に委ねる)。symlink は追従する。
  private boolean is_exec_format( String vpath ) {
    try {
      String hp = sysinfo.get_native_path( vpath );   // Cygwin / real symlink 追従
      byte[] head = new byte[4];
      int nrd;
      try( java.io.InputStream in = java.nio.file.Files.newInputStream( java.nio.file.Paths.get( hp ) ) ) {
        nrd = in.readNBytes( head, 0, 4 );
      }
      if( nrd >= 4 && (head[0]&0xFF)==0x7f && head[1]=='E' && head[2]=='L' && head[3]=='F' ) return true;  // ELF
      if( nrd >= 2 && head[0]=='#' && head[1]=='!' ) return true;  // shebang
      return false;
    } catch( Throwable t ) {
      return true;   // 読めない → 従来挙動 (Process load に委ねる)
    }
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

  // sigaltstack(uss, uoss): per-thread 代替 signal stack の get/set。
  //   struct sigaltstack { void *ss_sp@0; int ss_flags@8; size_t ss_size@16; } (24 byte)。
  //   ss_flags は SS_DISABLE(2) で「stack 無効」、SS_ONSTACK(1) で「現在 handler 実行中」。
  //   Go runtime は minit で sigaltstack(nil,&old) を呼び、SS_DISABLE なら自前の gsignal
  //   stack を登録する → これを正しく報告/保存しないと SA_ONSTACK delivery が機能しない。
  private long amd64_sigaltstack( long uss, long uoss ) {
    final int SS_DISABLE = Signal.SS_DISABLE;
    // 旧 alt stack を *uoss に書き出す (NULL でなければ)。
    if( uoss != 0 ) {
      long[] cur = process.get_alt_stack();   // 有効登録時のみ非 null
      if( cur != null ) {
        mem.store64( uoss,      cur[0] );      // ss_sp
        mem.store32( uoss + 8,  0 );           // ss_flags = 0 (有効、未実行中)
        mem.store64( uoss + 16, cur[1] );      // ss_size
      } else {
        mem.store64( uoss,      0 );
        mem.store32( uoss + 8,  SS_DISABLE );  // 未登録 → SS_DISABLE
        mem.store64( uoss + 16, 0 );
      }
    }
    // 新 alt stack を *uss から読んで登録 (NULL でなければ)。
    if( uss != 0 ) {
      long ss_sp    = mem.load64( uss );
      int  ss_flags = mem.load32( uss + 8 );
      long ss_size  = mem.load64( uss + 16 );
      if( (ss_flags & SS_DISABLE) != 0 ) {
        process.set_alt_stack( 0, 0, SS_DISABLE );   // 無効化
      } else {
        process.set_alt_stack( ss_sp, ss_size, ss_flags & ~SS_DISABLE );
      }
    }
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
    // Linux _NSIG=65 (signal 1..64、32..64=RT signal)。Go runtime の initsig が全 signal を反復
    //   するので RT signal の sigaction も受け付ける (step 3d-2c-37、旧 >=32 で EINVAL→Go 即死)。
    if( sn < 0 || sn >= Signal.SIGNALS ) return -22L; // -EINVAL
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
    // Linux waitid(2) flags (bitmask):
    //   WNOHANG    = 0x01 — 非ブロック。終了した子がいなければ即 0 を返す。
    //   WUNTRACED  = 0x02 — stop した子を返す (emulin は stop signal 未対応のため
    //                       実質 ignore)。
    //   WCONTINUED = 0x08, __WALL = 0x40000000, __WCLONE = 0x80000000 等もあるが
    //   いずれも emulin の semantics に影響しないので無視で良い。
    //   issue #131 (tmux): tmux server の `waitpid(WAIT_ANY, &status, WNOHANG|WUNTRACED)`
    //   は options=0x3 で渡る。旧実装は `options == WNOHANG` の **完全一致**比較で
    //   options=0x3 を「blocking 経路」と誤認し、SIGCHLD handler 内で 5ms sleep
    //   ループに入って tmux 全体が動かなくなっていた。bitwise AND に変更。
    final int WNOHANG = 1;
    int pid = (int)pid_l;
    int options = (int)options_l;
    boolean nohang = (options & WNOHANG) != 0;
    int ret_pid = 0;
    if( pid == -1 ) {
      while( true ) {
        ret_pid = sysinfo.kernel.is_child_exited( process.pid );
        if( ret_pid > 0 ) break;                      // 子が終了
        if( ret_pid == 0 ) { ret_pid = ECHILD; break; } // 子がいない → ECHILD
        // ret_pid == -1: 子はいるがまだ終了していない
        if( nohang ) { ret_pid = 0; break; }
        Thread.yield( );
        // issue #138: 旧 100ms は emacs の fork+exec+wait4 シーケンス (find-file
        //   on dir で /bin/ls を起動する経路など) で 1 件あたり ~100ms の追加
        //   待ち時間になり perceived hang の主因の一つだった。5ms に短縮して
        //   short-lived child の wait レイテンシを下げる (CPU 増は微小)。
        try { Thread.sleep( 5L ); } catch( InterruptedException m ) { }
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
          if( nohang ) {
            // Phase 30: 即 return。child の race は really_exited 判定
            // (exec_replacing 中は終了扱いしない) で本質 fix 済。yield や
            // sleep を入れると並列回帰テストが timing flake する。
            // issue #131: options & WNOHANG bit を見る (WUNTRACED 等が混ざっても OK)。
            ret_pid = 0;
            break;
          }
          Thread.yield( );
          // issue #138: 旧 50ms を 5ms に短縮 (上の pid==-1 経路と同じ理由)。
          try { Thread.sleep( 5L ); } catch( InterruptedException m ) { }
          if( -1 != process.psig( )) {
            // sleep 中に終了したかチェック。
            // issue #191: 子が本当に終了済み (exit_flag && !exec_replacing) なら
            //   pending signal より子の reap を優先する (Linux 準拠)。旧実装は
            //   exit_code/term_sig をコピーせず ret_pid=pid で break していたため
            //   wait status が常に 0 になり、SIGCHLD handler を持つ親 (dpkg 等) が
            //   短命 child の exit code を取りこぼしていた。dpkg の deb_reassemble は
            //   dpkg-split の exit 1 (= not a part) を 0 (= reassembled) と誤読し、
            //   package を unpack せず silently 失敗する原因だった。下の通常 reap と
            //   同じ後始末 (exit_code/term_sig 退避 + process=null) をここでも行う。
            Process pe = pi.process;
            if( pe.exit_flag && !pe.exec_replacing ) {
              pi.exit_code = pe.exit_code;
              pi.term_sig  = pe.term_sig;
              pi.process   = null;
              ret_pid = pid;
              break;
            }
            ret_pid = EINTR; break;
          }
          continue;
        }
        // 終了済み — wait4 した相手の pid を返す。
        // issue #131 (tmux): is_child_exited の pid==-1 経路は reap (pinfo.process=null)
        //   するが、specific-pid 経路は reap していなかった。結果 tmux server が
        //   wait4(pid=6) で utempter を reap した後の wait4(-1) で同じ pid=6 が
        //   再度返り、本来あるべき pid=7 (utempter del) が見えず session 管理を
        //   破綻させていた。ここで exit_code を退避してから process=null で reap。
        pi.exit_code = pp.exit_code;
        pi.term_sig  = pp.term_sig;   // issue #113: signal-kill (SIGSEGV) を退避
        pi.process = null;
        ret_pid = pid;
        break;
      }
    }
    if( status_addr != 0 ) {
      // wait status の Linux レイアウト:
      //   normal exit : (exit_code & 0xFF) << 8        → WIFEXITED, WEXITSTATUS
      //   signal exit : signal & 0x7F                   → WIFSIGNALED, WTERMSIG
      //   issue #113: segfault 等で signal-kill された子は term_sig (=SIGSEGV=11) を
      //   WIFSIGNALED 形式で返す。core dump bit (0x80) は付けない。
      int wstatus = 0;
      if( ret_pid > 0 ) {
        ProcessInfo pi = sysinfo.kernel.get_pinfo( ret_pid );
        if( pi != null ) {
          if( pi.term_sig != 0 ) wstatus = pi.term_sig & 0x7F;          // WIFSIGNALED
          else                   wstatus = (pi.exit_code & 0xFF) << 8;   // WIFEXITED
        }
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
    // issue #131: /proc/<pid>/fd 合成 dir は flist を走査して entries 合成。
    Fileinfo fi_dir = get_finfo( fd );
    if( fi_dir != null && fi_dir.proc_fd_dir ) {
      return _getdents64_proc_fd( fd, dirp, count );
    }
    // issue #411: /proc・/proc/<pid> 合成 dir は process table を走査して entries 合成。
    if( fi_dir != null && fi_dir.proc_dir ) {
      return _getdents64_procfs( fd, dirp, count );
    }
    String name = get_name( fd );
    if( name == null ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    int start = get_ptr( fd );      // 前回の途中位置 (バイトオフセット)
    // issue #322: 反復開始 (start==0) で dir を 1 度だけ snapshot して固定し、
    //   以降の getdents は同じ snapshot を byte offset cursor で走査する。
    //   dpkg の info-db upgrade は info dir を反復中に file 追加/削除するため、
    //   毎回 re-list すると entry が重複/skip し dpkg が削除済 file を再削除
    //   ("cannot remove ... No such file") → double-free していた。
    String[] list;
    if( start == 0 || fi_dir == null || fi_dir.dirSnapshot == null ) {
      list = file_list( name );
      if( fi_dir != null ) fi_dir.dirSnapshot = list;
    } else {
      list = fi_dir.dirSnapshot;
    }
    long d_off = 0;
    long w_size = 0;
    long address = dirp;
    String dir_with_slash = ('/' != name.charAt( name.length( )-1 )) ? name + "/" : name;
    // issue #207: parent dir の native path 解決はエントリ間で不変なのでループ外で 1 回だけ
    //   行う (旧実装は per-entry に get_native_path_nofollow を呼んでいた)。leaf は
    //   NOFOLLOW なのでここで親 dir を解決して d_name を append すれば等価。
    String native_dir_base;
    try { native_dir_base = sysinfo.get_native_path_nofollow( name ); }
    catch( Exception e ) { native_dir_base = sysinfo.get_native_path( name ); }
    if( native_dir_base.length() == 0 || native_dir_base.charAt( native_dir_base.length()-1 ) != '/' )
      native_dir_base += "/";

    for( int i = 0; i < list.length; i++ ) {
      // issue #322: host 上の名前 (host_name) は NTFS 予約文字が U+F000+c へ
      //   encode 済み (dpkg multiarch の <pkg>:<arch>.list 等)。host FS アクセス
      //   (native_child) には encode 名を使い、guest へ返す d_name は decode して
      //   元の `:` 等に戻す。Linux (CygSymlink 無効) では host_name == d_name。
      String host_name = list[i];
      // issue #349: case 衝突で別名 encode された leaf は map に登録し、guest へは元名で見せる。
      if( CygSymlink.enabled() )
        WinCaseMap.registerFromReaddir(
            native_dir_base.endsWith( "/" ) || native_dir_base.endsWith( java.io.File.separator )
                ? native_dir_base.substring( 0, native_dir_base.length() - 1 ) : native_dir_base,
            host_name );
      String d_name = CygSymlink.enabled()
          ? WinCaseMap.decodeCase( CygSymlink.decodeReservedPath( host_name ) ) : host_name;
      // Phase 27 step 42: ファイル名は UTF-8 byte 長で reclen を計算する
      //   (旧 char 長は U+0080 以上で短くなる)。
      int name_bytes = d_name.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
      // header 19 bytes + name + NUL, 8 バイトアライメント
      int memlen = 19 + name_bytes + 1;
      int reclen = (memlen + 7) & ~7;
      long old_d_off = d_off;
      d_off += reclen;
      if( start <= old_d_off ) {
        // ★ buffer 残量は w_size (このコールの書込量) で判定する。旧実装は d_off (全 entry の
        //   累積 offset) と count を比較していたため、cursor (start) が count を超える大きな dir
        //   では skip 中に d_off>count で break → 0 entry 返却 → reader が dir 終端と誤認し、
        //   count byte 以降の entry が永久に列挙されなかった (366 entry の dir が ~230 で truncate、
        //   bun が es-toolkit の .mjs を見つけられず module 解決失敗)。書けない時は d_off を
        //   old_d_off に戻し境界 entry を次コールで返す。
        if( w_size + reclen > count ) { d_off = old_d_off; break; }
        String full_child = dir_with_slash + d_name;
        // issue #207: 旧実装は per-entry に new Inode (exists + readAttributes +
        //   get_st_mode + length + lastModified で複数 NIO) と Files.isSymbolicLink
        //   (別 NIO) を発行していた。同一 dir を繰り返し getdents する
        //   package-initialize 等で getdents64 が syscall 時間の 66% (~73ms/call) を
        //   占める主因。getdents は d_type と ino だけ要るので、readAttributes(NOFOLLOW)
        //   1 回で symlink/dir/reg 判定 + fileKey(ino) を取得する (per-entry NIO を
        //   ~6 → 1 に削減)。broken symlink も lstat 相当で成功し DT_LNK を返す
        //   (Phase 33-11 の rm 対応を維持)。
        int d_type = 0;       // DT_UNKNOWN
        long ino_val = 0;
        String native_child = native_dir_base + host_name;   // host FS は encode 名で
        try {
          // issue #68: Cygwin マジックファイルも DT_LNK
          if( CygSymlink.enabled() && CygSymlink.isMagic( native_child ) ) {
            d_type = 10; // DT_LNK
          } else {
            java.nio.file.attribute.BasicFileAttributes at = java.nio.file.Files.readAttributes(
                java.nio.file.Paths.get( native_child ),
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS );
            if( at.isSymbolicLink() )     d_type = 10; // DT_LNK (broken でも lstat 成功)
            else if( at.isDirectory() )   d_type = 4;  // DT_DIR
            else if( at.isRegularFile() ) d_type = 8;  // DT_REG
            Object fk = at.fileKey();
            if( fk != null ) ino_val = ( fk.hashCode() & 0xFFFFFFFFL );
          }
        } catch( Exception ignored ) {}
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
    // issue #113: EMULIN_FORCE_SINGLE_THREAD=1 のとき pthread worker の spawn を
    //   拒否し -EAGAIN を返す。旧実装は FORCE_ST でも worker を spawn し
    //   (multiThreadActive=0 で cache coherency だけ無効化していたため) 並走 worker
    //   が stale-cache corruption を起こす逆効果だった。真に single-thread 化して
    //   「worker 無しで #113 が再現するか」を実環境で検証可能にする。glibc
    //   pthread_create は clone の -EAGAIN を EAGAIN として返す (emacs が abort
    //   するなら worker が crash 経路に必須という診断結果になる)。
    if( Memory.FORCE_ST ) {
      System.err.println( "[clone] EMULIN_FORCE_SINGLE_THREAD: refusing CLONE_THREAD (pthread spawn), returning -EAGAIN" );
      return -11L;  // -EAGAIN
    }

    // issue #113 ROOT CAUSE: clone を呼んだ「実行中のスレッド」の Cpu64 を親にする。
    //   旧コードは process.cpu (= main thread 固定) を使っていたため、worker thread が
    //   pthread_create (nested clone) すると、子が main thread の register state を継承し
    //   (rip = main の RCX、rbp/rsi 等も main の値)、しかも main は並走中なので register が
    //   race read される → 子の初期 rip が garbage (near-null 0x640/0x0 等) になり、生成
    //   直後に wild jump → #113 crash (worker stack UNMAPPED 等は release_buffers の
    //   二次被害)。呼び出し thread が worker (GuestThread) なら自分の CPU を、main
    //   process thread なら process.cpu を親にする。#221 native backend では
    //   GuestThread = NativeCpuBackend.Worker、process.cpu = NativeCpuBackend なので
    //   同じ判定で動く (旧 (Cpu64) cast は native で ClassCastException だった)。
    Thread curThread = Thread.currentThread();
    AbstractCpu parent_cpu = ( curThread instanceof GuestThread g ) ? g.guestCpu()
                                                                    : process.cpu;

    // issue #233 (Step 3/3 of #221 refactor): 旧実装は「new Cpu64(...) → copy_state
    //   → set_ip → set_ax → set_sp → fs_base → connect_devices → new Thread64 →
    //   ptid/ctid 書込 → t.start() → return tid」までを本メソッド内で展開して
    //   いたが、その block は Cpu64.spawnVCpu に「移動だけ」(logic 変更ゼロ) で
    //   集約。本メソッドは (1) FORCE_ST gate (2) 呼び出し thread の Cpu64 選択
    //   (3) parent_cpu.spawnVCpu(...) 呼び出しの薄い wrapper になる。これで
    //   将来 NativeCpuBackend (#221 Phase 0+) が同 partition 内に追加 vCPU を作る
    //   実装を同じ API で plug-in できる。
    return parent_cpu.spawnVCpu( flags, child_stack, ptid, ctid, tls );
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
  // issue #206: poll/pselect/epoll の 1 反復ぶんの待機。ttyWait(TTY を read 待ち)
  //   かつ BLOCKING_POLL のときは「入力到着で即復帰する blocking peek」を使い、
  //   busy-sleep(10ms 固定)の対話レイテンシを排除する。それ以外は従来 sleep。
  //   waitChunk は呼び出し側で deadline まで cap 済みであること (timeout 精度)。
  //   中断(InterruptedException)時は false を返す。
  private boolean pollWait( int waitChunk, boolean ttyWait ) {
    if( waitChunk < 1 ) waitChunk = 1;
    if( ttyWait && BLOCKING_POLL ) {
      sysinfo.kernel.console.peekWait( waitChunk );   // TTY 入力で即復帰 / 無ければ waitChunk ms
      return true;
    }
    try { Thread.sleep( waitChunk ); return true; }
    catch( InterruptedException ie ) { return false; }
  }

  private long amd64_pselect6( long nfds, long readfds, long writefds, long exceptfds, long timeout, long sig_arg ) {
    // timeout (struct timespec*): tv_sec (8) + tv_nsec (8)。NULL なら無限。
    long deadline_ms = -1, total_ms_for_log = -1;
    if( timeout != 0 ) {
      long sec  = mem.load64( timeout );
      long nsec = mem.load64( timeout + 8 );
      long total_ms = sec * 1000L + nsec / 1_000_000L;
      deadline_ms = System.currentTimeMillis() + total_ms;
      total_ms_for_log = total_ms;
    }
    // issue #219: 6th arg は { const sigset_t *ss; size_t ss_len } へのポインタ。
    //   待機中だけ適用する signal mask。emacs は SIGIO を普段 block し、pselect の
    //   間だけ unblock する mask を渡して、入力到着 (SIGIO) で wait を中断・
    //   ハンドラ実行して read する。この mask を反映しないと SIGIO が届かず
    //   emacs が入力を読めない (pty 越し emacs の無反応)。
    long sigmask_bits = -1L;  // -1 = mask 指定なし (従来動作)
    if( sig_arg != 0 ) {
      long ss = mem.load64( sig_arg );
      if( ss != 0 ) sigmask_bits = mem.load64( ss );
    }
    return select_core( (int)nfds, readfds, writefds, exceptfds, deadline_ms, total_ms_for_log, sigmask_bits );
  }

  // select(2): pselect6 と同じ fd 判定だが timeout が struct timeval (sec, usec)。
  //   旧実装は amd64 でも i386 版 sys_select に dispatch しており、readfds 等の
  //   アドレスを (int) で 32bit 切り詰めていた (Syscall.sys_select)。PIE の高位
  //   アドレス (0x7fff...) で fd_set を読めず、amd64 で select(2) を使う全
  //   プログラムが壊れていた。64bit address + pipe/socket 判定対応の pselect6 と
  //   select_core を共有する (timeout の単位 = timeval:usec / timespec:nsec の差のみ)。
  //   (issue #113 調査由来。cd7fa60 の方針を、進化後の現 pselect6 本体に適応。)
  private long amd64_select( long nfds, long readfds, long writefds, long exceptfds, long timeout ) {
    long deadline_ms = -1, total_ms_for_log = -1;
    if( timeout != 0 ) {
      long sec  = mem.load64( timeout );
      long usec = mem.load64( timeout + 8 );
      long total_ms = sec * 1000L + usec / 1000L;
      deadline_ms = System.currentTimeMillis() + total_ms;
      total_ms_for_log = total_ms;
    }
    return select_core( (int)nfds, readfds, writefds, exceptfds, deadline_ms, total_ms_for_log, -1L );
  }

  // pselect6 / select の共通 core: fd readiness 判定 + result bitmap write-back。
  //   sigmask_bits: pselect6 の待機中 signal mask (-1 = 指定なし)。
  private long select_core( int n, long readfds, long writefds, long exceptfds,
                            long deadline_ms, long total_ms_for_log, long sigmask_bits ) {
    if( System.getenv("EMULIN_DEBUG_TTY") != null ) {
      long rfds = (readfds != 0) ? mem.load64(readfds) : 0L;
      System.err.println("DBG_PSELECT nfds="+n+" rfds=0x"+Long.toHexString(rfds)+" timeout_ms="+total_ms_for_log);
    }
    int nwords = (n + 63) / 64;
    if( nwords < 1 ) nwords = 1;
    int max_iter = Integer.MAX_VALUE;
    while( max_iter-- > 0 ) {
      // issue #219/#225: pselect6/select は待機中に配信可能な signal が pending
      //   なら fd readiness より先に -EINTR を返す (Linux 仕様: blocking syscall は
      //   signal で中断)。
      //   - 明示 sigmask (pselect6 第6引数) 有り: 待機中だけ適用して判定。emacs は
      //     SIGIO を普段 block し pselect 中だけ unblock する mask を渡す (#219)。
      //   - sigmask 無し (NULL / select): 現在の mask 下で判定。emacs の idle
      //     pselect は NULL sigmask で、ここで SIGWINCH を拾えないと端末リサイズに
      //     追従しない (#225)。
      //   ignore シグナル (SIG_IGN / default-ignore) では中断しない (psig_actionable)。
      //   明示 mask の場合は EINTR 時に mask 適用のまま返し、戻り先の signal 配信で
      //   handler を発火させる (handler の sigreturn 後に emacs 側が mask を復元)。
      if( sigmask_bits != -1L ) {
        long orig_mask = process.get_signal_mask_bits( );
        process.set_signal_mask_bits( sigmask_bits );
        if( process.psig_actionable( ) >= 0 ) return -4L;  // -EINTR (mask 適用のまま)
        process.set_signal_mask_bits( orig_mask );
      } else {
        if( process.psig_actionable( ) >= 0 ) return -4L;  // -EINTR (現 mask 下)
      }
      int ready = 0;
      boolean any_alive = false;
      boolean ttyWaitSet = false;  // issue #206: native TTY を read 待ち中なら blocking peek で待つ
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
            if( DET_SOCKET ) { is_ready = true; any_alive = true; }  // issue #113: 決定的 socket
            else try {
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
              if( !DET_TTY && sysinfo.kernel.console.Available() ) is_ready = true;  // issue #113
              any_alive = true;
              ttyWaitSet = true;  // issue #206: この pselect は TTY 入力待ち → blocking peek 対象
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
      long now206 = System.currentTimeMillis();
      if( deadline_ms >= 0 && now206 >= deadline_ms ) {
        if( readfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( readfds + (long)w*8L, 0L );
        if( writefds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( writefds + (long)w*8L, 0L );
        if( exceptfds != 0 )
          for( int w = 0; w < nwords; w++ ) mem.store64( exceptfds + (long)w*8L, 0L );
        if( System.getenv("EMULIN_DEBUG_TTY") != null )
          System.err.println("DBG_PSELECT_RET timeout deadline="+deadline_ms+" now="+now206);
        return 0;
      }
      // issue #206: 旧 Thread.sleep(10) を「deadline まで cap した待機 + TTY 入力で
      //   即復帰する blocking peek」に置換。短 timeout の 10ms 過剰待ちも解消。
      int waitChunk = 10;
      if( deadline_ms >= 0 ) { long rem = deadline_ms - now206; if( rem < waitChunk ) waitChunk = (int)rem; }
      if( !pollWait( waitChunk, ttyWaitSet ) ) {
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
      // issue #191 (mozc): abstract socket は bind と同じ hash マップ先へ connect。
      {
        String absPath = abstractUnixPath( addr_ptr, addrlen );
        if( absPath != null ) {
          if( !java.nio.file.Files.exists( java.nio.file.Paths.get( absPath ) ) ) return -111L;  // ECONNREFUSED
          try {
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open( java.net.StandardProtocolFamily.UNIX );
            ch.connect( java.net.UnixDomainSocketAddress.of( absPath ) );
            finfo.unixSocket = ch;
            return 0;
          } catch( java.io.IOException m ) { return -111L; }  // ECONNREFUSED
        }
      }
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

  // issue #191 (mozc): abstract AF_UNIX address (sun_path[0]==0、Linux 固有の
  //   abstract namespace) を sandbox 内の決定的 host path にマップする。pathname
  //   socket / unnamed (autobind) なら null を返す。abstract name は binary 可で
  //   NUL 終端でない (長さは addrlen で決まる) ので、FNV-1a 64bit hash で短い hex に
  //   する (Unix socket path は ~108 byte 上限。生 hex 化だと長すぎて bind 不可)。
  //   同一 emulin 内なら bind と connect が同じ name → 同じ path で rendezvous する。
  //   mozc_server の session IPC が abstract socket を使う。
  private String abstractUnixPath( long addr_ptr, long addrlen ) {
    int n = (int)Math.min( addrlen - 2, 108 );
    if( n <= 1 ) return null;                                   // unnamed (autobind)
    if( (mem.load8( addr_ptr + 2 ) & 0xFF) != 0 ) return null;  // pathname socket
    long h = 0xcbf29ce484222325L;
    for( int i = 1; i < n; i++ ) { h ^= (mem.load8( addr_ptr + 2 + i ) & 0xFF); h *= 0x100000001b3L; }
    // issue #383: 旧実装は rootfs/tmp/.emulin-abstract/<hash> を host path にしていたが、
    //   Windows で rootfs が深いパス (OneDrive 等) にあると host path が AF_UNIX の sun_path
    //   上限 (108 byte) を超えて bind が IOException で失敗する (mozc の IPC socket が作れず
    //   exit、実機 WHP/software 共通)。host path を必ず短い system temp 直下にする。sandbox
    //   分離は rootfs の host path も hash に混ぜて担保 (bind/connect は同一 sysinfo なので一致)。
    String root = sysinfo.get_native_path( "/" );
    if( root != null ) for( int i = 0; i < root.length(); i++ ) { h ^= (root.charAt( i ) & 0xFF); h *= 0x100000001b3L; }
    return System.getProperty( "java.io.tmpdir" ) + java.io.File.separator + ".emu-abs-" + Long.toHexString( h );
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
      // issue #191 (mozc): abstract socket (sun_path[0]==0) を sandbox 内の専用 dir に
      //   hash マップして bind。emulin は filesystem socket のみ対応なので名前を写す。
      {
        String absPath = abstractUnixPath( addr_ptr, addrlen );
        if( absPath != null ) {
          try {
            java.nio.file.Path np = java.nio.file.Paths.get( absPath );
            java.nio.file.Path parent = np.getParent();
            if( parent != null ) { try { java.nio.file.Files.createDirectories( parent ); } catch( java.io.IOException ig ){} }
            try { java.nio.file.Files.deleteIfExists( np ); } catch( java.io.IOException ig ){}
            java.nio.channels.ServerSocketChannel ss = java.nio.channels.ServerSocketChannel.open( java.net.StandardProtocolFamily.UNIX );
            ss.bind( java.net.UnixDomainSocketAddress.of( absPath ) );
            finfo.unixServer = ss;
            finfo.listenPollinReady = true;
            return 0;
          } catch( java.io.IOException m ) { return -98L; }  // EADDRINUSE
        }
      }
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
        // issue #131 (layer 9): bind 直後は POLLIN を立てて tmux libevent に
        //   「listen socket は ready」と通知し、accept handler を呼ばせる経路を
        //   確実にする (libevent は最初の poll で accept handler を attach)。
        finfo.listenPollinReady = true;
        // issue #131 (layer 11): emulin process の umask を反映して socket file
        //   の mode を 0666 & ~umask にする。Java の ServerSocketChannel.bind は
        //   host JVM の umask を使うため、emulin の中で tmux が `umask(0177)`
        //   (= 0600 を要求) を呼んでいても socket は 0644 で作られていた。
        //   tmux client は接続前に socket の mode を check し、group/other bit
        //   が立っていると "access not allowed" で拒否する (server.c L396)。
        //   POSIX file permission を直接 set する。Windows native FS は
        //   PosixFilePermission 非対応なので silently skip (tmux は Linux のみ)。
        try {
          int mode = 0666 & ~process.umask;
          java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
              java.util.EnumSet.noneOf( java.nio.file.attribute.PosixFilePermission.class );
          if( (mode & 0400) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_READ );
          if( (mode & 0200) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_WRITE );
          if( (mode & 0100) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE );
          if( (mode & 0040) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_READ );
          if( (mode & 0020) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_WRITE );
          if( (mode & 0010) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE );
          if( (mode & 0004) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_READ );
          if( (mode & 0002) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE );
          if( (mode & 0001) != 0 ) perms.add( java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE );
          java.nio.file.Files.setPosixFilePermissions( np, perms );
        } catch ( java.io.IOException | UnsupportedOperationException ignored ) {
          // Windows native FS は PosixFilePermission 非対応 → silently skip
        }
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

  // issue #202 (mozc): shutdown(fd, how) — 旧実装は no-op return 0 (「close で
  //   十分」) だったが、これは half-close 前提の protocol を壊す。mozc の session
  //   IPC は connection-per-request で、client が request 送信後に shutdown(SHUT_WR)
  //   で「書き込み方向だけ」を閉じ (読み出しは応答受信に使うので開いたまま)、server
  //   側の recv が EOF(0) を受けて request 終端を認識する設計
  //   (ipc/unix_ipc.cc: RecvMessage は recv が 0 を返すまでループ)。no-op だと
  //   server の recv が永遠に EOF を受けられず block → request を処理せず無応答。
  //   AF_UNIX (SocketChannel) と AF_INET (java.net.Socket) の両方で、対応する
  //   方向を実際に shutdown して peer に EOF を伝える。
  //   how: SHUT_RD=0, SHUT_WR=1, SHUT_RDWR=2。
  private long amd64_shutdown( long fd, long how ) {
    Fileinfo finfo = get_finfo( (int)fd );
    if( finfo == null || !finfo.isSOCKET() ) return -9L;  // EBADF
    int h = (int)how;
    boolean doRd = (h == 0 || h == 2);  // SHUT_RD / SHUT_RDWR
    boolean doWr = (h == 1 || h == 2);  // SHUT_WR / SHUT_RDWR
    try {
      if( finfo.unixSocket != null ) {
        if( doWr ) { try { finfo.unixSocket.shutdownOutput(); } catch( java.io.IOException ig ){} }
        if( doRd ) { try { finfo.unixSocket.shutdownInput();  } catch( java.io.IOException ig ){} finfo.socketEof = true; }
      } else if( finfo.conn != null ) {
        if( doWr && !finfo.conn.isOutputShutdown() ) { try { finfo.conn.shutdownOutput(); } catch( java.io.IOException ig ){} }
        if( doRd && !finfo.conn.isInputShutdown()  ) { try { finfo.conn.shutdownInput();  } catch( java.io.IOException ig ){} finfo.socketEof = true; }
      }
    } catch( Exception ig ) { /* NotYetConnected 等は success 扱い (Linux も未接続 shutdown は ENOTCONN だが実害なし) */ }
    return 0;
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
    // issue #131 (tmux): UDP-connected sendto 経路は本物の socket かつ
    //   stream でない場合に限る。socketpair (emulin では <pipe> として実装 →
    //   socket_flag=false, stream_flag=false) はここで誤判定されないよう、
    //   isSOCKET() を必須にして FileWrite (pipe_write) 経路に流す。
    if( finfo != null && finfo.isSOCKET() && !finfo.isSTREAM() ) {
      if( System.getenv("EMULIN_TRACE_NET") != null )
        System.err.println("SENDMSG-UDP-CONN fd="+fd+" len="+buf.length+" -> ip="+finfo.get_ip_address()+" port="+finfo.get_port());
      // connected UDP: finfo.ip / finfo.port (connect で設定済) に送る
      boolean ok = sendto( (int)fd, buf, (int)flags, finfo.get_ip_address(), finfo.get_port() );
      return ok ? buf.length : -32L;
    }
    // issue #131 (tmux layer 14): AF_UNIX stream の SCM_RIGHTS で渡される
    //   console/tty fd を peer recvmsg 用 queue に enqueue。data 書き込みの「前」
    //   に呼ぶ (fd が data 到着時に確実に queue に在るように)。
    scmEnqueueFds( finfo, msghdr_addr );
    if( !FileWrite( (int)fd, buf ) ) return -32L;
    return buf.length;
  }

  private long amd64_recvmsg( long fd, long msghdr_addr, long flags ) {
    Fileinfo finfo = get_finfo( (int)fd );
    // issue #131: recvmsg を非 socket fd (普通の pipe) に呼ばれた場合は
    //   実 Linux と同じく ENOTSOCK (-88) を返す。tmux 等は libevent の signal
    //   self-pipe を openat した後、event loop で各 fd に recvmsg を試行する
    //   コードがあり、旧実装は finfo.Read (pipe 非対応経路) に落として f==null
    //   から -21 → -104 ECONNRESET を返却。libevent は connection-reset 誤判定
    //   で server を畳んでいた。socketpair (AF_UNIX SOCK_STREAM 経由) も
    //   pipe_no を持つが pipe_write_no も >=0 で双方向 (区別できる)。実 pipe
    //   は pipe_write_no = -1 のままなので、それを ENOTSOCK 対象にする。
    if( finfo != null && finfo.is_pipe( true ) && !finfo.isSOCKET()
        && finfo.pipe_write_no < 0 ) {
      return -88L;  // ENOTSOCK (real pipe; socketpair は除外)
    }
    long name_addr   = mem.load64( msghdr_addr + 0 );
    int  namelen_max = (int)mem.load32( msghdr_addr + 8 );
    long iov_addr    = mem.load64( msghdr_addr + 16 );
    long iov_count   = mem.load64( msghdr_addr + 24 );
    long total_max   = 0;
    for( long i = 0; i < iov_count; i++ ) total_max += mem.load64( iov_addr + i*16 + 8 );
    byte[] buf = new byte[(int)total_max];
    int r;
    int[] addr_info = new int[2];
    // issue #131: recvmsg は UDP (dgram) と stream (AF_UNIX / socketpair / TCP /
    //   pty 等) の双方で呼ばれる。dgram の有無で正確に分岐する。旧実装は
    //   !isSTREAM() で判定していたが、socket_flag が立っていない AF_UNIX 接続
    //   socket 等が UDP path に落ちて Fileinfo.recvfrom が dgram=null で NPE
    //   していた (tmux client↔server の事例で顕在化)。
    if( finfo != null && finfo.family_v6 && finfo.dgram != null ) {
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
    } else if( finfo != null && finfo.dgram != null ) {
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
      // dgram 無し → stream / AF_UNIX / socketpair / pty 等。
      // issue #131 (tmux): socketpair (pipe_no かつ pipe_write_no>=0) は
      //   finfo.Read だと f==null で -21 → -104 になり tmux client↔server IPC
      //   が壊れる。pipe_no 経由で kernel.pipe_read を直接呼ぶ。
      if( finfo != null && finfo.is_pipe( true ) ) {
        int rr = sysinfo.kernel.pipe_read( finfo.pipe_no, buf, finfo.nonBlock );
        if( rr == -2 ) return -11L;
        if( rr < 0 ) return -104L;
        r = rr;
      } else {
        r = (finfo != null) ? finfo.Read( buf ) : 0;
        if( r == -2 ) return -11L;
        if( r < 0 ) return -104L;
      }
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
    // issue #131 (tmux layer 14): peer から SCM_RIGHTS で渡された console/tty fd を
    //   この process の flist に install し msg_control に cmsg を合成する。
    //   AF_UNIX stream / socketpair 経路でのみ作動 (UDP / 通常 pipe は no-op)。
    scmDeliverFds( finfo, msghdr_addr );
    return r;
  }

  // ============================================================
  // issue #131 (tmux layer 14): AF_UNIX SOCK_STREAM の SCM_RIGHTS (fd passing)
  //   in-JVM エミュレーション。Java NIO の SocketChannel は ancillary data を
  //   露出しないので、同一 JVM 内の sendmsg→recvmsg を Kernel.pendingScmFds
  //   (AF_UNIX bind path / socketpair の pipe 番号単位 FIFO) で橋渡しする。
  //   foreground `tmux new-session` は
  //   client が server を fork するため両端が同一 JVM に居り、client が渡す
  //   stdin/stdout (共有 console) tty fd を server が受け取って isatty() を
  //   通し CLIENT_TERMINAL を立てられる ("open terminal failed: not a terminal"
  //   を解消)。渡すのは console/tty fd に限定 (= 安全。受信側に install する
  //   Fileinfo は std_flag/stderr_flag だけ持つ新規 object で、close しても
  //   JVM-wide singleton の kernel.console には影響しない)。
  // ============================================================
  // AF_UNIX connected socket の native path key (非空のみ。無ければ null)。
  private String scmPathKey( java.net.SocketAddress sa ) {
    if( !(sa instanceof java.net.UnixDomainSocketAddress) ) return null;
    String p = ((java.net.UnixDomainSocketAddress)sa).getPath().toString();
    if( p == null || p.isEmpty() ) return null;
    return p;
  }

  // 送信側 (sendmsg) の SCM key。peer が「どこから読むか」で識別する。
  //   - 名前付き AF_UNIX (unixSocket): connect 先 = peer の bind path。
  //   - socketpair (emulin では pipe pair で実装、unixSocket=null): 自分の
  //     書き込み先 pipe (pipe_write_no) = peer の読み元 pipe。foreground
  //     `tmux new-session` は server_start() が socketpair を作って fork する
  //     ため client↔server IPC は実際にはこの経路を通る。
  private String scmSendKey( Fileinfo finfo ) {
    if( finfo.unixSocket != null ) {
      try { String p = scmPathKey( finfo.unixSocket.getRemoteAddress() );
            return ( p == null ) ? null : "U:" + p; }
      catch ( java.io.IOException e ) { return null; }
    }
    if( finfo.pipe_write_no >= 0 ) return "P:" + finfo.pipe_write_no;  // socketpair
    return null;
  }

  // 受信側 (recvmsg) の SCM key。自分が「どこから読むか」で識別する。
  //   - 名前付き AF_UNIX: 自分の bind path (accepted 側 getLocalAddress)。
  //   - socketpair: 自分の読み元 pipe (pipe_no)。送信側 peer の pipe_write_no と一致。
  private String scmRecvKey( Fileinfo finfo ) {
    if( finfo.unixSocket != null ) {
      try { String p = scmPathKey( finfo.unixSocket.getLocalAddress() );
            return ( p == null ) ? null : "U:" + p; }
      catch ( java.io.IOException e ) { return null; }
    }
    if( finfo.pipe_write_no >= 0 ) return "P:" + finfo.pipe_no;        // socketpair
    return null;
  }

  // sendmsg 側: msg_control を走査し SCM_RIGHTS の console/tty fd を peer の
  //   recvmsg 用 queue に enqueue。data 書き込みの「前」に呼ぶ (fd が data 到着
  //   時に確実に queue に在るように)。console/tty 以外の fd は渡さない (= 従来
  //   どおり drop)。
  private void scmEnqueueFds( Fileinfo finfo, long msghdr_addr ) {
    if( finfo == null ) return;
    long ctrl    = mem.load64( msghdr_addr + 32 );
    long ctrllen = mem.load64( msghdr_addr + 40 );
    if( ctrl == 0 || ctrllen < 16 ) return;
    String key = scmSendKey( finfo );
    if( key == null ) return;
    long off = 0;
    // issue #322: 1 sendmsg の fd 群を 1 グループとして enqueue (recvmsg は 1 グループ=1 sendmsg
    //   分を 1 回で返す)。OpenSSH mm_send_fd は 1 fd/sendmsg・mm_receive_fd は 1 fd/recvmsg なので、
    //   fd を平坦化して drain すると最初の recvmsg が複数 fd を一括取得し後続 recvmsg が空 ("no
    //   message header") になる。pty master/slave が別 sendmsg で来るのでこの framing 保持が必須。
    java.util.ArrayList<int[]> group = new java.util.ArrayList<>();
    while( off + 16 <= ctrllen ) {
      long clen  = mem.load64( ctrl + off );
      int  level = (int)mem.load32( ctrl + off + 8 );
      int  type  = (int)mem.load32( ctrl + off + 12 );
      if( clen < 16 ) break;
      if( level == 1 /*SOL_SOCKET*/ && type == 1 /*SCM_RIGHTS*/ ) {
        int nfds = (int)((clen - 16) / 4);
        for( int j = 0; j < nfds; j++ ) {
          if( off + 16 + (long)j*4 + 4 > ctrllen ) break;
          int gfd = (int)mem.load32( ctrl + off + 16 + (long)j*4 );
          Fileinfo src = get_finfo( gfd );
          if( src == null ) continue;
          // console (STD/ERR) に加え pty (master/slave) も受け渡す。それ以外は drop。
          int[] desc;
          if( src.pty_master )     desc = new int[]{ 2, src.pty_ptn };
          else if( src.pty_slave ) desc = new int[]{ 3, src.pty_ptn };
          else if( src.isERR() )   desc = new int[]{ 1, -1 };
          else if( src.isSTD() )   desc = new int[]{ 0, -1 };
          else continue;
          group.add( desc );
          if( System.getenv("EMULIN_TRACE_NET") != null )
            System.err.println("SCM-SEND gfd="+gfd+" kind="+desc[0]+" ptn="+desc[1]+" key="+key);
        }
      }
      long adv = (clen + 7) & ~7L;   // CMSG_ALIGN
      if( adv <= 0 ) break;
      off += adv;
    }
    if( !group.isEmpty() )
      sysinfo.kernel.pendingScmFds
        .computeIfAbsent( key, k -> new java.util.concurrent.ConcurrentLinkedQueue<int[][]>() )
        .add( group.toArray( new int[0][] ) );
  }

  // recvmsg 側: peer から渡された fd を drain して console fd を install し、
  //   msg_control に SCM_RIGHTS cmsg を合成。fd が無ければ msg_controllen=0 を
  //   明示する (従来は未設定で guest が入力容量を ancillary 長と誤読し得た)。
  private void scmDeliverFds( Fileinfo finfo, long msghdr_addr ) {
    if( finfo == null ) return;
    String key = scmRecvKey( finfo );
    if( key == null ) return;
    long ctrl    = mem.load64( msghdr_addr + 32 );
    long ctrlcap = mem.load64( msghdr_addr + 40 );  // guest が渡した buffer 容量
    java.util.concurrent.ConcurrentLinkedQueue<int[][]> q =
      sysinfo.kernel.pendingScmFds.get( key );
    java.util.ArrayList<Integer> newfds = new java.util.ArrayList<>();
    if( q != null && ctrl != 0 && ctrlcap >= 20 ) {
      int maxfds = (int)((ctrlcap - 16) / 4);
      // issue #322: 1 recvmsg = 1 グループ (= 1 sendmsg 分) を返す。グループ内 fd だけ install
      //   (複数 sendmsg を 1 recvmsg に詰めない = OpenSSH の 1:1 framing を守る)。
      int[][] grp = q.poll();
      if( grp != null ) for( int[] desc : grp ) {
        if( newfds.size() >= maxfds ) break;
        Fileinfo nf = new Fileinfo();
        if( desc[0] == 2 || desc[0] == 3 ) {
          // pty fd を同じ ptn で再構築 (master=2 / slave=3)。受信プロセスは Kernel-wide な
          //   PtyManager から pipe pair を引いて同一 pty を指す fd を得る (open path と同じ向き)。
          PtyManager.PtyPair pair = sysinfo.kernel.pty.get( desc[1] );
          if( pair == null ) continue;   // pty が既に消滅 (この desc は捨てる)
          nf.open( desc[0]==2 ? "<pty-master>" : "<pty-slave>", "rw", Syscall.O_RDWR );
          if( desc[0] == 2 ) { nf.set_pipe_pair( pair.pipe_b, pair.pipe_a ); nf.pty_master = true; }
          else               { nf.set_pipe_pair( pair.pipe_a, pair.pipe_b ); nf.pty_slave  = true; }
          nf.pty_ptn = desc[1];
        } else {
          nf.open( desc[0]==1 ? "<err>" : "<std>", "rw", Syscall.O_RDWR );
        }
        int newfd = alloc_anon_fd( nf );
        newfds.add( Integer.valueOf( newfd ) );
        if( System.getenv("EMULIN_TRACE_NET") != null )
          System.err.println("SCM-RECV install fd="+newfd+" kind="+desc[0]+" ptn="+desc[1]+" key="+key);
      }
    }
    if( ctrl != 0 ) {
      int nf = newfds.size();
      if( nf > 0 ) {
        long clen = 16L + (long)nf * 4L;   // CMSG_LEN(nf*4)
        mem.store64( ctrl + 0, clen );
        mem.store32( ctrl + 8, 1 );        // cmsg_level = SOL_SOCKET
        mem.store32( ctrl + 12, 1 );       // cmsg_type  = SCM_RIGHTS
        for( int j = 0; j < nf; j++ )
          mem.store32( ctrl + 16 + (long)j*4, newfds.get(j).intValue() );
        mem.store64( msghdr_addr + 40, clen );   // msg_controllen = 実バイト数
      } else {
        mem.store64( msghdr_addr + 40, 0 );      // ancillary data 無し
      }
    }
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
    // issue #131 (tmux): SO_PEERCRED (=17) — AF_UNIX socket の peer credentials
    //   (struct ucred { pid_t pid; uid_t uid; gid_t gid; }、4 byte 3 = 12 byte) を返す。
    //   tmux server は `server_acl_join` で `getpeereid()` 経由で peer uid を取得し
    //   ACL list (= server 起動時の getuid() のみ) と比較する。一致しないと
    //   "access not allowed" で client を弾く。
    //   emulin は sandbox 内で全 process が同じ uid (= process.uid、通常 0=root)
    //   として動くので、peer uid は自分と同じ値を返せば実用上問題ない。
    //   (本来は client process の uid だが、別 JVM の場合は emulin の中では
    //    取得不可能。peer も同じ sandbox に居るなら uid 一致でよい近似。)
    //   pid は 0 (= swapper、unprivileged) で済ます — tmux は pid を使わない。
    if( level == 1 /* SOL_SOCKET */ && optname == 17 /* SO_PEERCRED */ ) {
      if( optval != 0 ) {
        mem.store32( optval,         0 );                  // pid
        mem.store32( optval + 4,  (int)process.uid );      // uid (= 自プロセスの uid)
        mem.store32( optval + 8,  (int)process.gid );      // gid
      }
      if( optlen_ptr != 0 ) mem.store32( optlen_ptr, 12 );
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
    if( sec < 0 || nsec < 0 || nsec >= 1_000_000_000L ) return -22; // EINVAL
    long ms       = sec * 1000L + nsec / 1_000_000L;
    long subNanos = nsec % 1_000_000L;
    if( ms > 0 ) {
      try { Thread.sleep( ms ); }
      catch( InterruptedException e ) { /* 短いスリープなので EINTR は無視 */ }
    } else if( subNanos > 0 || nsec > 0 ) {
      // ★ issue #221 step 3d-2c-37: sub-millisecond の sleep を「即 return」で潰すと、Go runtime の
      //   usleep ベースの spin-backoff (sysmon / osyield / lock 取得待ち) が実遅延ゼロの busy-loop に
      //   なり、go build 等で main goroutine が 100% CPU spin して進まなくなる (clock_nanosleep が
      //   #113 で同じ理由で「実際に sleep」修正済なのと同根)。実 nanosecond 待ちを LockSupport で
      //   行う (Thread.sleep は ms 精度なので sub-ms を表現できない)。
      java.util.concurrent.locks.LockSupport.parkNanos( nsec );
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
    if( REPORT_COUNTS ) {
      // process.evals = Cpu64 が更新する実行命令数 (software、1024 命令ごと更新で十分な精度)。
      System.err.println( "REPORT_COUNTS insn=" + process.evals + " syscall=" + syscallTotal
          + " insn_per_syscall=" + ( syscallTotal > 0 ? (process.evals / syscallTotal) : 0 ) );
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
    // worker thread (GuestThread = Thread64 / NativeCpuBackend.Worker) の exit(#60) は
    //   その thread だけを畳む (ThreadExitException で run() の finally へ)。main thread は
    //   下で worker 全完了を待ってから process 全体を exit する。
    if( cur instanceof GuestThread ) {
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
  private long amd64_poll( long fds_addr, long nfds, long timeout_ms, long sigmask_bits ) {
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
      // issue #225: poll/ppoll も配信可能な signal が pending なら -EINTR (Linux
      //   仕様: blocking syscall は signal で中断)。ppoll の sigmask は待機中だけ
      //   適用、plain poll / NULL sigmask は現在の mask 下で判定。これが無いと
      //   emacs が idle ppoll/poll 中に届いた SIGWINCH (端末リサイズ) を input
      //   到着まで処理せず画面が追従しない。ignore シグナルでは中断しない。
      if( sigmask_bits != -1L ) {
        long orig_mask = process.get_signal_mask_bits( );
        process.set_signal_mask_bits( sigmask_bits );
        if( process.psig_actionable( ) >= 0 ) return -4L;  // -EINTR (mask 適用のまま)
        process.set_signal_mask_bits( orig_mask );
      } else {
        if( process.psig_actionable( ) >= 0 ) return -4L;  // -EINTR (現 mask 下)
      }
      int ready = 0;
      boolean any_alive = false;
      boolean ttyWaitSet = false;  // issue #206: native TTY を POLLIN 待ち中なら blocking peek
      for( int i=0; i<n; i++ ) {
        long ent = fds_addr + (long)i * 8L;
        int fd     = (int)mem.load32( ent + 0 );
        int events = mem.load16( ent + 4 ) & 0xFFFF;
        int revents = 0;
        Fileinfo finfo = (fd >= 0) ? get_finfo( fd ) : null;
        // POLLOUT (0x4) / POLLWRNORM (0x100): 書き込み可能。socket / pipe の
        //   書き込み端は基本いつでも writable とみなして OK。
        if( (events & 0x104) != 0 ) revents |= (events & 0x104);
        // issue #416: eventfd/timerfd は count/expire を見て POLLIN を立てる。generic else で
        //   無条件 POLLIN にすると node(libuv) の async eventfd(count=0)を poll が常時 readable
        //   報告 → read が EAGAIN(-11) → event loop が無限 spin する (claude onboarding で再現)。
        //   epoll_revents は count を見ており、poll/select もそれに揃える。POLLOUT は上で設定済。
        if( finfo != null && (finfo.eventfd_flag || finfo.timerfd_flag) ) {
          any_alive = true;
          boolean rd = finfo.eventfd_flag ? (finfo.eventfd_count > 0)
                     : (finfo.timerfd_expire_ms != 0 && System.currentTimeMillis() >= finfo.timerfd_expire_ms);
          if( (events & 0x43) != 0 && rd ) revents |= (events & 0x43);
          mem.store16( ent + 6, (short)revents );
          if( revents != 0 ) ready++;
          continue;
        }
        // POLLIN (0x1) / POLLRDNORM (0x40) / POLLPRI (0x2): 読める時だけ立てる。
        //   socket: peekBuf に残データ or 接続中 (= EOF 未) で データ available
        //   pipe:   PipeManager に問い合わせる手段が無いので「読める想定」
        //           を避け、データが無いなら立てない (= curl 等の wakeup pipe で
        //           「常に ready 詐欺」を起こさないため)
        //   通常 fd: 不明なので保守的に立てる (read で実際に block / EAGAIN が
        //           判定する)
        if( (events & 0x43) != 0 && finfo != null ) {
          // issue #131 (tmux layer 5): AF_UNIX listen socket の pending accept を
          //   POLLIN として通知する。amd64_pselect6 では Phase 41 で既に対応済だが
          //   amd64_poll は欠落していて、tmux server が listen(6) 後に poll() で
          //   client の接続を検出できず exit していた。
          if( finfo.isSOCKET() && finfo.unixServer != null ) {
            any_alive = true;
            if( finfo.unixQueued != null ) {
              revents |= (events & 0x43);
            } else {
              try {
                finfo.unixServer.configureBlocking( false );
                java.nio.channels.SocketChannel ch = finfo.unixServer.accept();
                if( ch != null ) {
                  finfo.unixQueued = ch;
                  finfo.listenPollinReady = false;  // 実 connection 到着で flag 解除
                  revents |= (events & 0x43);
                } else if( finfo.listenPollinReady ) {
                  // issue #131 (layer 9): bind 直後は accept null でも POLLIN を立てる。
                  //   tmux libevent はこれで accept handler を attach し、その handler
                  //   が再度 poll 経由で listen fd を監視する経路に乗る。
                  // issue #131 (layer 15b): この POLLIN は **one-shot** にする。旧実装は
                  //   実 connection 到着まで flag を解除しなかったため、誰も named socket
                  //   に繋がない前景 `tmux new-session` (client↔server は socketpair 経由)
                  //   では poll の度に「accept null + POLLIN 詐欺」を返し続け、libevent が
                  //   accept → EAGAIN を 30s で 13 万回 spin して CPU を食い潰し attach
                  //   描画が進まなかった。一度立てたら解除し、以降の実接続は上の
                  //   accept != null 分岐 (poll 内 accept) が検出する。
                  revents |= (events & 0x43);
                  finfo.listenPollinReady = false;
                }
              } catch ( java.io.IOException ignored ) {}
            }
          }
          else if( finfo.isSOCKET() ) {
            // issue #131 (tmux layer 13): AF_UNIX 接続済 socket (unixSocket) の
            //   read-readiness を **early peek** で判定。pselect は line 1336 周辺
            //   に同等経路を持っていたが poll には無かった。
            //
            //   重要: この early peek だけでは tmux ls の rc=0 完走に不十分。
            //   isSOCKET block の末尾 (line ~2660 直後の "late peek") も並存
            //   させて 1 poll iteration 内で **2 段試行**することで、tmux client
            //   の libevent が server の MSG_WRITE_OPEN → MSG_WRITE_READY →
            //   MSG_WRITE → MSG_EXIT の 4 段 hand-shake を timeout なく完走でき
            //   るようになる。one path だけだと:
            //     - early のみ → tmux ls の stdout が空のまま rc=124 (timeout)
            //     - late のみ  → stdout は出るが client exit せず rc=124
            //   両方残す。下の `else if (finfo.unixSocket != null)` (late peek)
            //   を変更/削除する場合は本コメントを更新すること。
            //
            //   実装: pselect と同じ non-blocking 1-byte peek。読めれば
            //   Fileinfo.peekBuf に積んで POLLIN を立てる (peekBuf は次の
            //   finfo.Read が先に消費する)。
            if( finfo.unixSocket != null && !finfo.socketEof ) {
              any_alive = true;
              if( finfo.peekBuf != null && finfo.peekLen > 0 ) {
                revents |= (events & 0x43);
              } else {
                try {
                  finfo.unixSocket.configureBlocking( false );
                  java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate( 1 );
                  int r = finfo.unixSocket.read( bb );
                  if( r > 0 ) {
                    finfo.peekBuf = new byte[]{ bb.get(0) }; finfo.peekLen = 1;
                    revents |= (events & 0x43);
                  } else if( r < 0 ) {
                    finfo.socketEof = true;
                    revents |= 0x10;  // POLLHUP
                  }
                } catch ( java.io.IOException ignored ) { finfo.socketEof = true; }
              }
            }
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
            // issue #43 Phase 4-4 完走: AF_UNIX SocketChannel の **late peek**
            //   (block 先頭の "early peek" line ~2559 と pair で 2 段試行)。
            //   sshd の channel multiplexer は accept した agent-connection fd
            //   の read ready を poll で判定する。読めるなら 1 byte を peek
            //   して finfo.peekBuf にキャッシュ → 次の Read で消費させる
            //   (TCP socket の peek 経路と同じ pattern)。
            //
            //   issue #131 (tmux layer 13) で early peek を追加した後も本経路
            //   を残す必要がある: tmux client (libevent poll backend) は
            //   server からの 4 段 hand-shake (MSG_WRITE_OPEN → MSG_WRITE_READY
            //   → MSG_WRITE → MSG_EXIT) を 1 つも取り損ねず受信する必要があり、
            //   1 poll iteration 内に 2 回の peek 試行 (early + late) で安定
            //   する。本経路を削除すると tmux ls が rc=124 (timeout) で stuck。
            //   early peek の詳細コメント参照。
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
              // issue #131 (layer 17): ready() (=Available) が false でも peek で
              //   stream を能動 probe して pending 入力を拾う。JLine の reader.ready()
              //   は内部 buffer しか見ず、forked tmux server が passed tty fd を poll
              //   するケースで「buffer 空のまま false 固定」になり打鍵が server まで
              //   届かなかった (availablePeek は peek(timeout) で stream を probe)。
              boolean _rdy = !DET_TTY && ( sysinfo.kernel.console.Available()
                                           || sysinfo.kernel.console.availablePeek() );
              if( _rdy ) revents |= (events & 0x43);  // issue #113 / #131
              any_alive = true;
              ttyWaitSet = true;  // issue #206: TTY 入力待ち → blocking peek 対象
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
      long now206 = System.currentTimeMillis();
      if( deadline_ms >= 0 && now206 >= deadline_ms ) return 0;
      // issue #206: 旧 Thread.sleep(10) を deadline cap + TTY 入力で即復帰する
      //   blocking peek に置換 (対話レイテンシ解消、短 timeout の過剰待ちも回避)。
      int waitChunk = 10;
      if( deadline_ms >= 0 ) { long rem = deadline_ms - now206; if( rem < waitChunk ) waitChunk = (int)rem; }
      if( !pollWait( waitChunk, ttyWaitSet ) ) return 0;
    }
  }

  // ioctl — 64-bit address version
  private long amd64_ioctl( long fd_l, long req, long addr ) {
    int fd = (int)fd_l, i;
    int request = (int)req;
    long address = addr;
    boolean done = false;
    Fileinfo finfo = get_finfo( fd );
    if( TCGETS == request || TCGETS2 == request ) {
      // Phase 29: TCGETS は terminal でなければ -ENOTTY を返さなければ
      // issue #427: TCGETS2 (termios2) も同じ struct の先頭 36 byte は termios と同形。
      //   末尾に c_ispeed/c_ospeed が続く。codex (Rust) は raw mode に termios2 を使う。
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
      if( TCGETS2 == request ) {   // issue #427: termios2 は c_ispeed/c_ospeed (各 4 byte) が続く
        mem.store32( address, finfo.c_ispeed ); address+=4;
        mem.store32( address, finfo.c_ospeed ); address+=4;
      }
      done = true;
    }
    // TCSETS (0x5402) / TCSETSW (0x5403) / TCSETSF (0x5404) は payload 同じ。
    // W は出力 drain、F は入出力 flush。emulator では同 set_parameter 動作。
    if( TCSETS==request || TCSETSW==request || request==0x5404 || TCSETS2==request ) {
      int new_iflag = mem.load32( address ); address+=4;
      int new_oflag = mem.load32( address ); address+=4;
      int new_cflag = mem.load32( address ); address+=4;
      int new_lflag = mem.load32( address ); address+=4;
      byte new_line = (byte)mem.load8( address ); address+=1;
      byte[] new_cc = new byte[19];
      for( i=0; i<19; i++ ) { new_cc[i]=(byte)mem.load8(address); address++; }
      if( TCSETS2 == request ) {   // issue #427: termios2 は c_ispeed/c_ospeed (各 4 byte) が続く
        finfo.c_ispeed = mem.load32( address ); address+=4;
        finfo.c_ospeed = mem.load32( address ); address+=4;
      }
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
      // issue #225: pty fd は pty 単位に保持した winsize (SSH client のサイズ) を
      //   返す。launcher console のサイズではない (それだとリサイズに追従しない)。
      int rows, cols, xpix = 0, ypix = 0;
      if( finfo != null && finfo.pty_ptn >= 0 ) {
        int[] ws = sysinfo.kernel.pty.get_winsize( finfo.pty_ptn );
        rows = ws[0]; cols = ws[1]; xpix = ws[2]; ypix = ws[3];
      } else {
        rows = sysinfo.kernel.console.getRows( );
        cols = sysinfo.kernel.console.getColumns( );
      }
      if( rows <= 0 ) rows = 25;
      if( cols <= 0 ) cols = 80;
      mem.store16( address, (short)rows ); address+=2;
      mem.store16( address, (short)cols ); address+=2;
      mem.store16( address, (short)xpix ); address+=2;
      mem.store16( address, (short)ypix ); address+=2;
      done = true;
    }
    if( TIOCSWINSZ == request ) {
      // issue #225: pty fd への TIOCSWINSZ は SSH client のリサイズを sshd が
      //   master fd 経由で伝えるもの。pty 単位に新サイズを保持し、その pty の
      //   foreground process group に SIGWINCH を配信 → emacs/vim が TIOCGWINSZ で
      //   新サイズを取得し再描画する。旧実装は受信値を捨てていたので追従しなかった。
      if( finfo != null && finfo.pty_ptn >= 0 ) {
        int rows = mem.load16( address )     & 0xffff;
        int cols = mem.load16( address + 2 ) & 0xffff;
        int xpix = mem.load16( address + 4 ) & 0xffff;
        int ypix = mem.load16( address + 6 ) & 0xffff;
        sysinfo.kernel.pty.set_winsize( finfo.pty_ptn, rows, cols, xpix, ypix );
        int fg = sysinfo.kernel.pty.get_fg_pgrp( finfo.pty_ptn );
        if( fg > 0 ) sysinfo.kernel.kill( -fg, Signal.SIGWINCH );
        else         sysinfo.kernel.kill( -1, Signal.SIGWINCH );  // fg 未設定なら broadcast
      }
      // pty でない tty (launcher console 等) は従来どおり受信値を読み捨て success。
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
        } else if( finfo.is_pipe( true ) ) {
          // issue #223: pty slave / pipe の read 側 fd で読める byte 数。
          //   emacs は SIGIO (input_available_signal) を受けると interrupt_input
          //   経路で keyboard fd に FIONREAD を発行し「実際に読める byte 数」を
          //   確認する。ここで 0 を返すと「select がウソをついた」と判断して
          //   read を skip し続け、SSH 越し pty で一切入力できなくなる
          //   (handler は発火し pselect も ready=1 を返すのに read に進まない)。
          //   pipe buffer の実バイト数を返すと emacs が read に進む。
          avail = sysinfo.kernel.pipe_available( finfo.pipe_no );
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
    // issue #349: FS_IOC_GETFLAGS (_IOR('f',1,long)=0x80086601) / FS_IOC_SETFLAGS
    //   (_IOW('f',2,long)=0x40086602) = inode flags (chattr の +C/NOCOW 等)。
    //   systemd-tmpfiles の "h" entry (journal-nocow.conf の `h /var/log/journal +C`)
    //   が GETFLAGS→SETFLAGS で NOCOW を立てる。emulin の VFS は特殊属性を持たないので
    //   GETFLAGS は flags=0、SETFLAGS は no-op 受理する (ENOTTY を返すと systemd が
    //   journal の属性設定を「失敗」と扱い dpkg trigger が exit≠0 になる)。
    if( request == 0x80086601 ) {  // FS_IOC_GETFLAGS
      if( addr != 0 ) mem.store64( addr, 0L );
      return 0;
    }
    if( request == 0x40086602 ) {  // FS_IOC_SETFLAGS (no-op 受理)
      return 0;
    }
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
      //   0xc020660b = FS_IOC_FIEMAP (_IOWR('f',11,struct fiemap)): dpkg が file の
      //     extent map を引いて効率コピーを試みる。emulin の VFS は extent 概念を
      //     持たないので -ENOTTY を返せば dpkg は通常 read+write copy に fallback する
      //     (正しい挙動)。install 中に大量に呼ばれ warning が氾濫するので抑制 (issue #322)。
      boolean silent = (request == 0x4B66)
                       || (request >= 0x5600 && request <= 0x5607)
                       || (request == 0xc020660b);
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
    long new_addr;
    try {
      new_addr = mem.alloc_and_map( 0, (int)aligned_new, -1, 0 );
    } catch( NativeMemoryBackend.NativeOom oom ) {
      return -12L;  // -ENOMEM (native pool 枯渇、JVM crash 回避)
    }
    if( new_addr == 0 ) return -12L;
    long copy_len = Math.min( old_size, new_size );
    for( long i = 0; i < copy_len; i++ ) {
      mem.store8( new_addr + i, mem.load8( old_addr + i ) );
    }
    mem.free( old_addr, old_size );   // issue #392 review #1: long で渡す (≥2GB 切り詰め防止)
    return new_addr;
  }

  // mmap(addr, len, prot, flags, fd, offset) — AMD64: 6 直接引数
  // Linux: ページ境界 (4KB) に切り上げてマップする。length 以下はファイル
  // 内容、それ以降ページ末尾までゼロ詰めで OK。
  // Phase 32: prot を AllocInfo に保持して fork 時の reference share 判定に使う。
  // issue #113: madvise。MADV_DONTNEED(4)/MADV_FREE(8) は対象領域を「次アクセスで
  //   ゼロ」にする (実 Linux は物理ページ解放→zero page 再フォールト)。glibc の
  //   pthread stack cache は thread exit 時に stack を MADV_DONTNEED でゼロ化解放し、
  //   次 thread が同 stack を「ゼロ済み」前提で再利用する。emulin が no-op だと前
  //   thread の戻りアドレス/関数ポインタ等 garbage が残り、再利用 thread がそれを
  //   読んで wild jump → #113 crash。よって DONTNEED/FREE 対象の mapped 領域を
  //   実際にゼロ fill して実 Linux 挙動を再現する。他の advice は no-op success。
  //   best-effort: 未マップ/範囲外/例外でも madvise としては常に 0 を返す。
  // issue #403: ただし file-backed page (fd>=0 mmap / ELF PT_LOAD) は zero 化しない。
  //   実 Linux は DONTNEED 後の file-backed page を「file 内容」に再フォールトさせるが、
  //   emulin に file 再フォールト機構は無く zero 化すると内容を失う。Bun/V8 (claude) は
  //   ELF 埋め込み JS ソース (PT_LOAD) を madvise(DONTNEED) で decommit しつつ zero-copy
  //   文字列 view を保持するため、zero 化すると module 名が garbage 化して ENOENT 起動失敗
  //   していた (#403)。anonymous (#113 の pthread stack cache) は従来どおり zero 化する。
  private long amd64_madvise( long addr, long length, long advice ) {
    if( (advice == 4 || advice == 8) && length > 0 && length <= 0x7FFFFFFFL ) {
      try {
        long end = addr + length;
        // mapped かつ anonymous な page だけゼロ化 (跨ぎ/未マップは in() で、file-backed は
        //   isFileBacked() で弾く)。page 単位で確認。
        for( long p = addr; p < end; p += 0x1000L ) {
          if( mem.in( p ) && !mem.isFileBacked( p ) ) {
            long chunk = Math.min( 0x1000L, end - p );
            mem.bulkZero( p, (int)chunk );
          }
        }
      } catch( Throwable t ) { /* best-effort: madvise は常に成功 */ }
    }
    return 0;
  }

  private long amd64_mmap( long addr, long length, long prot, long flags, long fd, long offset ) {
    final long PAGE = 0x1000L;
    long aligned = (length + PAGE - 1) & ~(PAGE - 1);
    if( aligned <= 0 ) aligned = PAGE;
    // issue #416: io_uring fd の mmap は setup で確保済みの ring / SQE 領域 VA を返す
    //   (新規 mapping でなく既存 anon memory を共有)。IORING_OFF_SQ_RING=0 / CQ_RING=0x8000000 /
    //   SQES=0x10000000。SINGLE_MMAP なので SQ_RING で SQ+CQ 両方を覆う。
    {
      Fileinfo iou = ((int)fd >= 0) ? get_finfo( (int)fd ) : null;
      if( iou != null && iou.io_uring_flag ) {
        if( offset == 0x10000000L ) return iou.iouSqeVA;   // SQES
        return iou.iouRingVA;                              // SQ_RING (0) / CQ_RING
      }
    }
    long result;
    try {
      if( aligned > 0x7FFFFFFFL && (int)fd < 0 ) {
        // multi-GB anonymous mmap (JSC gigacage / WASM cage 等)。Java byte[] の
        //   2GB 上限を超えるので sparse (chunk 遅延 alloc) で backing する。
        result = mem.alloc_huge( addr, aligned, (int)prot, (flags & 0x10L) != 0 );  // 0x10 = MAP_FIXED
      } else {
        // flags も渡す (native backend が MAP_FIXED の有無で addr を hint として扱う。
        //   software backend は default メソッドが flags を無視するので従来挙動 byte-identical)。
        result = mem.alloc_and_map( addr, (int)aligned, (int)fd, offset, (int)prot, flags );   // issue #336: file offset を切り詰めない
        // issue #113: file-backed mmap (fd>=0) の元 file path を記録する。
        //   segfault dump で faulting RIP がどの library かを特定できるようにする。
        if( (int)fd >= 0 && result > 0 ) {
          Fileinfo mf = get_finfo( (int)fd );
          if( mf != null ) mem.set_map_path( result, mf.get_name() );
        }
      }
    } catch( NativeMemoryBackend.NativeOom oom ) {
      // native(WHP/KVM) pool 枯渇: Linux 同様 -ENOMEM をゲストに返す (JVM スレッド crash を回避)。
      //   claude/V8 等が巨大 mmap でプールを使い切ったとき、落とさずゲストに OOM を委ねる。
      if( System.getenv("EMULIN_TRACE_MMAP") != null )
        System.err.println( "[mmap] native pool 枯渇 -> ENOMEM: " + oom.getMessage() );
      return -12L;  // -ENOMEM
    }
    // issue #403: file-backed (fd>=0) は madvise(DONTNEED) で zero 化しない範囲として登録。
    //   anonymous (fd<0) は、同 VA が以前 file-backed だった痕跡 (MAP_FIXED で置換等) を消す
    //   (残っていると anon stack の DONTNEED zero 化が skip され #113 が再発する)。
    if( result > 0 ) {
      if( (int)fd >= 0 ) mem.registerFileBacked  ( result, aligned );
      else               mem.unregisterFileBacked( result, aligned );
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
          mem.store32( buf_addr + 28, eff_uid() );  // st_uid = 実効 uid (issue #383: root のとき 0 で従来同等)
          mem.store32( buf_addr + 32, eff_gid() );  // st_gid = 実効 gid
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
    if( name == null || name.isEmpty() ) return ENOENT;  // issue #322: 空 path は ENOENT
    name = sysinfo.get_full_path( process.get_curdir(), name );
    // issue #41 Phase 2: /dev/ptmx と /dev/pts/N は character device として
    //   stat する。ttyname(3) は fstat(slave_fd) と stat(/dev/pts/N) の
    //   st_dev/st_rdev が一致することを要求 — _set_tty_stat64 で固定値を
    //   返す両者を一致させる。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    // issue #131: /proc/<pid>/fd 合成 dir は S_IFDIR で返す。
    if( _isProcFdDirPath( name ) ) {
      _set_dir_stat64( buf_addr );
      return 0;
    }
    // issue #411: /proc・/proc/<pid> (dir) / /proc/<pid>/<file> (合成 regular) を stat。
    if( _statProcPath( name, buf_addr ) ) return 0;
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
    if( name == null || name.isEmpty() ) return ENOENT;  // issue #322: 空 path は ENOENT
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
    // issue #131: /proc/<pid>/fd は合成 directory として open する。tmux /
    //   openssh / glibc の closefrom 等で opendir + getdents64 で fd を列挙する
    //   経路に使われる。sandbox に実体は無いので Fileinfo を手動で構築し
    //   proc_fd_dir flag を立てる。fstat/getdents64 で flag を見て合成挙動。
    if( _isProcFdDirPath( name ) ) {
      Fileinfo fi = new Fileinfo();
      fi.opendir( name );           // opened=1、name 設定 (real file 不要)
      fi.proc_fd_dir = true;
      return (long)place_fd( fi );  // ★ atomic 確保 (3d-2c-42)
    }
    // issue #411: procfs (/proc, /proc/<pid>, /proc/<pid>/<file>)。open(2) 経路も共用。
    long procfd = _openProcfs( name, flags );
    if( procfd != -2L ) return procfd;
    return open_resolved( name, (int)flags );
  }

  // issue #131: /proc/<pid>/fd または /proc/self/fd の path 判定。
  private static boolean _isProcFdDirPath( String name ) {
    if( name == null ) return false;
    if( "/proc/self/fd".equals(name) || "/proc/self/fd/".equals(name) ) return true;
    if( !name.startsWith("/proc/") ) return false;
    int slash = name.indexOf('/', 6);
    if( slash < 0 ) return false;
    String mid = name.substring(6, slash);
    if( mid.isEmpty() ) return false;
    for( int i = 0; i < mid.length(); i++ ) {
      if( !Character.isDigit(mid.charAt(i)) ) return false;
    }
    String tail = name.substring(slash);
    return "/fd".equals(tail) || "/fd/".equals(tail);
  }

  // ===== issue #411: procfs — process table を /proc に露出し ps/top/pgrep/kill を動かす =====
  //   ps/top/pgrep は /proc を getdents で走査して pid dir を見つけ、
  //   /proc/<pid>/{stat,cmdline,status,comm} を読む。内部の Kernel.ptable を合成 file 化する。

  private static boolean _isProcRoot( String name ) {
    return "/proc".equals( name ) || "/proc/".equals( name );
  }

  // /proc/<pid> または /proc/self (tail 無し dir) が指す pid。非該当は -1。
  private int _procDirPid( String name ) {
    if( name == null || !name.startsWith( "/proc/" ) ) return -1;
    String mid = name.substring( 6 );
    if( mid.endsWith( "/" ) ) mid = mid.substring( 0, mid.length() - 1 );
    if( mid.isEmpty() || mid.indexOf( '/' ) >= 0 ) return -1;   // 配下があれば dir 自身でない
    if( "self".equals( mid ) || "thread-self".equals( mid ) ) return process.pid;
    for( int i = 0; i < mid.length(); i++ ) if( !Character.isDigit( mid.charAt( i ) ) ) return -1;
    try { return Integer.parseInt( mid ); } catch( Exception e ) { return -1; }
  }

  // /proc/<pid>|self/<file> を pid と file 名に分解 (outFile[0] に file 名)。非該当は -1。
  private int _procFilePid( String name, String[] outFile ) {
    if( name == null || !name.startsWith( "/proc/" ) ) return -1;
    String rest = name.substring( 6 );
    int slash = rest.indexOf( '/' );
    if( slash < 0 ) return -1;
    String mid = rest.substring( 0, slash );
    String file = rest.substring( slash + 1 );
    if( file.isEmpty() || file.indexOf( '/' ) >= 0 ) return -1;   // 1 階層のみ (stat/cmdline 等)
    int pid;
    if( "self".equals( mid ) || "thread-self".equals( mid ) ) pid = process.pid;
    else {
      if( mid.isEmpty() ) return -1;
      for( int i = 0; i < mid.length(); i++ ) if( !Character.isDigit( mid.charAt( i ) ) ) return -1;
      try { pid = Integer.parseInt( mid ); } catch( Exception e ) { return -1; }
    }
    outFile[0] = file;
    return pid;
  }

  // comm = argv[0] の basename を 15 文字に切る (Linux /proc/<pid>/comm 仕様)。
  private String _procComm( Process p ) {
    if( p != null && p.init_process ) return "init";   // pid 1 の placeholder
    String n = ( p != null && p.name != null ) ? p.name
             : ( p != null && p.exec_path != null ) ? p.exec_path : "?";
    int sl = n.lastIndexOf( '/' );
    if( sl >= 0 ) n = n.substring( sl + 1 );
    if( n.length() > 15 ) n = n.substring( 0, 15 );
    return n.isEmpty() ? "?" : n;
  }

  // /proc/<pid>/<file> の合成内容。pid 不在 or 未対応 file は null。
  private byte[] _procFileContent( int pid, String file ) {
    ProcessInfo pi = sysinfo.kernel.get_pinfo( pid );
    if( pi == null ) return null;
    Process p = pi.process;
    String comm = _procComm( p );
    boolean exited = ( p == null ) || p.exit_flag;
    int ppid = pi.ppid;
    int uid  = ( p != null ) ? p.uid : 0;
    int gid  = ( p != null ) ? p.gid : 0;
    int pgrp = ( p != null && p.pgrp > 0 ) ? p.pgrp : pid;
    java.nio.charset.Charset U8 = java.nio.charset.StandardCharsets.UTF_8;
    switch( file ) {
      case "comm":
        return ( comm + "\n" ).getBytes( U8 );
      case "cmdline": {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try {
          if( p != null && p.argv != null && p.argv.length > 0 ) {
            for( String a : p.argv ) { if( a != null ) bo.write( a.getBytes( U8 ) ); bo.write( 0 ); }
          } else if( p != null ) {
            String c = ( p.exec_path != null ) ? p.exec_path : ( p.name != null ? p.name : "" );
            bo.write( c.getBytes( U8 ) ); bo.write( 0 );
          }
        } catch( java.io.IOException e ) {}
        return bo.toByteArray();
      }
      case "stat": {
        char state = exited ? 'Z' : 'S';
        // field: 1 pid 2 (comm) 3 state 4 ppid 5 pgrp 6 session 7 tty_nr 8 tpgid 9 flags ...
        StringBuilder sb = new StringBuilder();
        sb.append( pid ).append( " (" ).append( comm ).append( ") " ).append( state )
          .append( ' ' ).append( ppid ).append( ' ' ).append( pgrp ).append( ' ' ).append( pgrp )
          .append( " 0 -1 0" );          // tty_nr tpgid flags
        for( int f = 10; f <= 52; f++ ) sb.append( f == 20 ? " 1" : " 0" );  // 20=num_threads
        sb.append( '\n' );
        return sb.toString().getBytes( U8 );
      }
      case "status": {
        String st = exited ? "Z (zombie)" : "S (sleeping)";
        StringBuilder sb = new StringBuilder();
        sb.append( "Name:\t" ).append( comm ).append( '\n' )
          .append( "Umask:\t0022\n" )
          .append( "State:\t" ).append( st ).append( '\n' )
          .append( "Tgid:\t" ).append( pid ).append( '\n' )
          .append( "Pid:\t" ).append( pid ).append( '\n' )
          .append( "PPid:\t" ).append( ppid ).append( '\n' )
          .append( "Uid:\t" ).append( uid ).append( '\t' ).append( uid ).append( '\t' ).append( uid ).append( '\t' ).append( uid ).append( '\n' )
          .append( "Gid:\t" ).append( gid ).append( '\t' ).append( gid ).append( '\t' ).append( gid ).append( '\t' ).append( gid ).append( '\n' )
          .append( "Threads:\t1\n" );
        return sb.toString().getBytes( U8 );
      }
      default:
        return null;
    }
  }

  // /proc または /proc/<pid> の getdents64。
  private long _getdents64_procfs( int fd, long dirp, int count ) {
    // get_name(fd) は native→virtual 変換 (Mount) を通すが、proc_dir の name は
    //   既に guest path ("/proc" 等) なので Fileinfo.name を直接参照する。
    Fileinfo fi = get_finfo( fd );
    String dname = ( fi != null ) ? fi.name : null;
    java.util.ArrayList<String> names = new java.util.ArrayList<>();
    java.util.ArrayList<Integer> types = new java.util.ArrayList<>();
    names.add( "." );  types.add( 4 );
    names.add( ".." ); types.add( 4 );
    if( _isProcRoot( dname ) ) {
      names.add( "self" ); types.add( 10 );  // DT_LNK
      int n = sysinfo.kernel.ptable_size();
      for( int pid = 1; pid <= n; pid++ ) {
        ProcessInfo pi = sysinfo.kernel.get_pinfo( pid );
        if( pi != null && pi.process != null ) {
          names.add( Integer.toString( pi.process.pid ) ); types.add( 4 );  // DT_DIR
        }
      }
    } else if( _procDirPid( dname ) > 0 ) {
      for( String f : new String[]{ "stat", "cmdline", "status", "comm" } ) { names.add( f ); types.add( 8 ); }
      names.add( "fd" ); types.add( 4 );
    }
    return _emitDirents( fd, dirp, count, names, types );
  }

  // dirent64 列を書き出す共通 writer (byte offset cursor 付き)。
  private long _emitDirents( int fd, long dirp, int count, java.util.List<String> names, java.util.List<Integer> types ) {
    int start = get_ptr( fd );
    long d_off = 0, w_size = 0, address = dirp;
    for( int idx = 0; idx < names.size(); idx++ ) {
      String d_name = names.get( idx );
      int name_bytes = d_name.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
      int reclen = ( 19 + name_bytes + 1 + 7 ) & ~7;
      long old_d_off = d_off;
      d_off += reclen;
      if( count < d_off ) break;
      if( start <= old_d_off ) {
        long ino_val = ( idx == 0 ) ? 1L : ( idx == 1 ) ? 2L : (long)( idx + 100 );
        mem.store64( address +  0, ino_val );
        mem.store64( address +  8, d_off );
        mem.store16( address + 16, (short)reclen );
        mem.store8 ( address + 18, (byte)(int)types.get( idx ) );
        mem.storeString( address + 19, d_name );
        for( int p = 19 + name_bytes + 1; p < reclen; p++ ) mem.store8( address + p, 0 );
        w_size += reclen;
        address = dirp + w_size;
      }
    }
    set_ptr( fd, (int)d_off );
    return w_size;
  }

  // procfs 合成 regular file (/proc/<pid>/stat 等) の path stat (struct stat 144B)。
  private void _set_procfile_stat64( long addr, long size ) {
    for( int i = 0; i < 144; i += 8 ) mem.store64( addr + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    mem.store64( addr +  0, 0x14 );          // st_dev
    mem.store64( addr +  8, 1L );            // st_ino
    mem.store64( addr + 16, 1L );            // st_nlink
    mem.store32( addr + 24, 0x8124 );        // st_mode = S_IFREG | 0444
    mem.store64( addr + 48, size );          // st_size
    mem.store64( addr + 56, 1024L );         // st_blksize
    mem.store64( addr + 72, now );           // st_atime
    mem.store64( addr + 88, now );           // st_mtime
    mem.store64( addr +104, now );           // st_ctime
  }

  // path 指定の stat で /proc 系を処理 (dir は S_IFDIR、合成 file は S_IFREG)。処理したら true。
  private boolean _statProcPath( String name, long buf_addr ) {
    if( _isProcRoot( name ) || _procDirPid( name ) > 0 ) { _set_dir_stat64( buf_addr ); return true; }
    byte[] sys = _procSysFileContent( name );
    if( sys != null ) { _set_procfile_stat64( buf_addr, sys.length ); return true; }
    String[] ff = new String[1];
    int pid = _procFilePid( name, ff );
    if( pid > 0 ) {
      byte[] c = _procFileContent( pid, ff[0] );
      if( c != null ) { _set_procfile_stat64( buf_addr, c.length ); return true; }
    }
    return false;
  }

  // システム全体の /proc file (/proc/meminfo /uptime /stat /loadavg)。非該当は null。
  //   ps aux は %MEM の分母に /proc/meminfo の MemTotal を読むため必須。
  private byte[] _procSysFileContent( String name ) {
    java.nio.charset.Charset U8 = java.nio.charset.StandardCharsets.UTF_8;
    switch( name ) {
      case "/proc/meminfo": {
        long totKb  = 2L * 1024 * 1024;   // 2 GiB を total に報告 (%MEM 分母)
        long freeKb = totKb / 2;
        StringBuilder sb = new StringBuilder();
        sb.append( "MemTotal:       " ).append( totKb ).append( " kB\n" )
          .append( "MemFree:        " ).append( freeKb ).append( " kB\n" )
          .append( "MemAvailable:   " ).append( freeKb ).append( " kB\n" )
          .append( "Buffers:               0 kB\n" )
          .append( "Cached:                0 kB\n" )
          .append( "SwapTotal:             0 kB\n" )
          .append( "SwapFree:              0 kB\n" );
        return sb.toString().getBytes( U8 );
      }
      case "/proc/uptime":
        return "100.00 100.00\n".getBytes( U8 );
      case "/proc/loadavg":
        return "0.00 0.00 0.00 1/1 1\n".getBytes( U8 );
      case "/proc/stat":
        return ( "cpu  0 0 0 0 0 0 0 0 0 0\ncpu0 0 0 0 0 0 0 0 0 0 0\nbtime 0\nprocesses 1\n" ).getBytes( U8 );
      default:
        return null;
    }
  }

  // 合成内容 (memContent) を持つ fd を開く。
  private long _openMemFd( byte[] content, long flags ) {
    int mfd = FileOpen( "<procmaps>", "r", O_RDONLY );   // memContent 対応 fd
    if( mfd < 0 ) return -1L;
    Fileinfo mfi = (Fileinfo)flist.elementAt( mfd );
    mfi.memContent = content;
    mfi.memPos = 0;
    if( ( flags & 0x80000L ) != 0 ) set_cloexec( mfd, true );  // O_CLOEXEC
    return (long)mfd;
  }

  // procfs path (絶対 path 前提) を open する。proc path でなければ -2 を返し、
  //   呼び出し元 (open(2)/openat(257)) は通常 open に fall through する。
  private long _openProcfs( String name, long flags ) {
    byte[] sys = _procSysFileContent( name );
    if( sys != null ) return _openMemFd( sys, flags );
    String[] ff = new String[1];
    int pfpid = _procFilePid( name, ff );
    if( pfpid > 0 ) {
      byte[] content = _procFileContent( pfpid, ff[0] );
      if( content != null ) return _openMemFd( content, flags );
      // 未対応 file (maps/fd 等) は通常経路 (-2) に委ねる
    }
    if( _isProcRoot( name ) || _procDirPid( name ) > 0 ) {
      Fileinfo fi = new Fileinfo();
      fi.opendir( name );
      fi.proc_dir = true;
      return (long)place_fd( fi );
    }
    return -2L;  // procfs path ではない
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
    int mrc = mkdirErrno( full );   // issue(npm): 失敗を errno (ENOENT=親不在/EACCES) で返す。node の mkdirp が親作成に必要
    if( mrc != 0 ) return mrc;
    // issue #131 (tmux): 要求 mode を chmod で反映 (sys_mkdir と同じ理由)。
    if( mode != 0 ) do_chmod( full, mode & 07777 );
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
  // issue #130 Tier 2: statx(2) を実装する。旧来 ENOSYS スタブだったが、Rust の
  //   std は fstat を statx 経由で行い、ENOSYS fallback 経路 (newfstatat(fd,"")) が
  //   emulin で不整合を起こして ripgrep/fd が segfault していた。実 Linux と同じく
  //   statx を成功させることで fallback を踏ませない。layout は Linux struct statx
  //   (256 byte)。STATX_BASIC_STATS 相当のみ充填する。
  private static final int STATX_BASIC_STATS = 0x000007ff;
  private void _fill_statx( long a, Inode ino ) {
    for( int i = 0; i < 256; i += 8 ) mem.store64( a + i, 0L );
    mem.store32( a + 0x00, STATX_BASIC_STATS );                   // stx_mask
    mem.store32( a + 0x04, (int)(ino.st_blksize & 0xFFFFFFFFL) ); // stx_blksize
    mem.store32( a + 0x10, (int)(ino.st_nlink & 0xFFFFFFFFL) );   // stx_nlink
    int _sxeu = eff_uid(), _sxeg = eff_gid();   // issue #383
    mem.store32( a + 0x14, ( (ino.st_uid & 0xFFFF) == 0 && _sxeu != 0 ) ? _sxeu : ( ino.st_uid & 0xFFFF ) );  // stx_uid
    mem.store32( a + 0x18, ( (ino.st_gid & 0xFFFF) == 0 && _sxeg != 0 ) ? _sxeg : ( ino.st_gid & 0xFFFF ) );  // stx_gid
    mem.store16( a + 0x1C, (short)(ino.st_mode & 0xFFFF) );       // stx_mode
    mem.store64( a + 0x20, ino.st_ino & 0xFFFFFFFFL );            // stx_ino
    mem.store64( a + 0x28, ino.st_size );                         // stx_size
    mem.store64( a + 0x30, ino.st_blocks );                       // stx_blocks
    mem.store64( a + 0x40, ino.st_atime ); mem.store32( a + 0x48, (int)ino.st_atime_nsec ); // atime
    mem.store64( a + 0x60, ino.st_ctime ); mem.store32( a + 0x68, (int)ino.st_ctime_nsec ); // ctime
    mem.store64( a + 0x70, ino.st_mtime ); mem.store32( a + 0x78, (int)ino.st_mtime_nsec ); // mtime
    mem.store32( a + 0x8C, (int)(ino.st_dev & 0xFFFFFFFFL) );     // stx_dev_minor
  }
  private void _fill_statx_char( long a ) {
    for( int i = 0; i < 256; i += 8 ) mem.store64( a + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    mem.store32( a + 0x00, STATX_BASIC_STATS );          // stx_mask
    mem.store32( a + 0x04, 1024 );                       // stx_blksize
    mem.store32( a + 0x10, 1 );                          // stx_nlink
    mem.store16( a + 0x1C, (short)(0x2000 | 0x1B6) );    // stx_mode = S_IFCHR | 0666
    mem.store64( a + 0x20, 1L );                         // stx_ino (non-zero)
    mem.store64( a + 0x40, now ); mem.store64( a + 0x60, now ); mem.store64( a + 0x70, now );
  }
  // issue #322: statx(AT_SYMLINK_NOFOLLOW) で symlink 自身を返す (S_IFLNK)。
  //   st_ino は name の hash で non-zero (rm/fts が st_ino=0 を無効 entry 扱いするため)。
  private void _fill_statx_symlink( long a, String name, long targetLen ) {
    for( int i = 0; i < 256; i += 8 ) mem.store64( a + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    long ino = ( name.hashCode() & 0xFFFFFFFFL ); if( ino == 0 ) ino = 1;
    mem.store32( a + 0x00, STATX_BASIC_STATS );          // stx_mask
    mem.store32( a + 0x04, 4096 );                       // stx_blksize
    mem.store32( a + 0x10, 1 );                          // stx_nlink
    mem.store16( a + 0x1C, (short)(0xA000 | 0x1FF) );    // stx_mode = S_IFLNK | 0777
    mem.store64( a + 0x20, ino );                        // stx_ino (non-zero)
    mem.store64( a + 0x28, targetLen );                  // stx_size = target 文字列長
    mem.store64( a + 0x40, now ); mem.store64( a + 0x60, now ); mem.store64( a + 0x70, now );
    mem.store32( a + 0x8C, 1 );                          // stx_dev_minor (non-zero)
  }
  // statx(dirfd, pathname, flags, mask, statxbuf)
  private long amd64_statx( int dirfd, long path_addr, int flags, int mask, long buf_addr ) {
    final int AT_EMPTY_PATH = 0x1000;
    final long EFAULT = -14L;
    // issue #131: Rust std は libc::statx の存在検査として statx(0,0,0,0,0) を
    //   呼ぶ (kernel が ENOSYS or EFAULT を返すか確認)。旧実装は buf_addr=0 でも
    //   _fill_statx_char(0) を進めて address 0 への store8 で emulin 内 segfault。
    //   実 Linux と同じく NULL buf は EFAULT で返す。
    if( buf_addr == 0 ) return EFAULT;
    // issue #131: NULL path で AT_EMPTY_PATH 無しは EFAULT (path 解決不能のため)。
    //   AT_EMPTY_PATH 付きの NULL/空 path は fd 自身 stat として valid なので許容。
    if( path_addr == 0 && (flags & AT_EMPTY_PATH) == 0 ) return EFAULT;
    String path = ( path_addr != 0 ) ? mem.loadString( path_addr ) : "";
    if( (flags & AT_EMPTY_PATH) != 0 || path.isEmpty() ) {
      // fd 自身を stat (AT_EMPTY_PATH or 空 path)
      if( isSTD(dirfd) || isERR(dirfd) || isPIPE(dirfd) ) { _fill_statx_char( buf_addr ); return 0; }
      Fileinfo fi = get_finfo( dirfd );
      if( fi == null ) return EBADF;
      if( fi.pty_master || fi.pty_slave ) { _fill_statx_char( buf_addr ); return 0; }
      // issue #131: /proc/<pid>/fd 合成 dir
      if( fi.proc_fd_dir ) { _fill_statx_dir( buf_addr ); return 0; }
      // issue #411: /proc・/proc/<pid> 合成 dir / 合成 file fd (memContent)
      if( fi.proc_dir ) { _fill_statx_dir( buf_addr ); return 0; }
      if( fi.memContent != null ) { _fill_statx_reg( buf_addr, fi.memContent.length ); return 0; }
      String nm = get_name( dirfd );
      if( nm == null ) return EBADF;
      nm = sysinfo.get_full_path( process.get_curdir(), nm );
      Inode ino = new Inode( nm, sysinfo );
      if( !ino.isExists() ) return ENOENT;
      _fill_statx( buf_addr, ino );
      return 0;
    }
    String name = resolve_at_path( dirfd, path );
    if( name == null ) return EBADF;
    // issue #131: /proc/<pid>/fd 合成 dir は S_IFDIR で返す。
    if( _isProcFdDirPath( name ) ) {
      _fill_statx_dir( buf_addr );
      return 0;
    }
    // issue #411: /proc・/proc/<pid> (dir) / /proc/<pid>/<file> (合成 regular) を statx。
    if( _statxProcPath( name, buf_addr ) ) return 0;
    // issue #322: AT_SYMLINK_NOFOLLOW (= lstat 相当、ls -l が使う) で最終 component が
    //   symlink なら symlink 自身の stat (S_IFLNK) を返す。旧実装は flag を無視して
    //   Inode (follow) で target を stat していたので ls -l が symlink を target の
    //   regular file として表示していた。
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    if( (flags & AT_SYMLINK_NOFOLLOW) != 0 ) {
      String native_path = sysinfo.get_native_path_nofollow( name );
      String cyg_target = CygSymlink.enabled() ? CygSymlink.read( native_path ) : null;
      java.nio.file.Path p = java.nio.file.Paths.get( native_path );
      if( cyg_target != null || java.nio.file.Files.isSymbolicLink( p ) ) {
        try {
          String tgt = ( cyg_target != null ) ? cyg_target
              : java.nio.file.Files.readSymbolicLink( p ).toString();
          _fill_statx_symlink( buf_addr, name, (long)tgt.length() );
          return 0;
        } catch( Exception e ) { return ENOENT; }
      }
    }
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _fill_statx( buf_addr, inode );
    return 0;
  }

  // issue #305: path に Windows drive-letter 絶対 path (X:\ または X:/) が含まれるか。
  //   Windows host の C:\Users\... が env (TEMP 等) 経由で guest path に混入したケースを
  //   検出する。Linux file path に drive-letter pattern はまず無いので誤検出は無視できる。
  static boolean _hasWinDrivePath( String p ) {
    if( p == null ) return false;
    for( int i = 0; i + 2 < p.length(); i++ ) {
      char c = p.charAt( i );
      if( ( (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') )
          && p.charAt( i + 1 ) == ':'
          && ( p.charAt( i + 2 ) == '\\' || p.charAt( i + 2 ) == '/' ) ) {
        return true;
      }
    }
    return false;
  }

  private long amd64_newfstatat( int dirfd, long path_addr, long buf_addr, int flags ) {
    final int AT_EMPTY_PATH = 0x1000;
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    String path = (path_addr != 0) ? mem.loadString( path_addr ) : "";
    // ★ issue #221 step 3d-2c-42: 空 path の扱いは AT_EMPTY_PATH の有無で分かれる (Linux 準拠)。
    //   AT_EMPTY_PATH 有 → dirfd 自身を stat (fstat 相当)。AT_EMPTY_PATH 無 + 空 path → ENOENT。
    //   旧実装は「空 path なら無条件に fstat(dirfd)」で、Go の os.Stat("") (AT_FDCWD,"",flags=0) を
    //   fstat(-100)=EBADF にしていた。Go は ENOENT を os.IsNotExist で握って先へ進む設計なので、
    //   EBADF だと「未知のエラー」と判断して go build が "bad file descriptor" で落ちる。
    if( (flags & AT_EMPTY_PATH) != 0 ) {
      return amd64_fstat( (long)dirfd, buf_addr );
    }
    if( path.isEmpty() ) return ENOENT;  // 空 path + AT_EMPTY_PATH 無し = ENOENT
    String name = resolve_at_path( dirfd, path );
    if( name == null ) return EBADF;
    // issue #305: guest path に Windows host の絶対 path (C:\... = TEMP 漏れ等) が混入すると
    //   Windows JVM の Paths.get / File が InvalidPathException で native eval crash する。
    //   drive-letter path (X:\ or X:/) を検出して ENOENT で弾く (根本は Kernel.boot の
    //   TMPDIR=/tmp 固定。これは万一漏れた場合の防御)。
    if( _hasWinDrivePath( name ) ) return ENOENT;
    // issue #41 Phase 2: pty (path-based) は character device として stat。
    if( "/dev/ptmx".equals( name ) || PtyManager.parse_slave_path( name ) >= 0 ) {
      _set_tty_stat64( buf_addr );
      return 0;
    }
    // issue #131: /proc/<pid>/fd 合成 dir は S_IFDIR で返す (sandbox に実体
    //   無し、Inode resolve 失敗を避ける)。tmux 等は openat 前後で newfstatat
    //   を呼んで dir の存在確認することがある。
    if( _isProcFdDirPath( name ) ) {
      _set_dir_stat64( buf_addr );
      return 0;
    }
    // issue #411: /proc・/proc/<pid> (dir) / /proc/<pid>/<file> (合成 regular) を stat。
    if( _statProcPath( name, buf_addr ) ) return 0;
    // issue #349: /dev/null /zero /full /tty /random /urandom は path stat でも
    //   character device (S_IFCHR) を返す。sandbox には 0-byte regular file が
    //   置いてあるため Inode だと S_IFREG になり、dash の noclobber (`set -C`) 下の
    //   `cmd > /dev/null` が「regular file が存在」と判断して "cannot create
    //   /dev/null: File exists" で失敗していた (exim4 の update-exim4.conf 等)。
    //   fstat(open fd) は既に CHR を返すが path stat 経路にも揃える。
    if( _isStdDevicePath( name ) ) {
      _set_tty_stat64( buf_addr, _stdDeviceRdev( name ) );
      return 0;
    }
    // Phase 33-9c: AT_SYMLINK_NOFOLLOW (= glibc lstat の実体) — path が
    // symlink なら target を follow せず symlink 自身の stat を返す。
    // 旧実装は flag 無視で target を follow し、broken symlink (e.g. git の
    // symlink test 用 .git/<rand> -> testing) で ENOENT 返却 → git は
    // 「symlink test 失敗」と誤判定し dangling symlink を残す → rm -rf
    // で .git/ rmdir 失敗。
    if( (flags & AT_SYMLINK_NOFOLLOW) != 0 ) {
      return lstatNameToBuf( name, buf_addr );  // issue #349: NOFOLLOW stat を共通ヘルパへ
    }
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // issue #349: 最終 component を follow しない stat。symlink なら S_IFLNK、それ以外は
  //   通常 stat。lstat (newfstatat AT_SYMLINK_NOFOLLOW) と O_PATH fd の fstat で共用する。
  private long lstatNameToBuf( String name, long buf_addr ) {
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
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) return ENOENT;
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // fstat(fd, buf) — AMD64 struct stat (144 bytes)
  private long amd64_fstat( long fd, long buf_addr ) {
    // issue #422: std console (fd 0/1/2) は /dev/tty (rdev 0x500 = makedev(5,0)) と
    //   同じ st_rdev/st_ino を報告する。さもないと glibc ttyname_r(0) が
    //   readlink(/proc/self/fd/0)=/dev/tty を stat 検証する際に rdev 不一致 (console
    //   0x302 vs /dev/tty 0x500) で reject → /dev scan で /dev/ptmx (0x302=console と
    //   一致) を誤採用 → claude(Bun) が pty MASTER を stdin に開いて読めなくなる
    //   (#422 の対話入力不能の真因)。console を /dev/tty と一致させ ttyname_r が
    //   /dev/tty を返すようにする。pipe (redirect) は従来どおり legacy 0x302。
    if( isSTD((int)fd) || isERR((int)fd) ) {
      _set_tty_stat64( buf_addr, 0x500L );
      return 0;
    }
    if( isPIPE((int)fd) ) {
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
    // issue #131: /proc/<pid>/fd 合成 dir → S_IFDIR で返す。
    if( dbg.proc_fd_dir ) {
      _set_dir_stat64( buf_addr );
      return 0;
    }
    // issue #411: /proc・/proc/<pid> 合成 dir → S_IFDIR、合成 file fd (memContent:
    //   /proc/<pid>/stat 等 + /proc/self/maps) → S_IFREG (opendir/fopen の fstat 用)。
    if( dbg.proc_dir ) {
      _set_dir_stat64( buf_addr );
      return 0;
    }
    if( dbg.memContent != null ) {
      _set_procfile_stat64( buf_addr, dbg.memContent.length );
      return 0;
    }
    // issue #349: O_PATH fd は最終 component を follow しない lstat を返す
    //   (symlink を open(O_PATH) した systemd-tmpfiles が fstat で S_IFLNK を確認する)。
    if( dbg.o_path ) {
      String onm = get_name( (int)fd );
      if( onm == null ) return EBADF;
      onm = sysinfo.get_full_path( process.get_curdir(), onm );
      return lstatNameToBuf( onm, buf_addr );
    }
    String name = get_name( (int)fd );
    if( name == null ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir(), name );
    // issue #131: /dev/null / zero / full / tty / random / urandom は Linux で
    //   character device。tmux 等は fstat(open("/dev/null")) で S_ISCHR を確認
    //   して daemonize の正当性を検証するため、sandbox に touch で 0-byte file
    //   が在ろうと無かろうと、fstat は CHR を返す必要がある (旧実装は Inode が
    //   見つけ損なって ENOENT、見つけても regular file mode で返し、tmux の
    //   server 起動が daemonize 段で exit 1 になっていた)。
    // issue #131 続: tmux は更に fstat の st_rdev を見て real /dev/null か検証
    //   (期待: major:1, minor:3 = 0x103)。device path 別に正しい rdev を渡す。
    if( _isStdDevicePath( name ) ) {
      _set_tty_stat64( buf_addr, _stdDeviceRdev( name ) );
      return 0;
    }
    Inode inode = new Inode( name, sysinfo );
    if( !inode.isExists() ) {
      // issue #191: unlink された open fd の fstat。apt は download/atomic-write
      //   の scratch に mkstemp → 即 unlink (path を消して fd だけ保持) する匿名
      //   temp pattern を使う (O_TMPFILE 非対応 fs への portable fallback)。Linux
      //   の fstat は path と無関係に open fd に対して動くので、path が消えていても
      //   RandomAccessFile ハンドルから size を取って regular file の stat を合成
      //   する。旧実装は path ベースで ENOENT を返し、apt が "Unable to determine
      //   file size for fd N - fstat" で repository を弾いていた。
      if( dbg.f != null ) {
        long sz;
        try { sz = dbg.f.length( ); }
        catch( java.io.IOException e ) { sz = 0; }
        _set_anon_file_stat64( buf_addr, sz, (int)fd );
        return 0;
      }
      return ENOENT;
    }
    _set_file_stat64( buf_addr, inode );
    _fixup_stat_mode( buf_addr, name, inode );
    return 0;
  }

  // issue #191: unlink 済み open fd 用の合成 struct stat。regular file
  //   (S_IFREG|0600)、st_nlink=0 (unlinked)、st_size はハンドルの実 length。
  //   st_ino は 0 だと一部 tool が skip するので fd 由来の非ゼロ合成値。
  private void _set_anon_file_stat64( long addr, long size, int fd ) {
    long ino     = 0x10000000L + (fd & 0xFFFFFFL);
    long blocks  = (size + 511) / 512;
    mem.store64( addr,      1L              ); addr += 8;  // st_dev
    mem.store64( addr,      ino             ); addr += 8;  // st_ino
    mem.store64( addr,      0L              ); addr += 8;  // st_nlink (unlinked)
    mem.store32( addr,      0x8000 | 0x180  ); addr += 4;  // st_mode = S_IFREG|0600
    mem.store32( addr,      0               ); addr += 4;  // st_uid (root)
    mem.store32( addr,      0               ); addr += 4;  // st_gid (root)
    mem.store32( addr,      0               ); addr += 4;  // __pad0
    mem.store64( addr,      0L              ); addr += 8;  // st_rdev
    mem.store64( addr,      size            ); addr += 8;  // st_size
    mem.store64( addr,      4096L           ); addr += 8;  // st_blksize
    mem.store64( addr,      blocks          ); addr += 8;  // st_blocks
    mem.store64( addr,      0L              ); addr += 8;  // st_atime
    mem.store64( addr,      0L              ); addr += 8;  // st_atime_nsec
    mem.store64( addr,      0L              ); addr += 8;  // st_mtime
    mem.store64( addr,      0L              ); addr += 8;  // st_mtime_nsec
    mem.store64( addr,      0L              ); addr += 8;  // st_ctime
    mem.store64( addr,      0L              ); addr += 8;  // st_ctime_nsec
    mem.store64( addr,      0L              ); addr += 8;  // __unused[0]
    mem.store64( addr,      0L              ); addr += 8;  // __unused[1]
    mem.store64( addr,      0L              );             // __unused[2]
  }

  // issue #131: 標準的な character device path の判定。emulin は /dev/null /
  //   /dev/urandom (+ /dev/random) を device router で `<null>` / `<urandom>`
  //   というマーカ名で扱うため、それも match させる (Kernel.java の path 解決)。
  private static boolean _isStdDevicePath( String name ) {
    if( name == null ) return false;
    return "<null>".equals( name )       || "<urandom>".equals( name )
        || "/dev/null".equals( name )    || "/dev/zero".equals( name )
        || "/dev/full".equals( name )    || "/dev/tty".equals( name )
        || "/dev/random".equals( name )  || "/dev/urandom".equals( name );
  }

  // AMD64 struct stat レイアウト (144 bytes、Linux x86-64 ABI):
  //   st_dev(8) st_ino(8) st_nlink(8) st_mode(4) st_uid(4) st_gid(4) __pad0(4)
  //   st_rdev(8) st_size(8) st_blksize(8) st_blocks(8)
  //   st_atime(8) st_atime_nsec(8) st_mtime(8) st_mtime_nsec(8)
  //   st_ctime(8) st_ctime_nsec(8) __unused[3](24)
  // Phase 27 step 22: 旧実装は st_size / st_atime 等を 32-bit mask で
  //   切り詰めていた (>2GB ファイル truncate / Y2038 wrap)。マスクを
  //   除去して Inode の long フィールドをそのまま使う。
  // issue #383: file の st_uid/st_gid を「実行プロセスの実効 uid/gid」(geteuid と同値) で報告する補助。
  //   NTFS 等で実 file 所有者を追跡できず file_uid()=EMULIN_UID(既定 0) を返す環境では、sshd の
  //   setuid drop で process が非 root(uid 1000) になると「自分の file が root(0) 所有」と見え、
  //   mozc の「profile は実行 uid 所有であること」check 等が失敗する。実効 uid で報告して回避する。
  private int eff_uid() { return ( process.euid < 0 ) ? process.uid : process.euid; }
  private int eff_gid() { return ( process.egid < 0 ) ? process.gid : process.egid; }

  private void _set_file_stat64( long addr, Inode inode ) {
    mem.store64( addr,      inode.st_dev   & 0xFFFFL ); addr += 8;  // st_dev
    mem.store64( addr,      inode.st_ino   & 0xFFFFFFFFL ); addr += 8; // st_ino
    mem.store64( addr,      inode.st_nlink & 0xFFFFL ); addr += 8;  // st_nlink
    mem.store32( addr,      inode.st_mode  & 0xFFFF ); addr += 4;   // st_mode
    int _euid = eff_uid(), _egid = eff_gid();   // issue #383
    mem.store32( addr,      ( (inode.st_uid & 0xFFFF) == 0 && _euid != 0 ) ? _euid : ( inode.st_uid & 0xFFFF ) ); addr += 4;   // st_uid
    mem.store32( addr,      ( (inode.st_gid & 0xFFFF) == 0 && _egid != 0 ) ? _egid : ( inode.st_gid & 0xFFFF ) ); addr += 4;   // st_gid
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

  // statfs(path, buf) / fstatfs(fd, buf) — Linux x86-64 struct statfs (120 byte)。
  //   f_type(0) f_bsize(8) f_blocks(16) f_bfree(24) f_bavail(32) f_files(40)
  //   f_ffree(48) f_fsid(56) f_namelen(64) f_frsize(72) f_flags(80) f_spare[4](88)
  //   issue #191: apt は空き容量を statvfs(→statfs) で確認するので host fs の
  //   実値 (FileStore) を返す。
  // issue #349: /proc は procfs として扱う。systemd の proc_mounted() は
  //   path_is_fs_type("/proc", PROC_SUPER_MAGIC) = statfs の f_type が 0x9fa0 か
  //   で判定する。emulin は /proc を rootfs 上の dir に map しているため通常 fs の
  //   magic (0xEF53) を返し「/proc not mounted」となっていた。
  static final long PROC_SUPER_MAGIC = 0x9fa0L;
  static final long EXT_SUPER_MAGIC  = 0xEF53L;
  static boolean isProcPath( String name ) {
    return name != null && ( name.equals( "/proc" ) || name.startsWith( "/proc/" ) );
  }
  private long amd64_statfs( long path_addr, long buf_addr ) {
    String name = mem.loadString( path_addr );
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    if( isProcPath( name ) ) return write_statfs( buf_addr, sysinfo.get_native_path( "/" ), PROC_SUPER_MAGIC );
    if( !new Inode( name, sysinfo ).isExists( ) ) return ENOENT;
    return write_statfs( buf_addr, sysinfo.get_native_path( name ), EXT_SUPER_MAGIC );
  }
  private long amd64_fstatfs( long fd, long buf_addr ) {
    if( get_finfo( (int)fd ) == null ) return EBADF;
    String name = get_name( (int)fd );
    if( name == null || "<noname>".equals( name ) ) {
      return write_statfs( buf_addr, sysinfo.get_native_path( "/" ), EXT_SUPER_MAGIC );  // 特殊 fd は / で代用
    }
    name = sysinfo.get_full_path( process.get_curdir( ), name );
    long magic = isProcPath( name ) ? PROC_SUPER_MAGIC : EXT_SUPER_MAGIC;
    return write_statfs( buf_addr, sysinfo.get_native_path( isProcPath( name ) ? "/" : name ), magic );
  }
  private long write_statfs( long buf, String nativePath, long fType ) {
    final long BSIZE = 4096L;
    long blocks, bfree, bavail;
    try {
      java.nio.file.FileStore fs = java.nio.file.Files.getFileStore(
          java.nio.file.Paths.get( nativePath ) );
      blocks = Math.max( 1L, fs.getTotalSpace( )  / BSIZE );
      bfree  = Math.max( 1L, fs.getUsableSpace( ) / BSIZE );
      bavail = bfree;
    } catch( Exception e ) {
      blocks = 1L << 30; bfree = 1L << 29; bavail = bfree;  // fallback: 大きめ
    }
    for( int i = 0; i < 120; i += 8 ) mem.store64( buf + i, 0L );
    mem.store64( buf +  0, fType );      // f_type (issue #349: /proc は PROC_SUPER_MAGIC)
    mem.store64( buf +  8, BSIZE );     // f_bsize
    mem.store64( buf + 16, blocks );    // f_blocks
    mem.store64( buf + 24, bfree );     // f_bfree
    mem.store64( buf + 32, bavail );    // f_bavail
    mem.store64( buf + 40, 1L << 20 );  // f_files
    mem.store64( buf + 48, 1L << 19 );  // f_ffree
    mem.store64( buf + 64, 255L );      // f_namelen
    mem.store64( buf + 72, BSIZE );     // f_frsize
    return 0;
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
    final int AT_REMOVEDIR = 0x200;
    String path = mem.loadString( path_addr );
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    // issue #191: AT_REMOVEDIR は rmdir(2) 相当 → directory 専用 (non-dir は
    //   ENOTDIR)。それ以外は unlink (file/symlink 削除)。
    return ( (flags & AT_REMOVEDIR) != 0 ) ? rmdir_resolved( full ) : unlink_resolved( full );
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
      // issue #322: Windows NTFS は hard link が FS 種別 / open handle / 特殊 path
      //   等で弾かれることがある。dpkg の info file migration (link + unlink で
      //   <pkg>.list → <pkg>:<arch>.list) や git の object commit が EPERM で
      //   失敗するのを避け、CygSymlink モードでは内容 copy で代替する。link count は
      //   増えないが dpkg/git の用途 (= 別名で同内容の file が欲しい) では等価。
      if( CygSymlink.enabled() ) {
        try {
          java.nio.file.Files.copy(
            java.nio.file.Paths.get( old_native ),
            java.nio.file.Paths.get( new_native ));
          return 0;
        } catch( java.nio.file.FileAlreadyExistsException e2 ) {
          return -17;
        } catch( java.nio.file.NoSuchFileException e2 ) {
          return -2;
        } catch( Exception e2 ) {
          return -1;
        }
      }
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
      // issue #349: symlink (Cygwin magic file) も異 case の sibling と衝突するなら別名へ encode。
      //   manpages-dev の `_Exit.2.gz` -> `_exit.2.gz` (regular) が該当。
      native_link = WinCaseMap.resolveCreate( native_link );
      // issue #349: POSIX 上 linkpath が既存なら symlink(2) は EEXIST。emulin が -1 を返すと
      //   coreutils ln の `ln -s SRC DIR` (DIR/basename を作る dir-insert) や `-f` 再試行が
      //   発動せず失敗する (emacsen-common postinst の `ln -s ... .` が EPERM になる)。
      if( java.nio.file.Files.exists( java.nio.file.Paths.get( native_link ),
            java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) return -17L; // EEXIST
      return CygSymlink.write( native_link, target ) ? 0 : -1L;
    }
    // issue #322: 作成する symlink (linkpath) の最終 component は追従しない。
    String native_link = sysinfo.get_native_path_nofollow( full );
    try {
      java.nio.file.Files.createSymbolicLink(
        java.nio.file.Paths.get( native_link ),
        java.nio.file.Paths.get( target ) );
      return 0;
    } catch( java.nio.file.FileAlreadyExistsException fae ) {
      return -17L; // EEXIST (coreutils ln の dir-insert / -f 再試行用)
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
  // issue #322: AT_SYMLINK_NOFOLLOW を honor する。dpkg は extract した symlink
  //   (例 libruby3.3 の <font>.ttf.dpkg-new -> /usr/share/fonts/... の絶対 symlink、
  //   target package 未導入で broken) の時刻を AT_SYMLINK_NOFOLLOW 付き utimensat で
  //   設定する。flag を無視して follow すると broken target を解決して ENOENT になる。
  private long amd64_utimensat( int dirfd, long path_addr, long times_addr, int flags ) {
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    if( path_addr == 0 ) return 0;  // futimens 経路は touch では使われない
    String path = mem.loadString( path_addr );
    String full = resolve_at_path( dirfd, path );
    if( full == null ) return EBADF;
    boolean nofollow = ( flags & AT_SYMLINK_NOFOLLOW ) != 0;
    String native_path = nofollow ? sysinfo.get_native_path_nofollow( full )
                                   : sysinfo.get_native_path( full );
    java.nio.file.Path p = java.nio.file.Paths.get( native_path );
    java.nio.file.LinkOption[] opts = nofollow
        ? new java.nio.file.LinkOption[]{ java.nio.file.LinkOption.NOFOLLOW_LINKS }
        : new java.nio.file.LinkOption[]{};
    // 対象が存在しない (symlink でもない) なら touch 相当で作る (従来挙動)。
    //   broken symlink は NOFOLLOW で「存在する」と判定して createNewFile しない。
    boolean exists = java.nio.file.Files.exists( p, opts )
        || java.nio.file.Files.isSymbolicLink( p );
    if( !exists ) {
      try { new java.io.File( native_path ).createNewFile( ); }
      catch( Exception m ) { return -2; }
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
    try {
      java.nio.file.Files.getFileAttributeView( p,
          java.nio.file.attribute.BasicFileAttributeView.class, opts )
          .setTimes( java.nio.file.attribute.FileTime.fromMillis( mtime_ms ), null, null );
    } catch( Exception m ) {
      // symlink 自身の時刻設定が host で不可でも dpkg 用途では success 扱い
    }
    return 0;
  }

  // readlinkat(dirfd, path, buf, bufsiz) — Phase 28-3j: dirfd 対応。
  //   /proc/self/exe / /proc/self/fd/0 は special-case で exec_path を返す。
  private long amd64_readlinkat( int dirfd, long path_addr, long buf_addr, int bufsiz ) {
    String path = mem.loadString( path_addr );
    String target = null;
    if( "/proc/self/exe".equals(path) ) {
      // 絶対パス必須 (glibc の _dl_get_origin が leading '/' を assert する)
      target = (process.exec_path != null) ? process.exec_path : process.name;
    }
    // issue #41 Phase 2: /proc/self/fd/N — N の fd が pty slave なら
    //   /dev/pts/<ptn>、master なら /dev/ptmx を返す。ttyname(3) が isatty 後の
    //   path 解決で使う。★pty 判定は下の fd/0 → exec_path より「先」に行うこと。
    //   旧実装は /proc/self/fd/0 を無条件に exec_path にしていたため、sshd の
    //   対話 session で fd 0 が pty slave でも exec_path が返り、ttyname(0) が
    //   非 tty path を得て /dev で fallback scan → /dev/ptmx (master) を誤って
    //   拾っていた (interactive sshd の controlling tty 不整合の一因)。
    if( target == null && path != null && path.startsWith("/proc/self/fd/") ) {
      try {
        int n = Integer.parseInt( path.substring("/proc/self/fd/".length()) );
        Fileinfo fi = get_finfo( n );
        if( fi != null && fi.pty_slave ) {
          target = "/dev/pts/" + fi.pty_ptn;
        } else if( fi != null && fi.pty_master ) {
          target = "/dev/ptmx";
        }
      } catch( NumberFormatException e ) { /* fall through */ }
    }
    // issue #413/#416: fd0 が std console (TTY) なら /dev/tty を返す。
    //   node/libuv は TTY stdin の read setup で readlink(/proc/self/fd/0) の結果を
    //   「stdin が指す device」として再 open し、そちらを read する。ここで exec_path
    //   (= /usr/bin/node 等) を返すと node が実行ファイル自身を stdin として開いて read
    //   し、打鍵が永久に届かない (claude/Ink 等 TUI の入力不能の真因。trace で
    //   readlink(/proc/self/fd/0)→/usr/bin/node→openat→read を確認)。console なら
    //   /dev/tty を返せば open(/dev/tty) が controlling-pty 無し時に <std> console に
    //   解決し、再 open 経由でも打鍵が読める。非 console (redirect 等) は従来どおり
    //   exec_path (glibc static binary が stdin 経由で自身 path を解決する経路用)。
    if( target == null && "/proc/self/fd/0".equals(path) ) {
      target = isSTD( 0 ) ? "/dev/tty"
                          : ((process.exec_path != null) ? process.exec_path : process.name);
    }
    // issue #403 後続: /proc/self/fd/N (pty/exec 以外の通常 fd) は開いた path を返す。
    //   Bun/claude は cwd を openat(".",O_PATH) → readlink(/proc/self/fd/N) で realpath
    //   解決する。これを ENOENT にすると `Can't access working directory` で起動失敗する。
    if( target == null && path != null && path.startsWith("/proc/self/fd/") ) {
      try {
        int n = Integer.parseInt( path.substring("/proc/self/fd/".length()) );
        Fileinfo fi = get_finfo( n );
        if( fi != null ) {
          String nm = fi.get_name();   // FileOpen は native path を name に保持 (dir=opendir(native)/file=open(native))
          if( nm != null && !nm.startsWith("<") )   // <pipe>/<procmaps>/<stdin> 等の特殊 fd は除外
            target = sysinfo.get_virtual_path( nm );  // native → guest 仮想 path に変換 (claude の cwd realpath 解決用)
        }
      } catch( NumberFormatException e ) { /* fall through */ }
    }
    // issue #403 後続: /proc/self/cwd は現在の作業ディレクトリ (絶対 path) を返す。
    if( target == null && ("/proc/self/cwd".equals(path) || "/proc/thread-self/cwd".equals(path)) ) {
      target = process.get_curdir();
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
      // issue #322: readlink は symlink 自身を読む → 最終 component は追従しない。
      //   get_native_path は最終 symlink を follow するので nofollow を使う。
      String native_path = sysinfo.get_native_path_nofollow( full );
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
    Fileinfo tf = get_finfo( tgt );
    if( tf == null ) return -9L;  // EBADF
    // ★ issue #221 step 3d-2c-42: epoll は regular file / directory をサポートしない。Linux は
    //   epoll_ctl(ADD) で **EPERM** を返す。旧実装は 0 (成功) を返していたため、Go runtime が
    //   source file / directory を pollable とみなして netpoller に登録していた (Linux と挙動が
    //   食い違う)。実害は fstatat 修正 (#3d-2c-42) で消えたが、Linux semantics に合わせて EPERM。
    if( !_epollSupported( tf ) ) return -1L;  // -EPERM
    long events = mem.load32( ev_addr ) & 0xFFFFFFFFL;
    long data   = mem.load64( ev_addr + 4 );
    // issue #416: [2] は EPOLLET (edge-triggered) 用の「前回 readable だったか」状態。
    //   ADD/MOD で 0 リセット (次の readable を edge 扱い)。
    ep.epoll_interest.put( tgt, new long[]{ events, data, 0 } );
    return 0;  // ADD / MOD
  }

  // epoll が監視可能な fd か。socket / pipe(socketpair/pty) / eventfd / timerfd / epoll / std/err は
  //   epoll 可。regular file / directory は Linux で epoll_ctl が EPERM を返す。
  private boolean _epollSupported( Fileinfo f ) {
    if( f.isSOCKET() || f.isPIPE() || f.eventfd_flag || f.timerfd_flag || f.epoll_flag ) return true;
    if( f.isSTD() || f.isERR() ) return true;
    return false;
  }

  // 監視 fd の現在 ready な epoll event mask を返す (interest で要求された bit のみ)。
  // issue #413: TCP socket の受信データを能動的に検出する (poll の peek 経路と同一ロジック)。
  //   Java Socket.available() は kernel buffer を見ないため、available()==0 でも setSoTimeout(1)
  //   +1byte read で peek し、取れたら peekBuf に積んで readable とする (Fileinfo.Read が peekBuf を
  //   先に消費)。EOF / error も「読める」(read が 0/EOF を返す)。epoll の socket readiness 判定で使用。
  private boolean _socketReadablePeek( Fileinfo f ) {
    if( f.socketEof ) return true;
    if( f.peekBuf != null && f.peekLen > 0 ) return true;
    if( f.conn == null ) return false;
    try {
      if( f.conn.getInputStream().available() > 0 ) return true;
      int prev = f.conn.getSoTimeout();
      f.conn.setSoTimeout( 1 );
      try {
        byte[] one = new byte[1];
        int r = f.conn.getInputStream().read( one );
        if( r > 0 ) {
          byte[] nb = (f.peekBuf == null) ? new byte[1] : new byte[f.peekLen + 1];
          if( f.peekBuf != null ) System.arraycopy( f.peekBuf, 0, nb, 0, f.peekLen );
          nb[nb.length - 1] = one[0];
          f.peekBuf = nb; f.peekLen = nb.length;
          return true;
        } else if( r < 0 ) {
          f.socketEof = true;
          return true;  // EOF も readable
        }
      } catch ( java.net.SocketTimeoutException ste ) {
        // データ無し (socket は alive)
      } finally {
        f.conn.setSoTimeout( prev );
      }
    } catch ( java.io.IOException ignored ) {
      f.socketEof = true;
      return true;  // error → read で EOF/error を返させる
    }
    return false;
  }

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
      // issue #413: poll と同じ能動 peek で受信検出。available() だけだと Java Socket の
      //   kernel buffer 不可視で、応答到着後も EPOLLIN が立たず Bun(claude) の epoll が
      //   永久に read しない (curl=poll は peek で動くが claude=epoll が詰まる非対称)。
      if( _socketReadablePeek( f ) ) r |= EPOLLIN;
      r |= EPOLLOUT;
    } else if( f.isSOCKET() && f.sconn != null && f.subprocess != null ) {
      if( f.subprocess.Accepted() == SubProcess.ACCEPT_DONE ) r |= EPOLLIN;
    } else if( isSTD(fd) || isERR(fd) ) {
      // issue #413: stdin の epoll は poll と同じく availablePeek() (peek probe) も見る。
      //   Available()=reader.ready() は JLine 内部 buffer しか見ず pending 入力を見逃すため、
      //   node(libuv) が fd0 を epoll で stdin 待ちすると打鍵があっても EPOLLIN が立たず read に
      //   来ない。socket の能動 peek と同型。(npm/node 版 claude-code は libuv が fd0 を epoll する。)
      if( isSTD(fd) && ( sysinfo.kernel.console.Available() || sysinfo.kernel.console.availablePeek() ) ) r |= EPOLLIN;
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
        long[] v = e.getValue();
        int interest = (int)v[0];
        long data = v[1];
        int rev = epoll_revents( fd, interest );
        // ★ EPOLLONESHOT (0x40000000): 一度イベントを報告したら epoll_ctl(MOD/ADD) で再 arm する
        //   まで二度と報告しない (Linux 仕様)。旧実装は未対応で level 報告し続けたため、
        //   EPOLLONESHOT|EPOLLOUT で writable watcher を張る Bun/JSC の event loop (Claude Code の
        //   REPL) が毎回 EPOLLOUT を受け取って無限 spin し、stdin 入力処理に到達できなかった。
        //   epoll_ctl(MOD/ADD) が v[2]=0 で再 arm するので、報告後に v[2]=1 を立てるだけでよい。
        if( (interest & 0x40000000) != 0 && v.length > 2 && v[2] != 0 ) rev = 0;
        // issue #416: EPOLLET (0x80000000) は edge-triggered。readable が継続している間
        //   ずっと EPOLLIN を報告する level 動作だと、node(libuv) の EPOLLET 登録 fd
        //   (eventfd 等) を「edge 処理済なのに毎回通知」して event loop が無限 spin する。
        //   currRd && 前回も readable のときは EPOLLIN を抑制し、edge (0→1) のみ報告する。
        //   v[2] に前回 readable 状態を保持 (共有 long[] なので snapshot 越しに更新可)。
        if( (interest & 0x80000000) != 0 && v.length > 2 ) {
          Fileinfo etf = get_finfo( fd );
          if( etf != null && etf.eventfd_flag ) {
            // issue #427: eventfd の EPOLLET は「write 毎」に edge を再 arm する (Linux は
            //   eventfd_write が毎回 poll waiter を wake し ready-list へ再登録)。汎用の
            //   readable-level 判定だと、waker eventfd を drain しない tokio (codex) が 2 回目
            //   以降の write で edge を作れず epoll_pwait が永久 block する (deadlock)。
            //   eventfd_writes (単調増加の write 世代) を見て前回報告より write が増えていれば
            //   EPOLLIN を報告。node/libuv の「write 無しで再 poll」は世代不変で抑制され #416 の
            //   spin 防止も維持する。v[2] に前回報告時の世代を保持。
            long gen = etf.eventfd_writes;
            if( (rev & EPOLLIN) != 0 && gen <= v[2] ) rev &= ~EPOLLIN;
            v[2] = gen;
          } else {
            boolean currRd = (rev & EPOLLIN) != 0;
            boolean prevRd = v[2] != 0;
            if( currRd && prevRd ) rev &= ~EPOLLIN;   // edge 既報告 → 抑制
            v[2] = currRd ? 1 : 0;                    // 次回 edge 判定用に更新
          }
        }
        if( rev != 0 ) {
          mem.store32( ev_addr + (long)n*12,     rev );
          mem.store64( ev_addr + (long)n*12 + 4, data );
          if( (interest & 0x40000000) != 0 && v.length > 2 ) v[2] = 1;  // EPOLLONESHOT: 報告済みにし再 arm まで抑制
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

  // ============================ io_uring (issue #416) ============================
  //  Debian の system libuv (apt node が link) は io_uring を試み、io_uring_setup が
  //  ENOSYS だと event loop 初期化で startup 早期終了する。SQ/CQ ring + SQE を guest が
  //  mmap する anon memory に確保し、io_uring_enter で submit 済 SQE を非ブロッキング実行
  //  → CQE を書き戻す。ring layout (single-mmap): SQ header @0-20, CQ header @64-84,
  //  SQ array @128, CQ cqes @動的。
  private long amd64_io_uring_setup( long entries, long params_ptr ) {
    int n = (int)entries;
    if( n <= 0 || params_ptr == 0 ) return -22L;            // EINVAL
    // review #12: power-of-2 化の前に上限 clamp する。後段で clamp すると n が
    //   [2^30+1, 2^31-1] のとき while( sqe < n ) sqe <<= 1 が overflow して
    //   負/0 になり無限ループになる。
    if( n > 4096 ) n = 4096;
    int sqe = 1; while( sqe < n ) sqe <<= 1;                 // power of 2 (n≤4096 ゆえ overflow しない)
    int cqe = sqe * 2;
    final int SQ_ARRAY = 128;
    int CQ_CQES = (SQ_ARRAY + sqe*4 + 63) & ~63;
    int ringSize = (CQ_CQES + cqe*16 + 4095) & ~4095;
    int sqeSize  = (sqe*64 + 4095) & ~4095;
    long ringVA = mem.alloc_and_map( 0, ringSize, -1, 0 );
    if( ringVA <= 0 ) return -12L;                          // ENOMEM
    long sqeVA  = mem.alloc_and_map( 0, sqeSize,  -1, 0 );
    if( sqeVA <= 0 ) { mem.free( ringVA, ringSize ); return -12L; }  // review #8: ringVA leak 防止
    mem.store32( ringVA + 8,  sqe - 1 );   mem.store32( ringVA + 12, sqe );  // SQ mask / entries
    mem.store32( ringVA + 72, cqe - 1 );   mem.store32( ringVA + 76, cqe );  // CQ mask / entries
    Fileinfo f = new Fileinfo();
    f.io_uring_flag = true;
    f.iouRingVA = ringVA; f.iouSqeVA = sqeVA;
    f.iouSqEntries = sqe; f.iouCqEntries = cqe;
    f.iouSqArrayOff = SQ_ARRAY; f.iouCqCqesOff = CQ_CQES;
    f.iouPending = new java.util.ArrayList<long[]>();
    int fd = ((FileAccess)this).alloc_anon_fd( f );
    set_cloexec( fd, true );
    // io_uring_params: sq_entries@0 cq_entries@4 features@20、sq_off@40 cq_off@80
    mem.store32( params_ptr + 0,  sqe );
    mem.store32( params_ptr + 4,  cqe );
    mem.store32( params_ptr + 20, 1 | 2 );  // IORING_FEAT_SINGLE_MMAP | NODROP
    mem.store32( params_ptr + 40, 0 );  mem.store32( params_ptr + 44, 4 );   // sq head/tail
    mem.store32( params_ptr + 48, 8 );  mem.store32( params_ptr + 52, 12 );  // sq mask/entries
    mem.store32( params_ptr + 56, 16 ); mem.store32( params_ptr + 60, 20 );  // sq flags/dropped
    mem.store32( params_ptr + 64, SQ_ARRAY );                                // sq array
    mem.store32( params_ptr + 80, 64 ); mem.store32( params_ptr + 84, 68 );  // cq head/tail
    mem.store32( params_ptr + 88, 72 ); mem.store32( params_ptr + 92, 76 );  // cq mask/entries
    mem.store32( params_ptr + 96, 80 ); mem.store32( params_ptr + 100, CQ_CQES ); // cq overflow/cqes
    mem.store32( params_ptr + 104, 84 );                                     // cq flags
    if( System.getenv("EMULIN_DEBUG_TTY") != null )
      System.err.println("DBG_IOURING setup fd="+fd+" sqe="+sqe+" ringVA=0x"+Long.toHexString(ringVA)+" sqeVA=0x"+Long.toHexString(sqeVA));
    return fd;
  }

  private long amd64_io_uring_enter( long fd, long to_submit, long min_complete, long flags, long sig, long sigsz ) {
    Fileinfo f = get_finfo( (int)fd );
    if( f == null || !f.io_uring_flag ) return -9L;  // EBADF
    long ringVA = f.iouRingVA;
    int sqMask = f.iouSqEntries - 1, cqMask = f.iouCqEntries - 1, cqEntries = f.iouCqEntries;
    boolean dbg = System.getenv("EMULIN_DEBUG_TTY") != null;
    // review #5/#6: IORING_ENTER_EXT_ARG (flags bit3=8) なら arg=struct io_uring_getevents_arg
    //   { sigmask(8) sigmask_sz(4) pad(4) ts(8)@16 } の ts(struct __kernel_timespec) から
    //   caller の poll timeout を取得し honor する。旧実装は固定 ~1s で timer/latency を壊していた。
    long timeoutMs = -1L;  // -1 = timeout 指定なし
    if( (flags & 8) != 0 && sig != 0 ) {
      long tsPtr = mem.load64( sig + 16 );
      if( tsPtr != 0 ) timeoutMs = mem.load64( tsPtr ) * 1000L + mem.load64( tsPtr + 8 ) / 1_000_000L;
    }
    int consumed = 0;
    // review #7: 同一 ring への並行 enter を直列化 (iouPending の CME / cq_tail RMW race 防止)。
    synchronized( f ) {
      // 1. submit: review #10 — to_submit 件までだけ consume する (全 drain しない)。
      int sqHead = mem.load32( ringVA + 0 );
      int sqTail = mem.load32( ringVA + 4 );
      long sqeVA = f.iouSqeVA;
      while( sqHead != sqTail && consumed < (int)to_submit ) {
        int sqeIdx = mem.load32( ringVA + f.iouSqArrayOff + (long)(sqHead & sqMask)*4 ) & sqMask;
        long sqe = sqeVA + (long)sqeIdx*64;
        int  opcode = mem.load32( sqe + 0 ) & 0xFF;
        int  opFd   = mem.load32( sqe + 4 );
        long off    = mem.load64( sqe + 8 );
        long addr   = mem.load64( sqe + 16 );
        int  len    = mem.load32( sqe + 24 );
        long ud     = mem.load64( sqe + 32 );
        f.iouPending.add( new long[]{ opcode, opFd & 0xFFFFFFFFL, addr, len & 0xFFFFFFFFL, off, ud } );
        if( dbg ) System.err.println("DBG_IOURING submit op="+opcode+" fd="+opFd+" len="+len);
        sqHead++; consumed++;
      }
      mem.store32( ringVA + 0, sqHead );  // 消費した分だけ SQ head 前進
    }
    // 2. pending op を非ブロッキング実行 → CQE 書き戻し。GETEVENTS のとき min_complete or timeout まで待つ。
    boolean getevents = (flags & 1) != 0;  // IORING_ENTER_GETEVENTS
    long mc = min_complete;
    long maxSpins = (timeoutMs >= 0) ? Math.max( 0, timeoutMs / 5 ) : 200;  // sleep 5ms 単位。未指定は ~1s
    if( maxSpins > 4000 ) maxSpins = 4000;  // 20s 上限 (hang 防止)
    int spins = 0, completed = 0;
    while( true ) {
      synchronized( f ) {  // review #7
        java.util.Iterator<long[]> it = f.iouPending.iterator();
        while( it.hasNext() ) {
          // review #3: CQ に空きが無ければ (guest 未 reap) これ以上 complete せず reap を待つ。
          //   NODROP を申告しているので overwrite による completion 喪失を防ぐ。
          int cqHead = mem.load32( ringVA + 64 );
          int cqTail = mem.load32( ringVA + 68 );
          if( (cqTail - cqHead) >= cqEntries ) break;
          long[] op = it.next();
          long res = tryIouOp( op );
          if( res == Long.MIN_VALUE ) continue;  // 未完了 → pending 維持
          long cqe = ringVA + f.iouCqCqesOff + (long)(cqTail & cqMask)*16;
          mem.store64( cqe + 0, op[5] );      // user_data
          mem.store32( cqe + 8, (int)res );   // res
          mem.store32( cqe + 12, 0 );         // flags
          mem.store32( ringVA + 68, cqTail + 1 );
          it.remove();
          completed++;
        }
      }
      if( !getevents || completed >= mc || f.iouPending.isEmpty() ) break;
      if( spins++ >= maxSpins ) break;
      try { Thread.sleep( 5 ); }
      catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); return -4L; }  // review #11: EINTR
    }
    return consumed;  // review #10: 実際に consume した SQE 数を返す
  }

  // pending op を 1 つ非ブロッキング実行。完了で res(>=0/-errno) を返す、未完了で Long.MIN_VALUE。
  private long tryIouOp( long[] op ) {
    int opcode = (int)op[0], opFd = (int)op[1];
    long addr = op[2];
    long lenL = op[3]; if( lenL > 0x7FFFFFFFL ) lenL = 0x7FFFFFFFL;  // review #2: u32 len を (int) 化で負にしない (NegativeArraySize 回避)
    int  len  = (int)lenL;
    long off  = op[4];  // review #1: SQE の file offset (pread/pwrite semantics)
    boolean isRead  = (opcode == 22 || opcode == 1 || opcode == 27);  // READ / READV / RECV
    boolean isWrite = (opcode == 23 || opcode == 2 || opcode == 26);  // WRITE / WRITEV / SEND
    boolean vectored = (opcode == 1 || opcode == 2);
    if( opcode == 0 ) return 0;               // NOP
    if( !isRead && !isWrite ) return -22L;    // 未対応 op は EINVAL で完了
    // review #1: off≠-1 かつ seekable (regular file) のときだけ positioned I/O。
    //   socket/pipe/std は off を無視 (kernel も非 seekable では off 無視)。
    boolean positioned = (off != -1L);
    if( positioned ) {
      Fileinfo ff = get_finfo( opFd );
      if( ff == null || ff.isSOCKET() || ff.is_pipe( true ) || isSTD(opFd) ) positioned = false;
    }
    if( isRead ) {
      if( !_iouReadable( opFd ) ) return Long.MIN_VALUE;  // データ未着 → pending
      long r;
      if( positioned ) r = vectored ? amd64_preadv( opFd, addr, len, off & 0xFFFFFFFFL, off >>> 32 )
                                     : amd64_pread64( opFd, addr, len, off );
      else             r = vectored ? amd64_readv( opFd, addr, len )
                                     : amd64_read( opFd, addr, len );
      if( r == -11L ) return Long.MIN_VALUE;  // review #4: EAGAIN (amd64_read は -11L を返す) → pending
      return r;
    }
    // write
    if( positioned ) return vectored ? amd64_pwritev( opFd, addr, len, off & 0xFFFFFFFFL, off >>> 32 )
                                      : amd64_pwrite64( opFd, addr, len, off );
    return vectored ? amd64_writev( opFd, addr, len ) : amd64_write( opFd, addr, len );
  }

  // io_uring READ の非ブロッキング readiness。fd0(console)/socket/pipe は実 availability を見る。
  private boolean _iouReadable( int fd ) {
    if( isSTD(fd) ) return sysinfo.kernel.console.Available() || sysinfo.kernel.console.availablePeek();
    Fileinfo f = get_finfo( fd );
    if( f == null ) return true;  // bad fd → read が EBADF を返す
    if( f.isSOCKET() && f.conn != null ) return _socketReadablePeek( f );
    if( f.is_pipe( true ) )
      return sysinfo.kernel.pipe_available( f.pipe_no ) > 0 || !sysinfo.kernel.is_pipe_connected( f.pipe_no );
    return true;  // 通常 file は常に ready
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
    //   issue #221 step 3d-2c-4: backend 非依存に set/get_fs_base を使う。software (Cpu64) は
    //   fs_base field、native (NativeCpuBackend) は guest の MSR_FS_BASE を KVM で更新する。
    //   ★ #221 multi-vCPU: GuestThread (Thread64 / NativeCpuBackend.Worker) で worker の CPU を取る。
    //   旧 `instanceof Thread64` は native worker を取りこぼし process.cpu(=main) に誤書込 →
    //   main の fs_base 破壊 + worker vcpuFd への cross-thread KVM_SET_MSRS で worker crash した。
    Thread cur = Thread.currentThread();
    AbstractCpu cpu = (cur instanceof GuestThread g)? g.guestCpu() : process.cpu;
    if( (int)code == ARCH_SET_FS ) {
      cpu.set_fs_base( addr );
      return 0;
    }
    if( (int)code == ARCH_GET_FS ) {
      mem.store64( addr, cpu.get_fs_base() );
      return 0;
    }
    // ARCH_SET_GS / ARCH_GET_GS: ignored
    return 0;
  }

  // TTY (stdin/stdout/stderr/pipe) 用の固定 stat。
  //   既存 caller (pty / STD/ERR/PIPE fd) は legacy default の st_rdev=0x302。
  private void _set_tty_stat64( long addr ) {
    _set_tty_stat64( addr, 0x302L );
  }

  // issue #131: st_rdev を caller 指定にする overload。tmux 等は fstat の
  //   st_rdev を見て「real /dev/null か」を検証する (major:1, minor:3 = 0x103
  //   を期待) ため、character device 別に正しい rdev を返す必要がある。
  //   /dev/ptmx 経路は legacy 0x302 を保つ (ttyname(3) 用の self-consistency
  //   のみ要求し具体値は問わないため)。
  private void _set_tty_stat64( long addr, long rdev ) {
    mem.store64( addr, 0x16 );      addr += 8;  // st_dev (char dev)
    // ★ st_ino は rdev ごとに変える。固定値だと console (rdev=0x302) と /dev/null
    //   (rdev=0x103) が同一 inode になり、GNU grep の「stdout が /dev/null か」判定
    //   (S_ISCHR(stdout) かつ SAME_INODE(fstat(1), stat("/dev/null"))) が誤一致し、
    //   grep が「出力先 = /dev/null」と誤認してマッチ行の出力を抑制する
    //   (`echo aaa | grep aaa` や `grep aaa file` がコンソール出力時に 0 件に化ける)。
    //   実 Linux でも /dev/null とコンソール tty は別 inode。rdev 派生にすれば
    //   同一 char device (例: pty slave fd と /dev/pts/N path、共に rdev=0x302) は
    //   同一 inode を保ち ttyname(3) の self-consistency も維持される。
    mem.store64( addr, 0x8B0000L | ( rdev & 0xFFFFL ) ); addr += 8;  // st_ino (rdev 派生)
    mem.store64( addr, 1 );         addr += 8;  // st_nlink
    mem.store32( addr, 0x21B6 );    addr += 4;  // st_mode (S_IFCHR|0666)
    mem.store32( addr, 0 );         addr += 4;  // st_uid
    mem.store32( addr, 0 );         addr += 4;  // st_gid
    mem.store32( addr, 0 );         addr += 4;  // __pad0
    mem.store64( addr, rdev );      addr += 8;  // st_rdev (caller 指定)
    mem.store64( addr, 0 );         addr += 8;  // st_size
    mem.store64( addr, 0x1000 );    addr += 8;  // st_blksize
    mem.store64( addr, 0 );         addr += 8;  // st_blocks
    // zero out remaining 6×8 + 3×8 = 72 bytes
    for( int i = 0; i < 9; i++ ) { mem.store64( addr, 0 ); addr += 8; }
  }

  // issue #131: directory 用の固定 stat (struct stat 144 byte)。/proc/<pid>/fd の
  //   合成 dir 等で使う。emulator 用 dummy mtime は現在時刻 (epoch sec)。
  private void _set_dir_stat64( long addr ) {
    for( int i = 0; i < 144; i += 8 ) mem.store64( addr + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    mem.store64( addr +  0, 0x14 );             // st_dev
    mem.store64( addr +  8, 1L );               // st_ino (非 0)
    mem.store64( addr + 16, 2 );                // st_nlink (dir は >= 2)
    mem.store32( addr + 24, 0x41ED );           // st_mode = S_IFDIR | 0755
    mem.store32( addr + 28, 0 );                // st_uid
    mem.store32( addr + 32, 0 );                // st_gid
    mem.store64( addr + 48, 4096L );            // st_size
    mem.store64( addr + 56, 4096L );            // st_blksize
    mem.store64( addr + 64, 8L );               // st_blocks
    mem.store64( addr + 72, now );              // st_atime
    mem.store64( addr + 88, now );              // st_mtime
    mem.store64( addr +104, now );              // st_ctime
  }

  // issue #131: directory 用の statx fill。STATX_BASIC_STATS 相当。
  private void _fill_statx_dir( long a ) {
    for( int i = 0; i < 256; i += 8 ) mem.store64( a + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    mem.store32( a + 0x00, STATX_BASIC_STATS );    // stx_mask
    mem.store32( a + 0x04, 4096 );                 // stx_blksize
    mem.store32( a + 0x10, 2 );                    // stx_nlink (dir >= 2)
    mem.store16( a + 0x1C, (short)0x41ED );        // stx_mode = S_IFDIR | 0755
    mem.store64( a + 0x20, 1L );                   // stx_ino (非 0)
    mem.store64( a + 0x28, 4096L );                // stx_size
    mem.store64( a + 0x30, 8L );                   // stx_blocks
    mem.store64( a + 0x40, now );                  // atime
    mem.store64( a + 0x60, now );                  // ctime
    mem.store64( a + 0x70, now );                  // mtime
  }

  // issue #411: 合成 regular file (procfs /proc/<pid>/stat 等) の statx 充填。
  private void _fill_statx_reg( long a, long size ) {
    for( int i = 0; i < 256; i += 8 ) mem.store64( a + i, 0L );
    long now = System.currentTimeMillis() / 1000L;
    mem.store32( a + 0x00, STATX_BASIC_STATS );    // stx_mask
    mem.store32( a + 0x04, 1024 );                 // stx_blksize
    mem.store32( a + 0x10, 1 );                    // stx_nlink
    mem.store16( a + 0x1C, (short)0x8124 );        // stx_mode = S_IFREG | 0444
    mem.store64( a + 0x20, 1L );                   // stx_ino
    mem.store64( a + 0x28, size );                 // stx_size
    mem.store64( a + 0x30, 0L );                   // stx_blocks
    mem.store64( a + 0x40, now );                  // atime
    mem.store64( a + 0x60, now );                  // ctime
    mem.store64( a + 0x70, now );                  // mtime
  }

  // statx path 経路で /proc 系を処理 (dir/合成 file)。処理したら true。
  private boolean _statxProcPath( String name, long buf_addr ) {
    if( _isProcRoot( name ) || _procDirPid( name ) > 0 ) { _fill_statx_dir( buf_addr ); return true; }
    byte[] sys = _procSysFileContent( name );
    if( sys != null ) { _fill_statx_reg( buf_addr, sys.length ); return true; }
    String[] ff = new String[1];
    int pid = _procFilePid( name, ff );
    if( pid > 0 ) {
      byte[] c = _procFileContent( pid, ff[0] );
      if( c != null ) { _fill_statx_reg( buf_addr, c.length ); return true; }
    }
    return false;
  }

  // issue #131: /proc/<pid>/fd 合成 dir の getdents64 entries 生成。
  //   flist を走査して open 済 fd を "0", "1", "2", ... の名前で返す。
  //   d_type = DT_LNK (10) — 実 Linux でも /proc/<pid>/fd/N は symlink。
  //   "." / ".." は冒頭に DT_DIR で含める (POSIX 慣習)。
  //   reentrant: get_ptr/set_ptr で前回 offset を保存して途中再開する。
  private long _getdents64_proc_fd( int fd, long dirp, int count ) {
    // 全 entry 名を構築 ("." / ".." / 開いている各 fd)
    java.util.ArrayList<String> names = new java.util.ArrayList<>();
    java.util.ArrayList<Integer> types = new java.util.ArrayList<>();
    names.add( "." );  types.add( 4 );   // DT_DIR
    names.add( ".." ); types.add( 4 );
    for( int i = 0; i < flist.size(); i++ ) {
      Fileinfo f = get_finfo( i );
      if( f == null ) continue;
      names.add( Integer.toString( i ) );
      types.add( 10 );  // DT_LNK
    }
    int start = get_ptr( fd );
    long d_off = 0;
    long w_size = 0;
    long address = dirp;
    for( int idx = 0; idx < names.size(); idx++ ) {
      String d_name = names.get( idx );
      int name_bytes = d_name.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
      int memlen = 19 + name_bytes + 1;
      int reclen = (memlen + 7) & ~7;
      long old_d_off = d_off;
      d_off += reclen;
      if( count < d_off ) break;
      if( start <= old_d_off ) {
        long ino_val = (idx == 0) ? 1L : (idx == 1) ? 2L : (long)idx;
        mem.store64( address +  0, ino_val );
        mem.store64( address +  8, d_off );
        mem.store16( address + 16, (short)reclen );
        mem.store8 ( address + 18, (byte)(int)types.get( idx ) );
        mem.storeString( address + 19, d_name );
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

  // issue #131: 標準的な character device の Linux 規定 st_rdev (major:minor)。
  //   tmux daemonize 等は fstat 後に st_rdev で実 device 一致を確認する。
  //   MKDEV(major, minor) ≈ (major << 8) | minor (Linux glibc 2.34+
  //   gnu_dev_makedev、低位 8 bit minor + 12 bit major)。
  private static long _stdDeviceRdev( String name ) {
    if( name == null ) return 0x302L;
    if( "<null>".equals(name)    || "/dev/null".equals(name) )    return 0x103L;
    if( "/dev/zero".equals(name) )                                 return 0x105L;
    if( "/dev/full".equals(name) )                                 return 0x107L;
    if( "/dev/random".equals(name) )                               return 0x108L;
    if( "<urandom>".equals(name) || "/dev/urandom".equals(name) )  return 0x109L;
    if( "/dev/tty".equals(name) )                                  return 0x500L;
    return 0x302L;  // 旧 default (legacy 用途)
  }
}
