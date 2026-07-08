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
  //   簡易化: 同一 signal 番号に複数の異なる si_value が queue された場合は最後の値のみ保持
  //   (RT の合体しない配送回数は count で正しく数える。値の per-instance FIFO は未対応)。
  int  siCode  = 0;
  long siValue = 0;
  int  siPid   = 0;
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
  public Siginfo duplicate( ) {
    Siginfo siginfo   = new Siginfo( );
    siginfo.count     = count;
    siginfo.mask      = mask;
    siginfo.func_adrs = func_adrs;
    siginfo.sa_flags  = sa_flags;
    siginfo.sa_mask   = sa_mask;
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
  public void cancel( ) {
    count = 0;
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
