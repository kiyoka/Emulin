// ----------------------------------------
//  File-backed VA range set (issue #403)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: madvise(MADV_DONTNEED / MADV_FREE) が「file-backed な page」をゼロ化しない
//   ようにするため、file 由来の内容を持つ VA 範囲を追跡する。
//
// 背景 (#403): 実 Linux の MADV_DONTNEED は、対象が anonymous なら次アクセスで
//   zero page に再フォールトし、file-backed (MAP_PRIVATE/SHARED の fd>=0 や ELF
//   PT_LOAD) なら **file 内容に再フォールト** する。emulin には file 再フォールト
//   機構が無く、madvise の実装 (SyscallAmd64.amd64_madvise、issue #113 で導入) は
//   対象 page を一律 bulkZero している。anonymous には正しい (#113 pthread stack
//   cache の再利用) が、file-backed page まで zero 化してしまう。
//
//   Bun/V8 (claude) は ELF に埋め込んだ JS ソースを PT_LOAD として mmap したまま
//   parse 後に zero-copy 文字列 view を保持し、要らなくなった領域を
//   madvise(DONTNEED) で decommit する。require() 解決時にその view を deref する
//   と emulin では zero 済みになっており module 名が garbage → ENOENT で起動失敗
//   していた (#403)。
//
// 本クラスは file-backed な範囲を base→end (exclusive) の **非重複** interval 集合
//   として保持する。add で隣接/重複を畳み、remove は overlap する interval を
//   head/tail に分割保持する (munmap で部分解除しても残りが file-backed のまま)。
//   madvise はゼロ化前に contains() を引き、true の page は skip する。
//
// 登録元: (a) amd64_mmap の fd>=0 経路 (.so / data file mmap)
//        (b) NativeCpuBackend.connect_devices の PT_LOAD copy (本体 ELF の file 部)
// 解除元: sys_munmap / anonymous mmap (MAP_FIXED で file-backed を anon に置換)。
//
// thread 安全: pthread から mmap/munmap/madvise が並行に来るので全メソッド
//   synchronized。範囲数は (PT_LOAD 数 + mmap した .so 数) 程度で小さく、走査は
//   TreeMap の対数オーダなので madvise (hot path ではない) で十分速い。
package emulin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

final class FileBackedRanges {

  // base (VA) -> end (VA, exclusive)。非重複・非隣接になるよう add で正規化する。
  private final TreeMap<Long,Long> ranges = new TreeMap<>();

  /** [addr, addr+len) を file-backed として登録する (隣接/重複は畳む)。 */
  synchronized void add( long addr, long len ) {
    if( len <= 0 ) return;
    long lo = addr;
    long hi = addr + len;
    if( hi <= lo ) return;                       // overflow / 不正 guard
    // lo を跨ぐ/隣接する floor entry を吸収。
    Map.Entry<Long,Long> f = ranges.floorEntry( lo );
    if( f != null && f.getValue() >= lo ) {
      lo = Math.min( lo, f.getKey() );
      hi = Math.max( hi, f.getValue() );
      ranges.remove( f.getKey() );
    }
    // [lo,hi] に開始が入る (= 隣接含む) 後続 entry を畳む。
    while( true ) {
      Map.Entry<Long,Long> c = ranges.ceilingEntry( lo );
      if( c == null || c.getKey() > hi ) break;
      hi = Math.max( hi, c.getValue() );
      ranges.remove( c.getKey() );
    }
    ranges.put( lo, hi );
  }

  /** 別集合の全 interval を取り込む (fork 子へ親の file-backed 範囲を複製、#403)。 */
  synchronized void copyFrom( FileBackedRanges other ) {
    if( other == null || other == this ) return;
    Map<Long,Long> snap;
    synchronized( other ) { snap = new TreeMap<>( other.ranges ); }   // deadlock 回避にコピーを取ってから put
    ranges.putAll( snap );
  }

  /** addr が file-backed 範囲に含まれるか。 */
  synchronized boolean contains( long addr ) {
    Map.Entry<Long,Long> e = ranges.floorEntry( addr );
    return e != null && addr < e.getValue();
  }

  /** [addr, addr+len) を file-backed 集合から除去する (overlap は head/tail に分割保持)。 */
  synchronized void remove( long addr, long len ) {
    if( len <= 0 || ranges.isEmpty() ) return;
    long lo = addr;
    long hi = addr + len;
    if( hi <= lo ) return;
    // lo を含み得る floor から走査開始 (floor が lo を跨ぐ interval かもしれない)。
    Long startKey = ranges.floorKey( lo );
    SortedMap<Long,Long> tail = ranges.tailMap( startKey != null ? startKey : lo );
    List<Long>   toRemove = new ArrayList<>();
    List<long[]> toAdd    = new ArrayList<>();
    for( Map.Entry<Long,Long> e : tail.entrySet() ) {
      long b  = e.getKey();
      long en = e.getValue();
      if( b >= hi ) break;          // これ以降の interval は [lo,hi) と重ならない
      if( en <= lo ) continue;      // lo より前で終わる interval は無関係
      toRemove.add( b );
      if( b  < lo ) toAdd.add( new long[]{ b,  lo } );   // head 残し
      if( en > hi ) toAdd.add( new long[]{ hi, en } );   // tail 残し
    }
    for( Long k : toRemove ) ranges.remove( k );
    for( long[] a : toAdd )  ranges.put( a[0], a[1] );
  }
}
