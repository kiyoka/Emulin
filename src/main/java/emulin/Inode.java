// ----------------------------------------
//  Inode ( Inode Infomation )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//  Info : 
//   本クラスのメソッドは全て,仮想パスを受け取る
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class Inode
{
  // File types.
  static short __S_IFDIR = (short)0x4000;	/* Directory.  */
  static short __S_IFREG = (short)0x8000;	/* Regular file.  */
  // static short S_IRWXU = (short)0x0700;
  static short S_IRUSR = (short)0x0100;
  static short S_IWUSR = (short)0x0080;
  static short S_IXUSR = (short)0x0040;
  // static short S_IRWXG = (short)0x0070;
  static short S_IRGRP = (short)0x0020;
  static short S_IWGRP = (short)0x0010;
  static short S_IXGRP = (short)0x0008;
  // static short S_IRWXO = (short)0x0007;
  static short S_IROTH = (short)0x0004;
  static short S_IWOTH = (short)0x0002;
  static short S_IXOTH = (short)0x0001;

  File  file;

  short st_dev;
  int   st_ino;
  short st_mode;
  short st_nlink;
  short st_uid;
  short st_gid;
  short st_rdev;
  long  st_size;     // Phase 27 step 22: int → long (>2GB ファイル対応)
  int   st_blksize;
  long  st_blocks;   // 512 byte 単位の実使用ブロック数
  long  st_atime;    // Phase 27 step 22: int → long (Y2038 対応)
  long  st_mtime;
  long  st_ctime;
  long  st_atime_nsec;  // Phase 27 step 22: ナノ秒部 (make/ninja の変更検知)
  long  st_mtime_nsec;
  long  st_ctime_nsec;

  public Inode( String vpath, Sysinfo sysinfo ) {
    String path = sysinfo.get_native_path( vpath );
    file = new File( path );
    //    System.out.println( " Inode.Inode( " + vpath + " , )  path = " + path );
    if( file.exists( )) {
      update_info( vpath, path, sysinfo );
    }
  }

  private boolean update_info( String vpath, String path, Sysinfo sysinfo ) {
    // BasicFileAttributes は 1 回だけ読み、st_ino と atime/mtime で共用する
    //   (issue #517: utimensat が設定した atime を stat が読み返すため。
    //    従来は atime=mtime=lastModified で atime 非モデルだった)。
    java.nio.file.attribute.BasicFileAttributes battrs = null;
    try {
      battrs = java.nio.file.Files.readAttributes( java.nio.file.Paths.get( path ),
          java.nio.file.attribute.BasicFileAttributes.class );
    } catch( Exception ignored ) { }
    st_dev     = 0;                          // ファイルが存在するデバイス番号( なんでもよいはず )
    st_ino     = get_host_ino( battrs, vpath );// オンディスク inode 番号
                                             // (host の実 inode を反映、hardlink で同値)
    st_mode    = get_st_mode( vpath, path );  // ファイルモード (path = 解決済 native)
    st_nlink   = get_host_nlink( path );     // issue #443: host の実 nlink (dir=2+サブディレクトリ, hardlink 追跡)。取得不可(Windows等)は 1
    st_uid     = (short)sysinfo.file_uid( ); // ユーザー ID
    st_gid     = (short)sysinfo.file_gid( ); // グループ ID
    st_rdev    = 0;                          // 常に 0 ( デバイスファイルは扱わない )
    st_size    = file.length( );             // ファイルバイト数 (long)
    st_blksize = sysinfo.get_block_size( );  // ブロックサイズ
    // st_blocks は 512 byte 単位の実使用ブロック数。du / cp -a 等が読む。
    // ホスト FS の実際の使用量は Java では取れないので size 切り上げで近似。
    st_blocks  = (st_size + 511L) / 512L;
    if( battrs != null ) {
      long at_ns = battrs.lastAccessTime( ).to( java.util.concurrent.TimeUnit.NANOSECONDS );
      long mt_ns = battrs.lastModifiedTime( ).to( java.util.concurrent.TimeUnit.NANOSECONDS );
      st_atime = Math.floorDiv( at_ns, 1_000_000_000L );
      st_atime_nsec = Math.floorMod( at_ns, 1_000_000_000L );
      st_mtime = Math.floorDiv( mt_ns, 1_000_000_000L );
      st_mtime_nsec = Math.floorMod( mt_ns, 1_000_000_000L );
    } else {
      // 取得失敗 (Windows 等) は従来通り lastModified に fallback
      long ms = file.lastModified( );
      st_atime   = ms / 1000L;
      st_mtime   = st_atime;
      long nsec  = (ms % 1000L) * 1_000_000L;  // ms → nsec
      st_atime_nsec = nsec;
      st_mtime_nsec = nsec;
    }
    st_ctime = st_mtime;
    st_ctime_nsec = st_mtime_nsec;
    return( true );
  }

  // host の実 inode 番号を取得する (Phase 28-3l)。
  //   Phase 27 step 10 の path.hashCode() ベースは hardlink で異なる値を
  //   返してしまい、git clone --hardlinks file:// が「link 後の stat で
  //   src と dest の inode 一致」検証に失敗していた。
  //   Java NIO BasicFileAttributes.fileKey() は Unix で UnixFileKey
  //   (= dev + ino) を返し、hardlink は同じオブジェクトになる。hashCode で
  //   int に落とすと、(a) hardlink で同値、(b) 異 file は path.hashCode 同等
  //   の衝突確率、を両立できる。
  //   失敗時 (key=null、Windows host、permission 不足等) は path.hashCode に
  //   fallback (= 旧挙動)。
  private int get_host_ino( java.nio.file.attribute.BasicFileAttributes attrs, String vpath ) {
    try {
      if( attrs != null ) {
        Object key = attrs.fileKey();
        if( key != null ) return key.hashCode();
      }
    } catch( Exception ignored ) { }
    return vpath.hashCode();
  }

  // issue #443: host FS の実 st_nlink を返す。ディレクトリは 2+サブディレクトリ数
  //   (find の leaf 最適化が正しく働く)、hardlink は共有カウントを反映する。
  //   unix:nlink 非対応 (Windows 等) や失敗時は 1 にフォールバック。
  private short get_host_nlink( String native_path ) {
    try {
      Object v = java.nio.file.Files.getAttribute(
          java.nio.file.Paths.get( native_path ), "unix:nlink",
          java.nio.file.LinkOption.NOFOLLOW_LINKS );
      if( v instanceof Integer ) return (short)(int)(Integer)v;
    } catch( Exception ignored ) { }
    return 1;
  }

  private short get_st_mode( String pathname, String native_path ) {
    short v = 0;
    // ファイルタイプの解析
    if( file.isDirectory( ) ) {
      v |= (short)__S_IFDIR;
    }
    else if( file.isFile( )) {
      v |= (short)__S_IFREG;
    }

    // issue #68 Phase 2: Cygwin mode では chmod が xattr に保存した mode を
    //   優先して読む (NTFS は POSIX 9-bit を保持しないため)。xattr が
    //   無ければ従来の PosixFilePermissions / canRead fallback。
    if( CygMode.enabled() && native_path != null ) {
      int m = CygMode.getMode( native_path );
      if( m >= 0 ) {
        return (short)( v | (m & 07777) );
      }
    }

    // issue #517: unix view の "mode" 属性なら suid/sgid/sticky 含む
    //   12 bit が読める (do_chmod の unix:mode 設定と対)。file type bit は上で
    //   計算済みなので 07777 だけ合成。非対応 host は従来の 9 bit 経路へ。
    try {
      Object m = java.nio.file.Files.getAttribute( file.toPath( ), "unix:mode" );
      if( m instanceof Integer ) {
        return (short)( v | ( ((Integer)m).intValue( ) & 07777 ) );
      }
    } catch( Exception e ) { /* fallback */ }

    // POSIX permissions: 可能なら 9 bit を実ファイルから読む
    short perms = 0;
    boolean got_perms = false;
    try {
      java.nio.file.Path p = file.toPath( );
      java.util.Set<java.nio.file.attribute.PosixFilePermission> set
        = java.nio.file.Files.getPosixFilePermissions( p );
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OWNER_READ )    ) perms |= 0400;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OWNER_WRITE )   ) perms |= 0200;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE ) ) perms |= 0100;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.GROUP_READ )    ) perms |= 0040;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.GROUP_WRITE )   ) perms |= 0020;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE ) ) perms |= 0010;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OTHERS_READ )   ) perms |= 0004;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE )  ) perms |= 0002;
      if( set.contains( java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE )) perms |= 0001;
      got_perms = true;
    } catch( UnsupportedOperationException | java.io.IOException e ) { /* fallback */ }

    if( got_perms ) {
      v |= perms;
    } else {
      // フォールバック: canRead/canWrite だけ反映
      short rv = (short)(S_IRUSR | S_IRGRP | S_IROTH);
      short wv = (short)(S_IWUSR);
      short xv = (short)(S_IXUSR | S_IXGRP | S_IXOTH);
      if( file.isDirectory( ) || file.isFile( )) v |= xv;
      if( file.canRead( ))  v |= rv;
      if( file.canWrite( )) v |= (short)(rv | wv);
    }
    return( v );
  }

  // ディレクトリパーミッションありか？
  public boolean isDirectory( ) {
    boolean ret = false;
    if( 0 != ( st_mode & __S_IFDIR )) {
      ret = true;
    }
    return( ret );
  }

  // リードパーミッションありか？
  public boolean isReadable( ) {
    boolean ret = false;
    if( 0 != ( st_mode & S_IRUSR )) {
      ret = true;
    }
    return( ret );
  }

  // リードパーミッションありか？
  public boolean isWritable( ) {
    boolean ret = false;
    if( 0 != ( st_mode & S_IWUSR )) {
      ret = true;
    }
    return( ret );
  }

  // 実行 / ディレクトリ search パーミッションありか？
  // X_OK は file は execute、dir は search の意味。emacs の
  // file-directory-p は内部的に access(F_OK|X_OK) で「dir として開ける
  // か」を試すので、regular file の +x なし mode 0644 で X_OK を silent
  // 成功にすると emacs が file を directory と誤認する (Phase 29-D)。
  public boolean isExecutable( ) {
    boolean ret = false;
    if( 0 != ( st_mode & S_IXUSR )) {
      ret = true;
    }
    return( ret );
  }

  // ファイルが存在しているか？
  public boolean isExists( ) {
    return( file.exists( ));
  }
}
