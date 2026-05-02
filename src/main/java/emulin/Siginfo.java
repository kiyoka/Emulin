// ----------------------------------------
//  Siginfo
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/07 12:57:06 $ 
//  $Id: Siginfo.java,v 1.3 2000/01/07 12:57:06 kiyoka Exp $
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
  long func_adrs; // シグナルにバインドされた関数のアドレス (x86-64 対応で long)
  long sa_flags;  // sigaction の sa_flags (SA_RESTART 等)
  static long SIG_DFL  = 0L;  // func_adrsが 0 なら SIG_DFLとみなす
  static long SIG_IGN  = 1L;  // func_adrsが 1 なら SIG_IGNとみなす
  // sa_flags ビット (Linux x86-64)
  public static final long SA_SIGINFO = 0x00000004L;
  public static final long SA_RESTART = 0x10000000L;

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
    return( siginfo );
  }

  // シグナルの受信
  public void recv( ) {
    count++;
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
}
