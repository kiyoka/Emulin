// ----------------------------------------
//  Siginfo
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;
import emulin.device.*;

// シグナル情報  1シグナル につき 1インスタンスとなる。
public class Siginfo {
  int count;     // シグナル受信カウント数
  boolean mask;  // シグナルマスクフラグ 1=マスク/0=ノンマスク
  // issue #615: 最後に受信した siginfo (SA_SIGINFO ハンドラへ渡す si_code/si_value/si_pid)。
  //   sigqueue(rt_sigqueueinfo) は si_code=SI_QUEUE(-1) + si_value を運ぶ。kill/tgkill は
  //   si_code=SI_USER(0) + si_value=0。配送時に enterSignalHandler が読む。
  //   標準 signal (1..31) は「1 個だけ pending」が正しい挙動なので、この単一スロットが
  //   そのまま仕様に合う。RT signal は下の queue を使う (issue #815)。
  int  siCode  = 0;
  long siValue = 0;
  int  siPid   = 0;

  // issue #815: **リアルタイムシグナルのキューイング**。
  //
  //   POSIX の RT signal (SIGRTMIN..SIGRTMAX) は、ブロック中に同じ signal を n 回送ると
  //   **n 回、送った順 (FIFO) に、それぞれの si_value を伴って**配送される。
  //   旧実装は配送**回数** (count) だけ正しく数え、siginfo は単一スロットに上書きして
  //   いたため、3 回 queue すると 3 回配送されるが**値はすべて最後のもの**になっていた
  //   (= 送り手が載せた情報の消失)。sigqueue(3) で要求を渡すワークキューや、
  //   複数タイマを 1 つの RT signal に載せる POSIX タイマが誤動作する。
  //
  //   ★ 標準 signal (1..31) は合体が正しいので queue を使わない (挙動不変)。
  //   ★ 上限: 無制限に積むと悪意/暴走した guest で JVM が OOM するので上限を設ける。
  //     Linux も RLIMIT_SIGPENDING を超えた sigqueue を EAGAIN で拒否する。上限を超えた
  //     分は queue に積まず、単一スロット (最後の値) にだけ反映する = 旧挙動に縮退する。
  private java.util.ArrayDeque<long[]> siQueue;   // {si_code, si_value, si_pid} の FIFO
  static final int SIQUEUE_MAX = 1024;

  /** RT signal の siginfo を 1 件積む。単一スロットも従来どおり更新する
   *  (queue が空になったときの fallback として使う)。 */
  public synchronized void enqueueSiginfo( int code, long value, int pid ) {
    setSiginfo( code, value, pid );
    if( siQueue == null ) siQueue = new java.util.ArrayDeque<long[]>();
    if( siQueue.size() < SIQUEUE_MAX ) siQueue.addLast( new long[]{ code, value, pid } );
  }

  /** 次に配送する siginfo。queue が空なら単一スロット (標準 signal / 上限超過の縮退)。 */
  public synchronized int  peekSiCode( )  {
    long[] h = ( siQueue == null ) ? null : siQueue.peekFirst();
    return ( h == null ) ? siCode : (int)h[0];
  }
  public synchronized long peekSiValue( ) {
    long[] h = ( siQueue == null ) ? null : siQueue.peekFirst();
    return ( h == null ) ? siValue : h[1];
  }
  public synchronized int  peekSiPid( )   {
    long[] h = ( siQueue == null ) ? null : siQueue.peekFirst();
    return ( h == null ) ? siPid : (int)h[2];
  }

  /** 1 インスタンス配送したので先頭を捨てる。 */
  public synchronized void dequeueSiginfo( ) {
    if( siQueue != null ) siQueue.pollFirst();
  }
  long func_adrs; // シグナルにバインドされた関数のアドレス (x86-64 対応で long)
  long sa_flags;  // sigaction の sa_flags (SA_RESTART 等)
  long sa_mask;   // Phase 27 step 27: sigaction.sa_mask (signal handler 進入時に
                  //   追加で block する signal の bitmap、bit 0 = SIGHUP)
  static long SIG_DFL  = 0L;  // func_adrsが 0 なら SIG_DFLとみなす
  static long SIG_IGN  = 1L;  // func_adrsが 1 なら SIG_IGNとみなす
  // sa_flags ビット (Linux x86-64)
  public static final long SA_SIGINFO = 0x00000004L;
  public static final long SA_ONSTACK = 0x08000000L;  // handler を sigaltstack(2) の代替 stack で走らせる
  public static final long SA_RESTART = 0x10000000L;
  public static final long SA_NODEFER = 0x40000000L;  // 配信中の signal 自身を mask しない

  public Siginfo( ) {
    count = 0;
    mask  = false;
  }

  // 自分のコピーを返す。
  public synchronized Siginfo duplicate( ) {
    Siginfo siginfo   = new Siginfo( );
    siginfo.count     = count;
    siginfo.mask      = mask;
    siginfo.func_adrs = func_adrs;
    siginfo.sa_flags  = sa_flags;
    siginfo.sa_mask   = sa_mask;
    // issue #815: count を引き継ぐ以上、対応する siginfo の queue も引き継がないと
    //   「配送回数はあるのに値が無い」状態になる (count と queue の対応が崩れる)。
    siginfo.siCode  = siCode;
    siginfo.siValue = siValue;
    siginfo.siPid   = siPid;
    if( siQueue != null && !siQueue.isEmpty() ) {
      siginfo.siQueue = new java.util.ArrayDeque<long[]>();
      for( long[] e : siQueue ) siginfo.siQueue.addLast( new long[]{ e[0], e[1], e[2] } );
    }
    return( siginfo );
  }

  // シグナルの受信
  public void recv( ) {
    count++;
  }

  // issue #615: siginfo 付きで受信 (rt_sigqueueinfo / kill / tgkill)。
  public void setSiginfo( int code, long value, int pid ) {
    siCode  = code;
    siValue = value;
    siPid   = pid;
  }

  // issue #615: RT signal の配送で 1 インスタンスだけ消費する (合体しない)。
  public void consumeOne( ) {
    if( count > 0 ) count--;
  }

  // シグナルの受信回数を返す
  public int get_count( ) {
    return( count );
  }

  // シグナルのマスク
  public void mask( boolean _mask ) {
    mask = _mask;
  }

  // シグナルのマスクされているか？
  public boolean isMask( ) {
    return( mask );
  }

  // シグナルハンドラ関数のアドレスを返す
  public long get_func_adrs( ) {
    return( func_adrs );
  }

  // シグナルのキャンセル
  public synchronized void cancel( ) {
    count = 0;
    if( siQueue != null ) siQueue.clear();   // issue #815: pending を捨てるので値も捨てる
  }

  // シグナル関数の登録
  public void set_sigaction( long _func_adrs ) {
    func_adrs = _func_adrs;
  }

  public void set_sa_flags( long _flags ) { sa_flags = _flags; }
  public long get_sa_flags( ) { return sa_flags; }
  public boolean has_sa_restart( ) { return ( sa_flags & SA_RESTART ) != 0; }
  public boolean has_sa_siginfo( ) { return ( sa_flags & SA_SIGINFO ) != 0; }
  public boolean has_sa_nodefer( ) { return ( sa_flags & SA_NODEFER ) != 0; }
  public boolean has_sa_onstack( ) { return ( sa_flags & SA_ONSTACK ) != 0; }
  public void set_sa_mask( long _mask ) { sa_mask = _mask; }
  public long get_sa_mask( ) { return sa_mask; }
}
