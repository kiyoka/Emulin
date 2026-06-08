// ----------------------------------------
//  Signal
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
    // Phase 27 step 35: thread-targeted pending signal。tgkill / pthread_kill で
    //   特定 thread に送られた signal はその thread しか受け取れない (POSIX 仕様)。
    //   key = tid (Thread64.tid または main thread の pid)、value = pending count
    //   per signal。pending_recv_count は global hint として両方をカウント。
    private final java.util.concurrent.ConcurrentHashMap<Integer, int[]> thread_pending
        = new java.util.concurrent.ConcurrentHashMap<>();

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
    
    // 現 Java thread の tid を返す (worker なら tid、main thread なら process pid)。
    //   ★ #221 multi-vCPU: GuestThread (Thread64 / NativeCpuBackend.Worker) で worker を認識。
    //   旧 `instanceof Thread64` は native worker を取りこぼし process.pid を返していたため、
    //   tgkill が thread_pending[worker_tid] に積んだ thread 宛 signal を worker が読めなかった
    //   (gettid syscall は GuestThread.guestTid を返すので tid 不一致になっていた)。
    private int current_tid( ) {
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) return g.guestTid();
	if( this instanceof Process ) return ((Process)this).pid;
	return 0;
    }

    // 現 thread の per-signal pending 配列を取得。なければ作成
    private int[] my_pending( ) {
	return thread_pending.computeIfAbsent( current_tid(), k -> new int[SIGNALS] );
    }

    // シグナル受信チェック
    // 受信したシグナル番号を返す。
    // Phase 27 step 34: per-thread signal mask に対応。
    // Phase 27 step 35: per-thread pending signal にも対応。tgkill 経由で
    //   特定 thread に送られた signal は、その thread の pending にだけ入る。
    //   psig() は own thread の pending → process-wide pending の順で check。
    public int psig( ) {
	if( pending_recv_count == 0 ) return -1;
	long thread_mask = current_thread_mask();
	// 1. own thread の pending を先に check
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null ) {
	    for( int i = 1 ; i < SIGNALS ; i++ ) {
		if( mine[i] > 0 ) {
		    if( (thread_mask & (1L << (i - 1))) != 0 ) continue;
		    return i;
		}
	    }
	}
	// 2. process-wide pending を check
	for( int i = 0 ; i < SIGNALS ; i++ ) {
	    if( 0 < signals[i].get_count( )) {
		if( i >= 1 && (thread_mask & (1L << (i - 1))) != 0 ) continue;
		if( !(Thread.currentThread() instanceof Thread64) && signals[i].isMask( )) continue;
		return( i );
	    }
	}
	return( -1 );
    }

    // issue #225: pending かつ unmask かつ「無視されない」(handler 有り、または
    //   default action が terminate) な signal を返す。無ければ -1。
    //   poll/select/pselect の EINTR 判定に使う。ignore (SIG_IGN または
    //   default=SIGACTION_NONE) のシグナルでは blocking syscall を中断しない
    //   (Linux 仕様)。psig() と違い無視シグナルを飛ばすので、無視シグナルが
    //   先に pending でも後続の actionable シグナル (SIGWINCH 等) を拾える。
    public int psig_actionable( ) {
	if( pending_recv_count == 0 ) return -1;
	long thread_mask = current_thread_mask();
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null ) {
	    for( int i = 1 ; i < SIGNALS ; i++ ) {
		if( mine[i] > 0 && (thread_mask & (1L << (i - 1))) == 0 && is_actionable( i ) ) return i;
	    }
	}
	for( int i = 1 ; i < SIGNALS ; i++ ) {
	    if( signals[i].get_count( ) > 0 ) {
		if( (thread_mask & (1L << (i - 1))) != 0 ) continue;
		if( !(Thread.currentThread() instanceof Thread64) && signals[i].isMask( )) continue;
		if( is_actionable( i ) ) return i;
	    }
	}
	return -1;
    }
    private boolean is_actionable( int sig ) {
	long h = signals[sig].get_func_adrs( );
	if( h == Siginfo.SIG_IGN ) return false;
	if( h == Siginfo.SIG_DFL && get_action_type( sig ) == SIGACTION_NONE ) return false;
	return true;
    }

    // 現 Java thread の signal mask bits を返す。
    //   ★ #221: worker (Thread64 / NativeCpuBackend.Worker) は GuestThread で per-thread mask を持つ。
    //   旧 instanceof Thread64 は native worker で 0L を返し、psig() の own-thread pending masking が
    //   壊れていた (worker が block したはずの signal を配信してしまう)。
    private long current_thread_mask( ) {
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) return g.getSignalMask();
	return 0L;
    }

    // tgkill / pthread_kill 用: 特定 tid の thread の pending に send
    public void recv_to_thread( int target_tid, int sig ) {
	if( sig < 0 || sig >= SIGNALS ) return;
	int[] arr = thread_pending.computeIfAbsent( target_tid, k -> new int[SIGNALS] );
	synchronized( arr ) { arr[sig]++; }
	pending_recv_count++;
    }

    // シグナルのキャンセル
    // Phase 27 step 35: own thread の pending を先に消費。なければ process-wide。
    public void signal_cancel( int _sig ) {
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null && mine[_sig] > 0 ) {
	    int c;
	    synchronized( mine ) { c = mine[_sig]; mine[_sig] = 0; }
	    pending_recv_count -= c;
	    if( pending_recv_count < 0 ) pending_recv_count = 0;
	    return;
	}
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

    // Phase 27 step 23/34: signal mask の get/set。
    //   pthread worker (GuestThread = Thread64 / NativeCpuBackend.Worker) なら per-thread mask、
    //   main thread なら process-wide な Siginfo.mask フィールドを操作 (旧仕様)。
    //   ★ #221: 旧 instanceof Thread64 は native worker を取りこぼし process-wide mask を共有して
    //   いた (worker の block/unblock が他 worker と main に漏れる)。GuestThread 経由で per-thread に。
    public boolean is_signal_masked( int signum ) {
	if( signum < 0 || signum >= SIGNALS ) return false;
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) {
	    return (g.getSignalMask() & (1L << (signum - 1))) != 0;
	}
	return signals[signum].isMask( );
    }
    public void set_signal_mask( int signum, boolean masked ) {
	if( signum < 0 || signum >= SIGNALS ) return;
	// SIGKILL (9) と SIGSTOP (19) はマスク不可 (POSIX 仕様)
	if( signum == SIGKILL || signum == SIGSTOP ) return;
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) {
	    long m = g.getSignalMask();
	    if( masked ) m |= (1L << (signum - 1));
	    else m &= ~(1L << (signum - 1));
	    g.setSignalMask( m );
	} else {
	    signals[signum].mask( masked );
	}
    }
    // 32 signal 分の mask を 1 つの long に詰めて返す/設定する。
    //   bit 0 = signum 1 (SIGHUP)、... bit 30 = signum 31 (SIGUNUSED)
    public long get_signal_mask_bits( ) {
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) {
	    return g.getSignalMask();
	}
	long m = 0;
	for( int s = 1; s < SIGNALS; s++ ) {
	    if( signals[s].isMask( )) m |= (1L << (s - 1));
	}
	return m;
    }
    public void set_signal_mask_bits( long bits ) {
	// SIGKILL/SIGSTOP は mask 不可
	bits &= ~(1L << (SIGKILL - 1));
	bits &= ~(1L << (SIGSTOP - 1));
	Thread cur = Thread.currentThread();
	if( cur instanceof GuestThread g ) {
	    g.setSignalMask( bits );
	} else {
	    for( int s = 1; s < SIGNALS; s++ ) {
		boolean want = (bits & (1L << (s - 1))) != 0;
		signals[s].mask( want );
	    }
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
    public boolean has_sa_nodefer( int signum ) {
	return signals[signum].has_sa_nodefer( );
    }
    public void set_sa_mask( int signum, long mask ) {
	signals[signum].set_sa_mask( mask );
    }
    public long get_sa_mask( int signum ) {
	return signals[signum].get_sa_mask( );
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
	// issue #225: Linux のデフォルト動作が「無視」のシグナル。SIGWINCH は
	//   ハンドラ未登録のプロセス (resize を気にしない CLI 等) に届いても
	//   終了させてはならない。旧実装は SIGACTION_EXIT 既定 + 例外列挙漏れで
	//   SIGWINCH を受けた handler 無しプロセスが終了していた (TIOCSWINSZ が
	//   no-op だったため従来は露見せず)。SIGURG も同じく無視が既定。
	if( SIGWINCH	== signum ) { ret = SIGACTION_NONE; }
	if( SIGURG	== signum ) { ret = SIGACTION_NONE; }
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
