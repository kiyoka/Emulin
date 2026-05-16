// ----------------------------------------
//  Emulin Pty Manager
//
//  issue #41 Phase 2: /dev/ptmx と /dev/pts/N の対応関係を Kernel-wide で
//  管理する。sshd が親プロセスで /dev/ptmx を open して master fd を取り、
//  ioctl(TIOCGPTN) で ptn 番号を得る。fork した子プロセスは別途
//  /dev/pts/N を open する。両者は同じ pty pair を指す必要があるため、
//  ptn 番号と「master 側 pipe_no / slave 側 pipe_no」のマップを Kernel
//  共有で持つ。
//
//  実装: socketpair と同じ仕組みで 2 つの Pipe (a, b) を用意する。
//    master fd: read from b, write to a
//    slave fd : read from a, write to b
//  PtyManager は ptn → (pipe_a, pipe_b) を保持する。allocate() で新しい
//  ptn を発行。open_slave(ptn) で pipe_a/pipe_b を取り出し、Fileinfo に
//  pipe_pair を設定する。
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.util.HashMap;
import java.util.Map;

public class PtyManager {
  public static class PtyPair {
    public int pipe_a;  // master → slave 方向 (master writes, slave reads)
    public int pipe_b;  // slave → master 方向
    public PtyPair( int a, int b ) { pipe_a = a; pipe_b = b; }
  }

  private final Map<Integer,PtyPair> pairs = new HashMap<>();
  private int next_ptn = 0;

  // 新規 pty pair を作る。pipe_a / pipe_b は呼び出し側で connect_pipe() して渡す。
  public synchronized int register( int pipe_a, int pipe_b ) {
    int ptn = next_ptn++;
    pairs.put( ptn, new PtyPair( pipe_a, pipe_b ) );
    return ptn;
  }

  public synchronized PtyPair get( int ptn ) {
    return pairs.get( ptn );
  }

  // /dev/pts/N の N (整数) を path から抽出。"/dev/pts/0" → 0。
  //   失敗時は -1。
  public static int parse_slave_path( String path ) {
    if( path == null ) return -1;
    final String prefix = "/dev/pts/";
    if( !path.startsWith( prefix ) ) return -1;
    String tail = path.substring( prefix.length() );
    try { return Integer.parseInt( tail ); }
    catch( NumberFormatException e ) { return -1; }
  }
}
