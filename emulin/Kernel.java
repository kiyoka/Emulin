// ----------------------------------------
//  Emulin Kernel
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/02/10 18:59:40 $ 
//  $Id: Kernel.java,v 1.27 2000/02/10 18:59:40 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import emulin.*;
import emulin.device.*;

public class Kernel extends PipeManager {
  int cur_pid;
  boolean exec_request; // execのリクエストがあるかどうか
  String  exec_args[]; // execリクエスト用
  String  exec_envs[]; // execリクエスト用
  int exec_pid;  // execリクエスト用
  JFrame f;

  public Kernel( Sysinfo _sysinfo ) {
    ProcessInfo pinfo = new ProcessInfo( );
    // カーネルの初期化
    exec_request = false;
    sysinfo = _sysinfo;
    sysinfo.kernel = this;
    console = new Console( sysinfo );

    // プロセステーブルの初期化
    ptable = new Vector( );
  }

  // ボタンリスナ
  class ButtonListener implements ActionListener {
      // アクション
      public void actionPerformed( ActionEvent ae ) {
	  console.set_int( Signal.SIGINT );
      }
  }

  // カーネルのブート
  public void boot( String args[], String _native_curdir ) {
    String envs[] = new String[10];
    int j = 0;
    Process process;
    ProcessInfo pinfo;
    cur_pid = 1;


    // ネイティブコンソールの場合は INT 用のWindowを出す。
    if( sysinfo.get_console_type( ) == Sysinfo.CONSOLE_NATIVE ) {
	System.out.println( "Info:Display Window..." );
	f = new JFrame( "Emulin" );
	JButton button = new JButton( "Interrupt" );
	button.addActionListener(new ButtonListener( ));
	f.getContentPane( ).add( button );
	//	f.addWindowListener( new WindowEventHandler( ));
	f.setSize( 150,50 );
	f.show( );
    }

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

    // 環境変数の初期化
    envs[ j++] = "HOSTTYPE=i386";
    envs[ j++] = "PATH=/usr/local/bin:/bin:/usr/bin:.";
    envs[ j++] = "SHELL=/bin/sh";
    envs[ j++] = "OSTYPE=Linux";
    envs[ j++] = "SHLVL=0";
    envs[ j++] = "LESSCHARSET=japanese-sjis";
    envs[ j++] = "JLESSPLANESET=japanese";
    envs[ j++] = "LD_LIBRARY_PATH=/usr/local/lib";
    envs[ j++] = "TERMCAP=/etc/termcap";
    envs[ j++] = "TERM=vt100";

    // bootプロセスの生成
    pinfo = new ProcessInfo( );
    pinfo.process = new Process( cur_pid, sysinfo.get_default_gid( ), sysinfo.get_default_uid( ), 
				 sysinfo.get_virtual_path( _native_curdir ), 
				 args, envs, sysinfo, null );
    pinfo.process.syscall.FileOpen( "<std>", "r", 0 ); 
    pinfo.process.syscall.FileOpen( "<std>", "w", 0 ); 
    pinfo.process.syscall.FileOpen( "<err>", "w", 0 ); 
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
	pinfo.process.stop( );
	if( sysinfo.verbose( )) {
	    println( "Kernel.start( )  break" );
	}
	System.exit( 0 );
      }
    }
  }

  // exec( )処理
  public synchronized void exec( int _pid, String _args[], String _envs[] ) {
    Syscall syscall;
    ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( _pid-1 );
    int tmp_gid       = pinfo.process.gid;
    int tmp_uid       = pinfo.process.uid;
    String tmp_curdir = pinfo.process.get_curdir( );
    pinfo.process.stop( ); // プロセスを終了させる
    syscall = pinfo.process.syscall; // バックアップする。
    pinfo.process = new Process( _pid, tmp_gid, tmp_uid, tmp_curdir, _args, _envs, sysinfo, syscall ); // プロセスを生成
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
	  if( pinfo.process.is_exited( ))   { 
	    ret = i+1;
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

  // プロセスがいくら残っているかを返す
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
	    ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( _pid );
	    if( pinfo.process != null ) {
		pinfo.process.recv( _sig );
	    }
	}
	else {
	    println( "Emulin error: kernel kill( " + _pid + " ) unsupported." );
	}
	return( true );
    }
}
