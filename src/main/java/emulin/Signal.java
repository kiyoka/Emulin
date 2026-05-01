// ----------------------------------------
//  Signal
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:50:35 $ 
//  $Id: Signal.java,v 1.5 2000/01/13 15:50:35 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;
import emulin.device.*;

public class Signal extends Thread {
    public static int SIGHUP   = 1;	/* Hangup (POSIX).  */
    public static int SIGINT	= 2;	/* Interrupt (ANSI).  */
    public static int SIGQUIT	= 3;	/* Quit (POSIX).  */
    public static int SIGILL	= 4;	/* Illegal instruction (ANSI).  */
    public static int SIGTRAP	= 5;	/* Trace trap (POSIX).  */
    public static int SIGABRT	= 6;	/* Abort (ANSI).  */
    public static int SIGIOT	= 6;	/* IOT trap (4.2 BSD).  */
    public static int SIGBUS	= 7;	/* BUS error (4.2 BSD).  */
    public static int SIGFPE	= 8;	/* Floating-point exception (ANSI).  */
    public static int SIGKILL	= 9;	/* Kill, unblockable (POSIX).  */
    public static int SIGUSR1	= 10;	/* User-defined signal 1 (POSIX).  */
    public static int SIGSEGV	= 11;	/* Segmentation violation (ANSI).  */
    public static int SIGUSR2	= 12;	/* User-defined signal 2 (POSIX).  */
    public static int SIGPIPE	= 13;	/* Broken pipe (POSIX).  */
    public static int SIGALRM	= 14;	/* Alarm clock (POSIX).  */
    public static int SIGTERM	= 15;	/* Termination (ANSI).  */
    public static int SIGSTKFLT= 16;	/* ??? */
    public static int SIGCLD	= 17;	/* Same as SIGCHLD (System V).  */
    public static int SIGCHLD	= 17;	/* Child status has changed (POSIX).  */
    public static int SIGCONT	= 18;	/* Continue (POSIX).  */
    public static int SIGSTOP	= 19;	/* Stop, unblockable (POSIX).  */
    public static int SIGTSTP	= 20;	/* Keyboard stop (POSIX).  */
    public static int SIGTTIN	= 21;	/* Background read from tty (POSIX).  */
    public static int SIGTTOU	= 22;	/* Background write to tty (POSIX).  */
    public static int SIGURG	= 23;	/* Urgent condition on socket (4.2 BSD).  */
    public static int SIGXCPU	= 24;	/* CPU limit exceeded (4.2 BSD).  */
    public static int SIGXFSZ	= 25;	/* File size limit exceeded (4.2 BSD).  */
    public static int SIGVTALRM= 26;	/* Virtual alarm clock (4.2 BSD).  */
    public static int SIGPROF	= 27;	/* Profiling alarm clock (4.2 BSD).  */
    public static int SIGWINCH	= 28;	/* Window size change (4.3 BSD, Sun).  */
    public static int SIGPOLL	= 29; 	/* Pollable event occurred (System V).  */
    public static int SIGIO	= 29;	/* I/O now possible (4.2 BSD).  */
    public static int SIGPWR	= 30;	/* Power failure restart (System V).  */
    public static int SIGUNUSED= 31;
    
    static int SIGNALS  = 32;
    static int SIGACTION_NONE  = 0; /* 何もしない */
    static int SIGACTION_EXIT  = 1; /* 終了する  */
    static int SIGACTION_PAUSE = 2; /* 一時停止する  */
    static int SIGACTION_CONT  = 3; /* 停止中なら実行を再開する */
    public Sysinfo sysinfo;
    Siginfo signals[];
    
    public Signal( ) {
	int i;
	// オブジェクトの生成
	signals = new Siginfo[SIGNALS];
	// シグナルの初期化
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    signals[i] = new Siginfo( );
	}
    }
    
    // _signal の値で自分をアップデートする。
    public synchronized void update_info( Signal _signal ) {
	int i;
	sysinfo           = _signal.sysinfo;
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    signals[i] = _signal.signals[i].duplicate( );
	}
    }
    
    // シグナル受信チェック
    // 受信したシグナル番号を返す。
    public int psig( ) {
	int i;
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    if( !signals[i].isMask( )) {
		if( 0 < signals[i].get_count( )) {
		    return( i );
		}
	    }
	}
	return( -1 ); // シグナルなし
    }

    // シグナルのキャンセル
    public void signal_cancel( int _sig ) {
	signals[ _sig ].cancel( );
    }

    // シグナルハンドラ関数のアドレスを返す (x86-64 対応で long)
    public long get_func_adrs( int signum ) {
	return( signals[signum].get_func_adrs( ));
    }

    // シグナルの登録
    public boolean set_sigaction( int signum, long func_adrs ) {
	signals[signum].set_sigaction( func_adrs );
	return( true );
    }

    // sa_flags の設定 / 参照 (SA_RESTART 等)
    public void set_sa_flags( int signum, long flags ) {
	signals[signum].set_sa_flags( flags );
    }
    public long get_sa_flags( int signum ) {
	return signals[signum].get_sa_flags( );
    }
    public boolean has_sa_restart( int signum ) {
	return signals[signum].has_sa_restart( );
    }

    // シグナルの受信
    public boolean recv( int sig ) {
	int i;
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    if( sig == i ) {
		signals[i].recv( );
	    }
	}
	return( true );
    }
    
    // デフォルトのシグナルハンドラの種類を返す。
    public int get_action_type( int signum ) {
	int ret = SIGACTION_EXIT;
	if( SIGCHLD	== signum ) { ret = SIGACTION_NONE; }
	if( SIGCONT	== signum ) { ret = SIGACTION_CONT; }
	if( SIGSTOP	== signum ) { ret = SIGACTION_PAUSE; }
	if( SIGTSTP	== signum ) { ret = SIGACTION_PAUSE; }
	if( SIGTTIN	== signum ) { ret = SIGACTION_PAUSE; }
	if( SIGTTOU	== signum ) { ret = SIGACTION_PAUSE; }
	return( ret );
    }

    // シグナルの名前を返す
    public String get_signame( int signum ) {
	String ret = "";
	if( SIGHUP   == signum ) { ret = "SIGHUP"; }
	if( SIGINT   == signum ) { ret = "SIGINT"; }
	if( SIGQUIT	== signum ) { ret = "SIGQUIT"; }
	if( SIGILL	== signum ) { ret = "SIGILL"; }
	if( SIGTRAP	== signum ) { ret = "SIGTRAP"; }
	if( SIGABRT	== signum ) { ret = "SIGABRT"; }
	if( SIGIOT	== signum ) { ret = "SIGIOT"; }
	if( SIGBUS	== signum ) { ret = "SIGBUS"; }
	if( SIGFPE	== signum ) { ret = "SIGFPE"; }
	if( SIGKILL	== signum ) { ret = "SIGKILL"; }
	if( SIGUSR1	== signum ) { ret = "SIGUSR1"; }
	if( SIGSEGV	== signum ) { ret = "SIGSEGV"; }
	if( SIGUSR2	== signum ) { ret = "SIGUSR2"; }
	if( SIGPIPE	== signum ) { ret = "SIGPIPE"; }
	if( SIGALRM	== signum ) { ret = "SIGALRM"; }
	if( SIGTERM	== signum ) { ret = "SIGTERM"; }
	if( SIGSTKFLT   == signum ) { ret = "SIGSTKFLT"; }
	if( SIGCLD	== signum ) { ret = "SIGCLD"; }
	if( SIGCHLD	== signum ) { ret = "SIGCHLD"; }
	if( SIGCONT	== signum ) { ret = "SIGCONT"; }
	if( SIGSTOP	== signum ) { ret = "SIGSTOP"; }
	if( SIGTSTP	== signum ) { ret = "SIGTSTP"; }
	if( SIGTTIN	== signum ) { ret = "SIGTTIN"; }
	if( SIGTTOU	== signum ) { ret = "SIGTTOU"; }
	if( SIGURG	== signum ) { ret = "SIGURG"; }
	if( SIGXCPU	== signum ) { ret = "SIGXCPU"; }
	if( SIGXFSZ	== signum ) { ret = "SIGXFSZ"; }
	if( SIGVTALRM   == signum ) { ret = "SIGVTALRM"; }
	if( SIGPROF	== signum ) { ret = "SIGPROF"; }
	if( SIGWINCH	== signum ) { ret = "SIGWINCH"; }
	if( SIGPOLL	== signum ) { ret = "SIGPOLL"; }
	if( SIGIO	== signum ) { ret = "SIGIO"; }
	if( SIGPWR	== signum ) { ret = "SIGPWR"; }
	if( SIGUNUSED   == signum ) { ret = "SIGUNUSED"; }
	return( ret );
    }
}
