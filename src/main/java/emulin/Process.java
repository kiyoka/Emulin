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
  int umask = 0022;  // ファイル作成 mask (Linux デフォルト)
  /* exec で置き換えられる際、自スレッド終了時に file descriptor を閉じないためのフラグ。
     新プロセスが共有 syscall (= 同じ FileAccess) を引き続き使うため、ここで閉じると
     stdin/stdout/stderr が無効になる。 */
  volatile boolean exec_replacing = false;
  volatile boolean exit_flag;
  volatile int exit_code = 0;       // sys_exit / sys_exit_group に渡された終了コード (wait4 が読む)
  String name;        // argv[0] (busybox の applet 名 等)
  String exec_path;   // 実行ファイルの path (name と異なる場合あり)
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

    // オブジェクトの生成
    if( _syscall == null ) {
      syscall = new SyscallI386( sysinfo, this );
    }
    else {
      syscall = _syscall;
      syscall.process = this;
    }
    mem    = new Memory( sysinfo, syscall, this );
    name   = new String( args[0] );
    this.exec_path = filename;  // 絶対パス。/proc/self/exe で参照される
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
      if( mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64 ) {
        // exec 経由で既存の SyscallAmd64 を引き継いでいれば file descriptor を
        // 保持するために再利用する。それ以外 (新規 / i386 から exec 等) は新設。
        if( !(syscall instanceof SyscallAmd64) ) {
          syscall = new SyscallAmd64( sysinfo, this );
        }
        cpu     = new Cpu64( sysinfo, this );
        cpu.connect_devices( mem, syscall );
        // カーネルがスレッド起動前に初期 TLS を設定するのと等価な処理。
        // %fs:0x28 のスタックカナリアが有効メモリを指すように事前に設定する。
        long pre_tls = mem.alloc_and_map( 0, 4096, -1, 0 );
        if( pre_tls > 0 ) ((Cpu64)cpu).fs_base = pre_tls;
        long sp64 = stack_data_init64( sysinfo.get_stack_bottom_64( ), args, envs );
        // カーネルが ELF ロード時に処理する IRELATIVE リロケーションを解決する。
        cpu.set_sp( sp64 );
        resolve_irelative( (Cpu64)cpu );
        // Linux カーネルはプロセス起動時に汎用レジスタをすべてゼロクリアする
        // (rsp/rip 以外)。IRELATIVE 解決中に使ったレジスタが残っていると
        // _start が rtld_fini (rdx) として誤った値を __libc_start_main に渡し、
        // glibc がランダムなアドレスを exit handler として登録してしまう。
        Cpu64 cpu64 = (Cpu64)cpu;
        for( int i = 0; i < 16; i++ ) cpu64.r64[i] = 0;
        cpu64.set_sp( sp64 );
        cpu.set_ip( ip );
      }
      else {
        cpu = new Cpu( sysinfo, this );
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
    // ELF64: Cpu64.eval() が fetch/decode/execute ループを自己完結で行う
    if( mem != null && mem.e_ident[Elf.EI_CLASS] == Elf.ELFCLASS64 ) {
      if( !init_process ) {
        cpu.eval( );
        if( !exec_replacing ) syscall.all_file_close( );
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
	//	try { Thread.sleep( 50L ); }
	//	catch( InterruptedException m ) { };
	Thread.yield( );
      }
    }
    else {               // それ以外のプロセス
      int sig;
      long func_adrs;
      // CPUの実行サイクルに入る
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

  // ELF64 用スタック初期化 (x86-64 System V ABI: 8 バイトポインタ)
  // argc, argv[], NULL, envp[], NULL, AT_NULL (auxv 終端) を積む
  long stack_data_init64( long sp64, String args[], String envs[] ) {
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
    sp64 = sp64 & ~0xFL;  // 再アライメント

    // ELF プログラムヘッダのベースアドレスを求める (p_offset==0 のセグメントがELFヘッダを含む)
    long elf_base = 0;
    for( int k = 0; k < mem.segments; k++ ) {
      if( mem.segment[k].p_offset == 0 ) { elf_base = mem.segment[k].p_vaddr; break; }
    }
    long at_phdr  = elf_base + mem.e_phoff;
    long at_phnum = mem.e_phnum & 0xFFFFL;

    // auxv (AT_NULL が最後 = 高アドレス、先にプッシュ)
    sp64 -= 8; mem.store64( sp64, 0L );            // AT_NULL value
    sp64 -= 8; mem.store64( sp64, 0L );            // AT_NULL type

    // AT_RANDOM (25) — _dl_random のためのランダムバッファポインタ
    sp64 -= 8; mem.store64( sp64, at_random_ptr ); // AT_RANDOM value
    sp64 -= 8; mem.store64( sp64, 25L );           // AT_RANDOM type

    // AT_PAGESZ (6), AT_PHNUM (5), AT_PHENT (4), AT_PHDR (3)
    // glibc の _dl_aux_init が _dl_main_map.l_phdr/l_phnum に使用する
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

    if( sysinfo.debug( )) {
      println( "  Stack64 init: sp64=0x" + Long.toHexString( sp64 )
               + " argc=" + args.length );
    }
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
    for( int s = 0; s < mem.sections; s++ ) {
      Section sec = mem.section[s];
      if( sec.sh_type != SHT_RELA || sec.sh_size == 0 || sec.sh_addr == 0 ) continue;

      long n = sec.sh_size / 24;  // Elf64_Rela は 24 バイト
      for( long i = 0; i < n; i++ ) {
        long ea      = sec.sh_addr + i * 24;
        long r_offset = mem.load64( ea );
        long r_info   = mem.load64( ea + 8 );
        long r_addend = mem.load64( ea + 16 );
        int  r_type   = (int)(r_info & 0xFFFFFFFFL);
        if( r_type == R_X86_64_IRELATIVE ) {
          long result = cpu64.call_resolver( r_addend, trampoline );
          if( is_exited() ) return;
          mem.store64( r_offset, result );
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
