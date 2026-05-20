// ----------------------------------------
//  Emulin Kernel
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;
import emulin.device.*;

public class Kernel extends PipeManager {
  int cur_pid;
  int last_exit_code; // 最後に sys_exit に渡された終了コード
  boolean exec_request; // execのリクエストがあるかどうか
  String  exec_args[]; // execリクエスト用
  String  exec_envs[]; // execリクエスト用
  int exec_pid;  // execリクエスト用
  // issue #41 Phase 2: pty (/dev/ptmx + /dev/pts/N) 管理
  public final PtyManager pty = new PtyManager();

  public Kernel( Sysinfo _sysinfo ) {
    ProcessInfo pinfo = new ProcessInfo( );
    // カーネルの初期化
    exec_request = false;
    sysinfo = _sysinfo;
    sysinfo.kernel = this;
    console = new emulin.device.Console( sysinfo );

    // プロセステーブルの初期化
    ptable = new Vector( );
  }

  // カーネルのブート
  public void boot( String args[], String _native_curdir ) {
    java.util.ArrayList<String> envList = new java.util.ArrayList<>();
    Process process;
    ProcessInfo pinfo;
    cur_pid = 1;

    // initプロセスの起動
    pinfo = new ProcessInfo( );
    pinfo.process = new Process( cur_pid, sysinfo );
    pinfo.process.set_init_process( );
    pinfo.ppid = 1;
    pinfo.process.setPriority( Thread.MIN_PRIORITY );
    pinfo.process.start( );
    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );
    cur_pid++;

    // 環境変数の初期化 (基本セット)
    envList.add( "HOSTTYPE=i386" );
    envList.add( "PATH=/usr/local/bin:/bin:/usr/bin:." );
    envList.add( "SHELL=/bin/sh" );
    envList.add( "OSTYPE=Linux" );
    envList.add( "SHLVL=0" );
    // sandbox は root user (uid=0) で動くので home は /root 固定。
    //   bash の `cd` (引数なし) / ssh の ~/.ssh / git の ~/.gitconfig /
    //   vim の ~/.vimrc 等が解決する。HOME を host から passthrough すると
    //   Windows host の HOME=C:\Users\... が漏れて guest で不正になるため、
    //   passthrough せず基本セットで /root を与える (EMU_HOME で override 可)。
    envList.add( "HOME=/root" );
    envList.add( "USER=root" );
    envList.add( "LOGNAME=root" );
    // LESSCHARSET は passthrough にあれば host から、なければ utf-8。
    if( System.getenv( "LESSCHARSET" ) == null ) {
        envList.add( "LESSCHARSET=utf-8" );  // legacy japanese-sjis から utf-8 に
    }
    envList.add( "LD_LIBRARY_PATH=/usr/local/lib" );
    envList.add( "TERMCAP=/etc/termcap" );
    // TERM は passthrough にあれば host から、なければ vt100 (普遍的 fallback)。
    if( System.getenv( "TERM" ) == null ) {
        envList.add( "TERM=vt100" );
    }

    // 一部の env var はホストから引き継ぎ。実機 OpenSSL や Python が
    //   挙動制御に使う変数を許可する。完全に全部素通しすると
    //   再現性が損なわれるので、明示的に列挙したものだけ。
    String[] passthrough = {
      "OPENSSL_ia32cap", "OPENSSL_CONF",
      "PYTHONHASHSEED", "PYTHONPATH",
      "LANG", "LC_ALL", "TZ",
      "LD_DEBUG", "LD_DEBUG_OUTPUT",
      "LESSCHARSET", "LESS",  // less の設定 (LESSCHARSET=utf-8 デフォルト)
      "TERM",                  // terminfo lookup 用
    };
    for( String name : passthrough ) {
      String v = System.getenv( name );
      if( v != null ) envList.add( name + "=" + v );
    }
    // EMU_<NAME> prefix のものを <NAME> に変換して emulated process に渡す。
    //   ホスト JVM の挙動を変えずに emulated 側だけ env を制御したい場合に使う。
    //   例: EMU_LD_PRELOAD=/lib/foo.so → emulated process は LD_PRELOAD=/lib/foo.so を受け取る。
    java.util.Map<String,String> env = System.getenv();
    for( java.util.Map.Entry<String,String> e : env.entrySet() ) {
      String k = e.getKey();
      if( k.startsWith("EMU_") ) {
        envList.add( k.substring(4) + "=" + e.getValue() );
      }
    }

    String envs[] = envList.toArray( new String[0] );

    // bootプロセスの生成 (init を親とする)
    pinfo = new ProcessInfo( );
    pinfo.ppid = 1;
    pinfo.process = new Process( cur_pid, sysinfo.get_default_gid( ), sysinfo.get_default_uid( ),
				 sysinfo.get_virtual_path( _native_curdir ),
				 args, envs, sysinfo, null );
    // issue #15: fd の access mode を正しく設定する。fcntl(fd, F_GETFL) は
    //   GetModeBit(fd) を返すので、stdin=O_RDONLY / stdout・stderr=O_WRONLY に
    //   しないと、funzip 等が「stdout が O_RDONLY = 書けない」と誤判定する。
    pinfo.process.syscall.FileOpen( "<std>", "r", Syscall.O_RDONLY ); // fd 0
    pinfo.process.syscall.FileOpen( "<std>", "w", Syscall.O_WRONLY ); // fd 1
    pinfo.process.syscall.FileOpen( "<err>", "w", Syscall.O_WRONLY ); // fd 2
    // プロセスの起動
    pinfo.process.start( );
    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );
    cur_pid++;

    setPriority( Thread.MIN_PRIORITY );
  }

  // カーネルのメイン処理
  public void start( ) {
    for( ;; ) {
      if( exec_request ) {
	exec( exec_pid, exec_args, exec_envs );
	exec_request = false;
      }
      try { Thread.sleep( 1000L ); }
      catch( InterruptedException m ) { };
      Thread.yield( );

      if( sysinfo.verbose( )) {
	  println( "processes = " + processes( ) );
      }
      if( 1 >= processes( )) {
	// init プロセスを終了させる。
	ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( 0 );
	if( sysinfo.verbose( )) {
	    println( "Kernel.start( )  break" );
	}
	// issue #72: System.exit 前に console を flush + drain する。
	//   Windows native terminal では console host へのレンダリングが非同期で、
	//   write 直後に System.exit すると最後の出力が画面に出ない (ls -l 等)。
	//   flush + terminal.close では同期 drain しきれず、唯一 wall-clock 時間
	//   (sleep) が効くと実機調査で判明。native terminal のときだけ短い drain
	//   delay を入れる (Linux dumb terminal / pipe は対象外なので test 無影響)。
	//   delay は EMULIN_EXIT_DRAIN_MS で調整可 (default 200ms)。
	if( sysinfo.kernel != null && sysinfo.kernel.console != null ) {
	    sysinfo.kernel.console.flush();
	    if( sysinfo.kernel.console.is_native_tty() ) {
		int drain_ms = 200;
		String env = System.getenv( "EMULIN_EXIT_DRAIN_MS" );
		if( env != null ) { try { drain_ms = Integer.parseInt( env.trim() ); } catch( NumberFormatException e ) {} }
		if( drain_ms > 0 ) {
		    try { Thread.sleep( drain_ms ); } catch( InterruptedException e ) {}
		}
	    }
	    sysinfo.kernel.console.close();
	}
	System.exit( last_exit_code );
      }
    }
  }

  // exec( )処理
  public synchronized void exec( int _pid, String _args[], String _envs[] ) {
    exec( _pid, null, _args, _envs );
  }

  /**
   * exec with explicit executable path (different from argv[0]).
   * busybox 等の applet 形式で argv[0] が applet 名で path とは異なる場合に使う。
   * _exec_path == null の場合は _args[0] を path として扱う (従来挙動)。
   */
  public synchronized void exec( int _pid, String _exec_path, String _args[], String _envs[] ) {
    Syscall syscall;
    ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( _pid-1 );
    int tmp_gid       = pinfo.process.gid;
    int tmp_uid       = pinfo.process.uid;
    String tmp_curdir = pinfo.process.get_curdir( );
    /* /proc/self/exe → 親プロセスの実行ファイルパスに解決
       (busybox のパイプライン子プロセスで使われる) */
    if( _exec_path != null && "/proc/self/exe".equals( _exec_path ) ) {
      _exec_path = pinfo.process.name;
    }
    if( _exec_path == null && _args.length > 0 && "/proc/self/exe".equals( _args[0] ) ) {
      _args = _args.clone();
      _args[0] = pinfo.process.name;
    }
    /* file descriptor は exec 越しに保持する: 旧プロセスの run() で
       all_file_close() が走らないようフラグを立てる。 */
    pinfo.process.exec_replacing = true;
    pinfo.process.set_exit_flag( ); // プロセスを協調終了させる
    pinfo.process.interrupt( );
    syscall = pinfo.process.syscall; // バックアップする。
    /* Phase 27 step 39: FD_CLOEXEC が立った fd を exec 直前に閉じる。
       git の child notify pipe を閉じないと親が read(EOF) を受け取れず
       hang する。Syscall (= FileAccess) は新 Process と共有されるので
       new Process 作成前に閉じる必要がある。 */
    syscall.close_cloexec_files( );
    pinfo.process = new Process( _pid, tmp_gid, tmp_uid, tmp_curdir, _exec_path, _args, _envs, sysinfo, syscall ); // プロセスを生成
    pinfo.process.start( ); // プロセスをスタートする
  }

  // fork( )処理
  public synchronized int fork( Process _process ) {
    Process process = _process.duplicate( );
    ProcessInfo pinfo = new ProcessInfo( );
    pinfo.ppid = process.get_pid( );
    pinfo.process = process;

    if( sysinfo.verbose( )) {
      println( "fork( " + pinfo.ppid + " -> " + cur_pid + " ) " );
    }

    // プロセス情報の更新
    process.set_pid( cur_pid );
    process.cpu.set_ax( 0 );
    process.cpu.set_ip( process.cpu.get_ip( )+2 );   // 次のアドレスに進める。 fork用の int 命令からリターンしたところから
    process.ip = process.cpu.get_ip( );

    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );

    // パイプの接続処理
    process.syscall.pipe_connection( (FileAccess)_process.syscall );

    // 子プロセスのスタート
    process.start( );
    return( cur_pid++ );
  }

  // pid の子プロセスが終了したかを調べる処理
  // 戻り値 : 0  .... 該当プロセス無し
  //          1>= ... 終了したプロセスを返す
  //         -1  .... プロセスが終了していない
  public int is_child_exited( int pid ) {
    int i;
    int ret = 0;
    // プロセステーブルをなめる
    for( i = 0 ; i < ptable.size( ) ; i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null ) {
	if( pid == pinfo.ppid ) {
	  // exec_replacing 中の旧プロセスは「終了」ではなく差し替え途中なので
	  // 親の wait4 から見えてはいけない (commit acc25d8 の race 対策)。
	  if( pinfo.process.exec_replacing ) {
	    ret = -1;
	    continue;
	  }
	  if( pinfo.process.is_exited( ))   {
	    ret = i+1;
	    pinfo.exit_code = pinfo.process.exit_code;
	    pinfo.process = null;
	    return( ret );
	  }
	  else                              { ret =  -1; }
	}
      }
      if( sysinfo.verbose( )) {
	if( pinfo.process == null ) {
	  println( "pid=" + (i+1) + " ppid=" + pinfo.ppid );
	}
	else {
	  println( "pid=" + (i+1) + " ppid=" + pinfo.ppid + " exit_flag= " + pinfo.process.is_exited( ));
	}
      }
    }
    if( sysinfo.verbose( )) {
      println( ret + " = is_child_exited( " + pid + " ) " );
    }
    return( ret );
  }

  // Phase 27 step 28: pthread (Thread64) の TID 採番。pid とは別空間で
  //   Linux の TID と同様に大きめから始める (pid と衝突しないよう 10000+)。
  private int next_tid_counter = 10000;
  public synchronized int next_tid( ) {
    return ++next_tid_counter;
  }

  // 指定 pid (1-based, ptable index+1) の ProcessInfo を返す。
  // wait4 が exit_code を読むのに使う。
  public ProcessInfo get_pinfo( int pid_1based ) {
    int idx = pid_1based - 1;
    if( idx < 0 || idx >= ptable.size( ) ) return null;
    return (ProcessInfo)ptable.elementAt( idx );
  }

  // 指定 pid の Process を ptable から探す。なければ null。
  public synchronized Process find_process( int target_pid ) {
    for( int i = 0; i < ptable.size( ); i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null && pinfo.process.pid == target_pid ) {
        return pinfo.process;
      }
    }
    return null;
  }

  // プロセスがいくら残っているかを返す
  // Phase 33-16: Ctrl-C で SIGINT を送る foreground プロセスを heuristic で
  // 決める。最も新しい non-init non-exited プロセスを foreground とみなす
  // (典型的に bash → fork → exec した child = vim 等)。
  public synchronized int find_foreground_pid( ) {
    for( int i = ptable.size() - 1; i >= 1; i-- ) {  // i=0 は init なので除外
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.process == null ) continue;
      if( pinfo.process.is_exited() ) continue;
      return pinfo.process.pid;
    }
    return -1;
  }

  // issue #3-#2: Ctrl-C 用 — bash の child (= git clone 等の non-shell 子プロセス)
  // が存在する場合のみその pid を返す。bash 単独実行中は -1。
  // Phase 33-17 で kill(-1) / kill(bash) が panic 経路に入る問題を回避するため、
  // bash interactive prompt 中は SIGINT 配信を skip し、stdin 経由 byte 0x03
  // による readline abort に委ねる。
  // bash が fork+exec で起動した child (git/curl/wget 等) は network read 等で
  // blocking するため、SIGINT 配信が必須。
  public synchronized int find_foreground_child_pid( ) {
    Process foreground = null;
    for( int i = ptable.size() - 1; i >= 1; i-- ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.process == null ) continue;
      if( pinfo.process.is_exited() ) continue;
      foreground = pinfo.process;
      break;
    }
    if( foreground == null ) return -1;
    String n = foreground.name != null ? foreground.name : "";
    String ep = foreground.exec_path != null ? foreground.exec_path : "";
    // bash / sh 単独 (= interactive shell) は SIGINT を送らない (readline 自身
    // が byte 0x03 を処理する)。それ以外 (git/curl/vim 等) は SIGINT 配信。
    String basename_name = n;
    int sl = basename_name.lastIndexOf('/');
    if( sl >= 0 ) basename_name = basename_name.substring(sl + 1);
    String basename_ep = ep;
    sl = basename_ep.lastIndexOf('/');
    if( sl >= 0 ) basename_ep = basename_ep.substring(sl + 1);
    if( basename_name.equals("bash") || basename_name.equals("sh")
        || basename_name.equals("ash") || basename_name.equals("dash")
        || basename_ep.equals("bash") || basename_ep.equals("sh")
        || basename_ep.equals("ash") || basename_ep.equals("dash") ) {
      return -1;  // shell 単独 → byte 0x03 経路に委ねる
    }
    return foreground.pid;
  }

  public int processes( ) {
    int i;
    int ret = 0;
    // プロセステーブルをなめる
    for( i = 0 ; i < ptable.size( ) ; i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null ) {
	if( !pinfo.process.is_exited( )) {  ret++; }
      }
    }
    //    if( sysinfo.verbose( )) { System.out.print( "["+ret+"]" ); }
    return( ret );
  }

  // execのリクエストを行う
  public synchronized boolean exec_request( int _pid, String _args[], String _envs[] ) {
    boolean ret = false;
    if( !exec_request ) {
      exec_pid  = _pid;
      exec_args = _args;
      exec_envs = _envs;
      exec_request = true;
      ret = true;
    }
    return( ret );
  }

    // 指定デバイスファイルか？
    public synchronized boolean is_device( String _path ) {
	int index = _path.indexOf( "/dev/" );
	if( 0 == index ) { // マッチした
	    if( sysinfo.verbose( )) {
		println( "  " + _path + " is device " );
	    }
	    return( true );
	}
	if( sysinfo.verbose( )) {
	    println( "  " + _path + " is NOT device " );
	}
	return( false );
    }

    // 指定デバイスが存在するか調べる
    public synchronized String is_exist_device( String _path ) {
	if( 0 == _path.indexOf( "/dev/null" )) {
	    return( "<null>" ); // null デバイス
	}
	// Phase 30: /dev/tty を <std> (= 標準入出力 console) にルートする。
	// vim/emacs/less 等の対話 binary が /dev/tty を直接 open する経路で
	// JLine console (stdin keystroke + stdout escape sequence) と同じ
	// console を使えるようにする。
	if( 0 == _path.indexOf( "/dev/tty" )) {
	    return( "<std>" );
	}
	return( null );
    }


    // 指定 pid にシグナルを送る
    // _pid : 正の数の場合...シグナル _sig は,_pidにより識別されるプロセスに送られます。
    //        0 の場合     ...シグナル _sig は発信シグナルの属するグループのプロセスに送られます。
    //        -1の場合     ...シグナル _sig は最初のプロセス(init)を除くすべてのプロセスに送られる。
    //        -1未満の場合...シグナル _sig は,-_pidによって識別されるプロセスのグループに送られます。
    public synchronized boolean kill( int _pid, int _sig ) {
	int i;
	int ret = 0;
	if( -1 == _pid ) {
	    // pid 1 を除く全てのプロセスにシグナルを送信する。
	    for( i = 1 ; i < ptable.size( ) ; i++ ) {
		// 指定プロセスにシグナルを送信する。
		ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
		if( pinfo.process != null ) {
		    pinfo.process.recv( _sig );
		    if( sysinfo.verbose( )) {
			println( "Emulin info: send to signal pid=( " + ((int)i+1) + " ) sig=( " + _sig + " ) " );
		    }
		}
	    }
	    return( true );
	}

	if( 0 < _pid ) {
	    // 指定プロセスにシグナルを送信する。
	    // Phase 27 step 23: 旧実装は ptable.elementAt(_pid) で 1 つズレていた
	    //   (pid は 1-based、ptable は 0-based)。pid を使って線形に探す。
	    Process target = find_process( _pid );
	    if( target != null ) target.recv( _sig );
	}
	else {
	    println( "Emulin error: kernel kill( " + _pid + " ) unsupported." );
	}
	return( true );
    }
}
