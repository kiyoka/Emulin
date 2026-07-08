// ----------------------------------------
//  Process
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;
import emulin.device.*;

public class Process extends Signal {
  Memory mem;
  Syscall syscall;
  AbstractCpu cpu;
  long ip;
  int pid;
  // issue #102: process group ID。setpgid で設定、getpgrp/getpgid が返す。
  //   -1 = 未設定 (getpgrp は pid を返す = 自分が pgrp leader)。fork で継承。
  int pgrp = -1;
  // issue #473: session ID。setsid で設定、getsid が返す。
  //   -1 = 未設定 (getsid は pid を返す = 自分がセッションリーダー)。fork で継承。
  int sid = -1;
  int gid;
  int uid;
  // issue #324: effective / saved uid・gid。-1 = 未設定 (= 実 uid/gid に追従)。
  //   real(uid/gid) + effective(euid/egid) + saved(suid/sgid) の trio を追跡し、
  //   seteuid/setresuid の POSIX 意味論 (一時的な euid 変更と permanent drop の区別) を
  //   正しく扱う。apt は download 用に euid/egid を _apt へ下げてから root に戻す
  //   (seteuid(0)=setresuid(-1,0,-1)) ので、real/saved が 0 のままなら戻せる必要がある。
  //   一方 sshd の permanently_set_uid (setresuid(u,u,u) で 3 つとも非 0) 後は戻せない (#41)。
  int euid = -1, suid = -1, egid = -1, sgid = -1;
  int umask = 0022;  // ファイル作成 mask (Linux デフォルト)
  /* exec で置き換えられる際、自スレッド終了時に file descriptor を閉じないためのフラグ。
     新プロセスが共有 syscall (= 同じ FileAccess) を引き続き使うため、ここで閉じると
     stdin/stdout/stderr が無効になる。 */
  volatile boolean exec_replacing = false;
  volatile boolean exit_flag;
  // issue #435: vfork(clone CLONE_VM|CLONE_VFORK = posix_spawn)用。子がこの latch を
  //   持つと、生成した親スレッドは子が execve / _exit するまで suspend される(vfork 意味論)。
  //   null = vfork 子ではない(通常 fork / pthread)。
  public volatile java.util.concurrent.CountDownLatch vforkLatch;
  // issue #435: vfork 子は親と Memory を共有する。run() 終了時の mem.release_buffers() を
  //   スキップするフラグ(共有メモリを解放すると親のメモリが壊れ null-buf read になる)。
  public volatile boolean shares_parent_mem = false;
  volatile int exit_code = 0;       // sys_exit / sys_exit_group に渡された終了コード (wait4 が読む)
  volatile int term_sig = 0;        // issue #113: 0=normal exit、>0=この signal で死んだ (SIGSEGV=11)。wait4 が WIFSIGNALED で返す
  // Phase 27 step 39: pthread (Thread64) 生存中の counter。
  //   sys_exit (#60) を main thread が呼んだとき、Linux 仕様では main 自身
  //   だけが死に worker は走り続ける。が emulator では process 全体を tear
  //   down すると worker が壊れた状態で segfault するので、main の sys_exit で
  //   この counter が 0 になるまで待ってから process exit する。
  public final java.util.concurrent.atomic.AtomicInteger active_thread_count =
      new java.util.concurrent.atomic.AtomicInteger( 0 );
  String name;        // argv[0] (busybox の applet 名 等)
  volatile String comm;  // issue #447: prctl(PR_SET_NAME) で設定する comm 名 (最大15文字+NUL)。未設定なら name の basename。
  String exec_path;   // 実行ファイルの path (name と異なる場合あり)
  String[] argv;      // issue #411: 完全な argv (/proc/<pid>/cmdline 用)
  String curdir;
  boolean init_process;
  long evals;
  long handler_hook;       // signalハンドラフックアドレス
  long sig_no_embed_adrs;  // 割り込み番号を書き込むアドレス
  long handler_embed_adrs; // 割り込みハンドラアドレスを書き込むアドレス

  // Phase 27 step 23: ITIMER_REAL 用の background thread。alarm(N) と
  //   setitimer(ITIMER_REAL) は同じタイマを共有 (POSIX)。new alarm/setitimer
  //   は前の pending を cancel する。
  private Thread itimerThread;
  private long itimer_interval_ms;  // 定期発火間隔 (0 なら 1 回限り)
  private long itimer_value_ms;     // issue #443: 武装した初期 duration (ms)
  private long itimer_arm_ms;       // issue #443: 武装時刻 (currentTimeMillis)、0=未武装
  private final Object itimer_lock = new Object();

  public Process( int _pid, Sysinfo _sysinfo ) {
    // オブジェクトの生成
    sysinfo      = _sysinfo;
    pid          = _pid;
    init_process = false;
    handler_hook = 0;
    sig_no_embed_adrs   = 0;
    handler_embed_adrs  = 0;
  }

  public Process( int _pid, int gid, int uid, String _curdir, String args[], String envs[], Sysinfo _sysinfo, Syscall _syscall ) {
    this( _pid, gid, uid, _curdir, null, args, envs, _sysinfo, _syscall );
  }

  /**
   * _exec_path: 実行ファイルの path。null なら args[0] を使う (従来挙動)。
   * busybox の applet 形式 (argv[0]=applet名, exec path=/bin/busybox 等) で argv[0] と
   * 異なる path を指定できる。
   */
  public Process( int _pid, int gid, int uid, String _curdir, String _exec_path, String args[], String envs[], Sysinfo _sysinfo, Syscall _syscall ) {
    String path = (_exec_path != null) ? _exec_path : args[0];
    String filename = _sysinfo.get_full_path( _curdir, path );
    sysinfo         = _sysinfo;
    pid             = _pid;
    init_process    = false;
    // issue #191 (mozc): コンストラクタは gid/uid 引数を受け取りながら instance
    //   field へ代入しておらず、process.uid/gid が常に既定 0 (root) のままだった
    //   (従来は get_default_uid()=0 だったので顕在化せず)。EMULIN_UID で非 root
    //   起動を効かせる + exec 越しに uid を伝播させるため明示代入する。引数名が
    //   field を shadow するので this. が必要。
    this.gid        = gid;
    this.uid        = uid;

    // オブジェクトの生成
    if( _syscall == null ) {
      syscall = new SyscallI386( sysinfo, this );
    }
    else {
      syscall = _syscall;
      syscall.process = this;
    }
    mem    = new Memory( sysinfo, syscall, this );

    // issue #19: shebang (#!interpreter) 対応。filename が ELF ではなく
    //   #!/bin/bash 等で始まるスクリプトなら、interpreter を実行ファイルに
    //   差し替えて argv を組み直す (Linux kernel の execve 仕様)。
    //   元 argv: [script, a1, a2, ...]
    //   新 argv: [interp, (interp_arg), script_path, a1, a2, ...]
    //   ldd / 多くの shell script wrapper がこれで動くようになる。
    String[] shebang = detect_shebang( filename );
    if( shebang != null ) {
      String interp     = shebang[0];          // 例: /bin/bash
      String interp_arg = shebang[1];          // 例: -e (無ければ null)
      java.util.ArrayList<String> na = new java.util.ArrayList<>();
      na.add( interp );
      if( interp_arg != null ) na.add( interp_arg );
      na.add( filename );                       // script の絶対パス
      for( int i = 1; i < args.length; i++ ) na.add( args[i] );
      args = na.toArray( new String[0] );
      filename = sysinfo.get_full_path( _curdir, interp );
    }

    name   = new String( args[0] );
    this.exec_path = filename;  // 絶対パス。/proc/self/exe で参照される
    this.argv = args;           // issue #411: /proc/<pid>/cmdline 用に完全 argv を保持
    curdir = new String( _curdir );
    pid    = _pid;

    // exitフラグをクリアする。
    exit_flag = false;

    // ELFバイナリの読み込みを行う
    if( ! mem.load( filename )) {
      println( " Emulin : Can't execute process (" + filename + ") " );
      exit_flag = true;
    }
    if( !exit_flag ) {
      mem.load_symbol( filename + ".nm" );

      // EI_CLASS に応じて CPU / Syscall を選択する
      ip = mem.get_entry( );
      // Phase 24 step 1b: 動的リンカが指定されていれば interp をロードして
      //   起動 rip を interp の entry に切り替える (auxv 設定は step 1c)。
      //   interp はホスト側の実 /lib64/... を直読みする。base は本体実行
      //   ファイルやスタックと衝突しない高位アドレスを選ぶ。
      if( mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64 && mem.interp_path != null ) {
        // Phase 27 step 55: host (Linux x86-64 ASLR off) の典型的な ld.so load
        //   address に揃える。host: AT_BASE = 0x7ffff7fc5000 (= mmap_base 上の
        //   ld.so 専用領域)。我々の memory_top (0x7ffff7fbf000) より上、stack
        //   (0x7ffefff00000-) と衝突しない位置。
        //   AT_BASE が host と一致すると ld.so 内の relocation 結果のアドレス
        //   や PIE binary の load_bias 計算が一致し、デバッグ比較が容易に。
        long interp_base = 0x7ffff7fc5000L;
        // PT_INTERP は ELF 内の絶対パス (例: /lib64/ld-linux-x86-64.so.2)。
        //   Linux host では偶然 host file system にも存在するので raw のまま
        //   open できていたが、Windows host では当然 / 配下に Linux ld.so は
        //   無い。sandbox の Mount を介して resolve する。
        String interp_native = sysinfo.get_native_path( mem.interp_path );
        long interp_entry = mem.load_interp( interp_native, interp_base );
        if( interp_entry != 0 ) {
          if( sysinfo.verbose( ) ) {
            println( "  [interp] override entry: 0x" + Long.toHexString( ip ) +
                     " -> 0x" + Long.toHexString( interp_entry ));
          }
          ip = interp_entry;
        }
      }
      if( mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64 ) {
        // exec 経由で既存の SyscallAmd64 を引き継いでいれば file descriptor を
        // 保持するために再利用する。それ以外 (新規 / i386 から exec 等) は新設。
        if( !(syscall instanceof SyscallAmd64) ) {
          syscall = new SyscallAmd64( sysinfo, this );
        }
        // mem.syscall は最初の Process ctor で固定されるが、ここで syscall を
        // 差し替えると flist が分離してしまい、mem.alloc_and_map が空の flist を
        // 参照して NPE/IOOBE になる (= 動的リンクで mmap with fd を呼ぶと発生)。
        mem.syscall = syscall;
        // issue #231 (Step 1/3 of #221 refactor): Cpu64 直 instantiate を
        //   CpuBackend factory 経由に。EMULIN_BACKEND=software (default) では
        //   完全に同じ Cpu64 instance を返すので挙動変更ゼロ。
        cpu     = CpuBackend.resolve().createCpu64( sysinfo, this );
        try {
          cpu.connect_devices( mem, syscall );
        } catch( NativeCpuBackend.PoolExhaustedException e ) {
          // issue #379: native pool が低位 32GB 窓ひっ迫で取れない exec プロセスは、その 1 プロセスだけ
          //   software backend に fallback する。ELF は既に mem (software Memory) に load 済みなので
          //   software Cpu64 でそのまま実行できる (EMULIN_NATIVE_POOL_MB のチューニング不要で「native の
          //   速さ + 窓溢れ時の software の確実さ」を両取り。fork child は親 state が native 側なので非対応)。
          System.err.println( "[native] pool 確保不可 → このプロセスのみ software backend で実行 (issue #379 graceful fallback)" );
          cpu = new Cpu64( sysinfo, this );
          cpu.connect_devices( mem, syscall );
        }
        // issue #221 step 3d-2: TLS / software stack / IRELATIVE / r64 ゼロクリアは
        //   全て Cpu64 (software backend) 固有の処理 ((Cpu64)cpu cast を含む)。
        //   NativeCpuBackend (KVM) では guest 状態は connect_devices/eval 内で別途
        //   セットアップするので、ここは Cpu64 のときだけ実行する。software 経路は
        //   従来と byte 一致 (instanceof で包んだだけ)。
        if( cpu instanceof Cpu64 cpu64 ) {
          // カーネルがスレッド起動前に初期 TLS を設定するのと等価な処理。
          // %fs:0x28 のスタックカナリアが有効メモリを指すように事前に設定する。
          long pre_tls = mem.alloc_and_map( 0, 4096, -1, 0 );
          if( pre_tls > 0 ) cpu64.fs_base = pre_tls;
          long sp64 = stack_data_init64( sysinfo.get_stack_bottom_64( ), args, envs );
          // カーネルが ELF ロード時に処理する IRELATIVE リロケーションを解決する。
          cpu64.set_sp( sp64 );
          resolve_irelative( cpu64 );
          // Linux カーネルはプロセス起動時に汎用レジスタをすべてゼロクリアする
          // (rsp/rip 以外)。IRELATIVE 解決中に使ったレジスタが残っていると
          // _start が rtld_fini (rdx) として誤った値を __libc_start_main に渡し、
          // glibc がランダムなアドレスを exit handler として登録してしまう。
          for( int i = 0; i < 16; i++ ) cpu64.r64[i] = 0;
          cpu64.set_sp( sp64 );
        }
        else if( cpu instanceof NativeCpuBackend ncb ) {
          // issue #221 step 3d-2c-2: native backend は guest RAM (16MB) 内に
          //   System V x86-64 初期 stack (argc/argv/envp/auxv) を構築して RSP を設定。
          //   software の stack (0x7fff...) は guest RAM 外なので別配置。glibc が読む
          //   auxv も同一ビルダーで揃う。
          ncb.setup_initial_stack( args, envs );
        }
        cpu.set_ip( ip );
      }
      else {
        // issue #231 (Step 1/3 of #221 refactor): i386 Cpu も factory 経由に。
        cpu = CpuBackend.resolve().createCpu( sysinfo, this );
        cpu.connect_devices( mem, syscall );
        cpu.set_ip( ip );
        cpu.set_sp( sysinfo.get_stack_bottom( ));
        stack_data_init( cpu, args, envs );
      }

      if( sysinfo.debug( )) {
        println( "---------- Execute Start ----------" );
      }
    }
  }

  // issue #19: shebang 検出。filename (仮想 path) を native path に解決して
  //   先頭 2 byte が "#!" なら interpreter 行を parse する。
  //   戻り値: [interpreter, interp_arg(null可)]、shebang でなければ null。
  //   Linux kernel の挙動に合わせ、interpreter 行は最初の空白で
  //   interpreter と「残り全体を 1 引数」に分割する (= 引数は最大 1 個)。
  private String[] detect_shebang( String filename ) {
    try {
      String nativePath = sysinfo.get_native_path( filename );
      java.io.RandomAccessFile in = new java.io.RandomAccessFile( nativePath, "r" );
      byte[] head = new byte[256];
      int n = in.read( head );
      in.close();
      if( n < 2 || head[0] != (byte)'#' || head[1] != (byte)'!' ) return null;
      // 改行までを 1 行として取り出す
      int eol = 2;
      while( eol < n && head[eol] != (byte)'\n' && head[eol] != 0 ) eol++;
      String line = new String( head, 2, eol - 2 ).trim();
      if( line.isEmpty() ) return null;
      // 先頭空白区切りで interpreter と (任意の) 単一引数に分ける
      int sp = -1;
      for( int i = 0; i < line.length(); i++ ) {
        char c = line.charAt(i);
        if( c == ' ' || c == '\t' ) { sp = i; break; }
      }
      if( sp < 0 ) {
        return new String[]{ line, null };
      }
      String interp = line.substring( 0, sp );
      String arg    = line.substring( sp + 1 ).trim();
      return new String[]{ interp, arg.isEmpty() ? null : arg };
    } catch ( java.io.IOException e ) {
      return null;
    }
  }

  // 自分の複製を返す
  public synchronized Process duplicate( ) {
    // オブジェクトの生成
    Process _process    = new Process( pid, sysinfo );
    _process.update_info( (Signal)this );
    _process.clear_all_pending( );   // issue #619: 子は親の pending signal を継承しない
    _process.syscall    = syscall.duplicate( _process );
    _process.mem        = mem.duplicate( _process );
    _process.cpu        = cpu.duplicate( _process );
    _process.name       = new String( name );
    _process.curdir     = new String( curdir );
    _process.ip         = ip;
    _process.gid        = gid;
    _process.uid        = uid;
    _process.euid       = euid;   // issue #324: fork は eff/saved uid・gid を継承
    _process.suid       = suid;
    _process.egid       = egid;
    _process.sgid       = sgid;
    _process.umask      = umask;   // issue #550: 子は親の umask を継承 (fork)
    // issue #102: 子は親の process group を継承 (親が未設定なら親 pid)。
    _process.pgrp       = ( pgrp >= 0 ) ? pgrp : pid;
    // issue #473: 子は親の session を継承 (親が未設定なら親 pid)。
    _process.sid         = ( sid >= 0 ) ? sid : pid;
    _process.exit_flag  = exit_flag;
    _process.cpu.connect_devices( _process.mem, _process.syscall ); // メモリ,システムコールを接続する
    return( _process );
  }

  // issue #435: vfork(clone CLONE_VM|CLONE_VFORK = posix_spawn / Rust Command::spawn)用の複製。
  //   通常 duplicate() との唯一の差は「メモリを複製せず親のを共有する」点。codex(285MB+)を
  //   全複製すると OOM / tokio ランタイム複製による self-wake storm を起こすため、vfork の
  //   本来の意味論(子はアドレス空間を親と共有し、execve/_exit まで親は suspend)を実装する。
  //   fd テーブル(syscall)は複製する — 子の dup2/close(posix_spawn の file actions)が
  //   親の fd を壊さないため。子は短命(execve まで)で親は suspend されるので共有メモリへの
  //   並走アクセスは起きない。execve 時は kernel.exec が新 Memory を生成し子が共有から離脱、
  //   親のメモリは無傷で resume される。
  public synchronized Process duplicateVfork( long child_stack ) {
    Process _process    = new Process( pid, sysinfo );
    _process.update_info( (Signal)this );
    _process.clear_all_pending( );   // issue #619: 子は親の pending signal を継承しない
    _process.syscall    = syscall.duplicate( _process );  // fd テーブルは複製
    _process.mem        = mem;                            // ★メモリは共有(複製しない = OOM/storm 回避)
    _process.shares_parent_mem = true;                    // run() 終了時に共有メモリを解放しない
    _process.name       = new String( name );
    _process.curdir     = new String( curdir );
    _process.ip         = ip;
    _process.gid        = gid;
    _process.uid        = uid;
    _process.euid       = euid;
    _process.suid       = suid;
    _process.egid       = egid;
    _process.sgid       = sgid;
    _process.umask      = umask;   // issue #550: 子は親の umask を継承 (vfork)
    _process.pgrp       = ( pgrp >= 0 ) ? pgrp : pid;
    _process.sid        = ( sid >= 0 ) ? sid : pid;
    _process.exit_flag  = exit_flag;
    // backend 別に子 cpu を作る:
    //   software (Cpu64): 新 Cpu64 を共有 mem に接続。register は Kernel.vfork が set_ax/set_ip/set_sp。
    //   native (NativeCpuBackend): 親 VM/guestMem を共有する追加 vCPU(worker 型)を「別 process」として
    //     作る(Phase 2)。guestMem を複製しない = OOM/storm 回避。register/stack は clone-ABI で設定済み。
    if( cpu instanceof NativeCpuBackend ncb ) {
      _process.cpu = ncb.duplicateVforkChild( _process, child_stack );
    } else {
      _process.cpu = cpu.duplicate( _process );
      _process.cpu.connect_devices( _process.mem, _process.syscall );
    }
    return( _process );
  }

  // issue #435: vfork 子が execve / _exit したとき、suspend 中の親スレッドを resume する。
  //   二重 countDown を防ぐため latch を null 化してから解放する。vfork 子でなければ no-op。
  public void vfork_signal_parent( ) {
    java.util.concurrent.CountDownLatch l = vforkLatch;
    if( l != null ) {
      vforkLatch = null;
      l.countDown( );
    }
  }

  // initプロセスとして設定する。
  public void set_init_process( ) {
    init_process = true;
  }

  // exit したことを知らせる。
  // Phase 23: 親プロセスに SIGCHLD を自動配信する。
  //   - init_process は除外 (init はそもそも終了しない)
  //   - exec_replacing 経由は除外 (exec は同 pid スロットに新プロセスを
  //     差し替えるだけで「子の終了」ではない)
  //   - 自分自身が ppid=自pid の場合 (init/孤児) は除外
  public void set_exit_flag( ) {
    boolean was_set = exit_flag;
    exit_flag = true;
    if( was_set || init_process || exec_replacing ) return;
    if( sysinfo == null || sysinfo.kernel == null ) return;
    ProcessInfo my_pi = sysinfo.kernel.get_pinfo( pid );
    if( my_pi == null ) return;
    int ppid = my_pi.ppid;
    if( ppid <= 0 || ppid == pid ) return;
    ProcessInfo parent_pi = sysinfo.kernel.get_pinfo( ppid );
    if( parent_pi == null || parent_pi.process == null ) return;
    if( parent_pi.process.is_exited( ) ) return;
    parent_pi.process.recv( Signal.SIGCHLD );
  }

  // exit したか？
  public boolean is_exited( ) {
    return( exit_flag );
  }

  // シグナルのレシーブ
  public boolean recv( int sig ) {
    if( sysinfo.verbose( )) {
      println( " signal recv( " + sig + " ) " );
    }
    return( super.recv( sig ));
  }

  // Phase 27 step 23: ITIMER_REAL を arm/disarm。alarm() / setitimer() 共用。
  //   initial_ms = 0 なら disarm (cancel pending)。
  //   interval_ms > 0 なら最初の発火後、interval_ms ごとに繰り返し SIGALRM を投げる。
  //   過去の pending タイマは必ず cancel する (POSIX 仕様)。
  public void set_itimer_real( long initial_ms, long interval_ms ) {
    synchronized( itimer_lock ) {
      // 既存スレッドを停止
      if( itimerThread != null && itimerThread.isAlive() ) {
        itimerThread.interrupt();
      }
      itimerThread = null;
      itimer_interval_ms = 0L;
      itimer_value_ms = 0L; itimer_arm_ms = 0L;  // issue #443: disarm
      if( initial_ms <= 0L ) return;  // disarm のみ
      itimer_interval_ms = interval_ms;
      itimer_value_ms = initial_ms; itimer_arm_ms = System.currentTimeMillis();  // issue #443: 武装状態を記録
      final int target_pid = pid;
      final long delay = initial_ms;
      final long period = interval_ms;
      itimerThread = new Thread( () -> {
        try {
          Thread.sleep( delay );
          while( !Thread.currentThread().isInterrupted() ) {
            sysinfo.kernel.kill( target_pid, Signal.SIGALRM );
            if( period <= 0L ) return;  // 1 回限り
            Thread.sleep( period );
          }
        } catch( InterruptedException ignored ) { /* 終了 */ }
      }, "emulin-itimer-" + target_pid );
      itimerThread.setDaemon( true );
      itimerThread.start();
    }
  }

  // alarm(N) — N 秒後に SIGALRM。N=0 で cancel。返り値 = 前回 alarm の残り秒数 (issue #443)。
  public long set_alarm( long sec ) {
    long prev_ms = itimer_remaining_ms();
    long prev_sec = (prev_ms + 999L) / 1000L;   // 残りを秒に切り上げ (POSIX alarm 準拠)
    set_itimer_real( sec * 1000L, 0L );
    return prev_sec;
  }

  // issue #443: ITIMER_REAL の残り時間 (ms)。未武装/発火済みは 0。
  public long itimer_remaining_ms( ) {
    synchronized( itimer_lock ) {
      if( itimer_arm_ms == 0L ) return 0L;
      long rem = itimer_value_ms - ( System.currentTimeMillis() - itimer_arm_ms );
      return rem > 0L ? rem : 0L;
    }
  }
  // issue #443: ITIMER_REAL の interval (ms)。
  public long itimer_interval_ms_get( ) {
    synchronized( itimer_lock ) { return itimer_interval_ms; }
  }

  // デバッグ情報の表示
  public void println( String str ) {
    //    Fileinfo finfo = null;
    //    System.out.println( "--- fds = " + syscall.flist.size( ));
    //    if( syscall.flist.size( ) >= 2 ) {
    //      finfo = (Fileinfo)syscall.flist.elementAt( 1 );
    //      if( finfo != null ) {
    //	System.out.println( "--- isSTD( 1 ) = " + finfo.isSTD( ));
    //      }
    //    }
    System.out.println( name + " [" + pid + "]" + " : " + str );
  }

  public void write( int data ) {
    sysinfo.kernel.write( data );
  }

  private static final boolean TRACE_EXEC = System.getenv("EMULIN_TRACE_EXEC") != null;
  // プロセスの実行
  public void run( ) {
    if( TRACE_EXEC ) {
      System.err.println("DBG_RUN pid=" + pid + " name=" + name + " exec_path=" + exec_path
        + " exit_flag=" + exit_flag + " ELFCLASS64=" + (mem != null && mem.e_ident != null
        && mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64));
    }
    // ELF64: Cpu64.eval() が fetch/decode/execute ループを自己完結で行う
    if( mem != null && mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64 ) {
      if( !init_process ) {
        try {
          // issue #548: SIGSEGV ハンドラが登録済みなら fault 後にハンドラを起動して eval を
          //   再開する (ハンドラが ucontext.rip を書き替えて継続 = wasm trap / JS crash
          //   handler)。ハンドラが無い or 再度未登録 fault なら従来どおり SIGSEGV 終了。
          //   eval のホットループには try/catch を置かず (C2 最適化阻害回避)、ここで囲む。
          while( true ) {
            try {
              cpu.eval( );
              break;                                    // 正常終了
            } catch( Memory.SegfaultException se2 ) {
              if( cpu instanceof Cpu64 c64 && c64.deliverSegvToHandler( se2.faultAddr, se2.siCode ) ) {
                continue;                               // ハンドラ起動済 → eval 再開
              }
              throw se2;                                // ハンドラ無し → 下の catch で SIGSEGV 終了
            }
          }
        } catch( Memory.SegfaultException se ) {
          // issue #113: segfault → この process だけ SIGSEGV 終了 (JVM 全体は
          //   落とさない)。term_sig は Memory.raiseSegv で既に SIGSEGV に set 済。
          //   set_exit_flag で親へ SIGCHLD + exit_flag → 親は wait4 で WTERMSIG=11
          //   を受け取り継続。
          set_exit_flag( );
          // main process (親=init、ppid<=1) の segfault は JVM 終了コードに反映
          //   (128+SIGSEGV=139、real Linux の signal-kill 準拠)。fork 子の segfault
          //   は last_exit_code を触らない (親が wait4 で読むのが正しい)。
          { ProcessInfo mp = sysinfo.kernel.get_pinfo( pid );
            if( mp != null && mp.ppid <= 1 ) sysinfo.kernel.last_exit_code = 128 + Signal.SIGSEGV; }
        } finally {
          // Phase 31: process exit 時に Memory の byte[] を明示的に解放する。
          // 自然 exit / exec 差し替え / segfault の全経路で発火させ、fork+exec
          // 連鎖の OOM を防ぐ。
          if( !exec_replacing ) syscall.all_file_close( );
          // issue #435: vfork 子は親と Memory を共有するので解放しない(親のメモリが壊れる)。
          if( mem != null && !shares_parent_mem ) mem.release_buffers( );
        }
      }
      return;
    }

    int len = 0;
    byte buf[] = new byte[15];
    int i, j;
    int fd;
    if( init_process ) { // init プロセス
      while( true ) {
	// Phase 22 step 3c: 端末側 (JLine 等) で Ctrl-C を受けたら SIGINT を配信
	sysinfo.kernel.console.check_and_send_int( sysinfo );
	// Phase 22 step 3d: 端末リサイズを SIGWINCH として配信
	sysinfo.kernel.console.check_and_send_winch( sysinfo );
	// Phase 27 step 30: 旧実装は Thread.yield() で busy spin して CPU 1 core
	//   pegged。jstack で init thread が 100% CPU 食って worker thread と
	//   競合 → curl HTTPS 等で深刻な遅延の元。50ms sleep に変更 (端末
	//   レスポンスは目視で問題なし、Ctrl-C / SIGWINCH の検知も維持)。
	try { Thread.sleep( 50L ); }
	catch( InterruptedException m ) { }
      }
    }
    else {               // それ以外のプロセス
      int sig;
      long func_adrs;
      // CPUの実行サイクルに入る
      try {
      while( !exit_flag ) {
	if( exit_flag ) {
	  if( sysinfo.verbose( )) {
	    println( "Process [ " + name + " ]  exited. " );
	  }
	}

	// Phase 22 step 3c: Native / JLine 共通の Ctrl-C 取り込み (Std は no-op)
	sysinfo.kernel.console.check_and_send_int( sysinfo );

	// シグナルのチェック
	if( cpu.is_interrupt_done( )) {
	    sig = psig( );
	    if( -1 != sig ) {
		boolean done = false;
		func_adrs = get_func_adrs( sig );
		signal_cancel( sig );

		if( sysinfo.verbose( )) {
		    println( " got signal (" + get_signame( sig ) + ")  adrs=" + Util.hexstr( func_adrs, 8 ));
		}
		if(( Siginfo.SIG_IGN == func_adrs ) && !done) {
		    // Do Notiong...
		    done = true;
		}
		if(( Siginfo.SIG_DFL == func_adrs ) && !done) {
		    // デフォルト関数を実行する。
		    int action_type = get_action_type( sig );
		    if( SIGACTION_EXIT == action_type ) {
			// issue #411: default action で terminate する signal (SIGTERM/SIGKILL/
			//   SIGSEGV 等) は死因 signal を term_sig に記録し、wait4 が WIFSIGNALED(sig)
			//   を返せるようにする (kill <pid> で死んだ子の status を shell が正しく解釈)。
			term_sig = sig;
			syscall.sys_exit( 0, 0, 0, 0, 0 );
		    }
		    if( SIGACTION_PAUSE == action_type ) {
			
		    }
		    if( SIGACTION_CONT == action_type ) {
			
		    }
		    done = true;
		}
		if( !done ) {
		    // func_adrs で指し示す関数を実行する。
		    mem.store32( sig_no_embed_adrs, sig );
		    mem.store32( handler_embed_adrs, (int)func_adrs );
		    cpu.set_signal_handler( ip, handler_hook );
		    ip = cpu.get_ip( );
		    done = true;
		}
	    }
	}
	

	// ------------- debug start trigger ------------
	//	if( evals( ) > 2710000L ) {
	//	  sysinfo.verbose_set( 2 );
	//	  sysinfo.debug_on( );
	//	}
	// ----------------------------------------------

	if( !cpu.cache_check( ip )) {
	  cpu.fetch( ip, buf );                  // フェッチ
	  len = cpu.decode( ip, buf, false );    // デコード
	}
	else {
	  len = cpu.decode( ip, buf, true );     // デコード
	}


	if( sysinfo.debug( ) ||
	     (((sysinfo.verbose_level( ) > 1) &&
	       (( cpu.get_inst_id( ) == Instruction.CALL ) ||
		( cpu.get_inst_id( ) == Instruction.RETN ) ||
		( cpu.get_inst_id( ) == Instruction.RETF ))))) {
	  String str = "@" + Util.hexstr( ip, 8 ) + ": ";
	  for( j = 0 ; j < 6 ; j++ ) {
	    if( j < len ) {
	      str += " " + Util.hexstr( 0xFF & (int)buf[j], 2 );
	    }
	    else {
	      str += "   ";
	    }
	  }
	  println( str + " | " + cpu.disasm_str( ip + len ));
	}
	
	cpu.eval( );                // 実行
	if( sysinfo.debug( ) ) {
	  println( ">> " + cpu.reg_str( ));
	  println( ">> " + cpu.ip_str( ) + cpu.flag_str( ));
	  println( "" );
	}
	ip = cpu.get_ip( );         // 次のフェッチアドレスの取得
	Thread.yield( );
      }
      } catch( Memory.SegfaultException se ) {
	// issue #113: i386 process の segfault も SIGSEGV 終了 (親へ SIGCHLD)。
	//   term_sig は Memory.raiseSegv で既に set 済。
	set_exit_flag( );
	{ ProcessInfo mp = sysinfo.kernel.get_pinfo( pid );
	  if( mp != null && mp.ppid <= 1 ) sysinfo.kernel.last_exit_code = 128 + Signal.SIGSEGV; }
      }
    }
    // exec が失敗して cpu が初期化されない経路もあるので null-guard
    if( cpu != null ) cpu.cache_expire( );
    if( syscall != null ) syscall.all_file_close( );
  }

  // スタックの内容を初期化する ( Linux Kernel と等価な初期値を設定する )
  void stack_data_init( AbstractCpu cpu, String args[], String envs[] ) {
    // SVR4/i386 ABI の初期化を行う ( 参照 : glibc-2.0.6/sysdeps/i386/elf/start.S )
    // 訳
    // %edx   'atexit' 関数へのポインタが入っている。 動的リンカがどのように ....
    //
    // %esp   スタックは, 引数と環境変数を含む
    /*
   		0(%esp)			argc
		4(%esp)			argv[0]
		...
		(4*argc)(%esp)		NULL
		(4*(argc+1))(%esp)	envp[0]
		...
					NULL
    */

    // 以下原文
    /*
      This is the canonical entry point, usually the first thing in the text
      segment.  The SVR4/i386 ABI (pages 3-31, 3-32) says that when the entry
      point runs, most registers' values are unspecified, except for:

   %edx		Contains a function pointer to be registered with `atexit'.
   		This is how the dynamic linker arranges to have DT_FINI
		functions called for shared libraries that have been loaded
		before this code runs.

   %esp		The stack contains the arguments and environment:
   		0(%esp)			argc
		4(%esp)			argv[0]
		...
		(4*argc)(%esp)		NULL
		(4*(argc+1))(%esp)	envp[0]
		...
					NULL
    */
    {
      int i, j;
      long envp[]    = new long[256];
      long argp[]    = new long[args.length];

      // スタックの底の目印
      cpu.pushString( "--- bottom ---" );

      // 割り込みハンドラのフックを埋め込む
      cpu.push32(
		 0xC3 << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x90 );
      cpu.push32(
		 0x58 << 24 |
		 0x59 << 16 |
		 0x5A << 8 |
		 0x5B );
      cpu.push32(
		 0x90 << 24 |
		 0x5D << 16 |
		 0x5E << 8 |
		 0x5F );
      cpu.push32(
		 0x9D << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x90 );          // POP

      cpu.push32(
		 0x90 << 24 |
		 0x58 << 16 |     // dummy pop
		 0xD0 << 8 |
		 0xFF );          // call *%eax

      cpu.push32( 0xbbbbbbbb );
      handler_embed_adrs = cpu.get_sp( );
      
      cpu.push32(
		 0xB8 << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x90 );          // mov #0xxxxxxxxx,%eax

      cpu.push32(
		 0x90 << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x50 );          // PUSH %eax
      cpu.push32( 0xaaaaaaaa );
      sig_no_embed_adrs = cpu.get_sp( );
      
      cpu.push32(
		 0xB8 << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x90 );          // mov #0xxxxxxxxx,%eax

      cpu.push32(
		 0x9C << 24 |
		 0x90 << 16 |
		 0x90 << 8 |
		 0x90 );
      cpu.push32(
		 0x57 << 24 |
		 0x56 << 16 |
		 0x55 << 8 |
		 0x90 );
      cpu.push32(
		 0x53 << 24 |
		 0x52 << 16 |
		 0x51 << 8 |
		 0x50 );          // PUSH

      handler_hook = cpu.get_sp( );

      for( i = args.length-1 ; i >= 0 ; i-- ) {
	argp[i] = cpu.pushString( args[i] );  // argv[1]
      }

      for( j = 0 ; j < envs.length ; j++ ) {
	envp[j] = cpu.pushString( envs[j] );
      }

      // env
      cpu.push32( 0 );  // NULL
      for( i = j-1 ; i >= 0 ; i-- ) {
	cpu.push32( envp[i] );  // envp[i]
      }

      // argv
      cpu.push32( 0 );  // NULL
      for( i = args.length-1 ; i >= 0 ; i-- ) {
	cpu.push32( argp[i] );  // argv[i]
      }
      // argc
      cpu.push32( args.length );  // argc = 2

      if( sysinfo.debug( )) {
	println( "  Stack init value :" );
	cpu.mem.dump( (cpu.get_sp( ) / 16)*16-16, (int)(-cpu.get_sp( ) + 16) );
      }
    }
  }

  // ELF64 用スタック初期化 (x86-64 System V ABI: 8 バイトポインタ)
  // argc, argv[], NULL, envp[], NULL, AT_NULL (auxv 終端) を積む
  long stack_data_init64( long sp64, String args[], String envs[] ) {
    long sp = buildInitialStack64( mem, sp64, args, envs, mem );
    if( sysinfo.debug( )) {
      println( "  Stack64 init: sp64=0x" + Long.toHexString( sp ) + " argc=" + args.length );
    }
    return sp;
  }

  // issue #221 step 3d-2c-2: software (Memory) と native (NativeMemoryBackend) で共有する
  //   System V x86-64 初期 stack ビルダー。stack バイトは `mem` (MemoryBackend) に書き、
  //   ELF メタデータ (segments/e_phoff/e_phnum/interp_base/e_entry) は `elf` (Memory) から
  //   読む。software は両方に同じ Memory を渡すので従来と byte 一致。native は guest RAM
  //   (NativeMemoryBackend) を `mem`、software Memory を `elf` に渡す (guest 仮想=物理 identity
  //   なので stack pointer 値はそのまま guest が読める)。
  static long buildInitialStack64( MemoryBackend mem, long sp64, String args[], String envs[], Memory elf ) {
    int i, j;
    long[] envp = new long[256];
    long[] argp = new long[args.length];

    // SSE 文字列処理は 16 バイト単位で読むことがあるので、stack_bottom の
    // すぐ手前に文字列を置くと終端を越えて読み出されて segfault する。
    // 64 バイト分パディングしてから書き込みを開始する。
    sp64 -= 64;

    // 文字列を降順にスタックへ書き込む
    for( i = args.length - 1 ; i >= 0 ; i-- ) {
      byte[] b = (args[i] + "\0").getBytes();
      sp64 -= b.length;
      for( int k = 0 ; k < b.length ; k++ ) {
        mem.store8( sp64 + k, b[k] );
      }
      argp[i] = sp64;
    }
    for( j = 0 ; j < envs.length ; j++ ) {
      byte[] b = (envs[j] + "\0").getBytes();
      sp64 -= b.length;
      for( int k = 0 ; k < b.length ; k++ ) {
        mem.store8( sp64 + k, b[k] );
      }
      envp[j] = sp64;
    }

    // 16 バイトアライメント
    sp64 = sp64 & ~0xFL;

    // AT_RANDOM 用に 16 バイトのゼロ領域を確保 (カーネル提供の乱数バッファ相当)
    sp64 -= 16;
    long at_random_ptr = sp64;
    // Phase 27 step 55: AT_PLATFORM 用に "x86_64\0" を確保
    byte[] platBytes = "x86_64\0".getBytes();
    sp64 -= platBytes.length;
    long at_platform_ptr = sp64;
    for( int k = 0; k < platBytes.length; k++ ) mem.store8( sp64 + k, platBytes[k] );
    sp64 = sp64 & ~0xFL;  // 再アライメント

    // ★ System V ABI: _start での RSP は 16-byte align (argc を指す)。ここから下に積む
    //   auxv/envp/argv/argc の総 8-byte word 数が奇数だと最終 RSP が 8-mod-16 になり、
    //   ld.so の `movaps …,-0x80(%rbp)` 等が #GP (real CPU/native)。software は SSE を緩く
    //   扱うので顕在化しないが ABI 違反。総 word 数の parity は (envc + argc) で決まる
    //   (固定 auxv は偶数 word、interp 条件分も偶数 word なので残りが効く)。総数が奇数
    //   = (envs.length + args.length) が偶数 のとき 8 byte pad して最終 RSP を 16-align する。
    if( ((envs.length + args.length) & 1) == 0 ) sp64 -= 8;

    // ELF プログラムヘッダのベースアドレスを求める (p_offset==0 のセグメントがELFヘッダを含む)
    long elf_base = 0;
    for( int k = 0; k < elf.segments; k++ ) {
      if( elf.segment[k].p_offset == 0 ) { elf_base = elf.segment[k].p_vaddr; break; }
    }
    long at_phdr  = elf_base + elf.e_phoff;
    long at_phnum = elf.e_phnum & 0xFFFFL;

    // auxv (AT_NULL が最後 = 高アドレス、先にプッシュ)
    sp64 -= 8; mem.store64( sp64, 0L );            // AT_NULL value
    sp64 -= 8; mem.store64( sp64, 0L );            // AT_NULL type

    // Phase 27 step 55: host (Linux カーネル) と同じ auxv エントリを揃える。
    //   旧は AT_PHDR/AT_PHNUM/AT_PHENT/AT_PAGESZ/AT_RANDOM/AT_BASE/AT_ENTRY/AT_EXECFN
    //   の 8 種類しか push していなかった。host (LD_SHOW_AUXV=1 で確認) は更に
    //   AT_HWCAP/AT_HWCAP2/AT_PLATFORM/AT_CLKTCK/AT_FLAGS/AT_UID/AT_EUID/AT_GID/
    //   AT_EGID/AT_SECURE/AT_MINSIGSTKSZ を持つ。これらが欠けていると glibc の
    //   ld.so / libc init が code path を変えて mmap call の size や順番が
    //   host と違う結果になる。AT_SYSINFO_EHDR (vDSO) は実装してないので未追加。
    sp64 -= 8; mem.store64( sp64, at_platform_ptr ); // AT_PLATFORM value
    sp64 -= 8; mem.store64( sp64, 15L );             // AT_PLATFORM type
    sp64 -= 8; mem.store64( sp64, 0x2L );            // AT_HWCAP2 value (typical)
    sp64 -= 8; mem.store64( sp64, 26L );             // AT_HWCAP2 type
    sp64 -= 8; mem.store64( sp64, 0L );              // AT_SECURE value
    sp64 -= 8; mem.store64( sp64, 23L );             // AT_SECURE type
    sp64 -= 8; mem.store64( sp64, 1000L );           // AT_EGID value
    sp64 -= 8; mem.store64( sp64, 14L );             // AT_EGID type
    sp64 -= 8; mem.store64( sp64, 1000L );           // AT_GID value
    sp64 -= 8; mem.store64( sp64, 13L );             // AT_GID type
    sp64 -= 8; mem.store64( sp64, 1000L );           // AT_EUID value
    sp64 -= 8; mem.store64( sp64, 12L );             // AT_EUID type
    sp64 -= 8; mem.store64( sp64, 1000L );           // AT_UID value
    sp64 -= 8; mem.store64( sp64, 11L );             // AT_UID type
    sp64 -= 8; mem.store64( sp64, 0L );              // AT_FLAGS value
    sp64 -= 8; mem.store64( sp64, 8L );              // AT_FLAGS type
    sp64 -= 8; mem.store64( sp64, 100L );            // AT_CLKTCK value
    sp64 -= 8; mem.store64( sp64, 17L );             // AT_CLKTCK type
    sp64 -= 8; mem.store64( sp64, 0x1f8bfbffL );     // AT_HWCAP value (host と同じ)
    sp64 -= 8; mem.store64( sp64, 16L );             // AT_HWCAP type
    sp64 -= 8; mem.store64( sp64, 3632L );           // AT_MINSIGSTKSZ value
    sp64 -= 8; mem.store64( sp64, 51L );             // AT_MINSIGSTKSZ type

    // Phase 24 step 1c: 動的リンク用 auxv エントリ。interp が load 済みの
    //   ときだけ追加する (静的バイナリ時は不要 / 互換のため)。
    //   AT_EXECFN (31) — 実行ファイルパス文字列ポインタ (argp[0] と同じ)
    //   AT_ENTRY  (9)  — 本体実行ファイルの entry (interp の entry ではない)
    //   AT_BASE   (7)  — 動的リンカ自体の load base
    if( elf.interp_base != 0 ) {
      long execfn = ( argp.length > 0 ) ? argp[0] : 0L;
      sp64 -= 8; mem.store64( sp64, execfn );        // AT_EXECFN value
      sp64 -= 8; mem.store64( sp64, 31L );           // AT_EXECFN type
      sp64 -= 8; mem.store64( sp64, elf.e_entry );   // AT_ENTRY value
      sp64 -= 8; mem.store64( sp64, 9L );            // AT_ENTRY type
      sp64 -= 8; mem.store64( sp64, elf.interp_base );// AT_BASE value
      sp64 -= 8; mem.store64( sp64, 7L );            // AT_BASE type
    }

    // AT_RANDOM (25) — _dl_random のためのランダムバッファポインタ
    sp64 -= 8; mem.store64( sp64, at_random_ptr ); // AT_RANDOM value
    sp64 -= 8; mem.store64( sp64, 25L );           // AT_RANDOM type

    // AT_PAGESZ (6), AT_PHNUM (5), AT_PHENT (4), AT_PHDR (3)
    // glibc の _dl_aux_init が _dl_main_map.l_phdr/l_phnum に使用する。
    // 動的リンク時は AT_PHDR/AT_PHNUM は本体実行ファイルのものを指す
    // (interp 自身の PHDR ではない)。elf_base 計算は最初に見つかる
    // p_offset==0 の segment を取るので本体側が一致する。
    sp64 -= 8; mem.store64( sp64, 4096L );         // AT_PAGESZ value
    sp64 -= 8; mem.store64( sp64, 6L );            // AT_PAGESZ type
    sp64 -= 8; mem.store64( sp64, at_phnum );      // AT_PHNUM value
    sp64 -= 8; mem.store64( sp64, 5L );            // AT_PHNUM type
    sp64 -= 8; mem.store64( sp64, 56L );           // AT_PHENT value (sizeof Elf64_Phdr)
    sp64 -= 8; mem.store64( sp64, 4L );            // AT_PHENT type
    sp64 -= 8; mem.store64( sp64, at_phdr );       // AT_PHDR value
    sp64 -= 8; mem.store64( sp64, 3L );            // AT_PHDR type

    // envp[] (NULL 終端)
    sp64 -= 8; mem.store64( sp64, 0L );
    for( i = j - 1 ; i >= 0 ; i-- ) {
      sp64 -= 8; mem.store64( sp64, envp[i] );
    }

    // argv[] (NULL 終端)
    sp64 -= 8; mem.store64( sp64, 0L );
    for( i = args.length - 1 ; i >= 0 ; i-- ) {
      sp64 -= 8; mem.store64( sp64, argp[i] );
    }

    // argc
    sp64 -= 8; mem.store64( sp64, (long)args.length );

    return sp64;
  }

  // ELF64 IRELATIVE リロケーションの解決
  // Linux カーネルは ELF ロード時に R_X86_64_IRELATIVE (type=37) を処理する:
  // GOT エントリ (r_offset) ← resolver(r_addend) の戻り値 (RAX) に書き換える。
  private void resolve_irelative( Cpu64 cpu64 ) {
    final int SHT_RELA        = 4;
    final int R_X86_64_IRELATIVE = 37;

    // trampoline ページ: RET (0xC3) を 1 バイト置く
    long trampoline = mem.alloc_and_map( 0, 4096, -1, 0 );
    if( trampoline <= 0 ) return;
    mem.store8( trampoline, 0xC3 );

    // .rela.plt セクションを探す (SHT_RELA)
    // Phase 26: PIE (ET_DYN) の場合 sec.sh_addr / r_offset / r_addend は
    // load_bias を加算する。 ET_EXEC のときは load_bias=0 で従来通り。
    long bias = mem.load_bias;
    for( int s = 0; s < mem.sections; s++ ) {
      Section sec = mem.section[s];
      if( sec.sh_type != SHT_RELA || sec.sh_size == 0 || sec.sh_addr == 0 ) continue;

      long sec_addr = sec.sh_addr + bias;
      long n = sec.sh_size / 24;  // Elf64_Rela は 24 バイト
      for( long i = 0; i < n; i++ ) {
        long ea      = sec_addr + i * 24;
        long r_offset = mem.load64( ea );
        long r_info   = mem.load64( ea + 8 );
        long r_addend = mem.load64( ea + 16 );
        int  r_type   = (int)(r_info & 0xFFFFFFFFL);
        if( r_type == R_X86_64_IRELATIVE ) {
          long result = cpu64.call_resolver( r_addend + bias, trampoline );
          if( is_exited() ) return;
          mem.store64( r_offset + bias, result );
        }
      }
    }
  }

  // カレントディレクトリの設定
  public void set_curdir( String _virtual_path ) {
    if( sysinfo.verbose( )) {
      println( " set_curdir( " + _virtual_path + " )" );
    }
    curdir = _virtual_path;
    if( sysinfo.verbose( )) {
      println( "   curdir = " + curdir );
    }
  }


  // カレントディレクトリを返す
  public String get_curdir( ) {
    if( sysinfo.verbose( )) {
      println( " " + curdir + " = get_curdir( )" );
    }
    return( curdir );
  }

  // ダンプ表示
  void dump( int address, int size ) {
    int i, j;
 
    if( sysinfo.debug( )) {
      println( "Entry : " + Integer.toString( address, 16 ));
    }
    
    for( j = 0 ; j < size ; j++ ) {
      mem.dump( address + j*16, 16 );
    }
  }

  // 逆アセンブル表示
  void disassemble( int address, int size ) {
    int i, j, len;
    byte buf[] = new byte[16];
    String str;

    println( "Entry : " + Integer.toString( address, 16 ));

    for( i = 0 ; i < size ; i++ ) {
      mem.fetch( address, buf );
      len = cpu.decode( address, buf, false );
      str = " " + Util.hexstr( address, 8 ) + ": ";
      for( j = 0 ; j < 8 ; j++ ) {
	if( j < len ) {
	  	str += " " + Util.hexstr( 0xFF & (int)buf[j], 2 );
	}
	else {
	  	str += "   ";
	}
      }
      println( str + "  | " + cpu.disasm_str( address + len ));
      address += len;
    }
  }

  void inc_evals( )        { evals++; };
  long evals( )            { return( evals ); }
  // プロセス番号を返す
  int  get_pid( )          { return( pid ); }
  void set_pid( int _pid ) { pid = _pid;    }
}
