// ----------------------------------------
//  FileSystem Mount Manager
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;

class MountInfo {
  String _virtual;
  String _native;

  MountInfo( String __virtual, String __native ) {
    _virtual = __virtual; // 仮想マウントポイント
    _native  = __native;  // マウントパス
  }
}

// マウントポイントの管理を行う
public class Mount extends RootSysinfo {
  Vector mounts;      // マウントポイント
  String root;        // ルートポイント
  String native_sep;  // そのOSのファイルセパレータ

  Mount( ) {
    mounts = new Vector( );
    native_sep = System.getProperty( "file.separator" );
  }

  // ルートポイントを設定する
  public void set_root( String _root ) {
    int index = 0;
    if( native_sep.charAt( 0 ) == _root.charAt( _root.length( ) -1 )) { index = 1; }
    root = new String( _root.substring( 0, _root.length( ) -index ));
    if( verbose( )) {  kernel.println( " root = " + root );    }
    // issue #369: build 時に case-collision を pre-encode した bundle は rootfs 直下に marker/manifest
    //   `.emulin-casemap` を持つ。検出したら (方式C) まず ASCII payload から PUA 名の本体を Java NIO で配置
    //   (bsdtar は PUA 名を NTFS に作れないので初回起動時に emulin が補完する) し、続いて read 経路の dir
    //   単位 lazy scan を有効化して、直接 open でも encode 名を元名で解決できるようにする (CygSymlink 有効時のみ)。
    if( CygSymlink.enabled() && new java.io.File( root + native_sep + ".emulin-casemap" ).exists() ) {
      WinCaseMap.bootstrapFromPayload( root );
      WinCaseMap.enableReadScan();
    }
  }

  // マウントポイントを追加する。
  void add_mountpoint( String _mountpoint, String _nativepath ) {
    MountInfo mountinfo = new MountInfo( _mountpoint, _nativepath );
    mounts.addElement( (Object)mountinfo );
    CygSymlink.dentryClear();   // issue #495: virtual→native 対応が変わるのでキャッシュ全破棄
  }

  // マウントポイントを追加する。
  void remove_mountpoint( String _name ) {
    int i;
    // mountポイントかnativeパスに _nameがマッチしたらそのエントリを外す
    for( i = 0 ; i < mounts.size( ) ; i++ ) {
      int len;
      MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
      len = _name.indexOf( mountinfo._native );
      if( 0 == len ) {mounts.removeElementAt( i );break;}
      len = _name.indexOf( mountinfo._virtual );
      if( 0 == len ) {mounts.removeElementAt( i );break;}
    }
    CygSymlink.dentryClear();   // issue #495: virtual→native 対応が変わるのでキャッシュ全破棄
  }

  // Nativeパスから仮想パスに変換する
  String get_virtual_path( String _native_path ) {
    int len, no = -1;
    String ret = null;
    String _root = root;
    if( '<' == _native_path.charAt( 0 )) {
      return( _native_path );
    }
    // ルートポイントからのマッチング
    len = _native_path.indexOf( _root );
    // Mountポイントからのマッチング
    if( -1 == len ) {
      int i;
      // mountポイント指定にマッチした場合そのパスを返す。
      for( i = 0 ; i < mounts.size( ) ; i++ ) {
	MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
	len = _native_path.indexOf( mountinfo._native );
	if( 0 == len ) { // マッチした
	  no = i;
	  ret = _native_path.substring( mountinfo._native.length( ));
	  ret = mountinfo._virtual + ret;
	  break;
	}
      }
    } else {
      ret = _native_path.substring( _root.length( ));
    }
    if( -1 == len ) {
      System.err.println( "Emulin error : current path is out of virtual path area [" + _native_path + "]" );
      if( System.getenv("EMULIN_TRACE_VPATH") != null ) new Throwable("vpath-abort").printStackTrace();
      System.exit( 1 );
    }
    ret = ret.replace( native_sep.charAt( 0 ), '/' );
    if( verbose( )) {
      kernel.println( " get_virtual_path( " + _native_path + " )" );
      kernel.println( "   virtual_path = " + ret );
    }
    return( ret );
  }

  // 仮想マウントポイントからNativeパスに変換する。
  //   issue #68: Cygwin 式 symlink マジックファイル mode (CygSymlink.enabled)
  //   では path 中の symlink component を emulin 自身で追従する (host は
  //   マジックファイルを regular file としか見ないため)。最終 component も
  //   追従する (open/stat/access 等の通常 path 解決)。
  //   normal mode (= 非 Windows / force 無し) では resolve_cyg_symlinks が
  //   即 vpath を返すので、従来と完全に同一動作 (no-op)。
  String get_native_path( String _virtual_path ) {
    if( _virtual_path != null && !_virtual_path.isEmpty()
        && _virtual_path.charAt( 0 ) != '<' ) {
      if( CygSymlink.enabled() ) {
        _virtual_path = resolve_cyg_symlinks( _virtual_path, true );
      } else {
        _virtual_path = resolve_real_symlinks( _virtual_path, true );
      }
    }
    return native_path_raw( _virtual_path );
  }

  // symlink 追従なし版 (lstat / readlink / unlink / symlink 作成用)。
  //   最終 component の symlink は追従せず、中間 component のみ追従する。
  String get_native_path_nofollow( String _virtual_path ) {
    if( _virtual_path != null && !_virtual_path.isEmpty()
        && _virtual_path.charAt( 0 ) != '<' ) {
      if( CygSymlink.enabled() ) {
        _virtual_path = resolve_cyg_symlinks( _virtual_path, false );
      } else {
        _virtual_path = resolve_real_symlinks( _virtual_path, false );
      }
    }
    return native_path_raw( _virtual_path );
  }

  // 仮想パス → native パスの素変換 (symlink 解決なし)。
  String native_path_raw( String _virtual_path ) {
    int i;
    String ret = null;
    int index;
    int len, no = -1;
    String _root = root;
    if( _virtual_path.isEmpty() ) { return( _root ); }
    if( '<' == _virtual_path.charAt( 0 )) { // 内部ファイルパスなので,なにもしない
      return( _virtual_path );
    }
    // issue #322: Windows (CygSymlink モード) では NTFS が filename に使えない
    //   予約文字 (: * ? " < > | \) を Cygwin 式に U+F000+c へ encode してから
    //   host path を組み立てる。dpkg multiarch の <pkg>:<arch>.list 等が該当。
    //   path 区切り `/` は encode しない。getdents で d_name を decode して
    //   guest には元の `:` で見せる。Linux (CygSymlink 無効) では no-op。
    if( CygSymlink.enabled() ) {
      _virtual_path = CygSymlink.encodeReservedPath( _virtual_path );
    }
    if( verbose( )) {
      kernel.println( " get_native_path( " + _virtual_path + " )" );
    }
    ret = _root + _virtual_path;
    if( true ) {
      // mountポイント指定にマッチした場合そのパスを返す。
      for( i = 0 ; i < mounts.size( ) ; i++ ) {
	MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
	index = _virtual_path.indexOf( mountinfo._virtual );
	if( 0 == index ) { // マッチした
	  no = i;
	  _root = mountinfo._native;
	  ret = _virtual_path.substring( mountinfo._virtual.length( ));
	  ret = _root + ret;
	  break;
	}
      }
    }
    // ルートポイントをNativeパスに書き換える
    ret = ret.replace( '/', native_sep.charAt( 0 ));
    // issue #349: case 衝突で別名 encode 済みの component を on-disk 名へ置換する。
    //   衝突が一切登録されていなければ no-op (hot path)。
    ret = WinCaseMap.mapPath( ret );
    if( verbose( )) {
      kernel.println( "   native_path( " + no +  " ) = " + ret );
    }
    return ret;
  }

  // issue #68: vpath の各 component を walk し、Cygwin マジックファイル
  // symlink を target に追従する (namei)。followFinal=false なら最終
  // component は追従しない (lstat / readlink / unlink 用)。
  //   絶対 vpath 前提 (emulin は full path で呼ぶ)。ELOOP は 40 回で打ち切り。
  String resolve_cyg_symlinks( String vpath, boolean followFinal ) {
    int[] loops = { 0 };
    return resolve_cyg_rec( vpath, followFinal, loops );
  }

  private String resolve_cyg_rec( String vpath, boolean followFinal, int[] loops ) {
    String[] parts = vpath.split( "/" );
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for( int idx = 0; idx < parts.length; idx++ ) {
      String comp = parts[idx];
      if( comp.isEmpty() || comp.equals( "." ) ) continue;
      if( comp.equals( ".." ) ) {
        if( !out.isEmpty() ) out.remove( out.size() - 1 );
        continue;
      }
      boolean isFinal = ( idx == parts.length - 1 );
      out.add( comp );
      if( isFinal && !followFinal ) break;   // 最終は追従しない
      String cand = "/" + String.join( "/", out );
      String tgt;
      // issue #495: readCached (dentry cache) — 全 component の毎回 probe (NTFS stat+open) が
      //   openat ~765µs の主因だった。無効化は CygSymlink.write / FileAccess.unlink/rename が行う。
      try { tgt = CygSymlink.readCached( native_path_raw( cand ) ); }
      catch( Throwable t ) { tgt = null; }
      if( tgt != null ) {
        if( ++loops[0] > 40 ) break;          // ELOOP guard
        out.remove( out.size() - 1 );          // symlink component を外す
        String base;
        if( tgt.startsWith( "/" ) ) {
          out.clear();
          base = tgt;
        } else {
          base = "/" + String.join( "/", out ) + "/" + tgt;
        }
        // target を解決 (中間として扱うので followFinal=true) して prefix に。
        String resolvedBase = resolve_cyg_rec( base, true, loops );
        out.clear();
        for( String tp : resolvedBase.split( "/" ) ) {
          if( !tp.isEmpty() ) out.add( tp );
        }
      }
    }
    return "/" + String.join( "/", out );
  }

  // issue #322: Linux (非 Cygwin) 版の symlink namei。resolve_cyg_rec と同じロジックだが
  //   Cygwin マジックファイルでなく real host symlink (Files.isSymbolicLink/readSymbolicLink)
  //   を読む。emulin は guest path を「rootfs prefix」だけで host path に変換し、symlink の
  //   追従は host FS に委ねていたが、guest 内で作られた「絶対パスをターゲットにする symlink」
  //   (例 apt staging の /tmp/apt-dpkg-install-XXX/NN-pkg.deb -> /var/cache/apt/archives/pkg.deb)
  //   は host が host 絶対パスと解釈して rootfs を脱出 → ENOENT になる。emulin 自身が namei で
  //   絶対ターゲットを rootfs 下へ再 root する。followFinal=false なら最終 component は追従しない
  //   (lstat / readlink / unlink / symlink 作成用)。絶対 vpath 前提。ELOOP は 40 回で打ち切り。
  String resolve_real_symlinks( String vpath, boolean followFinal ) {
    int[] loops = { 0 };
    return resolve_real_rec( vpath, followFinal, loops );
  }

  private String resolve_real_rec( String vpath, boolean followFinal, int[] loops ) {
    String[] parts = vpath.split( "/" );
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for( int idx = 0; idx < parts.length; idx++ ) {
      String comp = parts[idx];
      if( comp.isEmpty() || comp.equals( "." ) ) continue;
      if( comp.equals( ".." ) ) {
        if( !out.isEmpty() ) out.remove( out.size() - 1 );
        continue;
      }
      boolean isFinal = ( idx == parts.length - 1 );
      out.add( comp );
      if( isFinal && !followFinal ) break;   // 最終は追従しない
      String cand = "/" + String.join( "/", out );
      String tgt = null;
      try {
        java.nio.file.Path hp = java.nio.file.Paths.get( native_path_raw( cand ) );
        if( java.nio.file.Files.isSymbolicLink( hp ) ) {
          tgt = java.nio.file.Files.readSymbolicLink( hp ).toString();
          // Windows host で readSymbolicLink が \ 区切りを返す場合に備えて正規化
          tgt = tgt.replace( '\\', '/' );
        }
      } catch( Throwable t ) { tgt = null; }
      if( tgt != null ) {
        if( ++loops[0] > 40 ) break;          // ELOOP guard
        out.remove( out.size() - 1 );          // symlink component を外す
        String base;
        if( tgt.startsWith( "/" ) ) {
          out.clear();
          base = tgt;
        } else {
          base = "/" + String.join( "/", out ) + "/" + tgt;
        }
        // target を解決 (中間として扱うので followFinal=true) して prefix に。
        String resolvedBase = resolve_real_rec( base, true, loops );
        out.clear();
        for( String tp : resolvedBase.split( "/" ) ) {
          if( !tp.isEmpty() ) out.add( tp );
        }
      }
    }
    return "/" + String.join( "/", out );
  }

  // フルパス名を返す
  //   _curdir は仮想パス
  public String get_full_path( String _curdir, String name ) {
    if( 0 == name.length( )) {
      name = _curdir;
      return( name );
    }
    if( '<' == name.charAt( 0 )) {
      return( name );
    }
    if( verbose( )) {
      kernel.println( " get_full_path( " + name + " )" );
    }
    if( '/' != name.charAt( 0 )) {
      name = _curdir + "/" + name;
    }
    if( verbose( )) {
      kernel.println( "   full_path = " + name );
    }
    name = Util.realname( name );
    return( name );
  }
}
