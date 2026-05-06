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
  }

  // マウントポイントを追加する。
  void add_mountpoint( String _mountpoint, String _nativepath ) {
    MountInfo mountinfo = new MountInfo( _mountpoint, _nativepath );
    mounts.addElement( (Object)mountinfo );
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
      System.exit( 1 );
    }
    ret = ret.replace( native_sep.charAt( 0 ), '/' );
    if( verbose( )) {
      kernel.println( " get_virtual_path( " + _native_path + " )" );
      kernel.println( "   virtual_path = " + ret );
    }
    return( ret );
  }

  // 仮想マウントポイントからNativeパスに変換する
  String get_native_path( String _virtual_path ) {
    int i;
    String ret = null;
    int index;
    int len, no = -1;
    String _root = root;
    if( '<' == _virtual_path.charAt( 0 )) { // 内部ファイルパスなので,なにもしない
      return( _virtual_path );
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
    if( verbose( )) {
      kernel.println( "   native_path( " + no +  " ) = " + ret );
    }
    return ret;
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
