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
    // Phase 27 step 24: 1 命令ごとに psig() が 32 signal をスキャンしてた
    //   ホット bottleneck の fast-path。pending = 0 なら早期 return -1。
    //   recv() / cancel() で更新する。volatile = signal は別 thread から届く。
    volatile int pending_recv_count;

    public Signal( ) {
	int i;
	// オブジェクトの生成
	signals = new Siginfo[SIGNALS];
	// シグナルの初期化
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    signals[i] = new Siginfo( );
	}
	pending_recv_count = 0;
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
	// Phase 27 step 24: 大半のケースで pending = 0 → 即 return
	if( pending_recv_count == 0 ) return -1;
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
	int c = signals[_sig].get_count();
	signals[_sig].cancel( );
	if( c > 0 ) pending_recv_count -= c;
	if( pending_recv_count < 0 ) pending_recv_count = 0;
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

    // Phase 27 step 23: per-signal mask の get/set。Siginfo.mask フィールドを
    //   sigprocmask / sa_mask の格納先として使う。psig() が isMask() をチェック
    //   するので、masked な signal は配信されない (= pending のまま残る)。
    public boolean is_signal_masked( int signum ) {
	if( signum < 0 || signum >= SIGNALS ) return false;
	return signals[signum].isMask( );
    }
    public void set_signal_mask( int signum, boolean masked ) {
	if( signum < 0 || signum >= SIGNALS ) return;
	// SIGKILL (9) と SIGSTOP (19) はマスク不可 (POSIX 仕様)
	if( signum == SIGKILL || signum == SIGSTOP ) return;
	signals[signum].mask( masked );
    }
    // 32 signal 分の mask を 1 つの long に詰めて返す/設定する。
    //   bit 0 = signum 1 (SIGHUP)、... bit 30 = signum 31 (SIGUNUSED)
    public long get_signal_mask_bits( ) {
	long m = 0;
	for( int s = 1; s < SIGNALS; s++ ) {
	    if( signals[s].isMask( )) m |= (1L << (s - 1));
	}
	return m;
    }
    public void set_signal_mask_bits( long bits ) {
	for( int s = 1; s < SIGNALS; s++ ) {
	    boolean want = (bits & (1L << (s - 1))) != 0;
	    set_signal_mask( s, want );  // SIGKILL/SIGSTOP は内部で弾く
	}
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
    public boolean has_sa_siginfo( int signum ) {
	return signals[signum].has_sa_siginfo( );
    }

    // シグナルの受信
    public boolean recv( int sig ) {
	int i;
	for( i = 0 ; i < SIGNALS ; i++ ) {
	    if( sig == i ) {
		signals[i].recv( );
		pending_recv_count++;  // Phase 27 step 24: psig() の fast-path 用
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
