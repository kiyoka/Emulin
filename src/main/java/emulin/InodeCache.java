// ----------------------------------------
//  InodeCache ( stat 属性のプロセス横断キャッシュ )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

// issue #701: fork+exec のたびに ld.so が同じ共有ライブラリ群を openat/stat し、
//   Inode 構築 (file.exists + readAttributes(unix:*) + CygMode xattr) が
//   1 spawn あたり ~50ms (DrvFs/NTFS) を占めていた。stat 結果 (存在/非存在の
//   負キャッシュ込み) を native path キーで TTL 付きキャッシュし host stat を省く。
//
// 整合性の設計:
//   - guest 内の全変更操作が invalidate を呼ぶ: 書き込み open/close (Fileinfo)、
//     unlink/rename/mkdir (FileAccess)、chmod/ftruncate (Syscall)、utimens/
//     link/symlink/AF_UNIX bind (SyscallAmd64)、sshd env write (Kernel)。
//     invalidate は「変更が host に反映された後」に呼ぶこと (先に呼ぶと、変更前に
//     始まった並行 stat が変更前 snapshot を invalidate 後に store し stale が残る)。
//   - 書き込み open 中 (open("rw") 〜 last close) は writeCount > 0 の間
//     lookup も store も抑止する → write 直後の (f)stat が常に fresh を読む。
//   - store は「read 開始時刻より後に invalidate が無い」ときだけ許す
//     (lastInval / lastGlobalInval との比較)。
//   - emulator 外 (host 側) の変更は検知できない → TTL (既定 2000ms) で bound。
//     dentry cache (CygSymlink.readCached, TTL 2s) と同じ割り切り。
//   - EMULIN_INODE_TTL_MS で調整、0 で完全無効化 (トラブル時の切り分け用)。
public class InodeCache
{
  private static final long TTL_NANOS;
  static {
    long ms = 2000;
    try {
      String s = System.getenv( "EMULIN_INODE_TTL_MS" );
      if( s != null ) ms = Long.parseLong( s );
    } catch( Exception ignored ) { }
    TTL_NANOS = ms * 1_000_000L;
  }
  private static final int CACHE_MAX = 65536;   // dentry cache (DENTRY_MAX) と同じ上限

  // ---- issue #745 診断トレース (既定 off) --------------------------------
  //   EMULIN_TRACE_INODE_PATH=<部分文字列>  この文字列を含む native path だけ記録。
  //   EMULIN_TRACE_INODE_OUT=<host file>    出力先 (既定 emulin-inode-trace.log)。
  //   claude(TUI) の下で走らせるため stderr ではなく file に追記する。
  //   記録するのは lookup の採否と配った size / store の採否と「host が返した生の
  //   size」/ 書き込み open-close / invalidate / sweep。thread 名も出す
  //   (Bun は libuv thread pool から検証 stat を撃つため、どのスレッドが stale を
  //   store しどのスレッドが hit したかが機序の判定に直結する)。
  private static final String TRACE_PAT = System.getenv( "EMULIN_TRACE_INODE_PATH" );
  private static final long   TRACE_T0  = System.nanoTime( );
  private static java.io.PrintStream traceOut;

  private static boolean tracing( String nat ) {
    return TRACE_PAT != null && nat != null && nat.contains( TRACE_PAT );
  }

  private static synchronized void trace( String ev, String nat, String detail ) {
    if( traceOut == null ) {
      String f = System.getenv( "EMULIN_TRACE_INODE_OUT" );
      if( f == null ) f = "emulin-inode-trace.log";
      try {
        traceOut = new java.io.PrintStream(
          new java.io.FileOutputStream( f, true ), true, "UTF-8" );
      }
      catch( Exception e ) { traceOut = System.err; }
    }
    traceOut.printf( "[%10.3f] %-18s %-11s %s  %s%n",
                     ( System.nanoTime( ) - TRACE_T0 ) / 1e6,
                     Thread.currentThread( ).getName( ), ev, detail, nat );
  }

  // stat snapshot (immutable)。store 時に Inode からコピーし、hit 時に Inode へ書き戻す。
  //   st_ino は fileKey または native path hash 由来 (issue #598) で vpath 非依存なので
  //   そのままコピーできる。
  static final class Entry {
    final boolean exists;
    final int     st_ino;
    final short   st_mode;
    final short   st_nlink;
    final long    st_size;
    final long    st_atime, st_mtime, st_ctime;
    final long    st_atime_nsec, st_mtime_nsec, st_ctime_nsec;
    final long    storedAt;

    Entry( Inode ino, boolean _exists ) {
      exists         = _exists;
      st_ino         = ino.st_ino;
      st_mode        = ino.st_mode;
      st_nlink       = ino.st_nlink;
      st_size        = ino.st_size;
      st_atime       = ino.st_atime;
      st_mtime       = ino.st_mtime;
      st_ctime       = ino.st_ctime;
      st_atime_nsec  = ino.st_atime_nsec;
      st_mtime_nsec  = ino.st_mtime_nsec;
      st_ctime_nsec  = ino.st_ctime_nsec;
      storedAt       = System.nanoTime( );
    }

    // hit 時: host stat 0 回で Inode を構成する (update_info の cache 版)。
    //   uid/gid/blksize は host I/O 無しで sysinfo から毎回取る (update_info と同じ)。
    void fill( Inode ino, Sysinfo sysinfo ) {
      ino.st_ino     = st_ino;
      ino.st_mode    = st_mode;
      ino.st_nlink   = st_nlink;
      ino.st_size    = st_size;
      ino.st_blocks  = ( st_size + 511L ) / 512L;
      ino.st_atime   = st_atime;
      ino.st_mtime   = st_mtime;
      ino.st_ctime   = st_ctime;
      ino.st_atime_nsec = st_atime_nsec;
      ino.st_mtime_nsec = st_mtime_nsec;
      ino.st_ctime_nsec = st_ctime_nsec;
      ino.st_uid     = (short)sysinfo.file_uid( );
      ino.st_gid     = (short)sysinfo.file_gid( );
      ino.st_blksize = sysinfo.get_block_size( );
      // st_dev / st_rdev は常に 0 (Inode の field 既定値のまま)
    }
  }

  // 変更追跡: native path → { 書き込み open 中の数, 最終 invalidate 時刻 }。
  //   sweep (容量超過時の間引き) と open の競合は dead flag + 取り直しで防ぐ。
  private static final class PathState {
    int     writeCount;
    long    lastInval;
    boolean dead;
  }

  private static final java.util.concurrent.ConcurrentHashMap<String,Entry> cache
    = new java.util.concurrent.ConcurrentHashMap<>( );
  private static final java.util.concurrent.ConcurrentHashMap<String,PathState> states
    = new java.util.concurrent.ConcurrentHashMap<>( );
  // directory rename の prefix invalidate 用 (rare)。これ以前に始まった read は store 不可。
  //   nanoTime は原点任意なので差分比較のみ有効 — 初期値も nanoTime 基準にする
  //   (Long.MIN_VALUE だと readStart との減算が wrap して全 store が棄却される)。
  private static volatile long lastGlobalInval = System.nanoTime( ) - 1;

  // 属性 snapshot を引く。無し / TTL 切れ / 書き込み open 中は null (= host を読む)。
  static Entry lookup( String nat ) {
    if( TTL_NANOS <= 0 ) return null;
    boolean tr = tracing( nat );
    Entry e = cache.get( nat );
    if( e == null ) {
      if( tr ) trace( "lookup-MISS", nat, "" );
      return null;
    }
    long age = System.nanoTime( ) - e.storedAt;
    if( age > TTL_NANOS ) {
      cache.remove( nat, e );
      if( tr ) trace( "lookup-EXPIRED", nat, String.format( "size=%d age=%.1fms", e.st_size, age / 1e6 ) );
      return null;
    }
    PathState s = states.get( nat );
    if( s != null ) {
      synchronized( s ) {
        if( s.writeCount > 0 ) {
          if( tr ) trace( "lookup-WSUP", nat, "wcount=" + s.writeCount );
          return null;
        }
      }
    }
    // ★ここで配った size がそのまま guest の stat になる (stale なら #745 の警告)
    if( tr ) trace( "lookup-HIT", nat, String.format( "size=%d age=%.1fms mtime=%d.%09d",
                                                      e.st_size, age / 1e6, e.st_mtime, e.st_mtime_nsec ) );
    return e;
  }

  // issue #701: mmap read cache 等が「今の size/mtime」を host I/O ゼロで得るための窓口。
  //   fresh な正エントリがあり書き込み open 中でなければ {size, mtime_ms} を返す。無ければ null
  //   (呼び出し側は cache を使わず従来経路へ)。
  static long[] peekSizeMtimeMs( String nat ) {
    Entry e = lookup( nat );
    if( e == null || !e.exists ) return null;
    return new long[]{ e.st_size, e.st_mtime * 1000L + e.st_mtime_nsec / 1_000_000L };
  }

  // 属性 snapshot を格納する。read 開始 (readStart) より後に invalidate /
  //   書き込み open があった path は store しない (stale 書き戻し防止)。
  static void store( String nat, long readStart, Inode ino, boolean exists ) {
    if( TTL_NANOS <= 0 ) return;
    boolean tr = tracing( nat );
    // ★host が返した生の値。write 直後にここが古ければ host (NTFS) 由来、
    //   古くないのに lookup-HIT が古ければ stale 書き戻し。
    String raw = tr ? String.format( "hostsize=%d exists=%b win=%.1fms",
                                     exists ? ino.st_size : -1, exists,
                                     ( System.nanoTime( ) - readStart ) / 1e6 )
                    : null;
    if( readStart - lastGlobalInval <= 0 ) {
      if( tr ) trace( "store-REJ-global", nat, raw );
      return;
    }
    PathState s = states.get( nat );
    if( s != null ) {
      synchronized( s ) {
        if( s.writeCount > 0 ) {
          if( tr ) trace( "store-REJ-wcount", nat, raw + " wcount=" + s.writeCount );
          return;
        }
        if( s.lastInval - readStart >= 0 ) {
          if( tr ) trace( "store-REJ-inval", nat,
                          raw + String.format( " inval-after-readStart=%.1fms",
                                               ( s.lastInval - readStart ) / 1e6 ) );
          return;
        }
      }
    }
    else if( tr ) {
      // PathState が無い = 一度も write/invalidate していない、または sweep で捨てられた。
      //   後者なら lastInval による stale 書き戻し防御が消えている (候補機序 2)。
      trace( "store-NOSTATE", nat, raw );
    }
    if( cache.size( ) >= CACHE_MAX ) cache.clear( );  // dentry cache と同じ全 clear
    cache.put( nat, new Entry( ino, exists ) );
    if( tr ) trace( "store-OK", nat, raw );
  }

  // 書き込み open (Fileinfo.open mode="rw")。open〜last close の間 lookup/store を抑止。
  static void noteWriteOpen( String nat ) {
    if( TTL_NANOS <= 0 ) return;
    for( ;; ) {
      PathState s = states.computeIfAbsent( nat, k -> new PathState( ) );
      synchronized( s ) {
        if( !s.dead ) {
          s.writeCount++; s.lastInval = System.nanoTime( );
          if( tracing( nat )) trace( "writeOpen", nat, "wcount=" + s.writeCount );
          break;
        }
      }
      // sweep に除去された瞬間の PathState を掴んだ → 取り直す
    }
    cache.remove( nat );
    maybeSweepStates( );
  }

  // 書き込み fd の last close。writeCount を戻し、書き終わった属性を読み直させる。
  static void noteWriteClose( String nat ) {
    if( TTL_NANOS <= 0 ) return;
    PathState s = states.get( nat );
    if( s != null ) {
      synchronized( s ) {
        if( s.writeCount > 0 ) s.writeCount--;
        s.lastInval = System.nanoTime( );
        if( tracing( nat )) trace( "writeClose", nat, "wcount=" + s.writeCount );
      }
    }
    else if( tracing( nat )) trace( "writeClose-NOSTATE", nat, "" );
    cache.remove( nat );
  }

  // 変更操作 (unlink/rename/chmod/utimens/...) の後に呼ぶ。
  static void invalidate( String nat ) {
    if( TTL_NANOS <= 0 ) return;
    for( ;; ) {
      PathState s = states.computeIfAbsent( nat, k -> new PathState( ) );
      synchronized( s ) {
        if( !s.dead ) {
          s.lastInval = System.nanoTime( );
          if( tracing( nat )) trace( "invalidate", nat, "" );
          break;
        }
      }
    }
    cache.remove( nat );
    maybeSweepStates( );
  }

  // 自身 + 親 directory (mtime / nlink が変わる) を invalidate する。
  //   entry 作成/削除系 (unlink/rename/mkdir/link/symlink/creat) 用。
  static void invalidateWithParent( String nat ) {
    if( TTL_NANOS <= 0 ) return;
    invalidate( nat );
    String parent = new java.io.File( nat ).getParent( );
    if( parent != null ) invalidate( parent );
  }

  // directory rename 用: 旧/新 prefix 配下の entry を全て落とす。rare 前提の O(n)。
  //   in-flight read の store は lastGlobalInval で全体的に抑止する。
  static void invalidatePrefix( String prefix ) {
    if( TTL_NANOS <= 0 ) return;
    lastGlobalInval = System.nanoTime( );
    cache.keySet( ).removeIf( k -> k.startsWith( prefix ) );
  }

  // states の容量制御: 書き込み open 中 (writeCount > 0) は残して間引く。
  private static void maybeSweepStates( ) {
    if( states.size( ) <= CACHE_MAX ) return;
    int dropped = 0;
    boolean droppedTraced = false;
    for( java.util.Iterator<java.util.Map.Entry<String,PathState>> it
           = states.entrySet( ).iterator( ); it.hasNext( ); ) {
      java.util.Map.Entry<String,PathState> me = it.next( );
      PathState s = me.getValue( );
      synchronized( s ) {
        if( s.writeCount == 0 ) {
          s.dead = true; it.remove( ); dropped++;
          // ★ここで lastInval を捨てるため、これ以降 in-flight read の stale
          //   書き戻し防御が効かなくなる (候補機序 2 の窓)。
          if( tracing( me.getKey( ) )) { trace( "sweep-DROP", me.getKey( ), "" ); droppedTraced = true; }
        }
      }
    }
    if( TRACE_PAT != null )
      trace( "sweep", "-", "dropped=" + dropped + " remain=" + states.size( )
             + ( droppedTraced ? " (TRACED PATH DROPPED)" : "" ));
  }
}
