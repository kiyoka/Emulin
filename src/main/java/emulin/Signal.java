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
    
    // Linux の _NSIG は 65 (有効 signal は 1..64、32..64 が real-time signal SIGRTMIN..SIGRTMAX)。
    //   issue #221 step 3d-2c-37: Go runtime の initsig は全 signal (1..64) を反復して sigaction
    //   する。旧 SIGNALS=32 では signal 34 (SIGRTMIN) の sigaction が範囲外で EINVAL → Go が
    //   "fatal error: sigaction failed" で即死していた。RT signal は実配信せずとも sigaction の
    //   登録/照会が成功すれば良い (Go は SIGURG=23 でしか preempt しない)。65 に拡大。
    static int SIGNALS  = 65;
    // issue #615: kernel SIGRTMIN。signum >= 32 は real-time signal で、標準 signal
    //   (1..31) と違いキューイングされる (合体しない = 送った回数だけ配送)。標準 signal は
    //   従来どおり合体させ、RT signal だけ 1 つずつ消費する (consume_one 参照)。
    static final int SIGRTMIN = 32;
    static int SIGACTION_NONE  = 0; /* 何もしない */
    static int SIGACTION_EXIT  = 1; /* 終了する  */
    static int SIGACTION_PAUSE = 2; /* 一時停止する  */
    static int SIGACTION_CONT  = 3; /* 停止中なら実行を再開する */
    public Sysinfo sysinfo;
    Siginfo signals[];
    // Phase 27 step 24: 1 命令ごとに psig() が 32 signal をスキャンしてた
    //   ホット bottleneck の fast-path。pending = 0 なら早期 return -1。
    //   recv() / cancel() で更新する。signal は別 thread から届く。
    // ★ #221 ハードニング (step 3d-2c-22): 複数 thread (eval thread + signal 送信側 = itimer/kill/
    //   tgkill) が並行に recv/cancel する。旧 `volatile int` の `++`/`-=` は非 atomic な
    //   read-modify-write なので、異なる signal の並行 recv で increment が失われ under-count →
    //   `pending_recv_count==0` の fast-path が pending signal を取りこぼす (signal lost)。AtomicInteger で
    //   atomic 更新にして lost-update を排除する (single-thread では挙動不変、byte-identical)。
    final java.util.concurrent.atomic.AtomicInteger pending_recv_count =
        new java.util.concurrent.atomic.AtomicInteger();
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
	// pending_recv_count は field initializer で 0。
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
	if( pending_recv_count.get() == 0 ) return -1;
	long thread_mask = current_thread_mask();
	// issue #533 追補: main thread (非 GuestThread) の mask は Siginfo 側 (signals[i].isMask) に
	//   保存される (set_signal_mask_bits の else 分岐)。旧実装は thread 宛 pending (section 1) で
	//   これを見ておらず、main thread 宛の blocked signal が「配信可能」に見えていた
	//   (process-wide の section 2 には従来からある同チェックの section 1 版)。
	boolean useProcMask = !(Thread.currentThread() instanceof GuestThread);
	// 1. own thread の pending を先に check
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null ) {
	    for( int i = 1 ; i < SIGNALS ; i++ ) {
		if( mine[i] > 0 ) {
		    if( (thread_mask & (1L << (i - 1))) != 0 ) continue;
		    if( useProcMask && signals[i].isMask( ) ) continue;
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

    // issue #443: rt_sigpending 用。現在 pending な signal の bitmask (bit i = signum i+1)。
    //   mask の有無に関わらず「生成されたが未配信」の signal を返す (own-thread + process-wide)。
    public long pending_bits( ) {
	long bits = 0;
	int[] mine = thread_pending.get( current_tid() );
	for( int i = 1 ; i < SIGNALS ; i++ ) {
	    if( signals[i].get_count( ) > 0 || (mine != null && mine[i] > 0) )
		bits |= (1L << (i - 1));
	}
	return bits;
    }

    // issue #225: pending かつ unmask かつ「無視されない」(handler 有り、または
    //   default action が terminate) な signal を返す。無ければ -1。
    //   poll/select/pselect の EINTR 判定に使う。ignore (SIG_IGN または
    //   default=SIGACTION_NONE) のシグナルでは blocking syscall を中断しない
    //   (Linux 仕様)。psig() と違い無視シグナルを飛ばすので、無視シグナルが
    //   先に pending でも後続の actionable シグナル (SIGWINCH 等) を拾える。
    public int psig_actionable( ) {
	if( pending_recv_count.get() == 0 ) return -1;
	long thread_mask = current_thread_mask();
	boolean useProcMask = !(Thread.currentThread() instanceof GuestThread);  // issue #533 追補 (psig と同じ)
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null ) {
	    for( int i = 1 ; i < SIGNALS ; i++ ) {
		if( mine[i] > 0 && (thread_mask & (1L << (i - 1))) == 0
		    && !(useProcMask && signals[i].isMask( )) && is_actionable( i ) ) return i;
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
    // ★ issue #221 step 3d-2c-39: signal を queue した直後に呼ぶ async-kick hook。native backend が
    //   設定する。target_tid (>0=特定 thread、-1=process-wide) が現在実 vCPU で guest code を
    //   実行中 (KVM_RUN) の場合、その host thread に host signal を送って KVM_RUN を割込ませ、
    //   syscall を待たずに guest signal を配信できるようにする。software backend では null (no-op)。
    public static volatile java.util.function.IntConsumer asyncKick = null;
    private static void kick( int target_tid ) {
	// issue #435 追補: lost-wakeup 診断用に kick (signal queue → vCPU cancel 要求) を可視化。
	if( SyscallAmd64.TRACE_WAKE ) SyscallAmd64._wakeTrace( "SIGKICK target_tid=" + target_tid );
	java.util.function.IntConsumer k = asyncKick;
	if( k != null ) { try { k.accept( target_tid ); } catch( Throwable ignore ) {} }
    }

    public void recv_to_thread( int target_tid, int sig ) {
	if( sig < 0 || sig >= SIGNALS ) return;
	int[] arr = thread_pending.computeIfAbsent( target_tid, k -> new int[SIGNALS] );
	synchronized( arr ) { arr[sig]++; }
	pending_recv_count.incrementAndGet();
	kick( target_tid );   // 走行中 vCPU なら async 配信のため kick
    }

    // シグナルのキャンセル
    // Phase 27 step 35: own thread の pending を先に消費。なければ process-wide。
    public void signal_cancel( int _sig ) {
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null && mine[_sig] > 0 ) {
	    int c;
	    synchronized( mine ) { c = mine[_sig]; mine[_sig] = 0; }
	    if( pending_recv_count.addAndGet( -c ) < 0 ) pending_recv_count.set( 0 );  // atomic 減算 + 防御 clamp
	    return;
	}
	int c = signals[_sig].get_count();
	signals[_sig].cancel( );
	if( c > 0 && pending_recv_count.addAndGet( -c ) < 0 ) pending_recv_count.set( 0 );
    }

    // issue #615: 配送時の消費。標準 signal (1..31) は従来どおり合体 (signal_cancel で全消費)、
    //   RT signal (>=SIGRTMIN) は 1 インスタンスだけ消費して残りを再配送させる (キューイング)。
    //   psig() と同じく own-thread pending → process-wide pending の順で 1 減らす。
    public void consume_one( int sig ) {
	if( sig < SIGRTMIN ) { signal_cancel( sig ); return; }   // 標準 signal は合体 (挙動不変)
	int[] mine = thread_pending.get( current_tid() );
	if( mine != null && mine[sig] > 0 ) {
	    synchronized( mine ) { if( mine[sig] > 0 ) mine[sig]--; }
	    if( pending_recv_count.decrementAndGet() < 0 ) pending_recv_count.set( 0 );
	    return;
	}
	if( signals[sig].get_count() > 0 ) {
	    signals[sig].consumeOne( );
	    if( pending_recv_count.decrementAndGet() < 0 ) pending_recv_count.set( 0 );
	}
    }

    // issue #615: 配送する signal の siginfo (SA_SIGINFO ハンドラへ渡す)。
    public int  get_si_code( int sig )  { return signals[sig].siCode; }
    public long get_si_value( int sig ) { return signals[sig].siValue; }
    public int  get_si_pid( int sig )   { return signals[sig].siPid; }
    // issue #615: rt_tgsigqueueinfo (thread 宛 sigqueue) 用に siginfo を保持する。
    public void set_thread_siginfo( int sig, int si_code, long si_value, int si_pid ) {
	if( sig >= 0 && sig < SIGNALS ) signals[sig].setSiginfo( si_code, si_value, si_pid );
    }

    // シグナルハンドラ関数のアドレスを返す (x86-64 対応で long)
    public long get_func_adrs( int signum ) {
	return( signals[signum].get_func_adrs( ));
    }

    // シグナルの登録
    public boolean set_sigaction( int signum, long func_adrs ) {
	signals[signum].set_sigaction( func_adrs );
	// issue #474: SIG_IGN に設定した瞬間、その signal の pending instance は
	//   破棄される (POSIX/Linux 仕様)。これをしないと rt_sigpending が
	//   「SIG_IGN にした後もまだ pending」という誤った集合を報告する。
	if( func_adrs == Siginfo.SIG_IGN ) signal_cancel( signum );
	return( true );
    }

    // issue #550: execve 越しに引き継ぐ signal 状態を old (exec 前の Process) から取り込む。
    //   Linux/POSIX の execve 仕様:
    //     - handler 付き disposition は SIG_DFL にリセット (新 signals[] のデフォルトが SIG_DFL
    //       なので何もしない)
    //     - SIG_IGN に設定された disposition は保存する
    //     - blocked signal mask (main thread は Siginfo.mask に保持) は保存する
    //   旧実装は exec で new Process を作るだけで、SIG_IGN も mask も失われていた。
    public void inheritExecSignalState( Signal old ) {
	for( int i = 0 ; i < SIGNALS ; i++ ) {
	    if( old.signals[i].get_func_adrs( ) == Siginfo.SIG_IGN ) {
		signals[i].set_sigaction( Siginfo.SIG_IGN );   // SIG_IGN は保存
	    }
	    signals[i].mask( old.signals[i].isMask( ) );       // blocked mask を保存
	}
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
    public boolean has_sa_onstack( int signum ) {
	return signals[signum].has_sa_onstack( );
    }
    public void set_sa_mask( int signum, long mask ) {
	signals[signum].set_sa_mask( mask );
    }
    public long get_sa_mask( int signum ) {
	return signals[signum].get_sa_mask( );
    }

    // ── sigaltstack(2): per-thread 代替 signal stack ───────────────────────────
    //   Go runtime は全 signal handler に SA_ONSTACK を立て、各 M (thread) ごとに
    //   sigaltstack で専用 stack を登録する。これを無視すると handler が割込み点の
    //   goroutine stack 上で走り、Go の adjustSignalStack が「foreign stack 上の
    //   signal」と誤認 → needm → lockextra 無限 spin (issue #221 netpoller hang)。
    //   alt_stack[tid] = { ss_sp, ss_size, ss_flags }。
    public static final int SS_ONSTACK = 1;
    public static final int SS_DISABLE = 2;
    private final java.util.concurrent.ConcurrentHashMap<Integer,long[]> alt_stack =
	new java.util.concurrent.ConcurrentHashMap<>();
    public void set_alt_stack( long ss_sp, long ss_size, long ss_flags ) {
	alt_stack.put( current_tid(), new long[]{ ss_sp, ss_size, ss_flags } );
    }
    // 現 thread の登録済 alt stack を返す (未登録 / SS_DISABLE は null)。
    public long[] get_alt_stack( ) {
	long[] as = alt_stack.get( current_tid() );
	if( as == null || (as[2] & SS_DISABLE) != 0 || as[1] == 0 ) return null;
	return as;
    }
    // signal 配信時の handler 開始 RSP base を返す。SA_ONSTACK かつ有効な alt stack が
    //   登録済で、かつ被中断点が既に alt stack 上で「ない」とき alt stack の top を返す。
    //   それ以外は -1 (= 割込み stack を使う) を返す。
    public long sig_alt_stack_base( int sig, long cur_rsp ) {
	if( !has_sa_onstack( sig ) ) return -1L;
	long[] as = get_alt_stack();
	if( as == null ) return -1L;
	long sp = as[0], size = as[1];
	if( cur_rsp >= sp && cur_rsp < sp + size ) return -1L;   // 既に alt stack 上 (nested) は継続
	return sp + size;                                        // alt stack の最上位 (stack は下方成長)
    }

    // シグナルの受信
    public boolean recv( int sig ) {
	return recv( sig, 0, 0L, 0 );   // issue #615: kill 既定 = SI_USER(0) / si_value 0
    }

    // issue #615: siginfo (si_code / si_value / si_pid) 付きの process-wide 受信。
    //   kill は SI_USER(0)、rt_sigqueueinfo(sigqueue) は SI_QUEUE(-1) + si_value を運ぶ。
    //   RT signal (>=SIGRTMIN) は signals[sig].count を ++ してキューイング (合体しない)。
    public boolean recv( int sig, int si_code, long si_value, int si_pid ) {
	if( sig < 0 || sig >= SIGNALS ) return true;
	signals[sig].setSiginfo( si_code, si_value, si_pid );   // 最後の siginfo を保持
	signals[sig].recv( );
	pending_recv_count.incrementAndGet();  // Phase 27 step 24: psig() の fast-path 用
	kick( -1 );   // process-wide pending → 全 vCPU を kick (step 3d-2c-39)
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
