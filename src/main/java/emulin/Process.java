// ----------------------------------------
//  Process
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:22 $ 
//  $Id: Process.java,v 1.81 2000/01/13 15:50:22 kiyoka Exp $
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
  int gid;
  int uid;
  volatile boolean exit_flag;
  String name;
  String curdir;
  boolean init_process;
  long evals;
  long handler_hook;       // signalハンドラフックアドレス
  long sig_no_embed_adrs;  // 割り込み番号を書き込むアドレス
  long handler_embed_adrs; // 割り込みハンドラアドレスを書き込むアドレス

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
    String filename = _sysinfo.get_full_path( _curdir, args[0] );
    sysinfo         = _sysinfo;
    pid             = _pid;
    init_process    = false;

    // オブジェクトの生成
    if( _syscall == null ) {
      syscall = new Syscall( sysinfo, this );
    }
    else {
      syscall = _syscall;
      syscall.process = this;
    }
    mem    = new Memory( sysinfo, syscall, this );
    cpu    = new Cpu( sysinfo, this );
    name   = new String( args[0] );
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

      // CPUを初期化する
      ip = mem.get_entry( );

      cpu.connect_devices( mem, syscall ); // メモリ,システムコールを接続する
      cpu.set_ip( ip );
      cpu.set_sp( sysinfo.get_stack_bottom( ));

      // スタックデータの初期化を行う
      stack_data_init( cpu, args, envs );

      // _debug_
      //      ip = handler_hook;
      //      cpu.set_ip( ip );

      if( sysinfo.debug( )) {
	println( "---------- Execute Start ----------" );
      }
    }
  }

  // 自分の複製を返す
  public synchronized Process duplicate( ) {
    // オブジェクトの生成
    Process _process    = new Process( pid, sysinfo );
    _process.update_info( (Signal)this );
    _process.syscall    = syscall.duplicate( _process );
    _process.mem        = mem.duplicate( _process );
    _process.cpu        = cpu.duplicate( _process );
    _process.name       = new String( name );
    _process.curdir     = new String( curdir );
    _process.ip         = ip;
    _process.gid        = gid;
    _process.uid        = uid;
    _process.exit_flag  = exit_flag;
    _process.cpu.connect_devices( _process.mem, _process.syscall ); // メモリ,システムコールを接続する
    return( _process );
  }

  // initプロセスとして設定する。
  public void set_init_process( ) {
    init_process = true;
  }

  // exit したことを知らせる。
  public void set_exit_flag( ) {
    exit_flag = true;
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

  // プロセスの実行
  public void run( ) {
    int len = 0;
    byte buf[] = new byte[15];
    int i, j;
    int fd;
    if( init_process ) { // init プロセス
      while( true ) {
	  if( sysinfo.get_console_type( ) == Sysinfo.CONSOLE_NATIVE ) { // コンソールからの割り込みのチェック
	  sysinfo.kernel.console._byte_read( sysinfo );
	  sysinfo.kernel.console._int_check_and_send( sysinfo );
	}
	//	try { Thread.sleep( 50L ); }
	//	catch( InterruptedException m ) { };
	Thread.yield( );
      }
    }
    else {               // それ以外のプロセス
      int sig;
      int func_adrs;
      // CPUの実行サイクルに入る
      while( !exit_flag ) {
	if( exit_flag ) {
	  if( sysinfo.verbose( )) {
	    println( "Process [ " + name + " ]  exited. " );
	  }
	}

	if( sysinfo.get_console_type( ) == Sysinfo.CONSOLE_NATIVE ) { // コンソールからの割り込みのチェック
	  sysinfo.kernel.console._int_check_and_send( sysinfo );
	}

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
			syscall.sys_exit( 1, 0, 0, 0, 0 );
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
		    mem.store32( handler_embed_adrs, func_adrs );
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
    }
    cpu.cache_expire( );
    syscall.all_file_close( );
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
