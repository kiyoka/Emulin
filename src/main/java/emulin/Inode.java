// ----------------------------------------
//  Inode ( Inode Infomation )
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/04/13 17:39:47 $ 
//  $Id: Inode.java,v 1.15 1999/04/13 17:39:47 kiyoka Exp $
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
  int   st_size;
  int   st_blksize;
  int   st_blocks;
  int   st_atime;
  int   st_mtime;
  int   st_ctime;

  public Inode( String vpath, Sysinfo sysinfo ) {
    String path = sysinfo.get_native_path( vpath );
    file = new File( path );
    //    System.out.println( " Inode.Inode( " + vpath + " , )  path = " + path );
    if( file.exists( )) {
      update_info( vpath, path, sysinfo );
    }
  }

  private boolean update_info( String vpath, String path, Sysinfo sysinfo ) {
    st_dev     = 0;                          // ファイルが存在するデバイス番号( なんでもよいはず )
    st_ino     = get_uniq_no( vpath );       // オンディスク inode 番号( ユニークであればよい )    
    st_mode    = get_st_mode( vpath );       // ファイルモード
    st_nlink   = 1;                          // 常に 1 ( シンボリックリンクは認識しない )
    st_uid     = (short)sysinfo.file_uid( ); // ユーザー ID
    st_gid     = (short)sysinfo.file_gid( ); // グループ ID
    st_rdev    = 0;                          // 常に 0 ( デバイスファイルは扱わない )
    st_size    = (int)file.length( );        // ファイルバイト数
    st_blksize = sysinfo.get_block_size( );  // ブロックサイズ
    st_blocks  = 0;                          // 固定
    st_atime   = (int)(file.lastModified( )/1000L);    // 更新時間
    st_mtime   = st_atime;
    st_ctime   = st_mtime;
    return( true );
  }

  // パス名ごとにユニークな番号を生成する。
  //   旧実装は単純な char+index 合計でハッシュ衝突が頻発し、ld.so の
  //   (st_dev, st_ino) ベースの「同一ライブラリ重複ロード抑止」で
  //   無関係なライブラリをロードできなくなる事故 (例:
  //   /lib/libcom_err.so.2 と /lib/libhogweed.so.6 の hash 衝突 →
  //   curl で error_message が解決できない) があった。
  //   String.hashCode() は Java 仕様で「31 進多項式」なので衝突確率が
  //   極めて低く、実用上ユニーク。負値も含むので int ぜんぶを返す。
  private int get_uniq_no( String pathname ) {
    return pathname.hashCode();
  }

  private short get_st_mode( String pathname ) {
    short v = 0;
    // ファイルタイプの解析
    if( file.isDirectory( ) ) {
      v |= (short)__S_IFDIR;
    }
    else if( file.isFile( )) {
      v |= (short)__S_IFREG;
    }

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

  // ファイルが存在しているか？
  public boolean isExists( ) {
    return( file.exists( ));
  }
}
